# V29 · 订单历史成本（COGS）完整度预览

**历史 V29 基线；现行规则及精确验收见 [当前事实](../current-status.md)。真实 Amazon 订单及实际成本核算仍需卖家本地完成核证。**

## 核心来源事实

- 通过官方 Orders v2026-01-01 `searchOrders` 的 `Order.createdTime` 获取订单业务发生时点；仅在源数据存在时写入 `sales_order.order_time`，后续订单更新时间不得覆盖初始订单日期。来源没有 createdTime，订单时间保持 NULL，不用 lastUpdatedTime 或当前时间来猜测。
- V23 的当前订单项写入时明确设置 `order_item.data_origin` 为 `LIVE` 或 `MOCK`；与已存来源不一致的订单项会拒绝，防止只改部分行。同步后校验当前订单项数量与来源观测集合一致，若不一致整个页事务回滚。
- 原有 `sku_cost_version` 为内部 SKU 的人工历史单位成本；`listing_sku_mapping` 提供 Seller SKU 到内部 SKU 的有效期映射。查询只使用同租户同站点、订单当时有效的历史映射；日期按 Marketplace 配置的当地时区换算，绝不借用当前映射或后来的成本日期。
- 若订单是 MOCK / UNVERIFIED、订单项未观测、订单业务时间未知、Seller SKU 或内部 SKU 历史映射缺失、数量/成本未知、成本原币与站点原币不一致，都不返回整体 COGS 数字；缺口逐行列出。现阶段单位成本是人工维护的 SKU 成本版本，并未追踪实际 FIFO 采购批次。

## 只读 API

`GET /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/cost-readiness`

- 服务端从 Session 读取租户/Owner；必须在指定 Marketplace 中找到订单，否则 404，不接受浏览器输入的 Seller ID 或来源成本。
- 响应 `readiness`、`coveredItems/itemCount`、`provisionalCost`（无法完全覆盖时为 NULL）、逐项历史单位成本与缺失原因。没有自动外币换算。
- React 财务工作台可以选择已读取的订单，展示历史映射、数量、费用币种和覆盖状态，帮助运营先补齐成本资料。

## 与真实利润的区别

**COGS_READY_PREVIEW 不等于 Amazon 实际结算利润。** 后续仍需财务明细来源与订单/退款的关联审核、包裹/入库/退仓成本、广告费/税费、实际采购批次，以及跨币种 FX 完整性。不能把 Orders ITEM 小计减暂定 COGS 的结果称为最终净利润。

## 本地授权与验收约定

真实 Seller 授权暂不需要。全部功能代码优先构建，使用官方 SDK 的 synthetic fixtures、Java 单测和 React 静态/单测。待功能覆盖足够后，再由仓库使用者在本地配置 Seller Central OAuth/私有授权、明确开启生产 SP-API 读取开关，并自行验证真实 API。默认 Demo/Mock 与 LIVE 数据隔离；无需现在提供 Refresh Token、账号信息或外部角色证明。


## V33 下一层经营贡献预览

[已审核来源的单笔订单贡献额](order-contribution-v33.md) 将 V29 的完整历史 COGS 仅与 V28 已核平并经人工确认属于该订单的 LIVE Finances 分类金额结合；拒绝对退款或未核实调整推断利润，真实来源未齐前不得叫净利润。

## V245 · 历史 COGS 计算输入一致性（待验收）

- 每条当前订单项必须具有非空且唯一的来源 item ID；重复或超过既有 1000 行上限返回 HTTP 409，而不是双重计算或输出部分总额。映射与历史成本候选均须恰好一条；零候选即使携带值也不得计入 COGS，沿用 V200 的多候选阻断。
- 历史单位成本必须非负、可无损表示为 NUMERIC(20,6)，不接受负数、超范围或额外有效小数位；已明确录入的零价仍只作为人工参考，不等于外部核证零成本。缺失或错误的任何一行令整体 provisionalCost 为 null。
- 独立 V29 调用在 read-only REPEATABLE_READ 事务中查询订单头、业务日期、有效期映射和成本行；如果已有外层事务则沿用其隔离级别，本规则不保证所有上游组合接口跨查询的一致性。无新的会计或外部来源认证，不得将预览升级成实际批次 COGS、净利润。
- Java 回归覆盖零候选携带值、重复身份、非法单位价、事务声明；本轮快 CI 待验收，V200 真 PG18 SQL 契约不变。
