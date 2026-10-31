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
- **前端**：Node 24 LTS、Vue 3.5、TypeScript 6、Vite 8、TanStack Vue Query 5、Zod 4、Vitest；使用已提交的 `apps/web/package-lock.json` 与 **`npm ci`**，不要改回无锁安装。
- **部署**：单体 API + Vue 静态页面、Docker Compose；Redis 预留而非所有功能都必须用。Dockerfile 与 GitHub Actions 必须和本地版本一致。
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

- 根目录 [DESIGN.md](DESIGN.md) 定义 Merchivra 的界面设计规范与质量检查清单。保留 Vue/TypeScript 业务代码、现有蓝黑品牌风格和 Core/Mock 数据边界；除非维护者同意，不安装 Impeccable 或其他设计插件、Hook、npm 依赖。
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
