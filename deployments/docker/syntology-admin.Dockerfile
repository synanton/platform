FROM node:20-alpine AS builder
WORKDIR /app
COPY ui/syntology-admin/package*.json ./
RUN npm ci
COPY ui/syntology-admin/ .
RUN npm run build

FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY deployment/docker/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
