# PO-001 · 供应商、采购单、分批收货（V20）

**这是本地 ERP 采购事实，绝不等价于 Amazon FBA 库存或财务入账。**
此轮按维护者指示优先编码，未运行数据库、CI 或生产验收。

| 功能 | 端点 | 说明 |
|---|---|---|
| 供应商列表/创建 | `GET/POST /api/v1/purchasing/suppliers` | Owner、租户唯一供应商编号 |
| 采购单列表/创建 | `GET/POST /api/v1/purchasing/orders` | 1–100 行、原币种、每行数量及单价、不可重复 SKU |
| 采购单详情 | `GET /api/v1/purchasing/orders/{id}` | 每行订购/已收、历史状态 |
| 提交审批 | `POST /api/v1/purchasing/orders/{id}/submit` | DRAFT → SUBMITTED |
| 双人批准 | `POST /api/v1/purchasing/orders/{id}/approve` | **申请创建人不能自行批准**；需另一名 OWNER |
| 取消 | `POST /api/v1/purchasing/orders/{id}/cancel` | DRAFT/SUBMITTED/无收货 APPROVED |
| 分批收货 | `POST /api/v1/purchasing/orders/{id}/receive` | V39 必须指定 `warehouseId`；APPROVED/RECEIVING；数量不能超过订购，原子追加本地入库流水 |

所有 API 从 Session 获得当前 Tenant、Owner；危险操作由 CSRF 保护。Purchase Order 行记录单价与原币种快照，receipt 为追加式事实而不是通过更新一个计数掩盖历史；事务持有 PO 行锁避免并发超收，复合 FK 确认每条 Receipt 对应同一 PO 的 Line。动作有独立 audit。前端 Owner 导航“采购与供应商”可录入供应商、采购行、提交、审批与收货；员工不可操作。

**未实现：**供应商资料完善、合同、自动补货算法、仓库出库/调拨/退货、采购付款/应付、采购单 PDF、审批多级策略、正式 Amazon Inbound/Shipment 写入。V39 已新增**仅限本地采购收货的入库流水**，但其累计入库不是实物可用量，更不能推导 FBA 可售数量。

## V37 · FBA 补货建议 → 采购草稿（人工触发）

`/api/v1/replenishment/marketplaces/{id}/purchase-drafts` 复用原有 `PurchasingService.create()`，因此创建后状态仍为 `DRAFT`，必须按上述双人审批与收货流程继续。它并未增加采购自动执行权限。只对具有最新可核验观测且有 ACTIVE SKU 映射的单个 Seller SKU 开放；库存、策略或映射变化时返回冲突。使用 UUID 幂等请求键与 V37 独立来源快照表，保留原币、建议数量、来源观测以及关联采购单。参见 [INV-004](../inventory/replenishment-policy-v22.md)。

## V39 · 本地仓库记录与已审批待收货

详见 [PO-002 本地仓库收货与库存流水](local-warehouse-receipt-v39.md)。仓库入库不更新 Amazon FBA，历史未指定仓库的收货不追认入库。新增仓库创建、仓库累计采购收货、最近库存流水、已审批待收货 API 与 React 面板。
