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

## G2 数据与接口约束关联（设计级）

| 业务/风险 | FR/验收链 | G2 方案与证据 |
|---|---|---|
| 租户与账号隔离 | FR-SYS-01, FR-SHOP-02, AC-01/28 | [DATA-003](../architecture/physical-data-design.md)、[SEC-002](../security/tenant-enforcement.md)、G2-AC-01/02/15 |
| SKU 版本和成本 | FR-CAT-03, FR-COST-01, AC-09/19 | DATA-003、G2-AC-03 |
| FBA 库存 UNKNOWN | FR-INV-03, AC-21 | [INT-003](../integrations/amazon-data-contract.md)、G2-AC-04 |
| 平台财务来源辨重 | FR-FIN-01/03, AC-10/24 | DATA-003、INT-003、G2-AC-05/06 |
| 同步任务与检查点 | FR-SYNC-01/02/03, AC-05/23/25 | [ARCH-003](../architecture/consistency-boundaries.md)、G2-AC-07/08/09/11 |
| API 与授权/数据类型 | FR-API-01, AC-26 | [API-002](../api/README.md)、G2-AC-12/13/16 |

G2 全部为**设计验收规范**，并未执行任何数据库/HTTP 测试，详见 [G2-QA](g2-architecture-acceptance.md)。

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


## V18 · FR-SHOP-01/02 实现与剩余验收（2026-10-31）

- FR-SHOP-01：公有应用 OAuth State/Callback、AES-GCM 保存、Seller/Marketplace 核验、脱敏审计、本地撤销及同 Seller 重授权已经编码。不可切换历史 Seller；撤销同步使角色/Marketplace 证据失效。
- FR-SHOP-02：本地 Marketplace 预设与区域校验、Seller v1 参与状态和 24h 时效门禁已有代码。
- Gate：必须用真实 Amazon 应用、Seller 和已批准角色验证生产 OAuth 与 Sellers v1；此前这些条款均为**离线模拟覆盖**，不得关闭需求验收项。
- 依赖：正式 Orders、Listing、FBA 的数据同步只有在上述 gate 通过后才可算“已接入”。


### V19 代码优先交付（未进行测试/生产验收）

- `FR-SHOP-01`：新增私有 Seller 应用的一次性服务端凭证导入/状态/撤销，可与公有 OAuth 明确区分，状态待 Sellers v1 验证。见 V17 SQL 与 INT-005。
- `FR-CAT-01`：新增 Owner 内部 SKU 增改归档恢复业务 API 和 React 页面。
- `FR-CAT-03`：新增 Listing→内部 SKU 显式人工确认/解除，历史版本闭合而不硬删除。
- `FR-API-01`：租户/Owner 从服务端 Session 获取；Catalog 写接口由 CSRF 守卫。
- 未测量覆盖率、未执行 Java/React CI、未做实际 PostgreSQL 迁移或 Amazon 实时 API 调用，不得关闭对应生产验收项。


### V19/V20 增量成本、采购代码（待验收）

- `FR-COST-01`：SKU 原币历史单位成本、版本生效日、半开区间，服务端租户 Owner 写入；未接注文处理财务和利润。
- `FR-PO-01`（P1）：供应商、本地采购单、另一 Owner 审批、追加式分批收货。
- `FR-FBA-01`：本轮收货仅属本地采购域，绝不等同 FBA 入仓或货件 API。
- 此表标记的是实现路径，不是通过验收的证据；所有新代码和迁移按用户要求后续统一测试。


### V21 财务来源事实及审核（功能代码已加入，未验收）

- `FR-FIN-01`：有符号原币来源事件、按 Marketplace/来源家族/来源 ID 的稳定去重；人工录入不视为 Amazon 原始记录。
- `FR-FIN-03`：人工关联同站点订单与事件来源不可变隔离、不可匹配事件独立保留，按币种和类别聚合。
- `FR-API-01`：当前 Owner Session + CSRF、键集分页、跨店铺匹配阻断；尚待 DB/HTTP 及授权环境验收。


### V22 库存补货建议（代码已加入，待验收）

- `FR-INV-02`、`FR-AUTO-01`：基于手动策略和最新可信 FBA 可售观测提出只读补货数量、标识来源缺失/过期/不完整；仅 Owner 可修改规则，绝不自动发起 Amazon 远程操作。
- `FR-PO-01`：当前采购是独立本地流程，补货建议尚未自动转换采购单，避免以未知在途冲抵目标数量。
