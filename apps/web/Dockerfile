FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build:vite

FROM nginx:alpine
# Explicit Vite/Nginx fallback image. The default Compose web runs Dockerfile.next.
ARG MERCHIVRA_BUILD_REVISION=""
LABEL org.opencontainers.image.source="https://github.com/kunlong-luo/merchivra" \
      org.opencontainers.image.revision="${MERCHIVRA_BUILD_REVISION}"
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
