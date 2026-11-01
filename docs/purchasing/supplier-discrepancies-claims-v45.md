# PO-008 · V45 采购差异快照、供应商索赔与可信绩效

状态：V45 核心代码与测试已提交；最终 Java/React CI 以对应提交的 GitHub Actions 结果为准，PG18 / Flyway / 真实 HTTP 多租户及数据库并发测试仍需专项验收。所有记录都是**本地 ERP 人工报告及审核事实**，不会调用 Amazon、供应商系统、物流跟踪或金融支付接口。

## 设计与来源约束

### 1. 三种采购数量差异（V45 `erp_purchase_variance`）

- `PO_SHORTAGE`：仅针对已经批准但尚未完全接收的 PO 明细，在 Owner 明确确认观察的时刻以**订单数量**作为 expectedQuantity，累计真实 `erp_purchase_receipt` 件数作为 observedQuantity。若尚未收足，差值为暂时不足（可能仍会再到货），**不自动认定永久短装**，不直接改 PO 收货状态。
- `ASN_SHORTAGE`：必须关联已人工报告供应商发货的 ASN，expectedQuantity 来自 `erp_purchase_asn.quantity`，observedQuantity 只统计 `erp_purchase_receipt.asn_id` 真实关联收货。未关联 ASN 的收货不能算到该 ASN，ASN SHIPPED 也不等于承运商已验证。
- `OVERAGE_UNPOSTED`：Owner 输入仓库人工观察的额外实物件数；expectedQuantity 是当时 PO 行尚可接收件数，observedQuantity = expectedQuantity + 报告额外件数。**绝不生成真实收货或 `erp_local_stock_movement` 正数流水**；必须由后续单独规范的审批/采购调整流程处理超额，不绕过 PO 行上限。
- 三种观测均快照来源采购行、ASN（如有）、本次期望/已记录件数、差异件数、原因和操作者。租户 `request_key` 幂等；数据库索引 `NULLS NOT DISTINCT` 确保同一 PO 行+类型+ASN 最多一条记录（不因 NULL ASN 重复报告）。超过一个观察点的后续变化由已有报告的来源新鲜度守卫处理，不能把同一记录覆盖为新证据。
- 状态：`OPEN` → 另一位 **不同 Owner** 复核 `REVIEWED` → 人工 `CLOSED`。复核前重新核对仓库实际收货、ASN 申报和 PO 收货余量；若事实改变返回 409，不能凭旧快照复核。差异关闭不表示供应商承认责任或已赔偿。

### 2. 供应商索赔（V45 `erp_supplier_claim`）

- 来源 **二选一**：已有独立复核的 `erp_purchase_variance`，或 V44 已完成的 `erp_purchase_receipt_qc` 不合格证据。所有来源经租户和 PO 联结推导供应商，前端不能提交任意 supplierId 或 tenantId；索赔数量只能为源差异/不合格件数的正整数子集。
- 每份来源最多一份索赔（包含历史取消的索赔），避免相同证据二次重复索赔。索赔类型 `REPLACEMENT`（申请补发）或 `CREDIT_REQUEST`（申请信用折让）。**CREDIT_REQUEST 不是已确认信用票据、退款、资金入账或 Amazon 结算。**
- 独立工作流：`DRAFT` → 提交 `SUBMITTED` → 由与创建者不同的 OWNER 审批 `APPROVED` → 填入至少 5 字符的内部结果说明后 `CLOSED`。可从 DRAFT/SUBMITTED 取消；已经审批的索赔不可取消。状态只是本地审核记录，**APPROVED 不会自动将索赔外发给供应商或同步财务**，CLOSED 也不证明外部完成。
- 对基于差异的索赔，在创建、提交和审批前都按原采购单行/ASN 当前记录重新核对来源快照。来源变化（新增收货/订单状态/ASN 变化）会拒绝后续提交或审批。V44 不合格件数是独立不可覆盖的质检记录，不依赖流动中的采购剩余量。
- `erp_supplier_case_audit`：记录每次差异观察、独立复核、差异关闭、索赔草稿、提交、审批、取消与关闭事件；并以 tenant、来源复合外键隔离。所有变更由 session OWNER + CSRF 操作。无邮件、EDI、外部财务与 SP-API 写入。

### 3. 供应商数据事实统计

`GET /api/v1/purchasing/disputes/supplier-performance` 按租户各供应商汇总：已审批/正在收货/已完成 PO 数量与其订单行订购数量、真实累计已收件数、已完成质检的不合格数量、尚未关闭差异数、未完成内部索赔数、已申报发货且未收足并超过预计日期的 ASN 数。表格显示 `订购/实收` 而非虚构的“准时送达率”或“到货及时率”；目前没有经承运商验证的 ETA，也不自动将原币采购金额转换并相加。

## HTTP API

均为 `/api/v1/purchasing/disputes` 下端点；所有动作由 Owner Session 推导 tenant/actor，POST 需 CSRF：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET / POST | `/orders/{orderId}/variances` | 获取差异 / 人工建立 `requestKey,lineId,kind,asnId,reportedExcessQuantity,reason` 快照 |
| POST | `/variances/{id}/review` | 不同 Owner 复核新鲜证据 |
| POST | `/variances/{id}/close` | 关闭已经复核的差异 |
| GET | `/orders/{orderId}/claims` | 当前采购单的索赔 |
| POST | `/claims` | `requestKey,varianceId,inspectionId,requestedQuantity,remedy,reason`，来源 ID 严格二选一 |
| POST | `/claims/{id}/submit` | 单独提交内部审批并校验来源 |
| POST | `/claims/{id}/approve` | 不同 Owner 独立审批并重新校验来源 |
| POST | `/claims/{id}/close` | `note` 必填，关闭仅代表内部已记录处理结果 |
| POST | `/claims/{id}/cancel` | 取消尚未审批的索赔 |
| GET | `/variances/{id}/events` | 追溯差异事件 |
| GET | `/claims/{id}/events` | 追溯索赔事件 |
| GET | `/supplier-performance` | 仅租户本地真实数据的供应商汇总 |

React Owner → 采购与供应商 → 收货差异、供应商索赔与交付绩效面板。先选采购单；按 PO 行或已上报发货 ASN 登记差异；另一位 Owner 复核后才出现可索赔来源（已完成 V44 QC 不合格也可直接作为来源）；随后草稿/提交/独立审批/人工关闭。表格提供审计查看与全部供应商实际统计。

## 验收与后续

新增 Java/Mockito 规则、来源变更、错误态、双人操作、锁与财务/库存不触碰测试，以及 React/Zod/Vitest 来源 payload、CSRF、审批、统计和审计契约。上述 CI 不等于 PG18 实例上的 Flyway 真实执行或用户本地 Amazon 授权验收。后续 V46 可实现严格的到货待检货位、批次、质检放行审批和供应商外部 RMA 证明；对于超额实物，需先有完整的采购调整和授权入账流程，不能在本模块直接提高仓库可售库存。
