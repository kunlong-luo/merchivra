# V193 · 冻结财务报表的数据库级不可变性

**2026-11-22 · P0-C 内部证据安全阶段。** 实现：[Flyway V103](../../apps/server/src/main/resources/db/migration/V103__profit_snapshot_immutability.sql)、既有 [V64 批处理](../../apps/server/src/main/java/org/merchivra/erp/finance/ProfitReportJobService.java)、[真实 PG18 负例](../../scripts/smoke_profit_snapshot_guards_v193.sql)。这仍是**内部合成/本地工程证据**，不是独立验证的全成本净利润。

## 根因

V62 的四张表已保存冻结批次、订单、SKU、审计事件及本地 `extract_sha256`。V192 在 V188 利润预览时核对每个订单与 SKU 的销售、Amazon 费用、FBA 费用、历史 COGS 和贡献额。但 V62 原生数据库此前未阻止已采集行直接 UPDATE/DELETE，也没有防止 job 仍在 READY 时给**已处理的上一个批次**晚补 SKU。Java 条件写入和只读金额守恒，不能替代数据库不可变防线。

## V103：在已有 V62 表上补约束，不重造账本

| 表 | 数据库级约束 |
| --- | --- |
| `finance_profit_report_job` | 保持创建时租户、Marketplace、请求幂等 key、日期区间、币种、Owner、初始订单数及原币人工 Ads 参考不变；SUCCEEDED 后禁止修改，所有删除禁止 |
| `finance_profit_report_order` | 初始仅允许 PENDING 进入未处理队列；只允许一次 PENDING→ELIGIBLE/BLOCKED，订单与批次身份不可改；首次采集后 UPDATE/DELETE 拒绝 |
| `finance_profit_report_sku` | 行插入后不能 UPDATE/DELETE；只允许挂到**当前尚未提交的 ELIGIBLE 订单**，`cohort_position < processed_orders` 或成功结束后的晚插拒绝 |
| `finance_profit_report_audit` | 只追加；UPDATE/DELETE 永远拒绝 |

SKU 守护先 `FOR SHARE` 锁定 job 状态，再 `FOR UPDATE` 锁定订单；跟现有 V64 的 job 锁→首次捕获订单→SKU 插入→提交批次进度顺序一致。原有批处理每事务最多 10 订单、每份任务最多 5000 订单的业务限制不变。

**历史证据边界**：不修改 V62 旧 Flyway、不回填历史行或重写摘要。V103 只能保证**安装之后**数据库约束对常规 DML 的保护，不能伪造旧数据自创建起无更改的证明。V62 `extract_sha256` 是 JVM 本地来源拼接摘要，不是 Amazon/银行签名或不可抵赖的外部证据；本轮**没有**声称规范化独立复算或平台收费真实性。未来版本化规范摘要必须明确区分旧/新捕获，不得事后冒充历史认证。

## 测试与财务门禁

完整 A3 在真实 PostgreSQL 18 上运行 [V193 SQL](../../scripts/smoke_profit_snapshot_guards_v193.sql)：先确认 public Flyway V103 与四个触发器已安装，再用临时的 V62 形状表执行**真实触发函数**。正例覆盖首次采集、多 SKU、后续批次、成功完成；负例覆盖未采集/BLOCKED 的 SKU、已捕获金额更改/删除、历史 SKU 修改、晚追加、已完成 job 修改，以及审计删除。事务末尾 ROLLBACK，不写 public 财务表、不访问外部 API。Java 25 单测补充源码结构守卫。

**独立财务结果仍然不可用**：V188 继续只是未核验原币贡献敏感度，`realizedNetProfit=null`。没有验证 Amazon/Ads/物流/银行收费、退款 COGS、FX、会计政策、GL 或外部 WMS 物理实收；G1/DEC-06/07/08 待签署，Seller/Ads OAuth 关闭，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。请以 [精确 SHA 的完整 A3](https://github.com/kunlong-luo/merchivra/actions/workflows/milestone-validation.yml) 实际运行结果为准。
