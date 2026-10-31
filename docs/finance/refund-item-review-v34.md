# V34 · 退款来源与商品处置人工审核

状态：代码已开发，待离线 CI/PG18 验证；没有真实 Amazon、仓库或银行连接。

## 核心原则

1. `SPAPI_FINANCES/LIVE` 退款交易必须先经 V21 人工关联到**同一 Marketplace 的 LIVE 订单**，并在 V28 的全部末级财务分类金额核对后出现 `PRODUCT_REFUND`，才能提交订单项退款审核。若官方外部 Order ID 与人工选择的订单 ID 对应外部号不符，立即阻断。
2. 每次审核指定真实已观测当前 `order_item.external_item_id`、商品数量、人工处置结论及可追踪证据参考号。处置结论只允许 `RESTOCKED_CLAIMED`（手动声称已入库，未核实）、`NOT_RESTOCKED`、`UNKNOWN`；不接受自动认可“已重新入库”或无证据的金额。
3. 源财务交易（以及关联关系）与订单项均不可被此次审核变更。任何数量必须严格为正，且同订单同 item 在跨多个退款事件中的审核数量合计不得超过原始订单项的订购数量。
4. 保存/变更/撤销均追加 `finance_refund_review_audit`，含版本、数量、处置、人工证据编号、操作者和时点；原审核被撤销时只删除当前状态，完整历史保留。已存在商品审核时禁止重新绑定这笔财务事件到别的订单，必须先撤销审核。
5. 仅当 V29 原订单历史单位成本齐全、币种与订单一致、人工声称退货已入库时，返回 `restockedCostSensitivity = historicalUnitCost × reviewedQuantity`。**这是人工成本敏感度参考，不是真实库存入库、不是真实 COGS 回转，也不修改 V33 贡献额**；若退回数量、库存收货或成本没有独立可信证据，仍保留 `REFUND_COST_REVERSAL_UNVERIFIED`。
6. Controller 全部经 Owner Session 获取租户，写入必须 CSRF，所有数据按 tenant/Marketplace/event/order/item 定界。跨 Marketplace 不可读取或修改退款审核。人工审核不请求 Amazon API、不会上传个人信息。

## API

- `GET /api/v1/finance/marketplaces/{marketplaceId}/events/{eventId}/refund-items`：返回已审核订单、财务来源金额、可选订单项、审核现状、成本敏感度及 `inventoryReceiptVerified=false`。
- `POST` 同路径，JSON `{itemId,quantity,disposition,evidenceReference}`：原始来源校验、订单/来源行锁、跨多事件累计数量限制，幂等无修改不重复写审计，修订历史版本。
- `DELETE /.../refund-items/{itemId}`：撤销当前审核并保留历史记录。
- `GET /.../refund-items/audit`：最多 100 条追加审计。

## UI

选择一个已经人工关联订单的 LIVE 财务事件，即可在财务工作台看到逐订单项的退款审核；未完成 V28 退款分类只显示缺口、不允许保存。状态表展示经审核数量、商品 SKU、处置、仅供核对的成本参考影响；历史审计可查询。

## 后续必须补完

实际退货/退仓/损坏来源凭证、经过授权的库存收货记录、FBA Return/Removal 原始事件、成本和采购批次匹配、退款金额与退货数量精确关联、税费/运费回冲、退款跨周期复核、库存/成本双重过账控制和正式利润。没有这些证据之前**不自动放开 V33 的退款贡献额阻断**。

请继续按既定约定先开发核心功能，最后由使用者在本地 Seller 授权并验证真实 API。


## V35 商品退款金额分配

[退款金额原币归属](refund-amount-allocation-v35.md) 在 V34 数量审核之后才能进行；如 V35 已存在金额分配，必须先解除归属才能撤销 V34 数量审核。资金来源仅取 V28 已核对的 `PRODUCT_REFUND` 原币类别金额，且不自动核实库存收货或成本回转。
