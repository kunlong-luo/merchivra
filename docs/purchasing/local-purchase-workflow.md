# PO-001 · 供应商、采购单、分批收货（V20）

**这是本地 ERP 采购事实，绝不等价于 Amazon FBA 库存或财务入账。**

> **历史 V20 实施记录，不是当前完整功能清单。** V20 原轮次优先编码、当时未进行真实数据库/CI/生产验收；此后 V37–V107 已陆续实现补货草稿、仓库调拨/批次/QC、采购异常和页面增强，V108 [集中 Mock/PG18 回归](https://github.com/kunlong-luo/merchivra/actions/runs/42448160593) 已通过对应提交，**但从未替代真实 Seller/OAuth/采购付款验收**。最新源码对标与真实业务缺口见 [BENCH-001](../architecture/open-source-erp-benchmark-2026-11-08.md)、[P0 查询截断](../testing/p0-purchasing-read-cap-audit-v108.md) 和 [Issue #62](https://github.com/kunlong-luo/merchivra/issues/62)。

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

**V20 当时的“未实现”清单（历史，不可作为今天状态）：**供应商资料完善、合同、自动补货算法、仓库出库/调拨/退货、采购付款/应付、采购单 PDF、多级审批、正式 Amazon Inbound/Shipment 写入。**此后已经实现** V22/V37/V71 的解释性补货建议/人工创建 DRAFT、V40 仓库操作、V44/V50 的退货与批次异常、V63–V92 的本地 FBA 货件/历史选择等；**不得**继续以 V20 的句子断言“仓库调拨和退货都未做”。目前经源码确认的 P0 是采购单 100、供应商 200、流水 100、库存余额与待收货 500 的固定上限，V107 表格搜索仅针对已加载行。供应商应付/采购付款和真实 Amazon 货件/物流写入**没有可用的生产验收证据**，前者仍是需先确认业务范围的 P1 候选，后者默认关闭。V39 本地入库累计**不是**实物可用量，更不能推导 FBA 可售数量。

## V37 · FBA 补货建议 → 采购草稿（人工触发）

`/api/v1/replenishment/marketplaces/{id}/purchase-drafts` 复用原有 `PurchasingService.create()`，因此创建后状态仍为 `DRAFT`，必须按上述双人审批与收货流程继续。它并未增加采购自动执行权限。只对具有最新可核验观测且有 ACTIVE SKU 映射的单个 Seller SKU 开放；库存、策略或映射变化时返回冲突。使用 UUID 幂等请求键与 V37 独立来源快照表，保留原币、建议数量、来源观测以及关联采购单。参见 [INV-004](../inventory/replenishment-policy-v22.md)。

## V39 · 本地仓库记录与已审批待收货

详见 [PO-002 本地仓库收货与库存流水](local-warehouse-receipt-v39.md)。仓库入库不更新 Amazon FBA，历史未指定仓库的收货不追认入库。新增仓库创建、仓库累计采购收货、最近库存流水、已审批待收货 API 与 React 面板。
