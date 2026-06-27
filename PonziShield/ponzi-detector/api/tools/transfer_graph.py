from __future__ import annotations

from dataclasses import dataclass
import math
from threading import Lock
from typing import Any

import networkx as nx


@dataclass(frozen=True)
class TransferEvent:
    from_address: str
    to_address: str
    value: int
    block_number: int
    tx_hash: str
    timestamp: int


class TransferGraph:
    def __init__(self) -> None:
        self._graph = nx.MultiDiGraph()
        self._events: list[TransferEvent] = []
        self._lock = Lock()

    def add_event(self, event: TransferEvent) -> None:
        with self._lock:
            self._events.append(event)
            self._graph.add_edge(
                event.from_address,
                event.to_address,
                weight=event.value,
                block_number=event.block_number,
                tx_hash=event.tx_hash,
                timestamp=event.timestamp,
            )

    def summary(self) -> dict[str, int]:
        with self._lock:
            return {
                "node_count": self._graph.number_of_nodes(),
                "edge_count": self._graph.number_of_edges(),
            }

    def events(self, limit: int = 200) -> list[TransferEvent]:
        with self._lock:
            return list(self._events[-limit:])

    def graph_for(
        self,
        address: str,
        hop: int = 1,
        intermediary_addresses: set[str] | None = None,
    ) -> dict[str, list[dict[str, Any]]]:
        with self._lock:
            normalized = address.lower()
            intermediary_addresses = intermediary_addresses or set()
            if normalized not in self._graph:
                return {"nodes": [], "edges": []}

            selected_nodes = {normalized}
            frontier = {normalized}
            for _ in range(max(1, hop)):
                neighbors = set()
                for node in frontier:
                    neighbors.update(self._graph.predecessors(node))
                    neighbors.update(self._graph.successors(node))
                selected_nodes.update(neighbors)
                frontier = neighbors

            subgraph = self._graph.subgraph(selected_nodes)
            nodes = []
            total = max(1, len(subgraph.nodes))
            for index, node in enumerate(subgraph.nodes):
                if node == normalized:
                    kind = "contract"
                elif node in intermediary_addresses:
                    kind = "intermediary"
                else:
                    kind = "eoa"
                nodes.append({
                    "id": node,
                    "label": node[:6] + "..." + node[-4:],
                    "kind": kind,
                    "x": 410 + 260 * math.cos(index * 6.283 / total),
                    "y": 210 + 150 * math.sin(index * 6.283 / total),
                    "degree": subgraph.degree(node),
                })

            edges = []
            for from_address, to_address, data in subgraph.edges(data=True):
                edges.append({
                    "from": from_address,
                    "to": to_address,
                    "value": data["weight"],
                    "block_number": data["block_number"],
                })
            return {"nodes": nodes, "edges": edges}
