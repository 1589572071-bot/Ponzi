from __future__ import annotations


WEIGHTS = {"w1": 0.45, "w2": 0.35, "w3": 0.20}


def fuse_risk(p_graph: float, lifecycle_score: float, intermediary_count: int) -> dict[str, object]:
    intermediary_factor = min(1.0, intermediary_count / 3)
    raw = (
        WEIGHTS["w1"] * p_graph
        + WEIGHTS["w2"] * lifecycle_score
        + WEIGHTS["w3"] * intermediary_factor
    )
    risk_score = round(raw * 100, 1)
    if risk_score >= 70:
        risk_level = "HIGH"
    elif risk_score >= 40:
        risk_level = "MEDIUM"
    else:
        risk_level = "LOW"
    return {
        "risk_score": risk_score,
        "risk_level": risk_level,
        "weights": WEIGHTS,
        "intermediary_factor": intermediary_factor,
    }