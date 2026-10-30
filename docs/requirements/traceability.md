# RTM-001 · 全链路需求追踪（第 2 轮）

> **状态：设计追踪，不是测试通过证明。** P0 需求必须拥有 FR → 业务场景/状态 → 数据 → API/UI → AC / E2E 的可见关系；正式物理表与 OpenAPI YAML 仍在 G3 前待定。

| Epic | FR | 领域/流程/图纸 | 数据/来源模型 | API/UI | 验收 |
|---|---|---|---|---|---|
| SYS | FR-SYS-01/02/03 | [IAM-001](../architecture/tenant-bootstrap.md)、[SEC-001](../security/security-design.md) | tenant/user/membership/role | [API-001](../architecture/api-contracts.md)、[UX-002](../ux/page-state-matrix.md) | AC-01/16/27，E2E-01/02 |
| SHOP | FR-SHOP-01/02 | [BIZ-01](../processes/core-flows.md)、[INT-002](../integrations/sp-api-operation-matrix.md) | seller_account, seller_marketplace | API-001、UX-002 | AC-02，E2E-01 |
| CAT | FR-CAT-01/02/03 | [DOM-001](../architecture/domain-state-model.md)、BIZ-02 | product_sku, listing, mapping version | API-001、UX-002 | AC-03/19，E2E-02/04 |
| ORD | FR-ORD-01/02/03/04 | BIZ-03、DOM-001 | sales_order, order_item, raw status | API-001、UX-002 | AC-04/05/06/18/20，E2E-03 |
| INV | FR-INV-01/02/03 | BIZ-04、DOM-001 | fba_inventory_observation | API-001、UX-002 | AC-07/08/21，E2E-04 |
| COST | FR-COST-01 | [FIN-001](../architecture/finance-model.md) | product_cost_history, fx_rate | API-001、UX-002 | AC-09/14，E2E-07 |
| FIN | FR-FIN-01/02/03 | BIZ-05、[DATA-002](../architecture/source-reconciliation.md)、FIN-001 | finance_transaction, order_finance_match | API-001、UX-002 | AC-10/11/22/24，E2E-05 |
| SYNC | FR-SYNC-01/02/03 | [SYNC-001](../architecture/sync-engine.md)、[SYNC-002](../architecture/sync-stream-contract.md) | sync_stream, sync_run, sync_attempt, page_commit | API-001、UX-002 | AC-05/12/13/23/25，E2E-06 |
| DASH | FR-DASH-01 | FIN-001、DATA-002 | analytics_projection, cost / source versions | API-001、UX-002 | AC-14/22，E2E-05/07 |
| MOCK | FR-MOCK-01 | [ARCH-001](../architecture/overview.md) | synthetic fixtures | API-001、UX-002 | AC-15，E2E-01 |
| API | FR-API-01 | [API-001](../architecture/api-contracts.md)、SEC-001 | requestId, audit_log | API-001、UX-002 | AC-26，E2E-08 |

## 状态与证据

- ✅ **追踪链已设计**：每一条 FR/模块具有架构/数据/API/验收的候选关联。
- ⏳ **评审未签署**：真实业务口径、权限批准、接口字段及物理数据结构仍待 G1/G2。
- ❌ **实施/测试尚未完成**：所有 AC/E2E 均为待实现、待执行，不能标成 PASS。

## 变更规则

新 P0 功能没有可验证测试场景不得进入开发；任何变更都须更新 FR → ADR/数据 → API/UI → AC → 风险/发布说明。评审决策详见 [DEC-001](../governance/decision-register.md)。
