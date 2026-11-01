# PO-013 · V50 库位盘点独立复核、跨仓目的仓收货确认与隔离品处置

V50 延续 V46–V49 的本地采购实收、待检、独立质量放行、库位库存账、签名仓库流转、跨仓调拨和过期隔离。所有“物理”动作均为登录 Owner **人工确认的本地业务事实**；不调用 Amazon Seller OAuth/SP-API、承运商、外部 WMS 或支付接口。

## 1. 盘点差异：观察 → 提交 → 不同 Owner 复核

来源是 V49 `erp_local_lot_action.action_type='COUNT_OBSERVATION'` 的不可覆盖记录，内含来源 position、来源 bin、`expected_remaining`、`observed_quantity`、观察人及理由。

`POST /api/v1/purchasing/lot-reconciliation/counts` 只接收 `requestKey,observationActionId,reason`，从后端追溯真实仓、SKU、位置。请求必须基于仍相同的 source bin 与 position remaining；记录当时 warehouse signed balance `expected_on_hand` 并生成 `PENDING_REVIEW`。一次观察只能提交一个审核单，不能凭 UI 任意指定一个数量。此阶段**不写可用库存**。

`POST /counts/{caseId}/decision` 仅独立 Owner 可审核，且不得是原观察人或提交人。可 REJECT（终结，不改账），或 APPROVE：
- **盘亏 `observed < expected`**：再次锁 tenant、真实仓库和来源 position，核对来源 bin、position 剩余与提交时 warehouse 签名可用库存快照；在同一个事务将 source `remaining_quantity` 改为真实 observed，并调用既有 V40 `WarehouseStockOperationService.post(COUNT)` 对当前全仓 SKU 余额做恰好 `expected-observed` 的负向修正。V40 的 `expectedOnHand`、不许产生负库存、不许侵占其他定位批次、一次性来源审计和现有库内 SKU 校验全部保持；成功后审核单状态 `APPLIED`，记录 reviewer、时间、意见与真实 stock operation ID。任一子操作失败回滚前面的 position 改动和后面的审核状态。
- **无差异**：状态 `CONFIRMED_NO_CHANGE`，不写流水。
- **盘盈 `observed > expected`**：状态 `EVIDENCE_REQUIRED`，不允许增加已放行批次或 warehouse 可用数。真实盘盈有待后续补交来源证据 / 待检质检流程，不能以独立复核替代真实货物流入证明。

同一观察只有一个审核单；提交和审核按 tenant + requestKey 和后端不可变原事实校验。之前已形成的 V49 实物观察也可用于 V50，无需删除或回写旧记录。

## 2. V49 跨仓已过账，V50 目的仓独立收货确认

V49 `TRANSFER` **已经在同一事务**扣源仓库位库存、生成 V40 `TRANSFER_OUT/TRANSFER_IN` 等量签名库存流水，并建立目标仓 `origin_position_id` 溯源位置。V50 不得对此新增任何 `TRANSFER_IN` 或本地库存正向移动。

`GET /warehouses/{warehouseId}/transfers` 按目标仓显示所有 V49 真实调拨及状态：
- `POSTED_AWAITING_DESTINATION`：V49 已过账，但尚无第二人核对实际实物
- `CONFIRMED`：由与最初调拨人**不同的 Owner**提交 `receivedQuantity == postedQuantity`，同时记录签收参考编号与原因
- `MISMATCH`：不同 Owner 报告 `0 <= receivedQuantity < postedQuantity`，保存异常与原本应收数，**原 V49 已过账金额和数量暂不自动改写**，留待进一步实物核实、来源追溯和正式纠错

`POST /transfers/{actionId}/acknowledge` 的 payload `requestKey,receivedQuantity,reference,reason`；tenant + action 唯一，同一操作重复提交须完整内容、actor、requestKey 一致，不可重复签收。若实点超过原 V49 已过账数量，本接口直接拒绝并要求独立报告多货来源，不能自动增加可售库存。

## 3. V49 已隔离商品的处置计划：两人审批与独立执行报告

只允许引用 `EXPIRED_QUARANTINE` 类型的 V49 action，且必须在 `erp_local_stock_movement` 查到唯一匹配的已签名 `LOT_QUARANTINE=-quantity`；这证明库存此前已经退出可用台账。一次 V49 负数隔离行动只允许一个后续处置计划（整笔来源数量），不重复扣账。

`POST /dispositions`：当前 Owner 提交 `requestKey,quarantineActionId,method,reason`，其中 method 是 `DISPOSAL` 或 `SUPPLIER_RETURN`。生成 `PENDING_APPROVAL`，只表示申请，不代表货物已经销毁或已由供应商签收。

`POST /dispositions/{id}/decision`：和隔离执行者、计划提出者均不同的 Owner 独立审批，生成 `APPROVED` 或 `REJECTED`。批准是处置计划的权限确认，**不是实际物流完成**。

`POST /dispositions/{id}/completion`：计划已批准后，由**不同于审核人的 Owner**上传 `reference,reason`，状态转 `EXECUTED_REPORTED`，记录时间、责任人和物理处置/退货参考信息。这是人工报告，仍不是可由外部供应商、承运商或回收单位独立验证的凭据。状态变化和执行报告**均不新增负库存流水**，因 V49 隔离时已经扣过一次。

## 4. REST 与 React Owner 工作台

API 前缀：`/api/v1/purchasing/lot-reconciliation`。所有路径的租户及 actor 由 Owner session 和 Spring CSRF 获取，客户端的 tenant/owner 不能伪造。

| 方法 | 后缀 | 用途 |
|---|---|---|
| GET | `/warehouses/{warehouseId}/counts` | 按仓查看已提交盘点、正式调整和未验证盘盈 |
| POST | `/counts` | 盘点观察提交独立审核 |
| POST | `/counts/{id}/decision` | 拒绝，或按业务规则独立复核并安全正式调整 |
| GET | `/warehouses/{warehouseId}/transfers` | 目的仓待签收、已确认和差异状态 |
| POST | `/transfers/{id}/acknowledge` | 第二位 Owner 登记目的仓实际清点 |
| GET | `/warehouses/{warehouseId}/dispositions` | 处置计划、审批、人工执行报告 |
| GET | `/warehouses/{warehouseId}/unplanned-quarantines` | 已做负库存隔离、未有处置计划的来源 |
| POST | `/dispositions` | 报废/供应商退货计划 |
| POST | `/dispositions/{id}/decision` | 不同 Owner 独立批准或拒绝 |
| POST | `/dispositions/{id}/completion` | 与审核人不同的 Owner 记录物理执行参考 |

React Owner → 采购与供应商 → V50 工作台按仓库展示三部分：V49 观察来源与 V50 审核状态、目标仓已过账调拨的到货确认或异常、已扣减过期品的处置申请/审核/人工完成报告。重要操作均需要文字说明，涉及真实过账会出现明确确认。当前是单租户 Owner 权限模型，不代表跨账号外部 WMS 的独立证明。

## 5. 验收与未完成内容

JUnit/Mockito：盘亏审核必须不同 Owner、仓库存量快照变化不能审核、盘亏只通过 V40 COUNT 负向差额、盘盈不能信用新增、跨仓第二人确认与数量不符只记异常不重过账、隔离计划必须有真实签名来源且执行报告不二次扣账。React/Vitest：盘点提交与复核不同 POST、签收 CSRF 和数量、处置计划及审核/报告分离。

**Java/React CI 成功不等于 PostgreSQL 18 真正 Flyway 迁移、Compose 端到端 V50 REST、多租户并发与真实仓库业务已验收。** V50 仍未实现盘盈补充来源后自动入账、目的仓 MISMATCH 复核纠错、物理处置物流独立验证、条码扫描设备。后续 V51 应对这些剩余异常开展有来源的闭环。Amazon 真实授权及 SP-API 联调按项目 Owner 的要求留到核心功能完毕后本地进行。
