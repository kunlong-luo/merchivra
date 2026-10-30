# REVIEW-002 · 第二轮架构一致性评审报告

**日期：2026-10-30｜评审性质：文档静态设计评审｜范围：D0 需求、流程、数据、集成、安全、测试｜状态：待业务/技术批准**

> 本轮只完成**文档审查与修订**。下列「设计已修订」表示方案已经写在文档中，不代表代码实现、真实 API 验证或评审签字完成。

## 1. 主要评审发现与处置

| ID | 严重度 | 原有设计缺口 | 处理文档 | 当前状态 | 关闭条件 |
|---|---|---|---|---|---|
| REV-01 | Blocker | 将 Refunded 当作订单履约状态 | [DOM-001](../architecture/domain-state-model.md), [BIZ-001](../processes/core-flows.md) | 方案已修订 | 业务认可状态定义；AC-18 |
| REV-02 | Blocker | 订单金额与财务流水可能双重计入 | [DATA-002](../architecture/source-reconciliation.md), [FIN-001](../architecture/finance-model.md) | 方案已修订 | 财务确认收入/费用分类；AC-22/24 |
| REV-03 | High | Sync checkpoint 绑定单一运行任务 | [SYNC-002](../architecture/sync-stream-contract.md) | 方案已修订 | stream/run/attempt 物理模型获批；AC-23/25 |
| REV-04 | High | 未映射 SKU 会阻断订单/库存原始事实 | [DOM-001](../architecture/domain-state-model.md), [DATA-001](../architecture/data-model.md) | 方案已修订 | 无映射场景可落库；AC-19 |
| REV-05 | High | 退款、发货、结算三个时间轴没有独立建模 | [DOM-001](../architecture/domain-state-model.md) | 方案已修订 | AC-18/22 |
| REV-06 | High | API 未定义稳定错误/幂等/分页策略 | [API-001](../architecture/api-contracts.md) | 方案已修订 | OpenAPI 3.1 + 契约测试；AC-26 |
| REV-07 | Blocker | 「创建首个管理员」存在安全引导缺口 | [IAM-001](../architecture/tenant-bootstrap.md) | 方案已修订 | Auth/bootstrap ADR、AC-27 |
| REV-08 | High | 财务匹配键在缺少源稳定 ID 时不可靠 | [DATA-002](../architecture/source-reconciliation.md) | 风险受控提案 | 真实脱敏样本验证不冲突 |
| REV-09 | High | Amazon v2026 Orders 的过滤/分页/字段差异未落表 | [INT-002](../integrations/sp-api-operation-matrix.md) | 文档核实 | 对目标站点/角色试点记录 |
| REV-10 | High | 库存缺失/过期/零数量混淆 | [DOM-001](../architecture/domain-state-model.md) | 方案已修订 | AC-21 与 FBA 样本 |
| REV-11 | Medium | FR → 模块 → 数据 → UI → 测试映射不完整 | [RTM-001](../requirements/traceability.md), [TEST-002](../testing/end-to-end-scenarios.md) | 持续完善 | P0 行映射无空项 |
| REV-12 | High | 部署自举与 Auth 尚未完成，却可能误称可生产部署 | [IAM-001](../architecture/tenant-bootstrap.md), [SEC-001](../security/security-design.md) | 明确阻断 | Auth/tenant 安全测试通过 |
| REV-13 | High | 租户内多账号数据归属/授权范围不明 | [IAM-001](../architecture/tenant-bootstrap.md) | 提案待定 | 角色 + 账号作用域测试 |
| REV-14 | Medium | 汇率、订单/财务口径依赖卖家未确认 | [DEC-001](decision-register.md), [FIN-001](../architecture/finance-model.md) | **未解决** | G1 财务/卖家决策 |
| REV-15 | High | Amazon 私有/公开开发者资格无法由开源自动替代 | [INT-001](../integrations/amazon-sp-api.md), [INT-002](../integrations/sp-api-operation-matrix.md) | **外部待核实** | 获得实际应用类型及 role 确认 |

## 2. 设计评审结论

- **文档完备度**：需求、概念架构、数据关系、逻辑状态、同步和 API 行为均有候选定义。
- **产品冻结 G1：未通过**。目标站点、FBA/FBM、真实数据规模、利润指标口径与成本来源未由负责人确认。
- **架构冻结 G2：未通过**。身份机制、财务事件键、实体约束、Amazon 角色及现场样本需要验证。
- **开发准入 G3：未通过**。尚缺签字后的 OpenAPI/schema、物理 ERD、可执行合同测试。
- **下一轮**：完成 [DEC-001](decision-register.md) 的关键决策，更新 G1 范围；之后精化物理表与 API 细节。设计修订不等于签收。

## 3. 评审证据

- 内部审查：检查 PRD、FR、NFR、ERD、业务流程、同步方案、权限、利润模型与验收方案的字段/语义一致性。
- 官方资料核对：Orders v2026-01-01 migration（分页令牌 24h，履约枚举）、Finances v2024-06-19（独立 role）、FBA Inventory v1（库存分桶），证据列表见 [INT-002](../integrations/sp-api-operation-matrix.md)。
- 本次未进行真实接口请求，未取得卖家原始订单样本，未执行业务代码及安全测试。

## 4. 评审签署区（空白、不得预填）

| 角色 | 评审人 | 结论 | 日期 | 备注 |
|---|---|---|---|---|
| 产品负责人 | 待指定 | 待审 | — | |
| 卖家运营代表 | 待指定 | 待审 | — | |
| 财务代表 | 待指定 | 待审 | — | |
| 技术负责人 | 待指定 | 待审 | — | |
| 安全负责人 | 待指定 | 待审 | — | |
