# FIN-005 · 原币财务事件与人工对账（V21）

**实现状态：代码已添加，待统一 Java/React/PostgreSQL/真实 Amazon 验收。**

本模块实现独立于 Orders 的原币财务事件事实，遵循 [FIN-001](../architecture/finance-model.md) 与 [BIZ-005](../product/finance-rulebook.md) 的「预估、平台财务、结算」三视图分离原则。当前**没有**可实际执行的 Amazon Finances v2024-06-19 授权 Worker，不自动记录 Amazon 交易；也**没有**银行流水、结算支付或真实净利润。

## 已有服务端 API（仅当前 Tenant Owner + Session + CSRF）

| 入口 | 作用 |
|---|---|
| `GET /api/v1/finance/marketplaces/{id}/events?limit=50&beforeAt=...&beforeId=...` | 每个 Marketplace 的不可变来源事件，以 timestamp + UUID 键集分页 |
| `GET /api/v1/finance/marketplaces/{id}/buckets` | 按 origin、ISO 原币、业务类别分别求有符号金额总和，不跨币合并 |
| `POST /api/v1/finance/manual-events` | 人工录入**未核验**参考事件；状态永久为 `MANUAL_UNVERIFIED`，不具备自动升格 LIVE 的入口 |
| `POST /api/v1/finance/events/{eventId}/review` | 人工审核，把事件显式关联同租户、同 Marketplace 的已存订单；`orderId:null` 标为未匹配，不删除或覆盖来源事实 |

## 来源契约

- `finance_source_event` 保存来自人工或受信内部来源的固定事件标识、分类、原始金额符号、ISO 币种、平台/记录时间、可选订单引用，以及获取时间。
- `UNCLASSIFIED` 与 `UNMATCHED` 分离：未知费用分类不代表不存在交易；无法可靠关联订单的事件不得自行归入收入。
- 来源唯一约束 `(tenant, marketplace, source_family, source_event_key)`。重复同键同内容读取同一记录；重复同键不同金额或分类返回 409，禁止覆盖已存原始事实。
- `MANUAL_UNVERIFIED` 不能经用户提交自动转换成 `LIVE`；既有 `recordTrusted` 仅供未来**受信内部** Worker，在 Seller、Marketplace、Finances 应用角色独立确认后调用；目前未为任何卖家自动发放 `Finance and Accounting` 角色，因此不宣称已经具备实时 Finances 同步。
- `finance_event_link_review` 保存当前匹配决策，`finance_event_review_audit` 追加审计历史。审查不会改变来源金额、类别或事件入账时间；人工关联不构成平台或银行支付确认。

## React

主导航「财务与对账」实现原币类别表、人工参考记录输入、来源/时间/分类列表、当前站点订单关联以及下一页游标；员工暂不能查看财务详情（由后端 Owner 边界保护）。

## 明确未实现

Finances API 正式 Worker/限流/权限审批、财务事件拆解、结算批次与回款验证、跨币种外汇、完整账本双录、税务、发票、真实净利润和估算贡献额。这些必须在上游证据齐全后分模块开发，不能从该统计表直接推算真实净利润。
