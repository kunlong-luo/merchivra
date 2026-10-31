# V35 · 退款商品金额分配与原币差异审核

这是**独立于库存入库、财务记账和 Amazon 退款操作**的本地审核功能。当前不需要使用者授权真实 Amazon Seller，真实测试留到核心功能完成后在本地进行。

## 设计前提

- V34 已要求退款源是 `SPAPI_FINANCES/LIVE`、人工审核关联的同站点 LIVE 订单，并且来源 Order ID 不得与人工审核订单号冲突。当前必须先审核某笔 V34 订单项的退款数量，才能给该订单项归属退款金额。
- V28 `FinanceClassificationService.preview` 要求来源 transaction 的全部末级 breakdown 被同币种核平、按 Owner 审核规则分类，且存在 `PRODUCT_REFUND` 类别。**只取 PRODUCT_REFUND 的有符号类别合计，不是整个交易 net total**；其他费用、调整和税款不能偷算到退货金额中。
- V35 的 `finance_refund_amount_allocation` 对同一事件每个订单项只能有一条当前金额分配。原币 signedAmount 按原符号写入；不能倒转符号、混币种、超额归属；多个项可部分归属，显式展示剩余差额。无合法来源时，不显示任何归属总额或零差额。
- V35 数值与全部原始财务和 V34 商品审核记录隔离。更改分类规则后，预览会重新核对来源覆盖；无法核平时状态变为 `SOURCE_REFUND_NOT_READY`。旧分配和历史审计不消失，但不会用过时规则“假核平”。

## 审核与权限

`POST/DELETE` 在订单行和 Finances 源事件行加锁，锁顺序与 V34 一致，防止交叉事件重复和订单关联变更竞态。保存记录必须由 Owner Session 身份、站点/租户一致且通过 CSRF；HTTP 不接受伪造 reviewer/tenant 字段。金额和人工依据版本化，追加 `finance_refund_amount_allocation_audit`（CREATED、UPDATED、WITHDRAWN）。即使删除当前分配，审计历史也保留。

V34 的审核商品项如果已经有 V35 的金额分配，必须先撤销该金额归属才能撤销 V34 数量审核，避免金额引用悬空。

## 核对状态与 API

- `GET /api/v1/finance/marketplaces/{mp}/events/{event}/refund-allocations`
- `POST` 同路径：`{itemId,signedAmount,currency,evidenceReference}`
- `DELETE /.../refund-allocations/{itemId}`
- `GET /.../refund-allocations/audit`

只读 `RefundAmountAllocationEngine` 返回 `SOURCE_REFUND_NOT_READY`、`NO_ITEM_ALLOCATIONS`、`PARTIALLY_ALLOCATED`、`SIGNED_TOTAL_MATCHES_UNVERIFIED` 等状态；反向或超额会以冲突拒绝写入。 `inventoryReceiptVerified=false` 和 `accountingPosted=false` **永久保持 false**。

财务工作台 V34 退款项面板下加入 V35 分配表、原币商品退款总额 / 分配额 / 剩余额、显式保存和撤销、独立操作审计。金额核平**不等于退款真正入库、成本回转或银行到账**。

## 下一阶段仍需

需要独立的 FBA/第三方仓库 return receipt、退货差异及损坏、实际入库批次和单位成本、税费/运费/广告费用分摊、跨期退款审核及正式财务过账。即使 V35 金额对平，V33 在出现退款类别时仍因物理入库和成本回转尚未验真而拒绝出最终贡献额。
