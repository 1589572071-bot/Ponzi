# Sealos 发布说明

## 常见报错

```text
upstream connect error ... delayed connect error: 111
```

含义：网关/反代连不上后端（Connection refused）。

常见原因：

1. 只部署了前端，nginx 去连 `ponzi-detector:8000`，但集群里没有这个服务
2. Sealos 应用端口填错（应暴露 **80**，不是 8000）
3. 前后端拆成两个应用，但没有配置正确的内网服务名

## 推荐：单容器部署（最省事）

使用项目根目录 Dockerfile，一个容器同时跑：

- nginx（80）托管前端
- uvicorn（127.0.0.1:8000）提供 API

### Sealos 配置

| 项 | 值 |
|---|---|
| 构建目录 | 项目根目录 `PonziShield/` |
| Dockerfile | `Dockerfile` |
| 容器端口 | **80** |
| 对外端口 | 80 或平台自动映射 |

重新发布后，访问你的域名即可，前端会通过同源 `/api/` 访问后端。

## 备选：前后端分开部署

### 后端

| 项 | 值 |
|---|---|
| 构建目录 | `ponzi-detector/` |
| 容器端口 | **8000** |

### 前端

| 项 | 值 |
|---|---|
| 构建目录 | `ponzi-web/` |
| 容器端口 | **80** |
| 环境变量 | `BACKEND_UPSTREAM=你的后端内网地址:8000` |

例如 Sealos 内网地址：

```text
BACKEND_UPSTREAM=ponzi-detector.ns-xxxx.svc.cluster.local:8000
```

或使用后端应用的公网域名（不带 `/api` 后缀）：

```text
BACKEND_UPSTREAM=your-backend.example.com:443
```

## 本地 build

```bash
# 前端
cd ponzi-web && npm run build

# 后端检查
cd ../ponzi-detector
python -c "from api.main import app; print(app.title)"
```

## 本地 Docker（如环境支持）

单容器：

```bash
cd PonziShield
docker build -t ponzishield .
docker run -p 8080:80 ponzishield
```

双容器：

```bash
docker compose up -d --build
```

## 验证

部署成功后应能访问：

- `/` — 前端页面
- `/api/v1/health` — 返回 `{"status":"ok"}`

## 说明

- 当前 Sealos 版本 **不包含 Java Demo**；`/api/v1/demo` 在精简容器里不可用
- 其余接口（`/health`、`/history`、`/analyze`、`/graph`）正常
