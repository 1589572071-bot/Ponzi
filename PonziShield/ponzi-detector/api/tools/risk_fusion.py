"""Risk fusion layer.

Combines the three detection channels (graph classifier, lifecycle scorer and
intermediary detector) into a single, explainable risk verdict.

Beyond the baseline weighted sum this layer adds three capabilities that a plain
linear blend cannot provide:

1. Per-channel contribution decomposition, so every point of the 0-100 score can
   be traced back to the channel that produced it.
2. Stage-aware calibration, which corrects the static linear sum using the
   lifecycle stage. A contract that keeps paying out after inflows stop
   (COLLAPSE) is floored to HIGH even when its graph features have decayed, while
   a contract that merely *looks* Ponzi-like but has not paid anyone yet
   (FUNDRAISING) is damped to suppress early-stage false positives.
3. A confidence estimate derived from data sufficiency and inter-channel
   agreement, so downstream consumers know how much to trust the verdict.
"""

from __future__ import annotations

from typing import Any


# Base fusion weights for the three channels (graph / lifecycle / intermediary).
WEIGHTS = {"w1": 0.45, "w2": 0.35, "w3": 0.20}

# Risk band thresholds on the 0-100 scale.
HIGH_THRESHOLD = 70.0
MEDIUM_THRESHOLD = 40.0

# Stage-conditional calibration parameters.
COLLAPSE_FLOOR = 75.0      # a confirmed collapse cannot be rated below HIGH
STAGNATION_FLOOR = 55.0    # stagnation is a high-risk transition phase
FUNDRAISING_DAMP = 0.85    # multiplier applied to a graph-only signal in early fundraising


def fuse_risk(
    graph_prediction: dict[str, Any],
    lifecycle: dict[str, Any],
    intermediaries: list[dict[str, Any]],
) -> dict[str, object]:
    """Fuse the three channel outputs into a calibrated, explainable risk verdict.

    Args:
        graph_prediction: output of ``classify_graph`` (uses ``p_graph``,
            ``features`` and ``evidence``).
        lifecycle: output of ``score_lifecycle`` (uses ``score``, ``stage`` and
            ``dimensions``).
        intermediaries: output of ``detect_intermediaries`` (a list of nodes).

    Returns:
        A dict that is backward compatible with the original implementation
        (``risk_score``, ``risk_level``, ``weights``, ``intermediary_factor``)
        and additionally exposes the contribution breakdown, calibration trace
        and confidence estimate.
    """
    p_graph = _clamp01(float(graph_prediction.get("p_graph", 0.0)))
    lifecycle_score = _clamp01(float(lifecycle.get("score", 0.0)))
    stage = str(lifecycle.get("stage", "FUNDRAISING"))
    intermediary_count = len(intermediaries)
    intermediary_factor = min(1.0, intermediary_count / 3)

    # 1. Baseline linear fusion — preserved unchanged for comparability.
    base_raw = (
        WEIGHTS["w1"] * p_graph
        + WEIGHTS["w2"] * lifecycle_score
        + WEIGHTS["w3"] * intermediary_factor
    )
    base_score = round(base_raw * 100, 1)

    # 2. Per-channel contribution decomposition (drives the gauge / weight bars).
    contributions = [
        _contribution(
            "graph",
            "Graph classifier (p_graph)",
            WEIGHTS["w1"],
            p_graph,
            _as_evidence(graph_prediction.get("evidence")),
        ),
        _contribution(
            "lifecycle",
            f"Lifecycle scorer ({stage})",
            WEIGHTS["w2"],
            lifecycle_score,
            _lifecycle_evidence(lifecycle),
        ),
        _contribution(
            "intermediary",
            "Intermediary factor",
            WEIGHTS["w3"],
            intermediary_factor,
            _intermediary_evidence(intermediaries),
        ),
    ]
    dominant = max(contributions, key=lambda item: item["contribution"])

    # 3. Stage-aware calibration — turn the static linear sum into a behaviour-aware verdict.
    calibrated_score, calibration = _calibrate(base_score, stage, lifecycle, p_graph)

    # 4. Confidence — data sufficiency x inter-channel agreement.
    confidence, confidence_level, confidence_factors = _confidence(
        graph_prediction,
        lifecycle,
        intermediaries,
        [p_graph, lifecycle_score, intermediary_factor],
    )

    risk_score = round(calibrated_score, 1)
    risk_level = _band(risk_score)

    return {
        # --- backward-compatible fields ---
        "risk_score": risk_score,
        "risk_level": risk_level,
        "weights": WEIGHTS,
        "intermediary_factor": round(intermediary_factor, 4),
        # --- enhanced, additive fields ---
        "base_score": base_score,
        "contributions": contributions,
        "dominant_channel": dominant["channel"],
        "calibration": calibration,
        "confidence": confidence,
        "confidence_level": confidence_level,
        "confidence_factors": confidence_factors,
        "reasons": _reasons(dominant, calibration, contributions),
        "summary": _summary(risk_score, risk_level, stage, dominant, confidence_level),
    }


def _calibrate(
    base_score: float,
    stage: str,
    lifecycle: dict[str, Any],
    p_graph: float,
) -> tuple[float, dict[str, object]]:
    """Apply stage-conditional corrections to the linear base score."""
    adjustments: list[dict[str, object]] = []
    score = base_score
    dimensions = lifecycle.get("dimensions", {})
    withdrawal_detected = bool(_dim(dimensions, "withdrawal_control").get("detected"))

    if stage == "COLLAPSE":
        # Payouts continue long after inflows ceased: the graph channel decays here,
        # so the linear sum can underrate a scheme that is, by construction, terminal.
        if score < COLLAPSE_FLOOR:
            adjustments.append({
                "rule": "collapse_floor",
                "delta": round(COLLAPSE_FLOOR - score, 1),
                "reason": (
                    "Stage=COLLAPSE: payouts persist after inflows stopped; risk floored "
                    "to HIGH even though static graph features have decayed."
                ),
            })
            score = COLLAPSE_FLOOR
    elif stage == "STAGNATION":
        # Inflows have dried up while withdrawals persist — a high-risk transition.
        if withdrawal_detected and score < STAGNATION_FLOOR:
            adjustments.append({
                "rule": "stagnation_floor",
                "delta": round(STAGNATION_FLOOR - score, 1),
                "reason": (
                    "Stage=STAGNATION: inflows dried up while withdrawals continue; "
                    "raised to the MEDIUM-HIGH transition band."
                ),
            })
            score = STAGNATION_FLOOR
    elif stage == "FUNDRAISING":
        # No payout has happened yet, so a high p_graph alone is weak evidence this early.
        # Damp the score to reduce early-stage false positives.
        if p_graph > 0.5 and lifecycle_score_of(lifecycle) < 0.4:
            damped = round(score * FUNDRAISING_DAMP, 1)
            adjustments.append({
                "rule": "fundraising_damping",
                "delta": round(damped - score, 1),
                "reason": (
                    "Stage=FUNDRAISING: topology looks Ponzi-like but no payouts have "
                    "occurred; graph-only signal damped to cut early-stage false positives."
                ),
            })
            score = damped

    score = max(0.0, min(100.0, score))
    return score, {
        "stage": stage,
        "applied": bool(adjustments),
        "adjustments": adjustments,
        "base_score": base_score,
        "calibrated_score": round(score, 1),
    }


def _confidence(
    graph_prediction: dict[str, Any],
    lifecycle: dict[str, Any],
    intermediaries: list[dict[str, Any]],
    signals: list[float],
) -> tuple[float, str, dict[str, object]]:
    """Estimate verdict confidence from data sufficiency and channel agreement."""
    features = graph_prediction.get("features", {})
    tx_volume = _clamp01(float(features.get("tx_count_norm", 0.0)))

    dimensions = lifecycle.get("dimensions", {})
    dim_hits = sum(1 for dim in dimensions.values() if isinstance(dim, dict) and dim.get("detected"))
    lifecycle_support = min(1.0, dim_hits / 5)
    intermediary_support = min(1.0, len(intermediaries) / 3)

    data_sufficiency = round(
        0.5 * tx_volume + 0.3 * lifecycle_support + 0.2 * intermediary_support,
        4,
    )

    # Spread of the three normalised signals: 0 = full agreement, 1 = maximal divergence.
    spread = max(signals) - min(signals) if signals else 0.0
    agreement = round(1 - spread, 4)

    confidence = round(0.55 * data_sufficiency + 0.45 * agreement, 4)
    if confidence >= 0.66:
        level = "HIGH"
    elif confidence >= 0.4:
        level = "MEDIUM"
    else:
        level = "LOW"

    factors = {
        "data_sufficiency": data_sufficiency,
        "channel_agreement": agreement,
        "tx_volume": round(tx_volume, 4),
        "lifecycle_support": round(lifecycle_support, 4),
        "intermediary_support": round(intermediary_support, 4),
        "signal_spread": round(spread, 4),
    }
    return confidence, level, factors


def _contribution(
    channel: str,
    label: str,
    weight: float,
    signal: float,
    evidence: list[str],
) -> dict[str, object]:
    return {
        "channel": channel,
        "label": label,
        "weight": weight,
        "signal": round(signal, 4),
        "contribution": round(weight * signal * 100, 1),
        "evidence": evidence[:3],
    }


def _lifecycle_evidence(lifecycle: dict[str, Any]) -> list[str]:
    dimensions = lifecycle.get("dimensions", {})
    evidence: list[str] = []
    for name, dim in dimensions.items():
        if isinstance(dim, dict) and dim.get("detected") and dim.get("evidence"):
            evidence.append(f"{name}: {dim['evidence']}")
    return evidence


def _intermediary_evidence(intermediaries: list[dict[str, Any]]) -> list[str]:
    evidence: list[str] = []
    for node in intermediaries[:3]:
        address = str(node.get("address", ""))
        role = str(node.get("role", ""))
        detail = str(node.get("evidence", ""))
        short = f"{address[:10]}…" if len(address) > 10 else address
        evidence.append(f"{role} {short} ({detail})" if detail else f"{role} {short}")
    return evidence


def _reasons(
    dominant: dict[str, object],
    calibration: dict[str, object],
    contributions: list[dict[str, object]],
) -> list[str]:
    reasons: list[str] = [
        f"Dominant channel: {dominant['label']} (+{dominant['contribution']} pts)",
    ]
    for contribution in contributions:
        channel_evidence = contribution.get("evidence") or []
        if channel_evidence:
            reasons.append(str(channel_evidence[0]))
    for adjustment in calibration.get("adjustments", []):  # type: ignore[union-attr]
        reasons.append(str(adjustment["reason"]))
    return reasons[:6]


def _summary(
    risk_score: float,
    risk_level: str,
    stage: str,
    dominant: dict[str, object],
    confidence_level: str,
) -> str:
    return (
        f"{risk_level} risk {risk_score}/100 at stage {stage}; "
        f"driven by {dominant['label']}; confidence {confidence_level}."
    )


def _band(score: float) -> str:
    if score >= HIGH_THRESHOLD:
        return "HIGH"
    if score >= MEDIUM_THRESHOLD:
        return "MEDIUM"
    return "LOW"


def lifecycle_score_of(lifecycle: dict[str, Any]) -> float:
    return _clamp01(float(lifecycle.get("score", 0.0)))


def _dim(dimensions: dict[str, Any], name: str) -> dict[str, Any]:
    dim = dimensions.get(name, {})
    return dim if isinstance(dim, dict) else {}


def _as_evidence(value: object) -> list[str]:
    if isinstance(value, list):
        return [str(item) for item in value]
    return []


def _clamp01(value: float) -> float:
    return max(0.0, min(1.0, value))
