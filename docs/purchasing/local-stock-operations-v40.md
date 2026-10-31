# PO-003 · V40 本地仓库出库、调拨与盘点（2026-10-31）

**开发边界**：仅记录本地仓库实物运营事实。绝不调用 Amazon SP-API 写入 FBA/FBM、Feeds、Shipment 或财务系统。仓库库存台账只覆盖 V39 之后明确分配仓库的采购收货和 V40 已记录的人工库存操作；此前未分配仓库的 V20 历史采购收货不追认入库。

## V40 数据模型

- `erp_local_stock_operation`：租户内唯一 `request_key`，单次 Owner 操作快照保存类型、来源/目的仓、内部 SKU、操作件数、原台账余额、结果余额、实际差异、业务原因和操作人。相同请求原样重试返回原记录，不同参数或操作人复用请求键则返回冲突。
- `erp_local_stock_movement`：V39 已有采购收货 `PURCHASE_RECEIPT` 保持原样；V40 新增 `OUTBOUND` 负数、`TRANSFER_OUT` 负数、`TRANSFER_IN` 正数、`COUNT_ADJUSTMENT` 带符号差异，均保留 `operation_id`。每个操作类型与 ID 的组合唯一，且 `purchase_receipt_id` 和 `operation_id` 必须恰好二选一。库存不可直接覆盖、删除，也不能跨租户引用。
- 仓库台账余额为同仓同 SKU 所有已记移动数量的**有符号合计**；`recordedReceivedQuantity` 仍单独保留历史正向采购收货总和。余额不表示 Amazon 库存、未记录出库、可售/预留/质检、财务成本或物流在途。
- 单据操作封装在一个 `@Transactional` 内。先通过 Session 解析 Owner 所在租户，在 tenant 行锁下串行化请求键，然后对来源/目的仓按 UUID 排序加 `FOR UPDATE` 锁，复核 ACTIVE 仓库、ACTIVE 内部 SKU 和实际台账余额，再写操作快照和 1～2 条流水。任何失败全部回滚。仓库收货采用现有仓库 SHARE 锁，与出库 UPDATE 锁互斥。
- 出库/调拨要求库存足够，绝不允许扣成负数。盘点请求提交**实盘数量**和 UI 上看到的原台账余额，服务端据此求差异；若当前余额变更、实盘相同、差异绝对值超过 1,000,000 或输入越界均拒绝。盘盈不是推定的 Amazon 来源或采购收货。
- 出库/调拨最大单次 1,000,000 件；盘点实数 0～10,000,000 件；原因至少 5 个可打印字符。真实物流单号、批次、批号、权限精细化、货位及复核单尚未实施。

## API / Web

- `POST /api/v1/purchasing/warehouse-operations`：Owner + Session + CSRF，JSON `{requestKey,type,warehouseId,destinationWarehouseId,productSkuId,quantity,expectedOnHand,reason}`。类型 `OUTBOUND`、`TRANSFER`、`COUNT`。COUNT 的 `quantity` 是**实盘数量**，不是差异数；非调拨时目的仓为 null。
- `GET /api/v1/purchasing/warehouse-operations?warehouseId=...`：最新 100 次涉及该仓库的人工操作，包括 0 次也是空数组。
- `GET /api/v1/purchasing/warehouse-stock?warehouseId=...`：保留 `recordedReceivedQuantity` 并新增 `onHandQuantity`。
- `GET /api/v1/purchasing/warehouse-ledger?warehouseId=...`：增加 `operationId`、`reason`，`purchaseReceiptId` 可以为空；`quantity` 是带符号变化。
- React Owner → 采购与供应商 → 本地仓库台账：选仓库/SKU、查看原台账余额、选择出库/调拨/实盘、填数量和原因，再经确认写入。每次编辑重新生成请求键，失败网络重试保留请求键。操作成功后刷新当前仓库台账与流水。

## 验收与后续

新增了 JUnit/Mockito 的输入拒绝、余额不一致、超卖、幂等冲突、调拨方向及前端 Vitest 的签名请求和负数流水用例。**是否通过须查看相应提交的实际 CI 结论；不能将测试代码存在等同于 PostgreSQL 并发实测。** PostgreSQL 18 V40 迁移、并行出库、双仓交叉调拨、租户安全 HTTP/CSRF 仍需独立验收。

后续：仓库低库存阈值/异常待办、员工操作权限、多级审批、退货回仓、调拨中转单和关联真实货件 API。未经真实店铺授权与环境验收不启用 Amazon 网络写入。
