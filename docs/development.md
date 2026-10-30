# Development guide

## Prerequisites
Java 21, Maven 3.9+, Node.js 22+, npm, Docker Compose.

## One-command local stack
```bash
cp .env.example .env
# Edit MERCHIVRA_DB_PASSWORD
docker compose -f deploy/compose.yaml up --build
```

The web is available on 127.0.0.1:3000; API on 127.0.0.1:8080. Database and Redis are internal to the Compose network.

## Run independently
```bash
# Start PostgreSQL/Redis using docker compose if desired
docker compose -f deploy/compose.yaml up -d postgres redis

# Shell 1 (set DB_PASSWORD to match .env)
DB_PASSWORD=your_password mvn -f apps/server/pom.xml spring-boot:run

# Shell 2
cd apps/web
npm install
npm run dev
```

Vite proxies `/api` to the local Spring Boot API. The API reads `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` from environment (the Compose server receives the appropriate values).

## Tests
```bash
mvn -f apps/server/pom.xml test
cd apps/web && npm install && npm run typecheck && npm run test && npm run build
```

A reproducible frontend lockfile is still missing. See [dependency decisions](architecture/dependency-adoption.md) and Issue #2. The current CI temporarily uses npm install instead of npm ci; do not treat this as a reproducible production build. More integration tests will be required as business features arrive.

## Warnings
The M0 application exposes only health and ping endpoints and a static frontend. There is **no authentication**. It is intended only for localhost development. Do not expose it to the internet or supply real Amazon credentials.
