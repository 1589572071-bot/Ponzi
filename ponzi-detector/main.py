"""PonziShield API — Sealos / local demo (PRD v1.1 endpoints)."""

from __future__ import annotations

from collections import defaultdict
from datetime import datetime, timezone
from typing import Any

import networkx as nx
from fastapi import FastAPI
from fastapi.responses import RedirectResponse
from pydantic import BaseModel, Field

app = FastAPI(
    title="PonziShield API",
    description="Embedded Ponzi detection — transfer graph + lifecycle scoring (demo)",
    version="1.0.0",
)

# In-memory store (demo); replace with persistent store in production.
_transfers: list[dict[str, Any]] = []
_graph = nx.DiGraph()


class TransferEvent(BaseModel):
    from_address: str = Field(alias="from")
    to: str
    value: str
    block_number: int
    tx_hash: str
    timestamp: int

    model_config = {"populate_by_name": True}


class AnalyzeRequest(BaseModel):
    contract_address: str
    current_block: int = 0


def _add_transfer(event: dict[str, Any]) -> None:
    _transfers.append(event)
    src, dst = event["from"], event["to"]
    weight = float(event.get("value", 0) or 0)
    if not _graph.has_node(src):
        _graph.add_node(src)
    if not _graph.has_node(dst):
        _graph.add_node(dst)
    if _graph.has_edge(src, dst):
        _graph[src][dst]["weight"] += weight
        _graph[src][dst]["count"] += 1
    else:
        _graph.add_edge(src, dst, weight=weight, count=1)


def _extract_subgraph(center: str, hops: int = 1) -> nx.DiGraph:
    if center not in _graph:
        return nx.DiGraph()
    nodes = {center}
    frontier = {center}
    for _ in range(hops):
        nxt: set[str] = set()
        for n in frontier:
            nxt.update(_graph.predecessors(n))
            nxt.update(_graph.successors(n))
        nodes.update(nxt)
        frontier = nxt
    return _graph.subgraph(nodes).copy()


def _detect_intermediaries(sub: nx.DiGraph, contract: str) -> list[dict[str, Any]]:
    if sub.number_of_nodes() == 0:
        return []
    bc = nx.betweenness_centrality(sub)
    out: list[dict[str, Any]] = []
    for node in sub.nodes:
        if node == contract:
            continue
        in_d = sub.in_degree(node)
        out_d = sub.out_degree(node)
        score = bc.get(node, 0.0)
        hits = sum([
            score > 0.05,
            in_d >= 3 and out_d >= 2,
            in_d > 0 and out_d > 0 and (in_d / max(out_d, 1)) > 2.0,
        ])
        if hits >= 2:
            role = "RELAY" if score > 0.1 else "DISTRIBUTOR"
            out.append({
                "address": node,
                "role": role,
                "betweenness": round(score, 4),
                "in_degree": in_d,
                "out_degree": out_d,
            })
    return out


def _lifecycle_score(contract: str, current_block: int) -> dict[str, Any]:
    related = [t for t in _transfers if t["to"] == contract or t["from"] == contract]
    in_count = sum(1 for t in related if t["to"] == contract)
    out_count = sum(1 for t in related if t["from"] == contract)
    unique_senders = len({t["from"] for t in related if t["to"] == contract})

    fund_flow = in_count >= 2 and out_count >= 1
    referral = unique_senders >= 3
    profit_logic = in_count > out_count and in_count >= 3
    withdrawal_control = out_count >= 1 and in_count >= 5
    camouflage = False  # set true when Java PonziContract stake() events arrive

    if current_block <= 100:
        stage = "FUNDRAISING"
        score = (0.4 * int(referral) + 0.3 * min(1.0, unique_senders / 5) + 0.3 * int(fund_flow))
    elif current_block <= 500:
        stage = "PAYOUT"
        score = (0.5 * int(fund_flow) + 0.3 * int(profit_logic) + 0.2 * int(referral))
    elif current_block <= 1000:
        stage = "STAGNATION"
        score = (0.4 * int(withdrawal_control) + 0.3 * int(fund_flow) + 0.3 * min(1.0, out_count / 10))
    else:
        stage = "COLLAPSE"
        score = min(1.0, out_count / max(in_count, 1))

    return {
        "stage": stage,
        "age_blocks": current_block,
        "score": round(score, 4),
        "dimensions": {
            "fund_flow": {"detected": fund_flow, "evidence": f"in={in_count}, out={out_count}"},
            "profit_logic": {"detected": profit_logic, "evidence": "inflow exceeds outflow pattern"},
            "referral_mechanism": {"detected": referral, "evidence": f"unique_senders={unique_senders}"},
            "withdrawal_control": {"detected": withdrawal_control, "evidence": "withdrawal activity present"},
            "camouflage": {"detected": camouflage, "evidence": ""},
        },
    }


@app.get("/")
def root():
    return {
        "service": "PonziShield",
        "status": "running",
        "docs": "/docs",
        "health": "/api/v1/health",
        "transfers_recorded": len(_transfers),
    }


@app.get("/api")
def api_index():
    return RedirectResponse(url="/docs")


@app.get("/api/v1/health")
def health():
    return {
        "status": "ok",
        "transfers": len(_transfers),
        "graph_nodes": _graph.number_of_nodes(),
        "graph_edges": _graph.number_of_edges(),
    }


@app.post("/api/v1/transfer", status_code=202)
def ingest_transfer(event: TransferEvent):
    payload = {
        "from": event.from_address,
        "to": event.to,
        "value": event.value,
        "block_number": event.block_number,
        "tx_hash": event.tx_hash,
        "timestamp": event.timestamp,
    }
    _add_transfer(payload)
    return {"accepted": True, "total_transfers": len(_transfers)}


@app.post("/api/v1/analyze")
def analyze(body: AnalyzeRequest):
    contract = body.contract_address.lower()
    sub = _extract_subgraph(body.contract_address, hops=1)
    intermediaries = _detect_intermediaries(sub, body.contract_address)
    lifecycle = _lifecycle_score(body.contract_address, body.current_block)

    # Graph channel: heuristic until ethXpose model is wired.
    p_graph = min(1.0, sub.number_of_edges() / 10.0) if sub.number_of_edges() else 0.0
    if lifecycle["score"] > 0.5:
        p_graph = max(p_graph, 0.6)

    intermediary_factor = min(1.0, len(intermediaries) / 3.0)
    w1, w2, w3 = 0.45, 0.35, 0.20
    risk_raw = w1 * p_graph + w2 * lifecycle["score"] + w3 * intermediary_factor
    risk_score = round(risk_raw * 100, 1)
    if risk_score >= 70:
        level = "HIGH"
    elif risk_score >= 40:
        level = "MEDIUM"
    else:
        level = "LOG"

    return {
        "contract_address": body.contract_address,
        "risk_score": risk_score,
        "risk_level": level,
        "lifecycle": lifecycle,
        "graph_analysis": {
            "p_graph": round(p_graph, 4),
            "node_count": sub.number_of_nodes(),
            "edge_count": sub.number_of_edges(),
        },
        "intermediaries": intermediaries,
        "weights": {"w1": w1, "w6": w2, "w3": w3},
        "analyzed_at": datetime.now(timezone.utc).isoformat(),
    }


# ethXpose-compatible health (legacy path)
@app.get("/api/health")
def legacy_health():
    return {"status": "ok"}
