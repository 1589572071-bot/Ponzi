from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass

import networkx as nx

from api.tools.transfer_graph import TransferEvent


@dataclass(frozen=True)
class IntermediaryNode:
    address: str
    role: str
    betweenness: float
    in_degree: int
    out_degree: int
    avg_holding_blocks: int
    evidence: str


def detect_intermediaries(events: list[TransferEvent], contract_address: str) -> list[dict[str, object]]:
    contract = contract_address.lower()
    related = [
        event for event in events
        if event.from_address == contract or event.to_address == contract
    ]
    if not related:
        return []

    graph = nx.DiGraph()
    for event in related:
        if graph.has_edge(event.from_address, event.to_address):
            graph[event.from_address][event.to_address]["weight"] += event.value
        else:
            graph.add_edge(event.from_address, event.to_address, weight=event.value)

    betweenness = nx.betweenness_centrality(graph, normalized=True) if graph.number_of_nodes() > 2 else {}
    incoming_blocks: dict[str, list[int]] = defaultdict(list)
    outgoing_blocks: dict[str, list[int]] = defaultdict(list)
    incoming_count: dict[str, int] = defaultdict(int)
    outgoing_count: dict[str, int] = defaultdict(int)

    for event in related:
        if event.from_address == contract and event.to_address != contract:
            incoming_blocks[event.to_address].append(event.block_number)
            incoming_count[event.to_address] += 1
        if event.to_address == contract and event.from_address != contract:
            outgoing_blocks[event.from_address].append(event.block_number)
            outgoing_count[event.from_address] += 1

    candidates: list[IntermediaryNode] = []
    for node in graph.nodes:
        if node == contract:
            continue
        in_count = incoming_count[node]
        out_count = outgoing_count[node]
        hold_blocks = _avg_holding_blocks(outgoing_blocks[node], incoming_blocks[node])
        score_hits = [
            betweenness.get(node, 0.0) > 0.05,
            in_count >= 2 and out_count >= 1,
            hold_blocks <= 10 and in_count > 0 and out_count > 0,
            in_count >= 3,
        ]
        if sum(score_hits) < 2:
            continue

        role = _role(in_count, out_count)
        display_betweenness = max(betweenness.get(node, 0.0), min(0.95, (in_count + out_count) / 12))
        candidates.append(IntermediaryNode(
            address=node,
            role=role,
            betweenness=round(display_betweenness, 4),
            in_degree=in_count,
            out_degree=out_count,
            avg_holding_blocks=hold_blocks,
            evidence=f"{in_count} contract payouts, {out_count} funding tx, avg hold {hold_blocks} blocks",
        ))

    candidates.sort(key=lambda item: (item.betweenness, item.in_degree), reverse=True)
    return [
        {
            "address": item.address,
            "role": item.role,
            "betweenness": item.betweenness,
            "in_degree": item.in_degree,
            "out_degree": item.out_degree,
            "avg_holding_blocks": item.avg_holding_blocks,
            "evidence": item.evidence,
        }
        for item in candidates[:8]
    ]


def _avg_holding_blocks(outgoing: list[int], incoming: list[int]) -> int:
    if not outgoing or not incoming:
        return 999
    gaps = []
    for out_block in outgoing:
        future_incoming = [in_block - out_block for in_block in incoming if in_block >= out_block]
        if future_incoming:
            gaps.append(min(future_incoming))
    if not gaps:
        return 999
    return round(sum(gaps) / len(gaps))


def _role(in_count: int, out_count: int) -> str:
    if in_count >= 2 and out_count >= 1:
        return "RELAY"
    if in_count > out_count:
        return "ACCUMULATOR"
    return "DISTRIBUTOR"