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

## V37 · 人工确认生成采购草稿

补货策略本身仍然只做建议。新增 `POST /api/v1/replenishment/marketplaces/{id}/purchase-drafts` 是**单次人工确认**操作，仅创建本地 `DRAFT` 采购单；不提交审批、不触发 Amazon 写入。页面要求 Owner 选择现有 ACTIVE 供应商并人工输入原币单位采购价，不能自动猜测成本、币种或供应商。

请求必须包含 UUID `requestKey`、`sellerSku`、`supplierId`、`currency`、`unitCost`、`expectedObservationId`、`expectedPolicyUpdatedAt` 和 `expectedQuantity`。服务端在同一事务中复核租户、当前 ACTIVE Listing → 内部 SKU 映射、SKU 状态、策略版本、最新 FBA 观测 ID、观测时间/质量/数量与最终建议数量；任一变化均 409，要求刷新后重新确认。库存未知、部分观测、过期、未来时间或建议量超过 1,000,000 时不允许创建。

V37 `erp_replenishment_purchase_draft_source` 保留审计所需的政策参数、原始库存观测 ID/时间/可售量、采购数量、供应商、币种和单价快照，并将 `requestKey` 唯一约束绑定到采购单；同一创建者使用完全相同请求重试返回已有采购单，不会重复创建。采购单仍按照 PO-001 提交审批、独立 Owner 批准、分批收货。来源记录可通过 `GET /api/v1/replenishment/marketplaces/{id}/purchase-drafts` 按站点查看（最新 100 条）。

注意：`quality=OBSERVED` 仅为库存记录的质量标志，**不证明数据来自真实 Amazon**；Mock 数据生成的采购单也是本地草稿。此版本没有销量预测、在途抵扣、供应商 MOQ、自动采购、发货计划或 Amazon 货件写入。新增代码需要以实际 CI/Flyway/HTTP 测试结果为准。

## V38 · 相同观测禁止重复采购草稿

新增数据库唯一索引 `(tenant_id,seller_marketplace_id,seller_sku,observation_id)`，不同 `requestKey` 不能用同一条库存观测重复建立采购草稿；即使原草稿后续取消，仍需新的 FBA 观测、人工复核后才能再次生成。服务端在锁保护下提前返回 409，底层唯一索引提供并发兜底。不把已有采购单数量当作在途 FBA 数量。
