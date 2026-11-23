# Merchivra V211 · 期间退款归属、跨期和冻结报表重叠的利润阻断

**2026-11-23 · 无新 Flyway / 无新会计分录 · 精确代码 SHA/CI/A3 待核验。**

## 核心目标与现有能力复用

项目要实现的最终业务是**真实订单/SKU/期间的全部收入、平台费用、广告、物流、银行和入库成本归属，退款 COGS 正确回转后核算可审计净利润**；当前 **`realizedNetProfit=null`**，不得用手工敏感度估值替代已核证的实账。

V188 已经把 V64 版本化冻结订单贡献、V91/V92 本地唯一归属费用、V165/V168 来源冲突合成原币敏感度；V194 对订单/SKU 捕获进行独立 SHA 复算；V210 按订单**全量**重算所有 LIVE 已匹配产品退款的原币金额、人工 V34 数量、V35/V63 审查与跨事件数量上界。V211 **不是第二套利润引擎、退款过账接口或新的审核表**，而是在期间利润界面实际接入这几层现有核对，堵住旧报表误用的间隙。

### 三个不可合并的日期轴

1. **V64 冻结订单集合**：`sales_order.order_time`，按订单创建时间 UTC 日期选入；创建时冻结 `finance_profit_report_order`，不同版本的报告可能合法地包含相同订单。
2. **Finances 退款发生时间**：`finance_source_event.posted_at`，仅以当前分类为 `PRODUCT_REFUND` 的 LIVE 来源，按 UTC `[start,end)` 统计；某笔旧订单的退款可能在本期发生，另一笔本期订单的退款可能下月发生。
3. **V91 费用业务日期**：V188 原有的 `finance_cost_source_v91.business_date`，不是已签署的 G1 会计确认日期；V92 按期间的候选分摊只说明本地预算敏感度，不证明真实费用确认。

因此 V211 **不自动再次从 V188 的有符号销售/平台费用里扣一遍退款**；也不把多份 V64 冻结报告 CSV 当不同账本入账凭证相加。不同报表快照重叠是**风险提示而非实际重复扣费证据**。

## 后端契约

只读 Owner/Session/租户/Marketplace API：

```text
GET /api/v1/finance/marketplaces/{marketplaceId}/period-refund-profit-guard/{jobId}
```

1. **先沿用 V188** 的 `PeriodProfitWaterfallServiceV188.preview`。它会使用已授权作用域查找 V64 `jobId`，并按 V194 要求版本化 SHA/完整冻结订单+SKU 捕获通过。老/错版本或不能复核的冻结摘要返回明确 **HTTP 409**，不展示金额。
2. 在独立 `REPEATABLE READ` 只读快照中执行三个原始 SQL：
   - **本期所有 LIVE 已分类 `PRODUCT_REFUND` 来源**（最多 1000 行，`LIMIT 1001`）：逐源带出原币有符号金额、UTC 入账时间、归属订单和匹配数，通过 PostgreSQL `LATERAL count` **保证即使错误出现多个 MATCHED 关联也不会把原始退款金额乘两倍**；保留未匹配及订单未进入 V64 冻结批次的事件，而不是暗中省略。
   - **V64 冻结订单全生命周期的退款订单集合**（最多 100 个订单，`LIMIT 101`）：既包括跨期已匹配 LIVE 产品退款，也包括拥有 V34 手工审查、但当前缺失已匹配来源的订单；**这里故意不按退款发生日期过滤**，从而发现下月退款或人工来源孤儿。
   - **其他重叠 V64 版本的共同冻结订单**（最多 100 条重叠关系，`LIMIT 101`）：返回重叠快照和订单计数，区分“多个报表覆盖了同一订单”和“财务账本真实重复入账”，后者本阶段没有证明。
3. **在快照事务结束后**对上述全生命周期退款订单逐一调用 V210。复核每个事件 ID、原币、金额和入账时间是否与刚才的期间来源快照一致，核对 V34/V63、跨事件累计数量以及来源是否已经变化。V210 的合法 404/409 **不得污染 PostgreSQL 快照事务造成 HTTP 500**；存在失效订单直接 HTTP 409，不能把未知计为 0。
4. 检测多匹配、未匹配、超出 V64 冻结订单、跨期退款、金额/币种/时间来源漂移、快照后新增事件、V210 证据缺失和其他报表快照重叠。每原币独立汇总**来源退款有符号金额**，绝不自动跨币汇率混加。
5. 任意类别超过安全上限，**HTTP 409 不显示截断后的部分利润/退款总额**。避免把当前 50 条 UI 分页或前 1000 条冒充完整期间。

新的 `PeriodRefundProfitGuardV211.Result` **包含原 V188 完整基线**（给财务人员核对其原有费用/收入敏感度的来源），另列期间退款原币桶、V210 各订单、外币数量、交叉期/异常来源与重叠快照计数。即便全部人工数据恰好守恒：

- `refundRecognitionPeriodPolicyApproved=false`
- `actualRefundCogsVerified=false`
- `independentAmazonRefundCoverageVerified=false`
- `doubleJournalDeductionAllowed=false`
- **`refundAdjustedContribution=null`**
- **`realizedNetProfit=null`**

缺分类、缺 LIVE Finances、来源未匹配或退款数为零，不代表真实 Amazon 全量退款已经覆盖。不存在数据不等于证实金额 0。

## Next.js 16 利润中心

在既有 V64 整期报告和 V188 成本瀑布下，增加 **V211「退款跨期与成本回转 · 利润阻断复核」**，仅对已完成的冻结任务展示入口。默认折叠，财务人员按需点击才执行可能较重的整单核对；不每 15 秒自动重算全部历史事件。成功后展示不同原币、跨期数量、归属异常信号、V64 其他快照覆盖提示，最多呈现前 12 条**明确标注“异常示例”的追踪行**；全部事件数量和原币合计仍来自服务器完整快照，不在浏览器分页求和。对于 409 不显示零元利润或假成功，输出中文阻断原因；Zod 保证服务端不能突然把真实入账布尔值改成 true 或把 `realizedNetProfit` 从 null 改成伪值。

## 安全与回归

- Java `PeriodRefundProfitGuardV211Test`：同订单本期退款及下一期退款、另一币种、两个 V64 冻结快照共享订单、0 匹配、2 匹配不得乘金额、退款旧订单不在本期 cohort、原币/入账时间漂移、V210 新增退款、重复事件与缺失复核、无已分类事件不能认 0、Owner/no-store 和全量 SQL 不应偷偷 LIMIT 50。
- PG18 `MERCHIVRA_V211_DISPOSABLE_ONLY=true python3 scripts/smoke_period_refund_profit_guard_v211.py`：使用**V211 Java 源码里三个 SQL 原文**、现有 Flyway V111 后的真实 PostgreSQL 18，在 TEMP 表上构造 USD/EUR 跨期退款、不同租户、其他 Marketplace、无匹配、多重匹配、不同 V64 快照、旧期间与新期间；从源查询核对没有重复乘额，最后 ROLLBACK。SQL 合成验证不证明真实 Amazon 完整性或任何利润。
- 同一精确代码 SHA 的 Java25/Next16 pnpm/Chromium/HTTP/PG18/Compose **完整 A3 7/7** 和快速 CI；针对新 GET 匿名请求 HTTP 401。无新 Flyway 数据表或迁移，不应声称 V112 已经运行。
- 技术栈不变：Java25/Spring Boot4.1.2/Gradle/PostgreSQL18，React19/Next.js16.4.5/pnpm，**不使用 npm/Maven，不向 Vite 回退扩展新功能**。直接提交 `main`。

## 后续 V212 及正式上线闸门

V212 优先用真实财务缺证清单与 G1 决议继续完善**退款 COGS 实际批次归属、银行付款、结算、广告/物流各项成本的重复入账审计**。只有维护者本地明确授权真实 Seller/Ads/银行与相关实物来源，且完成 G1/G2/G3、DEC-06/07/08 审计后才可能改变核证状态。继续保留 `realizedNetProfit=null`、正式项目评分 **70/100**、真实 OAuth **OFF**、生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。
