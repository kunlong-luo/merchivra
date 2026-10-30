# Merchivra ERP

**Open-source commerce operations platform — start with Amazon Seller.**

[简体中文](README.zh-CN.md) · [完整设计文档](docs/README.md) · [API 契约](docs/api/README.md) · [MVP](docs/requirements/mvp.md) · [Architecture](docs/architecture/overview.md) · [Roadmap](docs/roadmap.md) · [Contributing](CONTRIBUTING.md)

> **Status: pre-alpha / authenticated core read workspace plus synthetic Mock demo.** First local admin login, Spring Security Session/CSRF, tenant-scoped Marketplace/Listing/order/FBA read endpoints and **local-only unconfigured Seller Account setup with audit** are implemented. New tenants correctly show no real seller data. Complete multi-role security, real Amazon integration and accounting are NOT implemented. Not production-ready.

## Goals

- Self-hosted, extensible Amazon Seller ERP with a modular monolith architecture.
- Start read-only: accounts → listings/products → orders → FBA inventory → estimated profit.
- Reliable, auditable sync: checkpointing, idempotency, retries, and data reconciliation.
- Multi-tenant by design; new marketplace adapters in future versions.

## Technology

**Java 25 LTS / Spring Boot 4.1.2 / Spring Security 7 / Jackson 3 / Maven**, **Vue 3.5 / TypeScript 6.0.3 / Vite 8.3.5 / Vitest 5 / Node.js 24 LTS / npm ci**, TanStack Vue Query / Zod, PostgreSQL 17, Redis 7, Docker Compose.

See [October 2026 toolchain migration notes](docs/architecture/stack-upgrade-2026-10-30.md) and [Agent engineering policies](Agent.md). Frontend dependencies are now locked in `apps/web/package-lock.json`.

Engineering conventions are informed by [Pagevela](https://github.com/kunlong-luo/pagevela) (CI, dependency hygiene and persistence coordination), while retaining the ERP-specific Java/Vue architecture. See [dependency decisions](docs/architecture/dependency-adoption.md).

## Quick start (local Mock demonstration only)

Requires Docker with Compose.

```bash
cp .env.example .env
# Set a strong MERCHIVRA_DB_PASSWORD, and set your own
# MERCHIVRA_BOOTSTRAP_USERNAME and MERCHIVRA_BOOTSTRAP_PASSWORD (>=16 chars).
# No built-in password is provided.
docker compose --env-file .env -f deploy/compose.yaml up --build
```

- Web: http://localhost:3000
- API: http://localhost:8080/api/v1/ping
- Mock overview: http://localhost:8080/api/v1/demo/overview (only with `MERCHIVRA_DEMO_ENABLED=true`)
- Actuator health: http://localhost:8080/actuator/health

**Local session login protects APIs and demo endpoints. A [one-shot PostgreSQL V1–V4 smoke](https://github.com/kunlong-luo/merchivra/actions/runs/41059134533) passed, but full Docker Compose, role security and production deployment are still unverified. Keep it on localhost; do not expose publicly.**

Local development instructions: [docs/development.md](docs/development.md) · [authenticated core read APIs](docs/api/core-read-api.md) · [local Seller setup](docs/api/local-seller-setup.md) · [Mock demo guide](docs/demo.md) · [local authentication](docs/security/local-auth.md).

## Roadmap

- **M0** Engineering foundation: docs, runnable scaffold, local deployment, CI.
- **M1** Seller account and SP-API connection with secure credential storage and a mock adapter.
- **M2** Listings, order and FBA inventory synchronization.
- **M3** Product costs, financial transactions, estimated margin and reconciliation.
- **M4** Stability, security, auditability, beta release.

Scope and acceptance criteria: [MVP PRD](docs/requirements/mvp.md).

## Security and data use

Never commit Amazon refresh tokens, client secrets, personal customer data or real seller exports. Amazon developer approval, API role permissions and data-protection compliance are required for real integrations. See [SECURITY.md](SECURITY.md).

## License

Licensed under the [Apache License 2.0](LICENSE). Contributions welcome.

## Full local Mock simulation

The **Core ERP** page now has an opt-in simulated SP-API replay for a local **UNCONFIGURED** US/DE Marketplace. It writes tenant-scoped facts marked `MOCK` using fictional paginated Orders, Listing, and FBA fixtures, then reuses the protected core APIs. Finance remains **NOT_AUTHORIZED**; no real Amazon credentials or data are used. See [full chain verification](docs/testing/mock-full-chain.md). The independent Compose smoke workflow is opt-in, not a new mandatory CI gate.

**Verified:** [Compose 全链路成功运行（2026-10-30）](https://github.com/kunlong-luo/merchivra/actions/runs/41061679196) built the Compose Nginx/Java/PostgreSQL stack, ran fictional US+DE Orders/Listing/FBA replay through the protected APIs, and confirmed persistent facts after restarting the server. This is *not* a live SP-API integration or production certification.
