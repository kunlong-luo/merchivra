# V199 · 结算声明净额缺失不得视为三方一致

**状态：代码实施中，须核对精确 main SHA 的 CI/A3。** V198 已检查结算报告行数、混币、同一 Finances 事件多结算归属及相同编号多报告争议，但 V54 允许原报告 `declared_net` 为 NULL，原 Java 判定仅在有声明金额时比对。这使缺少报告声明净额时，明细和 Finances 金额相等也可能被标成 `SOURCE_AMOUNTS_AGREE_UNVERIFIED`，产生“有三方证据一致”的假阳性。

## 修改范围

- 复用 `SettlementReportComparisonService.decision`，在完整行数、原币与来源归属冲突检查之后，把缺少原报告 `declared_net` 明确判为 `REPORT_DECLARED_NET_UNAVAILABLE`。保留真正数值零 `0.000000` 与未知 NULL 的区别，不增加 DB 默认值、不修改历史数据、不重造来源审批。
- V56 结算独立 Owner 审核继续只能审批 `SOURCE_AMOUNTS_AGREE_UNVERIFIED`；如果旧审核来源状态改变，按原有比较状态新鲜度返回 `STALE`，不能凭人工意见自动解锁。
- Next 的对账 Zod、来源审核状态合同及原有结算工作台同步接受新阻断状态并用中文说明；`reconciled=false`、`bankVerified=false` 始终不变。
- Java 增加缺失声明净额且其他两项相等的反例，包括真实零金额区别、冲突优先序；PG18 继续运行 V198 的原 SQL TEMP/ROLLBACK 回归，专门验证真实可空声明净额在 SQL 上仍为 NULL，不被零化。

本轮验证仅说明**本地来源数据质量**，不代表真实 Amazon 报告被第三方验证。没有新的 Amazon/Ads 出站、自动成本抵扣、退款 COGS 冲回或库存/会计写入。外部授权、银行核证、G1/DEC-06/07/08、真实净利润仍待批准；`realizedNetProfit=null`、生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。项目综合评分仍按 [68/100 基线](../governance/project-scorecard-2026-11-22.md)。
