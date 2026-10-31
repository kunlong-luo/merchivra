# Merchivra · Agent 开发策略

> 本文件是本仓库的长期开发约定，适用于自动化 Agent 和人工贡献者。
> 与根目录 `AGENTS.md` 保持一致。用户明确的新指示优先于本文；产品 G1/G2 待签署的业务方案不得因本文自动视为批准。

## 目标与协作

- 维护 **Merchivra（麦奇维拉）**：Apache-2.0、开源、自托管、Amazon Seller ERP；优先交付能运行的业务纵向闭环。
- 按维护者现行指示：**直接在 `main` 开发、逐步提交并验证 CI**；不再创建长寿命分支或 Draft PR，除非维护者更改要求。更新相关文档与 GitHub Issue，报告确切提交/检查结果。
- Amazon SP-API 接入**优先使用官方 Java SDK `software.amazon.spapi:spapi-sdk:1.13.0` 与官方 OpenAPI 模型**；已有 `SpApiOfficialSdkFactory` / `SpApiOfficialModules` 和保守 Core Read Adapter。未来新增模块不应优先手写无类型的 URL 接口，须按操作审查 DTO/角色/速率/权限。见 [INT-004](docs/integrations/sp-api-code-integration.md)。
- 优先使用经过广泛验证的官方 Starter/成熟库；不要重复实现基础设施，不因新潮就加入 Kafka、微服务、重型前端框架或与业务无关的依赖。
- 可参考 [Pagevela](https://github.com/kunlong-luo/pagevela) 的构建、依赖更新、持久化并发纪律，但不照搬其 React / Dexie 浏览器状态设计。

## 当前工程基线（2026-10-30）

- **后端**：Java 25 LTS、Spring Boot 4.1.2、Spring Security 7、Jackson 3、Maven、Spring JDBC + Flyway、PostgreSQL 18。
- **前端（2026-10-31 React 迁移覆盖早期 Vue 决策）**：Node 24 LTS、React 19.3、React DOM、TypeScript 6、Vite 8、TanStack React Query 5、Zod 4、Vitest；使用已提交的 `apps/web/package-lock.json` 与 **`npm ci`**，不要改回无锁安装。
- **部署**：单体 API + React 静态页面、Docker Compose；Redis 预留而非所有功能都必须用。Dockerfile 与 GitHub Actions 必须和本地版本一致。
- 不迁移到 Gradle，除非存在实质工程收益并经维护者确认；添加依赖前先判断当前阶段是否真正使用。

## 测试与开发节奏

- **M0～M2 轻量 CI**：后端 Maven 快速单元测试；前端 TypeScript 类型检查、少量 Vitest、Vite build。
- 不提前引入 Playwright/E2E、跨浏览器矩阵、83% 等覆盖率门禁、大型 Docker 集成 CI。M3 核心流程基本成熟时逐步补 E2E；M4 Beta 之前再加必要安全、数据库和上线验收。
- 保留**少量高价值负例**：租户/店铺越权、CSRF、同步幂等、金额/币种、退款状态、库存 `UNKNOWN ≠ 0`。成熟库内部行为无需重新测试。
- 每次修改后检查当前 `main` 的 **CI 实际结论**；失败时先诊断并修复。已完成的 PostgreSQL V1–V6 和真实 Compose Mock HTTP 全链路（含逐页重启恢复）冒烟应附准确证据，但**未执行的** Compose、并发、Amazon SP-API 及生产安全实测不应声称已通过。

## 安全与业务不变量

- `MERCHIVRA_SPAPI_ENABLED` 默认 **false**，SDK 原始远程方法不得开放为 HTTP 代理；`MERCHIVRA_SPAPI_ALLOW_WRITES` 默认 false。正式环境要有经过批准的 Seller OAuth、按租户隔离的凭证 Vault、权限核验和完整业务审计后才可开启出站。**SDK 包含大量 API ≠ ERP 全部真实调用已实现。**
- **V7 OAuth 协议与 Vault**：公有 Seller 授权需要 Start→Amazon Login URI（含 amazon_callback_uri/amazon_state）→最终 Code Redirect 两阶段；绑定短期一次性哈希 State + SameSite=Lax HttpOnly 交接 Cookie，使用固定回调 Origin 白名单，AES-256-GCM/AAD 绑定 tenant/account/seller，日志不得出现 Code/Token。成功后仅 `PENDING_VERIFICATION`，不得直接变为 `CONNECTED` 或默认给 Finance 权限。禁止在 Demo 模式开启真实 OAuth；未通过官方授权、安全评审、KMS 轮换与身份/角色核验前保持关闭。详见 [SEC-004](docs/security/seller-oauth.md)。V7 PostgreSQL/Compose 回归已通过（[证据](https://github.com/kunlong-luo/merchivra/actions/runs/41145773306)），真实 OAuth/Token/生产环境未测试。
- **默认禁止接入真实 Amazon 卖家资料**：现阶段仅有本地 Session/OWNER 初版，不是完整生产 IAM。不能提交凭证、Token、买家 PII 或真实导出；不将无授权端点暴露公网。
- 所有正式业务端点必须从**服务端 Session/认证身份**获取活动租户及授权店铺范围，数据库查询显式过滤 `tenant_id` 与对应 marketplace/account；绝不能信任浏览器传入的 `tenantId`。
- 本地 Seller Account / Marketplace 配置只允许当前租户 Owner 创建 `UNCONFIGURED` 占位实体：服务端生成 `LOCAL-` 内部标识，区域/币种/时区使用受控预设，不能把其解释为 Amazon 已授权，也不能接受真实 Seller 凭证；创建保持幂等，动作在同事务记录无敏感内容的配置审计。详见 [API-004](docs/api/local-seller-setup.md)。
- 当前仅有 OWNER 全租户作用域；未实现的员工角色/账户级授权不能虚构。对于不属于当前租户的资源返回不可见/拒绝，不返回其他租户数据。
- PostgreSQL 迁移**只新增新版本**，不回改已有 V1/V2/V3/V4/V5/V6/V7；跨表关系保持 `(tenant_id,id)` 复合外键，来源唯一键以租户+店铺+Marketplace 确定。
- 金额用 `BigDecimal` / `NUMERIC` 与 ISO 币种，禁止将不同币种无汇率直接相加；预计商品收入、退款、平台入账、利润须分别表达。未知/缺失 != 零库存，订单履约/退款/财务状态分开。
- `/api/v1/demo/**` 只读/写入**虚构演示数据**，需显式启用 Mock 并经 Session/CSRF 保护；正式资源 API 不得读取/复用演示 JSON 快照。严禁以固定 Demo Tenant 当作真实登录租户。
- 已新增 **V6 Sandbox 手动逐页**：每次提交一个虚构 Orders 页，`sandbox_page_stream` / `sandbox_page_commit` 持久化进度、页面与递增代数；PostgreSQL NOWAIT 行锁 + 幂等键保护事务重放。它是演示用协议，**不是正式后台 Worker、分布式租约或 Amazon 分页/水位恢复**；需要明确区分已实现与 Proposed 的 SYNC-002。见 [TEST-004](docs/testing/mock-full-chain.md)。
- 正式同步 Stream 仍需幂等、稳定来源键、事务与 checkpoint/lease fencing，未做的 Worker/正式分页/真实授权不得描述为完成。

## 文档与验收

- 实现功能时同步维护 README、`docs/development.md`、`docs/architecture/`、`docs/security/`，并保持 `Agent.md` / `AGENTS.md` 一致。
- G1/G2 产品及安全架构尚有 Proposed 决策：区分「代码已实现」「CI 测试已通过」「真实 DB/容器已验收」「业务负责人已批准」，不可混淆。
- 当前已具备**会话租户/Marketplace 只读 API 与本地未连接 Seller 配置/审计初版**。已有一次性 PostgreSQL V1–V4 验证，以及 [Compose 全链路 Mock 实机成功](https://github.com/kunlong-luo/merchivra/actions/runs/41061679196)（Nginx/Session/CSRF/US+DE 模拟页/Listing/Orders/FBA/审计/重启）；这不表示 Amazon SP-API、浏览器 E2E、生产 IAM 或 Finances 已完成。此外 [V6 页级持久恢复的真实 Compose 运行](https://github.com/kunlong-luo/merchivra/actions/runs/41065562926) 已通过（页 1→重启→页 2→旧键重放不进位）。后续仍应加强真正的多租户/并发验证，然后再设计可审计、可撤销的 Amazon 授权流程；未经批准不得接入真实 Seller/财务数据。

参见 [轻量 CI 策略](docs/testing/ci-policy.md)、[安全初版](docs/security/local-auth.md)、[依赖与 Pagevela 借鉴](docs/architecture/dependency-adoption.md)、[已实施核心表](docs/architecture/core-domain-foundation.md)。

## V8 · Sellers v1 Marketplace 验证准入（2026-10-31）

- [SEC-005](docs/security/seller-verification.md) 新增默认关闭的手动 Owner 验证入口，
  使用 Amazon 官方 SDK Sellers v1 的 `getMarketplaceParticipations`。
  必须同时开启 SP-API、OAuth、验证，关闭 Demo 与 Amazon sandbox；**未进行真实 Amazon 验收**。
- 只允许所有已配置 Marketplace 均 `isParticipating=true`、Listings 未暂停时
  将授权由 `PENDING_VERIFICATION` 升至 `VERIFIED`，店铺才成为 `CONNECTED`。
  验证请求需 Claim fencing，撤销优先；Worker Vault 仅允许 24 小时内有效的
  Marketplace 证据，任何失效/缺失都默认拒绝。
- 该 Sellers v1 操作**无法提供独立的 Seller ID 或角色证明**：Seller ID 仍来自
  经过状态绑定的 Amazon OAuth 回调，Finance/PII/受限操作没有角色批准记录，
  `approvedRoles` 继续为空，禁止自动宣称角色已获批准。
- V8 为代码与离线单测交付，尚未实现自动凭证轮换、KMS 多版本密钥、
  后台实时 Worker、安全运营与真实卖家环境测试；后续 Issue 不能据此关闭生产验收。

## V9 · Seller Vault AES 密钥环及手动重加密（2026-10-31）

- [SEC-006](docs/security/seller-key-rotation.md)：V7 单密钥向多版本密钥环兼容升级，OAuth 写入、V8 验证与 Worker Vault 使用同一 cipher Bean。缺失旧密钥、重复版本、解密认证失败都必须拒绝，不能尝试降级。
- 新增 OWNER + Session/CSRF 的 `POST /api/v1/seller-accounts/{accountId}/oauth/rotate-key`，只有 OAuth/SP-API 显式启用且 Demo 关闭时生效。按店铺数据库行锁 + 原版本 CAS + 新随机 nonce + 单事务 `KEY_REWRAPPED` 审计，已撤销凭证不可轮换。
- **本地密钥重新加密不等于 Amazon LWA Refresh Token 周期续期**，不能因此扩大 Seller 权限。当前尚无 KMS Envelope Encryption、全局自动轮换、跨节点演练、真实 Seller 网络验收或生产 Worker；Compose V1–V9 应依据最终实际运行证据记录。

## UI / UX 设计纪律（2026-10-31）

- 根目录 [DESIGN.md](DESIGN.md) 定义 Merchivra 的界面设计规范与质量检查清单。保留 React/TypeScript 业务代码、现有蓝黑品牌风格和 Core/Mock 数据边界；除非维护者同意，不安装 Impeccable 或其他设计插件、Hook、npm 依赖。
- 可直接参考公开的 Impeccable critique/audit/typeset/polish 方法进行**人工源码审查和小步修整**，但不可将此描述为已运行 Impeccable 专属检测器、浏览器截图比对、WCAG/性能认证。先解决操作信息层级、表格可读性、加载/错误/空状态、键盘焦点、手机触控、减少动画。
- 状态标签与功能开关必须和真实权限一致。顶部“API 已连接”仅代表后端 API 可用，不代表 Amazon Seller 连接成功；Mock 不得暗示真实收入，未知库存不可写为0。所有 UX 更改需通过现有 Vue 类型检查、Vitest、构建，再做浏览器级验收。


## Core 页面设计与数据完整性（2026-10-31）

- 第二轮不安装 Impeccable，继续按照公开 audit/polish 原则直接审查现有 Vue 组件，并在 [DESIGN.md](DESIGN.md) 记录发现与边界。
- Core 首页任务优先：未配置 Marketplace 时明确引导创建本地占位店铺；已有站点时展示当前数据和筛选，将本地配置、纯模拟回放放入可展开的辅助工具区。不能把 UNCONFIGURED 误当 CONNECTED，也不能将 API 可用当作 Amazon 已授权。
- Core Listing/Orders/FBA 在客户端只筛选已从服务端读取的最多 50 行，显式展示命中数/已加载数，不能暗示已实现服务器搜索或分页。
- 所有列标题必须和数据单元格对应，订单必须显式展示 dataOrigin；UNVERIFIED 不许显示为真实核验数据。保留 UNKNOWN ≠ 0 与 Finance NOT_OBSERVED/NOT_AUTHORIZED 语义。
- 修改 Core UX 必须至少检查离线筛选/来源标签测试和 Vue typecheck、Vitest、Vite build。不可声称已做浏览器截图/自动 Impeccable audit 或完成生产验收。


## V3 前端 UI 验收（2026-10-31）

- [TEST-005](docs/testing/browser-ui.md) 新增独立可选 Chromium 浏览器响应式冒烟：使用 CI runner 已装 Chrome + Node 24 内置 WebSocket/CDP，通过仅本机模拟 API 观察 Vue 实际渲染，覆盖 360/390/768/1280 屏宽、屏幕级溢出、表格局部滚动、列对齐、MOCK 来源和未知库存 ≠ 0；**不添加 Impeccable、Playwright 或 npm 浏览器依赖**。
- 保持前端快速 CI，不将独立 Chrome 任务变为每次提交必跑的门禁。工作流成功之前不得宣称已验收；完成以后也只能称 Chromium + 虚构数据的有限布局冒烟，不能称真实登录、真实卖家、跨浏览器或 WCAG/读屏器审核通过。
- Core/SellerSetup/Sandbox/Demo 的长表格必须保留原生 table 语义、带 scope 的列头和可聚焦水平滚动容器；跨端设计变化需尊重键盘焦点、文本状态、Mock/UNVERIFIED 区分与 UNKNOWN ≠ 0。


## UI 浏览器真实运行记录（2026-10-31）

- Chrome 156 / Node 24 可选响应式检查已在
  [run 41150873626](https://github.com/kunlong-luo/merchivra/actions/runs/41150873626)
  成功；相同提交的
  [Java/Vue CI run 41150873629](https://github.com/kunlong-luo/merchivra/actions/runs/41150873629)
  也成功。产出 11 张可下载截图；已人工查看中文 360px/1280px 的
  关键布局及 360px Listing/Orders/FBA 表格本体。详见 [TEST-005](docs/testing/browser-ui.md)。
- Runner 缺少中文字体导致最初截图乱码，已在**临时 CI Runner**
  安装 Noto CJK 字体并重测，未加入产品字体、Impeccable 或 npm 依赖。
- 本验收只证明虚构 localhost API 下 Chromium UI 布局/基础语义，
  不意味着真实 Seller、全量键盘导航、读屏器、WCAG AA、Safari/Firefox 或生产 IAM 已验收。


## V10 运营自动化待办（2026-10-31）

- 对标 [docs/product/competitor-gap-and-automation.md](docs/product/competitor-gap-and-automation.md)，以 Sellercloud/Linnworks/Helium 10 的订单工作流、异常管理、库存风险为参考，优先实现可落地的基础功能；不宣称已实现竞品所有能力。
- 详见 [OPS-001](docs/operations/ops-automation.md)：V10 Flyway 持久待办、规则巡检、4 类异常、重复巡检幂等、自动恢复条件已消失的异常、用户认领/完成/重开与事务审计；工作台开放“运营自动化”入口。低库存必须满足最近来源观测 quality=OBSERVED 且非 NULL，UNKNOWN 永远不等于 0；待办数据源仅 MOCK/UNVERIFIED，不能自动补货/发货/写 Amazon/财务记账。
- 用户端只允许 Owner Session 导出所属租户待办和触发扫描/变更状态。POST 受 Security CSRF 保护；请求没有 tenantId，foreign ID 返回 404。调度器环境开关 `MERCHIVRA_OPS_SCHEDULER_ENABLED=false` 默认关闭；打开才周期巡检；读业务事实不意味着 Seller 角色已核验。
- 实际交付必须经 Java 25/Vue CI + 独立 PostgreSQL Compose Session/CSRF Mock 全链路验证。调度器大规模多租户负载、规则配置/人员权限及真实 SP-API Worker 尚未完成，不能将 V10 巡检当作正式订单同步。


## V10 运营异常自动化验收证据（2026-10-31）

- [Java 25 / Vue 快速 CI 成功](https://github.com/kunlong-luo/merchivra/actions/runs/41151803877)，
  [Nginx + Spring Boot + PostgreSQL18 的 V1–V10 Compose Mock 全链路成功](https://github.com/kunlong-luo/merchivra/actions/runs/41151803940)；
  [Chromium 360px 虚构运营工作台回归成功](https://github.com/kunlong-luo/merchivra/actions/runs/41151964910)。
  所有验证均不包含真实 Amazon Seller 授权、定时调度多机负载或真实财务。
- 后续参见 [#23 运营规则配置与通知](https://github.com/kunlong-luo/merchivra/issues/23)、
  [#24 补货采购审批](https://github.com/kunlong-luo/merchivra/issues/24)；
  真正 Amazon Orders Worker 继续受 #6/#8 的 P0 前置授权与角色审查约束。


## V11A 订单增量 Worker 边界（2026-10-31）

- [V11A 设计与验收](docs/integrations/orders-worker-v11.md)：Flyway V11 + `orders_sync_job` 持久化窗口、分页 Token、UUID 租约和 fencing epoch、每页事务提交、分页重放代数、429/5xx 延迟重试、20h Token 预期失效时重新扫描原窗口、完成时才推进安全水位。
- **V11A 仅接 Mock 订单源**：`MERCHIVRA_ORDERS_SYNC_ENABLED=false` 默认关闭；开启必须 Demo=true 且真实 SP-API 出站=false，账号还必须是当前租户 LOCAL/UNCONFIGURED。Core 的测试工具面板显示分页/水位状态；没有真实 Seller Orders 请求、买家 PII、财务结算或自动发货。
- 正式 Orders 同步在独立验证 Amazon Seller 的 Inventory and Order Tracking 等合规角色之前不得启动；`SpApiSellerGrantVault` 当前未持久化已获批角色，严禁伪造 approvedRoles 或绕过生产开关以偷跑实时 API。
- 验收需 Java/Vue CI、真实 PG Compose Mock HTTP/CSRF/多页任务与重启，以及受控浏览器检查证据。未经执行不得宣称验证通过；生产角色/网络/流量与真实数据仍未验收。


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


## V11B · Orders 角色证据与只读适配器（2026-10-31）

- Flyway **V12** 新增 `spapi_orders_role_evidence`。Seller v1 Marketplace 参与状态**不能证明**应用具有 Orders API 角色，因此不会自动创建角色证据。每条证据按 tenant/account 绑定、审核者、SHA-256 外部证据摘要、审核时间、有效期（最多 30 天）、显式撤销，并强制关联当前 Seller 授权的 `verified_at`；重新核验/吊销 Seller 后旧证据无效。
- `SpApiSellerGrantVault.loadOrdersApproved` 在原先租户/账号/Marketplace/连接状态/24h Seller 核验证据门禁上，再检查独立的 `Inventory and Order Tracking` 角色审核证据。无证据或过期绝不返回带角色的 Seller Scope。
- `SpApiRequest` 与官方 SDK 的 `SpApiOfficialModules.searchOrders/ordersDetail` 都新增按操作强制检查角色。当前仅允许精确的 `Inventory and Order Tracking`，比 Amazon 对 Orders API 公布的角色清单更保守。
- `ApprovedOrdersReadGateway` 实现只读 Orders v2026-01-01 的服务端适配器：从租户 Marketplace 解析 Seller Account，前置校验授权与角色，调用已有审查过的 `SpApiReadOperations.searchOrders`，按官方响应数据结构解析订单、强制校验 Marketplace 匹配，并在返回前重新检查 Seller 授权，防止网络读取期间撤销授权后继续使用结果。
- **仍不注册自动 Spring Bean、不暴露 HTTP API、不挂接到 V11A 的 MOCK-only 写入 Worker**。目前没有证据登记/审核 UI，也没有直接连接真实卖家的生产读操作；不能通过增加上述代码宣称已实现 Seller 角色核验或真实 Amazon 同步。
- 单元测试覆盖缺少 Seller/角色拒绝出站、无效时间窗口、市场错配、请求途中撤销授权、响应结构与无 PII 读取；PostgreSQL 18 Compose 检查 V1–V12 全部迁移且默认角色证据表为空。

Amazon 官方角色文档：https://developer-docs.amazon.com/sp-api/docs/orders-api 。


## 2026-10-31 · PostgreSQL 18 + V12 实际验收证据

- [Java 25 + Vue CI 成功（run 41154501014）](https://github.com/kunlong-luo/merchivra/actions/runs/41154501014)，包含 V11B Orders 只读网关的缺失权限、错误 Marketplace、过期/撤销后拒绝等单测。
- [PostgreSQL 18 + Spring Boot + Nginx Compose 成功（run 41154500844）](https://github.com/kunlong-luo/merchivra/actions/runs/41154500844)：实际断言服务器版本 18 和默认 PGDATA；V1–V12 Flyway 全部迁移、V12 角色证据为空；Mock Session/CSRF、旧 V6 与 V11A 逐页提交→重启→恢复均成功。
- **仍未验收真实 PostgreSQL 17 数据卷到 18 的升级**。本次使用全新的、一次性 `pgdata18` 卷。若已有真实 PostgreSQL 17 数据，必须先依 [17→18 数据迁移手册](docs/deployment/postgresql-18-upgrade.md) 备份并人工迁移。
- **仍未接通真实 Amazon Orders**：V11B 的证据表仅提供“拒绝未审核角色”的基础，尚未实现独立角色证据审核登记、完成全部正式接入前置条件或开启 Live Worker。所有生产 SP-API 出站开关保持默认关闭。


## V11C LIVE Orders 只读 Worker 安全闸（2026-10-31）

- V11C `OrdersSyncConfiguration` 显式选择 `MOCK`（默认）或 `LIVE`（需要独立批准 `MERCHIVRA_ORDERS_SYNC_LIVE_APPROVED=true`）；LIVE 要求正式 OAuth、真实 Seller Verification、V12 Orders 角色证据、生产 SP-API 只读，Demo/Sandbox/远端写入必须关闭。默认不触发任何生产出站。
- `OrdersSyncService` 现在把任务 mode 与记录 `data_origin` 绑定，禁止既有 MOCK 任务继承 LIVE 任务游标；提交 LIVE 订单/页日志/安全水位时重新核验并事务锁住账户、授权、角色、参与证据，授权撤销后事务回滚且转暂停。
- Flyway V13 放开 ops_finding 只读建议记录的 LIVE 来源分类，Web 必须区分 Amazon API 已同步/模拟/未核验；LIVE 绝不代表已记账或 Amazon 写入批准。
- 所有真实角色审核、真实 LWA、网络 429/Token TTL 与生产部署仍需独立实测。项目 CI 必须保留 Mock-only 全链路及默认角色表为空的断言。详情 `docs/integrations/orders-worker-v11.md`。


## V11C 自动化 Worker 与权限门禁实测（2026-10-31）

- [Java/Vue CI](https://github.com/kunlong-luo/merchivra/actions/runs/41155975249)、
  [PostgreSQL 18 / V1–V13 Compose Mock 全链路](https://github.com/kunlong-luo/merchivra/actions/runs/41155924445)、
  [Chromium 360px LIVE / MOCK 分离 UI](https://github.com/kunlong-luo/merchivra/actions/runs/41155975252)
  均成功；浏览器和数据库使用虚构数据，不是正式 Amazon 账号验收。
- `OrdersSyncService` 任务 mode 与 `data_origin` 必须一致，拒绝 MOCK/LIVE 游标混用；调度查询 `mode=?` 仅领取本模式任务。LIVE 来源重读已审核角色，事务内锁定相关 Seller 授权与角色证据，撤销后不得提交新订单、分页或水位。专门的撤销竞态单测已加入。
- Core 已连接 Marketplace 提供独立的 V11C LIVE 检查点面板；本地测试工具只显示 V11A MOCK，不应让 LIVE 看起来是模拟数据，亦不能从浏览器绕过生产部署门禁。
- 未完成独立生产 Amazon app/role 审批、外部角色证据录入审批、多租户生产授信/真实连接、官方 SDK typed Orders API 直接调用和真实流量验证；后续不得将合成测试当作生产证据，生产出站仍默认关闭。


## V11D · 官方 Java SDK 类型化 Orders 读取（2026-10-31）

- LIVE Orders 不再直接以 `SpApiClient` 原始 HTTP+Jackson JSON 解码作为主路径。新增 `TypedOrdersSdkReader`：通过官方 SDK `SearchOrdersApi.searchOrders` 读取 Orders v2026-01-01 的 `SearchOrdersResponse` / `Order` / `Pagination` 类型；仅传递 lastUpdatedAfter/Before、Marketplace、maxResultsPerPage=100 和受控分页 Token，`includedData` 为 null，从而不请求 BUYER/RECIPIENT/财务扩展数据。
- V11B `ApprovedOrdersReadGateway` 依旧负责当前租户、Seller、Marketplace、V12 经审核 Orders 角色、网络前/后权限复核及页面数据归属校验；原始 JSON 读取构造函数暂为向后兼容测试用。V11C `OrdersSyncConfiguration` 的 LIVE 实例改用 `SpApiOfficialSdkFactory` + `TypedOrdersSdkReader`，且仍需所有 LIVE 显式开关，默认永久关闭。
- 适配器将 SDK `Order` 转成仅 ID、Marketplace、更新时间、履约状态四个字段的只读摘要；缺失履约状态保留 `NOT_OBSERVED`，不能当已发货。任意 Marketplace 不匹配、缺失/无效分页、空订单数组均拒绝。SDK 异常返回**安全错误码**而不是包含 PII、令牌和签名的完整远端响应；429 与 5xx 交由现有 Worker 重试策略处理。
- Amazon 公开的 `searchOrders` 默认使用计划速率 0.0056 rps，Token 24h；Worker LIVE 页间有最少约 180s 延迟和 20h 保守分页回扫。生产共享配额、真实网络 429/503、签名失败、multi-tenant 业务容量尚未现场验收。
- 单元测试仅用合成 SDK 模型，不连接 Amazon。真实应用角色审核登记工作流、官方 Amazon 实际授权与网络验收仍是阻断项。不得把“编译接通 SDK”描述为“真实账户已连通”。

参考官方文档 https://developer-docs.amazon.com/sp-api/reference/searchorders 及 https://developer-docs.amazon.com/sp-api/docs/orders-api-migration-guide 。


## V14 双人角色审核（2026-10-31）

- [V14 Orders 角色双人审核](docs/integrations/orders-role-review-v14.md)：Flyway V14 `spapi_orders_role_review_request` / `spapi_orders_role_review_audit`；默认关闭 `MERCHIVRA_SPAPI_ORDERS_ROLE_REVIEW_ENABLED=false`，必须由当前租户 OWNER 提交真实外部角色材料的 SHA-256 与非敏感 case reference，再由**另一位**拥有独立 `SECURITY_REVIEWER` 角色的有效 OWNER 人工复核。不能自动给审核人角色、不能申请者自批、不能因 Seller OAuth 或 Marketplace 核验自动生成 Orders role evidence。
- 审批生成 V12 的 7 天有效角色证据，经过当前 Seller grant 版本核验与历史审计；Owner 可紧急撤销。默认无任何角色、证据或审批记录。尚无受信任的账号开通/审核角色授予 UI，因此在外部管理权限、真实 Amazon 角色审批证据未验收前不得进入 LIVE 生产。
- [V11D Typed SDK](docs/integrations/orders-worker-v11.md) 的 LIVE 读取改用官方 Java SDK `SearchOrdersApi.searchOrders`，不请求 BUYER/RECIPIENT，审批门禁仍在内部网关和事务内复核；真实 Seller API 流量尚未验收。


## 2026-10-31 · V11D / V14 已执行的验证

- [Java 25 Maven 单测 + Vue TypeScript/Vitest/Vite CI 通过](https://github.com/kunlong-luo/merchivra/actions/runs/41156896032)：包含官方 SDK 类型化合成订单模型、缺失 Orders 角色拒绝、Marketplaces 归属错误拒绝、履约缺失不推断、V14 双人审批权限与审计测试。
- [PostgreSQL 18 + Spring Boot + Nginx Compose 全链路通过](https://github.com/kunlong-luo/merchivra/actions/runs/41156896071)：实际执行 Flyway V1–V14，断言 V12 已批准角色为空、V14 申请/审核审计均为空，继续测试模拟 Session/CSRF、先提交页1→真实重启后端→恢复页2/水位/去重。
- **这些全部是离线/模拟数据验收**：没有使用真实 Seller refresh token，也没有任何真实 Amazon 开发者后台角色证据、两位生产审核员或 LIVE 网络同步。在运营角色管理员 UI 完整、外部角色核验以及实际 Seller 授权验收前，V11D/V14 不能称为生产就绪。


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


## V16 已完成的实际验证（2026-10-31）

- [Java 25 Maven + Vue / TypeScript / Vitest / Vite CI 41162402846](https://github.com/kunlong-luo/merchivra/actions/runs/41162402846) **成功**，包含路径白名单、当前租户 Owner 校验及跨租户管理员不能借权操作的负例。
- [PostgreSQL 18 + Nginx + Spring Boot Compose 41162402904](https://github.com/kunlong-luo/merchivra/actions/runs/41162402904) **成功**：Flyway V1–V16，V15 邀请和 V16 员工跨店铺授权、账号激活、Session/CSRF、访问者越权 403/404、管理员撤销后权限立即失效；真实重启 Spring Boot 后 V11A 模拟订单页 2 恢复且无重复写入。日志：`PASS V16: staff login, scoped Marketplace + orders/listings/FBA, foreign 404, no OWNER routes, immediate revoke`。
- [Chromium 360px 员工界面验收 41162279336](https://github.com/kunlong-luo/merchivra/actions/runs/41162279336) **成功**；假设员工具有受限角色，验证仅有核心数据入口，团队/模拟导入/同步面板不展示；页面截图存放于 workflow artifacts。
- 本轮还有显式安全收紧：服务端仅放行精确已审核的 Marketplace GET 读路径，OWNER 权限必须属于**当前 Session 所选租户**，而不是其他租户中的 OWNER 权限。生产真实 Amazon API 出站默认关闭。
- **已完成的是初级 OPERATOR_READ 角色，不是全量员工 RBAC**。运营写入、采购、财务、SSO/MFA、外部生产安全审核均未完成，不能将模拟测试结果称作生产权限认证。

## ADR React-only（2026-10-31，最新约束）

用户已明确选择 **React 19** 替换 Vue：所有新 UI 用 React TSX、`@tanstack/react-query`、Vite React 插件、TypeScript + Zod。**禁止再新增 Vue SFC、vue-tsc、@vitejs/plugin-vue、@tanstack/vue-query、Pinia 或 Vue Router**。早期文件中的 Vue 描述仅为历史证据，**被本节覆盖**。保留 Spring Boot 4.1.2、Java 25、PostgreSQL 18、Maven、Flyway、租户 RBAC、Amazon 安全门禁。详见 [React 迁移 ADR](docs/architecture/react-migration-2026-10-31.md)。锁文件必须经过真实 npm registry 解析并提交，Docker 和 CI 用 npm ci。React 页面需执行类型检查、Vitest、Chromium 360/390/768/1280 冒烟与 PG18 Mock 全链路后方可视为验收。


## React migration acceptance (2026-10-31)

- [React 19 / TypeScript 6 / Vitest / Vite + Java 25 CI succeeded](https://github.com/kunlong-luo/merchivra/actions/runs/41164156695), including `scripts/check_frontend_stack.mjs` rejecting Vue packages and .vue source files, and React SSR bootstrap Vitest.
- [Chromium responsive React UI smoke succeeded](https://github.com/kunlong-luo/merchivra/actions/runs/41164009525): 360/390/768/1280 widths, tenant core Listing/Orders/FBA, staff-only navigation, operations triage, team reviewer, MOCK/LIVE Orders panels and source indicators. Tests use synthetic API data.
- [PostgreSQL 18 / Java 25 / React Nginx Compose full chain succeeded](https://github.com/kunlong-luo/merchivra/actions/runs/41164156751), including V1–V16 Flyway, authenticated Session/CSRF and multi-owner/operator checks, mock source import, Orders Worker checkpoint and actual backend restart recovery.
- Lockfile was regenerated through the npm registry and committed in [React lockfile commit](https://github.com/kunlong-luo/merchivra/commit/d3e56c564f254cc4153d996da240edc65a397424), with zero Vue/Pinia/vue-tsc/Vue Query packages. Temporary lock generator workflow removed; normal CI and Docker require `npm ci`.
- **Only the frontend framework changed**. All live Amazon Seller operations are still gated by the server and were NOT production-validated by these synthetic smoke tests. This change does not add OpenAPI/Orval, production Seller credentials, or Finances/Listing/FBA live sync.


## V17 · Seller 连接中心开发约束（2026-10-31）

- 用户只从 React Owner 工作台通过现有 CSRF/Session 接入 Seller OAuth/验证/Orders 角色审批，**不得**在浏览器储存或输入 Amazon Refresh Token、Seller Code、LWA Secret、API 凭证或租户 ID。OAuth 真实网络默认关闭。
- 后端成功 Marketplace 核验后，必须在同一事务内将 `seller_account.seller_id` 绑定经过两阶段 OAuth 校验的 Seller ID；服务端 Vault/Orders Worker 要求本地账户、授权与站点证据三者身份一致。
- Amazon OAuth 返回并不等于 Seller VERIFIED，Seller VERIFIED 不等于 Orders 角色已获批准，Owner 自己不能审核自己的 Orders 申请，失败与撤销必须不可绕过。
- 本轮新增 Java 单元测试和前端 Vitest 必须随现有 Java CI、React CI 及 PostgreSQL Compose 验证；测试中的 Amazon 数据全为合成值。


## V18 · P0 Seller 授权验收第一（2026-10-31）

- 以 FR-SHOP-01/02 + SEC-004/005 为第一阶段：先把 Amazon 店铺接入、OAuth、Seller Marketplace 核验和撤销/重授权全部跑通并验证后，才继续 LIVE Orders/Listing/FBA。运营自动化和报表不是当前阻断点。
- 公有应用 OAuth 和私有应用自授权不得混用。没有真实 Amazon 注册应用、生产卖家账号与明确批准角色，只能声称完成**代码和离线测试**，不能声称真实授权成功。
- 同一 `seller_account` 只能恢复此前授权过的 Amazon Seller；换 Seller 必须新建店铺记录，不复用订单/库存历史。撤销时同时销毁密文、旧站点与角色资格、未决审批，并确保任务后续鉴权 fail-closed。
- 在 React 中仅展示服务端允许的 Seller Central OAuth 链接，遵守 CSRF/Session；不允许浏览器录入或存储 Refresh Token/LWA Secret。默认 SP-API 网络关闭，不向 CI 写真实令牌。


## V19 · 用户指定「功能优先，集中验收」（2026-10-31）

- 当前交付按实际**代码是否具备服务/API/UI 路径**推进；先实现真实店铺私有自授权、SKU CRUD/人工映射，再拓展订单行、Listing、FBA、财务、采购与补货等。不要把一次 Mock 验证当成生产完成，也不要将全部 SDK 类型声明成 ERP 已实现所有功能。
- 本轮优先编码，**未执行测试/CI/数据库/真实 Amazon 验收时必须在变更说明中明确标注未验收**；不能预填成功。
- V17 服务端私有导入：必须使用临时进程环境/Secrets、明确显式开启、默认 Demo/OAuth/出站/远程写都隔离；不能在浏览器或日志暴露 token；导入后待 Sellers v1 验证、Orders 角色仍需独立审核。私有和公有不是一个授权流程。
- V18 主数据修改仅 Owner 能写入，SKU 的原始来源/映射历史不能破坏，不能自动写 Amazon，归档活跃引用必须拒绝；保留人工操作审计。
- 本次功能实现之后应再核验 Java 编译、TypeScript、Flyway、PG18 与安全验收，不能用编写代码替代运行证明。


## V19/V20 · 成本/采购来源隔离（功能优先开发）

- 维护者明确要求“先做完功能、再集中验收”，不再将每次 Java/React/PG18 的运行验证列为继续功能开发的前置；**未跑的检查必须明确标记未验证**。
- 内部历史成本金额为 `NUMERIC(20,6)` + ISO 原币及 `[effective_from,effective_to)`，追加新版本必须先锁 `product_sku`。不能将成本当做 Amazon Fees、P&L 或财务实际结算，也不能随意跨币聚合。
- 采购事实属于独立采购子域。双人审批由与创建人不同的当前租户 Owner 执行；收货为可审计追加事实并持有 PO 行锁、检查剩余数量。严禁用采购收货修改、推算或回填 FBA 观察表。
- V19/V20 API/UI/数据库设计还需后续集成与安全验收，不能认为功能已经达到正式上线要求。


## V21 财务事实实现纪律（功能先行，统一验收）

- `finance_source_event` 与 `sales_order` 不是同一收入流水。手工参考来源标记 `MANUAL_UNVERIFIED`，绝不能转为 LIVE 或自动参与 Amazon 结算；正式来源必须通过独立 Finances 角色和 Seller Scope。
- 每笔来源事件保留有符号原币金额、稳定来源键、Marketplace、时间与原始类别。重复键但数据不同必须拒绝，不能被最后写入覆盖。不得用无标识的总额跨币聚合。
- 人工匹配属于附属的可审计判断记录，不能改变来源金额；只允许同租户同 Marketplace 订单，不接受浏览器自报租户；Owner/CSRF 限制保持。
- 本轮开发服从“先补功能、后集中验收”，不得预先宣称 Java/React/PG18/真实 Amazon 验收通过。参见 FIN-005。


## V22 · 运营库存建议纪律（代码先行）

- V22 仅为 Owner 提供站点级 Seller SKU 补货点和目标量、提醒建议与审计。不可修改 Amazon、自动采购或从未核验采购收货推算 FBA 可售。
- 只有最新 `quality=OBSERVED` 且明确非 NULL 的 `fulfillable` 和不超过 48 小时的来源观测才用于补货建议；过期、缺失、未来异常时间与 PARTIAL 状态一律不可计算。不要把建议量误称真实销量预测。
- 本轮先提交代码，运行、PG 迁移、权限安全及 API/React 的正式测试后续集中完成，报告时必须区分代码提交与验收证据。


## V23 · Orders 商品项事实（功能优先）

- 不允许倒退到 deprecated v0 `getOrderItems`。官方 v2026 `searchOrders` 基础订单项足以写入 Item ID/Seller SKU/数量；带 `PROCEEDS` 才能看 ITEM 商品小计，绝不请求 PII 或把 Orders 小计当平台结算。
- `items_observation=NOT_OBSERVED`、`orderItems=null` 与空数组不同；源缺失不能覆盖已有明细。新版项快照更新当前行指示而保留历史，须与现有 Orders 逐页 commit/checkpoint/授权 fence 同事务。
- Source dataOrigin、Marketplace、Seller、时间版本和独立 Orders 角色都要保持单向验证；正式 Amazon 调用仍需在生产合规授权后显式打开。
- 本轮编写的 Java/React/数据库代码、离线测试与文档并不意味着真实 Seller 已验收。


## V24 · 真实 Listing/FBA 数据读取安全纪律

- 只有受信 `CatalogSyncService` 可调用官方 SDK `searchListingsItems` / `getInventorySummaries`，先通过 V12/V14 独立 Inventory and Order Tracking 应用角色审核及最新 Marketplace verified Seller Scope；每页落库前再校验，并保持数据库锁和租约 epoch。
- 默认关闭新出网开关；生产必须无 Demo、无 sandbox、无 API write，且有 LWA + 加密授权和独立站点验证。浏览器只传 Marketplace UUID 与 LISTINGS/FBA，不可接受可控 URL/Token/卖家 ID。
- 不将未知库存当零；PARTIAL、STALE 或未取得库存不触发自动补货。LISTINGS 不删站点缺失项、不自动改变 SKU 映射版本，不把 MOCK 升格 LIVE；FBA 的来源观测键跨重复页必须幂等。
- `V24` 仍需 Java/PG18/React、权限与真实卖家集中验收，严禁把存在代码误报为“Amazon 正式接入完成”。


### V24B · 只读自动扫描（显式 opt-in）

- CatalogSyncScheduler 默认关闭，且不允许在 catalog-sync 主开关关闭时独立运行；只继续处理已有 Owner 创建的 READY/RETRY 任务。不得主动注册 Seller、新建定期全量重扫、绕过租约或者自动提交 Amazon 修改。
- 所有自动页在事务外读取官方 SDK，事务内复查角色/授权再提交；429/5xx 延迟，不把错误/未知库存清零；日志不得包含 refresh token、source JSON 或 cursor。

- V24 安全修订：FBA Inventory 官方 nextToken 约 30 秒过期，本地 20 秒后必须重启完整新代次；Listing 采用单独 25 分钟阈值，自动调度默认 10 秒轮询。禁止把 FBA token 当成长寿命 Orders/Listing 游标。FBA 响应 granularity 必须匹配当前 Marketplace。


## V25/V26 · Finances 独立来源、角色及结算纪律

- Finance and Accounting 必须采用独立双人审核，不得由 Orders/Listings 权限或 OAuth 登录自动推断；财务角色有效期、grant 绑定、Reviewer 在职有效性、撤销和每页提交前锁必须检查。
- 官方 Finances `totalAmount` 可能是含多种明细的有符号净额；仅允许暂存 `UNCLASSIFIED`，**禁止**直接归为销售收入、退款、平台手续费、结算或净利润，不得与 Orders 预计收入重复相加。
- 同步端点只能接受 Marketplace ID 和受控 start/step；同步服务默认关闭，独立 Scheduler 默认关闭，用户显式启动后才能处理页。所有来源金额原币、稳定交易 ID、来源时间、审计与重放不变量保留；不得保存源 JSON、地址、买家或 OAuth token。
- 后续必须做 Finances breakdown/结算/来源对账和有证据的贡献额，而非从单一 totalAmount 推导利润。真实 Seller 与财务口径验收完成前不得标记生产可用。


- V27 财务 breakdown 一律保留 `source_path`/`parent_path` 层级及原币来源，不能同时加总父节点和子节点；旧来源重放数据不一致时必须冲突退出，不能覆盖。主财务汇总保持 UNCLASSIFIED 事实隔离，未取得明细不等于零费用。


## V28 · 财务只读分类安全边界

- `finance_source_event` 的原币 signed amount/sourceType/category 始终是不可变来源事实；`finance_breakdown_rule` 是当前人工解释规则，改变规则只能改变只读预览，不能自动写入原始事件、结算或记账总账。
- 只有 source breakdown 的 `is_leaf=true` 可用于临时类别求和；必须排除父节点，验证所有末级已完整获得金额、币种与来源总额一致、全部明细已分类；任何缺口不得返回类别合计。
- Marketplace+tenant+Owner 权限从 Session 推导，分类规则精准匹配 sourceType，变更创建/撤销有追加审计；无真实会计归属证明之前，不可称为最终利润、退款已核销或银行结算。


## V29 · 订单成本来源完整度与本地授权顺序

- Orders v2026 createdTime 和 lastUpdatedTime 不得混作同一时点；首个已知 createdTime 写入 order_time，缺失不猜。OrderItem 必须明确 LIVE/MOCK provenance，旧来源不一致必须阻断该页写入。
- COGS 只读预览必须满足订单 LIVE、订单项已观测、业务日期已知、当地日期有效成本、当前 Seller SKU 的当时映射、数量和成本原币一致；任何缺失返回 NULL 合计和行级原因。不得用历史 SKU 成本冒充真实 FIFO 成本，更不能标记最终利润。
- 用户明确选择“所有核心功能代码完成后，才在本地授权并自行调用真实 API 测试”：不要提前请求 Seller 账号、Token 或真实 Amazon 环境；继续使用 synthetic fixtures + CI，生产开关保持默认关闭。本地实测步骤应单独留待功能阶段收尾。


## V30 · 本地结算参考与对账隔离

- 参考结算单来源为 `MANUAL_UNVERIFIED`，只做 Owner 可审计的候选交易匹配；不得冒充 Amazon settlement、银行到账或会计过账。无银行/真实授权情况下，金额核平仍只能标记 `SIGNED_TOTAL_MATCHES_UNVERIFIED`，`bankVerified=false`。
- 同个 LIVE Finances 来源事件在一个 tenant 中只能分配一次，不能用 MANUAL_UNVERIFIED 事件拼凑结算；必须强制 tenant、Marketplace、币种、原始来源完整性。原币交易金额不可被 reference 值反向改写。
- 差异核算只加总每笔已绑定来源交易的 signed totalAmount，不许把 V27 的 breakdown 父项及子项再加一次。期间外单据必须异常提示。解除分配需保留审计，禁止强制复用已经分配的交易。
- 用户本地授权及真实 SP-API 测试留到所有核心功能完成后；此阶段服务默认无真实调用。修改任何旧版 Flyway migration 需确认开发环境和迁移 checksum 影响，不可无视生产升级风险。


## V31 · Finances 结算文档标识来源

- 只读取 `SETTLEMENT_ID`、`DISBURSEMENT_ID`、`FINANCIAL_EVENT_GROUP_ID` 这三类官方非 PII relatedIdentifiers，独立保存到 `finance_transaction_reference`；不保存买家/支付账号，不将订单 ID 与结算 ID 混淆，拒绝非法引用值。
- 来源引用必须同 LIVE Finances 源交易与页 checkpoint 原子提交，重复导入幂等；只读列表受 tenant/Marketplace/Owner 限制。对人工结算参考号的完全相等匹配只能用于候选排序，不可自动连结或判定已结算，更不是银行到账证明。


## V32 · 财务异常自动发现（本地有界扫描）

- 财务异常列表只能描述来源覆盖/不一致的待审核信号，不能自动创建或修改 Finance 事实、收入、结算链接、银行到账或退款记录；没有 LIVE 原始数据时空列表不意味着财务已核准。
- 来源 breakdown 只核对末级 signed amount，缺金额、缺业务分类、跨币种、未审订单关联和来源核平差异分别报告；未分配的来源交易仅为提示，不得称为漏付。
- 检查必须带 tenant/Marketplace、明确扫描上限并返回 partialScan；页面定时刷新也只访问本地 ERP API，真实 Amazon 默认关闭。


## V33 · 已审核来源的订单贡献额预览门槛

- 仅可使用当前 Marketplace 同一订单**人工确认关联**的 SPAPI_FINANCES/LIVE 交易；逐个 V28 叶级分类核平且与 V29 历史 COGS 同币种，才能形成暂定经营贡献额，不能把 Orders 估计 proceeds 同时加进去。
- 对退款、业务调整、未审订单关联、原币不符、成本日期/映射缺失、来源结果被截断、无已审销售、来源重复等情况必须不输出合计；退款成本回转未被证明前不得自动冲回商品成本。
- 平台费用保持原始有符号金额（负值不可重复扣除），`financialSourceComplete` 和 `bankSettlementVerified` 不能因为本地核对通过而被设置为 true。输出只能称经营贡献**预览**，不是利润或结算回执。


## V34 · 退款商品人工证据不可代替正式成本回转

- 退款项必须是 LIVE Finances、人工匹配的同站点 LIVE 订单、V28 已核平并识别 PRODUCT_REFUND 分类，且来源订单号（若有）与人工订单号一致。审核需要当前 LIVE order item、有效正数量、不可跨多笔退款累计超过来源订购数；写入要序列化原订单与原交易审阅关系。
- `RESTOCKED_CLAIMED` 仅为本地手工声明，不是 FBA/第三方仓库入库证明。允许展示单位历史成本乘审核数量的非入账敏感度，但不得改变库存、销售额、退款金额、V33 订单贡献或真实财务记账。
- 退款商品审核撤销/修改必须追加审计；已存在审核时禁止修改财务事件所关联的订单，必须先撤销审核。真实 API 与 Seller 授权仍延后到功能开发完成后的用户本地验证。


## V35 · 商品退款资金归属守则

- 不允许用 `finance_source_event.amount` 全部净额直接当作商品退款；必须基于 V28 完整已核对的原币 `PRODUCT_REFUND` 分类部分，归属到 V34 已审核正数量的同一来源订单项。所有 signed amounts 原样保存，不反向、混币种或分配超额。
- 记录金额归属的当前状态和完整追加审计；不得改写 Finances transaction、V34 数量、Amazon 退款、库存货量或历史 COGS；金额对平不自动证明库存入库、银行已付、会计过账、最终利润。
- V34 删除存在 V35 分配的商品审核前必须要求先撤销金额；V35 修改遵守与 V34 一致的订单→Finances 事件行锁顺序。Seller 真实授权仍留至功能完成后用户本地测试。


## V36 · 广告费用不可假装 Amazon Ads 已验证

- Amazon Ads API 与 SP-API Seller 授权独立。本阶段所有广告支出只允许 `MANUAL_UNVERIFIED`，不得伪装已从 Ads API 抓取，也不允许未知签名/广告币种向财务利润自动过账。
- 站点内人工广告来源 key 重复内容必须一致，否则拒绝；原币与站点一致，支出非负。SKU basisPoints 总和上限 10000，修改需持有支出来源行锁，追加审核操作审计。
- V36 广告分配总额和未分配余额是人工参考，只能用于核对，不等于真实 Ads 报表、ROAS/ACOS、银行已支付广告款。任何未来真实广告费用必须单独证明其来源并核对与 Finances 的费用是否重叠，才能进入正式利润。

## V37 · 补货建议人工转采购草稿（2026-10-31）

- Owner 可用补货建议主动创建**本地** `DRAFT` 采购单，但不得静默自动提交、批准或写入 Amazon。复用 PO-001 创建与双人审批；库存来源质量标记 `OBSERVED` 不等于真实 Seller 认证。
- 必须重新验证 ACTIVE Listing/内部 SKU 映射、最新 FBA 观测 ID、策略更新时间与建议量；原币单价由用户填写。V37 持久保留来源、SKU、库存、策略、数量、供应商和价格快照，使用租户内唯一请求 UUID 保障幂等；不将采购收货计入 FBA。
- 版本与操作定义见 `docs/inventory/replenishment-policy-v22.md`；未实际测试的 CI/Flyway/并发结果不得称通过。

## V39 · 采购收货与本地仓库流水（2026-10-31）

- `POST /api/v1/purchasing/orders/{id}/receive` 的新请求必须带 `warehouseId`，仅在 APPROVED/RECEIVING、已选同租户 ACTIVE 仓库时允许收货；原 V20 行锁与超收防护继续有效。
- 采购收货、订单状态与 `erp_local_stock_movement` 追加式入库流水共享单事务；`(tenant_id,purchase_receipt_id)` 唯一防止同一收货重复记库存。旧 V20 无仓库的收货不可自动分配或回填库存。
- 仓库 `recordedReceivedQuantity` 是**历史累计采购入库量，不是实际可用量/在途/FBA/FBM**；待收货仅为批准采购行剩余量，不等于供应商发货。
- 按 Session 当前租户和 OWNER / CSRF 隔离所有仓库读写，保持已上架 Amazon 数据与本地仓库操作严格分开。详见 `docs/purchasing/local-warehouse-receipt-v39.md`，验收须有真实 CI/PG18 证据。

## V40 · 本地仓储有符号流水与库存安全（2026-10-31）

- 库存台账 `onHandQuantity` 只累加已记录的本地采购收货与人工移动，`recordedReceivedQuantity` 是历史采购入库合计；二者均不表示 Amazon FBA/FBM、可售/预留、在途或完整的实物库存。
- 本地 `OUTBOUND`、`TRANSFER`、`COUNT` 必须显式 Owner 操作（Session/CSRF）、带 UUID 幂等键、原库存余额快照、仓库/内部 SKU 同租户锁与业务原因。库存不足和快照不一致返回冲突，不能覆盖移动事实。
- 转仓同事务记负数 `TRANSFER_OUT` 和正数 `TRANSFER_IN`；仓库操作在排序仓库锁下读余额，和 V39 收货共用仓库锁；所有操作不可通过 Amazon API 写入。
- `docs/purchasing/local-stock-operations-v40.md` 规定接口和边界。单测、CI 成功不等于正式 PostgreSQL 并发/真实 Seller 验收。

## V41 · 仓库低库存与异常审核（2026-10-31）

- 仓库 `NO_LEDGER` 与已记录 `onHandQuantity=0` **不可混淆**。只有当前仓库/SKU 存在正负移动证据且最终余额非负，才允许出具本地补货建议；负台账必须单独报异常，不能给出自动采购数量。
- 批准后尚未收完的采购数量属于**跨仓未分配**采购承诺，不是 Amazon FBA 在途，也不能盲目抵减特定本地仓补货计划。
- `erp_local_stock_finding` 状态更改必须 Owner + CSRF、Session tenant 隔离、带审计，扫描幂等；DONE 状态仅在实际条件消失并再次出现后重新打开。
- 仓储计划扫描默认关闭；只有配置 `merchivra.warehouse-alerts.scheduler-enabled=true` 才允许启动本地-only 巡检。没有 Amazon 写入、自动采购或外部消息。
- V41 约束与接口见 `docs/purchasing/local-stock-alerts-v41.md`；CI 不替代 PG18/Flyway/多实例真实验收。

## V42 · 供应商规则与采购计划安全边界（2026-10-31）

- 供应商 SKU 条款按租户隔离，包含 MOQ、包装倍数、交期自然日、原币采购单价；不得将其解释为 Amazon 货件 ETA 或真实平台成本。
- 本地采购计划只允许仓库/SKU 有库存流水证据且 V41 本地策略触发低库存时提出；数量 = `ceil(max(targetStock-onHand,MOQ)/packMultiple)*packMultiple`，最大 PO 单行 1,000,000 件。
- 人工保存计划和人工转采购单 DRAFT 是两次不同的 Owner + CSRF 动作；转换前必须重新检查仓库最后流水 ID、本地库存、策略和供应商版本。请求 UUID 幂等，同一仓库 SKU 库存流水证据全生命周期不可重复计划/创建 PO；变更一经保存保留来源快照/审计。
- 后续继续既有双人审批，不自动向 Amazon、供应商、支付或 FBA Inbound 写入。未按仓分配的采购待收货仅供参考不能冲减本地仓库建议。V42 见 `docs/purchasing/supplier-purchase-planning-v42.md`；未经 PG18/HTTP 并发验收勿声称生产完工。
