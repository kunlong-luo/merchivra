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

## V259 · 全单原始退款金额与 V35 覆盖门禁（待验收）

在 V258 同一只读 RR 中，限定相关 LIVE PRODUCT_REFUND 的 V35 `finance_refund_amount_allocation` 全人口（LIMIT 1001，超1000 hold），与 V34 event/item 集合严格相等；复用 `RefundAmountAllocationEngine.evaluate` 对每个原始事件的负向金额、原币与分摊合计独立核对。部分、缺失、错币/符号或重复均不返回任何订单成本候选金额。扩展 V250 真 PG18 合成夹具：V35 完整时保持 V258 原币候选；-19 对 -20 及删除 V35 行时 HOLD，恢复后候选恢复。此处比较的是原始 PRODUCT_REFUND 金额，并未调用 V22 分类或验证 V63 独立审核、V35 历史变更/审计、外部完整性和物理退货，不等于真实退款成本或利润。无新迁移、API、目录或过账，CI/A3 待核对，actualRefundCogsReversal/realizedNetProfit=null。


V258 验收回填：[代码 SHA 97ee40d99c1d](https://github.com/kunlong-luo/merchivra/commit/97ee40d99c1dec7f1bc9f2118fa1e8a7219c18e3)、[快 CI #45570749711 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45570749711)、[完整 A3 #45570749671 SUCCESS 7/7](https://github.com/kunlong-luo/merchivra/actions/runs/45570749671)，PG18 job #139057514459 成功。V259 [代码 SHA 3610ac70bae6](https://github.com/kunlong-luo/merchivra/commit/3610ac70bae682070cb2cd249db73ed185cce63c) [快 CI #45571409601 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45571409601)，该 SHA 未见完整 A3，不能把 V258 结果用于 V259。

## V260 · 历史 SKU/原销售批次门禁（待验收）


V260：全单候选在同一独立 RR 中复用 V203 preview（V29 历史成本和销售时 SKU 映射、V106 批次完整性），逐 claim 对齐 ID、订单行、原 receipt、数量、effectiveState；缺历史成本、映射重定向或任一 claim 失效则整单 HOLD，金额为空。底层 scope/conflict 异常仍失败封闭，不在参与事务内部吞掉异常。扩展 V250 真实 PG18 合成回归：缺映射 hold，补历史成本及映射后恢复候选，改到另一个同价有效 SKU hold，恢复原映射后候选恢复；保留 V259 金额负例并要求新 JUnit marker。V260 完整 A3 待验收；该门禁不证明销售时实际 COGS 或 ABA，不包含 V63/外部物理返仓/G1，实际回转与利润仍 null。无新迁移/API/目录。


V260 验收回填：[代码 SHA f0b020b80aba](https://github.com/kunlong-luo/merchivra/commit/f0b020b80abada92da7faed7c2f3a7e013070cfd)、[快 CI #45572837644 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45572837644)、[完整 A3 #45572837650 SUCCESS 7/7](https://github.com/kunlong-luo/merchivra/actions/runs/45572837650)，PG18 job #139063491336 成功（包含 V259 金额回归）。

## V261 · V63 独立审核和当前审计门禁（待验收）

V261：单 RR 全单候选在 V259 金额、V260 SKU 和 V119 预留完整后，复用 V63 preview 重算证据 SHA 与 V197 source parity；每订单最多100个 V63 事件，要求 REVIEWED/current、原币一致和完整本地金额。V63 同 revision 精确请求/决策审计二条，actor/digest/time 匹配，审核人不得为当前 V34/V35 作者。缺审、待审、变更、重复审计或同人冲突整单 HOLD，参与事务的业务异常仍失败封闭。V250 实际 PG18 夹具扩展真实 V63 request/decide、未审/待审、V35 revision 改变、同作者、重复审计及新审核恢复；JUnit marker 强制，待新 SHA A3。尚无跨修订完整审计/不可重用版本、ABA、现行 Owner 资格重核、真实返仓或外部签发证明。旧审计不能代替 G1，不实际过账，realizedNetProfit=null。无新迁移或 API。


## V262 · V34/V35 不可重用行版本与 V63 指纹升级（待验收）

V262：追加 Flyway V121，V34/V35 每行增加数据库全局序列不可重用版本；任何实际 UPDATE/INSERT（含恢复原值、删除重建、伪造旧版本）重分配，完全 no-op 保持，回滚不改变已提交版本。V63 证据摘要显式版本化并绑定同 RR 的两组有界行版本，旧 V63 指纹不能自动升级，必须重新发起并独立审批。V250 真实 PG18 夹具增加金额/作者/数量 ABA、撤回重建（携旧版本）、no-op、rollback 和复审恢复，JUnit marker 强制；V261 既有审计负例保留。仅覆盖 V34/V35 行生命周期，不涵盖 SKU/成本/源分类/订单/授权 ABA、管理员篡改序列或 V119 预留版本；不改历史迁移或原金额，不增加 API/UI。V261 CI/A3 尚未最终核对，V262 待独立 A3。真实返仓与外部来源/G1 缺失，实际回转与 realizedNetProfit=null。


V261 [代码 e76e520fe904](https://github.com/kunlong-luo/merchivra/commit/e76e520fe904ec8b2e630b83447eef5a1196f151) [快 CI #45576590997 CANCELLED](https://github.com/kunlong-luo/merchivra/actions/runs/45576590997)，[A3 #45576590955](https://github.com/kunlong-luo/merchivra/actions/runs/45576590955) 核对时仍运行，6 个 job 成功、PG18 未结束。V262 [代码 40071cb9a80d](https://github.com/kunlong-luo/merchivra/commit/40071cb9a80d2f0cdc37ccba92280a427d4f6f30) [快 CI #45576841371 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45576841371)，[A3 #45576841081](https://github.com/kunlong-luo/merchivra/actions/runs/45576841081) 待最终核对。

## V263 · V119 预留与 V34 证据版本（待验收）

V263：Flyway V122 追加 V119 不可变预留的 V34 evidence_revision_v121 捕获列。V119 原有 READ COMMITTED 订单行锁与源/审核行共享锁继续有效，按触发器名字顺序在既有捕获后核实身份并覆盖调用者版本；同 event/item 旧/不同版本 peer 阻断新预留。读取同 RR 比较捕获版本和当前 V34 版本及 peer，旧 NULL 一律 HOLD、不回填、不释放区间。V34 ABA 即使 V63 新复审也不能恢复原预留；V35 更新仍由 V63 重审处理，不绑定到早于 V35 的 V119 捕获。V250 PG18 增加版本匹配和永久 HOLD 回归，保留其他正例与竞争测试；新 marker 强制，待 A3。无撤销/替换协议，历史预留可能需后续经批准的补偿契约；不覆盖源/SKU/成本/授权 ABA，现行 Owner 资格待补，真实外部原件/返仓/G1 缺失。无新 API/UI、旧迁移不改，actualRefundCogsReversal/realizedNetProfit=null。


V261 [代码 e76e520fe904](https://github.com/kunlong-luo/merchivra/commit/e76e520fe904ec8b2e630b83447eef5a1196f151) [A3 #45576590955 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45576590955) 已完成；同 SHA 快 CI 取消。V263 [代码 4f1fcc7f999a](https://github.com/kunlong-luo/merchivra/commit/4f1fcc7f999aeb4d22a60ec53600d95efa8b3531) [快 CI #45577315150 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45577315150)，[A3 #45577315191](https://github.com/kunlong-luo/merchivra/actions/runs/45577315191) 尚未完成；V262 A3 尚未确认。

## V264 · 当前 Owner 与撤销审计门禁（待验收）

V264：V63 request/decide 复用 TenantAccessService 同等 active tenant/user/membership + 全局 OWNER 资格条件；decide 重核请求人当前资格与 request 后 OWNER_REVOKED 审计，历史失效 pending 可重新发起；全订单 read 在原 RR 内同时核查两位 Owner 与各自 request/decision 之后的撤销记录。V250 PG18 合成夹具扩展非 Owner、禁用用户、撤销角色、仅 seller-scoped Owner、membership revoke、待审请求人撤销后恢复及重新审核，强制 JUnit marker，待 A3。没有新 API/UI/迁移；不是全域授权版本或并发撤销线性化保证，未审计的直接数据库 ABA 尚未封闭。V119 stale/legacy 预留继续不可释放，缺乏经批准的补偿规则不开放替换/释放，下一步须明确补偿权限、幂等、不可重复归属及原始区间守恒后再实施；真实外部原件/返仓/G1 缺失，实际回转/realizedNetProfit=null。


V262 [代码 40071cb9a80d](https://github.com/kunlong-luo/merchivra/commit/40071cb9a80d2f0cdc37ccba92280a427d4f6f30) [A3 #45576841081 SUCCESS 7/7](https://github.com/kunlong-luo/merchivra/actions/runs/45576841081)，PG18 #139078036767 成功。V263 [A3 #45577315191 CANCELLED](https://github.com/kunlong-luo/merchivra/actions/runs/45577315191)，快 CI 成功，不记为完整通过。V264 [代码 9802fad16e46](https://github.com/kunlong-luo/merchivra/commit/9802fad16e466e4d95f02837b587a70cc8e8b81a) [快 CI #45577963575 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45577963575)，[A3 #45577963556](https://github.com/kunlong-luo/merchivra/actions/runs/45577963556) 待最终核对。

## V265 · 同区间补偿预检（待验收）

V265：内部 RR 同区间补偿预检，限定单 tenant/marketplace/event/item、最多50旧 claim/20 receipt；必须完整覆盖该 item 当前 V34 数量、同一已失效非 NULL 旧版本、旧数量不变、当前 V63 独立审核及原币/订单一致、V203 和 V249/V255 原批次成本指纹仍一致。逐原区间重算原币候选，提案 SHA 包含原身份/区间/指纹/旧与新数量版本/当前 V63 revision。相同快照输出确定性提案，复审 revision 变化生成新 ID；不是写入幂等键或审批。现行预留、历史 NULL、数量改变、来源或成本冲突不生成此类提案。V250 PG18 回归覆盖 current hold、复审前 hold、复审后同坐标 dry run、跨租户、重复读和新审核 revision；新 marker 强制，待 A3。没有迁移/HTTP/UI、没有写入提案/撤销/释放/替换、没有整单补偿激活或实际过账。旧预留依旧 HOLD 并占用区间；真实外部材料/G1 未签，actualRefundCogsReversal/realizedNetProfit=null。后续审批、持久化幂等、全单守恒、并发复核和激活必须另行验收。


V264 [A3 #45577963556 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45577963556) 已完成；V265 [代码 9b8218f9064c](https://github.com/kunlong-luo/merchivra/commit/9b8218f9064c70ac340ba03f06b5ef9c859b5772) [快 CI #45578849046 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45578849046)，[A3 #45578849118](https://github.com/kunlong-luo/merchivra/actions/runs/45578849118) 核对时运行中。

## V266 · 不可变补偿提案与独立批准（待验收）

V266：追加 Flyway V123 不可变同区间补偿提案及单独批准表；主键 tenant/proposal SHA，原 V265 canonical payload 独立 SQL SHA256 核对，另存 V63 revision/digest、scope、actor、理由及时间。内部 request/approve 各在独立 RR 重算 V265，校验 expected SHA、当前 Owner，审批人与提案人、V63 两位审核者及当前 V34/V35 作者均不同，已记录的请求人撤销阻断。相同当前快照、同人同理由的重复提交/批准复用记录；冲突理由/actor/过期摘要拒绝；数据库唯一键阻止重复行，RR 并发冲突可能返回 serialization failure，调用者须完整重试，尚无两连接并发回归。V250 PG18 加入真实持久化、重复、独立性、不可变 UPDATE/DELETE、复审过期与无激活场景，强制 JUnit marker，待 A3。批准是历史本地证据，不是激活令牌；不读取为有效预留，不修改/释放 V119，不支持整单补偿守恒或跨期记账，不开放 HTTP/UI。未经 G1/DEC 批准、外部真实材料、授权连续性/并发验收前保持 actualRefundCogsReversal/realizedNetProfit=null。


V266 [代码 d39c2e79c15f](https://github.com/kunlong-luo/merchivra/commit/d39c2e79c15fcb4ed185473172f9b114611f4d71) [快 CI #45579559372 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45579559372) [完整 A3 #45579559266 SUCCESS 7/7](https://github.com/kunlong-luo/merchivra/actions/runs/45579559266)，实际 PG18 job #139085964217 成功。

## V267 · 补偿批准当前有效性（待验收）

V267：同 RR 独立预检已记录的 V123 补偿批准，复算 V265/V63、核对当前 proposal SHA、payload、review revision/digest、双方 Owner、审批人与 V63/当前 V34/V35 作者独立、批准时间不早于请求、请求/批准后 OWNER_REVOKED 记录。record/approve 幂等重读复用同一门禁；撤销后恢复资格不能复活原批准，重新 V63 复核产生新的提案与批准，保留原记录。V250 实际 PG18 新增未批准、正常重复读、跨租户、账号禁用、审核人撤销恢复、复审新 ID 场景，强制 marker；待新 A3。无新迁移/API/UI、无激活令牌或实际释放、替换。不是全域授权不可重用版本或并发撤销线性化；V266 RR 并发幂等仍需两连接回归、整单补偿守恒及 G1/DEC 授权。旧预留继续 HOLD，actualRefundCogsReversal/realizedNetProfit=null。


V265 [代码 9b8218f9064c](https://github.com/kunlong-luo/merchivra/commit/9b8218f9064c70ac340ba03f06b5ef9c859b5772) [快 CI #45578849046 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45578849046) [A3 #45578849118 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45578849118)。V267 [代码 f6ad63c11e52](https://github.com/kunlong-luo/merchivra/commit/f6ad63c11e52719432e6516574b34618adb83357) [快 CI #45583165883 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45583165883) [A3 #45583165906 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45583165906)。

## V268 · 补偿并发整事务重试（待验收）

V268：V123 请求/批准独立 REQUIRES_NEW RR 写事务仅对 PostgreSQL SQLSTATE 40001 完整重试，最多3次总尝试，每次重新计算当前 V265/V63、角色与撤销状态、原指纹及幂等 actor/reason；耗尽返回409，业务冲突/权限/其他 SQL 异常不重试。不降低隔离级别，不重复记录；无激活/释放/替换。V250 PG18 夹具使用独立 gate 连接 SHARE table lock 强制两真实 service RR insert 等待，pg_blocking_pids 证实并发，同请求/同批准返回同 receipt、不同批准理由只一成功一409，并检查行数；新 JUnit marker 强制，待 A3。此为本地合成并发契约，不是全域授权线性化或补偿全单守恒、正式会计批准。后续需全单只读补偿组合与跨事件/批次守恒，E1/G1 外部真实材料优先，actualRefundCogsReversal/realizedNetProfit=null。


V268 [代码 bfbc124ca446](https://github.com/kunlong-luo/merchivra/commit/bfbc124ca446ce63b3b8fb0afac25baf1e1f1ec0) [快 CI #45586332715 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45586332715)，[A3 #45586332750](https://github.com/kunlong-luo/merchivra/actions/runs/45586332750) 核对时未结束。

## V269 · 显式整单补偿守恒模拟（待验收）

V269：内部显式选择最多20个已批准 event/item/proposal SHA，在单 RR 复用 V258–V267 整单人口/原币/历史 SKU/原成本及审核校验；V123 当前批准复核后仅对精确 stale claim/event 原坐标模拟补偿，保留其余 V119 通用条件、旧 peer 一致、原成本 digest、原区间与逐事件金额，必须逐一消费所有选定坐标、整单 event/item 数量守恒、原币成本守恒。普通读取仍无选择、stale HOLD；模拟结果单独标识 LOCAL_ORDER_COMPENSATION_SIMULATION_UNVERIFIED、COMPENSATION_NOT_ACTIVATED，未批准、缺覆盖、重复/过期/跨租户或其他证据失效不得产生完整候选，部分业务异常仍以403/404/409传播。V250 PG18 夹具两 claim 两事件，旧 A 已批准补偿 + 当前 B，正例与旧指纹/重复/跨租户/另一个 stale 负例，强制 marker，待 A3。没有写入、释放、替换、激活、公开 API/UI 或正式会计回转；未新增迁移。不是实际外部完整性、全域授权 ABA 或跨期会计批准，actualRefundCogsReversal/realizedNetProfit=null。
