# Development guide

## Prerequisites
**Java 25 LTS**, Gradle 9.2.1（项目内 Gradle Wrapper）, **Node.js 24.11+ (24.x)**, npm, Docker Compose. Frontend local Node selection: `cd apps/web && nvm use`. The same major versions are used in GitHub Actions and the Docker multi-stage builds.

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

# Shell 1: these environment variables must be inherited by the Java process.
export DB_HOST=127.0.0.1 DB_PORT=5432 DB_NAME=merchivra DB_USER=merchivra
export DB_PASSWORD='your-local-dedicated-role-password'
export MERCHIVRA_DEMO_ENABLED=true
export MERCHIVRA_BOOTSTRAP_USERNAME=your_admin
export MERCHIVRA_BOOTSTRAP_PASSWORD='your-unique-16-plus-character-password'
(cd apps/server && ./gradlew bootRun)

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
(cd apps/server && ./gradlew test)
cd apps/web && npm ci && npm run typecheck && npm run test && npm run build
```

The real npm-registry-resolved `apps/web/package-lock.json` is checked in (lockfile v3). CI and the Node 24 Docker build both use **`npm ci`** rather than resolving dependencies afresh. This improves dependency repeatability but does not guarantee production readiness; Gradle's managed Spring Boot dependency BOM remains pinned by Spring Boot 4.1.2. No new E2E/coverage gates were added. See [toolchain upgrade record](architecture/stack-upgrade-2026-10-30.md).

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

## SP-API connector code (no real authorization)

The backend includes Amazon's official SDK 1.13.0 plus `spapi/` typed and read-only core adapters. `MERCHIVRA_SPAPI_ENABLED=false` and `MERCHIVRA_SPAPI_ALLOW_WRITES=false` are the defaults, so this does **not** connect any seller. See [INT-004](integrations/sp-api-code-integration.md) for secure integration prerequisites and offline contract tests. Do not enable outbound for a real seller until OAuth authorization/secret vault and role verification are complete.

## Optional public Seller OAuth code (disabled by default)

Flyway V7 creates tenant-bound state and encrypted grant tables. Public-app website authorization includes Amazon login-URI handoff, short-lived Lax HttpOnly cookie, final Code exchange and local revoke/audit, but **never marks a Seller connected until separately verified**. Real OAuth is incompatible with Demo mode and requires a registered HTTPS redirect plus a protected AES-256 key and LWA app credentials. See [SEC-004](security/seller-oauth.md); do not put any credentials in this repo or public CI.

## V8/V9 Marketplace verification and vault key rotation (code only)

- V8 offers an opt-in, production-endpoint-only Sellers v1 Marketplace-participation check for Owner sessions, and 24-hour marketplace evidence before the internal Worker Vault releases a grant. Sellers v1 does **not** prove independent Seller identity or Amazon operation roles. Read [SEC-005](security/seller-verification.md).
- V9 supports AES-256-GCM versioned keyrings and an Owner/CSRF per-account `POST /api/v1/seller-accounts/{accountId}/oauth/rotate-key` for transactional rewrap and metadata-only audit. It does **not** rotate Amazon LWA Refresh Tokens. Follow the careful old/new key staging procedure in [SEC-006](security/seller-key-rotation.md).
- With real SP-API disabled, the independent Compose suite validates Flyway V7–V9 migration and fictional Mock regression; it does not test Amazon APIs, KMS, tenant-wide automatic rotation, roles or any live sync Worker. Never put real Seller secrets into CI or Demo fixtures.


## Optional browser-responsive layout smoke

See [TEST-005](testing/browser-ui.md) for a separate Chrome/Node 24 browser
layout test with synthetic localhost-only Seller/Listing/Orders/FBA responses.
It captures 360/390/768/1280 screenshots and checks no page-level horizontal
overflow, local table scrolling, table columns, and source/UNKNOWN semantics.
It is opt-in, does not install Impeccable or Playwright, and is not part of the
M0–M2 fast CI. It does not test real login, database, or Amazon connections.

## Gradle 9.2.1 / Java 25 build standard (2026-11-01)

The maintainer retired Maven. **Use the checked-in Gradle Wrapper**, not `mvn` or `apps/server/pom.xml`. Spring Boot remains 4.1.2. From the repository root:

```bash
cd apps/server
./gradlew --version
./gradlew test             # JUnit 5 + Jacoco XML/HTML reports
./gradlew bootRun          # DB_HOST/DB_NAME/DB_USER/DB_PASSWORD must be set
./gradlew bootJar          # build/libs/merchivra-server.jar
./gradlew -PstrictCoverage check  # optional strict >=83% total LINE coverage target
```

On Windows use `gradlew.bat`. Wrapper Gradle distribution is checksum-pinned in `gradle/wrapper/gradle-wrapper.properties`. The CI runs the equivalent Gradle 9.2.1 tasks under Temurin 25, plus real PostgreSQL 18/Flyway and React checks. The strict coverage target is a **future quality gate**: enable it when the test suite has reached the required baseline, do not claim it has passed unless the specific command did. No Maven files are needed to build the backend.

## Optional Redis 8 (2026-11-01)

Redis is now implemented as an opt-in Spring Session and SP-API admission store, **not a PostgreSQL replacement**. In an existing spark-db environment, set `MERCHIVRA_REDIS_PASSWORD` locally to the password of `spark-redis`, `MERCHIVRA_REDIS_SESSIONS_ENABLED=true`, and optionally `MERCHIVRA_SPAPI_RATE_LIMIT_ENABLED=true`. Container access uses `spark-redis:6379`; a host-run Java process uses the Redis port mapped to `127.0.0.1:6379` and needs `MERCHIVRA_REDIS_HOST=127.0.0.1`. Do not export real passwords in scripts committed to Git.

The standalone `deploy/compose.yaml` uses disposable `redis:8.10.1-alpine` under `--profile optional-cache`, with `noeviction` only for local testing; normal dev still uses just PostgreSQL + server + web. See [Redis runtime guide](deployment/redis-session-spapi-v53.md) for restart and Redis-unavailable smoke, the shared `allkeys-lru` hazard and production gating.

## IntelliJ IDEA 本地启动与 PostgreSQL SCRAM 验证（2026-11-01）

用户 2026-11-01 的 IDEA Java 启动日志定位到了数据库身份认证：`The server requested SCRAM-based authentication, but no password was provided`。这是 Flyway 启动前获取 JDBC 连接失败，不能据此判定 SQL 迁移失败；GitHub V61 CI 在隔离 PG18 环境中已通过，但不会替你本机设置数据库密码。

本机 IDEA 直接运行 `org.merchivra.erp.MerchivraApplication`（不是 Docker 内运行）时，打开 **Run → Edit Configurations → MerchivraApplication → Environment variables**，为这一个运行配置设置 `DB_HOST=127.0.0.1`、`DB_PORT=5432`、`DB_NAME=merchivra`、`DB_USER=merchivra` 和你在本机专用数据库角色设置的 **`DB_PASSWORD`**。如需首次初始化，再设置 `MERCHIVRA_BOOTSTRAP_USERNAME` 和 `MERCHIVRA_BOOTSTRAP_PASSWORD`。`application.yml` 的 JDBC 密码是 `${DB_PASSWORD:}`，未提供变量就为空。IntelliJ **不会自动读取** Compose `--env-file .env`；需要在 IDE 的进程环境中单独配置，或者使用经过审核的本地、未纳入 Git 的环境注入机制。

先在本机终端测试数据库连通（`-W` 交互输入，不将口令放在命令行或日志中）：

```bash
psql "host=127.0.0.1 port=5432 dbname=merchivra user=merchivra" -W \
  -c "SELECT current_database(), current_user, current_setting('server_version');"
```

如果 psql 连不上，检查 `spark-postgres` 或本机 PG18 是否运行、端口是否暴露、是否存在 `merchivra` 独立数据库及同名 LOGIN 角色、是否使用为该角色单独设置的密码。**不可使用管理员 root 密码代替，不要关闭 SCRAM 或执行 `down -v`**。

如果 psql 成功而 IntelliJ 报 `no password was provided`，检查当前正在运行的 IDEA Run Configuration 是否保存并注入了 `DB_PASSWORD`；重新启动 Java 进程后重试。成功标准是 Hikari 成功获取连接、Flyway 验证/迁移完成，以及 Spring Boot 就绪，而不只是 Tomcat initialized。密钥不得提交到仓库或发在聊天里。

## One-time PostgreSQL 18 cluster provisioning (only for external spark-db)

The role/database creation process is **not a Flyway migration**, `bootRun` lifecycle action, or CI/Compose startup hook. For a genuinely new `spark-postgres` cluster with no existing `merchivra` role/database, an authorized administrator may explicitly run [`apps/server/scripts/provision-merchivra-database.sql`](../apps/server/scripts/provision-merchivra-database.sql) once, with per-statement autocommit and stop-on-error. It first creates a `NOLOGIN` role, creates the `merchivra` database, then revokes database-level PUBLIC privileges. Setting a strong SCRAM password via trusted GUI or interactive `psql`, enabling LOGIN **afterwards**, and testing `psql -W` are separate manual operations.

**If either the database or role already exists, do not run the script**; inspect state and privileges instead. Never use this procedure on `iot_farm` or as a workaround for a missing IDEA `DB_PASSWORD`. The integrated `deploy/compose.yaml` has its own supported Postgres image bootstrap and must not execute this script. See [`docs/deployment/local-spark-db.md`](deployment/local-spark-db.md) for inspection, safe execution, GUI client transaction settings and password verification.

## V64.1 · springdoc + Orval API 生成（2026-11-01）

本项目现行 V61/V64 财务客户端由 Orval8.44.0 + TanStack Query5 基于真实 Controller 的 OpenAPI 自动生成。生成文件在 apps/web/src/generated，禁止手改。财务 Zod 运行时校验、Session Cookie、CSRF、no-store 保持有效。

默认 MERCHIVRA_CONTRACT_EXPORT_ENABLED=false，生产环境不要启用。仅本地隔离 PG18 环境显式启用时，环回地址可以访问 /v3/api-docs/finance-profits。

从仓库根目录将 curl 的 JSON 输出保存到 /tmp/finance-live.json，执行 node scripts/normalize-finance-openapi.mjs /tmp/finance-live.json apps/web/openapi/finance-profits.json。之后 cd apps/web && npm ci && npm run api:generate && npm run api:check && npm run typecheck && npm test && npm run build。提交正确锁文件、已核对契约与生成产物；CI 与真实 PG18 输出再次对比。旧 docs/api/openapi.json 为 G2 草案，不能用于代码生成。
