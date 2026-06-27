#!/bin/sh
set -e

cd /app

uvicorn api.main:app --host 127.0.0.1 --port 8000 &
API_PID=$!

for i in $(seq 1 30); do
  if python - <<'PY'
import urllib.request
urllib.request.urlopen("http://127.0.0.1:8000/api/v1/health", timeout=2)
PY
  then
    break
  fi
  sleep 1
done

trap 'kill "$API_PID" 2>/dev/null || true' EXIT TERM INT
exec nginx -g 'daemon off;'
