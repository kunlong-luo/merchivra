# V33 · 单笔订单已审核来源经营贡献额预览

**重要：这不是最终利润、不是 Amazon 结算额或已到账净额，也不会生成会计凭证。**

## 计算门槛

`GET /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/contribution-preview` 仅允许 Owner 查看当前租户/Marketplace 的订单。服务端独立核对如下条件：

1. V29 订单历史成本 `OrderCostEngine.readiness=COGS_READY_PREVIEW`，来源订单为 LIVE、当前订单项观测完整、订单时间有效且 Marketplace 当时的 SKU 映射、单位成本均齐、币种一致。
2. `finance_event_link_review` 必须为 `MATCHED` 且关联同站点的 `SPAPI_FINANCES/LIVE` 交易。原始 transaction 的 Order ID 不能直接当作“已审核关联”。同源订单号但未完成人工核对的交易阻止估算。
3. 每笔关联财务交易必须通过 V28 的 `RECONCILED_UNVERIFIED` 分类完整度校验：原币一致、末级费用明细已按人工规则分类、末级合计与有符号交易总额相符。不存在跨币种隐式 FX。
4. 如包含 `PRODUCT_REFUND`，需要真实退货/库存成本回转证据；如含 `ADJUSTMENT`，需要业务归属审核。当前均直接停止贡献计算，输出缺口代码。未知费用类别同样不允许猜。
5. 只能使用最多 100 笔已审核财务交易；若超过上限，不输出金额。至少存在已审核 `PRODUCT_SALES` 分类。

## 暂定公式

以人工审核和金额核平的同币种财务来源叶级分类为准：

`estimatedContribution = signedProductSales + signedAmazonFees + signedFbaFees - historicalUnitCostSubtotal`

其中平台费用通常为**负数**，所以维持原始正负号后相加，绝不能再次扣减；Orders `order_item.proceeds_amount` 作为另一层订单观测事实，**不再额外计入销售收入**，防止重复。

所有来源金额、COGS 和合计只在每个条件通过时返回；否则相关金额均为 NULL，返回一个明确的 readiness 阻断代码及已审核/待审交易数量。即使全部通过，`financialSourceComplete=false` 和 `bankSettlementVerified=false` 始终为假，因为缺少完整银行/结算回执、所有可能迟到费用、税费、广告与退货成本证明。

## 页面与测试

财务工作台在选择同站点订单后同时呈现 V29 历史单位成本完整度和 V33 已审核来源经营贡献预览；支持按需刷新，不发 Amazon API 请求。

纯 Java 单测覆盖：负费用不双重扣除、退款/调整禁止擅自计算、未人工审核关联、来源分类不完整、成本缺失、超出来源上限、源事件重复及混币种。下一阶段需要退款关联、实际成本回转、广告归因、税费与真实银行 Settlement 的受信数据才能形成可冻结的正式利润报表。

## 离线优先

用户将在主要核心功能全部开发后自行在本地授权 Seller 并进行真实 SP-API 调用与测试；当前不读取任何个人 Amazon 账号和私有凭证，LIVE 出站仍默认关闭。


## V34 退款审核不直接生成成本回转

[退款商品人工审核](refund-item-review-v34.md) 已能记录退款来源的订单项、数量、处置与人工证据，并计算非入账成本敏感度。由于真实库存收货和实际成本回转尚无可信来源，V33 仍遇到退款类别时停算，不把人工处置误称为最终盈利。


## V35 退款金额审核不等于退款成本已证明

[退款金额人工归属](refund-amount-allocation-v35.md) 能提供按项原币 signed 分配、数量审核与差额，但仍无独立返仓和库存 COGS 回转凭证；V33 遇到 `PRODUCT_REFUND` 仍然阻断，不因金额匹配而推断最终利润。
