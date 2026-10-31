# INV-004 · SKU 补货策略与来源质量限制（V22）

**代码已实现，仍待集中验收。** 这是一种仅在本地给出数量建议的保守能力，不会创建采购单，不会调用 Amazon Feeds、FBA 货件 API 或写入真实库存。

## 功能

- Owner 管理每个 Marketplace/Seller SKU 的 `reorderPoint`、`targetStock` 和 `leadTimeDays`。生效策略在 `erp_replenishment_policy`，更改和删除在 `erp_replenishment_policy_audit` 留审计。
- `GET /api/v1/replenishment/marketplaces/{id}/policies` 列出当前站点的策略。
- `POST /api/v1/replenishment/marketplaces/{id}/policies` 创建或修改策略，要求存在相同租户/站点的 Listing、目标大于阈值、输入数量和提前期合理。
- `DELETE /api/v1/replenishment/marketplaces/{id}/policies?sellerSku=...` 删除策略。用 URL query 参数而不是路径段，以支持含 `/` 的 Seller SKU。
- `GET /api/v1/replenishment/marketplaces/{id}/suggestions` 读取当前库存观测后的建议及不可计算原因。
- React 核心工作台 → FBA 库存 → “SKU 补货策略与建议”提供策略管理和只读建议。全部 API 只允许当前租户 Owner。

## 规则

1. 每条策略只观察当前站点、当前 SKU 的最新一条 FBA 库存观测 `fulfillable`。数量是原样观测，不把缺失改为 0。
2. 观测必须 `quality=OBSERVED` 且 `fulfillable` 非 NULL，来源 `observed_at` 不早于 48 小时且不过度超前。否则状态为 `NO_OBSERVATION`、`STALE_OBSERVATION` 或 `INCOMPLETE_OBSERVATION`，建议数量为 NULL（**不能计算**）。
3. 当可信可售量小于等于 `reorderPoint` 时，`suggestedQuantity = max(0, targetStock - fulfillable)`，状态 `REORDER`；高于阈值时 `HEALTHY`、建议为 NULL。
4. 提前期作为人类策略参数保存，不预测销量、需求或在途到仓时间。采购单未分配到 Marketplace，不能偷扣本地采购收货或猜测 Amazon inbound 库存。

后续模块可增加销量预测、原币资金约束、季节性、供应商采购量/MOQ、人工批准后创建采购单、货件跟踪及通知，但必须明确模型假设与来源可信度。该实现**没有实际运行 PostgreSQL/Java/浏览器/生产验收**。
