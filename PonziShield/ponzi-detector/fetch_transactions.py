#!/usr/bin/env python3
"""
fetch_transactions.py — 从 Etherscan API 批量拉取真实交易数据

用法:
    python fetch_transactions.py --api-key YOUR_ETHERSCAN_API_KEY

数据来源:
    - labels.csv: 382 个合约地址 (182 庞氏 + 200 非庞氏)
      * 庞氏地址来自: blockchain-unica/ethereum-ponzi
      * 非庞氏地址来自: xuyl0104/blockchain_ponzi_detection
    - Etherscan V2 API: 获取普通交易 + 内部交易
    - 输出: data/raw/xblock/transactions.csv

申请免费 API Key:
    https://etherscan.io/apis (注册后在 My API Keys 中创建)

说明:
    - 免费 API Key 额度: 5 次/秒, 100000 次/天
    - 382 个地址 × 2 种交易 = 764 次请求, 在免费额度内
    - 脚本自动控制请求速率, 避免触发 429 限流
    - 支持断点续传: 已下载的地址会读缓存跳过
"""

from __future__ import annotations

import argparse
import csv
import json
import ssl
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

# macOS 上 Python 3 的 SSL 证书常出问题，自动降级为跳过验证
_ssl_ctx = ssl.create_default_context()
try:
    urllib.request.urlopen(
        urllib.request.Request("https://api.etherscan.io"),
        timeout=5,
        context=_ssl_ctx,
    )
except ssl.SSLCertVerificationError:
    _ssl_ctx = ssl.create_default_context()
    _ssl_ctx.check_hostname = False
    _ssl_ctx.verify_mode = ssl.CERT_NONE
    print("⚠  SSL 证书验证失败，已跳过验证（仅限本地开发）")
except Exception:
    pass

ROOT = Path(__file__).resolve().parent
DEFAULT_LABELS = ROOT / "data" / "processed" / "labels.csv"
DEFAULT_OUTPUT = ROOT / "data" / "raw" / "xblock" / "transactions.csv"
DEFAULT_CACHE  = ROOT / "data" / "raw" / "xblock" / "cache"

# Etherscan V2 API（V1 已于 2025 年废弃，必须用 V2）
_BASE = "https://api.etherscan.io/v2/api?chainid=1&module=account&action={action}&address={address}&startblock=0&endblock=99999999&sort=asc&apikey={api_key}"


def _url(action: str, address: str, api_key: str) -> str:
    return _BASE.format(action=action, address=address, api_key=api_key)


def fetch_json(url: str, retries: int = 3) -> dict:
    """带重试的 GET 请求，返回 Etherscan JSON。"""
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "PonziShield/1.0"})
            with urllib.request.urlopen(req, timeout=30, context=_ssl_ctx) as resp:
                data = json.loads(resp.read().decode())
            status = data.get("status", "0")
            msg    = data.get("message", "")
            result = data.get("result", [])
            if status == "1":
                return data
            if status == "0" and ("No transactions" in str(result) or result == [] or result == ""):
                return {"result": []}
            # 限流或其他错误
            print(f"\n  API [{attempt+1}/{retries}]: {msg} — {str(result)[:80]}", end="")
            if attempt < retries - 1:
                time.sleep(5 * (attempt + 1))
        except urllib.error.HTTPError as exc:
            if exc.code == 429:
                wait = 10 * (attempt + 1)
                print(f"\n  429 限流，等待 {wait}s ...", end="")
                time.sleep(wait)
            else:
                print(f"\n  HTTP {exc.code}: {exc.reason}", end="")
                if attempt < retries - 1:
                    time.sleep(3)
        except Exception as exc:
            print(f"\n  请求异常: {exc}", end="")
            if attempt < retries - 1:
                time.sleep(3)
    return {"result": []}


def normalize_txs(normal: list, internal: list) -> list[dict]:
    """把 Etherscan 两种交易统一为训练脚本需要的格式。"""
    rows: list[dict] = []
    seen: set[str] = set()

    def safe_int(v: str | None) -> str:
        try:
            return str(int(float(v or "0")))
        except (ValueError, TypeError):
            return "0"

    for tx in normal:
        key = (tx.get("hash") or "").lower()
        if not key or key in seen:
            continue
        seen.add(key)
        f = (tx.get("from") or "").lower()
        t = (tx.get("to")   or "").lower()
        if not f or not t:
            continue
        rows.append({"from": f, "to": t,
                     "value": safe_int(tx.get("value")),
                     "block_number": safe_int(tx.get("blockNumber")),
                     "tx_hash": key,
                     "timestamp": safe_int(tx.get("timeStamp"))})

    for tx in internal:
        f = (tx.get("from") or "").lower()
        t = (tx.get("to")   or "").lower()
        key = f"{(tx.get('hash') or '').lower()}:{f}:{t}"
        if not f or not t or key in seen:
            continue
        seen.add(key)
        rows.append({"from": f, "to": t,
                     "value": safe_int(tx.get("value")),
                     "block_number": safe_int(tx.get("blockNumber")),
                     "tx_hash": (tx.get("hash") or f"int-{len(rows)}").lower(),
                     "timestamp": safe_int(tx.get("timeStamp"))})

    return rows


def read_labels(path: Path) -> list[tuple[str, int]]:
    result: list[tuple[str, int]] = []
    with path.open(newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            addr = (row.get("address") or "").strip().lower()
            if addr.startswith("0x") and len(addr) == 42:
                result.append((addr, int(row.get("label", "0"))))
    return result


def main() -> None:
    ap = argparse.ArgumentParser(description="从 Etherscan V2 API 拉取合约交易数据")
    ap.add_argument("--api-key",      required=True, help="Etherscan API Key（https://etherscan.io/apis）")
    ap.add_argument("--labels",       default=str(DEFAULT_LABELS))
    ap.add_argument("--output",       default=str(DEFAULT_OUTPUT))
    ap.add_argument("--cache-dir",    default=str(DEFAULT_CACHE))
    ap.add_argument("--max-addresses",type=int, default=0, help="限制处理地址数（0=全部）")
    ap.add_argument("--rate-limit",   type=float, default=0.22, help="每次请求后等待秒数（默认 0.22s ≈ 4.5次/秒）")
    args = ap.parse_args()

    labels_path = Path(args.labels)
    output_path = Path(args.output)
    cache_dir   = Path(args.cache_dir)

    if not labels_path.exists():
        sys.exit(f"找不到 labels.csv: {labels_path}")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    cache_dir.mkdir(parents=True, exist_ok=True)

    addresses = read_labels(labels_path)
    if args.max_addresses > 0:
        addresses = addresses[:args.max_addresses]

    print(f"共 {len(addresses)} 个地址  |  输出 → {output_path}\n")

    all_rows: list[dict] = []
    skipped = failed = 0

    for idx, (address, label) in enumerate(addresses):
        tag        = "PONZI" if label else "NORMAL"
        cache_file = cache_dir / f"{address}.json"

        # 读缓存
        if cache_file.exists():
            try:
                cached = json.loads(cache_file.read_text())
                tx_rows = cached.get("transactions", [])
                all_rows.extend(tx_rows)
                skipped += 1
                if (idx + 1) % 50 == 0:
                    print(f"[{idx+1}/{len(addresses)}] cached {address[:10]}… ({tag}): {len(tx_rows)} txs")
                continue
            except Exception:
                pass

        print(f"[{idx+1}/{len(addresses)}] {address[:10]}… ({tag}) fetching…", end="", flush=True)

        nd = fetch_json(_url("txlist",         address, args.api_key))
        time.sleep(args.rate_limit)
        id_ = fetch_json(_url("txlistinternal", address, args.api_key))
        time.sleep(args.rate_limit)

        normal   = nd.get("result",  []) or []
        internal = id_.get("result", []) or []
        if isinstance(normal,   str): normal   = []
        if isinstance(internal, str): internal = []

        tx_rows = normalize_txs(normal, internal)
        all_rows.extend(tx_rows)

        cache_file.write_text(json.dumps({
            "address": address, "label": label,
            "transaction_count": len(tx_rows),
            "transactions": tx_rows,
        }))

        print(f" {len(normal)} normal + {len(internal)} internal = {len(tx_rows)} rows")
        if not normal and not internal:
            failed += 1

    # 写 CSV
    with output_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=["from","to","value","block_number","tx_hash","timestamp"])
        writer.writeheader()
        writer.writerows(all_rows)

    print(f"\n{'='*60}")
    print(f"完成！共 {len(all_rows)} 条交易记录")
    print(f"  地址数: {len(addresses)}  |  缓存命中: {skipped}  |  空数据: {failed}")
    print(f"  输出: {output_path}")
    print(f"\n下一步（训练模型）：")
    print(f"  cd {ROOT}")
    print(f"  python ml/train_graph_classifier.py --transactions {output_path}")


if __name__ == "__main__":
    main()
