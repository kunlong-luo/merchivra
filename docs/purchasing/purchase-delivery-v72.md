# PO-072 · V72 本地采购交付进度与异常证据看板

**产品阶段 V72 / Flyway V69，2026-11-02。** 复用 V20 采购审批及收货、V43 ASN 人工申报及到期队列、V45 采购差异、V46 收货质检、V37/V71 补货采购来源。只读汇总，**没有新建采购、审批、通知或待办队列**。

## API

GET /api/v1/purchasing/delivery/overview

- 按 Owner 当前 Session 获取当前 ACTIVE Tenant；不接受浏览器传入 tenantId。只读取 APPROVED / RECEIVING / RECEIVED 采购单的前 300 条采购行；DRAFT 和 SUBMITTED 不能被误认为已批准交付义务。
- orderedQuantity 来自不可变采购单行；receivedQuantity 是 V20 本地真实登记收货，remainingQuantity=max(0, ordered - received)。缺少外部物流数据意味着**未知外部运输事实**，不是已发货或承运商已签收。
- draftAsnQuantity 是仍在 DRAFT 的 ASN 申报数量，**不代表已发货**。shippedAsnQuantity 是 Owner 人工标记 SHIPPED 的供应商声称件数。reportedUnreceivedQuantity 仅减去**明确关联同一 ASN 的真实采购收货**，不把未关联的收货暗自算到任一申报货件上。unlinkedReceiptQuantity 单独展示旧收货。
- earliestReportedEta/latestReportedEta 仅取 SHIPPED 且未完全关联收货的 ASN 供应商计划日期。没有 ASN、ASN 仍在草稿、已经全部关联收货时返回 NULL。多货件不合并为虚构的承运商 ETA。
- awaitingQcReleaseQuantity 是 V46 PENDING_QC 或 INSPECTED 批次的已收件数；releasedQcAcceptedQuantity 只有 V46 RELEASED 才统计经 QC 接受的数量；qcUnknownQuantity 记录历史缺 V46 批次的实收数量。本地收货 ≠ 经审批的仓储可售货品 ≠ Amazon FBA。
- unresolvedVarianceCount 来自 V45 OPEN/REVIEWED 未结采购差异行；V37/V71 补货来源原样关联，缺失保持 NULL。currency 原样展示，不跨币种汇总金额。

## 状态与异常优先级

- 未结差异：DISCREPANCY_REVIEW。
- 已收到全部采购件数，但仍待 QC 放行：AWAITING_QC_RELEASE；缺历史 QC 批次证据：QC_EVIDENCE_UNKNOWN；否则仅为 LOCAL_RECEIVED（本地登记）。
- 仍有供应商申报已发却未关联实收的货件，最早申报 ETA 早于当前 UTC 日期：REPORTED_ETA_OVERDUE；在 0～3 天内：REPORTED_ETA_DUE_SOON；更晚或无申报日期：SHIPMENT_REPORTED。这些都不是承运商真相。
- 仅有 ASN 草稿但尚未发货：ASN_AWAITING_DISPATCH；没有有效 ASN：NO_ASN_RECORDED。没有日期就不可能判为逾期。

React 采购与供应商页面直接新增只读交付表，点开采购单链接后复用原有 ASN 管理、分批收货、V45 差异与 V46 质检。V43 原人工/可选计划巡检仍是**唯一 ASN 到期待办队列**，不新建平行队列、不发送邮件。

## 验收

Java 测试覆盖来源状态/时间判断和租户边界；Vitest 校验 Session-only 的只读 API、NULL ETA、数量范围。scripts/smoke_purchase_delivery_v72.py 仅在 localhost、PG18、Mock 开启、SP-API/OAuth 关闭的临时数据库中，通过两个**不同 Owner**完成本地采购审批，随后按 HTTP 创建 ASN 草稿、供应商申报发货、修改 ETA、调用原 V43 扫描、分批收货及 V45 差异复核，确认最后仅为 QC 待放行，而非 FBA。Flyway V69 只新增索引，不创造新的来源状态事实。CI 检验不替代真实供应商、承运商和 Amazon 生产联调。

## 不在本阶段

没有真实供应商确认到货、承运商 EDI/追踪、可信 ETA、无 ASN 时的任意超时 SLA、自动催单、外部微信/邮件通知、资金付款、真实 FBA 到仓扣账与 Amazon Seller/Ads 授权。G1/G2/G3 仍需人工决策。
