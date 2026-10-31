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

The simulated source can now be advanced **one Orders page at a time**, with V6 PostgreSQL page commits/conditional epochs and a progress viewer in the Core ERP workspace. This is a manual Mock checkpoint prototype, **not** a production scheduler or live Amazon pagination. See [TEST-004](docs/testing/mock-full-chain.md).

## Amazon official SDK integration (code-first, disabled by default)

The backend now includes Amazon's **official Java SP-API SDK 1.13.0** with generated API types across many families, a server-only LWA/region configuration factory, typed core read operation wrappers, conservative Orders v2026/FBA payload parsers, and offline credential/role/PII safety tests. **No live Seller authorization or Amazon production traffic exists yet**, and remote integration is disabled by default. See [INT-004 SP-API code implementation](docs/integrations/sp-api-code-integration.md).

**Seller authorization (code only):** Flyway V7 and a default-disabled public-app OAuth handoff, encrypted per-tenant Seller credential vault, local revoke/audit and worker-only verified grant gate are in place. No live Amazon Seller authorization or KMS envelope-rotation validation has been performed. See [SEC-004](docs/security/seller-oauth.md).

**V8/V9 implementation (disabled by default):** The code now has an OWNER-triggered Sellers v1 Marketplace participation check with short-lived Worker Vault evidence, plus versioned AES-256-GCM keys and per-account ciphertext rewrap protected by Session/CSRF and row locks. The independent Seller ID and application roles have **not** been verified; Finance/RDT access remains blocked. This is not a working live Amazon sync Worker, and local encryption-key rotation is not LWA Refresh Token renewal. See [SEC-005](docs/security/seller-verification.md), [SEC-006](docs/security/seller-key-rotation.md).


## V10 运营自动化工作台

- [竞品能力矩阵与路线图](docs/product/competitor-gap-and-automation.md)：对照 Sellercloud、Linnworks 和 Helium 10 的订单自动化、库存风险、采购与财务模块，标记已完成、待集成和高风险写入能力。
- [OPS-001](docs/operations/ops-automation.md)：提供只读事实上的异常识别、持久待办、认领完成、审计与显式开启的定期巡检；默认无需调用真实 Amazon。建议先在本地模拟导入数据，然后通过“运营自动化”工作台手动执行巡检。
- **没有**实现物流发货、广告自动调价、正式补货单/采购审批、财务对账或正式 Amazon Orders Worker；功能对标不是宣称生产连接已全部完成。


## V11A 实际验收结果（2026-10-31）

- **Java 25 / Maven 与 Vue / TypeScript / Vitest / Vite**：[CI 41153634800](https://github.com/kunlong-luo/merchivra/actions/runs/41153634800) 成功。
- **实际 Chromium UI 测试**：[run 41153253770](https://github.com/kunlong-luo/merchivra/actions/runs/41153253770) 成功，包含移动端 V11A Mock-only 检查点面板。
- **PostgreSQL 17 + Nginx + Spring Boot 全链路**：[run 41153634845（attempt 2）](https://github.com/kunlong-luo/merchivra/actions/runs/41153634845) 成功。日志明确确认：Owner Session/CSRF、首次 V11A 页面提交前安全水位不前进、实际重启 Java 容器后恢复同一 job/page/epoch、第 2 页完整提交、幂等订单数、完整水位、外部 Marketplace 拒绝、退出后 401。也验证 V1–V11 Flyway 迁移以及旧 V6 分页恢复。
- 发现并修复一项真实幂等缺陷：V11A 必须共用已有 Sandbox 的规范化来源键 `SANDBOX-MOCK-US-001` 等，避免同一虚构订单入库成 `MOCK-US-001` 和 `SANDBOX-MOCK-US-001` 两行。修复提交：[4ca08712](https://github.com/kunlong-luo/merchivra/commit/4ca087122eb1c26f0bfd4867da27afad4bf4237e)。
- **仅证明固定虚构数据**的同步执行机制；真实 Amazon Orders 角色、LIVE 只读适配器、生产 token 过期/429 重试、真实订单明细和运维容量验收仍未实现。所有真实 Amazon 出站默认关闭。
