# PO-009 · V46 新收货先待检、真实批次与独立质量放行

**范围**：本地采购单已经人工审批后，真实登记到仓的**新增**收货仅进入待检批次；完整质检由一个 Owner 记录，合格件数须由**另一个 Owner** 人工放行才第一次进入可用本地仓库台账。依然不调用 Amazon SP-API、供应商、承运商及支付系统。

## 不可回写的双轨历史

1. **旧 V20/V39–V45 收货**：原始 `erp_purchase_receipt` 不变，历史 `PURCHASE_RECEIPT` 已写入的正数本地库存流水保持不动。原 V44 回溯性质检仍可登记完整实收/不合格件；不合格品以唯一 `QC_QUARANTINE` 负数流水隔离。既有历史数据不会伪造新的 `erp_receipt_quality_batch`，也不会重加一次合格品。V20 无明确仓库的收货不被猜入任意仓库。
2. **新 V46 收货**：保留 `PurchasingService.receive` 的 PO 状态、完整订单行数量限制、ASN 源与目标仓库校验、原始 `erp_purchase_receipt` 和不可覆盖的收货/ASN 事件。原 `WarehouseInventoryService.recordReceipt` 的新实现**不再立即写入 `PURCHASE_RECEIPT` 正数库存移动**，而是创建由真实 receipt ID、warehouse ID、SKU、实收件数、自动批次码及操作者组成的 `erp_receipt_quality_batch`（`PENDING_QC`）。
3. `PENDING_QC` 可仅在质检前更改 `lot_code`，必须同时发送预期旧编码、符合安全字符/长度的新编码和 5～300 字符原因；数据库按同仓库、SKU、批次编码去重。每次创建、修改、质检、放行均追加到 `erp_receipt_quality_batch_event`（操作者、来源实收、前后编码、数量和备注），不覆盖历史事件。单批次当前以整条实收为粒度；尚未实现序列号/批次拆分/物理仓位货架定位。
4. 质检 `inspect` 原 V44 HTTP 接口保持不变，必须满足 `accepted+rejected=receipt.quantity`、同一笔收货只记录一次、租户匹配与请求 UUID 幂等。先检查是否存在新批次：存在且 `PENDING_QC` → **只写质检快照和批次状态 `INSPECTED`，绝不加/减当前可用库存**。不存在批次 → 走旧 V44 的库存余额检查与负数隔离。这种区别由实际数据库是否持有批次证据判断，而非客户端 flag。
5. **独立质量放行**：`POST /api/v1/purchasing/receipt-batches/{receiptId}/release` 仅 `INSPECTED` 批次可执行，且当前 Owner 不能是这份 QC 的 `inspected_by`。在租户级串行锁和真实仓库行锁下，读取来自 V44 QC 快照的 `accepted_quantity`，**若大于 0 才且仅创建一条 `QC_RELEASE` 正数库存流水**；随后批次状态变成 `RELEASED`，记录 `released_by`、时间与审计。再次释放直接返回已放行批次，不重加库存。数据库 V44 既有 `UNIQUE(tenant_id,inspection_id)` 限制再兜底防止一个 QC 被重复生成流水。全数不合格时可走独立放行结案，但不产生任何正数库存移动；不合格品只能进入 V44 的独立隔离/供应商退货流程。
6. **库存读取清楚区分**：`erp_purchase_receipt` 的累计真实 `recordedReceivedQuantity` 含待检批次，但 `erp_local_stock_movement` 的 `onHandQuantity` 只含经第二 Owner 放行的合格品、旧 V39 入库、已有出库/调拨/盘点/历史质检负数流水。SKU 仅有待检批次时也应在仓库库存表显示，但可用余额为零。V41/V42 本地补货建议仍使用已发布库存流水；如果没有库存移动证据，不能将 `PENDING_QC` 的实收自动转成已可用库存，也不能偷偷作为 FBA 状态。

## 新 API 与前端

API 基础路径 `/api/v1/purchasing/receipt-batches`；所有 GET/POST 均根据 Owner Session 推导 tenant 和 actor，写操作受 CSRF 保护。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/orders/{orderId}` | 列出该单 V46 新收货批次、真实实收、状态、已检合格/不合格件数和审核操作者 |
| GET | `/warehouses/{warehouseId}` | 按具体本地仓库查批次 |
| POST | `/{receiptId}/relabel` | `expectedLotCode,newLotCode,reason`，仅 `PENDING_QC`，对原编码进行乐观版本比较 |
| POST | `/{receiptId}/release` | 另一位 Owner 对已完成质检的合格件显式签批放行 |
| GET | `/{receiptId}/events` | 不可覆盖的批次来源/改码/质检/放行事件链，最多 200 条 |

React Owner → 采购与供应商 → 选择已有采购单 → “V46 收货待检批次与合格放行”，查看待检数、已检待放行合格数、自动 lot 码、状态、来源时间、改码和审核历史；点击质检记录仍使用原 V44 UI，提交后数据进入已检待放行，由另一 Owner 点击“独立审批并放行合格品”。仓库流水显示 `QC_RELEASE` 和质检 ID；V44 旧质检负数流水保持 `QC_QUARANTINE` 名称。

## 验收

JUnit/Mockito 新增：改码预期版本冲突、编码输入校验与追加式审计、待检不可放行、质检员自审拒绝、独立 Owner 只向本地可用台账发布合格件数、全数拒收 0 发布、重复放行不重复计入；更新仓库测试，要求 V46 新收货只生成批次和事件而不立即写正数可用流水；更新质检用例验证新待检路径不触碰库存余额。Vitest 新增 GET/POST CSRF、批次改码、状态守卫、审计事件与人工发布流程。

**限制**：编译/Mock 单测通过不能代替 PostgreSQL 18 真实 Flyway、HTTP 多租户/并发隔离、串行锁、浏览器端到端验证。对 PostgreSQL 18 执行 V46 Flyway 并验证任意实收的库内数量守恒后，才可称为运行时已验收。仍需后续增强批次拆分、序列号追溯、物理待检货位及供应商赔付凭据；Amazon Seller OAuth、真实 SP-API 联调待全部核心功能完成后进行。
