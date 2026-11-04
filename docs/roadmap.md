# PLAN-001 · 文档先行与分阶段交付路线

> **最新实现阶段：V92（2026-11-04）**。V91 合并后继续审计发现：本地 FBA 货件历史界面被旧 `LIMIT 100` 截断，供应商采购规则只能选择传入的前 100 个内部 SKU。V92 复用原 V63 本地货件状态机增加 V81 索引和 OpenAPI/Orval 契约下的全历史 keyset 检索，V91 `InternalSkuPicker` 复用到供应商规则编辑；用 125 个本地货件 50+50+25 与第 130 个内部 SKU 的 PG18 HTTP 验收确认真正可操作。详见 [CORE-092](testing/fba-shipment-history-supplier-rules-v92.md)。所有 Mock 结果仍不代表 Amazon 已授权或真实 FBA 入仓。

### 当前 P0 清单及后续
1. **V92**：完整本地货件历史查找/选择/人工入仓入口，供应商 SKU 采购规则的第 100 条以上商品创建与安全编辑，拒绝跨站点旧游标。
2. **V93 候选**：采购供应商规则 `LIMIT 500`、本地采购计划/建议、库位和批次列表的服务器分页及历史审计完整性，优先修复会导致操作或核算遗漏的真实断点；沿用现有领域表和现有成熟技术。
3. **最终真实联调**：核心功能全部完成后，用户本地启用 Seller/Ads OAuth/SP-API，逐项验证权限、分页、地区、限流、撤销、结算、币种和仓库差异；无模拟自动审批或远程库存写入。


> **最新开发阶段：V91（2026-11-04）**。V90 已合并首 50 条补货/财务业务操作选择器问题；V91 继续修复 FBA 草稿 SKU 仍限前 50 条和内部 SKU/映射/成本/采购仍限前 100 条的业务入口。直接复用 V86/V87 全库 Listing/订单检索、既有 Owner-only `/catalog/skus?prefix&after`、V65 本地货件、V20 采购与历史成本服务，不新增 DB 表/缓存或依赖。独立验收用 V87 第 64 条 Listing 与 V91 第 130 个内部 SKU 演练映射、原币成本、FBA 本地 DRAFT 和采购 DRAFT。详见 [CORE-091](testing/fba-catalog-purchase-selection-v91.md)。最终 CI 以本 PR GitHub Actions 为准；Mock 测试不能替代 Amazon SP-API 真实联调。

### 当前实际进度与后续重点

1. **V91 当前 P0**：本地 FBA 货件多行 Listing 完整检索；内部 SKU 列表分页、映射、成本、采购和本地库存选择全库可用，并持续保留每次写入的服务端 Owner/租户与业务审批门禁。
2. **V92 P0**：仍依赖局部内部 SKU 集合的供应商采购计划、货位/批次和完整 FBA 货件历史等实际业务入口，按真实后端容量与审计模型逐一补齐，不重复造库存/财务系统。
3. **最终真实 Seller/Ads 联调**：所有核心功能完成后由用户本地启用 OAuth/SP-API，验证地区/角色/429/撤销/分页和银行来源、G1/G2/G3 审批，绝不把 Mock 演练当生产授权或自动批准。


> **最新阶段：V90（2026-11-04）**。V89 已基于全表真实 PostgreSQL 来源质量和 V64 原币贡献完成 Owner 经营总览；V90 审计发现跨模块业务入口仍存在首 50 行限制：补货策略 SKU 选择、财务订单关联及订单成本分析。复用 V86/V87 已成熟的 Session/Marketplace Keyset、React Query 和 Zod，补齐完整服务器检索并通过 V90 64 条 Mock HTTP 联调，不重复建设商品/财务系统。详见 [CORE-090](testing/cross-module-business-entrypoints-v90.md)。真实 Seller/Ads Amazon 授权仍留给用户在全部核心功能完成后本地执行。

### V90 及以后优先级
1. **当前 V90 P0**：跨模块“已入库源数据→可选人工操作目标”完整性，64 条订单/Listing 超过首 50 行仍可生成本地补货策略、进行同站点财务人工审核；切页面/站点/查询必须重验，继续保留人工审批。
2. **后续 P0**：全面扫描其他业务选择器与后端结果的隐式 50/100 行限制，逐项评估补充分页、来源完整度和操作边界；将原有购买/FBA/财务的模拟链路整理为统一验收追踪矩阵。
3. **最后真实联调**：由用户在本地授权 Seller/Ads 并逐项演练 API 分页/429/角色/撤销/多区域、银行口径和 G1/G2/G3 业务签署。模拟通过不等于生产 API 或资金验收。


> **最新实现阶段：V89（2026-11-04）**。V86–V88 已完成订单、Listing、FBA 库存的 PostgreSQL 全量检索/分页及模拟验收。V89 把原独立 Mock Gateway「经营总览」替换成 Owner 当前会话租户的来源质量概览，引用原 V64 已持久化原币贡献、复用安全 CSV 导出，避免跨币种相加/伪造净利润。详情：[DASH-089](analytics/business-source-quality-v89.md)。V89 的最终 CI 结果以 PR 工作流为准；**Mock 数据 ≠ Amazon 真实来源验收 ≠ 生产发布许可**。

### 当前阶段核心优先级

1. **V89 当前 P0**：全部站点逐币种 Listing/订单/FBA 质量数据、有效来源分布与 Owner-only 数据质量 CSV；已有 V64 原币报告作为唯一财务引用。
2. **V90 候选 P0**：跨模块核心闭环演练与数据质量验收包（采购 PO/收货、FBA、订单、退款、财务/Ads、自动化安全动作），优先补缺漏的业务操作流程而非重复实现已有规则。
3. **最终真实接入验收**：由用户在本地逐项授权 Seller/Ads OAuth/SP-API，测试权限/分页/429/撤销/币种、银行/会计口径、G1/G2/G3 签署与生产安全门禁，继续不由 CI 代办真实 Amazon 授权。


> **最新开发阶段：V88（2026-11-03）**。V87 的 Listing Seller SKU/ASIN 全库检索、映射版本与只读员工分页 CI 4 项通过并已合并。V88 解决核心 FBA 库存页原来只读取 50 个 SKU 的缺口，增加 PostgreSQL 全量最新观测检索、来源/质量后过滤和 keyset 分页。详见 [FBA-088](inventory/fba-inventory-observation-pages-v88.md)。Mock 测试不能当作 Amazon 实时库存验收，CI 最终状态以本 PR 为准。

### 核心功能近阶段优先级（覆盖早期过时状态）

1. **V88 当前 P0**：全量 FBA 库存每 Seller SKU 最新观测、UNKNOWN/OBSERVED 0/STALE 溯源与服务端分页、跨源版本筛选。
2. **V89 P0**：跨模块经营视图的来源可信度与安全导出入口，复用 V64 原有整期财务快照和 CSV，不重复造财务系统。
3. **其后**：核心模块全链路一致性和生产前验收包、真正 Seller/Ads SP-API/API 角色与限流/撤销/分页验证（**真实授权最后由用户本地完成**）。


> **最新开发阶段：V87（2026-11-03）**。V86 P0 代码审计和订单服务端游标检索已 CI 四项通过并合并 main；V87 继续补齐原 P0 Listing 仅展示前 50 条的业务缺口，增加 Seller SKU/ASIN 字面量前缀与映射状态筛选、有效/历史版本分离和完整服务端游标分页。详情：[CAT-087](catalog/listing-search-pagination-v87.md)。**代码交付、Mock CI、真实 Seller 授权和生产批准严格分开**；V87 的 CI 状态须以 GitHub Actions 为准。

### 当前下一步核心优先级

1. **V87 当前**：真实存储 Listing 全库搜索、映射证据保持、分页、UI/PG18 假数据回归；旧 Listing API 不破坏。
2. **V88 P0**：FBA 最新可信库存观测全库分页，明确 UNKNOWN / ZERO / STALE 和来源时间。
3. **V89 P0**：经营总览的可解释数据质量和导出入口，复用已存在 V64 来源贡献、CSV，而不是再造财务总账。
4. **最后由 Owner 本地联调**：真实 Seller/Ads OAuth 与批准角色、Orders/Catalog/FBA/Finances 只读 API，撤销、429、分页、地区及银行结算，G1/G2/G3 签署和生产门禁。


> **最新代码阶段：V86（2026-11-03）**。V85 四项离线 CI 已通过并合并；本页 V74 及更早的“当前”均为历史记录，不再是最新进度。V86 [P0 核心闭环审计](testing/core-coverage-audit-v86.md) 从真实代码核对原 P0/P1 FR 完成度，并实际增加全量订单号前缀搜索与 keyset 服务端分页。不能把 Mock CI、真实 Seller 授权、生产发布视为同一状态；V86 CI 结果以 GitHub Actions 为准。

## 当前核心功能后续顺序（覆盖以下历史版本计划）

1. **V86 当前**：核心 P0 订单服务端完整分页 + 功能需求溯源矩阵；现有财务 V64 CSV、采购 V20–V73、库存 V22/V65、同步 V11–V24 不重复开发。
2. **V87 P0**：Listing 全量服务端查询与分页，保留映射版本/冲突和 Seller SKU / ASIN 独立字段。
3. **V88 P0**：FBA 观测的每 SKU 最新快照全库分页，区分 UNKNOWN、OBSERVED 0、STALE 和数据来源。
4. **V89 P0**：跨模块经营视图与导出入口，复用 V64 持久化来源快照和安全 CSV，不擅自混算币种或造完整净利润。
5. **最后**：由 Owner 本地真实 Seller/Ads OAuth、SP-API 角色/429/分页/撤销和银行来源联调；随后正式 G1/G2/G3 与发布安全验收。


> **执行基线（2026-11-01，V61）：** 已有 Java 25 / Spring Boot 4.1.2 / Gradle / React 19 / PostgreSQL 18 代码及实际 CI；V61 最新 [CI #41285312468](https://github.com/kunlong-luo/merchivra/actions/runs/41285312468) 成功。但 CI 不能替代用户本地数据库运行或真实 Amazon/银行接口验收。G1/G2/G3 未正式签署；继续优先补齐核心代码，再由 Owner 本地授权验证。下文 V1–V54 是历史追加记录，其中部分旧「未实现／待 CI」描述已经过时，以本节当前状态为准。

| 阶段 | 目标 | 交付物 | 通过条件 |
|---|---|---|---|
| D0（现在） | 文档基线 | PRD、FR/NFR、业务图、架构图、ERD、同步、集成、安全、UI、测试、运维 | G1/G2 问题有结论 |
| D1 | 文档评审与定稿 | 业务决策、真实站点与利润口径、ADR 接受记录 | 所有 P0 决策无关键 TBD |
| M0 | 工程骨架验证 | Docker、CI、最小 Web/API、鉴权设计 | 可运行 Mock、无公网上线风险 |
| M1 | 账号 + 同步底座 | Seller 授权、Mock、任务与限流 | 安全授权、可重放 |
| M2 | 商品/订单/库存 | 业务 CRUD、数据同步 | 样本业务验证 |
| M3 | 财务/成本/利润 | 费用、成本与估算报表 | 可对账、可追踪 |
| M4 | Beta | 安全、运维、发布 | P0 验收通过、运行手册完成 |

## 2026-11-02 · V74 解释性补货优先级与本地采购核对（当前）
- 只在 V22 新鲜完整 FBA 和 V70 完整 28 天订单项且 MOCK/LIVE 来源一致时展示历史下单速率推导的库存覆盖天数；UNKNOWN、混源、无订单、缺覆盖不推断断货。
- 仅用 V37/V71 明确 Seller SKU 来源展示本地采购草稿/审批未收/本地实收，绝不作为 Amazon FBA 在途或可售；再建采购草稿前刷新既有 PO 并人工确认。Flyway V71 索引以及 V72 纠正既有 V24 LIVE Catalog/FBA 来源约束，详见 [INV-074](inventory/replenishment-priorities-v74.md)。

## 2026-11-02 · V73 供应商履约证据评分与采购人工催办（当前）
- 90 天完整 ASN 本地实际收货 vs 当时已申报 ETA、已检 QC 接受率；两类各至少 3 个样本才给暂定参考分，未观测不变成零。独立展示未结差异。
- 审批后默认 7 日的 ASN 覆盖缺口/全草稿未发货本地人工任务，保留认领/已处理审计并在条件消失时清理，V43 SHIPPED ETA 异常仍用已有队列；默认不后台推送。Flyway V70，详见 [PO-073](purchasing/supplier-performance-v73.md)。

## 2026-11-02 · V72 采购交付与异常证据看板（当前）
- 只读聚合 V20 已审批 PO/实际收货、V43 供应商 ASN 和已有 ETA 到期待办、V45 未结差异、V46 质检放行证据、V37/V71 补货草稿来源。没有 ASN/只有 DRAFT ASN 不代表发货或逾期；已收货不代表 QC 放行/FBA。
- Owner 在既有采购页按行查看剩余量、申报未收量、ETA 区间、QC 和差异，能打开既有 ASN/PO。Flyway V69 只建采购状态索引；PG18 双 Owner Mock 验证。见 [PO-072](purchasing/purchase-delivery-v72.md)。

## 2026-11-02 · V71 FBA 补货与供应商 MOQ/箱规报价衔接（当前）
- 复用 V22 ReplenishmentPolicy、V42 SupplierSkuRule.orderQuantity、V37/V38 人工采购 DRAFT；ACTIVE SKU 映射和可信 OBSERVED 库存才允许供应商 MOQ/箱规报价候选。原币金额和供应商人工交期不代表承诺到仓。
- Owner 选择报价后，服务端复核 FBA 观测/策略/供应商版本及取整采购量；Flyway V68 快照基础件数、实购件数、币种单价、MOQ/箱规与报价版本。新代码 PG18+HTTP CI 模拟验收，真实 Amazon Seller/Ads 仍关闭。详见 [INV-071](inventory/replenishment-supplier-quotes-v71.md)。

## 2026-11-02 · V70 订单观测速度与补货数据质量门禁（当前）
- 基于现有 Orders v2026 的订单项和订单同步检查点，提供按 Marketplace/Seller SKU 最近 7/28 天下单件数及提前期比例参考；未完成同步、缺订单行、混合来源、未映射 SKU 一律不可计算，严格区分零订单与未知。
- Flyway V67 只增加历史窗口/当前订单项索引；原 V22 策略、V37/V38 人工采购 DRAFT 与 V42 供应商规则不改写、不自动下单。React 直接在现有补货工作台展示来源质量和样本，不增加平行系统。见 [INV-070](inventory/observed-demand-v70.md)。
- 代码/单测、CI 模拟 PG18 HTTP 与 Docker/Chromium 结果需按各自 GitHub Actions 查看，真实 Amazon Seller/Ads 仍未授权。

## 2026-11-01 · V69 Marketplace 运营规则与站内提醒（当前）
- 复用 V10/V67 ops_finding，Flyway V66 增加 Tenant+Marketplace+Rule 配置、审计、版本冲突保护；按站点启停六类异常，单独设置 OBSERVED 低库存阈值。
- 实际运行的 MVC OpenAPI + Orval 8 Client、React 运营工作台配置/审计页，PG18 两站点 Mock 场景测试。无外部通知渠道，Scheduler/Seller OAuth 仍关闭。
- 详见 [OPS-009](operations/ops-rules-v69.md)。

## 2026-11-01 · V68 预发布安全与数据恢复门禁（当前）
- PostgreSQL 18 的 `pg_dump -Fc` / `pg_restore --single-transaction --exit-on-error` 一次性 Mock 恢复，覆盖所有 public 表行数+逻辑指纹、Flyway 历史/索引/约束；严格禁止非本机、非模拟数据使用。
- Spring Security Test / 本机 HTTP 验证匿名拒绝、CSRF、Cookie、头部、注销失效；Operations 本机串行 p95 冒烟；Docker Compose 生产 overlay 默认安全配置静态校验。
- CI 演练不等于线上生产容灾或安全批准；完整正式 RPO/RTO、KMS、真实 Amazon Seller/Ads、并发压测及安全签审继续列为阻塞。详见 [V68 验收手册](testing/release-readiness-v68.md)。

## 2026-11-01 · V67 运营自动化与成熟组件复用进度
- Flyway V65 扩展既有 V10 运营队列，新增 FBA 本地货件复核、Ads 未授权日报审核来源，Owner 认领/释放、暂缓提醒/恢复、完整操作审计、ACTIONABLE 工作台筛选、Spring Boot Validation。
- 基于实际 Spring MVC Controller 的 operations-v67 OpenAPI + Orval 8 生成客户端，替代前端原 V10 手写 HTTP；PG18 HTTP 与双 Owner 冒烟按 CI 运行验证。
- 定时器、Seller OAuth、Ads OAuth、自动远程写入/消息发送仍关闭；成熟依赖新增时需有具体价值。见 [V67 技术复用](architecture/reuse-audit-v67.md)。

## 2026-11-01 · V66 开发进度（替代早期阶段描述）
- V65 货件入仓/在途/差异本地 Mock 与 Orval 已经合并 `main`，真实 Amazon 入仓未授权。
- V66 启动独立 Ads 来源：Flyway V64、离线 Ads Profile、按日 Campaign 花费与双 Owner 复核、防重、实际 Controller OpenAPI + Orval；真实 Ads OAuth/Reports API 仍关闭。
- V67 后续仍为运营异常队列及自动化，V68 为安全/容灾验收。真实 Seller/Ads OAuth 均在核心开发完成后由 Owner 本地联调。

## 2026-11-01 · V61 计划复核与后续执行顺序（历史顺序参考）

**交付状态须分开**：代码提交 / 离线 CI 通过 / 本地 Mock 全链验收 / 真实 Amazon 或银行联调 / 生产上线批准，是五个不同关口。V61 为产品开发里程碑，**当前 Flyway 最新迁移为 V59**，不能混称数据库 V61。本文以下的历史段落不自动成为现行优先级。

| 工作流 | 已提交的代码与证据 | 不得误称已完成的内容 |
|---|---|---|
| M0 环境和底座 | Java25/Gradle、React19、PG18 Flyway、Docker Compose；V61 CI 四项成功 | 使用者本机 IntelliJ 的 PG 密码配置与本机运行结果仍未验证 |
| M1 Seller 与后台同步 | 公有/私有 Seller 授权代码、Seller Role、加密 Vault、Orders/Listing/FBA/Finances/Reports 只读 Worker、可选 Redis8 | 真实 Seller 授权、令牌撤销竞态、Amazon 实时负载、正式限流及出站安全验收 |
| M2 商品、库存、采购、仓储 | SKU 历史映射、订单项、库存观测、补货/采购、ASN/QC、批次/库位与人工仓储审计 | 真实 FBA Shipment/Inbound/物流状态、完整并发/多租户 HTTP 实测 |
| M3 财务、结算与分析 | V58B Reports 持久化、V59 双人来源审核、V60 银行人工参考、V61 店铺与单 SKU **按页贡献估算**；V61 CI 通过 | 多 SKU 分摊、可信退款成本回转、真实广告报表、整期财务快照、税费与汇兑、可信银行到账 |
| M4 Beta | **未进入正式发布验收** | G1/G2/G3 决策签署、威胁模型、真实接口、备份恢复、并发压测、生产发布 |

### 即时 P0：解决本地 IntelliJ 直启配置问题（V61.1）

用户于 2026-11-01 提供的 IntelliJ 日志提示 `The server requested SCRAM-based authentication, but no password was provided`。`application.yml` 中 `DB_PASSWORD` 缺省为空；IDEA 直接启动 `MerchivraApplication` 不会自动读取 Compose 的 `.env`。本机运行先在 IntelliJ Run Configuration 配置 `DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD`，再用交互式 `psql -W` 验证专用数据库账号。详见 [开发指南的 IntelliJ 专节](development.md)。**不可通过禁用 SCRAM、硬编码明文密码、覆盖管理员角色或删除数据卷解决**。这是本地连接配置问题，不能误报为 PG18 Flyway SQL 失败或 V61 业务逻辑失败。

### 接下来开发哪些功能

| 顺序 | 计划工作包 | 可审计交付和验收门槛 |
|---|---|---|
| P0 · V61.1 | 本地开发及 Mock 全链稳定性 | IntelliJ / Gradle 连接指导；PG18 模拟业务、Session/CSRF、跨 Marketplace 数据隔离、重放与重启、可选 Redis 异常；无真实 Seller |
| P0 · V62 | 多 SKU 订单逐行成本和费用归属 | 明确的订单项来源、人工审核分配、币种一致、签名金额守恒、版本/撤销审计，留存未分配差额；**不得默认按件数或销售额臆造费用归属** |
| P0 · V63 | 退款/退货与成本回转 | 复用 V34/V35，退款源到订单行、实际库存处置与历史成本证据分开验证；未确认成本回转不得计入利润 |
| P0 · V64 | **整期异步**店铺和 SKU 来源快照/导出 | 固定时间窗、来源版本、断点/重试、去重、可解释的数据覆盖率、币种隔离、可重新生成的报表；不把 V61 单页合计包装成整期净利润 |
| P1 · V65 | FBA 货件、入仓/在途和差异 | 官方只读 API 适配与离线模拟、货件与本地 ASN/库存来源隔离、数量/时间/状态追溯；默认不向 Amazon 写入 |
| P1 · V66 | 独立 Amazon Ads 授权和费用来源 | Ads OAuth 及只读报告、按币种去重、账户/站点隔离、SKU 归因审核；手工 `MANUAL_UNVERIFIED` 不能升级为受信 LIVE |
| P1 · V67 | 自动化异常队列与经营提醒 | 异常聚合、租户作用域、去重、可审计的 Owner 处理动作；调度默认关闭、平台写调用仍关闭 |
| 发布前 · V68 | 安全、性能、容灾和验收门禁 | 租户越权、文件/凭证保护、CSRF、事务/租约竞态、PG18 迁移与备份恢复、Redis noeviction、性能和可操作运行手册 |
| **最后** | Owner 在本地真实 Amazon/银行联调 | Seller OAuth、角色、LIVE 接口/限流/失败恢复、独立银行受信来源与人工核对；业务代码全量补齐后再进行，不在公共 CI 使用真实凭证 |

**财务边界**：Orders 预计款、Finances 原始交易、Reports 结算单与银行人工参考不得重复计入。来源双人审核不能证明银行到账；退款、广告/税费、FX 与多 SKU 分摊未知时仍只能显示贡献估算，而非净利润。

**已确认工程基线**：Java 25、Spring Boot 4.1.2、**Gradle**、React 19（不使用 Vue）、PostgreSQL 18、可选 Redis 8；无必需 OpenTelemetry javaagent。优先复用已经实现的 V29/V33/V34/V35/V36 来源与金额审核，不重复造平行利润引擎。业务设计的 G1/G2/G3 正式签署仍待完成，不因 CI success 自动批准。


## 第二轮评审状态（2026-10-30）

目前完成候选设计的交叉修订，但 G1、G2、G3 均**尚未通过**。下一步不是立刻启动 M0/M1，而是处理 [REVIEW-002](governance/review-report-2026-10-30.md) 的 Blocker/High 项，邀请运营与财务确认 [DEC-001](governance/decision-register.md) 后再签署相关 ADR。

## 第三轮 G1 产品设计补全（仍需批准）

[产品业务评审包](product/g1-review-package.md) 已定义 US/NA 试点候选、FBA/FBM 范围、SKU 映射和财务口径；[虚构案例](product/business-scenarios.md) 包括销售/部分退款/FBM 缺费/财务重复/库存未知等。下一步由负责人完成 [G1 决策](governance/decision-register.md) 和 [G1 业务验收](requirements/g1-business-acceptance.md) 的评审记录，然后才能正式启动 G2 物理数据/接口的细化与冻结。

## 第四轮 G2 约束级架构补全（候选）

[DATA-003 物理模型](architecture/physical-data-design.md)、[ARCH-003 一致性](architecture/consistency-boundaries.md)、[INT-003 来源契约](integrations/amazon-data-contract.md)、[API-002 OpenAPI](api/README.md) 及 [G2 评审报告](governance/g2-review-2026-10-30.md) 已成为可审阅的下一阶段设计稿。**G1 未签署，G2 亦未通过**；不得立即实施 Flyway/业务 API。后续先明确 DEC-04/05/06/07/09/11/13，再批准物理唯一约束和正式 Auth/API 方案。

## 开发阶段 CI 决策（已确认）

按项目负责人要求，**M0～M2 轻量 CI**：后端快速单测，前端类型检查、少量 Vitest 与构建。**不执行 Playwright E2E、跨浏览器矩阵、覆盖率或完整容器测试门禁**。M3 在主要业务流程能联通后逐步补关键 E2E，M4 Beta 上线前再按实际安全/业务风险加强自动检查。详见 [TEST-003](testing/ci-policy.md)。

这是开发效率决策，不表示取消真实授权、租户隔离和财务关键规则的必要验证，也不意味着已获准生产部署。

## 建议工作优先级

- 当前第一优先：回答 [Q01–Q08](requirements/decision-questions.md)，进行产品与利润口径评审。
- 第二优先：确认 [ER 图](architecture/data-model.md)、[同步设计](architecture/sync-engine.md)、[安全模型](security/security-design.md)。
- 第三优先：确定 Amazon 开发者权限/站点与真实 API 版本支持矩阵。
- 只有上述通过后，再从已建立的 GitHub Issues 认领开发任务。

## 不建议立即交付的内容

真实业务代码、生产部署脚本、数据库最终 DDL、最终高保真 UI，以及任何声称实际接入成功的演示。这些都依赖需求和授权决策。

## M2 核心库结构进度（2026-10-30）

- [x] Flyway V2 创建租户、Seller Account、Marketplace、内部 SKU、Listing 映射、订单/订单项、FBA 观测和 Sync Stream 等核心表，并在跨实体关联上约束 `tenant_id`。
- [x] 固定虚构账户可通过本地 Mock 成功同步写入结构化核心表，在 Web 同步页展示数据计数；导入使用稳定来源键及幂等插入。
- [ ] 真正的授权 Session、租户/账号权限作用域、真实 Seller 数据适配与 Finances 仍未实现；无真实业务 API。
- [ ] V1/V2 在实际 PostgreSQL 的迁移执行及跨租户失败用例尚未验收，CI 仍仅为快速编译、类型检查和少量单测。

## 本地模拟调用链补齐（2026-10-30）

- [x] 按 INT-002/INT-003/SYNC-002 **语义**添加 MockSpApiClient / 固定 US/DE 分页 Orders、Listing、FBA 场景；没有真实 HTTP Amazon 调用。
- [x] 本地 Owner 当前租户的 LOCAL/UNCONFIGURED Marketplace 可以显式触发 Sandbox 模拟导入，保留 MOCK 来源、事务、审计和幂等记录；前端通过核心业务 API 读取。
- [x] 添加 [TEST-004](testing/mock-full-chain.md) 与手动 Compose 校验脚本，不增加 M0～M2 日常强制 E2E。
- [ ] 真实 SP-API OAuth/角色、官方 DTO 的映射与分页 cursor/Worker 的租约 fencing、Finances 对账/成本利润、生产权限/备份仍未实现；不能把本地模拟运行称为已批准业务交付。

## 2026-10-30 · 实际 Compose 端到端模拟联调

- [x] [Compose 全链路成功运行（2026-10-30）](https://github.com/kunlong-luo/merchivra/actions/runs/41061679196)：真实 Nginx + Spring Boot + PG 构建并执行两店铺 Mock source → 订单分页、Listing、FBA → 持久化 → 登录用户 SQL 查询 → 运行/审计 → 重启后复核 → 退出失效。
- [x] 修复根目录 Compose `.env` 读取和 US/DE 共享内部 SKU 外键冲突；原始币种与 FBA NULL/0 均保留。
- [ ] 真实 SP-API 接入、页级持久检查点/worker fencing、Finances/结算/成本利润、正式租户安全、备份及生产部署仍未完成，不能把 Mock 测试结果当成正式业务验收。

## 逐页持久模拟（V6）

- [x] V6 只增加开发用 `sandbox_page_stream` 和 `sandbox_page_commit`，支持一个 LOCAL/UNCONFIGURED Marketplace 的手动下一页提交，并对同页/同键/同租户设唯一约束。
- [x] 后端用 PostgreSQL 事务行锁与递增 `lease_epoch`，每次只落一个 Mock Orders 页；前端可查看提交进度并手动继续。
- [x] 独立 Compose 脚本新增第 1 页提交后重启、再继续第 2 页及同键重放的验证场景；最终通过结论需参考对应工作流实际运行记录。
- [ ] 尚未实现真实后台 Worker、流租约过期、真实 API 分页 token 过期恢复、持久查询窗口与正式高水位、来源授权或真实对账。

- [x] [V6 逐页断点恢复 Compose 验证成功](https://github.com/kunlong-luo/merchivra/actions/runs/41065562926)：实际 PG 17 + Nginx/Java Compose 验证先提交第 1 页、重启后续第 2 页、重复旧幂等键返回原页，页级事务代数与审计正确；**不是正式后台 Worker**。

## Amazon 官方 SP-API SDK 代码先行（2026-10-30）

- [x] Maven 引入 Amazon 官方 `software.amazon.spapi:spapi-sdk:1.13.0`；提供可构造 SDK 已发布生成类型的内部工厂与常用领域类型化入口。
- [x] 配置 LWA 令牌交换/内存缓存、NA/EU/FE 固定域名、默认关闭网络出站/远程写入；Orders v2026、Listing、FBA、Catalog、Finances、Reports、Feeds 等有安全读取调用封装与离线响应测试。
- [ ] 这仍然**不是所有 SP-API endpoint 的 ERP 业务实现**：授权回调、凭证 Vault、来源实际认证、真实动态限额/Worker/财务对账、通知/Feeds/Reports 文件流程、非卖家/专用业务 API 均需逐模块补全。详见 [INT-004](integrations/sp-api-code-integration.md)。

## V7 公有应用 Seller OAuth 初版（2026-10-31）

- [x] V7 一次性 State hash 与 10 分钟 TTL、Amazon Login URI + Redirect URI、LWA Code 交换、AES-256-GCM 租户级凭证、审计与本地 revoke API；Owner Session+CSRF 保护用户发起/撤销。
- [x] 增加严格的 Amazon Callback Origin 检查、前后两个阶段 Seller ID 绑定，私密 Code 查询日志脱敏，`PENDING_VERIFICATION` 不当作已连接。
- [x] `SpApiSellerGrantVault` 仅在 `VERIFIED/CONNECTED`、活动 Tenant、Marketplace 相符时让内部 Worker 解密；当前没有将状态自动提升的真实账户校验。
- [ ] 实际公有/私有 Seller 授权资格、TLS 域名与密钥管理、API 角色与卖家一致性校验、Token/Key 轮换、账户级任务终止和真实 Amazon Worker/财务仍需开发与验收。见 [SEC-004](security/seller-oauth.md)。

- [x] [V7 真实 Compose/PG 验证](https://github.com/kunlong-luo/merchivra/actions/runs/41145773306)：Flyway V1–V7 与旧 Mock 链全部通过，未开放 OAuth 或插入任何真实 Seller 授权。不是 Amazon 生产授权验收。

## V17 · Seller 身份匹配与 React 连接中心（2026-10-31）

- 修复 Seller v1 核验通过后 `seller_account.seller_id` 仍为 `LOCAL-` 的断点；仅在全部 Marketplace 证据验证与事务 claim 成功时绑定 Amazon OAuth Seller ID。
- 在内部凭证 Vault 增加账号真实 Seller ID 与授权身份匹配校验，保持跨租户和失效证据拒绝。
- React Owner 导航新增授权状态/启动/核验/本地撤销、OAuth 审计和独立 Orders 角色审核（全程 CSRF/同源、默认关闭）；待真实 Amazon/角色/负载/密钥验收。

## V18 · P0 以 Seller 授权闭环优先（2026-10-31）

- [x] 代码：本地店铺 → 公有网站 OAuth → 一次性 State → 加密 Refresh Token → Marketplace 核验 → 状态/脱敏审计 → 本地撤销 → **同 Seller 重新授权**。V18 禁止更换 Seller 重用同一记录；撤销时同步清除旧站点及 Orders 角色证据。
- [x] React Owner 面板：初次授权/已撤销重新授权/手动验证/审计/角色证据审批；默认无 Amazon 出站，绝不把 UI 当成生产授权成功证明。
- [ ] 使用已获批准的 Amazon 生产应用与卖家开展真实网站 OAuth 浏览器回跳、Sellers v1 验证、LWA 密钥与网络、撤销期间 Worker fencing 和安全验收。
- [ ] 私有应用自授权是独立的后续路径，需另行设计仅管理员可用、服务端安全导入的 Token 入库方式。
- [ ] 后续才是 Orders LIVE 增量、Listing、FBA、Finances；未经授权和来源验证，不开启新的自动读取和财务核算。

受控验收依据：[FR-SHOP-01/02](requirements/functional.md)、[SEC-004 OAuth](security/seller-oauth.md)、[SEC-005 验证](security/seller-verification.md)、[ARCH-003 一致性](architecture/consistency-boundaries.md)。


## V19 · 先完成代码，再集中验收（2026-10-31）

维护者要求先实现尽量完整的产品功能，不再把每一轮完整 CI/E2E/真实 Amazon 验收当作开发前置。继续保持 Secret 安全、租户隔离和危险操作禁用；未测试的代码不能标记为「可上线」。

- [x] **代码已加入，未验收**：独立于公有 OAuth 的私有应用 Seller Central 自授权服务器一次性加密导入、相同 Seller 重导入、本地撤销、脱敏状态/审计；V17 记录 authorization_method。部署 Production Compose overlay 与服务端环境配置已新增。
- [x] **代码已加入，未验收**：内部 SKU 主数据创建/改名/归档/恢复、Listing 活跃映射的事务化版本历史/解绑、操作审计，以及 React Owner 操作页；V18 迁移。
- [ ] 待开发：真实 Listing/FBA 只读 Worker 全链、订单行、Finances 交易及对账、历史成本与利润、采购/补货审批、通知与运营规则管理、报表/导出、仓储/发货、广告等。
- [ ] 集中验证：所有 V17/V18 迁移、Java 编译、React 类型与构建、PostgreSQL 18 多租户 HTTP/CSRF、虚构私有导入、Amazon 真实 Seller 授权、生产权限与备份。

[INT-005 私有应用导入](integrations/private-seller-import.md) · [CAT-001 商品/SKU](catalog/sku-management.md)


## V19/V20 补充 · 成本及采购流程（2026-10-31）

- [x] **代码已编写，未验收**：V19 SKU 原币历史成本半开区间表、OWNER 追加价格版本的 API、React 表格与输入。旧周期闭合但不物理删除，单行独立金额不称真实净利润。
- [x] **代码已编写，未验收**：V20 供应商、采购单草稿/提交、另一名 Owner 批准/取消、追加式分批收货与数量上限、状态与审计；React “采购与供应商”页面。**不更新 Amazon/FBA，不视为财务已入账**。
- [ ] Amazon Orders/Listing/FBA 正式同步、Finance/结算/税费与报表、采购库存仓库流水、自动补货建议/审批/通知、成熟企业 RBAC、多币汇兑、出口合规和生产验收仍待开发。
- [ ] 所有本轮新增迁移、代码及页面均未按维护者要求执行测试，后续需集中修复编译、类型、Flyway 和数据一致性问题。

[FIN-004](finance/sku-cost-history.md) · [PO-001](purchasing/local-purchase-workflow.md)


## V21 · 财务来源事实与人工核对代码切片（2026-10-31）

- [x] **代码已加入，未通过集中验收**：独立于 Orders 的 `finance_source_event`、原币/来源分类/稳定唯一键，可靠来源事件不能被重复同键改写；手工录入只标 `MANUAL_UNVERIFIED`。
- [x] **代码已加入，未通过集中验收**：Owner 查看站点财务来源事件及同币分类汇总、审核关联同站点订单、保留审查历史；React 财务与对账工作台及服务端键集分页。
- [ ] 未完成：正式 Finances 角色审核及 SP-API API Worker、结算/回款、可信订单收入/退款汇总、汇兑、订单成本/已知费用的解释性利润、测试与审计。

[FIN-005](finance/source-events-v21.md)


## V22 · 站点级补货策略与保守建议（2026-10-31）

- [x] **开发代码已编写，待统一验收**：按 Seller SKU 配置补货点、目标数量、采购提前期，操作审计；使用最近 48 小时可信 FBA 可售观测提出补货数量，未知/过期/部分观测不返回数量。
- [x] **React 页面及 API 代码已编写**：Core → FBA 库存可设置、查看、编辑和删除补货策略。
- [ ] 真正销量预测、采购建议审批、采购自动生成、Amazon 货件/物流在途、跨仓库存统一汇总、真实 FBA 数据接入仍未实现。

[INV-004](inventory/replenishment-policy-v22.md)


## V23 · Orders v2026-01-01 真实订单项链路（2026-10-31）

- [x] **代码已加入，尚需集中验收**：官方 Orders v2026 `searchOrders` 直接提取商品项 ID、Seller SKU、数量和 `type=ITEM` 的原币商品小计；仅请求 PROCEEDS，不取 BUYER/RECIPIENT；无金额保留 NULL。
- [x] **代码已加入**：V23 增加是否观测订单项与当前行历史标记，订单与当前项在现有租户/Marketplace/授权 Worker 的单页事务中提交，分页 checkpoint 不脱节；老版本/空缺来源不覆盖可信明细。
- [x] **代码已加入**：受 Session/账号作用域保护的订单项明细 API 与 React Core 订单展开视图，区别「未取得」与「已观测为空」。
- [ ] 真实 Seller Orders 2026 联调、实际 Seller 网络限流/边界、PostgreSQL/Compose V23 回归以及订单履约/退款/财务对账尚需集中验证或开发。

[ORD-005](integrations/orders-items-v23.md)


## V24 · 官方 Listing / FBA 只读页同步（2026-10-31）

- [x] **代码已加入，待真实 Amazon 验证**：`searchListingsItems` typed 获取 Seller SKU/ASIN/FNSKU/更新时间；`getInventorySummaries` 获取 FBA 可售/预留/更新时间，缺失保留未知。
- [x] **代码已加入**：独立 Listing/FBA 任务的代次/页游标/租约 fencing/重试与原子写入；V12/V14 已核角色和 Marketplace Vault 每页前后复核，原生 SDK 非 PII 读取。
- [x] **页面代码已加入**：Core Listing 与 FBA 添加 Owner 手动触发只读来源扫描入口，source=LIVE 与 MOCK 分离。
- [ ] 未完成：真实 Seller 授权/角色演练、生产调用与长时间分页容灾、可选 scheduler、Listings 信息扩展、FBA 货件/仓库退货、实际利润。

[集成实现契约](integrations/catalog-fba-live-sync-v24.md)


### V24B · 可选自动分页（2026-10-31）

- [x] **代码已加入、待完整验收**：Owner 手动创建的 Listing/FBA 正式只读扫描可由默认关闭的 Scheduler 逐页执行和重试，保留数据库 Lease/Epoch 与来源权限逐页复核；不会自动对 Seller 发起新任务。


## V25/V26 · Finances 角色审批及平台交易只读（2026-10-31）

- [x] **已编写并提交** V25 独立 `Finance and Accounting` 双人角色审核、历史/撤销与加密 Seller Vault 财务 Scope；此前 Orders 审核不授予财务权限。
- [x] **代码已编写，待验收** V26 SDK `listTransactions`、原币有符号金额、稳定 SHA-256 来源键、`UNCLASSIFIED` 隔离；固定 14 天/48 小时回溯窗口，持久化页游标、租约、重试和事务内来源事件/检查点。
- [x] **代码已编写** React Seller 财务角色审批页面及财务工作台同步入口，另有默认关闭的可选自动分页调度。
- [ ] 真实 Seller Finances 正式数据验收、历史补录、未分类交易 breakdown、退款/费用/结算识别、利润报表与税务口径仍未实现。

[FIN-006](finance/finances-live-v26.md)


### V27 · Finances 原始交易明细层级（2026-10-31）

- [x] 代码新增：官方 Finances Transaction 的 breakdown 嵌套路径、类型、原币金额和父子关系持久化，重复来源不可静默改变明细事实。
- [x] React 财务工作台展示来源事件的 breakdown 树形路径，无论顶层还是末级都不自动加总或推断净利润。
- [ ] 费用业务字典、退款/税金归属、可审计结算关联、成本和广告汇总的利润模型及真实 Amazon 联调尚待实现。


## V28 · Finances 规则分类与质量核对（2026-10-31）

- [x] **已编写待 CI/集中验收**：Marketplace 级精确 sourceType 人工分类、创建/修改/停用审计和历史。
- [x] **已编写待 CI/集中验收**：只对末级 breakdown 做原币 signed 汇总，缺失、混币种、未分类、来源总额不平等均不返回临时类别合计。
- [x] **已编写**：财务工作台逐事件质量状态、规则审批、修订审计和临时分类预览。
- [ ] 平台费用归属、订单成本/退款和结算款核实、真实 Amazon/PG18 联调与正式利润模型仍待开发。

[FIN-007](finance/finance-classification-v28.md)


## V29 · Orders 创建时点和历史单位成本核对（2026-10-31）

- [x] **代码待 CI/本地验收**：保留 v2026 Orders createdTime，LIVE/MOCK 订单项来源明确标记，写入当前项时校验来源隔离与集合完整性。
- [x] **代码待 CI/本地验收**：历史 SKU 映射 + 市场当地订单日期 + 有效期单位成本的 COGS 只读覆盖率与失败原因；缺来源、数量、币种时不返回误导性成本。
- [x] **代码待 CI/本地验收**：React 财务工作台按订单展示成本历史覆盖和缺项。
- [ ] 本地真实 Seller 数据验证、采购批次真实成本追踪、退款/税费/结算对账和净利润模型仍需后续开发。

[订单历史成本完整度说明](finance/order-cost-readiness-v29.md)


## V30 · 结算参考单与 LIVE 财务来源对账（2026-10-31）

- [x] **代码已加入，待 PG18 全链路验收**：人工参考结算单/有符号金额/原币/期间、LIVE Finances 交易唯一关联、人工解除与审计。
- [x] **代码已加入**：同站点同币种候选、未分配计数、期间外异常、来源交易 signed net 核平与参考差异；永不自动认为银行已到账。
- [x] **代码已加入**：React 结算单创建、来源交易选择、差异预览及分配审计；不依赖真实卖家授权。
- [ ] 真正 Settlement/Disbursement ID 及 Amazon 报告数据导入、银行流水、退款/税费最终归类、正式利润报表仍待实现。

[结算参考及差异核对](finance/settlement-reconciliation-v30.md)


## V31 · Finances 结算文档来源引用（2026-10-31）

- [x] **代码已加入待 CI**：Finances v2024 交易 `SETTLEMENT_ID/DISBURSEMENT_ID/FINANCIAL_EVENT_GROUP_ID` 原始业务标识独立存储，来源与页提交原子幂等。
- [x] **代码已加入**：V30 同站点同原币参考账单按来源 ID 精确匹配优先提示，运营人工确认才分配，不自动声称结算/银行已到账。
- [x] **代码已加入**：Owner 的来源 ID 只读查询、synthetic 官方 SDK 引用测试。
- [ ] 真实 Amazon 报告/银行入账/Settlement 明细验真及来源覆盖率仍待实现。

[结算来源引用](finance/settlement-source-references-v31.md)


## V32 · 本地财务异常自动扫描（2026-10-31）

- [x] **已编写待 CI**：按来源交易、结算参考单及 LIVE 订单历史成本自动生成有界的异常待办项，不写入会计凭证。
- [x] **已编写待 CI**：末级 breakdown / 原币 / 未分类 / 原订单关联 / 结算差异 / 过期或未知历史成本的分级检查。
- [x] **已编写待 CI**：React 异常工作台按级别筛选、60 秒自动刷新本地 API，明确标注仅扫描部分近期数据。
- [ ] 审核处置工作流、永久增量扫描、结算及银行验真和正式贡献利润模型尚待开发。

[FIN-010 财务异常自动检查](finance/finance-exception-queue-v32.md)


## V33 · 已审核财务来源的订单经营贡献预览（2026-10-31）

- [x] **代码已编写待 CI**：受信 LIVE 来源交易人工订单关联 + V28 分类核平 + V29 历史单位成本形成有界原币贡献额预览。
- [x] **代码已编写**：退款、调整、未审订单关联、混币种和成本不齐时直接不输出金额；负费用按原始正负号相加。
- [x] **代码已编写**：订单财务页面展示来源分类/成本/贡献和质量原因，任何核平都不宣称已结算利润。
- [ ] 真实退货成本回转、广告费用、仓储和采购物流、税费、正式银行到账与利润冻结报表仍待开发。

[FIN-011 订单贡献额预览](finance/order-contribution-v33.md)


## V34 · 退款商品处置与成本回转证据链（2026-10-31）

- [x] **已编写，待 CI**：LIVE Finances 来源退款的人工订单项审核，跨多个退款交易数量不超过订单原订购数量。
- [x] **已编写，待 CI**：人工“已重新入库”/未入库/未知处置 + 审核证据参考号、追加审计、撤销，订单关联变更前必须先撤销商品审核。
- [x] **已编写，待 CI**：按历史 SKU 单位成本计算仅供核验的退款成本敏感度，不写入库存台账、不变更 V33 贡献额。
- [ ] 官方退货入仓、物流/退货损耗、真实采购批次成本回转、退款金额和商品分摊/正式利润仍待继续。

[财务退款商品审核说明](finance/refund-item-review-v34.md)


## V35 · 退款商品金额分配与签名差额（2026-10-31）

- [x] **代码已写入待 CI**：V28 `PRODUCT_REFUND` 受信来源金额按 V34 已核验数量的商品项进行有符号人工分配，保留来源币种/金额/差额。
- [x] **代码已写入待 CI**：超额、币种不一致、退款签名方向冲突、未审核商品审核状态拒绝保存，只有同店同源可编辑。
- [x] **代码已写入待 CI**：独立追加审计、前后端分配和撤销；V34 项撤销前必须先撤销 V35 金额分配。
- [ ] 实际入库/退货成本回转、正式 Amazon 报表与真实退款 API 验证仍待开发，V35 不产生库存或记账数据。

[退款金额分配说明](finance/refund-amount-allocation-v35.md)


## V36 · 广告费用参考来源与内部 SKU 分摊（2026-10-31）

- [x] **代码已加入待 CI**：人工未核验广告费用参考来源、稳定来源 key 幂等、广告活动/日期/原币支出事实，不伪造 Ads API。
- [x] **代码已加入待 CI**：Owner 审核 SKU 分摊基点，累计不超过 100%、显示未分摊费用、变更/撤销审计。
- [x] **代码已加入待 CI**：财务页面广告费用表与归属比例录入，纯 Java 分摊计算及边界测试。
- [ ] Ads API 独立 OAuth/报告、广告费用与 Finances 去重、SKU 广告归因及最终毛利/净利仍待开发。

[广告费用 SKU 分摊](finance/ad-spend-sku-attribution-v36.md)

## V37 · 补货建议转换本地采购草稿（2026-10-31）

- [x] 代码提交：Owner 人工从 FBA 补货建议生成本地采购 `DRAFT`，创建前验证最新观测/策略版本/ACTIVE 内部 SKU 映射、采购价和供应商；同租户 UUID 请求幂等与 V37 不可变来源快照，React 操作与来源历史。
- [x] 增加后端补货决策及拒绝测试，前端请求契约测试；**实际测试结论以 GitHub CI 为准**。
- [ ] 待完整验收：PostgreSQL 18 Flyway V37、并发幂等/隔离、跨租户 HTTP/CSRF、浏览器实际操作；后续预测算法、MOQ、仓库履约、官方 Shipment API 均未实现。
- **不自动采购**：只创建本地草稿，沿用 PO-001 提交和不同 Owner 审批，不使用 Amazon 写入 API。

## V39 · 本地采购收货、仓库入库流水及待收货（2026-10-31）

- [x] 代码提交：V39 本地仓库与追加式入库流水；采购收货明确指定仓库，仓库/收货/内部 SKU 复合租户约束与来源收货唯一键，收货+库存移动同事务。
- [x] React 采购页仓库创建、收货选择、已审批待收货、仓库累计采购入库和近期收货流水；后端/前端离线防护用例已加入。
- [ ] 真实 PostgreSQL 18 新迁移与并发/CSRF/浏览器联调的验收，以实际 CI/Compose 运行结果为准。
- [ ] 仍缺真实物流在途/ASN、出库扣减/仓间调拨/盘点、供应商 MOQ、仓储异常提醒，不能将本地累计入库视为 Amazon FBA 可售。
- 文档：[PO-002 本地仓库收货](purchasing/local-warehouse-receipt-v39.md)。

## V40 · 本地仓库出库、调拨与盘点（2026-10-31）

- [x] 编码 V40 迁移：新增可追溯操作实体，扩展 V39 采购收货流水以保存人工出库、调拨双向流水和实盘差异；同租户请求键、库存来源唯一约束与非零数量、数量方向检查。
- [x] 新增 Owner + Session/CSRF 库存操作 API、余额快照复核、仓库行锁、库存不足拒绝；React 采购仓库台账增加人工操作与历史明细；JUnit/Vitest 离线用例。
- [ ] PostgreSQL 18 V40 真实迁移、跨进程并发/反向调拨死锁验证、生产级仓储操作审计和完整 HTTP/浏览器业务验收仍需以实际测试记录为准。
- [ ] 未实现真实货件跟踪、仓库低库存异常告警、实物在途、质检预留、成本结算或 Amazon 写入。
- 参考：[PO-003 本地库存操作契约](purchasing/local-stock-operations-v40.md)。

## V41 · 本地仓储告警与保守采购参考（2026-10-31）

- [x] V41 迁移：仓库/SKU 补货阈值与目标库存、策略变更审计、持久异常队列、状态审计、人工/计划巡检记录。
- [x] 代码：从 V39/V40 本地流水形成库存建议，不把**无流水**变成 0；仅在有台账证据且触发本地低库存阈值时提供建议。全仓已审批待收货单列而不扣减或称作已发货在途。
- [x] 低库存、异常负台账、7 日内较大盘点差异识别；Owner 认领/标记处理/清除，React 仓库管理面板；可选本地计划巡检默认关闭。
- [x] 新增 Java 规则/隔离检查与 React 契约测试；实际 CI 结果须单独确认。
- [ ] PostgreSQL 18 V41 迁移、并行多实例巡检与跨租户 HTTP/CSRF、完整真实流程验收尚待完成。
- [ ] 仍缺 ASN / 供应商交期与 MOQ / 真实发货在途 / Amazon FBA 入库对账与用户通知渠道。
- 详见 [PO-004 本地仓储告警](purchasing/local-stock-alerts-v41.md)。

## V42 · 供应商 MOQ 与仓库采购计划（2026-10-31）

- [x] V42 数据迁移：供应商/SKU 起订量、包装倍数、参考自然日交期、原币采购价格、版本和策略审计；本地采购计划带仓库最后库存流水证据、V41 策略与供应商条款版本、生成数量、预期日期和生命周期审计。
- [x] 后端：按 max(本地建议,MOQ) 对包装倍数向上取整；不为无库存证据的仓库生成计划，不盲目扣减未按仓分配的待收货；同仓 SKU 同一库存观测只能建立一份计划。
- [x] 前端：人工录入供应商 SKU 条款 → 选择仓库预览建议 → 保存本地计划 → 二次确认转换为采购单 DRAFT → 保留原双人审批。重复转换幂等，无真实 Amazon/供应商写入。
- [x] 新增 JUnit/Vitest 算法、来源快照、幂等和 CSRF 用例；以对应提交 CI 结果确认验证状态。
- [ ] V42 PostgreSQL 18 迁移与并发、跨租户 HTTP，以及供应商报价阶梯、物流 ASN、到货预测与真实 Amazon Inbound 接入需另行验收/开发。
- 文档：[PO-005 供应商采购计划](purchasing/supplier-purchase-planning-v42.md)。

## V43 · 供应商预到货通知 ASN 与交期异常（2026-10-31）

- [x] V43 数据迁移：采购行关联目标本地仓的供应商 ASN、来源请求幂等 UUID、计划到货日期、人工供应商申报状态、历史事件、可选 `erp_purchase_receipt.asn_id` 复合约束及持久交期异常审计。
- [x] 新增明确供应商发货上报、ETA 更新（旧值匹配/原因审计）、取消未收货 ASN、查看真实链接入库量；采购收货在同一事务锁定 PO / ASN，防超量和不匹配仓库、采购行。
- [x] 本地 ASN 未来三天到货及逾期待办，已完成/不匹配自动清除，Owner 可人工处理，按租户可选的计划巡检默认关闭。
- [x] React 采购 ASN 管理、ETA 变更、关联实物收货、工作队列和单元契约测试；以对应最终 CI 记录为验证依据。
- [ ] 真实 PostgreSQL 18 V43 Flyway/Compose、跨租户 HTTP、并发串行化及完整业务流程尚须专项验收；实际供应商 EDI、承运商 Tracking、真实 Amazon Inbound 尚未接入。
- 参考：[PO-006 供应商 ASN 预到货与交期管理](purchasing/supplier-asn-tracking-v43.md)。

## V44 · 本地采购实收质检、隔离品和供应商退货（2026-10-31）

- [x] V44 数据迁移：独立一次性实收 QC、合格/不合格数量守恒、带唯一 inspection 来源的负向 `QC_QUARANTINE` 库存移动、租户隔离的供应商退货草稿与人工发出、不可覆盖审计与异常工作队列。
- [x] Spring Boot 服务与 Owner/Session/CSRF API：严格核对真实采购收货与实际仓库，库存不足不得隔离；单次质检只扣不合格品，退货发出不能第二次扣仓库台账，所有相关动作幂等并附加审计。
- [x] 退货可分批草拟/发出，但所有非取消草稿累计不能大于本次质检不合格品；隔离未处置和超过 7 天的异常可人工/可选计划巡检，默认不自动巡检、不外发邮件、不调用 Amazon。
- [x] React 质检与退货管理、库存来源展示及 JUnit/Vitest 合约用例；以最新 CI 记录确认测试状态。
- [ ] PostgreSQL 18 V44 Flyway/Compose 真实迁移、跨租户 HTTP/并发、产品真实环境整体验收尚需单独执行。暂不支持待检实物货位、批次/序列号、供应商索赔与 RMA 物流签收、退款/信用票据。
- 详见 [PO-007 质检与供应商退货](purchasing/receipt-quality-and-supplier-returns-v44.md)。

## V45 · 采购差异、供应商索赔与可信交付概览（2026-11-01）

- [x] V45 数据迁移：按租户/采购行/ASN 约束的人工数量差异不可覆盖快照（PO 未收足、ASN 申报未收足、未入账的额外实物）；独立审阅、索赔与追加式审计，保留所有早期 V20–V44 真实库存流水。
- [x] 后端：Owner 人工登记差异，后续由另一位 Owner 复核；复核、建立来源型索赔、提交及审批前再次检验真实 PO/ASN 收货快照，不新建采购收货或 Amazon 货件状态。
- [x] 索赔来源仅允许已复核的差异或 V44 不合格品，数量不能超来源，补发/信用折让仅是本地需求。索赔 DRAFT/SUBMITTED/APPROVED/CLOSED/CANCELLED，独立 Owner 审批与人工结案，绝不自动产生退款、财务票据或向供应商提交。
- [x] React 新增差异登记、复核、供应商索赔、审计历史和可信供应商统计（真实订购/实收/QC 拒收/活跃差异/逾期 ASN）；新增 Java、Vitest 契约测试，最终 CI 结论以对应提交为准。
- [ ] 仍需 PostgreSQL 18 V45 实例真实 Flyway/Compose、真实 HTTP 跨租户/CSRF、多会话并发，完善待检货位、批次、质检独立审批，以及实际 RMA、外部索赔提交和 Amazon Inbound。
- 详见 [PO-008 供应商差异与索赔](purchasing/supplier-discrepancies-claims-v45.md)。

## V46 · 新收货待检批次与独立质量放行（2026-11-01）

- [x] PostgreSQL V46 向前迁移：新实收专属 tenant+receipt 批次、仓库 SKU 下唯一批次编码、PENDING_QC/INSPECTED/RELEASED 状态、Owner 审计事件以及唯一 QC_RELEASE 正数库存来源；绝不更改历史 V20/V39–V45 实收和 V44 已记库存。
- [x] `PurchasingService.receive` 原采购审批/ASN 真实收货事务仍生效，但其库存记录改为**先入待检批次**，不立刻进入可用库存；完成 QC 只保存合格/不合格证据，待另一 Owner 显式释放合格品才一次性写 `QC_RELEASE`，全数拒收没有可用库存入账。
- [x] 旧 V44 无待检批次的收货继续按已入库历史单独负向隔离不合格品；二者数据不能混同。仓库真实累计实收与可用库存分开汇总，待检 SKU 也出现在库存表（可用为零）。
- [x] 新建批次编码变更的版本比较和审计、批次查询/审批/事件 React 工作台，JUnit/Vitest 覆盖无重复入账、质检员不得自审、零合格放行、历史路径兼容及 CSRF。
- [ ] 尚需 PostgreSQL 18 实例 Flyway/Compose、HTTP 多租户与并发、完整浏览器业务流程专项验收；批次拆分/序列号/物理货位及 Amazon 授权留后续。
- 详见：[PO-009 V46 批次质检与独立放行](purchasing/held-receipt-lots-quality-release-v46.md)。

## V47 · 待检批次拆分、库位与补货压力可视化（2026-11-01）

- [x] V47 Postgres 迁移：仓库默认虚拟 QC-HOLD，租户/仓库复合约束的待检库位、真实收货 ROOT 分段及事件，原始已检/已放行批次不回写位置。
- [x] 后端只允许在真实 PENDING_QC 阶段拆分批次分段、同仓待检库位移动；tenant+批次锁、来源仓库/库位验证、UUID 幂等、原因审计，分段数量守恒；**不产生库存入账**。
- [x] 支持创建 QC_HOLD / STORAGE 库位目录，其中待检分段不允许移动到 STORAGE；新仓库和每笔新 V46+ 实收自动创建接收来源，缺少默认待检库位必须回滚实收事务。
- [x] 只读供应压力：签名库存流水可用数、V46 待检数、已检待另一位 Owner 放行的合格数、供应商申报 ASN 在途未收数；不自动计为可售或创建采购单。
- [x] React 管理库位、批次分段、数量核对、待检压力；JUnit/Vitest 新增数量守恒、跨仓拦截、来源快照、CSRF 与无库存副作用验证，以最终 CI 为准。
- [ ] 真实 PostgreSQL 18 Flyway/Compose、跨租户 HTTP 与竞争事务仍需专项验收；真实批次级可用库存/序列号/FEFO/仓位盘点和 Amazon API 留后续。
- 文档：[PO-010 V47 待检批次/库位](purchasing/receipt-lot-locations-v47.md)。

## V48 · 分段质检、已放行批次库位台账及 FIFO/FEFO 原子出库（2026-11-01）

- [x] V48 Postgres 迁移：V47 piece 逐段不可覆盖 QC、每来源唯一请求键；已放行真实 receipt 的 STORAGE 库位上架原数/余数、可选有效期、FIFO/FEFO 出库主记录、批次扣减明细及追加式审计事件。
- [x] 分段质检必须守恒并覆盖全部分段，随后由原 V44/V46 inspect 生成唯一整笔 QC；第一段质检开始后冻结待检片段移动/拆分。旧整笔质检 API 如果发现部分分段已检也不能绕过数量和覆盖核对；V46 第二 Owner 放行规则保持。
- [x] 已放行合格品可显式放入当前租户同仓 ACTIVE STORAGE 库位，最多上架原合格总量，且总库位余量不能大于仓库签名库存余额；上架仅分配来源，不会第二次写 QC_RELEASE。
- [x] FIFO/FEFO 选择有位置且未过期的批次、校验 expectedOnHand；人工确认后同事务先扣 lot position、调用既有 V40 OUTBOUND 签名仓库流水并保存 pick 分配/审计，失败全部回滚，重复 requestKey 不重复出库。普通出库/调拨/盘点不允许侵占已定位批次余量。
- [x] React 分段质检、整笔核对、上架及 FIFO/FEFO 预览/人工拣货；新增后端与 Vitest 来源/幂等/审批/库位数量保护测试，以对应最新 CI 结果确认。
- [ ] 仍需真实 PostgreSQL 18 Flyway/Compose、并发事务及 HTTP 权限/端到端环境专项验收；批次级部分放行、已放行库位间调拨、退货/盘点批次溯源、条码扫描和 Amazon API 仍待后续。
- 文档：[PO-011 V48 分段质检及批次拣货](purchasing/graded-lot-putaway-fifo-fefo-v48.md)。

## V49 · 已放行批次移位、跨仓调拨、盘点观察与过期隔离（2026-11-01）

- [x] V49 迁移：批次位置 origin_position_id 来源父链、审计动作（MOVE/TRANSFER/EXPIRED_QUARANTINE/COUNT_OBSERVATION）、独立签名 LOT_QUARANTINE 负数移动及租户复合 FK/唯一幂等。V48、V46 历史收货与原始库存事件不重算。
- [x] V48 已放行 STORAGE 位置同仓移位，明确旧库位、目标 STORAGE 和预期数量，写审计但不写总库存；剩余 0 不允许虚报实物移位。
- [x] 跨仓有来源库存调拨：同一事务中扣来源位置、调用 V40 真实 TRANSFER_OUT/TRANSFER_IN，并创建目标仓来源子位置，保留实收 receipt、lot、SKU、原有人工有效期和父级位置。对已过期来源拒绝跨仓，已调拨子位置后续可按 V48 FIFO/FEFO 出库。
- [x] 实物盘点观察只记录 expectedRemaining 与 observedQuantity、操作者、原因，不会未经独立复核直接生成正/负库存调整；过期预警只读，手工确认已过期品才同时扣减仓库签名可用与库位余量，写 LOT_QUARANTINE 源流水而非伪造销售出库。
- [x] Owner Session + CSRF V49 API / React 工作台：近 30 天到期提醒、同仓移位、跨仓调拨、盘点差异、过期隔离、不可覆盖操作审计及递归溯源到最初真实收货位置；JUnit/Vitest 覆盖无库存副作用、调拨和单次隔离等规则，以 CI 结论为准。
- [ ] PostgreSQL 18 真正 Flyway / Compose、跨租户 HTTP 权限、并发多事务与多仓实物扫描仍需专项验收。后续 V50 优先正式盘点差异审批与调整、跨仓收发双人确认及独立隔离库位。
- 详见：[PO-012 V49 批次移位/调拨/盘点/隔离](purchasing/released-lot-lifecycle-v49.md)。

## V50 · 盘点差异独立复核、跨仓收货确认与隔离品审批（2026-11-01）

- [x] PostgreSQL V50 迁移：与 V49 原始 COUNT_OBSERVATION 一对一的独立盘点复核单、跨仓 TRANSFER 一对一的第二人收货确认、与已签名 LOT_QUARANTINE 一对一的处置审批/执行记录，含请求键和 tenant/Owner 来源审计。
- [x] 盘点少货经不同 Owner 审批后，在同一事务更新定位批次剩余数量并通过 V40 `COUNT` 生成标准负向签名库存流水；重查 bin、批次数、SKU 和 warehouse expectedOnHand，任一来源变化则拒绝并回滚。盘盈只标 `EVIDENCE_REQUIRED` 不凭空加库存；无差异/拒绝也不改账。
- [x] V49 已过账的目的仓收货由另一名 Owner 独立核对：CONFIRMED 全额、MISMATCH 少收时保留真实异常，绝不重复创建 TRANSFER_IN 或未经核对自动调账；一次调拨仅一次带凭据核对。
- [x] 已隔离过期品只有在能定位到唯一 V49 `LOT_QUARANTINE` 签名负数流水时才允许新增 DISPOSAL/SUPPLIER_RETURN 计划。不同 Owner 独立批准；审核人不得自己报告处置执行；人工报告仅更新状态、参考编号和责任事件，绝不第二次扣库存。
- [x] 新增 V50 Owner/CSRF REST endpoints、React 三模块工作台与 JUnit/Vitest 数量守恒、双人审批、重复过账禁止、来源凭据校验测试，以最终 CI 为准。保留 React 19、Java 25 / Spring Boot 4.1、PostgreSQL 18 技术选型。
- [ ] 仍需 PostgreSQL 18 真正 Flyway/Compose 与多用户/多租户 HTTP 并发验收；有凭据盘盈转入、目的仓 MISMATCH 纠错、隔离品物流证明和 Amazon SP-API 真正联调待后续。
- 详见：[PO-013 V50 库位复核与异常处理](purchasing/lot-count-review-transfer-receiving-disposition-v50.md)。

## V51 · 有来源盘盈绑定、目的仓少收纠正与处置凭据元数据独立复核（2026-11-01）

- [x] V51 迁移：`erp_local_lot_exception_resolution` 保存 V50 EVIDENCE_REQUIRED 盘盈或 MISMATCH 目标仓短收的唯一来源、仓库/库位/库存快照、证据编号、独立 Owner、状态及真实签名 COUNT operation ID；`erp_local_lot_disposition_proof` 保存 V50 EXECUTED_REPORTED 的人工证明材料元数据（可选 SHA-256）及不同 Owner 的内部审核。迁移保持所有既有库存历史和 PostgreSQL18 选型。
- [x] 盘盈只能从同一 V46 真实已 RELEASED、approved accepted 未分配完的原始根批次位置补充分配；再次核对 tenant、position/bin、quantity、signed warehouse SKU 可用余额、该来源所有根位置 placed 合计及仓库所有库位 remaining。审批只是增加 root placed/remaining 和 SURPLUS_BOUND 事件，不允许新增正向库存流水或伪造采购实际收货。
- [x] V50 已由另一 Owner 证实的跨仓少收 MISMATCH 才能申请纠正。另一非申请人/原签收人核准，必须要求目标仓原调拨位置尚未再次出库、调拨、移位且库存快照未变化；同事务减目标位置余量并通过 V40 COUNT 减目标仓签名库存，追加 SHORTAGE_CORRECTED 来源事件；不新增第二次 TRANSFER_IN/OUT。
- [x] 过期品实际处置已 EXECUTED_REPORTED 后可登记与处置方法一致的报废/供应商退货参考编号、可选 SHA-256 摘要、来源说明；不是附件上传或外部核验。不同于凭据登记者和原处置执行者的 Owner 可以标记 METADATA_REVIEWED/REJECTED，明确“内部审核元数据”，不冒充承运商签收或外部文件真伪验证，不产生任何库存流水。
- [x] React V51 两个工作台、Owner/CSRF API、JUnit/Vitest 校验来源与审批者、防重复调账、非负库存和不误报文件核验；最终以前后端 CI 结论为准。
- [ ] PostgreSQL18 Flyway/Compose 与多用户多租户 HTTP 并发真实性验收仍待专项完成；实际证据附件上传/摘要比对、外部物流/供应商签收核验和与 Amazon SP-API 的真实授权联调后续。
- 文档：[PO-014 V51 有来源异常纠正与证明元数据](purchasing/lot-exception-source-corrections-proof-v51.md)。

## V52 · 真正的私有凭据原件、SHA-256 摘要检验、原供应商退货来源与 PG18 Flyway（2026-11-01）

- [x] `V52__proof_binary_and_supplier_trace.sql`：按 tenant 和 V51 proof ID 唯一存储最多 2 MiB 的 PDF/PNG/JPEG 原始 BYTEA，带服务端实际 SHA-256、上传者/时间；供应商退货 trace 唯一引用已经 V50 审批且人工报告执行的过期品处置，来源按原始 V20 receipt/order/supplier 推导。
- [x] Owner Session + CSRF multipart 真正上传、实际字节 magic/大小校验、SHA 与 V51 人工事先填写 digest 比对、上传后不可覆盖；GET 私有元数据，下载时重新校验 SHA 并强制 attachment/no-store/nosniff，不允许匿名查看文档。
- [x] 对已独立审批并人工报告 SUPPLIER_RETURN 的 V50 过期品处置，保存人工运单及根据原真实采购订单解析的 supplier；另一位 Owner 人工记录供应商实收或 RECEIPT_MISMATCH，不能复用 V44 质检拒收品退货来源，不能重复扣库存或声称外部系统已经签收。
- [x] 新增 React 19 原件上传/下载、哈希展示、原供应商来源和退货收货对账页面，Java 单测和 Vitest 覆盖文件/hash、审批后不可修改、供应商来源真实性、不同 Owner、回执差异与无重复库存流水。
- [x] GitHub CI 增加真实 PostgreSQL 18 服务和 Spring Boot Flyway 全迁移作业，检查 V46–V52 及最新表；是否成功须以对应最终 SHA 的 CI 结果为准，不能将模拟单测等同于 PG18 已验收。
- [ ] 未实现病毒扫描/对象存储加密、外部签名、物流接口和自动财务冲销；真实 Amazon OAuth/SP-API 仍由 Owner 在全部核心开发后本地授权。
- 文档：[PO-015 V52 原件与供应商对账](purchasing/lot-proof-files-supplier-trace-v52.md)。

## V53 基础构建迁移 · Maven → Gradle（2026-11-01）

- [x] 维护者明确要求后端淘汰 Maven，统一 `apps/server/build.gradle` Groovy DSL、Gradle **9.2.1**、Java **25**、Spring Boot **4.1.2**（保持当前较新的已验证小版本）和 dependency-management 1.1.7，group `org.merchivra`；不能复用别的项目 `com.spark.iot`。
- [x] Docker 多阶段构建、GitHub CI Java 单测和真实 PG18 + Flyway bootRun、README/中文开发文档、Agent 工程规则切换到 Gradle。官方 Gradle Wrapper 含发行版 SHA-256 验证；JaCoCo XML/HTML 报告与可选 `-PstrictCoverage` 83% 行覆盖率验证。
- [x] Maven `pom.xml`、`target/`、Maven CI 构建命令不再作为有效工程入口。Kafka、InfluxDB、Redis Repository、JPA、WebSocket 等尚无 Merchivra 代码使用，暂不引入以保持三服务部署不变。
- [ ] 真实 Amazon Seller 授权及所有外部 API 仍待项目 Owner 在全部核心开发完成之后自行本地测试；Gradle 迁移成功不等于业务联调验收。

## V54 · Redis 8 共享会话、SP-API 跨实例冷却与真实冒烟（2026-11-01）

- [x] 后端 Gradle Spring Data Redis + Spring Session Data Redis：显式 `MERCHIVRA_REDIS_SESSIONS_ENABLED=true` 启用 Spring Security Session/CSRF 持久化，4 小时失效、独立 `merchivra:session:v1` 前缀；默认仍保留无 Redis 的单实例开发模式，Redis 故障不降级到隐式可信内存 Session。
- [x] `RedisSpApiRateLimiter` 使用原子 `SET NX PX` + SHA-256 卖家摘要，SDK 每 Seller/Region/Family 共享冷却，Sellers 尚未获得 Seller ID 的验证入口另设按区域的保守冷却，旧 HTTP SP-API 入口以授予令牌摘要/operation 控制；Redis 不可用则拒绝真实出站，生产 SDK 配置要求显式开启限流。当前默认冷却 2000ms 只是初级配额保护，不是 Amazon 动态使用计划/429 反馈队列。
- [x] `deploy/compose.spark-db.yaml` 可直接复用原项目 `spark-redis:6379`；内置可选 `redis:8.10.1-alpine` + noeviction 仅开发使用；不新建额外 spark-db 容器/数据库，不触碰 `iot_farm`。新增 Redis session HTTP 真容器 login→Java 重启→Redis 故障失权冒烟。
- [ ] 正式使用现有 `spark-db` 的 `allkeys-lru` 仍有配额 Key 被淘汰导致提前放行的风险，生产前需隔离无淘汰 Redis/足够内存、ACL/TLS、监控与容量；真实 Amazon 用量限制/Burst/Retry-After 仍待 Owner 完成功能后本地授权联调。
- 指南：[Redis 8 Session/限流与使用现有 spark-db 的安全边界](deployment/redis-session-spapi-v53.md)。

## 2026-11-01 · V61.1 本地 PostgreSQL 18 一次性手工初始化补充

- [x] 参照已有 Lanveriq 仅供人工管理的示例，将其安全原则迁移为 **Merchivra 独立**的纯 SQL `apps/server/scripts/provision-merchivra-database.sql`；只创建 `merchivra NOLOGIN` 角色和专用数据库、移除 PUBLIC DB 权限，不依赖 Flyway/bootRun/Compose/CI，不包含密钥。
- [x] 更新中英文 README、`docs/deployment/local-spark-db.md` 和 `docs/development.md`：只有**不存在**既有角色和数据库时才允许 DBA 手工初始化，分开密码设置与 LOGIN，GUI 需 autocommit、stop-on-error，不能单事务 `CREATE DATABASE`；本地 IntelliJ 仍需单独配置 `DB_PASSWORD`。
- [ ] 用户在**自己的** PG18 上确认专用数据库已正确预置或现有状态无需初始化、专用密码认证成功、IDEA 启动正常。未在 CI/工具侧执行管理员级数据库创建，不可称本地连接问题已经解决。

## 2026-11-01 · V62 多 SKU 来源费用人工分摊（当前实施状态）

- [x] Flyway **V60**：同租户同 Marketplace 事件与订单、唯一项+类别分配、金额符号、完整人工双人审核和不可变审计。
- [x] `ItemAllocationEngine` + `ItemAllocationService`：真实 LIVE Finances 已审核订单关联、V28 分类合计、历史成本版本、来源 SHA-256 + 已提交明细 SHA-256，全部类别精确守恒才可由另一 Owner APPROVED；重开删除旧明细并再次审核。
- [x] `MultiSkuContributionService` 和 V61 页面汇总联动：所有相关事件审批完成、历史成本及订单财务合计吻合才计入逐 SKU 和单页总额；退款/调整及未知来源仍阻塞。
- [x] React 财务事件逐项归属、费用残差、审核、版本审计，Java/Vitest 回归用例和文档。
- [ ] 用户本地真实 Amazon 账号的 Finances 原始 leaf、退货与 Cost reconciliation、复杂晚到事件和真实费用分配仍需所有代码完成后做受控验收。V62 为可解释来源贡献估算，不是正式利润。
- 详见：[FIN V62 多 SKU 分配说明](finance/multi-sku-item-allocation-v62.md)。

## 2026-11-01 · V63 退款/退货成本来源复核

- [x] PG18 Flyway V61：同 Marketplace 退款来源人工双人审核表、SHA-256 证据摘要、不可变审计。
- [x] 整合 V34/V35 退款数量和有符号金额与原始业务日 SKU 成本，精确退款金额守恒才允许复核。变更时旧审核显示 STALE。
- [x] React 财务退款面板展示成本敞口、人工声称重新入库的潜在回转敏感度、独立 Owner 审核/驳回及操作审计；Java 和 React 离线回归。
- [ ] 受信 FBA/FBM 退货实际收货、库存处置、正式 COGS 回转/会计凭证未完成；仍阻塞最终净利润。下一阶段 V64 只读整期异步报表继续保留缺口标记。

## 2026-11-01 · V64 整期店铺/SKU 贡献报表实现状态

- [x] Flyway **V62**：创建时冻结候选 LIVE 订单 ID 和固定时窗，任务状态/重试/幂等键、每单不可重复的结果快照、单 SKU 分解、批次审计与租户/Marketplace 原币边界。
- [x] Java25/Gradle 任务计算与 Owner API：复用 V61/V62 严格贡献金额门禁，单批10单独立事务提交、进程中断可回滚重试、PG SKIP LOCKED 避免并行重复、失败五次暂停、任务手动恢复。
- [x] React19 工作台：创建任务、查看历史、任务状态/原币汇总、独立人工 Ads 参考、逐单缺失原因、分页、手动推进及仅 SUCCEEDED 的全量安全 CSV 导出；后台调度显式选择开启，默认 OFF。
- [x] Java/React 单测覆盖状态、日期限制、Session Owner/CSRF、未核实现金及全局快照标记、CSV 注入边界。
- [ ] **不等于全局原子来源快照**：创建时只冻结订单候选列表，按批各自冻结来源结果；源后续变化需建新任务比对。正式净利润、税费/真实 Ads、可信退货实收和银行到账仍未达标，保持 blocked/null/false。
- [ ] 下一阶段 **V65 FBA 货件、入仓、在途与差异的只读来源对账**。真实 Amazon 授权仍由 Owner 等所有核心代码完成后在本地执行。
- 详细说明：[V64](finance/period-profit-report-v64.md)。

## 2026-11-01 · V64.1 Orval 客户端代码生成（已实现）

- [x] springdoc-openapi 3.2.0 对真实 Spring Boot 4.1.2 Controller 导出九条 V61/V64 利润 API，默认文档关闭且只允许显式开启的 loopback 导出。
- [x] Node24/npm registry 安装 Orval 8.44.0 并提交 package-lock，生成 TypeScript 模型和 TanStack Query5 GET Query/POST Mutation Hooks。
- [x] V61/V64 财务前端改用生成请求函数；统一 same-origin Session、CSRF/no-store/错误和 Orval8 {data,status,headers}，保留 Zod 财务不变量。
- [x] CI 用 npm ci、重新生成漂移检测和 PG18 真实 Controller 契约比对；Java/React/PG18/Compose 四项成功。
- [x] 一次性写权限的 Orval 引导工作流已移除，不留后台自动提交 main 的长期入口。
- [ ] V65 FBA 以及其他 Orders/Finances/Ads 模块还需渐进纳入生成器，不应宣称全部 API 均已自动生成。
- 架构说明：[V64.1 成熟工具优先 ADR](architecture/mature-tool-first-api-client-2026-11-01.md)。
