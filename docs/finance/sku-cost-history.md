# FIN-004 · 内部商品历史成本代码切片（V19）

本功能是**原币种单位采购/商品成本版本事实**，不是从 Amazon Finances 同步的费用或结算，也不是完整利润账。

- `GET /api/v1/catalog/skus/{id}/costs`：当前租户 OWNER 查看最近 500 条生效历史。
- `POST /api/v1/catalog/skus/{id}/costs`：OWNER 追加新的单位成本、ISO 货币与 `effectiveFrom` 日期。
- 交易以 `product_sku` 行锁串行化；新成本必须晚于当前开放期间，前一区间结束日为新版本生效日，日期均采用 **[from, to)** 半开区间。
- 金额使用 PostgreSQL `NUMERIC(20,6)`，不能跨币种直接合计；业务时间成本与真实费用/售价不混写。
- React Core → Listing → SKU 管理区提供成本版本操作与查询。
- 未实施财务交易入库、历史成本纠错审批、订单行成本匹配、汇率、税费或利润计算。开发期只添加代码，按维护者要求不在这一轮执行验收。


## V29 订单历史成本覆盖

[订单成本只读预览](order-cost-readiness-v29.md) 使用 Orders createdTime（当地业务日）、Seller SKU 当时有效映射与 sku_cost_version 同期单位成本，任何来源缺口阻止输出整单 COGS；实际采购 FIFO/结算仍待实施。
