# Build stage para gerar bundle estático do frontend (React + Vite + Tailwind)
FROM node:22-alpine AS builder

WORKDIR /app

COPY package.json package-lock.json* ./
RUN npm ci --no-audit --no-fund

COPY . .
RUN npm run build

# Stage opcional standalone (quando não servido diretamente pelo backend FastAPI)
FROM nginx:1.27-alpine AS runtime
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 80
