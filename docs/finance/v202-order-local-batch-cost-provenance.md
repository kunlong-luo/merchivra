# Merchivra V202 · 订单 SKU 对本地收货批次与采购成本来源的只读核对

**状态：代码已提交后需按精确 SHA 运行快 CI / 完整 A3（初始文档阶段）**。本轮是批次 COGS 原始证据审计，不是销售批次归属、会计过账或真实 Amazon 验证。

## 审计现状与不能推断的事实

已有 V20 `erp_purchase_order_line.unit_cost` + 采购订单原币、`erp_purchase_receipt` 数量与时间；V39/V40 本地库存流水（`OUTBOUND` 仅指本地仓库操作）；V44/V46 QC 收货、批次、放行记录；V47 库位及 lot-piece 信息；V73 FBA 入库采购行链接。然而：

1. **没有已验证的 Amazon 销售订单项 → 实际出库 → 特定本地采购批次（receipt/lot/piece）**路径。V73 链接是 *FBA inbound intent ↔ 本地 PO*，不是客户订单 COGS 归属；V39/V40 本地出库操作也没有绑定 Seller 订单行与具体批次。
2. `sku_cost_version` 是人工生效期成本，`erp_purchase_order_line.unit_cost` 是本地采购行单价；两者不是同一种证据，价格相同不能当作同一已售批次。QC 放行是本地库存操作，不是 Amazon 履约与银行确认。
3. 当前外部 WMS/实物身份 G1/G2/G3、Amazon Seller/Ads 和财务口径仍未签署；不能凭临近日期、相同 SKU、可用数量猜测 FIFO，也不能在不同原币间做平均。

## V202 复用且只读的实现

- 新增 `OrderLotProvenanceServiceV202`，**调用现有** `OrderCostReadinessService.preview` 核实租户、Marketplace、真实 LIVE 订单、唯一有效期 SKU 映射、历史成本及原币完整度。若原预览 `INCOMPLETE`，V202 直接显示阻断，**不查询/臆测**本地批次来源。
- 对已 READY 的唯一 `internal_sku`，仅一次按 SKU 聚合只读 SQL：取该租户、该 SKU、`received_at <= 订单 UTC 时间` 的 V20 收货；通过复合租户键连接采购单、采购行、V46 批次、V44 QC 和 `QC_RELEASE` 库存流水，**每笔 receipt 只计一次**，不按订单行复制收货数量。
- 独立计数：旧收货缺 V46 批次、PENDING/INSPECTED/RELEASED，收货的 SKU/仓库/数量与批次不一致，QC 的原 receipt/SKU/仓库/验收数量不一致，释放的 QC ledger 数量/SKU/仓库不一致；只有 QC 数量和独立放行流水完全吻合时统计`observedReleasedLocalUnits`。这仅是「已观察到的本地放行数量」，不是在售数或可归属销售数。
- 不跨币种求和。只有所有匹配的采购订单具有相同 `currency_code`、同一 `unit_cost` 时才展示单个**本地采购单价参考**；不同币种/多单价一律返回 `null`，而不是任意选一个/做汇率折算。
- Owner 租户隔离、`no-store` 的 GET `/api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/lot-provenance`。其 `saleLotAttributed=false`、`actualBatchCogsVerified=false`、`realizedBatchCogs=null` **无条件保持**，并提供必需阻断码 `SALE_TO_LOCAL_RECEIPT_LOT_LINK_NOT_RECORDED`、`FBA_FBM_FULFILLMENT_AND_EXTERNAL_WMS_UNVERIFIED`、`G1_ACTUAL_BATCH_COGS_POSTING_NOT_AUTHORIZED`。
- Next 原订单成本面板内部增加本地采购参考表（不堆新的独立利润卡片），Zod **拒绝**把批次归属/实际 COGS 误报为已确认，业务用户可见“同 SKU 本地收货≠原订单出货批次”。

## 验收边界

- Java `OrderLotProvenanceV202Test`：状态优先顺序、原成本不 READY 时不查采购来源、Owner/no-store、实际 COGS 恒未知。
- Vitest `order-lot-provenance-v202.test.ts`：原币采购参考、未归属、伪造 `saleLotAttributed=true` / 非空 `realizedBatchCogs` 必须拒绝。
- 真 PostgreSQL 18 脚本 `MERCHIVRA_V202_DISPOSABLE_ONLY=true python3 scripts/smoke_local_lot_cost_provenance_v202.py`：提取**原 Java SQL**并运行 TEMP/ROLLBACK，覆盖租户及订单时间、无收货/旧收货、QC hold/inspection/release、QC 错连其它 receipt、批次 SKU 错位、放行流水数量差、同 SKU 的不同采购币种/单价。假数据不进入真实租户、库存/财务表。
- 需要精确 SHA 的快 CI、完整 A3（Java25/Gradle、Next16/React19、Chromium、PG18/Flyway、Spring HTTP、Compose）实际完成后才能写「验收成功」。

## 阻塞和下阶段

下一步应设计并由 G1/G2/G3 核准**销售出库对具体批次的不可变来源证据**、实物仓库主体签名、跨出库事件已用批次件数守恒、采购/运费/关税分配、成本政策及退款 COGS 冲回，再整合可重算利润；在授权前不得靠手动当地库存给 Amazon 订单分配批次。

**真实 Seller/Ads OAuth OFF；`realizedNetProfit=null`；生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) NO-GO。** V200 六维 [70/100](../governance/project-scorecard-v200-2026-11-22.md) 仍为最新正式评分；V202 的本地合成诊断不能自动上调真实利润项。
