# V31 · Finances 官方结算类引用标识与精确匹配建议

**离线开发阶段：真实 Seller 权限、真实 Finances 响应仍由使用者未来在本地自行授权并验收。**

## 只存业务文档标识

- 官方 Finances v2024-06-19 `Transaction.relatedIdentifiers` 可以携带 `SETTLEMENT_ID`、`DISBURSEMENT_ID` 和 `FINANCIAL_EVENT_GROUP_ID`，新增 `finance_transaction_reference` 保存这三类的原始非 PII 标识，不保存 payment token、买家隐私、支付方法或整段 Amazon JSON。
- 当前订单引用 `ORDER_ID` 继续走 V26 原有 `external_order_id`，不作为银行结算凭证。非法含空格/控制字符/过长业务引用会拒绝当前来源页，而不是悄悄截断。
- `TypedFinancesSdkReader.Page.references` 和 breakdown/transaction facts 同步在单页事务内交给 `FinancesReferenceService`。同一来源 ID+种类+值可幂等重放，不能将人工事件注入到受信来源引用表。
- 只保留确切列举的 Amazon 枚举类型；其余业务引用不抓取。

## 结算匹配建议（不自动分配）

V30 人工参考结算单 `statement_ref` 与同站点、同原币且未分配的来源事件引用值**完全相等**时，候选列表在前面显示来源引用类别，提示运营优先核对。比较顺序优先 SETTLEMENT_ID，其次 DISBURSEMENT_ID，然后 FINANCIAL_EVENT_GROUP_ID。

- 不做模糊匹配，不反向修改 Statement 和 Finance 交易，不自动调用 link API，也不以精确 ID 匹配来断言真实账单已结算或银行到账。
- 若 Amazon 来源引用不存在，仍允许同站点/原币/时窗人工选取，显示“无相同来源编号”，需人工独立审查。
- `GET /api/v1/finance/marketplaces/{mp}/events/{eventId}/references` 提供当前租户 Owner 的只读来源引用追溯，非 LIVE 事件不能使用。
- 实际银行流水、Amazon Settlement Report 和 Payment/Disbursement 明细仍待独立来源验真。

## 本地验证

`TypedFinancesSdkReaderTest` 增加 synthetic 官方 SDK 模型测试：保存 Settlement/Disbursement ID、原订单号不混淆、非受信业务引用不保留及非法值拒绝；Java/React/PG18 CI 继续是离线构建检查，不触发任何真实 Amazon 请求。
