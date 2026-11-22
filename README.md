# Merchivra ERP

**Open-source, self-hosted Amazon Seller ERP — designed around auditable, evidence-backed cost and profit accounting.**

> **Current baseline · November 22, 2026 · V195 session UX / V194 finance.** The project is **pre-alpha, local/isolated testing only**. The stack is Java 25 / Spring Boot 4.1.2 / Gradle / PostgreSQL 18 and React 19 / **Next.js 16.4.5** / Node 24, using **pnpm 12.20.0 exclusively** for the web app. Next.js is the default local runtime. Vite/Nginx is an explicit rollback and synthetic GitHub Pages build path.
>
> **Verified engineering ≠ production approval.** The latest [V193 full A3 #45012495202](https://github.com/kunlong-luo/merchivra/actions/runs/45012495202) passed for its exact [`6008832` code SHA](https://github.com/kunlong-luo/merchivra/commit/600883236a3669f51b6703229447d93499b271cd). The newer [V195 quick CI #45016097852](https://github.com/kunlong-luo/merchivra/actions/runs/45016097852) passed for [`2136375`](https://github.com/kunlong-luo/merchivra/commit/2136375590abaae2373c565a1de930aa4ce45483); **V194 fingerprint changes still need their own full PG18/A3**. Real Seller/Ads OAuth, live bank/fee verification, and production are **NOT approved**; [#104](https://github.com/kunlong-luo/merchivra/issues/104) remains **NO-GO**.

[简体中文](README.zh-CN.md) · [Current status](docs/current-status.md) · [Active priorities](docs/roadmap/current-plan.md) · [Documentation hub](docs/README.md) · [Engineering standards](docs/governance/engineering-standards.md) · [Deployment](docs/deployment/local-spark-db.md) · [Development](docs/development.md) · [Security](SECURITY.md)

## Implemented functionality and hard limitations

Merchivra has local, tenant/Marketplace-scoped seller, catalog, orders, purchasing/FBA, inventory/lot, exceptions, and finance flows backed by Spring Boot and PostgreSQL. The React/Next.js workbenches use same-origin API access, Session/CSRF protections, and reviewed local business evidence. V64 provides **known-scope contribution**; V91–V104 and V162–V194 add manual source review, allocation conservation, settlement identity, full-period blockers, single source ownership and **read-only original-currency profit sensitivity**.

**It does not yet calculate independently certified, fully loaded net profit.** Real Amazon/Ads/bank provenance, refund COGS reversals, FX/tax/accounting policies, independently verified warehouse receipts and G1 financial approvals remain open. A locally `APPROVED_UNVERIFIED` source cannot silently become a posted expense; `realizedNetProfit=null` is not zero. See the [scope matrix](docs/current-status.md) and [profit evidence plan](docs/roadmap/current-plan.md).

## Quick start — bundled PostgreSQL 18

Docker Engine and Compose v2 are required. From the repository root:

```bash
git clone https://github.com/kunlong-luo/merchivra.git
cd merchivra
cp .env.example .env
# Set a dedicated MERCHIVRA_DB_PASSWORD
# Set MERCHIVRA_BOOTSTRAP_USERNAME and a strong password of at least 16 characters
docker compose --env-file .env -f deploy/compose.yaml config -q
docker compose --env-file .env -f deploy/compose.yaml up -d --build
```

The default three services are `postgres`, `server`, and `web` (Next.js Node), all with loopback-only ingress. Open **http://127.0.0.1:3000**; the Spring health ping is **http://127.0.0.1:8080/api/v1/ping**.

If you already operate PostgreSQL 18 through `spark-db`, follow the [dedicated role/database bootstrap](docs/deployment/local-spark-db.md) first, then launch only the ERP API and web:

```bash
docker compose --env-file .env -f deploy/compose.spark-db.yaml config -q
docker compose --env-file .env -f deploy/compose.spark-db.yaml up -d --build
```

Do not combine the two base Compose files or run `down -v` on data you want to retain.

## Local development — pnpm only

Start Spring Boot locally on **127.0.0.1:8080** with an authorized PostgreSQL 18 connection; then:

```bash
cd apps/web
corepack enable
pnpm install --frozen-lockfile
pnpm dev
```

`pnpm dev` and `pnpm build` use Next.js 16 (port 3000); `pnpm api:check` validates Orval contracts; `pnpm typecheck` and `pnpm test` validate the web app. `pnpm dev:vite` / `pnpm build:vite` are explicit rollback/testing commands only, and `vite-web` requires its opt-in Compose profile. The sole JS lockfile is `apps/web/pnpm-lock.yaml`. Detailed setup, safe recovery and optional Redis integration are covered in the [development](docs/development.md) and [deployment](docs/deployment/local-spark-db.md) guides.

## Delivery policy

Regular work is committed **directly to `main`**, without routine feature branches/PRs. Every push runs quick CI; finance, stock, identity and database boundary changes also require focused tests and, where appropriate, a fresh exact-commit full A3. See the [test policy](docs/testing/ci-policy.md) and [contribution guide](CONTRIBUTING.md). The primary roadmap is to verify independent expenses and complete evidence-backed profit, **not** to replace unknown costs with zero.

[Public GitHub Pages showcase](https://kunlong-luo.github.io/merchivra/) is **synthetic-only**: no authenticated ERP, live Seller account, bank integration, database or writes.

[License](LICENSE) · [Security reporting](SECURITY.md) · [Product design guidance](DESIGN.md).
