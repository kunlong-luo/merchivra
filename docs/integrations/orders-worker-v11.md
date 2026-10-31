# V11A · Durable Orders incremental Worker (safe synthetic first)

Status: **code implemented; full CI and Compose evidence is required before marking verified**.

## Scope and Amazon compatibility

Amazon's Orders API v2026-01-01 uses `searchOrders`, `lastUpdatedAfter`,
`lastUpdatedBefore`, `paginationToken`; the pagination token expires after
24 hours. Avoid `includedData=BUYER` / `RECIPIENT` in initial sync to avoid
unnecessary buyer PII. The existing `SpApiReadOperations.searchOrders` and
`SpApiPayloadDecoder.orders2026` are the API/DTO integration seam.

References:
- https://developer-docs.amazon.com/sp-api/docs/orders-api
- https://developer-docs.amazon.com/sp-api/lang-us/docs/orders-api-migration-guide
- https://developer-docs.amazon.com/sp-api/reference/searchorders

**V11A does not yet call Amazon.** It introduces a reliable worker lifecycle
with a strict fixture-only implementation. It will refuse to start if live
SP-API egress is enabled, if Demo is off, or if the account is anything other
than a current-tenant LOCAL/UNCONFIGURED account. It must NOT be repurposed
to process a real verified grant until an independently checked Orders role,
Seller identity consistency and token revocation workflow exist. Merely
having the official SDK installed is not evidence of permission.

## Implemented

- Flyway V11: `orders_sync_job` (one active cursor per tenant + marketplace,
  immutable window during paging), per-page `orders_sync_page_commit`
  with generation for token restart; `sales_order` source schema additionally
  allows `LIVE` for future role-verified reads, but V11A only writes `MOCK`.
- Backend: `GET /api/v1/orders-sync/{mp}`, `POST .../start`, `POST .../step`.
  All derive tenant from Session; POST requires CSRF. No cursor, LWA token,
  arbitrary time window, Seller ID or tenant ID accepted from browser.
- Start initial window: 24h ending 2min before now; after full window
  success, next start overlaps its watermark by 5min. A completed window
  **alone** advances the safe watermark.
- Step: transactionally claim tenant-scoped lease with UUID owner + incremented
  fencing epoch; source fetch occurs outside transaction; then recheck local
  Seller scope and atomically upsert synthetic orders, journal page and advance
  cursor. Stale worker cannot commit after another claim.
- Token: if a pending token is older than 20h, restart the **same window**
  from page 0 with a new generation. Upsert keys prevent duplicates.
- Errors: safe code only, 429/502/503/504/500 -> delayed retry with bounded
  exponential delay; 401/403 and unexpected errors -> PAUSED; 400 for pending
  cursor -> restart window. No raw Amazon response bodies or secrets in logs
  or API responses.
- Optional scheduler: one due page per job, bounded batch (10), disabled by
  default. Mock fixture can page immediately; production operation-specific
  default pacing would conservatively wait at least 180s between requests.
- Core UI: under the optional local test tools disclosure, show the
  Mock-only worker status, safe watermark and manual one-page controls.
  Source authorization and real worker are not exposed in navigation.

```dotenv
MERCHIVRA_ORDERS_SYNC_ENABLED=false
MERCHIVRA_ORDERS_SYNC_SCHEDULER_ENABLED=false
MERCHIVRA_ORDERS_SYNC_SCHEDULER_MS=30000
```

Only disposable local Demo environments may set the first flag to true; in
V11A production environments the setting must remain false. Never enable
`MERCHIVRA_SPAPI_ENABLED` alongside this fixture source.

## Test contract

`scripts/smoke_orders_sync.py` runs after the existing Mock full-chain in
PostgreSQL17+Java25+Nginx Compose. Exercises: 401 unauth, POST without CSRF 403,
OWNER start, idempotent start, page 1 no watermark, page 2 complete with
watermark, repeated step denied, no extra orders, new overlapping window,
foreign marketplace 404 and logout 401.

The regular Java/Vue CI covers isolated source guardrails and web CSRF/status
schema. Browser acceptance is separate. All tests use invented data only.

## Gaps before actual Amazon production enablement

- Independent Orders role approval evidence in vault: currently
  `SpApiSellerGrantVault.loadVerified` has an empty approvedRoles set.
  Do **not** bypass this; build operation-specific role evidence and guard.
- Official typed SDK Orders call must be connected to a trusted worker
  backend with production rate limit/Retry-After, request window validation,
  token expiry and critical revocation tests. Current worker's page logic is
  tested against only the fixed synthetic source.
- Need actual `orderItems` ingestion and provenance-safe business/fulfillment
  interpretation, page replay amid authorized grant rotation, multi-node
  real network failure/retry testing, quotas, backfill and Seller PII retention.
- Need production key management, MFA/RBAC, monitoring, backup restore and
  full production security review. No full Amazon Orders sync is claimed here.


## V11A PostgreSQL restart validation (2026-10-31)

The opt-in Compose regression runs `scripts/smoke_orders_sync.py` in two stages:
first, an authenticated Owner starts a LOCAL/UNCONFIGURED US Marketplace job,
commits page 1, and confirms watermark is still NULL; then the backend container
is actually restarted and a new login checks that the same job/page/lease epoch
persisted. It commits the final page, confirms the safe watermark and no extra
orders, starts the next overlapping window, and verifies foreign ID 404 and
logout 401. This is a **Mock-only** Worker test. Its real run evidence must
come from the completed GitHub Actions workflow, not inferred from code.


## V11A 实际验收结果（2026-10-31）

- **Java 25 / Maven 与 Vue / TypeScript / Vitest / Vite**：[CI 41153634800](https://github.com/kunlong-luo/merchivra/actions/runs/41153634800) 成功。
- **实际 Chromium UI 测试**：[run 41153253770](https://github.com/kunlong-luo/merchivra/actions/runs/41153253770) 成功，包含移动端 V11A Mock-only 检查点面板。
- **PostgreSQL 17 + Nginx + Spring Boot 全链路**：[run 41153634845（attempt 2）](https://github.com/kunlong-luo/merchivra/actions/runs/41153634845) 成功。日志明确确认：Owner Session/CSRF、首次 V11A 页面提交前安全水位不前进、实际重启 Java 容器后恢复同一 job/page/epoch、第 2 页完整提交、幂等订单数、完整水位、外部 Marketplace 拒绝、退出后 401。也验证 V1–V11 Flyway 迁移以及旧 V6 分页恢复。
- 发现并修复一项真实幂等缺陷：V11A 必须共用已有 Sandbox 的规范化来源键 `SANDBOX-MOCK-US-001` 等，避免同一虚构订单入库成 `MOCK-US-001` 和 `SANDBOX-MOCK-US-001` 两行。修复提交：[4ca08712](https://github.com/kunlong-luo/merchivra/commit/4ca087122eb1c26f0bfd4867da27afad4bf4237e)。
- **仅证明固定虚构数据**的同步执行机制；真实 Amazon Orders 角色、LIVE 只读适配器、生产 token 过期/429 重试、真实订单明细和运维容量验收仍未实现。所有真实 Amazon 出站默认关闭。

## 2026-10-31 数据库基线

新部署及本次后的 Mock Compose 以 PostgreSQL 18 为准（正式已通过的旧 `run 41153634845` 是 PostgreSQL 17 历史证据），旧卷升级规则见 [PG18 手册](../deployment/postgresql-18-upgrade.md)。


## V11B · Orders 角色证据与只读适配器（2026-10-31）

- Flyway **V12** 新增 `spapi_orders_role_evidence`。Seller v1 Marketplace 参与状态**不能证明**应用具有 Orders API 角色，因此不会自动创建角色证据。每条证据按 tenant/account 绑定、审核者、SHA-256 外部证据摘要、审核时间、有效期（最多 30 天）、显式撤销，并强制关联当前 Seller 授权的 `verified_at`；重新核验/吊销 Seller 后旧证据无效。
- `SpApiSellerGrantVault.loadOrdersApproved` 在原先租户/账号/Marketplace/连接状态/24h Seller 核验证据门禁上，再检查独立的 `Inventory and Order Tracking` 角色审核证据。无证据或过期绝不返回带角色的 Seller Scope。
- `SpApiRequest` 与官方 SDK 的 `SpApiOfficialModules.searchOrders/ordersDetail` 都新增按操作强制检查角色。当前仅允许精确的 `Inventory and Order Tracking`，比 Amazon 对 Orders API 公布的角色清单更保守。
- `ApprovedOrdersReadGateway` 实现只读 Orders v2026-01-01 的服务端适配器：从租户 Marketplace 解析 Seller Account，前置校验授权与角色，调用已有审查过的 `SpApiReadOperations.searchOrders`，按官方响应数据结构解析订单、强制校验 Marketplace 匹配，并在返回前重新检查 Seller 授权，防止网络读取期间撤销授权后继续使用结果。
- **仍不注册自动 Spring Bean、不暴露 HTTP API、不挂接到 V11A 的 MOCK-only 写入 Worker**。目前没有证据登记/审核 UI，也没有直接连接真实卖家的生产读操作；不能通过增加上述代码宣称已实现 Seller 角色核验或真实 Amazon 同步。
- 单元测试覆盖缺少 Seller/角色拒绝出站、无效时间窗口、市场错配、请求途中撤销授权、响应结构与无 PII 读取；PostgreSQL 18 Compose 检查 V1–V12 全部迁移且默认角色证据表为空。

Amazon 官方角色文档：https://developer-docs.amazon.com/sp-api/docs/orders-api 。


## Verified PG18 / V11B (2026-10-31)

- [Java/Vue CI 41154501014](https://github.com/kunlong-luo/merchivra/actions/runs/41154501014): success.
- [PostgreSQL 18 / V1–V12 full-chain 41154500844](https://github.com/kunlong-luo/merchivra/actions/runs/41154500844): success, actual PG18 mount, 0 preseeded Orders permissions, Mock replay + restart recovery.
- Older PostgreSQL 17 data volumes were **not upgraded** by this test. See [migration procedure](../deployment/postgresql-18-upgrade.md). Production Amazon Orders still blocked pending independently reviewed application roles and live-source authorization.


## V11C · Production Orders read source behind explicit safety gates (2026-10-31)

V11C connects the independently role-checked V11B `ApprovedOrdersReadGateway` to the **existing V11A durable Worker**, but **does not enable it automatically**. Default deployment behavior remains unchanged: `MERCHIVRA_ORDERS_SYNC_ENABLED=false`, `MERCHIVRA_ORDERS_SYNC_MODE=MOCK`, `MERCHIVRA_ORDERS_SYNC_LIVE_APPROVED=false`.

Two mutually exclusive `OrdersSyncSource` implementations are now supported:

- `MOCK`: original Demo-only fixed fixture, permitted only if Demo is on, SP-API outbound is off and LIVE approval is off; data origin `MOCK`.
- `LIVE`: `LiveOrdersSyncSource` delegates to V11B's read-only `ApprovedOrdersReadGateway.search`. Configuration refuses startup unless **all** of these are true: sync enabled, explicit mode `LIVE`, explicit LIVE approval, Demo off, SP-API on, SP-API sandbox **off**, SP-API writes **off**, OAuth on, and Seller participation verification on. Requires a configured cryptographic Vault, LWA app credentials, live Seller verification and **separate audited Orders role evidence** per Seller. No such evidence is automatically seeded or obtained from Marketplace participation.

The persistent worker now reads and writes a mode-specific `MOCK`/`LIVE` value and rejects a stored job when its mode does not match the configured source. A `MOCK` cursor cannot be relabelled or reused as `LIVE`. In LIVE mode the worker locks tenant, Marketplace, connected Seller account, verified grant, Orders role evidence and current Marketplace participation with `FOR SHARE` in the commit transaction; a concurrent revocation cannot overtake the final authorization check. It re-validates both Marketplace and independently reviewed roles immediately before committing a page; a revoked role causes the transaction to roll back, the job to pause, and **no** page, order or safe-watermark commit. A successful page and its cursor are still atomic with source rows. Source failures remain sanitized; no token, buyer data or raw response logged.

Orders from an authorized LIVE read carry `data_origin=LIVE` in `sales_order`; this never authorizes financial settlement, refunds, procurement or Amazon writes. Frontend core order validation and operator labels now explicitly distinguish `LIVE` / `MOCK` / `UNVERIFIED`. Flyway V13 extends the advisory `ops_finding` provenance constraint for LIVE records, without enabling automated financial decisions. Real sales orders' item details and restricted PII are not ingested.

**Security / acceptance limitation:** this stage adds the wiring and rejects unauthorized configurations in automated tests. No real Amazon Seller credentials or separately reviewed role evidence are available in project tests, and this version has **not** completed a production Amazon Orders read, real throttle/TTL testing, or operator-controlled Orders role attestation. The existing reviewed `SpApiReadOperations` transport powers this path; generating SDK classes is not by itself proof of a production integration. Keep LIVE **disabled** until external Amazon app/role approval, vault and key management, production read-only networking, revocation, privacy, and operational approval are complete.

CI checks both synthetic source modes in Java tests; the actual PostgreSQL 18 Compose workflow remains MOCK-only and asserts zero LIVE orders and no seeded role evidence. It also tests repeated pagination, restart recovery and tenant/session/CSRF. Evidence of passing requires the completed workflow run—not just committed tests.



## V11C verified evidence (2026-10-31)

- [Java 25 + Vue/Vitest/Vite CI passed](https://github.com/kunlong-luo/merchivra/actions/runs/41155975249).
  Unit tests include LIVE-mode configuration guardrails, scheduler filtering
  by matching source mode, role rejection and the **fetched page followed by
  revoked grant before transaction commit** race. The last case asserts no
  Orders source rows, page journal, or watermark update.
- [PostgreSQL 18 + Nginx + Spring Boot Compose passed](https://github.com/kunlong-luo/merchivra/actions/runs/41155924445).
  Tests confirm Flyway V1–V13, no pre-seeded Orders role evidence or LIVE rows,
  tenant Session/CSRF, existing mocked order replay, first-page commit,
  actual backend container restart, second-page recovery and idempotence.
- [Headless Chromium responsive smoke passed](https://github.com/kunlong-luo/merchivra/actions/runs/41155975252):
  a synthetic connected Marketplace exposes one distinctly labelled
  V11C LIVE check-point panel at 360px, while the separate local
  UNCONFIGURED Marketplace retains its MOCK-only workflow. Screenshots
  are available in the workflow artifact.
- The Vue Core workspace now displays V11C LIVE status/actions for
  **connected** marketplaces, outside the local-only tools disclosure;
  it still shows an explicit unauthorized/disabled error if safety
  preconditions are not met. UI cannot override server authorization,
  install app roles, provide a token, or select the source mode.
- Live Amazon egress, OAuth approval, external review of Orders role
  evidence, production 429/TTL limits, real order items, financial
  settlement, and acceptance with any genuine Seller credentials **are
  not complete**. All known proof is synthetic, and LIVE remains
  separately opt-in and disabled by default.



## V11D · 官方 Java SDK 类型化 Orders 读取（2026-10-31）

- LIVE Orders 不再直接以 `SpApiClient` 原始 HTTP+Jackson JSON 解码作为主路径。新增 `TypedOrdersSdkReader`：通过官方 SDK `SearchOrdersApi.searchOrders` 读取 Orders v2026-01-01 的 `SearchOrdersResponse` / `Order` / `Pagination` 类型；仅传递 lastUpdatedAfter/Before、Marketplace、maxResultsPerPage=100 和受控分页 Token，`includedData` 为 null，从而不请求 BUYER/RECIPIENT/财务扩展数据。
- V11B `ApprovedOrdersReadGateway` 依旧负责当前租户、Seller、Marketplace、V12 经审核 Orders 角色、网络前/后权限复核及页面数据归属校验；原始 JSON 读取构造函数暂为向后兼容测试用。V11C `OrdersSyncConfiguration` 的 LIVE 实例改用 `SpApiOfficialSdkFactory` + `TypedOrdersSdkReader`，且仍需所有 LIVE 显式开关，默认永久关闭。
- 适配器将 SDK `Order` 转成仅 ID、Marketplace、更新时间、履约状态四个字段的只读摘要；缺失履约状态保留 `NOT_OBSERVED`，不能当已发货。任意 Marketplace 不匹配、缺失/无效分页、空订单数组均拒绝。SDK 异常返回**安全错误码**而不是包含 PII、令牌和签名的完整远端响应；429 与 5xx 交由现有 Worker 重试策略处理。
- Amazon 公开的 `searchOrders` 默认使用计划速率 0.0056 rps，Token 24h；Worker LIVE 页间有最少约 180s 延迟和 20h 保守分页回扫。生产共享配额、真实网络 429/503、签名失败、multi-tenant 业务容量尚未现场验收。
- 单元测试仅用合成 SDK 模型，不连接 Amazon。真实应用角色审核登记工作流、官方 Amazon 实际授权与网络验收仍是阻断项。不得把“编译接通 SDK”描述为“真实账户已连通”。

参考官方文档 https://developer-docs.amazon.com/sp-api/reference/searchorders 及 https://developer-docs.amazon.com/sp-api/docs/orders-api-migration-guide 。
