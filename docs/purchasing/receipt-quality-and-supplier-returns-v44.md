# PO-007 · V44 本地采购收货质检、隔离台账、供应商退货与异常提醒

V44 核心语义：**实物收货记录 `erp_purchase_receipt` 不可倒写，实际入库来自 V39/V43 采购收货操作；质检与供应商退货是另外两组显式、可追溯的本地业务事实。** 不涉及 Amazon FBA/FBM、供应商实际签收、退款到账或财务应收，也不调用任何真实 SP-API。

## 数量来源与安全约束

1. `erp_purchase_receipt_qc`：每条实际收货至多一次完整质检，`inspected_quantity=accepted_quantity+rejected_quantity=receipt.quantity`。只有 V39 后带具体本地仓库的收货可以隔离；旧 V20 无仓库收货保留为未分配，不追认默认仓库。每个租户 `request_key` 唯一；原样请求重试可取回此前结果，复用不同内容拒绝。
2. V39 收货原本已把整笔收货件数一次性写入 `erp_local_stock_movement`（`PURCHASE_RECEIPT`），**质检后合格数量不能再加一遍**。若 `rejected_quantity>0`，质检追加**且仅追加一条** `QC_QUARANTINE` 负数仓库流水（`inspection_id` 来源唯一），从已记录的本地余额移走不合格件数。仓库库存不足时质检整笔拒绝，禁止用超卖方式“隔离”已出库的货物。合格件数不新增库存，避免双计。仓库 `recordedReceivedQuantity` 仍是历史原始收货总数，不表示全部合格；`onHandQuantity` 则包含质检负数变动，亦不自动算预留和 FBA 可售。
3. 质检会在 ACTIVE tenant 行锁下执行，对目标仓库加 `FOR UPDATE` 锁，并核对已记录的现有台账余额，协调 V40 出库/调拨和 V42 采购计划取证；插入 QC 快照、负数流水及 QC 事件属于一个 `@Transactional`，任何失败都回滚。不存在库存移动的历史仓库不可凭空构建可供隔离的数量。
4. `erp_supplier_return`：退货从 **不合格隔离件** 中创建，状态 `DRAFT`、`DISPATCHED`、`CANCELLED`。在同一 QC 上的所有非取消退货数量合计不得超出 `rejected_quantity`。`DRAFT` 仅预留隔离件，不确认发货；`DISPATCHED` 是 Owner 记录已向供应商发出（非供应商签收）。退货发出不会再次减本地库存，因为隔离时已经减过一次。被取消的草稿释放数量预留；发出后不可取消或被重复扣账。原供应商由关联实际收货采购单派生，不由浏览器随意指定。
5. `erp_purchase_quality_event`：追加式完整事件（`INSPECTED/RETURN_DRAFTED/RETURN_DISPATCHED/RETURN_CANCELLED`），包含操作者、数量、引用及时间，不删除既往实收、ASN 或其他本地业务事实。
6. **库存历史准确性边界**：质检发生在既有实收入账之后，未实施“收货时直接进入独立待检库存”的可用/待检物理货位模型。因此系统可保证已记录隔离不会双计或超量，但不能证明历史出库货品的具体批次是否来自此次收货，也不能保证库存已物理盘点。批次/序列号、质检采样标准、批量重检与质检签名升级为后续阶段。

## 质检异常待办

- `erp_purchase_quality_finding`：按租户、inspection、规则唯一，包含 `QC_REJECTED_PENDING`（有未申报发出退货处置的不合格品）和 `QC_HOLD_AGED`（已隔离超过 7 日且仍有未申报处置品）。**只有退货正式标记 `DISPATCHED` 才从待处置数量扣除；仅创建 DRAFT 草稿仍会继续提醒。**
- 人工 `OPEN/ACKNOWLEDGED/DONE` 有追加式 `erp_purchase_quality_finding_audit`。状态实际条件消失 → 下次完整扫描自动 `CLEARED`；若日后重新匹配才回到 `OPEN`，而非人工已处理就循环重开。
- `erp_purchase_quality_scan_run` 保留人工或计划巡检来源、计数和操作者。部署者可配置 `merchivra.purchase-quality.scheduler-enabled=true` 启用本地异常巡检；默认关闭，间隔 `merchivra.purchase-quality.scan-interval-ms` 默认 900000 ms。不会外发消息、自动下单或自动更新 Amazon。

## HTTP API

全部从 Owner Session 获取 tenant 和 actor，POST 必须 CSRF；不接受浏览器传入的 tenantId。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/purchasing/quality/orders/{orderId}/receipts` | 关联本地采购单的真实收货及质检/隔离统计，不自动补录旧收货 |
| POST | `/api/v1/purchasing/quality/receipts/{receiptId}/inspect` | `requestKey,acceptedQuantity,rejectedQuantity,reason`；完整实收质检、拒绝超额隔离、同事务写负数流水 |
| GET/POST | `/api/v1/purchasing/quality/inspections/{inspectionId}/returns` | 已知质检的退货历史 / `requestKey,quantity,reference` 草稿 |
| POST | `/api/v1/purchasing/quality/returns/{id}/dispatch` | 人工记录隔离品已向供应商发出，不重复扣库存 |
| POST | `/api/v1/purchasing/quality/returns/{id}/cancel` | 取消尚未发出的退货草稿，释放预留 |
| GET | `/api/v1/purchasing/quality/findings?filter=ACTIVE\|ALL` | 本地质检审核待办列表 |
| POST | `/api/v1/purchasing/quality/scan` | 当前租户隔离品待处置与超期扫描 |
| POST | `/api/v1/purchasing/quality/findings/{id}/state` | Owner 认领、人工处理或重新打开 |

React Owner → 采购与供应商 → 选中采购单 → 到货质检与供应商退货：可选择有明确仓库的真实收货行并输入不合格数量和原因；质检后从隔离品创建退货草稿，再独立确认发出；全局待办支持手动巡检与审阅。仓库台账流水标明 `QC_QUARANTINE`、QC ID 与业务原因。整个过程与 V43 供应商预到货通知和实收建立清晰区别。

## 验证和剩余范围

新增 JUnit/Mockito 用例：数量守恒、旧收货无仓库不予隔离、本地台账余额不足拒绝、单次隔离、同一请求原样重放、退货超额拦截、重复发出幂等与绝不再次写库存、跨租户待办和计划巡检。Vitest 契约校验历史收货的 `warehouseId=null`、CSRF、质检明细、退货草稿与发出两步、审核操作。以相应提交的实际 GitHub CI 结论为准；**后端 Mock 单测和前端 TS/Vitest 通过并不意味着 PostgreSQL 18 V44 Flyway/真实 HTTP/并发性能已经实测。**

后续包括到货时先隔离待检的更严格流程、收货差异短装/溢装与供应商索赔审批、供应商 RMA 物流证明、供应商信用票据/退款、序列号/货位/质检采样、真实 Amazon 入库对账。真实 Seller SP-API OAuth 和端到端验收按既定开发顺序最后完成。
