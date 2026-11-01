# PO-014 · V51 有来源盘盈、跨仓短收纠正及处置凭据内部复核

V51 接续 V50 的两类未闭环异常：已由不同 Owner 独立复核的 `EVIDENCE_REQUIRED` 盘盈和 V49 已过账后由另一名 Owner 核对的 `MISMATCH` 目的仓短收。此外为 V50 的 `EXECUTED_REPORTED` 隔离品处置加上只读、可审查的证明材料**元数据**，避免用“人工完成报告”冒充供应商或者第三方已经验真。

## 1. 盘盈只可绑定原先就存在的可用货物

- V51 只接受 `erp_local_lot_count_review.status='EVIDENCE_REQUIRED'`、`observed > expected` 的 V50 盘盈审核来源。不允许使用尚未批准的观察、拒绝的审核、过期品或任意一笔普通货物来伪造合格品。
- 提交必须注明真实 `sourceReceiptId`：来源位置须是未通过跨仓子位置继承的 `origin_position_id IS NULL` 根位置，`position.receipt_id=sourceReceiptId` 且对应 V46 批次仍为 `RELEASED`，最终 QC 实际 accepted 数量为正。
- 另一 Owner 审核时再次核对 tenant、position ID、原库位 ID、原 remaining、签名 warehouse SKU 总库存快照、V46 QC 状态和数量。**原收货所有根位置 placed_quantity 总和 + 盘盈差额不能大于其 acceptedQuantity**。**当前仓库该 SKU 所有已定位位置 remaining 总和 + 盘盈差额不能大于签名的总可用库存余额**。来源或数量有任何变化时返回 409，不忽略失败也不强制应用。
- 全部条件满足后同事务把原根位置 `placed_quantity` 和 `remaining_quantity` 各增加相同差额并追加 `SURPLUS_BOUND` 事件；**不创建新 PURCHASE_RECEIPT、QC_RELEASE、COUNT_ADJUSTMENT 或任何正数库存流水**。本操作仅用于把已有的 *已放行但尚未定位* 库存分配到真实清点后的库位，绝不等于以盘盈证据凭空入账。
- 如果盘盈确实是仓库总库存以外的新实物，本阶段明确不支持直接转可售；须通过真正采购/其他货物流入来源与 QC，后续仍需新工作流，不能把 V51 绑定规则改成正向 COUNT。

## 2. 目的仓少收：避免 V49 重复过账

- 只允许引用 V50 `erp_local_lot_transfer_ack.status='MISMATCH'`、`receivedQuantity < posted transfer quantity` 的 V49 真实调拨来源；待签收、已全部确认、超额收货不可作为本纠正来源。
- 申请人提供源调拨 actionId、实际签收证据编号与理由；在 V51 申请时保存目标仓库和目标批次的 `expectedBinId`、`expectedPositionQuantity`、签名仓库库存快照和短少件数。
- 独立审核者不能是申请人或原 V50 收货确认者。批准时再次校验 ack 仍为 MISMATCH 且差额未变，目标位置还保留与原 V49 调拨数量完全一致的 `placed_quantity/remaining_quantity`，且没有下游拣货、移位或二次调拨改变库位/余量。来源状态不一致直接拒绝，不自动修正另一批次。
- 同一 Spring `@Transactional` 中先扣减目标位置 `remaining_quantity` 对应短少数，再调用原 V40 `WarehouseStockOperationService.post(COUNT)` 将**目标仓**该 SKU 的签名库存减少相同数量（而非新增一笔 TRANSFER_OUT/TRANSFER_IN），保存真实 stock operation ID 和 `SHORTAGE_CORRECTED` 审计。原 V49 来源仓转出流水仍完整，V50 目的仓人工核对记录仍保持。任何一步失败全部回滚。
- 每个已签收 mismatch 最多一笔纠正申请；`request_key` 只接受同提交人、同 source、同 reference/reason 的完全一致幂等重放。该限制避免同一个短少凭据反复负数调整。

## 3. 已报告处置的参考凭据与内部复核

- `erp_local_lot_disposition_proof` 只引用 V50 已 `EXECUTED_REPORTED` 的报废/供应商退货计划，且 `proofKind` 与原处置 `method` 一一匹配：`DISPOSAL_CERTIFICATE` / `SUPPLIER_RETURN_RECEIPT`。
- 由当前 Owner 手工录入参考编号、证据来源说明和**可选** 64 位小写 SHA-256 摘要。系统本阶段**不接收、持久化或校验原件文件，不会验证摘要实际是否与文件相符，也不会直接访问承运商/供应商**。任何人工输入的参考编号和 hash 都只是元数据。
- 另一个不同于提交者以及 V50 实际执行报告人的 Owner 可标记 `METADATA_REVIEWED` 或 `REJECTED`，记录独立审核意见与时间。METADATA_REVIEWED **不是外部证书已验证或承运商实际确认**。
- 每个 V50 已报告处置只允许一条元数据证据审阅记录，重复 key 须同源/同 actor/同 payload，所有这类写入都不产生库存或采购/财务变化，原 V49 `LOT_QUARANTINE` 的负数扣减不可在 V51 重复发生。

## 4. 数据、API 与 UI

Flyway V51 新增 `erp_local_lot_exception_resolution`（PENDING_REVIEW / APPLIED / REJECTED）、`erp_local_lot_disposition_proof`（PENDING_REVIEW / METADATA_REVIEWED / REJECTED），完整 tenant FK、唯一来源约束、request_key、操作/审核 Owner、审计说明、库存快照与可选 signed COUNT operation_id。V48 `erp_local_lot_event` 增加 `SURPLUS_BOUND` / `SHORTAGE_CORRECTED` 可溯源事件类型。

`/api/v1/purchasing/lot-exceptions`：`GET /warehouses/{warehouseId}`、`POST /`（`requestKey,resolutionType,sourceId,sourceReceiptId,evidenceReference,reason`）、`POST /{id}/decision`（`decision,reason`）。当前 Owner Session 决定 tenant/actor，POST 需要 Spring CSRF。

`/api/v1/purchasing/lot-disposition-proofs`：`GET /warehouses/{warehouseId}`、`POST /`（`requestKey,dispositionId,proofKind,evidenceReference,documentSha256,explanation`）、`POST /{id}/decision`（`REVIEW/REJECT,reason`）。不得在返回值中声称外部文件已成功上传或外部机构已验真。

React Owner → 采购与仓储工作台增加 V51 异常申请/审核、已报告处置凭据元数据与独立内部复核两块；V50 原始盘点、调拨签收、处置申请与执行面板保持可追溯。

## 5. 验收与边界

新增 JUnit 测试：不合法/被拒绝的盘盈来源拒绝、提交者/目的仓收货人不能自行审批、已放行合格与签名未定位库存双上限、库存短少使用原 V40 COUNT 而非虚构 TRANSFER、新的根位置只重新定位现存合格货物、不产生正库存流水、报废与退货凭据类型核对、执行人不得审阅自己证据。Vitest：前端 zod、CSRF、拆分申请/审批、document SHA-256 元数据和内部审核状态。

真实 PostgreSQL18 Flyway/Compose、并发双人跨租户 HTTP 和实际物流扫描/证明文件上传、真实 Amazon SP-API 仍需要专项验证。Java/React CI 不能替代这些验收。后续建议 V52：对仓库入库来源的异常进行实际实物扫描/附件上传和凭据核验，完善供应商退货回执联动和可审阅的财务损益事件；Amazon 真实授权按既定安排最后由 Owner 在本地进行。
