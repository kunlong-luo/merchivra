# Merchivra — evidence-first Amazon Seller ERP

**Open-source, self-hosted, pre-alpha ERP.** Its central goal is **auditable, complete SKU/order/period net profit**, not a plausible-looking profit number with missing costs. The project is usable for **isolated development and synthetic tests only**; it is **not approved for production**.

**Verified baseline (2026-11-24).** Java 25, Spring Boot 4.1.2, **Gradle** (no Maven), PostgreSQL 18/Flyway; React 19, **Next.js 16.4.5** App Router, Node 24 and **pnpm 12.20.0 only** (not npm). Next.js is the default. Vite/Nginx exists **only for explicitly selected rollback and synthetic GitHub Pages**.

- Latest independent engineering/product maturity: **[76/100, V231](docs/governance/project-scorecard-v231-2026-11-24.md)** (prior V230 [75/100](docs/governance/project-scorecard-v230-2026-11-24.md)). This is **not** a production-readiness or verified-net-profit percentage.
- **True profit is not verified**: `realizedNetProfit=null`; Amazon Seller/Ads live OAuth is **OFF**; independent bank, freight, customs, WMS and refund COGS evidence plus G1/DEC accounting approval are missing. Production [#104](https://github.com/kunlong-luo/merchivra/issues/104) remains **NO-GO**.

[简体中文](README.zh-CN.md) · [Actual status](docs/current-status.md) · [Current P0 plan](docs/roadmap/current-plan.md) · [Documentation index](docs/README.md) · [Development](docs/development.md) · [Contributing](CONTRIBUTING.md) · [Security](SECURITY.md)

## Current engineering status

V237 full isolated A3 passed (7/7), including the actual PostgreSQL 18 Java Reports service test with a synthetic remote worker. V238–V240 quick CI passed. V241 full A3 passed (7/7); V242 full A3 passed (7/7). V243 quick CI passed but PG18 had a failed step; V244 full A3 passed (7/7), including real Owner HTTP audit rejection. V245 quick CI passed. V246 quick CI passed. V247 quick CI passed; A3 6/7 failed on PG18 trigger alias ambiguity. V248 full A3 passed (7/7), including real PG18 range races. V249 quick CI passed. V250 quick/full workflows failed before jobs started; test not executed. V251 quick CI passed, A3 failed on Bash substitution. V252 full A3 passed (7/7), including actual PG18 composition and Owner HTTP HOLD. V253 full A3 passed (7/7); V254 full A3 passed (7/7), including nonempty HTTP and Chromium. V255 full A3 passed (7/7); V256 quick CI passed, A3 6/7 failed on an ambiguous PG18 trigger variable. V257 quick CI passed; V258 adds Flyway V120 repair and bounded whole-order refund cost candidate replay; full A3 7/7 passed; V259 quick CI passed (no full run found); V260 full A3 7/7 passed; V261 requires current V63 independent review and exact revision audit (full A3 pending), no actual reversal/posting. See [current status](docs/current-status.md).

## What the repository directories actually do

These are the **retained, tracked, functional directories**. Do **not** delete a directory because it happens to contain legacy version names: data migrations, rollback artifacts and failed CI evidence may be essential.

| Directory | What uses it / why it stays | Maintenance boundary |
| --- | --- | --- |
| `apps/server/` | Spring Boot 4.1.2 ERP API, Java domain logic, Session/CSRF, Gradle Wrapper, **forward-only Flyway Vxxx migrations** and tests | **Required runtime**, no Maven; never delete/modify historic Flyway migrations |
| `apps/web/` | React 19, **Next.js App Router** pages, finance/Core workspaces, Orval OpenAPI snapshots/generated clients, Zod, Vitest and pnpm lock | **Required runtime**. `index.html`, `vite.config.ts`, rollback Dockerfile/Nginx belong to explicit rollback/synthetic pages, **not the default server** |
| `deploy/` | Local PostgreSQL18/Next/Spring Compose, separate `spark-db`, production reference and isolated TLS regression overlays | **Required operation/test files**. Never mix alternative base Compose files or run destructive volume commands on real data |
| `ops/` | Monitoring, operational scripts and release evidence/checklists | **Retained operational controls**, not demo-only code |
| `scripts/` | CI contract, documentation, generated-client, browser, database and **disposable-only** HTTP/PG18 regression tests | **Required verification**. Synthetic data-damaging scripts must never run on real customer databases |
| `.github/` | Actions CI, full A3, browser/Pages workflows and dependency maintenance | **Required automation**, not disposable PR remnants |
| `docs/` | Current documentation, operating guides, decisions, per-version immutable evidence and archived historical plans | **Required audit history**. See the classification below; don't confuse archived proposals with current policy |

Root-level `.env.example`, `.gitignore`, `.editorconfig` configure safe local development; `AGENTS.md` is the **sole authoritative agent instruction file**, `DESIGN.md` defines UI practice, `CONTRIBUTING.md` contributions, `SECURITY.md` responsible disclosure, and `LICENSE` licensing. **The obsolete mixed-case `Agent.md` duplicate has been deleted**; no second agent policy should be recreated.

### Which documentation directories are kept, and why

| Directory under `docs/` | Purpose / classification |
| --- | --- |
| `roadmap/` | **Only current execution plan:** [`current-plan.md`](docs/roadmap/current-plan.md); older V108/A3 plans are **historical evidence**. The root [`docs/roadmap.md`](docs/roadmap.md) is explicitly archived, **not another active plan** |
| `governance/` | [Decision/document control](docs/governance/document-control.md), [engineering standards](docs/governance/engineering-standards.md), exact-version scorecards, open G1/DEC approvals and historic audits |
| `finance/` | Original-currency source audits, refund/lot/settlement attribution and actual-profit blockers; [V230 source proof requirements](docs/finance/v230-external-source-proof-dossier-and-owner-workflow.md) |
| `testing/`, `deployment/`, `api/` | CI/A3 policies, PG18/bootstrap/restore safety, real Springdoc/OpenAPI and Orval interface references |
| `architecture/` | Architecture, [diagram index](docs/architecture/diagram-index.md), ADRs and [ADR template](docs/architecture/adrs/template.md) |
| `requirements/`, `product/` | Business acceptance, [core business flows](docs/product/core-flows.md), still-unsigned financial decisions and scope |
| `purchasing/`, `inventory/`, `catalog/`, `operations/`, `analytics/` | Domain implementations, purchasing/FBA, catalog, operational runbooks and business-data quality evidence |
| `security/`, `integrations/` | Session/CSRF/tenant security and Seller/Ads/SP-API integration boundary documentation |
| `design/` | Next UI design, navigation and page-state reference |

**Deletion policy:** remove proven obsolete duplicate drafts and unused aliases; retain deployed code, rollback mechanisms, migration history, policies and version-specific A3/PG18/financial evidence. Git history preserves deleted drafts. The previously duplicated pending-PR V120 supplier note was removed; the canonical [completed V120 supplier trace](docs/purchasing/supplier-followup-return-traces-v120.md) remains. See [the documentation map](docs/README.md) and [document-control rules](docs/governance/document-control.md).

### Directory maintenance

The 17 retained documentation domains are listed above. Retired paths: `docs/ops/` → [operations](docs/operations/operations.md), `docs/ux/` → [design](docs/design/navigation-and-page-states.md), `docs/diagrams/` → [architecture](docs/architecture/diagram-index.md), `docs/processes/` → [product](docs/product/core-flows.md), `docs/templates/` → [ADR template](docs/architecture/adrs/template.md). Removed duplicates and original versions remain recoverable from Git. The root `ops/` is executable tooling, not retired. V240 consolidates V181/V181B in [settlement identity](docs/finance/v181-immutable-settlement-line-identity.md), TEST-001 into [CI policy](docs/testing/ci-policy.md), and the early API adoption note into [dependency adoption](docs/architecture/dependency-adoption.md). Historical originals are linked by exact Git SHA from their canonical replacements. [Document control](docs/governance/document-control.md) governs deletions; the CI guard rejects retired paths and undocumented documentation directories.

## Quick start — isolated local PostgreSQL 18

Requires Docker Engine + Compose v2. In the repository root:

```bash
git clone https://github.com/kunlong-luo/merchivra.git
cd merchivra
cp .env.example .env
# Set a dedicated MERCHIVRA_DB_PASSWORD
# Set MERCHIVRA_BOOTSTRAP_USERNAME and a strong (>=16-character) password
docker compose --env-file .env -f deploy/compose.yaml config -q
docker compose --env-file .env -f deploy/compose.yaml up -d --build
```

Local Next UI: **http://127.0.0.1:3000**; Spring API health **http://127.0.0.1:8080/api/v1/ping**. Local-only ingress does not make the app production approved. With an independently managed PostgreSQL 18 `spark-db`, follow [the separate-role bootstrap guide](docs/deployment/local-spark-db.md) and use **only** `deploy/compose.spark-db.yaml` instead. Never merge the two base Compose files or use `down -v` on retained data.

## Local development and validation

Start the Spring API on `127.0.0.1:8080` with PostgreSQL18; in a separate terminal:

```bash
cd apps/web
corepack enable
pnpm install --frozen-lockfile
pnpm dev         # Next.js 16, not Vite
pnpm api:check   # reviewed Orval-generated contracts
pnpm typecheck
pnpm test
pnpm build
```

Backend tests use `./apps/server/gradlew -p apps/server --no-daemon test`. CI documentation/structure checks use `python3 scripts/check_docs_consistency_v109.py` and `python3 scripts/check_ci_cadence_v108.py`. Full A3 may create **disposable synthetic** database data only in its protected GitHub main workflow; never run PG18 fixture mutations against a personal/production tenant.

All normal development goes directly to **`main`**, no routine feature branches/PRs. Every code change requires the right level of exact-SHA regression evidence; **CI passing never proves external Amazon/Ads/bank authenticity or authorizes production**.

[Public GitHub Pages showcase](https://kunlong-luo.github.io/merchivra/) is **synthetic-only** (no real Seller login, bank or financial posting).
