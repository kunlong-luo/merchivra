# PO-012 · V49 已放行批次跨库位、跨仓、盘点证据与过期隔离

**范围**：只针对 V48 实际已放行、由 Owner 明确上架到 STORAGE 的 `erp_local_lot_position` 可用库存。仓库总库存以 `erp_local_stock_movement` 的签名流水为准；库位剩余量只能是其中有真实批次来源的子集。Amazon Seller OAuth / SP-API、承运商状态和外部 WMS 仍未启用。

## 数据结构

- `erp_local_lot_position.origin_position_id` 记录跨仓调拨来源库位（可多层递归），每份位置保留原始真实 V46 采购收货 `receipt_id`、SKU、人工有效期及放行来源。**既有 V48 位置不做任何假的跨仓回填**。
- `erp_local_lot_action` 追加式记录同仓移位 `MOVE`、真实跨仓调拨 `TRANSFER`、已过期人工隔离 `EXPIRED_QUARANTINE` 和只记录观察结果的 `COUNT_OBSERVATION`。固定 requestKey、来源 position、最初来源仓及 bin、操作前期望 position 数量、可选期望仓库可用库存、目标 bin / 目标仓库 / 目标位置、操作者、原因及时间；tenant 内一次来源一个 requestKey 不可重用为不同动作。
- V49 为 `erp_local_stock_movement` 增加 `lot_action_id` 与签名类型 `LOT_QUARANTINE`，只有过期隔离产生带此来源的负数可用库存，约束 `(tenant_id,lot_action_id)` 唯一，禁止把真实隔离伪装成 V40 `OUTBOUND` 销售。扩充 `erp_local_lot_event` 记录位置移动、跨仓移出/移入和到期隔离。
- V48 putaway 可放入的总来源合格数量，仅统计 `origin_position_id IS NULL` 的原始批准上架，避免跨仓调拨产生的下游库位再重复消耗最初 QC 放行额度。

## 四类动作与事务保证

**1. MOVE 同仓真实库位移位**：只接受当前 tenant、同 warehouse 的 ACTIVE STORAGE 库位，要求和来源 bin 不同、提交上一次读取的 expectedRemaining。变更原位置 `bin_id`，保留人工 reason 和前后来源 bin，**不修改全仓可用库存**，不会生成订单、付款或 Amazon 记录。来源库位为零时不应再做实物移动。

**2. TRANSFER 跨仓批次调拨**：必须选择不同 ACTIVE 目标仓库和 STORAGE bin；来源 `remaining_quantity>0`、明确人工件数且未过期（基于人工有效期）和 expectedOnHand，重新锁住租户、来源位置、两仓及库存余额。先在同一事务中减少来源位置件数，调用既有 V40 `WarehouseStockOperationService.post(TRANSFER)` 写入完全等量的 `TRANSFER_OUT` 与 `TRANSFER_IN`，然后创建目标位置并将 `origin_position_id` 指向来源位置；目标位置保留原 receipt、lot 编号、SKU、人工有效期，`placed=remaining=本次调拨件数`。V48 FIFO/FEFO 可继续从目标仓按相同库存来源拣货。任一写入失败全部回滚。V40 自带全仓余额、仓库锁、租户、审计和严格的负数出库余额保护；跨仓调拨也必须要求两个真实仓库实际持有外部证据之外的本地收发凭证，此处**仅记录 Owner 对实物已转移的确认**。

**3. COUNT_OBSERVATION 实物盘点差异**：Owner 输入扫描/清点的 `observedQuantity`、原 `expectedRemaining` 与详细原因，追加盘点观察，不写 `erp_local_stock_operation`、不改任何位置或 warehouse 可用库存，不把实物多出来的件数偷偷认定为已有可售库存。后续需要独立的调整审批和源证据验证流程。

**4. EXPIRED_QUARANTINE 人工过期隔离**：仅当本来源批次有人为登记的 `expiry_date` 且已早于当前日期才可执行；预警只读而不是后台自动扣账。再次核对库位余额、全仓 expectedOnHand、操作件数及来源；同一事务下扣减 position remaining 和签名 `erp_local_stock_movement.LOT_QUARANTINE=-quantity`，固定 `lot_action_id`，追加隔离事件。由此已过期品不再进入本地可用库存和 FIFO/FEFO 拣货。隔离是**台账上脱离可用状态**，不是扫码证据或实际仓库隔离区物理搬迁已确认；后续独立的处置流程应明确销毁、退货和责任证据。

## 可查询来源与预警

- `GET /api/v1/purchasing/lot-lifecycle/positions/{positionId}/lineage` 使用 tenant 限定的递归父位置链向上追到原始真实 `receipt_id`、首笔放行、历史仓/货位及人工有效期，最多 100 层；从当前仓可以向上查上一仓的库位来源，避免跨仓之后丢失批次身份。
- `GET /api/v1/purchasing/lot-lifecycle/positions/{positionId}/history` 查询此位置作为来源或调拨目标的 V49 事件，包含原 bin、目标仓位、调拨签名 operation ID、盘点观察数量及操作者、原因。
- `GET /api/v1/purchasing/lot-lifecycle/warehouses/{warehouseId}/expiry-alerts` 只读查询真实已上架、剩余数量为正且 `expiry_date <= CURRENT_DATE+30` 的位置，分 `EXPIRED`、`NEAR_EXPIRY`；未登记有效期不虚构预警，也不以此认定 FBA 可售。
- `POST /api/v1/purchasing/lot-lifecycle/positions/{positionId}/actions` 统一携带 `requestKey,actionType,destinationBinId,quantity,expectedRemaining,expectedOnHand,observedQuantity,reason`。传入的来源租户/actor 字段不可信，**只从认证 Session / CSRF 中派生**。后端按动作严格限定允许字段与变更；同一请求键重放验证完整来源与操作者，不重复扣除库存。
- React 采购工作台新增 V49 面板：来源仓与真实货位、30 天到期预警、目的仓和 STORAGE bin、操作数量、实际盘点差异、操作原因与二次确认、不可覆盖的来源链和动作审计。盘点仅记事实，过期隔离会真实扣减本地库存；区别必须在 UI 明确。

## 不可夸大的范围与验收

- V49 没有自动采集的仓储扫码、物理移动证明、审批后独立执行、库存过期自动计提/报废凭证、已隔离品回库或真实 Amazon 货件状态；以上动作是当前 Owner 对本地事实的确认，未授权任何外部系统。
- 真实 PostgreSQL 18 / Flyway 全迁移、严格并发锁、两用户跨租户权限、完整浏览器和 Docker Compose 所有 V49 API 仍需独立验收。Java 25 / Spring Boot 和 React TypeScript/Vitest 的 CI 通过不能替代数据库真实运行验收。
- 后续 V50 建议把盘点差异审批与实际可售库存修正衔接，同时对移位和跨仓调拨增加收发双方二次确认、隔离物理库位、报废/退货单据；**不能将 COUNT_OBSERVATION 当作已经批准的财务调整**。
