# BENCH-001 · 开源 Amazon ERP / 采购库存财务源码对标（2026-11-08）

> **结论：参考成熟业务实现，不更换 Merchivra 技术栈，不把参考项目自动升级为依赖。** 本文基于 GitHub 公开仓库的**实际文件和代码**与当前 Merchivra `main` 控制器、服务、既有实施记录比对；不是体验全部 SaaS 功能的验收，不是生产 Amazon 授权、会计税务或开源许可法律意见。它用于收敛 [V108 MVP 计划](../roadmap/mvp-freeze-release-v108.md) 的 A1/A2，不扩充 P0 阻塞项。

## 1. 参考源与许可证 / 技术兼容性

| 项目（实际源码） | 已核实的源文件和特点 | 复用结论 |
| --- | --- | --- |
| [Wimoor ERP](https://github.com/wimoor-erp/wimoor) | [根 README](https://github.com/wimoor-erp/wimoor/blob/main/README.md)、[MIT LICENSE](https://github.com/wimoor-erp/wimoor/blob/main/LICENSE)；包含 `wimoor-erp`、`wimoor-amazon`、`wimoor-amazon-adv`、`wimoor-finance`、`wimoor-ui`；使用 Maven / Spring Cloud / Vue / MySQL 等不同栈 | **Amazon 卖家工作流优先参考**，不复制微服务/数据库/前端基础设施或直接照搬 HTTP 风险模式 |
| [ERPNext](https://github.com/frappe/erpnext) | [采购单状态服务](https://github.com/frappe/erpnext/blob/develop/erpnext/buying/doctype/purchase_order/services/status.py)、[库存流水对象](https://github.com/frappe/erpnext/blob/develop/erpnext/stock/doctype/stock_ledger_entry/stock_ledger_entry.py)；Python/Frappe，GPL-3.0 | **采购收货/账务状态和库存事件不变量参考**；不粘贴 GPL 源码到 Apache-2.0 项目 |
| [metasfresh](https://github.com/metasfresh/metasfresh) | [根 README](https://github.com/metasfresh/metasfresh/blob/new_dawn_uat/README.md) 说明三层业务 ERP + React 前端；仓库分为 `backend`、`frontend`；实际 [前端 GPL-3](https://github.com/metasfresh/metasfresh/blob/new_dawn_uat/frontend/LICENSE.md) / [后端 GPL-2](https://github.com/metasfresh/metasfresh/blob/new_dawn_uat/backend/LICENSE.md) | **采购、物料收货、单据 UI 与领域边界**概念参考，不混入源码/授权不兼容文件 |
| [Amazon SP-API 官方 SDK](https://github.com/amzn/selling-partner-api-sdk) | [官方 README](https://github.com/amzn/selling-partner-api-sdk/blob/main/README.md) 明示 Java SDK 的 basic API、RDT、rate limiter 支持；Apache-2.0；[官方 OpenAPI models](https://github.com/amzn/selling-partner-api-models) 为 Apache-2.0 | **直接维持既有 Gradle 官方 SDK** `software.amazon.spapi:spapi-sdk:1.13.0` 与按操作核对的模型/协议；升级另行独立审查兼容性，不重新自造全部 OAuth/SDK 层 |
| [Amazon 官方示例与本地 Sandbox](https://github.com/amzn/selling-partner-api-samples) | [MIT-0 示例 README](https://github.com/amzn/selling-partner-api-samples/blob/main/README.md)、[本地 SP-API Sandbox](https://github.com/amzn/selling-partner-api-samples/blob/main/local-ai-sandbox/README.md)、[授权生命周期参考](https://github.com/amzn/selling-partner-api-samples/blob/main/use-cases/auth-lifecycle-management/README.md) | 可**评估可复现离线契约校验**；示例不是受支持的生产服务。Sandbox 部分操作可能 passthrough，且 AI 数据生成器依赖 AWS Bedrock，**未经流量隔离与许可证/操作审查不可接入核心 CI** |

**维护活跃度说明**：这些 GitHub 仓库在 2026-11-08 查询时均非 archived，但“非归档 / 有近期提交”不等于功能质量、安全或数据许可已验收。Wimoor 源码技术基线与 Merchivra（Java 25 / Spring Boot 4.1.2 / Gradle / PostgreSQL 18 / React 19 / Next.js 16 App Router 候选）差异较大，绝不整库复制、引入 Maven/Vue 或第二个 ERP 数据模型。许可证参照每个实际文件，不能只看仓库首页。

## 2. Wimoor 的实际业务证据（不只看 README 功能列表）

| 业务 | 可核对源码 | 对本项目的可操作借鉴 |
| --- | --- | --- |
| 采购单多状态、收货、申请付款、付款和单据导出 | [`purchase/form/listApi.js`](https://github.com/wimoor-erp/wimoor/blob/main/wimoor-ui/src/api/erp/purchase/form/listApi.js) 有 `list`、`paymentApply`、`payment`、`receive`、`downExcelReports` 等 API 调用 | 采购记录需具备**真实全量列表入口、当前状态、收货进度和操作证据**；采购付款链可列为 P1 候选，不在 A1 重造财务系统 |
| 采购账户台账、付款明细、对账和导出 | [`api/finance/purchaseLedger.js`](https://github.com/wimoor-erp/wimoor/blob/main/wimoor-ui/src/api/finance/purchaseLedger.js) 有 accounts/payments/pay/reconcile/export API | 作为**业务流程候选**，不可把 Merchivra 当前人工银行“Amazon 回款”误当“供应商应付付款”已实现；任何新应付会计口径需 G1 决策 |
| 采购手工记账、类别、核对、撤销和报表 | [`purchase-bookkeeping-help.md`](https://github.com/wimoor-erp/wimoor/blob/main/init-config/md/purchase-bookkeeping-help.md) | 参考用户操作分层/文档表述；撤销不能删除不可变历史，Merchivra 若新增需以反向事实 + 审计实现 |
| FBA 货件状态分组、物流跟踪和异常筛选 | [`shipment_tracking_functional_analysis.md`](https://github.com/wimoor-erp/wimoor/blob/main/init-config/md/shipment_tracking_functional_analysis.md) | 可优先借鉴**多维筛选与直达异常**的界面交互，但真实承运商轨迹 / Amazon FBA 接受量尚未验证 |
| FBA 每日库存筛选和历史对比 | [`fba_daily_inventory_functional_analysis.md`](https://github.com/wimoor-erp/wimoor/blob/main/init-config/md/fba_daily_inventory_functional_analysis.md) | 待真正 LIVE 完整性通过后再做可信历史趋势/导出；禁止把 UNKNOWN 变 0，不能用 UI 当前页计算全局库存 |

**不应照搬的模式**：Wimoor 采购 API 文件中存在对审批、取消付款等操作使用 GET 请求的例子。Merchivra 继续坚持 Spring Session + CSRF、POST 写操作、Maker/Checker、原币金额、tenant/Marketplace 与可审计来源，不能因为参考系统如此实现而引入 GET 副作用。

## 3. ERPNext / metasfresh 对“采购入库一致性”的提醒

- ERPNext 的 [PO 状态服务](https://github.com/frappe/erpnext/blob/develop/erpnext/buying/doctype/purchase_order/services/status.py) 分别更新**收货进度**和**账单进度**，有修改时间检查，防止编辑已变更单据时盲目覆盖。参考此**业务概念**：`ordered`、`received`、`invoiced`、`paid` 是不同事实；Merchivra 的 `RECEIVED` 不是供应商已付款，也不等于 Amazon FBA 已入仓。
- ERPNext 的 [库存流水](https://github.com/frappe/erpnext/blob/develop/erpnext/stock/doctype/stock_ledger_entry/stock_ledger_entry.py) 保留凭证类型/编号、仓库、批次、发生数量、变更后的数量、估值字段并对倒签时间与负库存做校验。参考此**事件建模思想**：跨仓调拨需关联源/目的操作，盘点和批次放行是不同事实，历史账本不可被当前库存快照覆盖。
- metasfresh 的 [原始代码结构](https://github.com/metasfresh/metasfresh/tree/new_dawn_uat/backend) 可以参考采购/物料和业务 API 的领域划分，但不代表这些 GPL 代码能直接应用于本项目。

## 4. Merchivra 已实现内容 vs 真正缺口（对应当前 main）

| 领域 | Merchivra 实际存在的代码/说明 | 经源码核实的缺口或验收边界 | 本轮决策 |
| --- | --- | --- | --- |
| 采购单状态、供应商、审批、收货 | [`PurchasingController`](../../apps/server/src/main/java/org/merchivra/erp/purchasing/PurchasingController.java) 有 `/orders`、详情、创建、提交、审批、取消、收货；[`PurchasingService`](../../apps/server/src/main/java/org/merchivra/erp/purchasing/PurchasingService.java) 事务检查 ACTIVE 供应商/商品、原币和 PO 收货 | **`orders LIMIT 100`、`suppliers LIMIT 200`；部分业务入口无法操作后续记录**，即使表格有 TanStack 搜索 | **P0 / Issue #62**：新增服务端稳定游标与搜索，并修复供应商选择，保持旧接口兼容 |
| 本地库存、收货、批次、调拨/盘点、异常 | [`WarehouseInventoryService`](../../apps/server/src/main/java/org/merchivra/erp/purchasing/WarehouseInventoryService.java)、[`WarehouseStockOperationService`](../../apps/server/src/main/java/org/merchivra/erp/purchasing/WarehouseStockOperationService.java)、[V47–V52](../purchasing/lot-count-review-transfer-receiving-disposition-v50.md) | **`warehouse-ledger LIMIT 100`、`warehouse-stock LIMIT 500`、`pending-receipts LIMIT 500`**。账本 UI 只显示最近一段，不是全量核对 | **P0 / Issue #62**：tenant+warehouse 游标、来源字段与跨页审计，不能篡改已发生的 signed quantity |
| FBA 货件与采购衔接 | [`FbaInboundController`](../../apps/server/src/main/java/org/merchivra/erp/fba/FbaInboundController.java) 已有 `/page`、发运、收货、差异与审核；[ASN/供应商追踪](../purchasing/supplier-asn-tracking-v43.md) | Amazon FBA/外部物流**真实来源和角色**尚未通过本地授权验收 | A4 **LIVE 准入**而不是创建另一套“货件状态”模块 |
| Amazon Finances、销售贡献、结算与人工银行回款 | [`BankPayoutController`](../../apps/server/src/main/java/org/merchivra/erp/finance/BankPayoutController.java)、[`BankPayoutManualReviewController`](../../apps/server/src/main/java/org/merchivra/erp/finance/BankPayoutManualReviewController.java)、[V60](../finance/manual-bank-payout-v60.md)、[V64](../finance/period-profit-report-v64.md) | 当前人工银行回款是 **`MANUAL_UNVERIFIED`**，`bankVerified=false`、`ledgerPosted=false`；不是已确认银行入账，也**不等于采购供应商应付台账** | A3/A4 保持利润/原币/来源阻断；供应商付款/发票三单匹配作为 P1 产品候选，须有 G1 业务范围后开发 |
| Seller SP-API / Ads / Rate-limit | [Gradle 官方 SDK](../../apps/server/build.gradle)、[现行离线适配](../integrations/sp-api-code-integration.md)、[Ads 独立来源](../finance/ads-independent-source-v66.md) | 没有生产 OAuth 角色和 LIVE 广告/卖家样本验收；官方 SDK 的 rate limiter 不能替代本系统按卖家/operation 的幂等与持久断点 | 保留既有 Java 官方 SDK、PG18 checkpoint 和可选 Redis；A4 最后在维护者本地逐权限联调 |
| Web/体验 | [V107 采购表格说明](purchasing-tables-v107.md)、[Next App Router](next16-ab-migration-v99.md) | 当前 V107 搜索只作用于已加载数据；Next 正式反向代理/Docker 未完成 | A1 真服务器搜索与游标，随后 **P0 / Issue #63** Next 切换。UI 模式参考，不采用 Vue |

**明确不做**：因为竞品有“账户管理 / Excel 导出 / 多级财务 / 自动发货”就插入一套未经核算的财务帐簿或无人批准的 Amazon 写操作。若要增加“采购应付、付款、采购发票”，必须先写独立的资产/负债和冲销/供应商原币口径，考虑采购订单、收货、供应商发票、付款是四种不同来源，避免与 Amazon Settlement 或银行手工回款重复记账。

## 5. MVP 前后的执行决定

### 当前 A1 / A2（不改变已批准优先级）

1. **先修真实历史遗漏**：继续 [P0 Issue #62](https://github.com/kunlong-luo/merchivra/issues/62)，按 `orders → suppliers → warehouse stock/pending → ledger` 的业务操作/审计风险顺序，依 [V108 已核实 LIMIT 清单](../testing/p0-purchasing-read-cap-audit-v108.md) 添加 tenant-bound、filter-bound、稳定 keyset、`hasMore` 和正确前端游标缓存。安全负例当天覆盖，禁止客户端伪造全局计数。
2. **再做 Next 正式迁移**：[P0 #63](https://github.com/kunlong-luo/merchivra/issues/63) 接真实后端 Session、CSRF、Cookie、安全文件访问和 HTTPS 代理，保留 Vite 回退。页面只借鉴成熟 ERP 的高信息密度与任务优先结构。
3. **冻结后集中运行 PG18 + Chromium + Compose**：[V108 里程碑](https://github.com/kunlong-luo/merchivra/actions/workflows/milestone-validation.yml) 在最终候选 SHA 运行；Amazon LIVE/OAuth 仍 OFF。不要因为阅读了开源业务源码，就为每次 docs 改动触发重型 CI。

### Beta 之后候选（**未批准，非当前 P0**）

- **采购供应商应付 / 付款与发票差异**：参考 Wimoor 台账 API、ERPNext 的收货与开票进度，先向 G1 业务负责人明确：预付款、退款/冲正、发票、原币税费、应付余额与对账是否在 MVP 范围。
- **FBA 物流体验增强**：状态标签/异常定位、渠道与日期筛选；基于已证实可访问的承运商数据后才提供真实轨迹。
- **每日库存可信历史图与受控导出**：需要来源完整性、权限和本地后台数据量验证，不能靠一次 UI 当前页推断全库快照。
- **Amazon 示例 Sandbox 评估**：先核对 MIT-0 许可、端点覆盖、模型版本和“pass-through”可能访问生产的边界；隔离 AWS Bedrock/外部依赖，再决定是否与已有离线 Mock 结合。绝不为了示例替换现有经过测试的 Mock/SDK。
- 不引入 Wimoor 的 Maven/Spring Cloud/Nacos/Vue/MySQL/财务库，也不导入 ERPNext/metasfresh GPL 代码。仅参考功能行为、数据源模型和交互原理。

## 6. 每次后续参考的验收规则

- **当前功能存在与否必须看 Merchivra Controller/SQL/页面和已通过 CI**，不能仅根据旧 V20/V60 文档里的“未实现”字眼判定；历史报告和当前状态按 [文档治理](../governance/docs-audit-v109.md) 区分。
- **外部项目的 README/帮助文档只证明项目声称/描述某项功能**；API 文件能证明存在调用入口，但仍不等于外部服务生产质量或完整财务会计正确。遇到竞品源码缺陷只借鉴业务目标，不复制脆弱实现。
- 复用第三方源码或组件之前登记原始仓库、具体文件、commit、许可证、notice、升级与安全维护路径。对 GPL 文件**只读学习，不直接复制**。MIT 文件复制也需保留对应版权/许可信息并经代码评审。
- **不接受** Mock 变 LIVE、Amazon FBA 库存与本地库存混算、供应商收货被当作 Amazon 入仓、采购审批即付款、银行人工材料即入账、不同币种直接相加。
