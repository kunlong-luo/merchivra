# Merchivra ERP

**Open-source commerce operations platform — start with Amazon Seller.**

[简体中文](README.zh-CN.md) · [完整设计文档](docs/README.md) · [API 契约](docs/api/README.md) · [MVP](docs/requirements/mvp.md) · [Architecture](docs/architecture/overview.md) · [Roadmap](docs/roadmap.md) · [Contributing](CONTRIBUTING.md)

> **Status: pre-alpha / read-only Mock workspace.** Local demonstration pages now show invented seller accounts, listings, orders and FBA stock via a Java Mock Gateway. Real Amazon integration, authentication, tenant isolation and accounting workflows are NOT implemented. Never use real credentials or expose the unauthenticated demo publicly.

## Goals

- Self-hosted, extensible Amazon Seller ERP with a modular monolith architecture.
- Start read-only: accounts → listings/products → orders → FBA inventory → estimated profit.
- Reliable, auditable sync: checkpointing, idempotency, retries, and data reconciliation.
- Multi-tenant by design; new marketplace adapters in future versions.

## Technology

Java 21 / Spring Boot 3.5, Vue 3 / TypeScript / Vite / TanStack Vue Query / Zod / Vitest, PostgreSQL, Redis, Docker Compose.

Engineering conventions are informed by [Pagevela](https://github.com/kunlong-luo/pagevela) (CI, dependency hygiene and persistence coordination), while retaining the ERP-specific Java/Vue architecture. See [dependency decisions](docs/architecture/dependency-adoption.md).

## Quick start (local Mock demonstration only)

Requires Docker with Compose.

```bash
cp .env.example .env
# Set a strong MERCHIVRA_DB_PASSWORD in .env
docker compose -f deploy/compose.yaml up --build
```

- Web: http://localhost:3000
- API: http://localhost:8080/api/v1/ping
- Mock overview: http://localhost:8080/api/v1/demo/overview (only with `MERCHIVRA_DEMO_ENABLED=true`)
- Actuator health: http://localhost:8080/actuator/health

**This scaffold has no login or API authorization yet. Keep it on localhost; do not expose it to the public internet.**

Local development instructions: [docs/development.md](docs/development.md) · [Mock demo guide](docs/demo.md).

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
