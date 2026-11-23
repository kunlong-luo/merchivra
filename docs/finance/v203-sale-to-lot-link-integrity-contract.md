# Merchivra V203 · 销售出库 ↔ 采购收货批次不可变归属声明契约

**2026-11-23；本地合成工程验收完成：[精确代码 `3d94550`](https://github.com/kunlong-luo/merchivra/commit/3d94550b9a2ef5a53c15db358b390ed88b60478b) · [同 SHA 快 CI #45116800143 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45116800143) · [完整 A3 #45116800085（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45116800085)。这些是精确代码提交的工程验证，不是实际 Amazon/WMS 发货真伪或 G1 批准。** 本阶段建立能够独立重算的**本地归属声明**契约，不宣称 Amazon/FBA/FBM 实际履约、外部仓库主体已核证，也不授权任何实物批次 COGS 会计过账。

## 复用与身份闭环

- 销售来源：V2/V23 `sales_order → order_item`，必须同租户、指定 Marketplace、当前 LIVE item；V200 的历史有效期 SKU/原币成本准备度在 Java 再独立检查。
- 本地出库：V48 `erp_local_lot_pick_line → erp_local_lot_pick → erp_local_stock_operation (OUTBOUND)`，并由 V48/V49 `position → V46 batch → V20 purchase_receipt → purchase_order_line` 追溯来源；`QC_RELEASE` 和 `OUTBOUND` 的库存流水只证明本地记录的操作。
- V106 新表 `finance_sale_lot_claim_v106` 将销售订单项、pick line、receipt 和声称的外部 dispatch 标识及 payload SHA-256 关联。触发器在数据库内部冻结订单、pick、批次 QC、采购行单价/币种等快照，客户端传入的伪快照将被覆盖；旧 V20–V105 数据**不回填，不猜 FIFO**。
- SHA-256 是**声称的外部消息摘要**，不证明其出自真实 Amazon 或独立 WMS；合规验证后才可能引入独立核验通道，此阶段 `operator_identity_verified`、`platform_fulfillment_verified`、`actual_batch_cogs_verified`、`cogs_posted` 均有数据库级 `CHECK(...=FALSE)`。

## 数量守恒、唯一归属与并发

- 同一批次可被多个 pick line 合法部分消耗，同一个 pick line 可以分给多个订单项，但**同一订单项 + pick line**组合不可重复，同一 dispatch 标识 + pick line 不可重复。
- 在追加声明的事务中按固定顺序锁住 **订单项 → pick line → receipt**，再按租户、整项/整行/整收货来源分别加总**所有历史声明**：`SUM(assigned_quantity) <= 当前有效订单项数量`、`<= pick line.quantity` 和 `<= QC.accepted_quantity`。不是按展示页求和，不允许跨租户数量混算。
- 由 `finance_sale_lot_retraction_v106` 以独立追加事件记录撤销。声明与撤销均不可 UPDATE/DELETE；**撤销不会释放已经使用的物理件数**，避免以撤销为手段再次分配同一批库存。未来争议重归属需要受控的独立裁决与补偿设计，不在此阶段伪造。

## 独立读取与证据失效

- `finance_sale_lot_integrity_v106` 是可直接在 PostgreSQL 18 查询的独立重算视图。它对每次读取重新核对当前 sale item、订单源更新时间、pick/operation/OUTBOUND 流水、仓位/采购收货、批次 QC 与 QC_RELEASE、PO 单价/币种，并分别统计 item、pick line、receipt 的**全历史声明数量**。
- 返回 `RETRACTED`、`SALE_EVIDENCE_STALE`、`PICK_OUTBOUND_EVIDENCE_STALE`、`PURCHASE_BATCH_EVIDENCE_STALE`、`QUANTITY_CONSERVATION_BROKEN` 或 `LOCAL_LINK_INTACT_UNVERIFIED`。即使本地身份完整，状态名称仍显式为 **UNVERIFIED**。
- Owner/no-store GET `/api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/sale-lot-integrity` 复用租户限制、V200 历史 SKU 映射；源状态或 SKU 不一致再次标记为 STALE。该路由没有 POST / PUT / 过账功能。**所有响应均固定** `saleLotAttributed=false`、`actualBatchCogsVerified=false`、`realizedBatchCogs=null`；不能把 `LOCAL_LINK_INTACT_UNVERIFIED` 当作实际成本。

## 技术验收

- Java `OrderLotAssignmentIntegrityV203Test`：无声明不能确认 COGS、独立状态覆盖、历史 SKU 漂移、Owner/no-store、无 INSERT/UPDATE read API。
- `MERCHIVRA_V203_DISPOSABLE_ONLY=true python3 scripts/smoke_sale_lot_assignment_v203.py` 在**已执行 Flyway V106 的隔离 PG18** 上，使用真实触发器 + 完整原始视图 SQL，建立 TEMP 阴影表并整体 ROLLBACK；负例覆盖重复 pick、跨 pick item 超额、跨事件 receipt 超额、订单数量后改、QC 验收件数漂移、pick 改量、撤销不回收数量、声明更新拒绝，以及外部证据标志恒假。CI 还要求匿名 HTTP 401。
- 本阶段最初 A3 负例测试仅接受唯一键异常，但数量触发器先阻断重复认领；随后测试脚本新 DO 分隔符缺失，均已在测试中纠正，未修改 Flyway V106。最终 [精确代码 `3d94550`](https://github.com/kunlong-luo/merchivra/commit/3d94550b9a2ef5a53c15db358b390ed88b60478b) 的 [同 SHA 快 CI #45116800143 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45116800143) 与 [完整 A3 #45116800085（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45116800085) 已确认通过，包含真实 PG18、Flyway、HTTP、Java、Next、Chromium、Compose 7/7。任何合成绿灯不等于实际 Amazon/WMS/银行证明。

## 尚未完成的 G1 / P0

**没有外部 WMS 的真实法人/主体身份、出库/交付原始签名、Amazon FBA/FBM 订单履约交叉核验、货运/关税成本分配、退款实际收货与 COGS 回转，以及独立会计 G1 批准，因此实际批次成本必须继续被阻断。** 现有人工 V19 SKU 成本和 V20 PO 单价只能作为来源参考，不参与真实净利润宣称。

`realizedNetProfit=null`、真实 Seller/Ads OAuth OFF、生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) NO-GO、正式评分 **70/100** 均不变。
