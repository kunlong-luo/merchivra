# V97 — 采购人工跟进、供应商履约证据评分与结算参考完整分页

## 已核实的旧上限

- `PurchaseFollowupService.findings()`：租户内人工采购催办前 **100** 条，后续 OPEN / ACKNOWLEDGED 的认领、DONE、重开按钮被隐藏；筛选 ACTIVE 与 ALL 都受限。
- `SupplierPerformanceService.scorecards()`：前 **200** 家 ACTIVE 供应商；后续 90 天的 ASN/QC 临时证据评分无法阅读。
- `SettlementStatementService.list()`：每站点前 **50** 张人工参考结算单。老账单从下拉选项消失，后续无法查看、对账或解除分配。
- `SettlementStatementService.candidates()`：单结算参考的前 **100** 个尚未分配的同站点、同币种、同期间且 `SPAPI_FINANCES/LIVE` 的来源交易。受信来源在此处是现有 provenance 断言，**不是**银行已到账断言。
- `SettlementStatementService.auditHistory()`：每参考单前 **100** 条审计；旧历史无法完整追溯。

本轮继续保留旧数组接口，确保其他已完成模块不破坏 API；所有真正交互的 React 工作台切换至游标页。无需引入新队列、Redis、Maven、Vue 或数据库实体；复用 `LocalKeysetPage`、`CursorPageControls`、Spring JDBC、Zod、TanStack Query。

## 新接口

| 路径后缀 | 页内排序及过滤 | 响应 |
| --- | --- | --- |
| `/api/v1/purchasing/supplier-performance/scorecards/page` | 租户内 ACTIVE 供应商，`display_name ASC,id ASC` | `items,nextCursor,hasMore,pageSize` |
| `/api/v1/purchasing/supplier-performance/followups/page?filter=ACTIVE\|ALL` | 租户内 OPEN→ACKNOWLEDGED→DONE→CLEARED + `changed_at DESC,id DESC` | 同上 |
| `/api/v1/finance/marketplaces/{mp}/settlements/page` | 租户/站点内 `created_at DESC,id DESC` | 同上 |
| `/{statement}/candidates/page` | `SPAPI_FINANCES/LIVE`、同租户站点/币种/期间、全局未分配；原始来源 ID 精确匹配优先于无匹配，`matched rank ASC,posted_at DESC,id DESC` | 同上 |
| `/{statement}/audit/page` | 先校验结算参考确属当前租户与站点，再按 `occurred_at DESC,id DESC` | 同上 |

所有分页默认 50，限 1–100，使用 `Cache-Control: no-store`。游标绑定租户与筛选或对应的 Marketplace、参考账单和来源匹配等级，错用游标返回 400。不以当前 50 条结果推断全表事实或试图通过部分列表更新持久金额。

React 的采购催办与供应商评分表可以翻页，审批/认领/重开后刷新至第一页；结算参考下拉框可翻页，切换到不同结算单时清空旧候选和审计游标；每次分配、撤销分配后返回最新第一页，避免继续用过期的候选交易页。旧关联事件的原始金额与手工参考的净额均保持现有签名数精度，绝不跨币种相加。

## CI 实测设计

`scripts/smoke_supplier_finance_settlement_pages_v97.py` 接续 V96 后在隔离 PG18 运行：

1. 插入 225 家无证据的纯本地样本供应商；证明旧服务前 200，分页 225 全量且不虚构评分。
2. 基于已有 V92 441 行测试采购单，插入 180 条跟进（130 OPEN、30 ACKNOWLEDGED、20 DONE）；证明旧前 100、分页可遍历全部，在第 101 条以后实际认领并记录审计。审批时间只补到**一次性 V92 测试采购单**，不会操作真实采购单。
3. 插入 125 张 `MANUAL_UNVERIFIED` 的人工参考账单；证明旧前 50、分页可选择旧批次。
4. 向本地测试数据库插入 170 条明确标记 `V97-LOCAL-FICTION` 的合成财务来源，测试环境中模仿 `SPAPI_FINANCES/LIVE` 的**数据库过滤字段**。这不是 Amazon 真实数据，不会调用真实 SP-API；其中 65 条具有与旧参考单一致的结算 ID，验证精确匹配优先及跨匹配等级分页。第 146 条的来源仍可明确分配、幂等重试并撤销；服务器的唯一来源分配约束保持不变。
5. 通过实际已存在的内部 HTTP 分配/解除 API 产生 106 条真实本地动作审计（连同参考单 CREATED 共 107 条），验证旧前 100 条与全部历史页，不插入虚假的审批历史。
6. 检查匿名访问 401、筛选和页长 400、不同结算单/站点游标拒绝，以及库存流水和采购单数量未变。

**警示：**本地 Mock 假设的 `LIVE` 字段仅用于模拟已定义的数据库条件，不能证明曾获得外部 Amazon 数据，不能用于银行对账、供应商结算、真实资金到账或会计记账。人工参考结算单始终标记 `MANUAL_UNVERIFIED`；无真实 Amazon OAuth、SP-API 远端调用或写入。

## 后续关注

- `BankPayoutManualReviewService.history` / `SettlementSourceReviewService.history` / `RefundItemReviewService.history` 各有 100 条界限，属于单案审计；若继续 V98，可统一按相同模型补全。
- `SettlementStatementService.preview` 的 `linkedEvents` 目前全量读取，未来可把摘要与可解除操作的事件列表拆开分页，避免大结算单的页面过载。
