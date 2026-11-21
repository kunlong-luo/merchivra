# V188：统一期间利润原币算术瀑布（V64 × V186 × V187）

状态：业务 PR 快速 CI、合并后精确 main SHA 完整 PostgreSQL 18 A3 尚待验收。**本功能不是已核验净利润、GL 凭证、第三方费用认证或新财务授权。**

## 产品目标

V64 已有按照 UTC 订单时间冻结的持久任务及逐订单已知范围贡献：`销售 + 带符号 Amazon 费用 + 带符号 FBA 费用 − 订单历史 COGS`。V162 的纯引擎定义了八类附加费用及不同来源应避免重复扣费的守恒规则；V164/V185/V186 为 V91 人工费用来源提供单一主分摊维度、获批未核验金额、期间算术投影，V187 为不同来源的本地身份重叠提供整期风险计数。然而原先 Next.js V64 报表未能把这些层**作为一个可验证的计算序列**显示。

V188 接到现有 `PeriodProfitReportPanel`，**不建立第二套原始成本或审批表、不重算 V64 已冻结订单，也不虚构 Ads/银行或退款实收**。把 V64、V186、V187 的相同租户+Marketplace+查询区间数据，在一个只读 PostgreSQL REPEATABLE READ 事务中组合；纯 Java 引擎执行精确六位原币算术守恒、状态门禁及敏感度公式。

## API、作用域与计算公式

`GET /api/v1/finance/marketplaces/{marketplaceId}/cost-waterfall-preview/{jobId}`

**V188-A3 第一次精确业务 SHA 的真实故障与修复：**原 GET 挂载在 V64 `/profit-reports` 路径下，触发 `scripts/normalize-finance-openapi.mjs` 的严格九路由白名单，真实 PG18 A3 #44870830613 中 OpenAPI 合同校验报 `Unexpected endpoint in bounded finance group`。该任务失败**不是 PostgreSQL 或 Spring 启动失败**。修复将 V188 独立只读端点移到 `/cost-waterfall-preview/{jobId}`，保留原 V64 报表 ID 和 Owner/tenant/Marketplace 校验，且不更改 V64 已生成 Orval API 的固定 9 路由合同；修复后的全量 A3 必须单独在新 main SHA 上验证。此前失败 A3 不得写成通过。

- Owner Spring Session 的既有 `TenantAccessService`、`Cache-Control: no-store`、跨租户/站点/错误 V64 报表 ID 404。先使用**原 V64 服务按 tenant+Marketplace+job UUID**做作用域校验，再以 `job.after/before` 同时获取 V186 全期间唯一来源成本池和 V187 风险并集。
- V64 订单金额从 `finance_profit_report_order` 的 `ELIGIBLE` 读取**整份已冻结 job**，而不是当前的 100 行或 1000 SKU 分页；检查 eligible/blocked/pending 行数完全对应 V64 job 及每行币种、必需金额非空，`sales + signed Amazon + signed FBA - COGS = contribution` 精确守恒。不能把负平台费用再减第二遍。
- 所有 V91 人工来源按 V186 的 `category/currency/primaryDimension` 列为 `CostLine`，其来源 UUID 已在 V186 去重（单源不可横跨 SKU/ORDER/PERIOD 重复计费）；V162 的八类枚举作为允许的类别，但**不将 V91 `APPROVED_UNVERIFIED` 冒充 V162 `REVIEWED_COST`**。人工来源必须能在同一原币保持精确六位，所有期间源总数必须与 V187 完整风险计数一致，状态不一致或金额错配 HTTP 409。
- 即使 V64 订单一部分符合资格，只有 `SUCCEEDED`、全部订单已处理、`blockedOrders=0` 且有不少于 1 单 ELIGIBLE，才允许将“整期候选贡献”用于下一步算术敏感度；否则只显示**合格订单子集贡献**，绝不称其为整期利润。
- 所有候选附加成本须为 V92 `APPROVED_UNVERIFIED`，独立审批审计完整、无 pending/unassigned、无剩余分摊、原币一致、金额守恒，且 V187 整期 **0 个本地跨来源冲突**，并至少有一笔成本来源，才输出：
  
  `unverifiedCostSensitivityContribution = eligibleContribution - approvedUnverifiedLocalCostExposure`
  
  这是 **“已知订单贡献减未核验 V92 人工费用”的纯原币假设敏感度**，不是已认可收入、营业利润或真实净利润。未审、其他币、风险冲突、没有人工来源或者退款财务未知，金额**NULL 而不是 0**。哪怕通过这些本地算术门禁，也不能证明平台费用未被另一个外部渠道重复计入 V33，不能批准会计跨期或汇率。
- **日期轴不是同一财务确认日：** V64 以订单 UTC 时间选期，V91 以人工来源的业务日期选期；V185 的服务日期均摊跨月也不等于 V91 日期。本版本将差异作为显式 `V64_ORDER_UTC_V91_SOURCE_BUSINESS_DATE_AXES_DIFFER` 阻断/警示，绝不自动将跨月服务日重新过账。V64 按历史批次冻结、V91/V92/V165/V168 为此次读时最新事实；前端和 API 均明确快照差异，V188 不落库。

## 状态与 UI

- `ORDER_FINANCIAL_BASELINE_INCOMPLETE`：有未处理、被阻断或 0 个可信订单，整期算术不能计算。
- `ADDITIONAL_COST_SOURCES_UNKNOWN`：无 V91 来源 ≠ 成本为 0。
- `MIXED_ORIGINAL_CURRENCY_BLOCKED`：存在不同原币费用，不做 DEC-08 未批准的 FX 换算。
- `CROSS_SOURCE_COLLISION_BLOCKED`：V165 八种身份风险或 V168 共用结算行任一来源命中，不能直接作为可扣费用。
- `V92_UNVERIFIED_COST_DISTRIBUTION_INCOMPLETE`：存在待审、未归属、独立审计缺失、金额未完全分配。
- `UNVERIFIED_COST_SENSITIVITY_ONLY`：以上本地算术条件全部具备，只是六位精度来源总额演算，**永远不是**真实净利润。

前端 React19/Next.js16 利润中心显示 V64 销售及带符号费、历史商品成本、V186 成本明细、原币费用敏感度、阻断条件和冲突计数，使用 TanStack Query + Zod 严格强制所有外部财务证明标记为 false、`realizedNetProfit=null`。Vite 回退仍保持；不新增开放端点、不关闭 Session/CSRF。

## 不变的财务门禁

`additionalCostExternallyVerified=false`、`accountingPolicyApproved=false`、`refundCogsReversalVerified=false`、`fxConversionApproved=false`、`bankSettlementVerified=false`、`ledgerPosted=false`、`realizedNetProfitVerified=false`、`realizedNetProfit=null`，并显式 `financeFeesExcludedFromAdditionalCosts=true`。特别注意 V63 退款历史敞口和 V173/V174 WMS 签名外部实收**不自动证明**退款商品重新可售或财务 COGS 可以冲回。未获得 G1/DEC-06/07/08 及真实第三方认证不能把任何人工费作为 V162 `REVIEWED_COST`，不能写入财务/银行/税表。

## 验收

Java25 纯单元证明 signed fee 不二次扣、10.000001 六位精度、缺可信订单/无来源/跨币/跨源冲突/待审未分配均 fail closed、V64 金额错配和原币来源数不符 HTTP 409、来源总数与 V187 一致；Owner no-store/404。Vitest/Zod 对虚假外部认证字段、伪造真实净利润、任意金额精度、阻断情况下填成本收益数值全部拒绝。

真实 PostgreSQL 18 合成 HTTP：在现有 V164 双 Owner、V185 跨月、V186 单源的测试事务后，使用原 V64 API 创建**独立的合成持久候选报表**，随后使用 Owner/另一个 Owner/匿名请求 V188 读接口，核对来源数、批准原币 7.250000 与 10.000001、剩余 2.750000 与 2.000000、未分配 V91 源、no-store、401/404、真实净利润始终 null、银行/Finances/Ads 无写入。允许创建这一个合成 V64 任务，但 V188 自身仅有 GET，不改变 V64 报表和 V91/V92 不可变事实。

仅业务 PR 合并后精确 SHA 的真实 PG18/Flyway、Java25、React19/Next16/Vite Chromium、Compose Session/CSRF/恢复完整 A3 7/7 成功后归档。Amazon Seller/Ads OAuth **OFF**、生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。
