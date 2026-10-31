# PO-006 · V43 本地供应商 ASN 预到货与交期异常（2026-10-31）

**范围限定**：仅记录由 Owner 在本地 ERP 人工输入的供应商 **Advance Shipment Notice**（ASN，预到货通知）与供应商申报的发货事实。它不等于承运商扫描、国际货件位置、真实签收、Amazon FBA Inbound 货件或 Amazon SP-API 数据。**库存实际收到的数量只来自采购收货 `erp_purchase_receipt`，并继续写入 V39/V40 本地仓库台账。** 未经授权不得声称真实物流对接成功。

## 数据结构及证据

- `erp_purchase_asn`：按 tenant、已批准采购单、唯一采购行、目标本地仓、数量、预计到货日期、可选参考号创建。每个租户 `request_key` 唯一，原样请求重试返回原 ASN（若之后人工修改过 ETA，请刷新而非重放旧参数）。状态 `DRAFT`、`SHIPPED`、`CANCELLED`；`SHIPPED` **仅表示 Owner 记录了供应商申报发货**，不会自动识别承运商发运，也不改变采购单状态。
- `erp_purchase_receipt.asn_id`：可空，新增复合 FK `(tenant_id,purchase_order_id,purchase_order_line_id,asn_id)` 到 ASN。V20/V39 旧收货值为 NULL，**不会默认为任一 ASN 收货**。只有 Owner 显式选择 ASN 时，已批准采购单的真实本地收货才会与 ASN 关联，SKU、采购行、仓库均要一致，且该 ASN 状态必须为 `SHIPPED`。
- `erp_purchase_asn_event`：独立追加式证据，记录通知创建、供应商申报发货、预计到货日修改的前后日期与原因、取消、每次 ASN 关联真实收货件数。不删除原记录，也不会覆盖供应商/承运商来源数据。
- 一个采购行所有未取消 ASN 件数，加上**不关联 ASN 的历史/人工已收货件数**，不得超过订购数量；单条 ASN 关联的真实收货合计不得大于其申报件数，采购单全部收货合计亦不得超订单行订购数。取消 ASN 前必须没有任何实际关联收货。采购单行锁序列化 ASN 创建、标记发货、改 ETA、取消和实际收货，失败回滚采购收货和本地库存流水。
- ETA 范围为当前 UTC 日期前 365 天至后 730 天；仅是供应商提供的**计划日期**。修改必须带原 ETA 快照及 5～300 字符原因，不允许用旧页面覆盖并发变更；不自动变更 Amazon 任何货件状态。

## 逾期与到货提醒

- `erp_purchase_asn_finding`：唯一 `(tenant,asn_id,rule_code)`，`OPEN / ACKNOWLEDGED / DONE / CLEARED` 工作队列；审核操作有 `erp_purchase_asn_finding_audit`；`erp_purchase_asn_scan_run` 保存扫描触发来源、数量、操作者和时间。
- **仅扫描供应商已申报发货 (`SHIPPED`) 且已批准/正在收货的本地 PO，且该 ASN 的真实关联收货还不足申报件数**。ETA 已过 → `SUPPLIER_ASN_OVERDUE`；ETA 处于当前日期至 3 日内 → `SUPPLIER_ASN_DUE_SOON`。不能因为一个 DRAFT/无 ASN 的 PO 待收货就推断货件已经发出。
- 人工标记 `DONE` 不再重复提醒，直至条件消失而 `CLEARED`，以后再次出现才重开为 `OPEN`。延期 ETA、收货完全或采购单完结后，在下一次完整扫描中自动清除不再匹配的旧待办。
- 手动扫描 Owner 可点击按钮。定时扫描默认**关闭**；部署者显式启用 `merchivra.supplier-asns.scheduler-enabled=true`，间隔 `merchivra.supplier-asns.scan-interval-ms`（默认 900000 ms）。扫描对 ACTIVE tenant 行加锁以序列化多实例运行，不调用 Amazon API，也不自动发送邮件。

## HTTP API 与 React

所有 API 根据登录 Session 自动确定租户/Owner；POST 请求须经标准 CSRF；不由浏览器提供 tenantId。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET/POST | `/api/v1/purchasing/supplier-asns/orders/{orderId}` | 本地采购单 ASN 列表 / 创建 ASN；提交 `requestKey,lineId,warehouseId,quantity,expectedArrivalDate,trackingReference` |
| POST | `/api/v1/purchasing/supplier-asns/orders/{orderId}/{asnId}/ship` | Owner 人工记录供应商已申报发货（非承运商确认） |
| POST | `/api/v1/purchasing/supplier-asns/orders/{orderId}/{asnId}/cancel` | 取消尚未发生实际关联收货的 ASN |
| POST | `/api/v1/purchasing/supplier-asns/orders/{orderId}/{asnId}/eta` | `expectedArrivalDate,expectedPreviousEta,reason` 有审计的 ETA 更新 |
| GET | `/api/v1/purchasing/supplier-asns/findings?filter=ACTIVE\|ALL` | ASN 交期异常队列，最多 100 条 |
| POST | `/api/v1/purchasing/supplier-asns/scan` | 当前租户 ASN 异常巡检 |
| POST | `/api/v1/purchasing/supplier-asns/findings/{findingId}/state` | 手工确认待办状态 `OPEN/ACKNOWLEDGED/DONE` |
| POST | `/api/v1/purchasing/orders/{id}/receive` | 保留 `lineId,quantity,warehouseId`，新增可选 `asnId`；非空时必须匹配发货申报与剩余件数，写收货记录、ASN 事件及本地库存流水于同一事务 |

React Owner → 采购与供应商：选中已审批采购单后录入 ASN、选择目标仓和预计日期、明确上报发货、更新 ETA，查看申报件数与实际关联收货件数；可从已发货 ASN 选择**关联实际收货**，并通过原有收货按钮确认。页面下方独立查看到期/逾期待办并人工标记。已完成订单只读呈现历史 ASN。

## 验收范围及未实现

新增 JUnit/Mockito 用例：不合法 ASN、未批准采购单、超量申报/旧收货、同 key 重试、取消已有真实收货、并发 ETA 快照、跨租户待办、计划扫描；既有采购收货测试增加关联 ASN 的仓库/采购行一致性、余额限制与审计。Vitest 增加 CSRF、申报/实收到货区分、ETA 请求与手工扫描测试。

**代码或单测通过不等于真实 PG18/Flyway、HTTP 租户/CSRF、并发与跨进程锁验证通过。** 本地 ASN 不会验证承运商轨迹、物流签收、实际运输单位、进口清关、supplier EDI 或 Amazon FBA Inbound。V44 可继续完善采购签收质检、仓库退货及差异处理，待核心功能编码完成后再统一做真实授权联调。
