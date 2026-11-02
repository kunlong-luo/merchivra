# INV-074 · V74 解释性补货优先级与本地采购核对

2026-11-02。产品 V74 / Flyway V71（查询索引）+ V72（既有 LIVE 来源约束修复）。按原 V22（48h 内完整 OBSERVED FBA）、V70（完整 28d 已观测 OrderItems）、V71（V42 供应商 MOQ/箱规条款）、V37/V68（有来源快照的本地采购）构建**只读**分析。没有自动采购、付款、SP-API 写入或真实承运商查询。

## API 与证据门禁

GET /api/v1/replenishment/marketplaces/{marketplaceId}/priorities，仅当前 Owner Session ACTIVE tenant/marketplace，最多 500 个配置策略的 Seller SKU。后端复用原库存、订单覆盖与报价服务，额外核对和 V22 完全一致的 latest observationId 所带 FBA data_origin。

- 没有当前可用 FBA 库存、质量 UNKNOWN/INCOMPLETE 或观测过期 → STOCK_UNTRUSTED / BLOCKED；缺失不能变为零。
- V70 订单窗口未完整覆盖、订单项未观测、未映射、没有订单 → DEMAND_INCOMPLETE / REVIEW_STOCK_ONLY。完整窗口但该 SKU 件数为 0 → NO_SKU_RATE / REVIEW_STOCK_ONLY。它们都不是未来需求为零。
- 有足够订单但 FBA data_origin 与订单 mode MOCK/LIVE 不一致 → SOURCE_MISMATCH / BLOCKED，**绝不能把 Mock 历史和真实库存混算**。
- 只有完整 FBA、完整订单源、同种来源且 28d SKU 件数大于 0，才计算示意覆盖天数：FBA fulfillable × 28 / observedUnits28d，显示一位小数。示意策略提前期件数：ceil(observedUnits28d × policyLeadTimeDays / 28)，超过 1,000,000 返回 NULL。风险比较用**未四舍五入**的 fulfillable×28 和 observedUnits28d×leadDays。
- 覆盖天数不长于策略提前期 → LEAD_WINDOW_EXPOSURE / HIGH；其他 V22 REORDER → POLICY_REORDER / MEDIUM；其他 → HISTORICAL_COVERAGE_OK / LOW。HIGH/MEDIUM/LOW 仅为人工审查优先级，**不是未来准确断货预测**；订单观测未扣退款、取消、促销/季节变化。
- 供应商选项仅统计 V71 READY 且 observationId + policyUpdatedAt 一致的报价，显示人工供应商 leadTimeDays 区间，不承诺实际到货日、不换汇或自动选中供应商。

## 本地采购对照与人工保护

Flyway V71 只给 erp_replenishment_purchase_draft_source 增加 (tenant_id,seller_marketplace_id,seller_sku,purchase_order_id) 查询索引，不造平行库存表。仅把有 V37/V71 来源的采购关联到特定 Marketplace Seller SKU；其他人工本地 PO，即使内部 SKU 相同，也不猜其归属。

- localDraftOrSubmittedUnits：DRAFT/SUBMITTED 本地采购数量；
- localApprovedUnreceivedUnits：APPROVED/RECEIVING 订单数量减本地真实已收，不等于 Amazon 在途；
- localReceivedUnits：上述已批准、RECEIVING、RECEIVED 的本地已登记实收，不能等同 QC 放行/FBA 入仓；
- localPendingOrderCount：进行中的非 CANCELLED、非 RECEIVED 明确来源 PO 张数。

如果已有进行中 PO，React 在点击“生成本地 DRAFT”前重新调用 V74 接口，向 Owner 显示未收件数、要求确认；核对失败则阻止生成。V71 现有 observationId + policy version + supplier price/MOQ 校验、request key 幂等及不同 Owner 审批继续生效；V74 自己不写库存、不创建采购。

## 验收

JUnit + Vitest：缺库存、缺订单数据、零 SKU 速率、跨源、精确提前期边界、严格 null/enum 及 Session-only API；Chromium 360px 测试未知库存与已有本地 PO 提示。scripts/smoke_replenishment_priority_v74.py 在一次性 localhost PostgreSQL18、Mock 启用且 SP-API/OAuth 关闭场景，通过真实 HTTP + PG18 组装 28d 覆盖、FBA、V42 报价、V71 PO、双 Owner 审批、本地实收、MOCK/LIVE 冲突与库存 UNKNOWN。CI 不能证明真实供应商/承运商履约或 Amazon FBA 在途。

后续再讨论可信 FBA Inbound 货件与本地 PO 的明确 ID 关联、商家预算/现金流审批、实际物流与供货合同 SLA 及真实授权联调。

## V24 LIVE 读同步来源约束的前向修复

真实 PG18 集成验证发现 V24 的只读 Catalog/FBA 同步写入 LIVE 来源标记，而 V5 的 marketplace_listing 与 fba_inventory_observation 原始 CHECK 仍仅允许 UNVERIFIED/MOCK。Flyway V72 显式拓宽为 UNVERIFIED/MOCK/LIVE，与 V11 Orders/OrderItems 的 LIVE 来源一致，不支持任何其他来源标记，不改变现有行、租户引用、库存/质量与金额校验。PG18 验收把 FBA 来源改为 LIVE，检查新约束能正确保存并触发 V74 与 MOCK 订单之间的风险阻断，恢复 MOCK 后保留历史观测案例。这是**只读同步写入已有数据表时的约束修复**，不是授权真实 Amazon API。
