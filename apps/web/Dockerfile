FROM node:24-alpine AS build
WORKDIR /app
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm run build:vite

FROM nginx:alpine
# Explicit Vite/Nginx fallback image. The default Compose web runs Dockerfile.next.
ARG MERCHIVRA_BUILD_REVISION=""
LABEL org.opencontainers.image.source="https://github.com/kunlong-luo/merchivra" \
      org.opencontainers.image.revision="${MERCHIVRA_BUILD_REVISION}"
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
