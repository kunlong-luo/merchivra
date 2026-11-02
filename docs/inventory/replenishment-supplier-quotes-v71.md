# INV-071 · V71 FBA 补货建议与供应商 MOQ/箱规衔接

产品阶段 V71 / Flyway V68，2026-11-02。V22 站点补货策略、V37/V38 人工采购草稿、V42 供应商 SKU 报价、V70 订单观察值均保持原有职责。没有新的采购/审批引擎、生产通知通道、Amazon 写入或自动购买。

## 流程

1. 当前 Tenant/Marketplace Owner 从原有 FBA 库存补货页查看 OBSERVED 质量、48 小时内、非 NULL 的可售库存。如果质量未知、过期或异常，不能得到报价可采购数量。
2. 只读 GET /api/v1/replenishment/marketplaces/{marketplaceId}/supplier-quotes 根据 ACTIVE Listing → ACTIVE 内部 SKU 映射 → ACTIVE SKU → V42 erp_supplier_sku_rule → ACTIVE Supplier，返回与本 Marketplace 当前策略相关的供应商选项。复用 ReplenishmentService.advice() 的观测质量判断，没有新的预测模型。
3. 根据现有 SupplierPurchasePlanService.orderQuantity() 方法计算 ceil(max(基础建议件数, MOQ) / 箱规倍数) × 箱规倍数。大于 1,000,000 时 LIMIT_EXCEEDED，不返回订货件数；不可信库存也不会因为有 Supplier Rule 就变得可计算。原币总额仅为手工录入单价 × 实际采购件数，不包含税费运费；供应商提前期不代表 Amazon 承诺入仓日。
4. Owner 在 React 补货页明确选择一个 READY 的 V42 报价，预览基础件数、实际件数、MOQ/箱规、供应商提前期、原币单价和总额，再人工点击创建 DRAFT。请求仍走原有 V37 POST /api/v1/replenishment/marketplaces/{marketplaceId}/purchase-drafts，绝不跳过人工审批。
5. 选择 V42 报价时，必须成对提交 expectedSupplierRuleUpdatedAt 与 expectedOrderQuantity。后端在同一事务内重新校验当前租户、ACTIVE 映射、V22 策略、最新 FBA 观测、V42 供应商规则版本/币种/单价/箱规数量。变化返回 409；缺任一成对字段返回 400。
6. Flyway V68 将实际 ordered_quantity、供应商规则版本、MOQ、箱规、供应商交期和 pricingSource 快照补入现有 V37 来源表；数据库约束保证版本化报价的采购数量至少满足原建议和 MOQ、并为箱规倍数，最大 1,000,000。已有 V37 手工草稿平滑迁移为 MANUAL、实购=原建议。仍用 V38 同一观测防重和 V37 requestKey 幂等，同一供应商报价规则修改后旧客户端快照不得创建 PO。
7. 原有 V37 手工供应商/单价模式保留兼容，但页面明确标示不自动符合 MOQ/箱规。购买流程仍是 DRAFT → 人工提交 → 由不同 Owner 审批；不会直接成为在途 FBA、供应商已确认订单或财务支出。

## 验证边界

- scripts/smoke_replenishment_supplier_v71.py 仅用于一次性 localhost PG18 模拟数据库，Seller/Ads 真实网络禁用。
- 模拟 18 基础件、MOQ 25、箱规 12，实际采购为 36 件；验证原币价格与交期变化、过期报价拒绝、错误数量/币种/单价拒绝，确认只创建 DRAFT。
- 错误/缺失 V42 报价字段不可建单；同 requestKey 重试幂等，新 key 同一库存观测拒绝。来源历史分别显示原建议/实购与报价证明。未知质量使 READY 失效。
- Java 25 Gradle、React 19/TypeScript/Vitest、PostgreSQL18/Flyway、Docker Compose、可选 Chromium 由各自 CI 验证；离线测试不等同真实 Seller 授权或生产业务验收。

## 未完成

跨市场/多币种预算、自动采购比价、可信库存预测、季节性、取消/退款净销量、供应商承诺送达时间、实际 Amazon 货件与在途抵扣、自动付款与真实 SP-API/Ads 集成均尚未实现。V70 销量参考没有直接改变 V22 补货点、目标量或本轮采购数量公式。