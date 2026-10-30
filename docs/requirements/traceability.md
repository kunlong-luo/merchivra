# RTM-001 · 需求—图纸—数据—验收追踪

| Epic | FR | 核心设计 | 数据实体 | 测试 |
|---|---|---|---|---|
| SYS | FR-SYS-01/02 | [安全与权限](../security/security-design.md) | tenant, user, role | AC-01, AC-16 |
| SHOP | FR-SHOP-01/02 | [Amazon 接入](../integrations/amazon-sp-api.md) | seller_account, seller_marketplace | AC-02 |
| CAT | FR-CAT-01/02 | [数据模型](../architecture/data-model.md) | product_sku, listing, sku_mapping | AC-03 |
| ORD | FR-ORD-01/02/03 | [业务流程](../processes/core-flows.md)、[同步引擎](../architecture/sync-engine.md) | sales_order, order_item | AC-04/05/06 |
| INV | FR-INV-01/02 | [业务流程](../processes/core-flows.md) | fba_inventory_snapshot | AC-07/08 |
| COST | FR-COST-01 | [财务口径](../architecture/finance-model.md) | product_cost_history | AC-09 |
| FIN | FR-FIN-01/02 | [财务口径](../architecture/finance-model.md) | finance_transaction | AC-10/11 |
| SYNC | FR-SYNC-01/02 | [同步引擎](../architecture/sync-engine.md) | sync_job, sync_checkpoint, sync_attempt | AC-12/13 |
| DASH | FR-DASH-01 | [财务口径](../architecture/finance-model.md)、[UI](../ux/navigation.md) | analytics_projection | AC-14 |
| MOCK | FR-MOCK-01 | [技术架构](../architecture/overview.md) | mock fixtures | AC-15 |

**维护规则**：新增 P0 FR 必须补上流程/设计/测试三者中的至少一条可追溯链接；无法定义可验证验收条件的需求不进入开发就绪状态。
