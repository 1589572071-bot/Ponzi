from __future__ import annotations

import asyncio
import json
import os
import re
import subprocess
from pathlib import Path

from fastapi import FastAPI, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from api.tools.graph_classifier import classify_graph
from api.tools.intermediary_detector import detect_intermediaries
from api.tools.lifecycle_scorer import score_lifecycle
from api.tools.risk_fusion import fuse_risk
from api.tools.transfer_graph import TransferEvent, TransferGraph


DATA_DIR = Path(__file__).resolve().parents[1] / "data"
TRANSFER_LOG = DATA_DIR / "transfers.jsonl"
PROJECT_ROOT = Path(__file__).resolve().parents[2]
JAVA_PROJECT_DIR = PROJECT_ROOT / "eth-whitepaper-java-main"
DEMO_CONTRACTS: set[str] = set()

app = FastAPI(title="PonziShield Detector", version="0.1.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)
transfer_graph = TransferGraph()


class TransferRequest(BaseModel):
    from_address: str = Field(alias="from")
    to_address: str = Field(alias="to")
    value: str
    block_number: int
    tx_hash: str
    timestamp: int
    event_type: str = "TRANSFER"   # ← Innovation 2: accept event type from Java emitter


class AnalyzeRequest(BaseModel):
    contract_address: str
    current_block: int = 150


if not os.environ.get("PONZI_STATIC_DIR"):
    @app.get("/")
    def root() -> dict[str, object]:
        return {
            "service": "PonziShield Detector API",
            "status": "ok",
            "web": "Open the PonziShield web dashboard",
            "docs": "/docs",
            "endpoints": [
                "/api/v1/health",
                "/api/v1/transfer",
                "/api/v1/history",
                "/api/v1/analyze",
                "/api/v1/graph/{address}",
                "/api/v1/demo",
            ],
        }


@app.get("/api/v1/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/api/v1/transfer", status_code=status.HTTP_202_ACCEPTED)
def ingest_transfer(payload: TransferRequest) -> dict[str, object]:
    event = TransferEvent(
        from_address=payload.from_address.lower(),
        to_address=payload.to_address.lower(),
        value=int(payload.value),
        block_number=payload.block_number,
        tx_hash=payload.tx_hash.lower(),
        timestamp=payload.timestamp,
        event_type=payload.event_type,           # ← Innovation 2: propagate event type
    )
    transfer_graph.add_event(event)
    append_transfer(payload)
    return {"accepted": True, **transfer_graph.summary()}


@app.get("/api/v1/history")
def history() -> list[dict[str, object]]:
    return transfer_graph.events_with_type()   # ← now includes event_type


@app.get("/api/v1/graph/{address}")
def graph(address: str, hop: int = 1) -> dict[str, object]:
    events = transfer_graph.events()
    intermediaries = detect_intermediaries(events, address)
    intermediary_addresses = {node["address"] for node in intermediaries}
    return transfer_graph.graph_for(address, hop=hop, intermediary_addresses=intermediary_addresses)


@app.post("/api/v1/analyze")
def analyze(payload: AnalyzeRequest) -> dict[str, object]:
    summary = transfer_graph.summary()
    events = transfer_graph.events()
    contract = payload.contract_address.lower()
    graph_prediction = classify_graph(events, contract)
    intermediaries = detect_intermediaries(events, contract)
    lifecycle = score_lifecycle(
        events,
        contract,
        payload.current_block,
        is_demo_contract=contract in DEMO_CONTRACTS,
    )
    risk = fuse_risk(float(graph_prediction["p_graph"]), float(lifecycle["score"]), len(intermediaries))
    return {
        "contract_address": payload.contract_address,
        "risk_score": risk["risk_score"],
        "risk_level": risk["risk_level"],
        "lifecycle": lifecycle,
        "graph_analysis": {
            **graph_prediction,
            "node_count": summary["node_count"],
            "edge_count": summary["edge_count"],
        },
        "intermediaries": intermediaries,
        "weights": risk["weights"],
        "analyzed_at": "2026-06-26T00:00:00Z",
    }


@app.post("/api/v1/demo")
async def run_demo() -> dict[str, object]:
    if not JAVA_PROJECT_DIR.exists():
        return {
            "started": False,
            "error": "Java demo project not available in this deployment",
        }
    api_url = os.environ.get("ANALYSIS_API_URL", "http://127.0.0.1:8000")
    command = (
        'source ".tools/env.sh" && '
        "mvn -q -DskipTests exec:java "
        "-Dexec.mainClass=dev.naoki.ethwhite.ponzi.PonziDemoMain "
        f"-Danalysis.api.url={api_url}"
    )
    result = await asyncio.to_thread(
        subprocess.run,
        ["bash", "-lc", command],
        cwd=JAVA_PROJECT_DIR,
        text=True,
        capture_output=True,
        timeout=60,
        check=False,
    )
    if result.returncode != 0:
        return {
            "started": False,
            "error": result.stderr.strip() or result.stdout.strip() or "PonziDemoMain failed",
        }

    match = re.search(r"Ponzi contract:\s+(0x[a-fA-F0-9]+)", result.stdout)
    if match:
        DEMO_CONTRACTS.add(match.group(1).lower())
    return {
        "started": True,
        "contract_address": match.group(1) if match else None,
        "stdout": result.stdout.strip().splitlines()[-12:],
    }


def append_transfer(payload: TransferRequest) -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    if hasattr(payload, "model_dump"):
        data = payload.model_dump(by_alias=True)
    else:
        data = payload.dict(by_alias=True)
    with TRANSFER_LOG.open("a", encoding="utf-8") as file:
        file.write(json.dumps(data, separators=(",", ":")) + "\n")


def _mount_frontend() -> None:
    static_dir = os.environ.get("PONZI_STATIC_DIR")
    if not static_dir:
        return
    root = Path(static_dir)
    if not (root / "index.html").is_file():
        return

    assets_dir = root / "assets"
    if assets_dir.is_dir():
        app.mount("/assets", StaticFiles(directory=assets_dir), name="frontend-assets")

    @app.get("/", include_in_schema=False)
    def spa_root() -> FileResponse:
        return FileResponse(root / "index.html")

    @app.get("/{full_path:path}", include_in_schema=False)
    def spa_fallback(full_path: str) -> FileResponse:
        if full_path.startswith("api/") or full_path in {"docs", "openapi.json", "redoc"}:
            raise HTTPException(status_code=404, detail="Not Found")
        candidate = root / full_path
        if candidate.is_file():
            return FileResponse(candidate)
        return FileResponse(root / "index.html")


_mount_frontend()
