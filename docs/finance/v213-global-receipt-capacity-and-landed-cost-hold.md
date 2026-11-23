# Merchivra V213 · 原采购收货单的跨订单数量归属与真实落地成本阻断

**2026-11-23 · 无新增 Flyway / 财务过账 / 采购成本回填 · 精确代码 SHA 与 CI/A3 在本轮验收后回填。**

## 主目标：补原批次“跨订单”缺口，不新增第二套业务表

Merchivra 的最终核心是核对真实 Amazon 收入、退款、平台/广告费用、物流、采购与银行成本，在真实资金、批次和会计政策得到独立确认后计算订单/SKU/期间完整净利润。当前仍是 pre-alpha，生产 #104 NO-GO，真正的 `realizedNetProfit=null`。

现有 V106/203 已有不可变原销售批次认领和 PostgreSQL 插入时的**收货单行级锁 + 同批次 QC 接受数量上限**（包括已撤销的认领）；V212 又读取某个退款订单涉及的原销售批次、V34/V210 退款累计数量及 V96/V97 本地返仓候选。**V106 全局超量写入本来已被阻断；V213 不重新发明一条相同的数据库约束。**

V213 补的是**财务读时的跨订单完整性视图**：V212 只返回当前订单行的原始认领，尚未能从财务界面看到**其他订单使用同一采购收货单的全部原始认领**，历史撤销是否仍占容量，当前原采购 reference 币种/单位价格或 QC 接受数量是否发生异常漂移。这些并不意味着真实第三方批次可核证，但能避免把“只看本订单”误当成“该收货批次只由本订单使用”，也能明确落地成本缺哪些证据。

## 新 Owner 只读 API 和隔离边界

`GET /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/refund-original-receipt-capacity`

- 先调用 V212 `RefundBatchLineageServiceV212.preview`，完全复用已有 V210→V203/V106→V34/V96/V97 的独立证据检查；已有的权限、Marketplace 归属和 LIVE 订单约束原样保留。
- 从 V212 所有原销售 `Lot.originalPurchaseReceiptId` 提取**唯一**收货单 ID（最多 1000）；`RefundGlobalReceiptCapacityServiceV213` 在只读 `REPEATABLE READ` 中，对每个采购收货单使用原来的 `finance_sale_lot_claim_v106` 与 `finance_sale_lot_integrity_v106` 视图，**跨全部本地销售订单**执行 `count(*)`、`count(distinct sales_order_id)`、`sum(assigned_quantity)`、原 QC `snapshot_accepted_quantity` 版本数、原采购币种/单价版本数、无效或已撤销 claim 行数。只按 tenant + receipts 过滤，**绝不加当前订单 ID 条件**，也**不排除历史 RETRACTED**（否则同一物理数量可能被虚构为可重用）。
- 总体只允许最多 1000 条原 V106 claim，SQL `LIMIT 1001` 检测完整收货单数量；按所有来源 `globalClaimRows` 再独立限制，超出 HTTP 409，不能展示“前 1000 行”的假完整成本。
- 在独立快照之后再次读取完整 V212 以及相同收货单的全部 V106 当前视图；若任意原件、数量、订单行、成本参考/版本或原批次集合变化，HTTP 409，禁止把不一致证据当作数值。**双读不是原子/密码学签名一致性证明**，可审计但不能开放实际会计入账。
- 对每个原始收货单展示当前订单领取量、所有订单领取量、其他订单领取量、QC 原始接受量、本地未认领接受量、涉及不同销售订单数和 stale/retracted 数。需要声明：**“其他订单/收货批次关联数”是每个收货单独立计数的关系数量，不是跨全部收货单去重后的订单人数**。
- 若任何收货单出现原认领数量超 QC 接受量、原 QC/原采购币种/单位价格版本不唯一、当前 V212 与全局来源参考不一致、历史撤销/当前本地视图失效、当前订单的原认领量高于原始销量，均清楚阻断。

**绝不误报**：同一个真实采购收货单由多个本地订单共同使用只说明批次共享（`ORIGINAL_RECEIPT_SHARED_ACROSS_LOCAL_SALE_ORDERS_NOT_DOUBLE_POSTING`），不是实际库存被重复发货或财务账本发生双重费用记账。采购 PO 单价可能未包含头程运费、关税、支付/汇兑和其他可资本化费用，也没有经过独立供应商/银行核实。不能用参考单价直接乘任何退款件数生成“实际回转 COGS”。

以下字段在 V213 中**无条件固定**：`everyRefundUnitMappedToOriginalReceipt=false`、`independentlyVerifiedActualLandedCosts=false`、`physicalReturnToOriginalReceiptVerified=false`、`originalPurchaseFxAndDutiesVerified=false`、`crossPeriodCogsDoublePostingExcluded=false`、`refundCogsReversalAllowed=false`、`actualRefundCogsReversal=null`、`realizedNetProfit=null`。即使 `completeGlobalLocalReceiptCapacity=true`，也只代表本地数量和参考身份没有明显矛盾。

## Next.js 16 利润中心

复用 V64→V188→V211→V212 订单选择器，在 V212 退款行与本地退货核对下增加 **V213 原采购收货批次跨订单容量**。默认收起，用户主动点“按需核对所有订单的原采购收货容量”才进行可能较重的全量读时核对；不启动 15 秒循环重算。只展示不混币的采购参考单价、可见的本地原始数量和阻断原因，绝不显示已核证实际落地成本。Zod 要求真实财务真值字段必须是 `false/null`，对 409 显示源变更/截断原因，不按零展示成本。

## 测试与真实边界

- `RefundGlobalReceiptCapacityV213Test`：同一收货单被两订单共享且总量在 QC 之内、跨单超出 QC 接受量、撤销 claim 仍占用历史数量、原采购币种/价格或 QC 接受量版本漂移、V212 原单数量小于本地认领、缺来源、重复收货单/跨订单/跨 Marketplace、Owner/no-store 及每个 COGS/净利润仍 null。
- PG18 `MERCHIVRA_V213_DISPOSABLE_ONLY=true python3 scripts/smoke_refund_global_receipt_capacity_v213.py`：在安装现有 Flyway 至 V111 的**真实 PG18** 中使用 Java 原始全局来源 SQL，TEMP 影子 V106 表/视图构造同收货单跨三订单、历史撤销仍占原 QC 容量、不同 USD/EUR 采购参考、不同 tenant、原 QC 版本/价格变更等，最后 ROLLBACK。**TEMP 影子视图不是 V106 触发器的真实外部核证**；V106 真正的数据库触发器回归应继续复用已有 A3，不得把脚本包装成“真实批次 COGS 已有凭据”。
- 完整 A3 精确代码 SHA 的 Java25/React19/Next16/pnpm/Chromium/PG18/HTTP/Compose 7/7，以及同 SHA 新 GET 匿名 401。后续快 CI/文档提交需再次核对具体 SHA，不能借用历史绿灯。

## 下一步 V214 与发布禁令

本地批次容量不能替代真实**落地单价 = 经独立核证的货价+可资本化头程运费/进口税费/其他仓储费用（按批准的 G1 分摊口径与汇率/期间确认）**。下一阶段优先检查 V91/V92 原币费用有没有**唯一可追溯的采购收货单身份**，以及多次退款若涉及多个原批次如何提供**外部退货物理归属证明**；不存在可信证据时只显示阻断/UNKNOWN，而不是 FIFO 猜测、虚构币种或制造数字。没有真实 Amazon Seller/Ads、银行、WMS 的维护者本地授权和 G1/G2/G3、DEC-06/07/08，不做真钱/库存出站与过账。

**评分 70/100；`realizedNetProfit=null`；真实 OAuth OFF；生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) NO-GO。**
