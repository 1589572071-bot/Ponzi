"""Unit tests for the enhanced risk fusion layer.

Runs without pytest:  python tests/test_risk_fusion.py
Also discoverable by pytest if it is installed.
"""

from __future__ import annotations

import sys
from pathlib import Path

# Make the ``api`` package importable regardless of the working directory.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from api.tools.risk_fusion import (  # noqa: E402
    COLLAPSE_FLOOR,
    WEIGHTS,
    fuse_risk,
)


def _graph(p_graph: float, tx_volume: float = 1.0, evidence=None) -> dict:
    return {
        "p_graph": p_graph,
        "features": {"tx_count_norm": tx_volume},
        "evidence": evidence or ["high-density 2-hop subgraph (0.91)"],
    }


def _lifecycle(stage: str, score: float, *, withdrawal: bool = False, hits: int = 3) -> dict:
    names = [
        "fund_flow",
        "profit_logic",
        "referral_mechanism",
        "withdrawal_control",
        "camouflage",
    ]
    dimensions = {}
    for index, name in enumerate(names):
        detected = index < hits
        if name == "withdrawal_control":
            detected = withdrawal
        dimensions[name] = {
            "detected": detected,
            "score": 0.8 if detected else 0.12,
            "evidence": f"{name} evidence",
        }
    return {"stage": stage, "score": score, "age_blocks": 120, "dimensions": dimensions}


def _intermediaries(count: int) -> list[dict]:
    return [
        {
            "address": f"0x{index:040x}",
            "role": "RELAY",
            "evidence": f"node {index}",
        }
        for index in range(count)
    ]


def test_backward_compatible_keys_and_baseline():
    # PAYOUT triggers no calibration, so the score equals the linear baseline.
    result = fuse_risk(_graph(0.9), _lifecycle("PAYOUT", 0.8), _intermediaries(3))
    for key in ("risk_score", "risk_level", "weights", "intermediary_factor"):
        assert key in result, f"missing backward-compatible key: {key}"
    assert result["weights"] == WEIGHTS
    assert set(result["weights"]) == {"w1", "w2", "w3"}
    # 0.45*0.9 + 0.35*0.8 + 0.20*1.0 = 0.885 -> 88.5
    assert result["base_score"] == 88.5
    assert result["risk_score"] == 88.5
    assert result["risk_level"] == "HIGH"
    assert result["calibration"]["applied"] is False


def test_contributions_decompose_base_score():
    result = fuse_risk(_graph(0.9), _lifecycle("PAYOUT", 0.8), _intermediaries(3))
    total = sum(item["contribution"] for item in result["contributions"])
    assert abs(total - result["base_score"]) < 0.1
    assert result["dominant_channel"] == "graph"  # 40.5 > 28.0 > 20.0


def test_collapse_floors_to_high_even_with_weak_graph():
    # Weak topology, low lifecycle score, no intermediaries -> tiny linear baseline...
    result = fuse_risk(
        _graph(0.1, tx_volume=0.2),
        _lifecycle("COLLAPSE", 0.3, withdrawal=True),
        _intermediaries(0),
    )
    assert result["base_score"] < 40
    assert result["risk_score"] == COLLAPSE_FLOOR
    assert result["risk_level"] == "HIGH"
    rules = [adj["rule"] for adj in result["calibration"]["adjustments"]]
    assert "collapse_floor" in rules


def test_stagnation_floor_requires_withdrawal_signal():
    result = fuse_risk(
        _graph(0.2, tx_volume=0.4),
        _lifecycle("STAGNATION", 0.3, withdrawal=True),
        _intermediaries(1),
    )
    assert result["risk_score"] >= 55.0
    assert "stagnation_floor" in [adj["rule"] for adj in result["calibration"]["adjustments"]]


def test_fundraising_damps_graph_only_signal():
    # High p_graph but no payouts yet and low lifecycle score -> damp to cut false positives.
    result = fuse_risk(
        _graph(0.8),
        _lifecycle("FUNDRAISING", 0.2, hits=1),
        _intermediaries(1),
    )
    assert result["risk_score"] < result["base_score"]
    assert "fundraising_damping" in [adj["rule"] for adj in result["calibration"]["adjustments"]]


def test_confidence_high_when_data_rich_and_channels_agree():
    result = fuse_risk(_graph(0.85, tx_volume=1.0), _lifecycle("PAYOUT", 0.85, hits=5), _intermediaries(3))
    assert result["confidence"] >= 0.66
    assert result["confidence_level"] == "HIGH"


def test_confidence_low_when_data_sparse_and_channels_diverge():
    # Strong graph but everything else empty -> sparse data + large signal spread.
    result = fuse_risk(
        _graph(0.95, tx_volume=0.0),
        _lifecycle("FUNDRAISING", 0.0, hits=0),
        _intermediaries(0),
    )
    assert result["confidence"] < 0.4
    assert result["confidence_level"] == "LOW"


def test_band_thresholds():
    from api.tools.risk_fusion import _band

    assert _band(70.0) == "HIGH"
    assert _band(69.9) == "MEDIUM"
    assert _band(40.0) == "MEDIUM"
    assert _band(39.9) == "LOW"


def _run() -> int:
    tests = [value for name, value in sorted(globals().items()) if name.startswith("test_")]
    failures = 0
    for test in tests:
        try:
            test()
            print(f"PASS  {test.__name__}")
        except AssertionError as error:
            failures += 1
            print(f"FAIL  {test.__name__}: {error}")
        except Exception as error:  # noqa: BLE001
            failures += 1
            print(f"ERROR {test.__name__}: {type(error).__name__}: {error}")
    print(f"\n{len(tests) - failures}/{len(tests)} passed")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(_run())
