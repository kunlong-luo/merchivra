# V28 · Finances 原始交易拆分分类与覆盖率核对

**交付类型：本地人工分类的只读经营分析基础，不是 Amazon 原始分类、会计过账、结算对账或真实已结算利润。**

## 必须先满足的数据不变量

- 原始 Finances `finance_source_event` 的 signed `totalAmount` 不被修改。V27 `finance_transaction_breakdown` 中的父节点金额**可能包含子节点**。V28 只统计 `is_leaf=true` 的末级金额，绝不把父节点同时累加。
- `finance_breakdown_rule` 是当前租户 Owner 在明确业务审阅后创建的**当前解释规则**：按 Marketplace + 原文 sourceType 精确匹配，分类为 PRODUCT_SALES、PRODUCT_REFUND、AMAZON_FEE、FBA_FEE、ADJUSTMENT 或 UNCLASSIFIED。不同站点相同源类型各有独立规则；应用规则只生成页面预览，**不改写原始交易类别**、不产生 Amazon 写请求、也不生成财务凭证。
- 所有创建、修改、停用均有 `finance_breakdown_rule_audit` 记录修订、原分类、新分类、状态和操作人；服务端从 Session 推导租户/Owner，不接受浏览器提供的 tenantId/actorId。历史规则不删除，停用立即使当前预览失去对应分类。
- 同源费用规则没有自动猜测。人工 `MANUAL_UNVERIFIED` 事件不参与正式来源的拆分覆盖率；`UNCLASSIFIED` 不算通过分类审核。

## 质量门槛

`FinanceClassificationEngine.evaluate` 输出以下互斥状态：

- NO_BREAKDOWNS：未取得可核对的末级明细
- MISSING_AMOUNT：存在未知/缺失原币金额
- CURRENCY_MISMATCH：末级金额含不同原币，不做汇率猜测
- UNMAPPED_LEAF：至少一个末级来源类型未审批分类
- AMOUNT_MISMATCH：已分类的末级有符号总额与源交易 totalAmount 不相等
- RECONCILED_UNVERIFIED：以上门槛均通过，末级金额与原始交易有符号总额核平，但这仍只代表**临时分类金额待复核**，并不代表 Amazon 结算、银行回款、净利润或税务口径已经确认。

只有最后一种状态 API 才返回 `provisionalTotals` 分组金额；其他状态分组必须为空，防止“部分金额被视为已完成财务”。纯 Java 单测覆盖缺项、混币种、未分类、金额不相等、负费用与已核平。规则在读取时动态应用，改变分类会改变当前预览，审计保留历史（不是不可变的已过账历史报表）。

## API

Owner Session / CSRF：

- `GET /api/v1/finance/marketplaces/{mp}/classification-rules`
- `POST /api/v1/finance/marketplaces/{mp}/classification-rules`，body 为 `{sourceType, category}`，精确匹配原文类型
- `DELETE /api/v1/finance/marketplaces/{mp}/classification-rules/{ruleId}`，仅软停用
- `GET /api/v1/finance/marketplaces/{mp}/classification-rules/{ruleId}/audit`
- `GET /api/v1/finance/marketplaces/{mp}/events/{eventId}/classification-preview`

财务工作台选择已取得的 SPAPI_FINANCES 来源事件后，能够直接选择末级明细类型、审批/修改、停用规则、查看修订历史和覆盖率异常；与人工订单关联审查共存。只有具备来源的 LIVE 明细能够产生分析预览。

## 明确未完成

Finances v2024 Transactions 的全部 item-level breakdown 层级、财务类型归属法规/税务规则、跨币种汇率、结算周期和银行到账证明、订单退款状态、成本有效日与物流/FBA 费用多来源对齐、正式可审计贡献利润或净利润。要做订单毛利或利润，需要独立成本时间版本和订单成本匹配，不能直接用交易总额减末级费用猜算。
