from __future__ import annotations

from collections import Counter, defaultdict
import json
import math
from pathlib import Path
from typing import Any

import networkx as nx

from api.tools.transfer_graph import TransferEvent


MODEL_PATH = Path(__file__).resolve().parents[1] / "models" / "graph_classifier_v1.json"
DEFAULT_FEATURES = [
    "tx_count_norm",
    "fan_in_norm",
    "fan_out_norm",
    "in_out_balance",
    "same_block_payout_rate",
    "recycling_ratio",
    "payout_ratio",
    "degree_centralization",
    "temporal_burst",
    "value_concentration",
    "neighborhood_density",
    "reciprocity_rate",
]


def classify_graph(events: list[TransferEvent], contract_address: str) -> dict[str, Any]:
    model = _load_model()
    contract = contract_address.lower()
    related = contract_neighborhood_events(events, contract, hop=2)
    features = extract_graph_features(related, contract)
    logit = float(model["intercept"]) + sum(
        float(model["weights"].get(name, 0.0)) * value
        for name, value in features.items()
    )
    probability = 1 / (1 + math.exp(-logit))
    return {
        "p_graph": round(probability, 4),
        "model_name": model["model_name"],
        "model_version": model["model_version"],
        "model_type": model["model_type"],
        "feature_count": len(features),
        "calibration": model.get("calibration", {}),
        "features": {name: round(value, 4) for name, value in features.items()},
        "evidence": _top_evidence(features, model["weights"]),
    }


def extract_graph_features(events: list[TransferEvent], contract: str) -> dict[str, float]:
    if not events:
        return {name: 0.0 for name in DEFAULT_FEATURES}

    inbound = [event for event in events if event.to_address == contract]
    outbound = [event for event in events if event.from_address == contract]
    inbound_senders = {event.from_address for event in inbound}
    outbound_receivers = {event.to_address for event in outbound}
    total_in = sum(event.value for event in inbound)
    total_out = sum(event.value for event in outbound)
    graph = nx.DiGraph()
    for event in events:
        graph.add_edge(event.from_address, event.to_address, weight=event.value)
    undirected = graph.to_undirected()

    same_block_payouts = [
        event
        for event in outbound
        if any(in_event.block_number == event.block_number for in_event in inbound)
    ]
    recycled_payouts = _recycled_payouts(inbound, outbound)
    block_span = max((event.block_number for event in events), default=0) - min(
        (event.block_number for event in events),
        default=0,
    )
    block_counts = Counter(event.block_number for event in events)
    max_block_tx = max(block_counts.values(), default=0)
    possible_edges = max(1, graph.number_of_nodes() * (graph.number_of_nodes() - 1))
    centrality = graph.degree(contract) / possible_edges if contract in graph else 0.0
    density = graph.number_of_edges() / possible_edges
    reciprocal_pairs = sum(
        1
        for source, target in graph.edges()
        if source != target and graph.has_edge(target, source)
    )
    counterparty_value = defaultdict(int)
    for event in inbound:
        counterparty_value[event.from_address] += event.value
    for event in outbound:
        counterparty_value[event.to_address] += event.value
    total_value = sum(counterparty_value.values())

    fan_delta = abs(len(inbound_senders) - len(outbound_receivers))
    fan_total = max(1, len(inbound_senders) + len(outbound_receivers))
    return {
        "tx_count_norm": min(1.0, len(events) / 24),
        "fan_in_norm": min(1.0, len(inbound_senders) / 10),
        "fan_out_norm": min(1.0, len(outbound_receivers) / 10),
        "in_out_balance": 1 - min(1.0, fan_delta / fan_total),
        "same_block_payout_rate": len(same_block_payouts) / max(1, len(outbound)),
        "recycling_ratio": len(recycled_payouts) / max(1, len(outbound)),
        "payout_ratio": min(1.0, total_out / max(1, total_in)),
        "degree_centralization": min(1.0, centrality * 4),
        "temporal_burst": min(1.0, max_block_tx / max(1, block_span + 1)),
        "value_concentration": (
            min(1.0, max(counterparty_value.values(), default=0) / total_value)
            if total_value
            else 0.0
        ),
        "neighborhood_density": min(1.0, density * 8),
        "reciprocity_rate": min(1.0, reciprocal_pairs / max(1, undirected.number_of_edges())),
    }


def contract_neighborhood_events(
    events: list[TransferEvent],
    contract: str,
    hop: int,
) -> list[TransferEvent]:
    graph = nx.Graph()
    for event in events:
        graph.add_edge(event.from_address, event.to_address)
    if contract not in graph:
        return []

    selected = {contract}
    frontier = {contract}
    for _ in range(max(1, hop)):
        neighbors = set()
        for node in frontier:
            neighbors.update(graph.neighbors(node))
        selected.update(neighbors)
        frontier = neighbors

    return [
        event
        for event in events
        if event.from_address in selected and event.to_address in selected
    ]


def _recycled_payouts(
    inbound: list[TransferEvent],
    outbound: list[TransferEvent],
) -> list[TransferEvent]:
    if not inbound or not outbound:
        return []
    inbound_blocks = [event.block_number for event in inbound]
    return [
        payout
        for payout in outbound
        if any(0 <= payout.block_number - block <= 2 for block in inbound_blocks)
    ]


def _top_evidence(features: dict[str, float], weights: dict[str, float]) -> list[str]:
    labels = {
        "tx_count_norm": "交易规模达到图模型有效区间",
        "fan_in_norm": "多个外部地址向合约注资",
        "fan_out_norm": "合约向多个地址分发资金",
        "in_out_balance": "注资与分发两侧结构相对均衡",
        "same_block_payout_rate": "新资金进入后同区块出现 payout",
        "recycling_ratio": "短窗口内观察到资金回流/再分发",
        "payout_ratio": "合约流出金额接近或覆盖流入金额",
        "degree_centralization": "合约在交易图中呈中心化枢纽",
        "temporal_burst": "交易在短区块窗口内集中爆发",
        "value_concentration": "资金集中流向少数核心地址",
        "neighborhood_density": "2-hop 邻域形成高密度交易子图",
        "reciprocity_rate": "邻域内存在双向资金往返",
    }
    ranked = sorted(
        features,
        key=lambda name: features[name] * float(weights.get(name, 0.0)),
        reverse=True,
    )
    return [
        f"{labels[name]} ({features[name]:.2f})"
        for name in ranked[:3]
        if features[name] > 0
    ]


def _load_model() -> dict[str, Any]:
    with MODEL_PATH.open(encoding="utf-8") as file:
        return json.load(file)
