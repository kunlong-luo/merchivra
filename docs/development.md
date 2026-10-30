# Development guide

## Prerequisites
**Java 25 LTS**, Maven 3.9+, **Node.js 24.11+ (24.x)**, npm, Docker Compose. Frontend local Node selection: `cd apps/web && nvm use`. The same major versions are used in GitHub Actions and the Docker multi-stage builds.

## One-command local stack
```bash
cp .env.example .env
# Edit MERCHIVRA_DB_PASSWORD, and set a unique >=16-character bootstrap password.
# Set MERCHIVRA_BOOTSTRAP_USERNAME and MERCHIVRA_BOOTSTRAP_PASSWORD.
docker compose --env-file .env -f deploy/compose.yaml up --build
```

The web now requires local Session login; see [first-run authentication](security/local-auth.md). The web is available on 127.0.0.1:3000; API on 127.0.0.1:8080. Compose explicitly enables the synthetic demo endpoints via MERCHIVRA_DEMO_ENABLED=true. Demo data and simulated sync run history persist in PostgreSQL (separate demo_snapshot/demo_sync_run tables); see [demo guide](demo.md). Database and Redis are internal to the Compose network.

## Run independently
```bash
# Start PostgreSQL/Redis using docker compose if desired
docker compose --env-file .env -f deploy/compose.yaml up -d postgres redis

# Shell 1 (set DB_PASSWORD to match .env)
MERCHIVRA_DEMO_ENABLED=true MERCHIVRA_BOOTSTRAP_USERNAME=your_admin MERCHIVRA_BOOTSTRAP_PASSWORD=your_unique_random_password DB_PASSWORD=your_db_password mvn -f apps/server/pom.xml spring-boot:run

# Shell 2
cd apps/web
npm ci
npm run dev
```

Vite proxies `/api` to the local Spring Boot API. The API reads `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` from environment (the Compose server receives the appropriate values).

## Fast tests during M0–M2

Current CI deliberately remains lightweight: backend unit tests, frontend typecheck, Vitest and build. Playwright/E2E, multi-browser testing, CI Docker stack and coverage gates are deferred until the core ERP flows are mostly built. See [CI policy](testing/ci-policy.md).

## Tests
```bash
mvn -f apps/server/pom.xml test
cd apps/web && npm ci && npm run typecheck && npm run test && npm run build
```

The real npm-registry-resolved `apps/web/package-lock.json` is checked in (lockfile v3). CI and the Node 24 Docker build both use **`npm ci`** rather than resolving dependencies afresh. This improves dependency repeatability but does not guarantee production readiness; Maven's managed dependency BOM remains pinned by Spring Boot 4.1.2. No new E2E/coverage gates were added. See [toolchain upgrade record](architecture/stack-upgrade-2026-10-30.md).

## Warnings
The M0/M1 application exposes health and ping endpoints plus an opt-in synthetic read-only demo. The demo endpoint is OFF by default and Compose enables it locally. There is now an initial **local Session/CSRF and OWNER tenant scope**, but no comprehensive production IAM or Amazon credential policy. It remains intended only for localhost development. Do not expose it to the internet or supply real Amazon credentials.

## Authenticated core read workspace (2026-10-30)

After logging in, the default page is **核心业务数据**. It reads normalized PostgreSQL `seller_marketplace`, `marketplace_listing`, `sales_order` and `fba_inventory_observation` with the active tenant resolved by the backend Session. Initially, a newly bootstrapped Owner typically has **no marketplaces**, so the empty state is correct. The old tabs continue to display synthetic Mock data only. See [implemented read APIs](api/core-read-api.md) and [Agent policy](../Agent.md).

The core queries are intentionally read-only and limited to the first 50 (up to 100 on API) records; cursor pagination, authenticated Seller connection and real Amazon/SP-API ingestion are pending. Do not use the local demo as evidence that a multi-tenant production system is validated.

## Local Seller setup (not Amazon authorization)

The default authenticated **核心业务数据** workspace now includes a local Seller Account setup form and a small audit list. Creates a server-generated `UNCONFIGURED` placeholder and selected Marketplace metadata, never a real Seller authorization or token. All writes require Session + CSRF. PostgreSQL Flyway V4 adds setup labels, request idempotency and tenant-scoped local audit. See [local setup API](api/local-seller-setup.md).

**Verification update (2026-10-30):** [一次性 PostgreSQL 17 冒烟验证](https://github.com/kunlong-luo/merchivra/actions/runs/41059134533) successfully launched Java 25 / Spring Boot 4 against a disposable PostgreSQL 17 service, applied Flyway V1–V4 and exercised the session/CSRF/account/marketplace/audit/404 path. This one-shot workflow deleted itself on success. **Still not verified:** concurrent transaction races, real Docker Compose stack and browser flows. Early CI remains Java unit tests + TypeScript/Vitest/Vite only.

**Compose root dotenv:** when invoking a Compose file under `deploy/` from the repository root, always pass `--env-file .env`. Without this explicit flag, Compose may search for `.env` in the Compose project directory rather than reading the repo-root file shown in these docs.

## Full simulated source-to-query chain

The explicit [TEST-004 simulated SP-API smoke](testing/mock-full-chain.md) exercises login, local setup, mocked paginated Orders/Listing/FBA, tenant-scoped SQL reads, audit/idempotency and backend restart through the Nginx proxy. Run the separate manual Compose workflow or the localhost-only Python script against disposable data. The daily CI remains lightweight. No live Amazon Seller/Finances credentials are used.

### Verified Compose mock integration

[Compose 全链路成功运行（2026-10-30）](https://github.com/kunlong-luo/merchivra/actions/runs/41061679196) built and ran the full Nginx + Java 25/Spring Boot 4 + PostgreSQL 17 Compose stack using ephemeral credentials, exercised authenticated US/DE Mock operations and tenant-scoped Listing/Order/FBA queries, and verified persistence across a backend restart plus logout/session invalidation. The standalone workflow is manual/on-workflow-file change only, not part of the regular CI gate. Follow [TEST-004](testing/mock-full-chain.md); live Amazon OAuth/Finances, multi-user ACL and production readiness are **not** verified.

### V6 restart-resumable Mock page validation

[V6 逐页断点恢复 Compose 验证成功](https://github.com/kunlong-luo/merchivra/actions/runs/41065562926): the standalone Compose suite submitted page 1 for a separate US LOCAL/UNCONFIGURED marketplace, restarted the Java API, resumed page 2, replayed the first request key safely, then validated the page count, epoch, core facts and audit. Run the manual [Mock full-chain workflow](https://github.com/kunlong-luo/merchivra/actions/workflows/mock-full-chain.yml) when changing page-commit behavior; no permanent heavy CI gate.
