# V187 · 全期间跨来源费用冲突风险与利润阻断（非重复扣费裁决）

**验收状态：业务代码已合并，精确 main SHA 完整 A3 7/7 通过。**

V187 整期跨来源费用疑似重复风险技术验收（2026-11-21）：业务 [PR #192](https://github.com/kunlong-luo/merchivra/pull/192) 已合并，精确业务 main SHA `b2e5cb24f6faedf7db8eb05ca708099926dae422`；合并前 [快速 CI #44868562885](https://github.com/kunlong-luo/merchivra/actions/runs/44868562885) **SUCCESS**（Java 25/Gradle、React19/Next16/Vite、Compose）；精确 SHA [完整 A3 #44868689550](https://github.com/kunlong-luo/merchivra/actions/runs/44868689550) **run_attempt=1、7/7 核心任务 SUCCESS**（真实 PG18/Flyway、V165/V187 合成 Owner HTTP 八类来源重叠及 V168 共用结算行统计、401/400/404、无匹配仍阻断、Java、Next/Vite Chromium、Compose Session/CSRF/恢复；独立设计专项策略 SKIPPED）。V187 **仅提供本地疑似跨来源冲突整期计数及最多 20 个脱敏样例**，20 个示例的上限不影响整期来源风险统计；本地身份编号或文档摘要相同不构成真实重复扣费或费用真实性证明，未匹配不代表成本为零或来源已核验。**V162/V64 已认证净利润、真实 Amazon/Ads、外部银行、G1/DEC-06/07/08 会计口径及退款 COGS 回转仍不解锁；Seller/Ads OAuth OFF，生产 #104 NO-GO。**\n\n**交付范围：** 在现有 V165 八种本地身份线索、V168 多来源共用导入结算行以及 V186 单来源费用归属基础上，增加一个完整期间的只读风险汇总，Next.js 16 利润工作台显示真实来源数、相互重叠风险与最近不超过 20 个示例。**没有任何新财务写入、费用重复确认、虚构外部原始单据或利润解锁。**

## 复用而非重新造轮子

V186 保证本地来源在 SKU、ORDER、PERIOD 中只有一个有效主分摊事实，但**两个不同的 V91 UUID 可能使用相同发票摘要、参考编号或同一 V94 导入结算报告行**。必须区分：

- **V186 同一个 V91 来源不重复加总**：数据约束及读取口径的单一来源归属。
- **V187 两个不同来源可能代表一笔外部费用**：V165/V168 的本地身份重叠风险。不能根据编号或散列相同直接断言重复扣费，更不能凭“未匹配”声称没有重复。
- **真实外部重复收费裁决和允许入账费用**：当前缺 Amazon Ads/Finances/Settlement 原始认证、独立财务审核与银行核对证据，仍不可用，不把主观审核变成 V162 `REVIEWED_COST`。

## 整期 API

`GET /api/v1/finance/marketplaces/{marketplaceId}/cost-cross-source-risk?periodStart=YYYY-MM-DD&periodEndExclusive=YYYY-MM-DD`

沿用 Owner Spring Session 和登录权限；当前 tenant + 活跃 Marketplace 校验，错租户/站点 404、未登录 401；全结果 `Cache-Control: no-store`。单个只读 `REPEATABLE_READ` 事务中两次读取：第一 SQL 对半开 V91 `business_date` 整期（最多 366 天）扫描并聚合 count，第二 SQL **仅取最近 21 条风险来源**判断最多 20 个可见示例及是否截断。**20 条限制绝不影响统计总数**。无需新增 Flyway/表、审核动作或 CSRF 例外。

SQL 重用 V165 的八个准确身份交集：其他 V91 手工来源同 SHA-256、手工 Ads reference 同源键、离线 Ads report 同源键或同 SHA、Finances 事件键、SP-API 结算报告键或同 SHA、人工结算声明键。另使用 V168 的同一 Marketplace/导入报告 UUID/行号下不同 V91 来源共用结算行检查（带 `EXISTS`，避免 V94 多个链接乘法）。跨系统候选不受请求期间限制，以免历史报告与本期成本发生重叠时被漏检；候选仍严格绑定同一租户/Marketplace。

输出：

- `sourceCount`：全期间 V91 来源数，**每笔来源只计一次**，不使用 `JOIN V92 lines` 或费用金额总和作为可认定扣减额。
- `potentialIdentityOverlapSourceCount`：任意八类本地身份重叠的来源数；每个来源无论命中几种信号只计一次。
- `sharedSettlementLineSourceCount`：共用同一导入报告行的来源数。
- `anyLocalCollisionSourceCount`：两类风险并集，不重复计算交集来源；`noLocalMatchSourceCount` 是差集，满足两者之和等于 `sourceCount`。没有本地匹配**不是**无重复费用证明。
- `examples`：最近最多 20 个来源 UUID、业务日期、所命中信号码和“是否共用结算行”；不给其他匹配来源的 UUID、原始凭证、银行信息或外部明文；`examplesTruncated` 明确说明示例不完整。
- `duplicateDebitVerified=false`、`externalChargeVerified=false`、`refundCogsReversalVerified=false`、`accountingPolicyApproved=false`、`bankReconciled=false`、`netProfitEligible=false`、`realizedNetProfit=null`；Zod 对所有这些字段在前端再次严格校验。**V162/V64 已核验利润不因任何本地扫描或无匹配而解锁**。

## 退款与利润的明确阻断

已存在的 V63 退款成本敞口、V173/V174 仓库签名回执差异与 V175 独立人工品检是不同层级的证据；签名收货不等于会计成本回转，且现有外部授权未实测。此接口仅提供 `REFUND_COGS_REVERSAL_REQUIRES_RECEIPT_AND_G1_POLICY`，**绝不根据有无冲突自动冲回退款 COGS 或加入净利润**。G1/DEC-06/07/08 财务期间、退款确认、税费资本化和外汇政策仍需要卖家及财务批准。

## 测试

Java25：八类信号码的严格复用、来源并集/交集不重复计数、20 个示例截断但整期计数不受影响、无来源永远未认证、日期/非法计数/重复 UUID 负例、Owner/no-store/404、SQL 无 INSERT/UPDATE。前端 TypeScript/Zod/Vitest 防止假冒 `duplicateDebitVerified`、`netProfitEligible`、`realizedNetProfit`。既有 V165 实际 PG18 合成 HTTP 测试已扩展：两个 V91 同 SHA、与手工 Ads 来源相同 key，整期 V91 单表 count 对比新汇总、401/400/404、无匹配月份仍阻断，确认没有财务/银行/Ads 写入。

按已有 CI 里程碑规则，业务合并后必须对**精确 main SHA**执行真实 PG18/Flyway、Java25/Gradle、React19/Next16/Vite Chromium、Compose HTTP/Session/CSRF/恢复的完整 A3。真实 Amazon Seller/Ads OAuth 保持 OFF，生产 #104 **NO-GO**。没有外部真实性、银行及 G1 核对，不得称为真实净利润。
