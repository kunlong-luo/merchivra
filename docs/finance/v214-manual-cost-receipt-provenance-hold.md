# Merchivra V214 · V91/V92 人工费用到原采购收货单的身份缺口审计

**2026-11-23 · 只读诊断 / 无新 Flyway / 无实际 COGS 或会计过账。** [V214 精确代码 `9de829f`](https://github.com/kunlong-luo/merchivra/commit/9de829fd8d302663e7589c80b3ec5c920a5ea949) · [同 SHA 快速 CI #45193406175 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45193406175)；Java 25 Gradle、Next.js、Compose 成功，PG18 快速 CI 专项 SKIPPED，完整 A3 尚未执行。本文件不把缺席的真实 PG18/HTTP/A3 验证写成成功。

## 已确认的数据库事实

- V20 `erp_purchase_receipt.id` 是本地采购收货的身份；V106 `finance_sale_lot_claim_v106.receipt_id` 将本地销售声明追溯到收货单，V213 聚合全订单数量，但原采购 PO 单价不是最终 landed cost。
- V91 `finance_cost_source_v91` 仅有 tenant、Marketplace、类别、原币、正金额、业务日期、人工声明来源键/文档摘要及独立内部审核，**没有 `purchase_receipt_id`**；`REVIEWED_UNVERIFIED` 不是外部发票/银行/物流真伪认证。
- V92 `finance_cost_allocation_batch_v92` 的目标仅 `SKU`/`ORDER`/`PERIOD`，明细也没有原采购收货单目标；唯一活跃分配约束阻止同一 V91 来源同时有两套活跃方案，但不能反推运费/关税实际应归哪张收货单。`APPROVED_UNVERIFIED` 永远不等于 G1 会计批准或实际 COGS。
- V186 已在期间层面按 V91 来源唯一归属，避免把 SKU、订单和期间分摊重复扣减。V214 **不新建费用账本、不把任何 V92 目标重命名成 receipt**；跨 Marketplace、币种、批次的费用无法仅凭日期/SKU/采购单号猜测关联。

## 实现：后端 Owner/Session/no-store 只读诊断

`GET /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/refund-original-receipt-cost-provenance`

1. 复用 V213 全量 V212→V106 收货批次守恒校验；如 V213 来源变化/超界/租户或订单身份不符，原样 fail closed。
2. 从同一租户和 Marketplace 的 V91 读取 `INBOUND_FREIGHT`、`IMPORT_DUTY`、`PAYMENT_AND_FX` 三类**候选人工元数据**，以 V92 唯一活跃方案 LEFT JOIN 得到其 `PENDING_REVIEW` / `APPROVED_UNVERIFIED` 与 `SKU/ORDER/PERIOD` 维度，不取金额、不 join 任一采购收货单。此类别集合不代表所有费用都可资本化，也不代表覆盖完整 landed cost。
3. `ORDER BY s.id,b.id LIMIT 1001`，最多允许 1000 行完整候选；超过 HTTP 409。重复 source ID、无法识别的审核/分摊状态、孤儿 V92 状态均 fail closed。V213 前后分别使用独立 RR 只读快照读取 V91/V92，发现候选行变化则 HTTP 409。双读**不是单一原子审计或外部签名**。
4. 返回三个类别的 `manualSourceCount`、`locallyReviewedUnverifiedCount`、`activeAllocationCount`、`approvedUnverifiedAllocationCount`，其作用只是判断 Marketplace **是否已有人工候选材料**；`0` 不是无运费、无进口税或真实成本为零。没有每采购收货单关联量、没有人民币/美元隐式换汇，也不显示猜测的每件金额。
5. 固定 `v91V92PurchaseReceiptIdentityLinked=false`、`externalFreightDutyAndFxVerified=false`、`verifiedLandedReceiptCostReady=false`、`verifiedLandedReceiptCost=null`、`refundCogsReversalAllowed=false`、`actualRefundCogsReversal=null`、`realizedNetProfit=null`。V213 外部来源不全、逐件退货原批次未知和 G1 未签仍保持阻断。

## 验证与保留限制

- `RefundReceiptCostProvenanceV214Test`：多费用类别与不同 V92 状态；独立人工审核仍不等于真实成本；无费用元数据为 UNKNOWN 非 0；重复源、错误类别、孤儿分摊、旧净利润非空 fail closed；SQL 明确限定 Marketplace，不存在伪造 receipt join；Controller 复用 TenantAccessService 并 `Cache-Control: no-store`。
- V214 代码 SHA `9de829f` 的快速 CI #45193406175 已 SUCCESS（Java25/Next16/Compose），但其中 PG18 为 SKIPPED。涉及金融边界应在相同最终代码 SHA 上单独运行真实隔离 PG18/HTTP/Chromium/Compose 完整 A3；不能借 V213 #45177212593 的历史绿色作为 V214 通过。
- 前端尚未将新只读路由接入 Next.js；当前 UI 仍显示 V213 的收货容量诊断。新路由是后端证据缺口输出，不是新增已核证报表。

## V215 / 发布前必要证据

在 G1 定义可资本化费用、税费/FX/期间与保守尾差规则，并经外部机构独立认证后，才可设计收货单级真实费用归属：不可变外部单据身份/版本、唯一费用来源、同租户收货主键 FK、跨多个收货单原币金额守恒、历史更正与独立复核、跨期退款回转、避免与 V92/V186 双计。没有这些证明不应插入猜测的 receipt-level 金额或解锁利润。

**正式评分 70/100；真实 Amazon Seller/Ads/银行 OFF；`realizedNetProfit=null`；生产 #104 NO-GO。**
