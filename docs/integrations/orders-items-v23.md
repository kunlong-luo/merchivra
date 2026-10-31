# ORD-005 · Orders v2026-01-01 订单项/商品小计（V23）

**交付状态：代码和离线用例已编写，真实 Amazon、PostgreSQL/容器安全和生产验收仍未完成。**

遵循 Amazon 官方 [Orders API migration guide](https://developer-docs.amazon.com/sp-api/docs/orders-api-migration-guide)：
新版 `searchOrders` 基础响应包含 `Order.orderItems[]`；无需继续使用将被停用的 v0 `getOrderItems`。
对商品金额仅显式请求 `includedData=PROCEEDS`。**不请求 BUYER/RECIPIENT/PAYMENT、RDT 或收件地址。**

## 实现

- 官方 Java SDK `software.amazon.spapi:spapi-sdk:1.13.0` 的 `SearchOrdersApi` 返回已验证 Seller 的订单页。类型化解析只提取 `orderId`、Marketplace、更新时点、履约原始状态，及订单项 ID、Seller SKU、订购数量。
- 商品金额：只提取 `orderItems[].proceeds.breakdowns[]` 中唯一 `type=ITEM` 的 `subtotal.amount/currencyCode`，不把 `proceedsTotal`（可能含运费、税、折扣）、`unitPrice` 或未知净结算混作商品小计。没有 PROCEEDS 或 Pending 订单未提供金额时，明确保持 NULL；未知绝不能变成零。
- V23 `sales_order.items_observation` 和 `order_item.is_current/source_updated_at` 表达订单项来源完整度与历史版本。数据未包含 `orderItems` 时一律不修改原有订单项；数组真实出现时才将该页原子提交为最新订单项集合并将旧行标记为非当前，不物理删除。
- Worker 沿用现有每页事务、Lease/Epoch、同租户/站点、Seller Vault + V12 Orders 角色证明以及凭证撤销再校验。订单旧版本/不同来源不能覆盖已存明细；与订单页和分页 checkpoint **同一事务**提交。
- `GET /api/v1/seller-marketplaces/{marketplaceUuid}/orders/{orderUuid}/items`：受 Session 和店铺作用域约束的只读明细，无 PII。权限适用 Owner 或 V16 的店铺级 `OPERATOR_READ`。
- React Core → 订单：显示 `itemsObservation=NOT_OBSERVED` 时为「未取得」，可展开查看每条来源订单项、卖家 SKU、数量及仅 ITEM 商品小计；**不称平台已结算、净利润或 Finances 费用**。

## 安全/数据不变量

- 只有已授权的 Orders 读取 Worker 能设置 `LIVE`，Mock Worker 仍走原有 Summary，不能伪造已观测订单项。
- 订单项最大 1000 条/订单，检查唯一来源订单项 ID、长度、金额精度、币种；不合法页不得写部分订单或推进 checkpoint。
- 源 `orderItems=null` 表示未取得；`orderItems=[]` 表示确实观测到空集合；原币商品金额未知保留 NULL。
- 同一 Order 新版本只把上一版本订单项转为 `is_current=false`，以免当前订单项包含历史已取消/消失的行。
- 不汇总跨币种小计，不使用来自 Orders 的 `ITEM` 小计假装 Finances `PRODUCT_SALES` 已入账。

## 待开发/验收

需要真实 Amazon 授权和批准的 Orders 角色、生产调用/节流、PostgreSQL V23 迁移/重复页及多租户负例、实际履约信息和退款事件、财务状态关联、Orders 大范围服务器分页与导出、后续逐条来源差异审计。在完成之前，本文只说明已交付**代码路径**，不是生产可用的证明。
