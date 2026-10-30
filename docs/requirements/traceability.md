# RTM-001 · MVP P0 全链路追踪矩阵（第二轮静态验收）

**状态：设计追踪已补齐，尚未批准或实现。** 表格中使用**完整 FR 编号**，便于机械检查；每行明确业务设计、数据来源、前端/API 契约和验收 AC。

| 需求 ID | 业务/规则设计 | 数据/来源 | API / UI | 验收用例 |
|---|---|---|---|---|
| FR-SYS-01 | [IAM-001](../architecture/tenant-bootstrap.md) | membership/role_binding | [API-001](../architecture/api-contracts.md) | AC-01 |
| FR-SYS-02 | IAM-001、[SEC-001](../security/security-design.md) | session/audit | API-001 | AC-16 |
| FR-SYS-03 | IAM-001 | bootstrap state/tenant | API-001 | AC-27 |
| FR-SHOP-01 | [BIZ-01](../processes/core-flows.md)、[INT-001](../integrations/amazon-sp-api.md) | seller_account | API-001 / [UX-002](../ux/page-state-matrix.md) | AC-02 |
| FR-SHOP-02 | [INT-002](../integrations/sp-api-operation-matrix.md) | seller_marketplace | API-001 | AC-28 |
| FR-CAT-01 | [DATA-001](../architecture/data-model.md) | product_sku | API-001 | AC-29 |
| FR-CAT-02 | [BIZ-02](../processes/core-flows.md)、[DOM-001](../architecture/domain-state-model.md) | listing | API-001 / UX-002 | AC-03 |
| FR-CAT-03 | DOM-001 | listing / mapping version | API-001 | AC-19 |
| FR-ORD-01 | [SYNC-001](../architecture/sync-engine.md) | sales_order / order_item | API-001 | AC-04、AC-05 |
| FR-ORD-02 | [BIZ-03](../processes/core-flows.md)、SEC-001 | sales_order / items | API-001 / UX-002 | AC-30 |
| FR-ORD-03 | [DATA-002](../architecture/source-reconciliation.md) | finance_transaction / refund match | API-001 | AC-06 |
| FR-ORD-04 | DOM-001 | raw fulfillment + refund state | API-001 / UX-002 | AC-18、AC-20 |
| FR-INV-01 | [BIZ-04](../processes/core-flows.md)、[DATA-001](../architecture/data-model.md) | fba_inventory_observation | API-001 | AC-07 |
| FR-INV-02 | BIZ-04、UX-002 | latest snapshot/freshness | API-001 | AC-08 |
| FR-INV-03 | DOM-001 | quality state, status buckets | API-001 | AC-21 |
| FR-COST-01 | [FIN-001](../architecture/finance-model.md) | product_cost_history | API-001 | AC-09 |
| FR-FIN-01 | DATA-002、[INT-002](../integrations/sp-api-operation-matrix.md) | finance_transaction | API-001 | AC-10 |
| FR-FIN-02 | FIN-001、DATA-002 | projection + source version | API-001 / UX-002 | AC-11 |
| FR-FIN-03 | DATA-002 | order_finance_match | API-001 / UX-002 | AC-22、AC-24 |
| FR-SYNC-01 | [SYNC-002](../architecture/sync-stream-contract.md) | sync_stream, sync_run | API-001 | AC-12 |
| FR-SYNC-02 | SYNC-002 | sync_attempt, sync_exception | API-001 / UX-002 | AC-13 |
| FR-SYNC-03 | SYNC-002 | sync_page_commit, lease epoch | API-001 | AC-23、AC-25 |
| FR-DASH-01 | FIN-001、DATA-002 | analytics_projection | API-001 / UX-002 | AC-14 |
| FR-MOCK-01 | [ARCH-001](../architecture/overview.md)、[TEST-002](../testing/end-to-end-scenarios.md) | synthetic fixtures | API-001 / UX-002 | AC-15 |
| FR-API-01 | API-001、SEC-001 | requestId / audit | API-001 / UX-002 | AC-26 |

## G1 业务验收追踪（单独设计验收，不代替 AC）

| G1 场景 | 涉及 FR | G1 验收 |
|---|---|---|
| FBA 销售/部分退款 | FR-FIN-02、FR-ORD-04 | G1-AC-01/02 |
| FBM 订单只读与缺失物流费 | FR-ORD-02、FR-FIN-02 | G1-AC-03 |
| Orders / Finances 防双计 | FR-FIN-03 | G1-AC-04/14 |
| FBA UNKNOWN / ZERO | FR-INV-03 | G1-AC-05 |
| SKU 归属冲突及未映射 | FR-CAT-02/03 | G1-AC-06/11 |
| USD/EUR 汇总 | FR-DASH-01 | G1-AC-07 |
| 历史成本与退款先到 | FR-COST-01、FR-ORD-03 | G1-AC-08 |
| 站点和 API 角色/权限 | FR-SHOP-02、FR-FIN-01、FR-SYS-01 | G1-AC-09/10/12 |
| 税费、退货及净额源 | FR-FIN-03、FR-INV-01 | G1-AC-13/15 |

详见 [G1-QA-001](g1-business-acceptance.md) 和 [BIZ-004](../product/business-scenarios.md)。G1-AC 是**人工业务规则复核条款，未执行**；原 30 条 AC 是系统实施后的验收规格，同样未执行。

## P1 / P2 预留（非 MVP 开发准入范围）

| 需求 ID | 后续设计依赖 | 状态 |
|---|---|---|
| FR-PO-01 | 采购、供应商、入库、审批、盘点 | P1：待业务设计与 AC |
| FR-FBA-01 | 头程、货件、FBA 入仓、物流成本 | P1：待业务设计与 AC |
| FR-ADS-01 | Amazon Ads 单独授权、广告归因窗口 | P1：待业务设计与 AC |
| FR-AUTO-01 | 规则引擎、审批、平台写入权限 | P2：待业务设计与 AC |

## 端到端验证与评审状态

[TEST-002](../testing/end-to-end-scenarios.md) 将 P0 功能组合为 8 条业务链路。此处的 `关联 AC` 是**设计覆盖**，没有执行记录。G1/G2/G3 全部尚未签署；物理 DDL、最终 OpenAPI 3.1 和真实 API 权限仍待确认。

修改 FR 必须同步修改流程/ADR、数据模型、API/UI、AC、E2E、风险及发布说明。
