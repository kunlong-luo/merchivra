# PO-073 · V73 供应商履约证据评分与本地采购人工催办

产品阶段 V73，Flyway V70。沿用 V20 采购、V43 ASN 与到期扫描、V44 质检、V45 差异、V46 质检放行、V72 交付看板；绝不重复开发外部物流、审批、付款或供应商通知系统。所有数据仅为当前租户本地人工业务记录，不验证供应商或承运商事实。

## 供应商 90 天证据指标

GET /api/v1/purchasing/supplier-performance/scorecards 仅按当前 Owner Session 租户访问 ACTIVE Supplier，最多 200 个。

- 完整 ASN 样本：只统计 V43 已申报 SHIPPED、物理收货全部显式关联该 ASN、关联数量精确等于 ASN 申报数量、最后一次收货发生在过去 90 天的 ASN；不包含 ASN DRAFT、零收货、未关联收货、部分到货或超量。没有样本的准时比例是 NULL，不是 0。
- 本地准时样本：将最后一笔实际本地收货时间的 UTC 日期和该时点之前已登记的最后一条 CREATED/ETA_UPDATED ASN 事件中的供应商人工 ETA 比较；事件在实际到货之后被修改时不可改写过去的收货评分。ETA 非承运商承诺，准时样本不是第三方物流 OTIF。
- 质检：过去 90 天 V44 质检的完整实收记录，SUM(accepted_quantity) / SUM(inspected_quantity)，零样本 NULL；没有质检不等于不合格；质检接受也不等于 V46 双 Owner 最终可售放行。
- 供应商差异 V45 OPEN/REVIEWED 只显示数量，不自动扣分或认定供应商责任；未结纠纷不是确定违约。
- 参考分数 = 60% × 完整 ASN 本地准时率 + 40% × 质检接受率，四舍五入一位小数，取值 0～100。仅 ASN 完整样本 >=3 且 QC 批次 >=3 且已检件数>0 才给出暂定分数 PROVISIONAL；否则证据不足 INSUFFICIENT_EVIDENCE、分数 NULL。严禁基于该分数自动禁用供应商、自动采购、付款或排行处罚。

## 审批后采购前发货人工跟进

使用 Flyway V70 的 erp_purchase_followup_finding / erp_purchase_followup_audit / erp_purchase_followup_scan 三表。已有 V43 erp_purchase_asn_finding 仍为唯一 SHIPPED ASN 到期/逾期待办来源，V73 绝不对已发货 ETA 建平行队列。

- 默认审批后第 7 个 UTC 日触发；部署选项 merchivra.purchase-followups.after-days 允许 1～60，定时调度 merchivra.purchase-followups.scheduler-enabled 默认关闭，显式启用才使用默认每 900000 ms 的本地扫描。不发微信/邮件/供应商消息，无 Amazon 写入。
- 扫描限 ACTIVE tenant，租户行 FOR UPDATE 序列化同租户并发扫描。只选 APPROVED/RECEIVING、已过人工跟进日、行仍有本地实收余额的采购明细；未审批、取消、已收齐、无审批时间均不催办。
- ASN_COVERAGE_GAP 表示该行订货数量超过非取消 ASN 承诺件数与不关联 ASN 的历史实收件数之和；与 ASN 关联的实收不再重复算作新增覆盖，避免错误消除缺口。没有 ASN 只是应当跟进，不等于供应商发货/物流逾期。
- ASN_NOT_DISPATCHED 表示 ASN 加未关联收货已覆盖订购件数，但只存在尚未发货的 ASN 草稿且尚无 SHIPPED ASN。与上一规则在同一采购行互斥；有 SHIPPED 且全额 ASN 覆盖之后由 V43 处理其 ETA。
- GET /api/v1/purchasing/supplier-performance/followups?filter=ACTIVE|ALL 当前租户最多返回 100 条；POST /scan 人工扫描；POST /followups/{findingId}/state 只允许 OPEN / ACKNOWLEDGED / DONE，写追加式审计。扫描保持 ACK/DONE，条件消失后 CLEARED，再次满足才回到 OPEN。清除状态不允许任意改写。所有写操作继承 Owner Session / CSRF。
- Owner 在原采购页查看和手工处理催办，点击打开原有采购单/ASN/收货；不创建新采购引擎或站外提醒。

## 测试与风险

Java 25 Gradle 负责评分的样本门槛/权重/租户过滤和人工跟进状态审计；React19/Vitest/Zod 负责 NULL、数量/枚举、Session、CSRF；scripts/smoke_supplier_performance_v73.py 通过仅限一次性 localhost PG18、Mock 模式、SP-API 关闭的模拟数据，验证双 Owner 真实审批、ASN 缺口→部分覆盖→全草稿→已申报发货的任务自动清理，和 3 个完整 ASN/3 个真实 QC 样本（2 个准时，30 已检/28 接受，参考分 77.3）。CI 通过不代表实际供应商承诺到达、承运商送达或 Amazon FBA 货件状态。

后续需要单独授权或开发：真实供应商对账、外部提醒、交付承诺时间签收证据、跨仓多币种预算、资金审批、自动采购、运营通知、真实 Amazon Seller/Ads 接入与 G1/G2/G3 生产验收。
