#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import json
import math
import random
from datetime import UTC, datetime
from pathlib import Path
from typing import Iterable

from api.tools.graph_classifier import DEFAULT_FEATURES, contract_neighborhood_events, extract_graph_features
from api.tools.transfer_graph import TransferEvent


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LABELS = ROOT / "data" / "processed" / "labels.csv"
DEFAULT_TRANSACTIONS = ROOT / "data" / "raw" / "xblock" / "transactions.csv"
DEFAULT_OUTPUT = ROOT / "api" / "models" / "graph_classifier_v1.json"
FEATURE_SCHEMA = {
    "tx_count_norm": "Normalized number of transfer events in the contract neighborhood.",
    "fan_in_norm": "Normalized count of unique inbound funders.",
    "fan_out_norm": "Normalized count of unique outbound receivers.",
    "in_out_balance": "Balance between inbound and outbound counterparties.",
    "same_block_payout_rate": "Share of payouts occurring in blocks that also contain inbound funding.",
    "recycling_ratio": "Share of payouts that follow inbound funding within a short block window.",
    "payout_ratio": "Total outbound value divided by total inbound value.",
    "degree_centralization": "Contract degree normalized by possible neighborhood edges.",
    "temporal_burst": "Maximum same-block transfer burst normalized by block span.",
    "value_concentration": "Largest counterparty flow divided by total counterparty flow.",
    "neighborhood_density": "Directed edge density of the 2-hop transaction neighborhood.",
    "reciprocity_rate": "Rate of bidirectional edges in the 2-hop neighborhood.",
}


def main() -> None:
    parser = argparse.ArgumentParser(description="Train PonziShield ethXpose-style graph classifier.")
    parser.add_argument("--labels", default=str(DEFAULT_LABELS), help="CSV with address,label columns.")
    parser.add_argument("--transactions", default=str(DEFAULT_TRANSACTIONS), help="XBlock/ethXpose transaction CSV.")
    parser.add_argument("--output", default=str(DEFAULT_OUTPUT), help="Model JSON output path.")
    parser.add_argument("--epochs", type=int, default=2500)
    parser.add_argument("--learning-rate", type=float, default=0.25)
    parser.add_argument("--l2", type=float, default=0.01)
    parser.add_argument("--test-size", type=float, default=0.25)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    labels = read_labels(Path(args.labels))
    events = read_transactions(Path(args.transactions))
    dataset = build_dataset(labels, events)
    if len(dataset) < 4:
        raise SystemExit("Need at least 4 labeled addresses with transaction neighborhoods to train.")
    if len({label for _, label in dataset}) < 2:
        raise SystemExit("Need both positive and negative labels to train.")

    train_rows, test_rows = stratified_split(dataset, test_size=args.test_size, seed=args.seed)
    intercept, weights = train_logistic_regression(
        train_rows,
        epochs=args.epochs,
        learning_rate=args.learning_rate,
        l2=args.l2,
    )
    train_metrics = evaluate(train_rows, intercept, weights)
    test_metrics = evaluate(test_rows, intercept, weights) if test_rows else train_metrics

    model = {
        "model_name": "PonziShield GraphClassifier",
        "model_version": f"ethxpose-local-{datetime.now(UTC).strftime('%Y%m%d')}",
        "model_type": "trained_logistic_graph_classifier",
        "description": (
            "Trained PonziShield graph classifier using ethXpose-style 2-hop transaction-neighborhood "
            "features from labeled Ethereum transaction graphs."
        ),
        "feature_scope": "2-hop transfer neighborhood centered on the analyzed contract",
        "calibration": {
            "method": "logistic regression",
            "positive_class": "ponzi_like_transaction_graph",
            "threshold_low": 0.35,
            "threshold_high": 0.7,
        },
        "intercept": round(intercept, 8),
        "weights": {name: round(weights[name], 8) for name in DEFAULT_FEATURES},
        "feature_schema": FEATURE_SCHEMA,
        "thresholds": {"low": 0.35, "high": 0.7},
        "training": {
            "labels": str(Path(args.labels)),
            "transactions": str(Path(args.transactions)),
            "sample_count": len(dataset),
            "train_count": len(train_rows),
            "test_count": len(test_rows),
            "positive_count": sum(label for _, label in dataset),
            "negative_count": len(dataset) - sum(label for _, label in dataset),
            "epochs": args.epochs,
            "learning_rate": args.learning_rate,
            "l2": args.l2,
            "seed": args.seed,
        },
        "metrics": {
            "train": train_metrics,
            "test": test_metrics,
        },
    }

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(model, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"Wrote model to {output}")
    print(f"Train F1={train_metrics['f1']:.3f}, Test F1={test_metrics['f1']:.3f}, Test AUC={test_metrics['auc']:.3f}")


def read_labels(path: Path) -> dict[str, int]:
    rows: dict[str, int] = {}
    with path.open(newline="", encoding="utf-8") as file:
        reader = csv.DictReader(file)
        for row in reader:
            address = normalize_address(first_present(row, ["address", "wallet", "contract", "contract_address"]))
            label = first_present(row, ["label", "is_ponzi", "class", "target"])
            if not address or label is None:
                continue
            rows[address] = parse_label(label)
    return rows


def read_transactions(path: Path) -> list[TransferEvent]:
    events: list[TransferEvent] = []
    with path.open(newline="", encoding="utf-8") as file:
        reader = csv.DictReader(file)
        for index, row in enumerate(reader):
            from_address = normalize_address(first_present(row, ["from", "from_address", "sender", "src"]))
            to_address = normalize_address(first_present(row, ["to", "to_address", "receiver", "dst"]))
            if not from_address or not to_address:
                continue
            events.append(TransferEvent(
                from_address=from_address,
                to_address=to_address,
                value=parse_int(first_present(row, ["value", "amount", "ether", "eth_value"]), default=0),
                block_number=parse_int(first_present(row, ["block_number", "block", "blockNumber"]), default=index),
                tx_hash=(first_present(row, ["tx_hash", "hash", "transaction_hash"]) or f"row-{index}").lower(),
                timestamp=parse_int(first_present(row, ["timestamp", "time", "datetime"]), default=0),
            ))
    return events


def build_dataset(labels: dict[str, int], events: list[TransferEvent]) -> list[tuple[dict[str, float], int]]:
    dataset: list[tuple[dict[str, float], int]] = []
    for address, label in labels.items():
        neighborhood = contract_neighborhood_events(events, address, hop=2)
        if not neighborhood:
            continue
        dataset.append((extract_graph_features(neighborhood, address), label))
    return dataset


def train_logistic_regression(
    rows: list[tuple[dict[str, float], int]],
    epochs: int,
    learning_rate: float,
    l2: float,
) -> tuple[float, dict[str, float]]:
    weights = {name: 0.0 for name in DEFAULT_FEATURES}
    intercept = 0.0
    n = len(rows)
    for _ in range(epochs):
        grad_b = 0.0
        grad_w = {name: 0.0 for name in DEFAULT_FEATURES}
        for features, label in rows:
            pred = sigmoid(intercept + sum(weights[name] * features.get(name, 0.0) for name in DEFAULT_FEATURES))
            err = pred - label
            grad_b += err
            for name in DEFAULT_FEATURES:
                grad_w[name] += err * features.get(name, 0.0)
        intercept -= learning_rate * grad_b / n
        for name in DEFAULT_FEATURES:
            gradient = grad_w[name] / n + l2 * weights[name]
            weights[name] -= learning_rate * gradient
    return intercept, weights


def evaluate(rows: list[tuple[dict[str, float], int]], intercept: float, weights: dict[str, float]) -> dict[str, float]:
    scores = [
        sigmoid(intercept + sum(weights[name] * features.get(name, 0.0) for name in DEFAULT_FEATURES))
        for features, _ in rows
    ]
    labels = [label for _, label in rows]
    predictions = [1 if score >= 0.5 else 0 for score in scores]
    tp = sum(1 for y, p in zip(labels, predictions, strict=True) if y == 1 and p == 1)
    fp = sum(1 for y, p in zip(labels, predictions, strict=True) if y == 0 and p == 1)
    tn = sum(1 for y, p in zip(labels, predictions, strict=True) if y == 0 and p == 0)
    fn = sum(1 for y, p in zip(labels, predictions, strict=True) if y == 1 and p == 0)
    precision = tp / max(1, tp + fp)
    recall = tp / max(1, tp + fn)
    f1 = 2 * precision * recall / max(1e-12, precision + recall)
    accuracy = (tp + tn) / max(1, len(rows))
    return {
        "accuracy": round(accuracy, 4),
        "precision": round(precision, 4),
        "recall": round(recall, 4),
        "f1": round(f1, 4),
        "auc": round(auc(labels, scores), 4),
        "tp": tp,
        "fp": fp,
        "tn": tn,
        "fn": fn,
    }


def stratified_split(
    rows: list[tuple[dict[str, float], int]],
    test_size: float,
    seed: int,
) -> tuple[list[tuple[dict[str, float], int]], list[tuple[dict[str, float], int]]]:
    rng = random.Random(seed)
    positives = [row for row in rows if row[1] == 1]
    negatives = [row for row in rows if row[1] == 0]
    rng.shuffle(positives)
    rng.shuffle(negatives)
    pos_test = max(1, int(len(positives) * test_size)) if len(positives) > 1 else 0
    neg_test = max(1, int(len(negatives) * test_size)) if len(negatives) > 1 else 0
    test_rows = positives[:pos_test] + negatives[:neg_test]
    train_rows = positives[pos_test:] + negatives[neg_test:]
    rng.shuffle(train_rows)
    rng.shuffle(test_rows)
    return train_rows, test_rows


def auc(labels: list[int], scores: list[float]) -> float:
    positives = [(label, score) for label, score in zip(labels, scores, strict=True) if label == 1]
    negatives = [(label, score) for label, score in zip(labels, scores, strict=True) if label == 0]
    if not positives or not negatives:
        return 0.0
    wins = 0.0
    total = len(positives) * len(negatives)
    for _, pos_score in positives:
        for _, neg_score in negatives:
            if pos_score > neg_score:
                wins += 1
            elif pos_score == neg_score:
                wins += 0.5
    return wins / total


def first_present(row: dict[str, str], names: Iterable[str]) -> str | None:
    lowered = {key.lower(): value for key, value in row.items()}
    for name in names:
        value = lowered.get(name.lower())
        if value not in (None, ""):
            return value.strip()
    return None


def normalize_address(value: str | None) -> str | None:
    if not value:
        return None
    address = value.strip().lower()
    return address if address.startswith("0x") and len(address) == 42 else None


def parse_label(value: str) -> int:
    lowered = value.strip().lower()
    if lowered in {"1", "true", "yes", "ponzi", "fraud", "phishing"}:
        return 1
    if lowered in {"0", "false", "no", "normal", "benign", "non-ponzi", "non_ponzi"}:
        return 0
    return int(float(lowered))


def parse_int(value: str | None, default: int) -> int:
    if value in (None, ""):
        return default
    try:
        return int(float(value))
    except ValueError:
        return default


def sigmoid(value: float) -> float:
    if value >= 0:
        z = math.exp(-value)
        return 1 / (1 + z)
    z = math.exp(value)
    return z / (1 + z)


if __name__ == "__main__":
    main()