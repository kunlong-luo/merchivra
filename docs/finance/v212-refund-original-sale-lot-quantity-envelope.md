# Merchivra V212 · 退款订单行 × 原销售批次 × 本地退货收货：数量守恒与成本阻断

**2026-11-23 · 无新 Flyway。[V212 精确代码 `921c2e2`](https://github.com/kunlong-luo/merchivra/commit/921c2e250ad4554349fb290e777c650b279fc259) · [同 SHA 快速 CI #45170646181 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45170646181) · [完整 A3 #45170646128（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45170646128)。工程合成验收完成；外部真实财务仍未核证。**

## 业务目标和本轮没有做的事

Merchivra 最重要的能力是基于**可核证的全部收入、亚马逊费用、广告、物流、采购/入库批次和退款回转，最终产生有来源的订单、SKU、期间净利润**。目前缺真实 Amazon/Ads、银行、真实仓库/物流实物确认和 G1 会计政策，因此 `realizedNetProfit=null` 必须保留。

现有 V203 / Flyway V106 已将本地销售订单行绑定到具体拣货/采购批次的**不可变人工认领**，通过 PG18 视图在读取时重算销量、拣货、QC 释放及领取数量；V171/V96 是人工**声称**已退货的数量/条件和独立内部审核；V172/V97 是仓库人工**声称**已收到的本地包裹入库候选；V210 重算同一订单全部已匹配 LIVE 退款事件、V34 审核行及跨事件数量上限；V211 展示订单日期和退款记账期间的差异。

**V212 不能再创建第二个批次表或审核/签名链。** 本轮实现基于已有 V106、V96、V97、V210 的**只读订单行数量包络**，回答：一个商品累计退款多少件、原销售批次本地最多认领多少件、人工声称返仓多少件、实物本地收货候选见证多少件、有什么缺口或历史来源变更？

即使都能对平，这也**不是把某一件退货对应到了某个原销售批次，更不是按 FIFO 猜出来的成本**。V106 原采购单价也可能没有头程运费、关税、换汇和加权入库费用；同一 SKU 的两个采购批次可能币种不同。因此绝不能用 `min/max/pick` 单价与退款件数相乘得到“实际 COGS”。

## 新 API 及范围

```text
GET /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/refund-batch-lineage-readiness
```

沿用 Owner/Session、Tenant/Marketplace/订单范围、CSRF、`Cache-Control: no-store`，只读，没有 Seller/Ads/WMS/银行外部调用和退货成本/库存/利润写入口。

`RefundBatchLineageServiceV212` 按如下顺序运行：

1. V210 `RefundOrderConservationServiceV210.preview`，要求订单 LIVE，所有匹配的产品退款及人工 V34 审核得到完整的数量和来源标记。
2. V203 `OrderLotAssignmentIntegrityServiceV203.preview`，重新核对当前原销售 SKU 映射和 V106 原单/拣货/批次不可变链。**即使 V106 报告本地完整，如果 V203 当前订单 SKU 重映射，则按更严格的 V203 状态阻断。**
3. 用独立只读 `REPEATABLE READ` 事务完整扫描以下三个原有表族，每类最多 **1000** 行、SQL `LIMIT 1001`，超过直接 HTTP 409 且不显示部分合计：
   - 全订单 `finance_refund_item_review`：按 `event_id,external_item_id` 区分多次退款的人工数量与 `RESTOCKED_CLAIMED/NOT_RESTOCKED`。
   - `finance_sale_lot_claim_v106` 联接 `finance_sale_lot_integrity_v106`：每条原订单行的原拣货批次认领、原采购 `receipt_id`、被 PG 自动封存的原采购原币单价和当前本地完整性状态；左连接保留坏/缺失 integrity 的认领以便阻断，不用失效的旧单位重新分配。
   - `finance_customer_return_intake_v97` 左联 `finance_return_receipt_claim_v96`：每个收货候选的原退款事件/订单行/人工收货数量/状态、返仓条件、上级已独立审核的 V171 人工声称数量。**LEFT JOIN 保留失效或找不到上游 Claim 的候选**，绝不能静默漏掉。
4. 事务外再次执行 V210 与 V203，且再次读取三个完整来源集合。发现任何订单/金额/数量/状态/批次/本地包裹变更，返回 **HTTP 409**，不宣称通过。此双读仅尽量降低跨事务漂移风险，**并不取代真正外部一致性快照/不可变记录**。
5. `RefundBatchLineageGuardV212.calculate` 精确比对相同订单行 ID 和每个 `eventId + itemId`：V34 累计退款数量与 V210 完全一致，原销售批次认领数量之和、不同原采购 `receipt_id` 和历史认领撤销标记；按退款行校验所有 PENDING+ATTESTED 包裹在 V34 和 V171 人工申报数量内，REJECTED 不作为实收。只有 ATTESTED_LOCAL 算**本地人工见证**，它不是外部物理收货。
6. 每订单行展示 `reviewedRefundQuantity`、`locallyAttributedSaleQuantity`、`restockClaimedQuantity`、`attestedLocalReturnQuantity`、`pendingLocalReturnQuantity`、`unassignedRefundQuantity=max(0,退款量-原销售批次认领量)`、`unattestedRestockQuantity=max(0,声称返仓量-ATTESTED_LOCAL)`，并列出原始采购批次 IDs 和**只作来源参考**的原币采购单价。

当 V210 退款变更、历史认领被撤销、原 SKU 映射变旧、退款商品原单数量未知、跨事件超量、原本地销售认领不足、声称返仓但没有足够本地 ATTESTED 包裹、重复包裹超过原退款或人工收货 Claim 额度，均有独立 blocker。历史撤销认领仍占 V106 原始物理批次额度，不被自动回收。

**注意** `localAllItemQuantitiesConsistent=true` 仅是内部人工记录在某次读取时未显示数量矛盾，不等于真实 Amazon 履约或仓库退货已经认证。以下字段永不因 V212 本地数量通过而更新：

- `externalAmazonRefundsComplete=false`、`originalAmazonFulfillmentVerified=false`
- `physicalReturnReceiptVerified=false`、`independentlyVerifiedActualBatchCogs=false`
- `accountingRecognitionApproved=false`、`refundCogsReversalAllowed=false`
- **`actualRefundCogsReversal=null`、`realizedNetProfit=null`**，每条原批次的 `actualBatchCogs=null`。

不新增 V112、不生成新货物成本、没有现金或库存过账、未增加第三方“已核实”按钮。

## 前端

在现有 Next.js 利润中心 V64 → V188 → V211 按需读取的期间退款诊断里，提供 V212 订单选择器：只允许选择本次 V211 已列出的涉及退款订单；用户明确选择后才调用 V212（避免并发针对 100 个订单大规模请求）。显示退款审核数量、历史原销售认领、人工返仓声称、本地见证包裹和两类数量缺口，必要时列前 8 个原采购收货单的原币单价参考。Zod 严格检查真实 COGS/利润状态必须保持 false/null；HTTP 409 以来源变动/越界的业务错误提示，不显示 0 元成本。

## 测试与证据

- `RefundBatchLineageV212Test`：两笔部分退款总数 3、两个原销售采购批次 USD/EUR 2+1、V171/V172 本地收货 2；V106 覆盖不足、返仓未见证、pending 不算实收、retracted/重映射、两个包裹声称超量、上游收货 Claim 未审批、重复 V106/V34 source ID、Owner no-store、无实际 COGS。
- `MERCHIVRA_V212_DISPOSABLE_ONLY=true python3 scripts/smoke_refund_batch_lineage_v212.py`：在真实 PostgreSQL 18、Flyway 已有 V106/V96/V97/V111 的隔离实例上，以三个 Java 源 SQL 原文与 TEMP 表合成回归验证作用域、源不重乘、不同币种、坏上游收货 Claim 可见、原 V106 状态再读取可见。**该脚本将现有 V106 integrity 视图用 TEMP 表影子模拟，仅验证 V212 的查询/连接行为；真实 V106 触发器/视图独立测试在 V203/既有 PG18 专项，不能把本脚本说成再次独立验证 V106 原始触发器。** 最后 ROLLBACK。
- 同一代码精确 SHA 跑 Java25/Next16/Chromium/HTTP/PG18/Compose 完整 A3 7/7 和 fast CI，并确保新 GET 匿名 401。文档随后回填准确 SHA 和运行链接，不用之前旧版本测试冒充当前证明。

## 本轮真实提交、验收与修复记录

[V212 精确代码 `921c2e2`](https://github.com/kunlong-luo/merchivra/commit/921c2e250ad4554349fb290e777c650b279fc259) 为最终代码/CI/浏览器检查提交，[同 SHA 快速 CI #45170646181 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45170646181) 已通过；[完整 A3 #45170646128（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45170646128) 首次运行 **7/7 SUCCESS**，包括 Java25、Next16/React19/pnpm、Chromium/Vite/Next 回归、Compose、隔离真实 PG18/Flyway 和 HTTP。

PG18 作业原始日志记录 `PASS V212 PG18 exact V34/V106/V96/V97 source SELECT scope`，并实际执行新 Owner 只读接口的匿名 401 断言。临时脚本虽直接执行 V212 Java 三条 SQL，却以 TEMP 表影子代替 V106 integrity VIEW，仅检验新查询关联和隔离，不重做 V203 对真实 View/触发器的完整性校验。

本轮首次 A3（[#45169687230](https://github.com/kunlong-luo/merchivra/actions/runs/45169687230)）**仅 6/7**，失败的是旧 V15 Owner 邀请表单的 Chromium 同步断言：Next App Router 导航短暂卸载团队 Workspace，列表单次等待成功后直接读取邀请页面可能返回 `undefined`。未删除权限检查，改为等待**同一次挂载中成员列表和实际邀请表单同时存在**；最终 [完整 A3 #45170646128（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45170646128) 的 Chromium 项已验证修复。之前一次修改过长 `ci.yml` 在 Actions 创建 0 个测试 Job 前失败，也不能归类为业务测试运行失败；将 9 个重复匿名安全 URL 变成单个 shell 基础路径后恢复调度，保留全部检查。

**技术数量守恒不能证明退款对应哪个真实采购批次**：本轮不生成 FIFO、原始成本乘积、入库记账或真实净利润。仍须外部 G1/DEC、Amazon/物流/银行真实性来源，评分 70/100、真实 OAuth OFF、生产 NO-GO 和 `realizedNetProfit=null` 原样保留。

## 下一阶段与不变的门禁

下一阶段 V213 优先继续实际财务成本的证据完整性与计算准入：**从真实物流/退货返回原销售批次的逐件映射、独立仓库签收、入库最终含税成本、物流与银行结算、跨期间 G1 决议**，以及已实现多次退款的金额/数量唯一归属。不先得到真实外部来源和 G1 会计批准就不能发布真实退款回转或非 NULL 净利润。

**正式评分 70/100，真实 Amazon Seller/Ads OAuth OFF，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) NO-GO，`realizedNetProfit=null`。**

## V255 · 原认领成本候选版本与退款子区间（内部观察，待验收）

`ReceiptCostCompositionServiceV249.prepareRefundBasis` 在同一独立只读 RR 中重算 V249、读取 V106 租户/Marketplace/receipt/claim/order/item 作用域，提取 V117 原销售区间、原币、数量分母、PO/头程/关税各 receipt 原金额与 claim 分摊；缺失/失效/撤销/费用 hold 拒绝，不另查当前 SKU 均价。`RefundOriginalCostSliceEngineV255` 对固定版本和上述字段做规范 SHA-256，逐来源独立复算原认领金额；退款重放必须显式提供期望作用域、原指纹和同一认领下全部拟议退款子区间。原 receipt 前缀差分复用 V246，禁止重复 event、重叠/越界、错误 item、数量不符、NOT_RESTOCKED 或空集合；同一 event 在同一 claim 仅允许一个连续区间。逐事件及剩余金额守恒，仍只返回未核证候选，实际回转与利润 null。V255 没有持久化快照、完整退款人口/事件存在性或跨调用全局唯一归属证明；RESTOCKED_CLAIMED 仍只是人工声称，SHA 不是签名或销售发生时会计事实。不得依据事后 capture 伪造历史成本。后续需不可变版本保存、V34/V210/V212/V213 原事件/返仓证据和数据库级退款区间守卫；E1/E3/E5 未通过前不可记账。单元回归和 V250 实际 PG18 scope/capture/价格漂移及 replay marker 待新 A3。

## V256 · Flyway V119 不可变本地成本观察与退款区间预留（待验收）

新增 `finance_original_cost_basis_v119`，每租户/原 V117 claim 一次性保存 V255 规范 payload 与 SHA-256、记录者/数据库时间。V249 包内 persistRefundBasis 使用独立可写 RR 同快照生成并插入；无 HTTP 入口、无回填。数据库独立核验摘要、原 scope、数量区间、PO 参考额、V112 费用行/原币/状态、来源唯一/数量与精确微单位金额；不能代替 V216 全审计或外部签证。`finance_refund_cost_slice_v119` 绑定快照摘要、实际本地 V34 RESTOCKED_CLAIMED review、V21 匹配 LIVE PRODUCT_REFUND（测试 LIVE 仅合成标签），数据库覆盖 review revision/quantity/time、item 和 recorded_at。READ COMMITTED + 原 item 行锁跨原认领串行，拒绝重叠/重复 event-claim、超原销售区间、同 event/item 超已审核数量；更新/删除拒绝，历史不释放。V250 实际 PG18 扩展持久化、重复、重叠、越界、删除回归，JUnit marker 强制；尚无双连接 V119 竞争、跨 claim 数量负例、读时失效检查、真实返仓证据、退款事件完整性或实际过账。原观察不是销售时历史 COGS；未来 V34 revision/撤销/关联变化必须读时 hold；不得把预约金额放进利润。

## V257 · 退款预留竞争及读取失效（待验收）

V255 [CI #45569211952](https://github.com/kunlong-luo/merchivra/actions/runs/45569211952) SUCCESS / [A3 #45569211987](https://github.com/kunlong-luo/merchivra/actions/runs/45569211987) 7/7，PG18 job #139051534636 原日志确认 V255 marker。V257 不修改 V119，真实 V250 夹具使用两 JDBC 连接、pg_blocking_pids 确认锁等待：先回滚同区间使竞争者成功；另同 item/不同 pick line 认领，先提交后同 event 另一认领超审核数量拒绝。包内 readRefundReservations 同 RR 比对存储 payload/SHA 与当前重算，核 V34 revision/quantity/time/disposition、V21 关联/来源、同 event/item peer 认领状态与数量，重放或 HOLD/null；不释放预留。仅单 claim 读取，无 HTTP/UI；其他 claim 完整成本不逐一重算，review ABA/恢复仍可能绕过相等检查，无全量退款或物理证明，不入账。JUnit marker 强制，新 A3 待验收。

## V258 · 修复 V119 触发器与全订单原币候选汇总（待验收）

V256 [CI #45569699760](https://github.com/kunlong-luo/merchivra/actions/runs/45569699760) SUCCESS，[A3 #45569699719](https://github.com/kunlong-luo/merchivra/actions/runs/45569699719) 6/7 FAILURE：PG18 job #139053562401 原日志 `column reference "source_amount" is ambiguous`，发生在 V250 Java 第111行 persistRefundBasis；此前 V119 已迁移但写入未通过。追加 Flyway V120 CREATE OR REPLACE，仅将同名变量改为专用变量，不改 V119。V257 快 CI SUCCESS、A3 初查尚未确认。

`readWholeOrderRefundReservations` 单独 RR 内复用 V257 读取及当前 V249/V255 指纹核对；每订单最多50个当前 LIVE item/50个 claim/20个收货单、1000事件/审核/预留，按 receipt 缓存计算。原销售数量必须全部有原 claim，所有 claim（含未退款）都需现行不可变成本；本地匹配 LIVE PRODUCT_REFUND 以及同站点精确外部订单引用全部纳入，未匹配、错归属、非负、未审核、NOT_RESTOCKED/UNKNOWN 或跨事件数量异常阻断（保守不猜未返仓成本）。每 event/item 审核数量与已存全部预留严格相等；缺任一行、成本指纹或审核失效整单 HOLD，currencies 空，不输出部分金额。不同原币分别给原销售候选、退款候选和剩余原候选，不换汇；无外部事件完整性、V35/V63 金额复核、真实退货、ABA 或历史销售时会计成本证明，不能记账。V250 实际 PG18 夹具扩展全单30/14.999999/15.000001，缺第二事件区间 hold，补齐后全退30、审核漂移 hold；JUnit 强制 V258 marker。待新 SHA A3；无 HTTP/UI 入口。
