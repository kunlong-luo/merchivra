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

**Java 25 LTS / Spring Boot 4.1.2 / Spring Security 7 / Jackson 3 / Maven**, **Vue 3.5 / TypeScript 6.0.3 / Vite 8.3.5 / Vitest 5 / Node.js 24 LTS / npm ci**, TanStack Vue Query / Zod, PostgreSQL 18, Redis 7, Docker Compose.

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
- **PostgreSQL 18 + Nginx + Spring Boot 全链路**：[run 41153634845（attempt 2）](https://github.com/kunlong-luo/merchivra/actions/runs/41153634845) 成功。日志明确确认：Owner Session/CSRF、首次 V11A 页面提交前安全水位不前进、实际重启 Java 容器后恢复同一 job/page/epoch、第 2 页完整提交、幂等订单数、完整水位、外部 Marketplace 拒绝、退出后 401。也验证 V1–V11 Flyway 迁移以及旧 V6 分页恢复。
- 发现并修复一项真实幂等缺陷：V11A 必须共用已有 Sandbox 的规范化来源键 `SANDBOX-MOCK-US-001` 等，避免同一虚构订单入库成 `MOCK-US-001` 和 `SANDBOX-MOCK-US-001` 两行。修复提交：[4ca08712](https://github.com/kunlong-luo/merchivra/commit/4ca087122eb1c26f0bfd4867da27afad4bf4237e)。
- **仅证明固定虚构数据**的同步执行机制；真实 Amazon Orders 角色、LIVE 只读适配器、生产 token 过期/429 重试、真实订单明细和运维容量验收仍未实现。所有真实 Amazon 出站默认关闭。

## PostgreSQL 18 数据卷升级提示（2026-10-31）

Compose 使用 `postgres:18-alpine`，新卷 `pgdata18:/var/lib/postgresql`。此前 17 的 `pgdata` **不自动升级、不自动删除**。已有数据须先按 [PostgreSQL 17→18 迁移指南](docs/deployment/postgresql-18-upgrade.md) 备份和恢复；不能将 17 的数据文件直接挂载到 18 或执行 `down -v` 处理生产数据。


## PostgreSQL 18 / V11B 验证（2026-10-31）

[PG18 + V1–V12 Compose](https://github.com/kunlong-luo/merchivra/actions/runs/41154500844) 与 [Java/Vue CI](https://github.com/kunlong-luo/merchivra/actions/runs/41154501014) 已通过。V11B 已加入 Orders 角色审核证据结构和仅服务端的只读网关，**并未开放真实 Amazon Orders 同步**；原 PostgreSQL 17 数据卷需要另外迁移。详见 [V11B 文档](docs/integrations/orders-worker-v11.md)。


## V11C LIVE Orders 只读 Worker 安全闸（2026-10-31）

- V11C `OrdersSyncConfiguration` 显式选择 `MOCK`（默认）或 `LIVE`（需要独立批准 `MERCHIVRA_ORDERS_SYNC_LIVE_APPROVED=true`）；LIVE 要求正式 OAuth、真实 Seller Verification、V12 Orders 角色证据、生产 SP-API 只读，Demo/Sandbox/远端写入必须关闭。默认不触发任何生产出站。
- `OrdersSyncService` 现在把任务 mode 与记录 `data_origin` 绑定，禁止既有 MOCK 任务继承 LIVE 任务游标；提交 LIVE 订单/页日志/安全水位时重新核验并事务锁住账户、授权、角色、参与证据，授权撤销后事务回滚且转暂停。
- Flyway V13 放开 ops_finding 只读建议记录的 LIVE 来源分类，Web 必须区分 Amazon API 已同步/模拟/未核验；LIVE 绝不代表已记账或 Amazon 写入批准。
- 所有真实角色审核、真实 LWA、网络 429/Token TTL 与生产部署仍需独立实测。项目 CI 必须保留 Mock-only 全链路及默认角色表为空的断言。详情 `docs/integrations/orders-worker-v11.md`。


## V15 多管理员团队与 SECURITY_REVIEWER 授权（2026-10-31）

- 新增 `local_owner_invitation` + `local_team_audit`（Flyway V15）。默认 `MERCHIVRA_TEAM_ENABLED=false`；开启后当前 OWNER 生成 256-bit 24h 单次邀请令牌（SQL 只存 SHA256），受邀用户在匿名但 CSRF 防护的 `/api/v1/auth/invitations/accept` 自设 16+ 位 BCrypt 密码，获得同租户 OWNER 权限。只适用于**另一位全权限管理员**，不是普通员工 RBAC。
- `LocalTeamService` 通过数据库事务、租户行锁和当前 OWNER Session 控制邀请、撤销、审核员角色授权。OWNER 不能给自己授予 `SECURITY_REVIEWER`，移除成员立即失去租户访问且不能移除最后一个 OWNER。所有变更写 `local_team_audit`，原始 token 不入库、不记日志、不在列表查询返回。
- 独立的有效 OWNER+SECURITY_REVIEWER 才可执行 V14 Orders 应用角色审核。V12 Vault 与 V14 审核增加 Reviewer OWNER 角色要求。团队页面在前端单独展示，未启用时清晰提示；激活入口位于登录页。尚无员工细粒度权限、OIDC/MFA/邮件、正式 Amazon LIVE 验收。
- [V15 说明与安全边界](docs/security/team-v15.md)。CI 包括 PostgreSQL 18 HTTP 双管理员邀请激活、身份/CSRF、审核员授予/撤销、成员撤销旧会话立刻失效，未经实际运行不得宣称通过。


## V15 实际验收证据（2026-10-31）

- [Java 25 / Vue TypeScript / Vitest / Vite CI 41159636629](https://github.com/kunlong-luo/merchivra/actions/runs/41159636629) **成功**。
- [PostgreSQL 18 + Java25 + Nginx Compose 41159526524](https://github.com/kunlong-luo/merchivra/actions/runs/41159526524) **成功**，执行 Flyway V1–V15，继续验证此前业务 Mock、订单双页 checkpoint、后端真实容器重启；包含 V15 一次性邀请、匿名/CSRF 拒绝、令牌重放失败、另一位 OWNER 独立账号登录、自授权 reviewer 拒绝、授予/撤销 reviewer、撤销 OWNER 后已登录旧 Session 拒绝访问、仍保留有效 OWNER。日志有 `PASS V15: PG18 2-Owner invitation ... membership immediate revocation`。
- [Chrome 360px responsive UI 41159636546](https://github.com/kunlong-luo/merchivra/actions/runs/41159636546) **成功**，合成双管理员数据验证「团队与权限」成员表、Reviewer 标记、邀请入口和移动端布局，并保留此前 Mock/LIVE 订单面板的响应式检查。
- 上述测试**不代表**生产安全审计、邮件邀请、MFA、SSO、员工/财务分权或 Amazon 正式角色审批；团队管理需显式开启，LIVE SP-API 仍默认关闭。

## V16 店铺级只读运营员工（2026-10-31）

- Flyway V16 为 V15 邀请增加 `invite_role` 与租户约束的 `seller_account_id`；旧邀请默认 OWNER 不变。`OPERATOR_READ` 必须绑定 Seller Account，禁止全租户该角色、非法或跨租户账号、未审核权限自动升级。Owner 邀请的员工激活后仅获得指定账号的店铺级只读角色，而不是 OWNER。
- 原有敏感控制器的 `TenantAccessService.currentTenant` 继续**只允许 OWNER**；新增 `currentReadTenant/readableTenants` 仅用于身份/核心只读业务端点。核心 Marketplace 列表、Listing、Orders、FBA 每个请求重新联查数据库当前 membership、用户状态、role_binding 与 Seller Account；未知及跨账号 ID 均返回同样 404。所有授权记录以服务器 Session userId 为准。
- `ReadOnlyStaffGuardFilter` 在业务控制器之外额外阻止员工读取旧 Demo 非授权接口、执行 Mock 同步、运营规则、Amazon OAuth、账户配置或任何其他非白名单 API。唯一放行额外工作区 API 是 GET `/api/v1/seller-marketplaces/**`，具体订单/库存查询仍二次校验角色；旧敏感管理员 API 未因员工登录而开放。
- 前端角色信息来自 `/api/v1/auth/me.isOwner`；员工只看「核心业务数据」，不显示团队、运营自动化、经营演示、Seller Setup 和同步按钮。隐藏 UI 不是安全边界。团队页面可以选择邀请 OWNER 或 `OPERATOR_READ`，后者要求选定 Seller Account。
- 这只是 **V16 第一层员工权限（店铺级只读运营）**。尚不支持采购写、财务阅览、普通运营修改、单独 Marketplace 级别授权、OIDC/MFA 和批量授权矩阵。真实 Amazon LIVE 出站依旧默认关闭。
- CI 将使用 PostgreSQL 18：员工一次性邀请→登录→只看到一个 Seller Account 所属 Marketplace→读取其订单/Listing/FBA→其他 Seller Account 所属站点 404→团队/运营/账户等管理员 API 403→Owner 撤销后旧 Session 无法读取。此处描述验收目标，必须以成功 workflow 为准。
