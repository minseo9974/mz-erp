# 웹 이미지 = 빌드한 화면 파일 + Caddy (HTTPS, /api는 백엔드로 프록시)
# 빌드 컨텍스트는 저장소 루트:  docker build -f frontend/Dockerfile -t mz-erp-web .

FROM node:22-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM caddy:2
COPY --from=build /app/dist /srv
