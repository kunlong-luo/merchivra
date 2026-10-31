# PO-002 · V39 本地仓库入库流水（2026-10-31）

**范围：本地 ERP 已审批采购的分批收货。不是 Amazon FBA/FBM 的库存同步，也不把采购单当作已发货的在途货件。** 此功能先编码再集中验证，真实 Amazon 授权留待本地。

## 数据与边界

- `erp_local_warehouse`：每个 tenant 的本地物理仓库，租户内唯一仓库编号；当前只支持创建和查看。
- `erp_purchase_receipt.warehouse_id`：新建收货请求必须明确传入 ACTIVE 本地仓库 UUID；V20 历史收货保留 NULL，**不回填、不自动计入本地仓库库存**。
- `erp_local_stock_movement`：追加式采购收货入库事实，`(tenant_id,purchase_receipt_id)` 唯一。每次收货与订单状态变更在单个数据库事务内，写入一个对应 SKU、仓库、数量、收货 ID 和操作者的移动记录。数量必须为正。不能直接按 FBA 观测更新本地仓库。
- 本轮“累计采购收货件数”按仓库+内部 SKU 的正向移动总和；**未实现出库、损耗、库存冻结或退货，所以它不是可用库存、利润核算库存或 FBA 库存**。
- “已审批待收货”仅按 APPROVED / RECEIVING 采购行订购数量减已收数量，**不推断实际发货、Shipment ETA、跨境在途或 Amazon inbound**。
- 新收货只能由 OWNER、Session 所属 tenant 发起，Spring Security CSRF 保持不变；仓库/采购/产品关联都由服务端校验；禁止浏览器输入 tenantId。

## API

| 方法 | 路径 | 含义 |
|---|---|---|
| GET/POST | `/api/v1/purchasing/warehouses` | 列出 / 新建本地仓库，提交 `warehouseCode,displayName` |
| GET | `/api/v1/purchasing/warehouse-stock?warehouseId={uuid}` | 所选仓库累计已记录采购收货数量，最多 500 SKU |
| GET | `/api/v1/purchasing/warehouse-ledger?warehouseId={uuid}` | 所选仓库最近 100 条追加式入库流水 |
| GET | `/api/v1/purchasing/pending-receipts` | 已审批、未全部收完的采购行，最多 500 行 |
| POST | `/api/v1/purchasing/orders/{id}/receive` | **新版本请求必须包含 `lineId,quantity,warehouseId`**；批准状态、数量上限、仓库同租户和 ACTIVE 均在同一事务内校验 |

仓库读 API 采用 tenant+仓库 UUID 双条件，没有跨租户信息泄露。库存流水保留同一收货记录的唯一来源。收货仍使用既有 PO 行锁，避免并发超收；新的写入一旦失败，由事务回滚收货及采购状态。

## Web

Owner → 采购与供应商：新增仓库创建表单、已审批待收货表格、分批收货的仓库下拉选择、本地仓库累计收货和最近入库流水。旧版未分配仓库的收货继续参与采购单已收数量，但**不会出现在仓库入库流水及累计入库**。

## 下一阶段

本地出库/仓间调拨、盘点调整、实物在途/ASN、供应商 MOQ、库位/批次、仓储运营异常和与 FBA Inbound 的只读关联需另行开发。没有真实 Amazon OAuth/货件 API 连接的成功声明。验证状态应以 CI/Flyway/PG18 Compose 的实际运行记录为准。
