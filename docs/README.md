# Merchivra ERP · 文档中心

> SP-API 开发更新：[INT-004 官方 SDK/离线接入代码](integrations/sp-api-code-integration.md) 已提交，覆盖官方多模块 SDK 工厂与核心读取封装；网络出站默认禁用，**真实 Seller OAuth/安全 Vault 与业务同步仍未完成**。

> 新增 [TEST-004 本地模拟 SP-API 全链路验证](testing/mock-full-chain.md)：登录 Session、配置店铺、模拟分页 Orders/Listing/FBA、写入已标识 MOCK 来源的核心表、查询/审计/重启。只覆盖纯虚构开发环境，不意味着真实 Amazon 接通。

> **M2 配置开发更新：** 增加 [API-004 本地 Seller Account/Marketplace 未连接配置与局部审计](api/local-seller-setup.md)。配置记录仍标记为 `UNCONFIGURED`，**不是 Amazon 授权**；缺少真实 PG/Compose 验证与正式安全签审。

> **2026-10-30 开发更新：** 已升级到 [Java 25 / Spring Boot 4 + Node 24 / Vite 8](architecture/stack-upgrade-2026-10-30.md)，加入 [Agent 策略](../Agent.md)，并提交 [按会话租户约束的核心只读 API 和工作台](api/core-read-api.md)；真实 Amazon 接入、完整 RBAC 和 PG/Compose 端到端验收**仍未完成**。后文 G1/G2 文档均属待签署设计。

> **M1 持久化 Mock 演示**：[本地 Mock 工作台](demo.md) 已直接在 main 提供店铺、商品、订单、FBA 库存、同步历史，以及 [DATA-004](architecture/core-domain-foundation.md) 的核心表 Mock 入库状态；不代表真实 Amazon 集成、认证或财务系统已经实现。

> **技术基线更新（2026-10-30）**：已升级至 Java 25 / Spring Boot 4.1.2 / Node 24 / Vite 8.3.5 / TypeScript 6.0.3，GitHub CI 与 Docker 使用对应版本，前端锁文件已生成。见 [DEP-002](architecture/stack-upgrade-2026-10-30.md)。

> **开发策略更新（2026-10-30）**：两个早期 PR 已合入 main；当前按 [轻量 CI 策略](testing/ci-policy.md) 推进 M0～M2，E2E 推迟至 M3～M4。下述 G1/G2 候选设计仍需业务/技术负责人审阅。

> **文档版本：0.4-g2-review｜日期：2026-10-30｜状态：Proposed / 待批准**
>
> 当前只完成**设计规格与评审修订**，不存在已完成的 Amazon ERP 业务系统。G1/G2/G3 均未批准；详见 [第二轮评审报告](governance/review-report-2026-10-30.md)。

## 第四轮（G2）数据与接口架构评审入口

- [物理数据字典、键和索引 DATA-003](architecture/physical-data-design.md)：PostgreSQL Schema 候选、跨租户复合外键、来源唯一键、金额和历史成本。
- [事务一致性 ARCH-003](architecture/consistency-boundaries.md)：同步 lease / 事务 / 可重放事实与最终一致投影。
- [Amazon 正规化字段 INT-003](integrations/amazon-data-contract.md)：订单、库存、财务来源及缺失字段语义。
- [租户与权限执行 SEC-002](security/tenant-enforcement.md)：服务端、数据库、Worker 的授权边界与安全负例。
- [可机读 OpenAPI 3.1.1 候选](api/README.md)：10 条资源路径、11 个操作及 Schema；认证方式仍待 DEC-09。
- [G2-QA-001 架构验收](requirements/g2-architecture-acceptance.md)：18 条设计验收条款（未执行）。
- [第四轮评审报告 REVIEW-004](governance/g2-review-2026-10-30.md)：已识别数据和接口风险、准入门禁。

**状态仍为 G1/G2/G3 均未批准**，本轮仅新增架构和契约草案，不存在数据库迁移或应用实现。

## 第三轮（G1）产品业务评审入口

- [G1 产品业务评审包](product/g1-review-package.md)：US 首发、FBA/FBM 范围、核心决策与评审退出条件。
- [FBA/FBM 履约边界](product/fulfillment-scope.md)、[SKU 映射策略](product/sku-listing-policy.md)。
- [8 个可手算的虚构经营案例](product/business-scenarios.md)、[费用和利润规则](product/finance-rulebook.md)。
- [15 条 G1 手工验收](requirements/g1-business-acceptance.md)、[G1 第三轮评审记录](governance/g1-review-2026-10-30.md)。
- **G1 仍未通过**：需明确选择方案、业务/财务审阅与登记决策。G2/G3 也未通过。此次不修改业务代码。

## 第一次阅读：按业务闭环而不是文件数量看

1. **产品与范围**：[PRD](requirements/mvp.md) → [功能需求](requirements/functional.md) / [非功能需求](requirements/nonfunctional.md) / [角色](product/personas.md) / [术语](product/glossary.md)。
2. **端到端业务**：[五条业务流程](processes/core-flows.md) → [订单/退款/库存状态不变量](architecture/domain-state-model.md)。
3. **技术与数据**：[系统架构](architecture/overview.md) / [模块边界](architecture/modules.md) → [概念 ERD](architecture/data-model.md) → [来源事实与对账](architecture/source-reconciliation.md)。
4. **外部集成**：[Amazon SP-API 总览](integrations/amazon-sp-api.md) → [操作级核对矩阵](integrations/sp-api-operation-matrix.md) → [同步架构](architecture/sync-engine.md) / [Stream 协议](architecture/sync-stream-contract.md)。
5. **安全与接口**：[初装及租户权限](architecture/tenant-bootstrap.md) / [安全设计](security/security-design.md) → [API 契约](architecture/api-contracts.md) → [UI 导航](ux/navigation.md) / [页面状态矩阵](ux/page-state-matrix.md)。
6. **验证与审查**：[验收用例](requirements/acceptance.md) → [端到端场景](testing/end-to-end-scenarios.md) / [测试策略](testing/test-plan.md) → [追踪矩阵](requirements/traceability.md) → [文档评审报告](governance/review-report-2026-10-30.md)。

## 当前设计评审结果

| 关口 | 应回答的问题 | 状态 |
|---|---|---|
| G1 产品冻结 | 谁用、哪些站点、FBA/FBM、财务/成本口径 | **未通过**：负责人输入缺失 |
| G2 架构冻结 | 多租户、原始事实、流式同步、平台权限/唯一键 | **未通过**：候选方案完成但待签署/真实字段验证 |
| G3 开发就绪 | API Schema、物理 ERD、可执行测试契约、权限批准 | **未通过**：正式实现前补齐 |

优先按 [G1 业务包](product/g1-review-package.md) 评审，再处理 [DEC-001](governance/decision-register.md)；尚未签署的金额和站点示例不能当作真实卖家事实。

必须先审阅 [待确认资料](requirements/decision-questions.md) 与 [决策登记](governance/decision-register.md)，不将默认值当作已确认事实。

## 重点图纸与数据

- [图纸目录](diagrams/index.md)：系统/部署图、概念 ERD、订单/退款状态、库存对账、同步时序、租户初始化与 UI。
- [数据模型 DATA-001](architecture/data-model.md)：支持映射缺失、账务事实和每 Stream 多次运行。
- [财务 FIN-001](architecture/finance-model.md)：预计/已入账/结算必须分列，同源金额不能重复相加。
- [来源对账 DATA-002](architecture/source-reconciliation.md)：保留无法匹配的财务事件与数据质量状态。

## 文档治理

- [状态、评审与变更规则](governance/document-control.md)
- [第二轮评审记录](governance/review-report-2026-10-30.md)
- [风险登记](governance/risk-register.md)
- [关键决策](governance/decision-register.md)
- [ADR-0001 模块化单体](architecture/adrs/0001-modular-monolith.md)、[ADR-0002 租户](architecture/adrs/0002-tenancy.md)、[ADR-0003 财务事实](architecture/adrs/0003-finance-ledger.md)
- [ADR 模板](templates/adr-template.md)、[阶段路线](roadmap.md)、[运维](ops/operations.md)

**文档不是执行证据**：所有测试用例目前均为未执行；部署/生产的安全与 API 资格必须在实现时验证。
