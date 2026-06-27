FROM node:20-alpine AS web-builder

WORKDIR /app

COPY ponzi-web/package.json ponzi-web/package-lock.json ./
RUN npm ci

COPY ponzi-web/ ./

ENV VITE_PONZI_API=
RUN npm run build

FROM python:3.11-slim

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

RUN apt-get update \
    && apt-get install -y --no-install-recommends nginx \
    && rm -rf /var/lib/apt/lists/*

COPY ponzi-detector/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

COPY ponzi-detector/api ./api
COPY ponzi-detector/data ./data

COPY --from=web-builder /app/dist /usr/share/nginx/html
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY deploy/start.sh /start.sh
RUN chmod +x /start.sh \
    && rm -f /etc/nginx/sites-enabled/default

EXPOSE 80

CMD ["/start.sh"]
