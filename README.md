# PonziShield

Ethereum Ponzi scheme detection demo: Java fund-flow capture, Python graph analysis, React dashboard.

## Structure

```text
PonziShield/
├── eth-whitepaper-java-main/   Java node + PonziContract + FundFlowEmitter
├── ponzi-detector/             FastAPI + graph classifier + lifecycle/intermediary modules
├── ponzi-web/                  React + Vite dashboard
├── Dockerfile                  Single-container deploy (nginx + API)
└── DEPLOY.md                   Sealos deployment guide

entrypoint.sh                   Sealos DevBox startup (port 8080)
```

## Quick start

### Backend

```bash
cd PonziShield/ponzi-detector
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn api.main:app --host 0.0.0.0 --port 8000
```

### Frontend

```bash
cd PonziShield/ponzi-web
npm install
npm run dev
```

Open http://localhost:5173

### Sealos DevBox

```bash
cd PonziShield/ponzi-web && npm run build
/home/devbox/project/entrypoint.sh prod
```

Sealos settings: startup `/bin/bash -c` + `/home/devbox/project/entrypoint.sh prod`, port **8080**.

## API

- `GET /api/v1/health`
- `GET /api/v1/history`
- `POST /api/v1/analyze`
- `GET /api/v1/graph/{address}`
- `POST /api/v1/demo` (requires Java/Maven in environment)

## License

Course / research demo project.
