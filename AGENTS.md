# Merchivra · Agent 开发策略

> 本文件是本仓库的长期开发约定，适用于自动化 Agent 和人工贡献者。
> 与根目录 `AGENTS.md` 保持一致。用户明确的新指示优先于本文；产品 G1/G2 待签署的业务方案不得因本文自动视为批准。

## 目标与协作

- 维护 **Merchivra（麦奇维拉）**：Apache-2.0、开源、自托管、Amazon Seller ERP；优先交付能运行的业务纵向闭环。
- 按维护者现行指示：**直接在 `main` 开发、逐步提交并验证 CI**；不再创建长寿命分支或 Draft PR，除非维护者更改要求。更新相关文档与 GitHub Issue，报告确切提交/检查结果。
- Amazon SP-API 接入**优先使用官方 Java SDK `software.amazon.spapi:spapi-sdk:1.13.0` 与官方 OpenAPI 模型**；已有 `SpApiOfficialSdkFactory` / `SpApiOfficialModules` 和保守 Core Read Adapter。未来新增模块不应优先手写无类型的 URL 接口，须按操作审查 DTO/角色/速率/权限。见 [INT-004](docs/integrations/sp-api-code-integration.md)。
- 优先使用经过广泛验证的官方 Starter/成熟库；不要重复实现基础设施，不因新潮就加入 Kafka、微服务、重型前端框架或与业务无关的依赖。
- **V74 解释性补货纪律**：仅 V22 新鲜完整 FBA 与 V70 完整 28 天订单数据的 MOCK/LIVE 来源一致时才计算示意覆盖天数；未知或零订单不得伪造未来断货日期。V42 lead time 是人工条款，V37/V71 明确来源的本地 PO 不能当 Amazon FBA 在途。V24 LIVE Catalog/FBA 的来源 CHECK 使用 V72 前向迁移显式允许 LIVE，仍拒绝未知来源。重复草稿前必须重新查询并人工确认，失败则阻断。见 [INV-074](docs/inventory/replenishment-priorities-v74.md)。
- **V73 供应商证据纪律**：仅用完整关联收货 ASN 与收货时已登记的人工 ETA、实际完成的 QC 样本；不足各 3 个样本分数必须 NULL，不把未知当失败。审批 7 天后无完整 ASN / 未申报发货可手动催办，SHIPPED ETA 异常继续由 V43 负责，不允许自动联系供应商或自动采购。见 [PO-073](docs/purchasing/supplier-performance-v73.md)。
- **V72 本地交付证据纪律**：已审批 PO 的剩余数量与已发 ASN 未关联收货分开；无 ASN 不猜 ETA，ASN DRAFT 不是发货，人工 SHIPPED 不是承运商证明。V45 差异、V46 QC 放行仍为独立事实，继续用 V43 ASN 告警队列而不再造另一套。见 [PO-072](docs/purchasing/purchase-delivery-v72.md)。
- **V71 供应商报价纪律**：FBA 补货与 MOQ/箱规复用 V22/V37/V38/V42；人类选择报价并生成 DRAFT 时，在同一事务内重新核对 OBSERVED 观测、ACTIVE 映射、供应商规则版本/原币单价/数量，审计原始建议和取整实购。交期只读，不可猜 Amazon 到仓时间，不可自动批准。见 [INV-071](docs/inventory/replenishment-supplier-quotes-v71.md)。
- **V70 补货证据纪律**：观察到的 7/28 天订单行件数不是预测/净销量；每个 SKU 需要完整 28 天成功同步窗口、已提交分页、全部订单项已观测、来源一致和 ACTIVE SKU 映射。缺失则 NULL，不伪造零销量；样本仅参考，不默认转换为采购 DRAFT；V37/V38/V42 原链不修改。见 [INV-070](docs/inventory/observed-demand-v70.md)。
- **V69 规则配置纪律**：站点级异常必须复用 V10/V67 ops_finding；规则禁用只影响下一次扫描，不能删除业务来源；使用租户锁 + optimistic version + 追加审计，源 UNKNOWN 不是 0。站内提示不等于外部消息通知。见 [OPS-009](docs/operations/ops-rules-v69.md)。
- **V68 安全/容灾纪律**：备份/恢复测试只在受保护的本机一次性 PG18 Mock 数据中执行，严禁在生产/真实 Seller 数据库运行 CI 的恢复脚本；优先官方 pg_dump/pg_restore、Spring Security Test、Flyway 和 Compose，结果需与生产正式演练区分。见 [V68 手册](docs/testing/release-readiness-v68.md)。
- **V67 复用策略**：复用 V10 ops_finding、Spring Scheduling / tenant 行锁、Spring JDBC、Zod + Orval/TanStack Query；新 Controller 的 DTO 优先采用 Boot-managed Hibernate Validator。避免与现有运营队列并行再造通知/审批基础设施。候选及引入门槛见 [V67 复用审查](docs/architecture/reuse-audit-v67.md)。
- 可参考 [Pagevela](https://github.com/kunlong-luo/pagevela) 的构建、依赖更新、持久化并发纪律，但不照搬其 React / Dexie 浏览器状态设计。

## 当前工程基线（2026-10-30）

- **后端**：Java 25 LTS、Spring Boot 4.1.2、Spring Security 7、Jackson 3、Spring JDBC + Flyway、PostgreSQL 18，构建工具唯一选择 Gradle 9.2.1（Gradle Wrapper）。
- **前端（2026-10-31 React 迁移覆盖早期 Vue 决策）**：Node 24 LTS、React 19.3、React DOM、TypeScript 6、Vite 8、TanStack React Query 5、Zod 4、Vitest；使用已提交的 `apps/web/package-lock.json` 与 **`npm ci`**，不要改回无锁安装。
- **部署**：单体 API + React 静态页面、Docker Compose；Redis 8 已可选接入共享 Session/CSRF 与跨实例 SP-API 冷却，默认关闭且单实例无需 Redis。Dockerfile 与 GitHub Actions 必须和本地版本一致。
- 维护者已淘汰 Maven：后端仅允许 Gradle Wrapper，必须同时更新 build.gradle、Dockerfile、GitHub Actions 和文档；禁止重新加入 pom.xml 或 mvn 命令。添加依赖前确认现有业务实际使用，避免把 Kafka/InfluxDB/Redis/JPA 强行变成运行必需。

## 测试与开发节奏

- **M0～M2 轻量 CI**：后端 Gradle 快速单元测试；前端 TypeScript 类型检查、少量 Vitest、Vite build。
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

用户已明确选择 **React 19** 替换 Vue：所有新 UI 用 React TSX、`@tanstack/react-query`、Vite React 插件、TypeScript + Zod。**禁止再新增 Vue SFC、vue-tsc、@vitejs/plugin-vue、@tanstack/vue-query、Pinia 或 Vue Router**。早期文件中的 Vue 描述仅为历史证据，**被本节覆盖**。保留 Spring Boot 4.1.2、Java 25、PostgreSQL 18、Gradle 9.2.1、Flyway、租户 RBAC、Amazon 安全门禁。详见 [React 迁移 ADR](docs/architecture/react-migration-2026-10-31.md)。锁文件必须经过真实 npm registry 解析并提交，Docker 和 CI 用 npm ci。React 页面需执行类型检查、Vitest、Chromium 360/390/768/1280 冒烟与 PG18 Mock 全链路后方可视为验收。


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

## V43 · 本地供应商 ASN 与来源可信度（2026-10-31）

- V43 仅允许 Owner 在已审批/正在收货的**本地采购单行**创建、人工标记供应商发货、修正预计到货日和取消 ASN。数据来自人工/供应商申报，不能代表 Amazon 真实货件、承运商确认或本地实物收货。
- 只有 `erp_purchase_receipt` 记载实际到仓；`asn_id` 可为空以兼容历史 V20/V39 收货。关联时后端必须锁采购单、核对 tenant/order/line/warehouse、申报 SHIPPED 状态及剩余件数，同一个事务记录采购收货、ASN 审计和本地仓库流水。不能将无 ASN 收货静默映射到某 ASN。
- ASN 单行累计申报总量（未取消）加未关联 ASN 的实际收货总量不能超订购行数量。取消任何已有实际关联收货的 ASN 必须失败；人工 ETA 修改需旧 ETA 快照、原因及审计。
- `SUPPLIER_ASN_OVERDUE` 和 `SUPPLIER_ASN_DUE_SOON` 仅当供应商上报发货、未完全实际收货、PO 仍可收货时匹配。扫描默认手工；可选 `merchivra.supplier-asns.scheduler-enabled=true`，**不发送外部消息、不调用 Amazon**，工作队列状态保留审计。
- `docs/purchasing/supplier-asn-tracking-v43.md` 为接口和边界。真实 PostgreSQL18 迁移、HTTP 和多会话并发须另行验收，不得将编译成功解释为生产已对接。

## V44 · 本地实收质检与隔离品来源（2026-10-31）

- V39/V43 的 `erp_purchase_receipt` 是唯一真实本地采购收货依据；不可把供应商 ASN 申报数量或历史无仓库收货静默认领为当前仓库库存或质检实收。
- V44 每笔实际收货最多完整质检一次，必须满足 `accepted+rejected=receipt.quantity`，使用 Owner/Session/CSRF、请求 UUID 幂等、原因审计。此前已入账的完整实收不重加合格数；**仅 rejected 件数生成唯一负向 QC_QUARANTINE 流水**，并在仓库锁下拒绝把台账余额扣成负数。
- 不合格品独立隔离，供应商退货的所有非取消草稿累计不得大于拒收件数。退货 DISPATCHED 是人工申报发出，既不等于供应商签收或退款，**更不能在质检已隔离的库存上再次扣减**。所有动作保持租户复合 FK 和只追加事件。
- 质检异常工作队列仅监控已有本地不合格品的未处置和超期 7 天，默认手动扫描；可选 `merchivra.purchase-quality.scheduler-enabled=true`。不自动采购、发货、退款、Amazon FBA/FBM 写入。
- 实施与限制详见 `docs/purchasing/receipt-quality-and-supplier-returns-v44.md`。真实 PostgreSQL 18 迁移、并发/权限及业务集成验收仍须单独完成，不可把编译 CI 视为生产验证。

## V45 · 供应商差异与索赔的可信度边界（2026-11-01）

- `PO_SHORTAGE` 与 `ASN_SHORTAGE` 只是 Owner 记录的**当前未收足快照**，不能当成最终供应商损失；`OVERAGE_UNPOSTED` 只是人工观察到的额外实物，绝不能自动修改 `erp_purchase_receipt` 或本地库存正数流水，也不能悄悄突破原采购单收货上限。
- 人工差异必须带来源采购行/可选 ASN、请求 UUID、原因及原始数量快照；审核差异的 Owner 与报告人不同，复核/建立索赔/提交/审批时要对真实收货或采购剩余容量重新校验，过期来源一律拒绝。原始事件、审核人和审计不能覆盖。
- 索赔仅能以**已经另一 Owner 复核的差异**或 V44 已完成质检的不合格品为来源，一次来源最多一份索赔，件数不超过来源。索赔另走 DRAFT → SUBMITTED → 另一 Owner APPROVED → 带说明 CLOSED；不自动向供应商发信、不创建赔偿金/信用票据、不更改 Amazon FBA、财务或结算。
- 供应商统计只使用已审批采购单实收、质检不合格和可核对的异常数；禁止没有承运商证据就输出虚构“准时率”、退款到账或跨币种总成本。本模块接口与未验收项见 `docs/purchasing/supplier-discrepancies-claims-v45.md`；后端 CI 不代替 PG18/HTTP/并发实测。

## V46 · 新收货待检与双人质量放行硬性边界（2026-11-01）

- 新 V46 实物 `erp_purchase_receipt` 先进入 `erp_receipt_quality_batch` PENDING_QC，不能在 `receive` 同一动作立刻把全数收货加到本地可用余额；V44 及更早历史收货已经有库存流水，严禁回写、删除或重新生成正数放行流水。
- 新批次检验仍采用单笔真实收货全数 `accepted+rejected=receipt.quantity`，只写一次不可变 QC 快照；V46 检验不生成仓库正/负库存移动，V44 历史检验仍保留原负数 QC_QUARANTINE 追溯路径。必须以后端存在的待检批次为判断依据，不能相信客户端切换标志。
- `QC_RELEASE` 仅可在另一位不同 Owner 对 INSPECTED 批次显式放行时产生，正数数量必须等于已记录 acceptedQuantity；零合格不得产生移动。tenant 行锁 + 仓库行锁 + QC 唯一来源保证重复点击不双入账；必须保留追加式批次事件、released_by 和时间。
- 批次码仅在 PENDING_QC 时允许变更，并校验原码版本和可打印安全字符、原因，所有修改留原值审计。供应商 ASN SHIPPED 仍不是承运商验证，待检不是可售或 FBA 已接收。可用库存与累计实收分别展示。
- 详见 `docs/purchasing/held-receipt-lots-quality-release-v46.md`；**真实 PostgreSQL18 Flyway / 跨租户 HTTP / 并发锁**未经专项验收，严禁把 Java/React CI 成功误报为真实 Amazon 接入成功。

## V47 · 收货分段、虚拟 QC 库位与补货证据约束（2026-11-01）

- V47 的 `erp_receipt_lot_piece` 是**原始实收的待检分段标签**，只允许 PENDING_QC 阶段拆分/移动；其件数总和必须恒等于真实 receipt 对应 V46 batch.received_quantity。不得把它们当作售出批次、FEFO 扣减、真实物理货架验证或各片段独立 QC/放行。
- 每个仓库自动创建系统内部 `QC-HOLD`，新增库位目的 `QC_HOLD` 或 `STORAGE`；只有同 tenant、同仓库、ACTIVE QC_HOLD 才能接收未检分段。系统虚拟默认库位不证明实物已扫描或上架。新增实收 ROOT 分段要随收货同事务创建，缺少 QC-HOLD 时失败回滚。
- 拆分必须在 tenant+batch 锁下保存来源 qty - n 和新片段 qty=n，要求 1<=n<原片段当前件数、唯一分段码、目标待检库位、原因和 requestKey；移动需要来源 bin 预期值与目标 bin，重复 requestKey 不重复移动或分段，记录只追加事件。**任何分段/库位操作都不产生 `erp_local_stock_movement`，也不修改 `erp_purchase_receipt` 或 Amazon FBA。**
- 库存补货压力视图必须把来自仓库已签库存流水的 usable、PENDING_QC、INSPECTED 待放行、ASN 申报 SHIPPED 未关联收货数量分开，不得相加成可售库存；V42 原补货决策仍以已发布本地库存流水为准，不能自动提交采购单。
- 详见 `docs/purchasing/receipt-lot-locations-v47.md`。Java/React CI 不替代 PostgreSQL 18 Flyway、跨租户 HTTP 与多事务锁验收。

## V48 · 分段 QC、已放行 STORAGE 和 FIFO/FEFO 出库的硬性不变量（2026-11-01）

- `erp_receipt_piece_grade` 只来源 V47 PENDING_QC 真实收货 piece，必须 1 次全量记录 `accepted+rejected=piece.quantity` 且留操作人与原因、租户 requestKey；一旦开始 grading，冻结所有分段拆分和待检移动。只有所有分段覆盖齐全且总和等于原始实际实收数量，才调用 V44/V46 唯一整笔 QC。旧 inspect API 不得绕过部分分段 QC；整笔放行仍需与最终 QC inspector 不同的 Owner。
- `erp_local_lot_position` 仅允许 V46 `RELEASED` 的 accepted 商品显式放进同仓 ACTIVE `STORAGE`，累计 `placed_quantity<=QC.accepted`，`SUM(remaining_quantity)<=仓库同 SKU 真实签名可用余额`。上架动作绝不再次生成 QC_RELEASE 或正数库存移动；未建位置的历史存量不得假造来源/有效期。
- FIFO/FEFO 只在 Owner 确认真实出库后调用统一 V40 `OUTBOUND`；在 tenant/仓库串行锁下将批次库位扣减、标准签名出库流水、操作和 pick_line 审计组成同一事务，任何中间失败全部回滚。FEFO 有效期来自人工记录；过期批次禁入自动拣货、未知有效期排在已知日期后，不得虚构承运商或 Amazon 证明。
- 普通 V40 OUTBOUND/TRANSFER/COUNT 减少库存后不可使余额低于定位批次余量，应拒绝并要求 lot-aware pick。不得只在 UI 实现该检查；支持原始 expectedOnHand、tenant+requestKey 幂等、来源和 actor 审计。
- 详见 `docs/purchasing/graded-lot-putaway-fifo-fefo-v48.md`；Java/React CI 成功不等于 PostgreSQL18 真实 Flyway、竞争事务或跨租户 HTTP 完整验收，真实 Amazon SP-API 在核心功能后联调。

## V49 · 已放行批次跨仓与过期隔离的不可突破约束（2026-11-01）

- 已放行位置同仓 MOVE 只改来源库位且实际剩余>0，须 ACTIVE 同仓 STORAGE、expectedRemaining 一致、请求键幂等和追加式来源事件，不得改签名总库存。COUNT_OBSERVATION 只能记录真实清点数和差异/原因，不能直接调整库存、擅自生成供应商财务记录或伪造经过审批的 COUNT 操作。
- V49 TRANSFER 只处理已由另一 Owner 放行并明确上架、未过期的真实批次余量；源和目标不同 ACTIVE 仓库/真实 STORAGE 库位，tenant+来源位置+两仓锁、expectedOnHand 校验，同事务扣位置和调用 V40 TRANSFER 签名仓库流水，创建 origin_position_id 指向源位置的目标位置，保留 receipt/lot/人工有效期，失败一律回滚。V48 原始 putaway 合格件额度仅统计 origin_position_id IS NULL 的根位置，不能因跨仓子位置导致原实收额度重复计算。
- EXPIRED_QUARANTINE 只允许有登记有效期且确已过期的批次，人工确认后负向扣减已放行位置余量与本地签名可用库存，唯一 lot_action_id 来源的 LOT_QUARANTINE，不得伪装 V40 OUTBOUND。预警只是只读，不能替代真实物理扫码隔离；未知有效期不可自动认定过期。
- V49 owner Session + CSRF，tenant requestKey、actor、payload 不允许重用为另一动作；所有来源、目标 bin 和仓库必须 tenant 复合验证。存储位置来源链可经递归 origin_position_id 找到最初 V46 真实收货，允许审计跨仓变化，但不可由这些本地记录推断 Amazon FBA 已接收或外部 WMS 已执行。
- 详见 `docs/purchasing/released-lot-lifecycle-v49.md`。CI 不能替代 PostgreSQL 18 Flyway / Compose、HTTP 多租户、并发锁和真实 Amazon 授权验收。

## V50 · 双人盘点调整、跨仓确认与隔离处置必须保留的证据（2026-11-01）

- V50 `erp_local_lot_count_review` 必须唯一引用 V49 COUNT_OBSERVATION 的真实位置、原 bin、仓库、SKU、expectedRemaining、实际实点数与观察人；提交时还需保存当时签名 warehouse expectedOnHand。审批者不能是观察人/提交人，必须再次核对 bin、位置和全仓库存快照。若少货，**同事务先将源位置 remaining 减少到实点，再使用 V40 COUNT 只做该差额的签名负向全仓调整**；任一失败必须全部回滚。无差异不调账；盘盈为 EVIDENCE_REQUIRED，不能凭审核意见虚构已放行合格件数或正向库存流水。
- V49 TRANSFER 已同步产生源/目标仓签名流水、目标子位置；V50 目的仓 ACK 只是不同 Owner 对实际收到的数量、参考编号与原因的第二人确认，**不能再次执行 TRANSFER_IN**。MISMATCH 单独保留异常，不自动偷偷改变原有台账；超出已过账数的盘盈需另取来源证据。一个 TRANSFER 只允许一个确认键/来源。
- V50 `erp_local_lot_disposition` 只有从 V49 EXPIRED_QUARANTINE 且存在唯一同等负数 `LOT_QUARANTINE` 签名移动的来源可申请。一个隔离 action 一个处置计划；提出人/原隔离执行人不能独立审批自己；审批人不能自己报告实际执行。批准和报告处置均**不得再次扣减或增加签名可用库存**，完成只表示人工记录，不能冒充承运商/供应商确认。
- 所有 V50 操作从 Owner session 取得 tenant/actor，CSRF 必需；tenant + requestKey 幂等，历史证据不可覆盖。对盘点审核、跨仓确认和隔离处置必须分别有独立 API、来源、操作人与状态审核审计；测试应覆盖无双入账、无负库存以及审查前后库存或 bin 改变的拒绝行为。
- 详见 `docs/purchasing/lot-count-review-transfer-receiving-disposition-v50.md`；CI 不能冒充 PostgreSQL 18 Flyway/Compose、多租户并发、真实 Amazon OAuth/SP-API 已验收。

## V51 · 盘盈来源再定位、短收纠正和内部凭据审核约束（2026-11-01）

- 只有 V50 已独立审核成 EVIDENCE_REQUIRED 的盘盈才可申请 `RELEASED_SURPLUS_BIND`，来源必须是本 tenant 的 V46 RELEASED 且 accepted 的真实收货根位置（origin_position_id IS NULL）。审核时重新核对 position/bin/expected_remaining、全仓 signed balance、原实收所有根位置累计 placed<=accepted、当前仓该 SKU 所有定位 remaining 加待绑定差额<=signed usable。成功只能增加已存在的 root placed/remaining 并追加 SURPLUS_BOUND，**绝不创建新的正数库存流水、收货/质检或虚构商品**。超出已放行但未定位库存的真实盘盈仍须新合法实物入库与 QC，不得绕过。
- 仅 V50 第二人已签收但数量少于 V49 原调拨的 MISMATCH 可申请 `TRANSFER_SHORTAGE`。审查人不能是申请人或原签收人，必须在 tenant/仓库锁内验证目标子位置尚未消耗/移位、remaining=原已过账调拨量、warehouse 签名余额与申请快照一致；同事务先减目标位置 remaining，再使用原 V40 COUNT 做等量负向签名库存调账并记录操作来源。禁止再生成 TRANSFER_IN/OUT；失败全部回滚。同源唯一 requestKey/唯一一次纠正。
- V51 已报告处置的 proof 只保存人工录入的 DISPOSAL_CERTIFICATE 或 SUPPLIER_RETURN_RECEIPT 参考编号、可选 SHA-256 摘要和说明，类型必须匹配 V50 已 EXECUTED_REPORTED 的 method。当前**没有文件上传和 SHA-256 对原件验算、物流/供应商第三方认证能力**。只有与凭据登记人、原处置执行报告人不同的 Owner 可以设置 METADATA_REVIEWED，意为本地元数据审核而非文件真实性/供应商签收核验，不得触发库存二次扣减。
- V51 统一 tenant/actor 仅来自 Owner Session，写操作 CSRF；任何请求键重复重放必须同 actor/来源/数量和证据，异内容冲突。不要让普通 V40 COUNT/转仓绕过已有已定位批次保护。新增功能测试需覆盖审批人与业务来源分离、根来源合格与已定位双上限、跨仓目标位置快照、完整事务回滚及证明元数据的非库存副作用。
- 详见 `docs/purchasing/lot-exception-source-corrections-proof-v51.md`。Java/React CI 成功不等于 PostgreSQL 18 真实迁移/Compose 并发、外部证明或 Amazon SP-API 已验收。

## V52 · 私有真实凭据与手工供应商退货来源的硬性规则（2026-11-01）

- 原件上传必须来自真实已登记的 V51 proof，只有 `PENDING_REVIEW`、本租户 Owner、CSRF 请求可新上传。只允许经服务端实际字节签名验证的 PDF/PNG/JPEG、上限 2 MiB；由服务端 SHA-256 计算真实文件摘要，与用户先前填写的 `document_sha256` 不一致直接拒绝。tenant+proof 和 tenant+requestKey 唯一，不得覆盖已上传的二进制，不得因重试上传不同文件。GET 文件字节须验证摘要/长度、授权 tenant、attachment+nosniff+no-store，文件名不得取自用户任意输入。
- `METADATA_REVIEWED` 仍是**内部元数据审核**，不能等于原件合法、签名真实、供应商签收或承运商证实；当前没有恶意文件扫描、对象存储加密或电子签名验证。上传仅保存私有数据库字节，不可写入日志或公开附件链接。
- V52 供应商退货严格引用 V50 `EXECUTED_REPORTED` 且 method=SUPPLIER_RETURN 的已扣库位过期品；原 supplierId 必须沿 V49 source_position → V48 original receipt → V20 original purchase order 得到，客户端不得指定。V44 erp_supplier_return 是 QC rejected 实物的另一个来源，**绝不可混同或重复计数已放行后过期隔离品**。人工回执需要非发运 Owner、数量不超过发运量；差异只记 RECEIPT_MISMATCH，不自动改变库存和应收财务，不能冒充 Amazon/第三方验证。
- GitHub CI 后端/前端单测之外必须启动独立 postgres:18 实例运行全 Flyway 并校验 V46–V52 迁移；不能把 Mock Compose 队列运行、尚未完成的 PG18 job 算作验收通过。Java 25 / Spring Boot 4.1 / React 19 / PostgreSQL 18 持续固定。
- 详见 `docs/purchasing/lot-proof-files-supplier-trace-v52.md`。


## 2026-11-01 · 维护者确认的唯一后端构建工具：Gradle

- Maven 已淘汰。所有新业务模块和 CI 必须使用 `apps/server/build.gradle`（Groovy DSL），Gradle 9.2.1（`apps/server/gradlew`/Windows `gradlew.bat`）和 Java 25 toolchain。禁止添加 `pom.xml`、Maven Wrapper、`mvn` 构建命令或 Maven 构建镜像。历史日志中的 Maven 表述只能说明历史版本。
- Spring Boot 保持项目已验证的 `4.1.2`（高于模板 `4.1.1`），`io.spring.dependency-management` `1.1.7`，不要把另一项目的 `com.spark.iot` 包名/业务依赖直接复制过来；当前 group 必须是 `org.merchivra`。
- 必需功能以 Spring JDBC、Flyway、Security、Web MVC、官方 Amazon SDK 和 PostgreSQL 18 为核心；Redis/Kafka/InfluxDB/JPA/PostGIS/WebSocket/Zen Engine/MapStruct/Lombok 等仅在有真实实现且测试覆盖时添加，当前 spark-db 共享环境不应因此被强制依赖。
- CI 必须执行 Gradle test + JaCoCo 报告，真实 PostgreSQL 18 的 bootRun/Flyway 迁移，React 19 的 npm ci/typecheck/Vitest/build，及 Docker Compose 服务数量验证。83% 行覆盖率保留为 `-PstrictCoverage check` 严格目标，在未达到覆盖率前不得虚假标记通过。所有 Gradle 分发和 Wrapper JAR 应校验 SHA-256。

## 2026-11-01 · V54 Redis 8 架构与安全约束

- 已实现（但保持显式开关）：`MERCHIVRA_REDIS_SESSIONS_ENABLED=true` 将 Spring Security Session/CSRF 存入 Redis（4h/独立命名空间），`MERCHIVRA_SPAPI_RATE_LIMIT_ENABLED=true` 将官方 SDK 和旧读 HTTP 的出站限制到跨实例共用的原子 Redis cooldown；不应恢复仅内存的跨实例配额和 Session。
- 现有 `spark-db` 上 Redis 8 可通过 `spark-iot-net` 的 `spark-redis:6379` 复用，密码由本地 `.env` 注入，禁止改动或删除用户的原 `spark-redis`、`iot_farm`、数据卷及其他服务 Key，禁止 FLUSHALL/FLUSHDB。
- PostgreSQL 18 是订单、采购、财务、审计及同步 checkpoint 唯一事实来源；Redis 不可用时不能让 Amazon 出站限流或 HTTP 登录会话 fail-open。单实例且两开关关闭时必须能完全不依赖 Redis 启动。
- 共享 `spark-db` 使用 `allkeys-lru`，可淘汰限流和 Session Key；生产不可直接当成强限流保证。要求独立 `noeviction` Redis/容量/ACL/TLS/告警方案，不能擅自修改现有全局 Redis 淘汰策略。
- CI 应同时保持无 Redis 的普通 PG18 Java25 Gradle / React19 验收，以及专门 Redis **8** 的真实登录、重启恢复、故障 fail-closed 集成；API cooldown 只是保守限额，不代表已按 Amazon 动态 Usage Plan 正式验收。详见 `docs/deployment/redis-session-spapi-v53.md`。

## 2026-11-01 · V55 Amazon SP-API 配额与后台任务退避约定

- 统一使用 Gradle Wrapper / Java 25 / Spring Boot 4.1.2。Gradle 入口是 `apps/server/settings.gradle`，仓库不引入 Maven 构建。
- Amazon 的 `x-amzn-RateLimit-Limit` 是按操作和授权维度尽力提供的响应元数据，不保证出现，也不代表所有 usage-plan 或 burst 限制。缺失/无效时保持保守配置。
- 旧版经过审核的 SP-API HTTP 读通道把响应头写入 Redis，以区域+操作+受保护 grant 指纹学习 24h 冷却间隔；429 通过共享 Redis fence 保留较长期限，Redis 出错仍 fail-closed。
- 官方 SDK 的 Orders / Listings / FBA Inventory / Finances 受审核的 typed read 操作现在经 `executeObservedRead` 取得 Redis 操作级许可，利用 `WithHttpInfo` 学习响应配额和 429 响应头；其他生成式 SDK 方法仍是家族级安全阀，尚未实现全量方法级动态配额及 burst/token-bucket，不能宣称全量完成。
- `SpApiRetryPolicy` 支持 Retry-After 秒数和 HTTP-date；指数增长加随机抖动、上限 1 小时。重试必须写 PostgreSQL `next_attempt_at`，禁止 HTTP 请求线程 sleep 或原地无限重试。
- Orders 后台同步对 429、临时 HTTP 错误与 Redis 拒绝在租约 fencing 后保存重试时间，最多 8 次，之后暂停；due 查询按租户交错公平分配。不得绕过租户/卖家授权。
- 不需要真实 Amazon 授权做这一阶段编码；本地正式启用前需专用 noeviction Redis、真实店铺授权及独立角色审查、实际响应头验收和完整 CI；远端写入继续禁用。

## 2026-11-01 · V56 Redis GCRA、批准的 Burst 与指标

- 下一阶段的设计、接口、运行测试详见 docs/integrations/sp-api-quota-scheduler-v56.md；Java 25 / Spring Boot 4.1.2 / Gradle 9.2.1 / PostgreSQL 18 / React 保持不变。
- 经审核的官方 SDK 只读 SEARCH_ORDERS、SEARCH_LISTINGS、GET_FBA_INVENTORY、LIST_TRANSACTIONS 走 operation-scoped wrapper，在请求前原子申请配额，用 WithHttpInfo 观察响应头，不能再重复使用 SDK builder 冷却以阻止已批准 burst。其他 SDK 调用仍未全面实现操作级限流。
- Redis 8 GCRA 使用 Redis TIME，429 fence、速率和下一次允许时间必须在单个 Lua 操作内判断。Redis 空响应/失败一律拒绝，绝不在单机内存中绕过；不要在生产共享 allkeys-lru Redis 上宣称强保证。
- Burst 默认 capacity=1。仅人工审查 Amazon 真实 usage plan 之后允许通过 MERCHIVRA_SPAPI_APPROVED_BURSTS 显式设置 2–10，且只有观测到有效的逐操作 rate 时才生效；不得从 x-amzn-RateLimit-Limit 猜测 Amazon 允许的 burst。
- 指标仅允许 operation/result/status 类低敏标签；不记录 sellerId、grant、token、订单、SKU 等。/actuator/metrics 不默认开放给普通店铺用户；tenant-safe Dashboard 另行设计。
- CI 除 Java25/Gradle PG18 React 外，必须在 Redis8 真服务验证 GCRA 的缺省容量、显式 burst、429 fence、未知 metadata，禁用真实 SP-API、禁用 FLUSHALL/FLUSHDB。

## 2026-11-01 · V57 后台同步任务监控策略

- 只读任务监控文档：docs/operations/sync-task-monitor-v57.md；端点 /api/v1/operations/sync-tasks 必须从认证 Session 的 TenantAccessService.currentTenant（Owner 权限）推导租户，不接受客户端租户参数。三张 PostgreSQL 作业表每一支查询均必须带 tenant_id=?。
- API 严禁查询/返回 Amazon refresh/access token、分页 cursor、lease_owner、原始订单或 Amazon 响应体；只提供任务状态、更新时间、重试次数、下次重试和固定安全错误码，响应 no-store。
- React 只在 Owner「运营自动化」页面展示同步监控。对 401/403、空任务、运行中、429 等状态必须准确描述，不把空列表解释成同步完成。面板只读，无启动/强制重试按钮。
- 测试覆盖后端 SQL 作用域与筛选限制、Session 租户、Zod 前端数据契约；不需要真实店铺授权。Java 25 / Spring Boot 4.1.2 / Gradle 9.2.1 / PostgreSQL 18 / React 19 不得回退。

## 2026-11-01 · V58 Amazon Reports 结算来源隔离策略

- 设计与实现边界：docs/finance/settlement-report-ingestion-v58.md。Amazon 结算报告应通过只读 getReports / getReportDocument 发现 Amazon 自动生成的文档，不能使用 createReport 假设可主动生成此类型。未完成的持久发现队列与授权本地验收不能称为已完成。
- 读取需 VERIFIED/CONNECTED Seller+Marketplace 与独立 Finance and Accounting 审核权限，`MERCHIVRA_SPAPI_REPORTS_ENABLED=false` 默认关闭；真实出站还须 Redis 8 共享配额。Reports 缺少学习值时初始冷却至少 60 秒，不凭 `x-amzn-RateLimit-Limit` 猜测 burst。
- getReportDocument 返回预签名短时效 URL 不可暴露浏览器或日志；只允许已审查的 AWS HTTPS 域名、无重定向、严格大小、内存处理且零磁盘落地；生产另需网络出口 ACL、防 DNS rebinding。
- 原始 Settlement 报告数据只能在独立 tenant+marketplace 的 staging 表幂等保存白名单非 PII 列；报告金额不等于 Finances 入账，也不等于银行已结算。禁止直接写 finance_source_event、利润结果或自动将同笔 Reports + Finances 金额相加。
- UTF-8/TSV/GZIP 解析要对未知列、列重复、混币、超大和错误金额 fail closed；报告 ID + 文档 SHA256 不能被静默覆盖。HTTP 只能提供当前 Session Owner 的只读摘要/明细。
- 仍需开发：报告发现任务持久队列/租约和批量分页、脱敏异常审计、真实 Seller 最终本地联调、来源匹配复核和生产部署安全。Gradle / Java25 / Spring Boot4.1.2 / PostgreSQL18 / React19 不变。

## 2026-11-01 · V58B 结算报告持久队列和来源对账

- 阅读 docs/finance/settlement-report-ingestion-v58.md 的 V58B 段落。严格区分: Amazon Reports 独立 staging / LIVE Finances 交易 / 真实结算 / 银行到账。
- 报告发现只由 Owner 显式启动，服务器持久 page token + rowOffset 及租约 fencing；下载必须重新获取短效签名 URL，超时/过期后从固定窗口幂等重扫。严禁在失去 lease 后将来源行提交。
- Reports 真实网络默认关闭，调度默认关闭，生产只用读操作及 Redis 8 fail-closed 操作级配额。不得由 UI 接收任意 Seller/租户 ID、URL、报表内容或授权 Token。
- 报告与 Finances 只读对账按当前 tenant、Marketplace、SETTLEMENT_ID、币种精确匹配；不得自动视为会计入账、退款或银行支付。源合计一致仍必须标识未验真。
- V57 的任务监控须包含 V55 REPORTS 任务，同时维持四条分支 tenant_id 条件和非敏感字段清单。

## 2026-11-01 · V59 双人审核与可信财务来源
- 阅读 docs/finance/settlement-source-review-v59.md。审核申请 Owner 与审批 Owner 必须不同，审计只追加、备注有界、权限由服务端 Session TenantAccessService 校验。
- 来源 APPROVED 仅表示 Amazon Reports 与 LIVE Finances 在同站点、相同 Settlement ID/原币下有符号金额已通过双人来源复核；bankVerified 与 ledgerPosted 永远 false，不能借此过账或确认到账。
- 哈希包含原始报告摘要与同一个 Settlement ID 精确关联的所有来源交易；证据变化显示 STALE 并需要重审，重复 Settlement ID 报表禁止批准。
- 服务端 PostgreSQL 18 / Java 25 / Gradle、React 19 与真实 Amazon 默认为关闭的策略保持不变；有状态审核不得启用 Amazon 写接口或默许浏览器 tenant ID。

## 2026-11-01 · V60 银行人工来源不等于银行到账

- 参考 `docs/finance/manual-bank-payout-v60.md`。标准化脱敏 CSV 银行参考只允许 MANUAL_UNVERIFIED；严格 7 列、最多 50 行 64 KiB，无账户号/PII/原始文件长期存储。SHA-256 和人工双人审查都不能证明款项真实到账。
- 必须按 tenant+Marketplace+Settlement ID 关联 Reports、LIVE Finances 双人来源审核与银行参考；PostgreSQL 18 联合外键约束，不得跨站点链接或覆盖不可变银行观察。
- 只能把同币种金额相等的人工银行来源交由独立 Owner REVIEWED；审批人既不能是上传人也不能是申请人。fx_rate 为人工声明值，跨币种差异仅用于显示，不允许自动批准为到账或对账完成。
- 所有 HTTP 接口保持 Session Owner + CSRF + no-store；bankVerified=false 和 ledgerPosted=false 不可被业务 UI 或 API 偷改。真实银行确认需受信银行来源与另行设计的审核，不得由 Mock 升级真凭证。
- 继续使用 Java25、Spring Boot4.1.2、Gradle、PostgreSQL18、React19；不启用真实 Amazon 写调用或未经验证的银行出站连接。

## 2026-11-01 · V61 SKU / 店铺贡献利润预览

- 参考 docs/finance/sku-profit-preview-v61.md。店铺与 SKU 聚合优先复用 OrderContributionEngine 的已审核 LIVE Finances + 历史有效 SKU 成本，禁止另算 Orders estimated proceeds 或把 Reports+Finances 双重计入。Amazon/FBA 负费用是签名来源数，不二次扣减。
- 订单级金融交易仅当订单全部当前商品映射到同一内部 SKU 且历史成本与财务分类都齐全时，可归属该 SKU。多 SKU 订单、退款回转未知、分类/事件链接未知必须标示阻塞，不按数量、收入猜分，也不以 0 表示来源缺失。
- 报表分页每页 30，UTC 时间窗口不超过 92 天，offset 0..3000、必须按30对齐。页内 SKU 归集与 CSV 只能算当前页，不得暗示整期完成。`financialSourceComplete`、`bankVerified`、`ledgerPosted` 始终 false。
- 手工 Ads 支出 `MANUAL_UNVERIFIED` 只能另示参考，不能伪装成可信 Ads API 或自动从贡献中扣除；零差额也不能声明完整净利润。
- CSV 防 spreadsheet formula 注入，接口只从 Session Owner 推导租户、no-store，无数据写入/外部 Amazon 或银行调用。技术栈保持 Java 25、Gradle、Spring Boot4.1.2、PostgreSQL18、React19。

## 2026-11-01 · V61 后计划复核与工程约束

- 当前阶段顺序见 `docs/roadmap.md` 的 2026-11-01 V61 复核章节。旧 V1–V54 是历史记录。优先 V61.1 本地 DX/Mock 稳定性 → V62 多 SKU 审核分摊 → V63 退款回转 → V64 整期异步快照 → V65 FBA 货件只读 → V66 Ads 独立授权 → V67 自动异常 → V68 发布安全。
- 用户的 IntelliJ 本地报错 `SCRAM-based authentication ... no password was provided` 是进程环境缺少 `DB_PASSWORD`。按 `docs/development.md` 配置 IDEA Run Configuration，并用交互式 psql 验证；不得提交密码、关掉 PostgreSQL 认证或破坏数据卷。
- 保持 Java25、Spring Boot4.1.2、Gradle、React19、PostgreSQL18 和可选 Redis8；不用 Maven/Vue，不以 OpenTelemetry javaagent 为前置。不重复计算已有 OrderContribution/V29/V34–V36 来源事实。
- Java/React/PG18 CI success 不等于用户本地成功或真实 Amazon/银行联调完成；产品 G1/G2/G3 尚待签署。依 Owner 要求先补齐所有核心代码，最后由 Owner 自己在本地授权并执行实际 API 验收。

## 2026-11-01 · PostgreSQL 独立集群初始化安全边界

- `apps/server/scripts/provision-merchivra-database.sql` 是仅供 DBA 手工一次性执行的**纯 SQL 集群操作**（仅外部 `spark-postgres` / 指定授权 PG18），不得放入 Flyway、`bootRun`、CI、`Dockerfile`、Compose 启动、自动安装或后台任务。
- 必须以授权管理员连接 `postgres` 维护库，确认 `merchivra` Role/DB **均不存在**且目标正确；每条语句 AUTOCOMMIT / STOP ON ERROR / 顺序运行，禁用 `BEGIN`/`--single-transaction`。任一对象已存在必须检查现状，不能自动修复、重置 Owner、删除或覆盖。
- 首先创建 `NOLOGIN`/非超级用户专用角色，独立数据库并撤销数据库级 PUBLIC 默认权限；实际 SCRAM 强密码只能通过受保护 GUI 或交互式 `psql` 设置，**确认设置成功后**才单独启用 `LOGIN`。`\password` 不属于可执行 SQL。
- Compose 内置 PG 模式由官方 PostgreSQL 镜像自行 bootstrap，不得运行此脚本；应用的 Flyway 仅创建/更新已存在 `merchivra` 库内业务 Schema。绝不影响 `iot_farm`、其他项目的角色/库、磁盘卷或共享 Redis。
- IDEA 直接运行使用本地进程 `DB_PASSWORD`；外部 Compose 从独立 `.env` 的 `MERCHIVRA_DB_PASSWORD` 注入。这些配置不应进入 Git/日志，且该脚本不能代替应用环境变量配置。

## 2026-11-01 · V62 多 SKU 金额归属

- 先读 `docs/finance/multi-sku-item-allocation-v62.md`。V60 Flyway 表是逐 `LIVE Finances event × 当前订单 external_item_id × 类别` 有符号归属，只接收人工批准的 Finances 分类叶节点 PRODUCT_SALES/AMAZON_FEE/FBA_FEE，不自动猜比例、不擅自分类退款或调整。
- 原始同 Marketplace 的事件到订单 MATCHED 是前提，订单所有商品项必须在原订单日期正确映射 SKU/历史成本；缺失/重复/混币/未知数量只能阻塞。
- 每类金额不得超过来源绝对值、符号方向一致；允许 DRAFT 残差，但只有完全零残差才提交，**必须不同 Owner 独立审批**。审批哈希必须绑定来源、成本以及逐项金额和依据，变动则 STALE，旧分摊不可以用于 V61 利润。
- 多 SKU 订单计入 V61 必须所有事件都获独立审批、分类与订单合计两层金额守恒、退款/调整阻塞解除。不得部分计入、重复计单、将财务来源完整度、银行到账或税务利润标为验证完成。
- Session Owner+CSRF+租户与 Marketplace 复合约束/no-store，审计只追加。Flyway 迁移号 V60 不等于版本里程碑 V60（银行人工来源），代码仍为 Java25/Gradle/Spring Boot4.1.2、React19、PG18 和可选 Redis8，无 Amazon 远程写入。

## 2026-11-01 · V63 退款成本来源复核防误判

- 详见 docs/finance/refund-recovery-v63.md；Flyway V61 只是人工材料两人复核与审计。V34 数量 + V35 原币 PRODUCT_REFUND 金额 + 下单日有效 SKU 历史成本必须完整守恒。
- RESTOCKED_CLAIMED 仅是 MANUAL_UNVERIFIED 的潜在成本回转敏感度，并非仓库已验收入库。actualCogsReversal=null、inventoryReceiptVerified=false、ledgerPosted=false；V61/V62 退款利润阻塞仍保留。
- 双人审批人不等于申请人也不能是当前 V34/V35 的编制人；任一金额、数量、处分、成本或分类来源变化后证据 SHA256 作废，重新审核。Session Owner/CSRF/no-store，租户 Marketplace 复合 FK，行锁与可回溯审计。
- 不自动修改 Amazon、银行流水、库存账或正式会计账。正式 COGS 回转必须有独立可信退货收货/处置凭证及 G1/G2 财务口径批准。

## 2026-11-01 · V64 整期持久报表/原币贡献

- `docs/finance/period-profit-report-v64.md` 为 V64 规则；Flyway V62，当前技术栈 Java25、Gradle、Spring Boot4.1.2、React19、PG18。报表由 Owner 显式创建，固定 1–92 天 UTC 日期、最多 5000 LIVE 订单、最多 1000 distinct SKU、Marketplace 原币隔离；超限禁止默默截断。
- 创建时以 REPEATABLE_READ 固定候选订单 UUID/次序，后续每批最多10单、各批独立 REPEATABLE_READ、原子提交，`SELECT FOR UPDATE SKIP LOCKED` 防并发双写。重启自动回滚未提交批，运行状态仅 READY/RETRY/PAUSED/SUCCEEDED，失败有限五次、安全错误码+审计。调度由 MERCHIVRA_PROFIT_REPORT_SCHEDULER_ENABLED 选择，默认 OFF；不开启时 Owner 手动逐批处理。
- 不能声称全球同一时间点财务快照：`cohortFrozen=true` 只代表候选订单集合冻结，`globalSourceSnapshot=false`，每单 capturedAt/提取摘要可追溯但上游若后改不能证明仍是最新；新来源应发起新的 requestKey 报表版本。
- 每单都复用 V61/V62 已审来源与成本计算，V63 退款成本仍未可信回转，故 blocked 订单 signed金额 NULL；只有 ELIGIBLE 金额进 SKU 和店铺贡献。Ads MANUAL_UNVERIFIED 独立展示不扣减，Finances/Reports 不重复计算。所有结果 `financialSourceComplete=false`、`bankVerified=false`、`ledgerPosted=false`。
- 汇总必须仅使用持久冻结行；正式导出只能 SUCCEEDED，最多5000单/1000 SKU 固定表头并防 CSV formula injection。Owner Session + Marketplace + CSRF + no-store；不加真实银行/Amazon 出站或 GL posting。

## 2026-11-01 · 成熟工具优先、不得重复造轮子（维护者明确要求）

- **默认选择**：先调查当前仓库、官方 SDK、成熟维护库是否已有正确实现，评估许可证、社区维护、CVE、2026 版本兼容性、资源开销、升级成本和自动化测试支持；满足要求时优先复用，而非自己写另一套通用 HTTP 客户端、DTO/Query Hook 生成器、JSON Schema、鉴权框架、调度框架或底层基础设施。对已有可用成熟工具，原则上不批准无明确理由的平行手写实现。
- **工程次序**：已有可靠工具 > 框架原语 > 少量受控适配层 > 仅在成熟方案确实不适用时自行实现。不能因新潮引入微服务、Kafka、无业务需求的 Redis、ORM 或多套同类依赖；实际兼容和 CI 优先于追新版本。
- **V64.1 API 客户端**：Spring Boot 4.1.x 使用兼容的 springdoc-openapi 3.2.x 从真实 Controller 导出/检查契约；React19/TypeScript6 使用 Orval 8.x 生成请求类型和 TanStack Query 5 Hooks。统一 Session Cookie、CSRF、no-store 和错误处理；关键财务/库存/权限响应继续由 Zod4 验证运行时不变量。docs/api/openapi.json 仍为 Proposed 设计稿，不能直接作为已实现 API 契约，更不可在生产环境未经认证公开 OpenAPI/Swagger。
- **验收门槛**：必须在 Node24 和真实 npm registry 下安装 Orval、更新并提交 apps/web/package-lock.json，经 npm ci + TypeScript + Vitest + Vite 构建；生成代码与真实 OpenAPI 漂移在 CI 检查；测试 Session、CSRF、错误码、no-store、租户和财务来源数据的安全门禁。以上未完成，不得声称“Orval 已接入”，也不能只改 package.json 而破坏锁文件。
- **适当保留领域代码**：Amazon SP-API 必须优先官方 Java SDK；本地认证复用 Spring Security；事务和锁复用 Spring JDBC、PostgreSQL18 与 Flyway。SKU 费用分配、来源留痕、Maker/Checker、库存处置属于领域特殊逻辑，不盲目以通用库替代，不得降低数据守恒、审核、身份和财务验证要求。
- **审查新增自研代码**：说明已有成熟替代、为何不适用、要承担哪些维护成本和安全负例、将来可替换路径。先清理重复的 fetch/类型/Hook/重试通用层，再开发 V65 后续模块；不要为了统一而一次性破坏现有接口。

## 2026-11-01 · V64.1 Orval/springdoc 已实际使用

- springdoc-openapi 3.2.0 从真实 Spring Boot 4.1.2 Controller 导出 V61/V64 当前财务 API；仅显式 MERCHIVRA_CONTRACT_EXPORT_ENABLED=true + 127.0.0.1/::1 允许访问，默认关闭；未安装 Swagger UI。准确契约在 apps/web/openapi/finance-profits.json，旧 docs/api/openapi.json 仍为 G2 设计草案。
- Orval 8.44.0 已实际从 npm registry 安装、锁定并生成 apps/web/src/generated/finance-profits.ts 和 models/Query Hooks。V61/V64 请求已调用生成方法。GET 生成 Query、POST 生成 Mutation，不手写重复 fetch、DTO 和 Hooks。
- 安全 mutator 采用 /api/v1/、same-origin Session、写请求 CSRF、no-store、统一错误、{data,status,headers}；财务 Zod 仍守住金额 NULL、审核和未验证状态。
- CI npm run api:check 检测生成漂移含未跟踪文件，PG18 实际导出与提交契约逐字节对比；一次性可写 main 的初始化工作流已移除。后续接口按模块渐进迁移，见 docs/architecture/mature-tool-first-api-client-2026-11-01.md。

## V78 · 采购/FBA 异常进入现有运营工作台（2026-11-02）

- 复用 V10/V67/V69 `ops_finding`、站点级规则开关及审计、租户锁和可选 Spring Scheduler，不另造异常队列、通知引擎或表。
- V77 证据指纹变化映射为 `FBA_PO_EVIDENCE_CHANGED`，人工处理期限已过映射为 `FBA_PO_EXCEPTION_OVERDUE`；只读判定，不能自动 RESOLVE / 修改 PO、ASN、入仓、库存，也不能把 Ops DONE 当作人工已复核。
- 规则可按站点独立暂停，默认定时巡检关闭。仅观察最多 100 张货件/500 行的证据视窗，旧窗口外的待办不能被假清除；截断时保留既有信号。
- V78 `/workbench/metrics` 是全部**已持久化人工处理单**的本地时效，缺失 RESOLVED 样本时均值为 NULL，绝不是 Amazon SLA/平台验收。详见 [INV-078](docs/inventory/fba-purchase-ops-escalation-v78.md)。

## V79 · 本地异常 Owner 分派与源记录跳转（2026-11-02）

- 仅扩展 V77 `fba_purchase_exception_batch` 原子批次账本（Flyway V76），不新建消息队列或自动化购买。批量分派最多 50 行，同一事务校验有效 Owner、来源指纹、CAS 版本及所属 Marketplace，逐行保留 `ASSIGN` 审计；跨动作/负载变更复用 requestKey 均不得混淆。
- 不以 ASSIGN 代替人工 ACKNOWLEDGE，也不以 V78 Ops DONE 代替源单 RESOLVE；批量操作不改动 PO、ASN、入仓或 FBA 库存。已处理的案件不允许直接重分派。
- Owner 负载汇总全部持久化的本地处理单，列出未分派以及离职/失效负责人案件，不以 V76 限量窗口代表所有业务事实；不把员工分派时长冒充 Amazon 平台 SLA。
- Ops→FBA 跨模块焦点只利用已有 V78 租户级任务源键，须在当前 Session 允许的 Marketplace 中选择对应本地记录；超出源窗口要明示。详见 [INV-079](docs/inventory/fba-purchase-assignment-v79.md)。

## V80 · 跨模块异常处理建议只读纪律（2026-11-02）

- V80 复用 V10/V69 `ops_finding` 分类、V77 本地证据处理、V78 本地时效和 V79 Owner 负载，**不新增表、队列、写入动作或定时任务**。应用于 Operations 的 V80 站点级只读看板，源数据仍来自服务端 Session 及对应 Marketplace。
- P0 来源变更/人工逾期，P1 独立身份审查/本地差异复核/临期/HIGH，P2 尚未指派或 WARNING，P3 常规；属于固定的可解释处理建议，绝不宣称平台风险预测或 Amazon SLA。历史已 RESOLVED 但来源变更必须重新关注。
- 优先级仅基于 V76 最近 100 货件/500 行、最多显示 30 条；必须展示截断信息。进度/负载来自全部持久化处理单，不能混淆口径；无人可分派返回空建议，不自动分派、ACKNOWLEDGE 或 RESOLVE。
- Ops 分类反映最近一次扫描，不在 GET 中暗中运行任务。读服务不得更改 PO、ASN、FBA 入仓、库存、财务和 Amazon Seller 数据。详见 [OPS-080](docs/operations/exception-insights-v80.md)。

## V81 · 异常审计趋势与复发真实性纪律（2026-11-02）

- 直接复用 V77 不可变审计日志，按 UTC 30 个自然日（包含今天）严格区别“新建处理单”“RESOLVE/REOPEN 动作次数”“受重开影响的不同处理单数”和“人工动作留下的来源指纹更新”；未发生人工审计的实时来源变化不算历史复发。
- 按 `actor_user_id` 展示人工动作，不把当前 assignee 或批量分配者冒充实际执行人。平均首次完成时长只算窗口内首次 RESOLVE 相对本地 case.created_at 的时间，无样本为 NULL；不得将它宣传为员工绩效排名、Amazon SLA 或库存入仓证明。
- 从当前 Session 校验 tenant/marketplace，GET 不执行巡检、外部通知或写操作。最多 100 名操作人、20 条复发记录，明确截断；日期零填充只针对确实成功读取的审计。详见 [OPS-081](docs/operations/exception-history-v81.md)。

## V82 · 优先级配置、说明模板与人工确认批量完成（2026-11-02）

- V82 只允许对 V80 的九种固定原因**提高或保持**建议优先级；来源变化/超期 P0、独立身份或差异复核/临期/HIGH P1 的安全下限不可降级。所有设置必须以租户/Marketplace/版本锁定并追加审计，不能复用 V69 Ops 扫描规则开关绕过 V77 处理门槛。
- 说明模板只能填入人工可编辑理由，**不能虚构独立复核完成、Amazon 签收或 FBA 库存入账**。每次提交必须有实际人工核对和长度 10–300 的可审计理由。
- 批量 RESOLVE 必须使用 V77 既有 `apply(RESOLVE)`、`fba_purchase_exception_batch` 幂等账本和审计，HTTP 需要 Owner Session、CSRF、显式 `confirmed=true`、1–50 条含 CAS 版本与指纹的完整选择；一行失败整批回滚。不能凭 UI 勾选绕过 V65/V75 独立审核条件。详见 [OPS-082](docs/operations/exception-guidance-v82.md)。

## V83 · 本地批量处理前置预检与诊断纪律（2026-11-02）

- 批量 RESOLVE 的逐行预检是只读、非锁定的**瞬时快照**，不能提供授权、批准或取代 V82 事务中的全部校验。UI 预检必须绑定当前 Marketplace、选择行、版本和来源指纹；变化就不能继续提交。最终提交仍须 Owner Session、CSRF、`confirmed=true`、V65/V75 业务门槛及幂等账本。
- 返回固定可解释的 `reasonCode`，包括来源指纹变动、乐观版本冲突、未确认、负责人缺失及独立证据复核不足。越界行统一 `OUT_OF_SCOPE`，不得泄露其他租户/站点信息。1–50 行、去重、版本+指纹校验必须由后端独立负责。
- 最近批次活动只来源于已有 V77/V79/V82 幂等账本；没有批次与逐行审计的实体关联时**绝不能猜测**，应指向已有的逐行 History。保持不接 Amazon、PO/ASN/库存写入。见 [OPS-083](docs/operations/fba-batch-preflight-v83.md)。

## V84 · Owner 局部最小权限与可验证批次审计（2026-11-02）

- 异常工作台权限为 **Owner 租户/Marketplace 局部限制**，不能把此授权表当成新的全局角色或 OPERATOR_READ 提权机制。未设置时继承原 OWNER 全权限；仅权限编辑者可限制其他有效 Owner，自己不能编辑自身权限，必须在 Marketplace 行锁内重新确认 MANAGE，写入版本 CAS + 不可变授权审计。
- VIEW / ASSIGN / REVIEW / RESOLVE / CONFIGURE / MANAGE 各端点服务端检查独立能力；UI 禁用只是便利。不得将 V80/V81 数据读取或 V82 规则修改变成受限 Owner 的绕过路径。CSRF、Session Tenant、来源指纹、独立复核不变。
- 原 V77/V79/V82 批次账本需先于同一事务内逐行人工操作写入，新增 audit.batch_id 外键精确关联；任意失败全部回滚，旧历史批次 NULL 不能补造。新增 batchDetail 明确 lineageComplete=false，不能猜测日志关联。见 [SEC-084](docs/security/fba-exception-capabilities-v84.md)。

## V85 · Operations 中采购/FBA 人工异常的跨模块权限边界（2026-11-02）

- V78 Ops 中的 `FBA_PO_EVIDENCE_CHANGED` / `FBA_PO_EXCEPTION_OVERDUE` 是 V77 人工来源异常的投影：VIEW 禁止读取、ASSIGN 禁止认领、REVIEW 禁止暂缓/重开、RESOLVE 禁止 DONE、CONFIGURE 禁止修改对应 Ops 规则；其他 Ops 业务类别不得错误继承这组局部权限。
- 不可绕过 `TenantAccessService`、活跃 Owner 成员资格及 Session/CSRF。**任何人工异常操作必须同时具有 VIEW**，撤销 VIEW 后即使残存 REVIEW/RESOLVE 等布尔位也不可凭猜到的行 ID 盲操作。
- Ops `/findings` 必须在 SQL `LIMIT 100` 前排除用户无 VIEW 的站点 FBA_PO 异常，`/summary` 从全量持久化数据按同一 ACL 聚合；规则和审计读取同样不能泄露两类来源。全租户手动 `/scan` 只要任一站点明确拒绝 VIEW 或 CONFIGURE 则全部拒绝，不能静默部分扫描；系统定时扫描仍独立运行。
- V76 原采购/FBA 差异证据 `/purchase-reconciliation` 亦需 VIEW。Ops DONE 不是原本地 case RESOLVED，更不是 Amazon 入仓认证。详见 [SEC-085](docs/security/fba-ops-cross-module-v85.md)。

## V86 · P0 核心订单分页与真实/模拟验收区分（2026-11-03）

- 产品/技术文档需求要和实际 `main` 代码、CI、模拟来源及真实外部授权四个证据层级分别标记，不能用旧 Roadmap 中“尚未实现”覆盖已存在的 V20–V85 代码，也不能把 Mock 绿色回归写成 Amazon 生产同步。见 [TEST-086](docs/testing/core-coverage-audit-v86.md)。
- 订单的全量读取必须使用 Owner/OPERATOR_READ 在服务端解析的 tenant/Marketplace；V86 新 `GET /api/v1/seller-marketplaces/{marketplaceId}/orders/page` 限制单页 1–100 行，按来源时间 NULLS LAST 和 UUID 排序，游标与站点+搜索前缀绑定；仅作位置提示，不作权限、全局快照或财务导出凭证。搜索输入限制长度、字符以及 SQL 通配符字面量转义；保留旧 `/orders?limit=...` 响应兼容。
- React 核心订单页的服务器搜索、上一页/下一页不能暗示完整 Live 库存或 Amazon 已验证，切换站点、前缀时要清理旧游标和明细；`NOT_OBSERVED` 订单项和财务 `NOT_OBSERVED` 不得等同业务金额零。
- 下一阶段优先补 Listing 和 FBA 的服务端全库分页，其后整合可解释 Dashboard，避免继续重复开发异常规则或引入新队列。真实 Amazon OAuth/SP-API 依照用户约定，核心功能完成后再由用户在本地手工授权联调。

## V87 · Listing 全库检索、版本化映射与有效作用域纪律（2026-11-03）

- 核心 Listing 页不是唯一 50 行：必须使用有界的 `/listings/page`，服务器校验 Session tenant、被授权 Marketplace、枚举字段（SELLER_SKU/ASIN）、映射状态、前缀长度和游标关联。保留旧 `/listings?limit=` 兼容，不要把其他表的临时过滤视为全库搜索。
- `listing_sku_mapping` 是映射版本唯一事实：`activeMappingVersion` 与 `internalSku` 只在 ACTIVE/valid_to NULL 时返回；`latestMappingVersion` 可包含已经撤销或有冲突的历史版本。即使是 CONFLICT 行也必须保留 Listing 来源，不能猜测已映射 SKU。
- Seller SKU/ASIN 前缀在 SQL 绑定参数中按字面量转义（包含 %, _, !），最多 100 条一页，(seller_sku,id) keyset 游标；不能接受客户端 tenantId、不允许把游标当授权或冻结快照。复杂 SKU 的原字节必须安全编码，跨站点/字段/状态/搜索复用游标返回 400。
- React 搜索提交与筛选页游标绑定、页面切换重置待操作 Mapping 选择、人工映射成功必须失效 `listings-v87` Query 缓存。分页不是财务完整集，真实 Amazon/Ads 仍保持关闭，待最后本地测试。详见 [CAT-087](docs/catalog/listing-search-pagination-v87.md)。

## V88 · 最新 FBA SKU 来源与有界全库分页（2026-11-03）

- 核心 FBA 观测查询必须复用原 fba_inventory_observation 的 latest 每 SKU 来源规则（observed_at DESC NULLS LAST、ingested_at DESC、id DESC），不能直接将所有观测数求和，也不能按入库时间把未知来源观测当作更新的远端库存。
- 库存质量和来源过滤一定**位于每 SKU 最新观测选择之后**。若最新 UNKNOWN，即使历史 OBSERVED 非零或零也不可从历史“补齐”结果；若最新 UNVERIFIED，旧 MOCK/LIVE 不能替代。fulfillable/reserved NULL 原样显示未知，真实观测 0 原样显示 0，STALE 保留质量，observedAt 与 ingestedAt 不互换。
- V88 GET /api/v1/seller-marketplaces/{marketplaceId}/inventory/fba/page 由服务端 Session tenant 和精确 Seller Account OWNER/OPERATOR_READ 授权；query 的 quality/origin 为封闭枚举，prefix 80 字符以内且 SQL 特殊字符按字面量转义。Keyset 复用 V87 多行 SKU Base64url 格式但包含类别 FBA_INVENTORY、Marketplace、质量、来源、前缀；游标不能当授权、快照或金额。
- 保留旧 /inventory/fba?limit API，新增 Flyway V80 最近来源排序和低开销表达式前缀索引。FBA 同步成功失效 inventory-v88 TanStack Query 缓存；切站点/从 Ops 异常跳转/更换筛选时重置游标。继续不引入新缓存系统或不必要的 Redis/Elastic。
- 模拟验收见 [FBA-088](docs/inventory/fba-inventory-observation-pages-v88.md)：PostgreSQL 18、多观测 64 SKU、50+14 keyset、后过滤、零/未知/STALE、时间优先级、Origin、Cursor 字符与权限边界。真实 Amazon OAuth/SP-API 仍保持关闭，留给用户最终本地联调。

## V89 · 经营总览采用真实 PostgreSQL 来源、不可伪造净利润（2026-11-04）

- Owner 左侧「经营总览」必须使用 `/api/v1/business-overview`，不再用 Demo Gateway 的预估销售数据冒充经营指标；该全租户 API 严格使用 Session 当前 Owner tenant，不能扩大 `OPERATOR_READ` 到全租户财务摘要。超过 100 个站点直接明确失败而不能静默截断。
- 逐 Marketplace 展示原始币种、Listing/Orders 全表来源分布、FBA **每 SKU 最新观测**的质量/来源计数；FBA 必须按 `observed_at DESC NULLS LAST,ingested_at DESC,id DESC` 先去重再计数。只计算实际已观测为零的 SKU 计数，不把 UNKNOWN 或 STALE 当零，不对多币种、不可信来源的金额求和。
- V64 持久来源贡献报告仍是**唯一**可显示的原币财务贡献来源：仅最新报告 SUCCEEDED 且有 ELIGIBLE 行时用历史已持久化 contribution；最新 READY/RETRY/PAUSED 时贡献必须 NULL，不可以回退之前成功报表的金额冒充当前结果。金融/银行/会计完整性固定 false，不能称净利润或全局同点快照。
- `/api/v1/business-overview/export.csv` 是**当前数据质量清单**；必须复用 `ProfitReportCsv.encode` 的 CSV 公式注入防护和 no-store。真正已冻结的订单/SKU CSV 仍调用 V64 的 Orval `getExportProfitReportCsvUrl`，不可重新计算/拼接财务结果。
- V89 HTTP PG18 测试在双币种站点验证完整计数、Owner-only 401/403、只读员工仅可查看自己的 Seller Account，模拟 V64 SUCCEEDED → 新任务 READY 金额回空、公式注入防护与无 Amazon 写入。详见 [DASH-089](docs/analytics/business-source-quality-v89.md)。正式真实 Seller/Ads 授权等全部核心功能收尾后由用户在本地联调。

## V90 · 跨模块实际选择器必须支持服务端全库检索（2026-11-04）

- 不要把 V86/V87 已实现的服务端分页停留在核心浏览页：任何**会触发业务动作**的 SKU/订单选择器（补货策略、采购、财务人工关联、成本/贡献审核等）都必须核查是否仍使用旧 `fetchCoreListings/Orders` 首 50 行。统一使用 `CoreMarketplacePicker`，复用既有 V86/V87 bounded Keyset API、Zod 类型及 TanStack Query，不建立第二套商品索引。
- 全库服务端检索只支持前缀并绑定当前 Session Marketplace。变更前缀/页面/店铺/审核对象后必须清理之前选择；不能将其他页旧 UUID 或 SKU 不经重新核对用于当前人工业务写入。已存策略点击编辑时应要求重新从服务器当前页选中对应 SKU，不把历史“来源行”自动视为已核对可操作目标。
- 异步请求失败、空查询、尚未加载必须分开呈现；不能把前 50 条当全库，也不能用 `getCoreOrderItems` 或 demo 快照推断所有订单。手动 Finance 匹配仍使用原 Owner-only `FinanceEventService.review` 同站点数据库校验，人工来源不能自动升级 LIVE、银行确认或 V64 利润。
- 补货策略仍然只是人工建议和本地 DRAFT 的起点，必须保留 V71/V74 策略版本、FBA 观测、报价与双人审批门禁，不得自动创建已批准 PO、将本地仓储写成 Amazon FBA 或开启 SP-API。跨 Marketplace 切换需重置相关状态。
- V90 用 64 条 Listing/订单的 PG18 实际 HTTP 回归确认第 64 条从浏览页进入 V70 本地补货策略与 V21 财务人工审核（不同站点订单 404），同时确认无自动 PO/FBA 改写；详见 [CORE-090](docs/testing/cross-module-business-entrypoints-v90.md)。

## V91 · FBA 货件与内部 SKU 写入入口的全量作用域（2026-11-04）

- 不要因为核心 Seller Listing 已经有 V87 分页，就误认为所有页面能选择第 51+ 个 SKU。V91 的 FbaInboundPanel 每条拟建本地货件行必须使用 V90 复用选择器，通过当前 Session Marketplace 的 V87 全库前缀分页选择；更换搜索/页码要清空旧值，创建时保留原 FbaInboundService 1..100 行、Listing 同站点、SKU 唯一和数量校验。此处只能生成本地 DRAFT，不代表 Amazon 在途/入仓/可售。
- 本租户 `product_sku` 查询必须复用 Owner-only `/api/v1/catalog/skus?prefix&after&limit=100`；`after` 仅接受真实最后一行 internalSku，满 100 只能表示后续**可能**还有记录，不可宣称已完成全库查询或把 SKU UUID 当游标。新的 `InternalSkuPicker` 是映射、历史成本、采购、本地仓库操作的统一 UI 入口，ACTIVE 才允许执行正向写入；归档记录须重新按主数据 API 恢复。请求失败/翻页/搜索/目标消失必须取消旧选择。
- SKU 成本不能默认选择第一页首商品；库存操作仍需服务端 UUID + 本地 `expectedOnHand` + Reason、PO 仍需 DRAFT→提交→异人审批及收货/质检的真实仓储边界。共享选择器只是修复第 100 条以上商品无法在 UI 被操作，不授权自动财务、入仓、下单或 Amazon 写入。
- 别忘了 `SupplierPurchasePlanningPanel` 和 `ReleasedLotStockPanel` 仍依赖旧部分 SKU 列表，`fba_inbound_shipment` 最近 100 条也不是完整历史；属于 V92 继续实查的问题，不可伪称 V91 已消除所有 ERP 记录容量限制。验收见 [CORE-091](docs/testing/fba-catalog-purchase-selection-v91.md)。

## V92 · 本地 FBA 货件历史/供应商规则的全量检索（2026-11-04）

- 原 V65 `GET /fba/marketplaces/{mp}/shipments` 固定 100 行只是兼容接口，绝不能作为 FBA 历史全量或人工可处理货件的集合。操作入口使用 V92 `GET /fba/marketplaces/{mp}/shipments/page`，依原 `fba_inbound_shipment` `created_at DESC,id DESC` 做 1..100 页 keyset；仅当前 Session Owner tenant/Marketplace 的本地记录；前缀/状态变更重设游标、选择及错误状态，游标不是授权或快照。货件只属于 Mock/手工未核验，不能记为 Amazon 入仓或库存。
- V92 接口源头必须为实际 Spring MVC OpenAPI，`normalize-fba-openapi.mjs` 维护完整操作集并检查，Orval 既有传输 `merchivraFetch` 管理同源 Cookie、CSRF 和 no-store；不得增加第二套 Bearer 授权客户端。只增 Flyway V81 本地历史索引，不复制业务表。
- 供应商规则界面必须通过 V91 `InternalSkuPicker` 而不是最初的 `fetchSkus()` 前 100 条识别任意 ACTIVE 商品；编辑规则时当前服务端已核验 SKU UUID 必须等于旧规则 SKU UUID，以防无意新建/覆盖其他 SKU；后端 Owner + ACTIVE 供应商/商品 + MOQ/包装倍数、原币继续原样校验。不因人工规则更新自动生成采购、财务金额、仓储流水或 Amazon API。
- 供应商规则历史 `LIMIT 500`、计划/建议容量以及部分库位/批次查询尚待真实审计；任何局部页面都不能标“全量库存”或总流水。完整 V92 PostgreSQL18 HTTP 验收见 [CORE-092](docs/testing/fba-shipment-history-supplier-rules-v92.md)。Seller/Ads 授权继续保持关闭，最终由用户本地验证。

## V99 · Next.js 16 App Router 迁移阶段（2026-11-07）

- 用户明确选择 **A+B**（深蓝效率布局 + 高级简洁质感），并要求 **直接实现代码，不把 Penpot/Figma 原型设为强制前置项**。采用已审核 SVG 设计图与 DESIGN.md 中的 tokens；业务页面与表格的真实交互优先，不另造不可操作的装饰性页面。
- **Next.js 16.4.5 是迁移目标，当前仍在双构建验证期**：`apps/web/app`、`next.config.ts`、`tsconfig.next.json` 和 `npm run *:next` 均为真实 App Router；旧 Vite/Nginx 是回退路径。**不要错误宣称已切生产或已删除 Vite**，必须先通过 Next 登录/CSRF/API、动态导航、Chrome 及 HTTPS ingress 安全核查。
- 复用原有 Spring Boot 4 / Java 25 / Gradle / PostgreSQL 18、Orval、TanStack Query、Zod；完整 ERP 在 Node Next + Spring Boot 后端上运行，静态 GitHub Pages 只能提供虚构演示，不得发布有真实 Session/OAuth/财务写入能力的应用。
- 下一步优先把 /orders、/purchasing、/core FBA、/ops、/finance 的真实页面按 A+B token 逐页视觉对照，再把原单页面内部导航迁移为可回退的 App Router 语义路由。真实 Amazon OAuth/远程写入继续默认禁用，待维护者本地联调。

## V100 routing / data provenance enforcement (2026-11-07)

- In Next.js: /orders, /catalog, /inventory MUST render the real
  tenant-scoped CoreWorkspace, never the independent Vite Mock Gateway.
  All real Seller OAuth, FBA writes, financial posting remain disabled.
- Keep WORKBENCH_VIEWS in src/lib/workbench-routes.ts as the source of truth
  for Server Component 404 validation, client URLs and browser history.
  Use next/navigation router.push/replace, NOT history.pushState hacks.
- Preserve Root/session and backend authorization; unauthorized
  deep links must not reveal restricted modules or nonempty data.
- A+B styles in app/design-system.css affect only the Next preview.
  Continue to test separate Vite and public Pages builds.
- Before production replacement, require Next route, Chromium mobile,
  real backend cookies+CSRF and OAuth reverse proxy safety validation.

## V101 Next.js real proxy security test (2026-11-07)

- In `pg18-migrations`, keep `scripts/smoke_next_session_csrf_v101.py` wired
  to the actual disposable Spring Boot 4 + PostgreSQL 18 backend, never a
  mocked auth API; run against Next's same-origin `/api/v1/*` rewrite.
- Negative CSRF and anonymous/isolated Session checks must pass alongside
  positive login/logout, HttpOnly/SameSite cookies and safe no-store reads.
  Never print disposable credentials, tokens or cookie values in CI logs.
- This does NOT replace production HTTPS/forwarded-header, OAuth logging,
  browser pixel/interaction, operator-permission or rollback checks.
  Retain the Vite fallback and synthetic-only GitHub Pages deployment.

## V103 Next UI component rule (2026-11-07)

- The A+B sidebar/header/dense-table ERP layout stays. Replace hand-rolled
  **complex interaction** (modal drawers, menus, dialogs, tabs with keyboard
  behavior) with stable Base UI primitives and familiar Lucide React icons;
  prefer native HTML controls for ordinary inputs, links and buttons.
- New Next-only shell: `src/components/NextWorkbenchNavigation.tsx`.
  Preserve semantic deep links, owner visibility and Base UI focus/Escape.
  Spring backend still owns role and tenant authorization.
- Keep Next-only A+B CSS in `app/design-system.css`. Never bulk replace the
  Vite fallback or add Tailwind merely to reproduce existing legacy CSS.
- Avoid reimplementing data-table internals. Evaluate TanStack Table for
  modules that actually need advanced sorting/column management; preserve
  backend cursor pagination, Source trust and UNKNOWN-versus-zero semantics.

## V104 procurement confirmation and write-safety rule

- For user-confirmed inventory, purchase, supplier dispute and claim
  mutations, use `src/components/useConfirmAction.tsx` (Base UI
  `AlertDialog`) instead of `window.confirm`. This component handles focus,
  accessible title/description, cancellation and destructive-action styling.
- Snapshot form values and idempotency keys before opening the dialog. No
  mutation or remote side effect may occur on open, Escape or cancel.
  Do not mark supplier approvals as external payments, Amazon receipts or
  automatically sent claims.
- Keep Spring RBAC, CSRF, optimistic stock balance checks, independent-owner
  approvals and audit logs authoritative. Confirmation is only a user
  interaction gate, never an authorization boundary.
- For every destructive-action UI migration, add at least one synthetic
  browser regression proving cancellation sends zero writes and explicit
  confirmation sends exactly one request.

## V105 server-paged grid and Base UI filter rules

- For operational lists with meaningful column requirements, use stable
  TanStack Table v8 with `getRowId` from the persisted UUID and explicit
  `manualPagination/manualSorting/manualFiltering` when the backend owns
  filtering and priority. The table displays only the already-returned
  authorized server page; never invent a global count or client-side
  multi-page sort.
- For small predefined interactive filters, use Base UI Select; when
  changed, reset opaque server cursor state and issue a new server query.
  A real date range UI requires a real backend date filter, never
  post-filtering the first page.
- Preserve MOCK/UNVERIFIED/LIVE provenance, FBA grants and action buttons
  when customizing optional columns. These are security/trust constraints,
  not mere table display defaults.
- Add a Chromium synthetic test proving filter parameters reach the local
  server, optional columns do not hide provenance, and mobile viewports do
  not overflow.

## V106 source-preserving finance table and cursor boundaries

- All finance event tables must retain original signed
  `currency + amount`, event identity, `MANUAL_UNVERIFIED/LIVE` source,
  review state and explicit human review action. Optional columns may
  hide secondary fields only, never audit or provenance essentials.
- Finance Marketplace filtering is an authenticated server-scope choice,
  not client-side tenant authorization. On changing the Marketplace,
  reset finance cursor and order matching state; never reuse a previous
  site's ID or cursor in the new site's query.
- The existing finance GET returns up to 50 events as an array with
  `beforeAt/beforeId`. Exactly 50 means *potentially* more pages;
  it is not a server-issued `hasMore`. Offer a next-page attempt and
  maintain an opaque previous-page cursor stack rather than pretending
  the entire result set is loaded.
- Demonstrate pagination across at least 51 synthetic rows plus another
  authorized Marketplace in Chromium before merging table changes.
