# V78 · 采购/FBA 异常统一运营待办与本地处理 SLA

## 边界与架构

V78 沿用 **V77 人工处理单**（`fba_purchase_exception_case` / audit / policy）、**V76 证据投影**、**V10/V67/V69 统一运营待办**（`ops_finding` / `ops_rule_setting` / 审计与可选 scheduler）。Flyway V75 **仅扩展原有两张规则表的允许类型**，不增加消息总线、第二套待办、邮件发送或假定 Amazon SLA。

- `FBA_PO_EVIDENCE_CHANGED`：本地 ASN/PO/FBA 关联分类等证据指纹不再等于 V77 上次人工确认的指纹时，标记需要重新确认；即使原处理单 RESOLVED 也不能以旧结论自动通过。
- `FBA_PO_EXCEPTION_OVERDUE`：V77 已登记处理单尚未 RESOLVED 且人工截止时间已过、站点 V77 期限提示已启用时，提示超期；不是 Amazon 入仓时效。
- 站点 Owner 可在既有 Ops 规则界面分别暂停/启用这两项；记录版本冲突保护与追加的原有 V69 审计。V77 人工期限本身仍可配置，不新增并行规则策略表。
- 手动「立即巡检」和显式开启的 `merchivra.operations.scheduler-enabled=true` 复用同一租户级事务、锁和既有队列幂等键 `(tenant,marketplace,rule,source)`。默认**没有定时巡检、站外通知、Amazon 写入**。

## 处理时效

`GET /api/v1/fba/marketplaces/{marketplaceId}/purchase-reconciliation/workbench/metrics`

返回当前会话租户和指定站点下**全部已经登记的本地人工处理单** `total/open/acknowledged/resolved/overdue`，以及 `meanResolutionHours`。后者依据当前 RESOLVED 记录最近一次 RESOLVE 审计时间与原创建时间差计算，最多保留 1 位小数；没有可计样本时返回 `null`，不是 0 小时。处理单以 V77 人工创建为起点，不代表货件的物理处理耗时或 Amazon SLA。和 V76/V77 页面最多 100 张货件/500 行的来源证据视窗是**不同口径**；前端明确标注。

## 一致性与限制

- 运营扫描只参考 V77 当次**有界证据视窗**内已登记处理单，不能把未被读取的历史货件判定为「无异常」。不在视窗内的原有 Ops 信号保留，截断时保留该站点旧信号，等待下次可完整读取后再重新判定。
- 两类待办只投射本地工作流结果。Ops 里将其标记 DONE 并**不能**代替 V77 的重新确认/独立差异或身份复核；源数据已恢复后，下次扫描转 CLEARED。
- 来源为 MOCK 的货件保留 MOCK，其他本地人工来源保守标注 UNVERIFIED；从不标记 Amazon VERIFIED 或计入 FBA 库存。
- 未新增自动收货、采购下单、实际物流跟踪或微信/邮件/推送。租户数据由 Owner Session 推导，不接受客户端 tenantId。没有启用真实 Seller OAuth/SP-API。负载或多租户极限性能未通过生产验证。

## CI 验证

新增 `scripts/smoke_fba_purchase_ops_v78.py`：在 PostgreSQL 18 + Java 25 的 disposable Mock 后端上复用 V76/V77 虚构货件，模拟截止时间与 ASN 状态变化，检查 SLA、Ops 幂等入箱、站点规则版本审计/暂停、信号恢复时清除，以及没有向库存写入。以实际 CI 结果为准，不能称为 Amazon 生产验收。
