#!/bin/bash
# PonziShield · Sealos DevBox 启动入口
# 控制台：启动命令 /bin/bash -c  参数 /home/devbox/project/entrypoint.sh prod
set -euo pipefail

MODE="${1:-prod}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

echo "[PonziShield] mode=$MODE root=$ROOT"

if ! command -v python3 >/dev/null 2>&1; then
  echo "[PonziShield] ERROR: python3 not found" >&2
  exit 1
fi

VENV="$ROOT/.venv"
if [ ! -d "$VENV" ]; then
  echo "[PonziShield] creating venv..."
  python3 -m venv "$VENV"
fi
# shellcheck disable=SC1091
source "$VENV/bin/activate"

REQ="$ROOT/PonziShield/ponzi-detector/requirements.txt"
if [ ! -f "$REQ" ] && [ -f "$ROOT/ponzi-detector/requirements.txt" ]; then
  REQ="$ROOT/ponzi-detector/requirements.txt"
fi

echo "[PonziShield] installing dependencies from $REQ ..."
pip install -q --upgrade pip
pip install -q -r "$REQ"

APP_DIR="$ROOT/PonziShield/ponzi-detector"
if [ ! -f "$APP_DIR/api/main.py" ]; then
  APP_DIR="$ROOT/ponzi-detector"
fi

STATIC_DIR="$ROOT/PonziShield/ponzi-web/dist"
if [ ! -f "$STATIC_DIR/index.html" ] && [ -f "$ROOT/ponzi-web/dist/index.html" ]; then
  STATIC_DIR="$ROOT/ponzi-web/dist"
fi

export PONZI_STATIC_DIR="$STATIC_DIR"
export PORT="${PORT:-8080}"

cd "$APP_DIR"
echo "[PonziShield] app_dir=$APP_DIR static_dir=$STATIC_DIR"
echo "[PonziShield] starting on 0.0.0.0:${PORT} ..."

if [ -f "$APP_DIR/api/main.py" ]; then
  exec uvicorn api.main:app --host 0.0.0.0 --port "$PORT"
fi

exec uvicorn main:app --host 0.0.0.0 --port "$PORT"
