# INT-003 · Amazon → Merchivra 正规化数据契约（G2 候选）

**状态 Proposed**。这是 ERP 接收的**内部标准数据语义**，不是官方 Amazon JSON 响应的逐字段转录；适配层需按具体 API 版本映射。正式字段应使用官方模型文档及获批操作的去敏试点验证。

## 1. 全部 Canonical Facts 的共用信封

```json
{
  "tenantId": "uuid-owned-by-server",
  "sellerAccountId": "account-uuid",
  "marketplaceId": "verified-marketplace-id",
  "sourceApi": "Orders",
  "sourceVersion": "2026-01-01",
  "sourceRecordKey": "source-scope-dependent",
  "sourceUpdatedAt": "2026-10-29T00:00:00Z",
  "ingestedAt": "2026-10-29T00:00:08Z",
  "quality": "OBSERVED",
  "schemaVersion": "canonical-v1"
}
```
该 JSON 为结构示意：所有值均为虚构。源响应不应信任自带的 `tenantId`，tenant 来自服务端已授权同步任务作用域。

## 2. Orders / order item

| Canonical 字段 | 来源意图（具体映射待模型核对） | 丢失/重排策略 |
|---|---|---|
| externalOrderId | Amazon orderId | 缺失则隔离，不创造猜测 ID |
| externalItemId | order item 级标识 | 与 orderId/账号作用域组合去重 |
| originalSellerSku | 订单原 seller SKU | 原值保存，即使没有内部 SKU 映射 |
| fulfillmentStatusRaw | Amazon 原始 fulfillment 状态 | 未知枚举保留原值，不强行回退 |
| fulfilledBy | Amazon/Merchant | 仅影响 FBA vs FBM 能力，不能据此推定库存 |
| quantity | 订单项数量 | 数值验证，取消/退款在独立事件记录 |
| itemProceeds / expense / promotion / tax | 可选财务结构，使用对象类型/货币及源类型 | 未请求/未返回 ≠ 零，不能当 Finances 入账重复合计 |
| sourceUpdatedAt | 订单外部更新时间 | 乱序写入时比较/隔离 |

Orders v2026-01-01 的 `includedData` 支持 FULFILLMENT/PROCEEDS/EXPENSE/PROMOTION/TAX 等选择；**不默认请求 BUYER/RECIPIENT**。数据字典不假设所有字段无条件返回。

## 2.1 Orders 分页与数据分量规则

`searchOrders` 的 `paginationToken` 是有有效期且依赖同一查询条件的临时句柄；必须记录初次窗口的 `lastUpdatedAfter/Before`、marketplace 过滤和查询指纹，避免重试时悄悄修改范围。字段是否存在受 `includedData` 选择、角色和来源条件影响，不能将未请求/无权字段当成 0。

## 3. FBA Inventory Observation

| 字段 | 含义 | 数据质量 |
|---|---|---|
| sellerSKU / FNSKU / ASIN | 平台库存识别字段（若提供） | 没有内部 mapping 也能保存 |
| marketplaceId / aggregation dimension | 平台聚合作用域 | 不跨站点/状况桶粗暴加总 |
| fulfillable / reserved / inbound / unfulfillable / researching | API 已返回的库存状态分桶 | 各字段允许 null = 未观测 |
| sourceObservedAt / ingestedAt | 外部观测、ERP 获得时间 | 若上游未提供观测时间，使用抓取完成时间标为 `ERP_OBSERVED`，不得伪称平台源时点 |
| quality | OBSERVED / PARTIAL / UNKNOWN / STALE | 库存值 0 与 UNKNOWN 不同 |

### FBA `details` 与缺字段

官方 `getInventorySummaries` 的 `details` 默认 false；不要求详细字段时无法据此做数量型断货推断。适配器必须记录请求是否 `details=true`，若无 `inventoryDetails` 则标记 `NOT_REQUESTED/UNKNOWN`。在 `startDateTime` 模式使用卖家 SKU 过滤时，先按官方操作语义验证过滤是否适用，不能臆测来源只覆盖指定 SKU。

## 4. Finances Transaction

| 字段 | 含义 | 特殊规则 |
|---|---|---|
| sourceOperation/version | listTransactions 等 | 版本变更需重新核对 |
| sourceTransactionKey nullable | 官方明确稳定记录标识（若有） | 没有就置 NULL，不能伪造“官方 ID” |
| transactionType / financialEventType | 源类别 | 未知类别保持 UNCLASSIFIED |
| amount / currency | 原币和定点金额 | 必须具备币种和借贷语义 |
| postedAt/occurredAt | 来源可得时间 | 与 orderTime、settledAt 分离 |
| relatedIdentifiers[] | 平台提供订单/结算/其他引用 | 只是证据，不能所有引用都自动当唯一键 |
| evidenceVersion / matchingStatus | ERP 匹配规则和状态 | 无法判定则保留孤立事件 |

严格避免 `netPayout = orderSale + financeNet` 等双重累计；来源/币种/时间和费用成分都要标明。Amazon Finances v2024-06-19 `listTransactions` 对应用角色有明确要求，未授权时仅显示 NOT_AUTHORIZED。

## 5. 兼容与安全

- Adapter 按 operation/version 建立映射，Canonical schema 独立版本化，字段更改要求契约测试和迁移策略。
- 非法 DTO 保存为脱敏异常记录，不能在日志中打印原始 Buyer/Recipient 数据。
- 无稳定 ID 的同金额/同时间记录优先进入 `PENDING_REVIEW`，由实际源记录 ID 证据和判重策略决定是否接纳。
- Amazon rate limit、分页 token、报告有效期按具体 operation 治理，不跨接口复制假设。
- 所有财务数字区分**经营估算**和**平台入账事实**，防重复累计见 [DATA-002](../architecture/source-reconciliation.md)。

## 6. 官方参考（接入前再次验证）

- [Orders API migration guide](https://developer-docs.amazon.com/sp-api/docs/orders-api-migration-guide)
- [Finances API](https://developer-docs.amazon.com/sp-api/docs/finances-api)
- [FBA Inventory API](https://developer-docs.amazon.com/sp-api/docs/fba-inventory-api)

真实数据授权不由这份规范赋予，未通过开发者权限核验前维持 Mock 模式。
