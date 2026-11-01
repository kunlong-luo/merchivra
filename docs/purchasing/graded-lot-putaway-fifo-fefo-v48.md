# PO-011 · V48 分段质检、合格品上架与真实 FIFO/FEFO 库位拣货

本模块承接 V46 收货待检及独立 Owner 质检放行、V47 待检货品分段。V48 的重点是让**批次位置数量与仓库真实签名库存流水同步**，而不是仅在前端推荐拣货顺序。所有动作仅属于本地 ERP，不向 Amazon、仓储服务商或供应商自动发送指令。

## 1. 分段质检（仅待检原始真实收货）

- `erp_receipt_piece_grade` 对每个 V47 收货分段最多追加一份完整质检 `accepted_quantity+rejected_quantity=piece.quantity`，记录 requestKey、质检人、原因和时间，tenant+piece/requestKey 唯一。
- 开始任一分段质检后，V47 拆分与待检库位移动被冻结；防止已确认的分段数量或来源位置被再次改变。分段质检本身不写库存。各段可由 Owner 人工记录，审核说明和源分段完整保留。
- `POST /piece-grades/receipts/{receiptId}/finalize` 必须确认所有分段都已质检，合格+不合格总和与原真实 `erp_purchase_receipt.quantity` 一致，才使用已有的 V44/V46 `PurchaseQualityService.inspect` 创建**一份**最终整笔质检记录及批次状态 `INSPECTED`。因此 V46 的独立 Owner 放行规则、V44 供应商退货来源和原始不可覆盖质检/ASN 审计继续工作。
- 若 V48 分段质检已开始，旧 V44 整笔质检端点也会重新检验分段质检覆盖率与数量一致性，不能通过旧端点绕过“只检了一部分”的约束。未开始分段质检的历史/现有 V46 正常整笔质检路径保留。
- 批次级部分放行尚未实现：**每段独立的合格/不合格证据已经可审阅，但最终 QC 和放行仍按整条真实收货结算**；不要把分段质检结果视作已经局部进入可用库存。

## 2. 已放行合格品上架（库存来源不重复计账）

- 仅 `erp_receipt_quality_batch.status='RELEASED'` 且 V44/QC acceptedQuantity>0 的实收可以建立 `erp_local_lot_position`。明确选择同租户、同仓库、ACTIVE `STORAGE` 库位及单次上架数量，可选登记商品有效期与上架原因。
- `placed_quantity` 是从这条真实收货批次已由另一位 Owner 放行的合格数量中分配的一部分；该收货的所有 `placed_quantity` 总和不能超过 approved acceptedQuantity。独立 `remaining_quantity` 是该库位仍在的未拣走数量；**上架本身不会重新创建 `QC_RELEASE` 或增加仓库库存**。
- 上架前同时核对仓库签名库存余额以及已定位的仓库本地同 SKU 货位余量；只能从其余尚未定位的已可用件数中分配。来源批次的有效期是操作人员录入的声明，不是自动扫描验证，过期日期可存档但会被后续自动拣货排除。
- putaway 使用 tenant+requestKey 唯一，校验此前操作的来源、数量、目标库位、有效期、说明和 Owner，避免误用同一键二次上架。动作产生独立 `PUTAWAY` 审计。
- **历史未追踪库位的已可用库存不回填成批次余量**，不会虚构货架/条码、有效期或可售来源。批次库位是仓库真实已签名可用余额的一个被明确人工分配的子集。

## 3. FIFO/FEFO 已落地为真实库存出库

- 只有 Owner 明确选择仓库、SKU、策略 `FIFO`（较早的真实收货优先）或 `FEFO`（较早的有效期优先，NULL 排后）、实际拣货数量以及原因，并确认后才调用 `POST /released-lots/picks`。
- 拣货只使用当前仓库、当前 SKU、`remaining_quantity>0` 且**有效期未过期或未填写有效期**的存放批次。FEFO 未登记有效期的货物只能按到货先后排在已知日期之后，不能宣称存在真实可验证的严格 FEFO。FIFO 也不会选择已知过期库存。
- 在 tenant/仓库锁下重新读取签名可用库存与预期 `expectedOnHand`，按策略计算足量的多个库位分配，并逐一扣减 `erp_local_lot_position.remaining_quantity`；随后**在同一个 Spring `@Transactional` 中**调用既有 V40 `WarehouseStockOperationService.post(OUTBOUND)`，产生标准审计 `erp_local_stock_operation` 和签名 `erp_local_stock_movement.OUTBOUND`，再保存 `erp_local_lot_pick` / `erp_local_lot_pick_line` 和追加式 `PICKED` 事件。任一环节失败，整个请求回滚（不能出现扣了库位却没扣仓库余额）。
- `erp_local_lot_pick` 用相同 tenant/requestKey 防止重试重复出库；重试同时验证提交者、仓库、SKU、策略、数量、原预期库存及原因。库存余额或可拣分配不足时拒绝，并返回让 Owner 刷新后再确认。
- **保护普通库存路径**：V40 直接出库、调拨或向下盘点时，若操作后总余额将小于库位已追踪的剩余合格件数，直接拒绝，要求使用来源明确的批次拣货。这允许用 V40 对仍未定位的库存做正常操作，同时避免把已定位批次数量无来源地扣走。
- 这一阶段暂不支持跨仓按批次调拨、退货来源扣减库位、库位间已放行批次挪位、真实序列号扫描、库位盘点修复、条码设备或 Amazon 发货确认。普通仓库操作的基于未定位库存的调整不视为批次 FEFO 消耗。

## 4. HTTP、React 与权限

均为当前登录 Owner Session 决定 tenant/actor，POST 必须 CSRF。

| 方法 | 路径（前缀 `/api/v1/purchasing`） | 说明 |
|---|---|---|
| GET | `/piece-grades/receipts/{receiptId}` | 查询该真实收货分段的完整质检结果 |
| POST | `/piece-grades/receipts/{receiptId}` | `requestKey,pieceId,acceptedQuantity,rejectedQuantity,reason`，记录某分段检验 |
| POST | `/piece-grades/receipts/{receiptId}/finalize` | `requestKey,reason`，全部分段核对无误后调用 V44 最终 QC |
| GET | `/released-lots/warehouses/{warehouseId}/positions` | 上架批次、库位、原上架数、剩余数、来源批次、人工有效期 |
| POST | `/released-lots/putaway` | `requestKey,receiptId,binId,quantity,expiryDate,reason`；仅放行的合格品 |
| GET | `/released-lots/warehouses/{warehouseId}/skus/{skuId}/pick-preview?policy=FIFO\|FEFO` | 只读排序预览，自动排除已知过期批次 |
| POST | `/released-lots/picks` | `requestKey,warehouseId,productSkuId,policy,quantity,expectedOnHand,reason`；人工确认后同事务批次扣减与签名 OUTBOUND |

React Owner → 采购与供应商 → “V48 · 分段质检、合格品上架与 FIFO/FEFO 拣货”。V46 旧待检与独立放行页面继续保留；V47 分段与待检库位用于质检前的来源记录。V48 额外提供分段质检、全部结果汇总、STORAGE 上架与库位 FIFO/FEFO 出库。

## 验收和限制

新增 Java/Mockito：分段数量不守恒拒绝、不允许部分质检汇总、旧端点不允许绕过已写分段证据、幂等、已检未放行上架拒绝、期望库存过期拒绝、库位不足拒绝、批次库位和标准 V40 出库同事务调度；原 V40 测试补“普通出库/盘点不能侵占已定位库存”。React Vitest 校验 Zod 来源契约、CSRF、人工质检/放行和拣货 POST 分离、只读 FEFO 预览。

**CI 的 Java/React 编译和 Mock 测试不等于 PostgreSQL 18 Flyway/Compose 真实迁移、跨租户权限、并发多事务、端到端仓库业务的实测结果**；实际迁移与多用户 HTTP 验收须单独完成。真实 Amazon Seller OAuth 和 SP-API 联调按既定计划最后由项目 Owner 在本地执行。
