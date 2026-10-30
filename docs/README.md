# Merchivra ERP · 文档中心

> **文档版本：0.1-draft｜基线日期：2026-10-30｜状态：待产品/技术评审**
>
> 本目录是一套**设计稿，不是已经实现的功能清单**。所有未获卖家确认、未获得 Amazon 授权、未测试的假设均标注「待确认」。本文档不包含业务代码。

## 阅读顺序（首次参与项目）

1. [产品需求 PRD](requirements/mvp.md) → 确定做什么、不做什么。
2. [功能需求矩阵](requirements/functional.md) 与 [用户角色](product/personas.md) → 对齐业务场景。
3. [业务流程](processes/core-flows.md) → 验证实际操作流程。
4. [系统架构](architecture/overview.md) → 明确系统边界及技术原则。
5. [数据模型/ER 图](architecture/data-model.md)、[同步引擎](architecture/sync-engine.md) → 审核最关键设计。
6. [Amazon SP-API 接入](integrations/amazon-sp-api.md)、[安全设计](security/security-design.md) → 识别外部限制。
7. [UI 信息架构](ux/navigation.md)、[测试验收](testing/test-plan.md) 与 [里程碑](roadmap.md) → 准备交付。

## 文档清单与负责人（角色而非指定个人）

| 层级 | 文档 | 建议评审人 | 状态 |
|---|---|---|---|
| 产品 | [PRD / MVP](requirements/mvp.md)、[功能需求](requirements/functional.md)、[角色](product/personas.md) | 产品负责人、卖家 | 待确认 |
| 产品 | [术语表](product/glossary.md)、[需求追踪](requirements/traceability.md) | 产品、开发 | 草案 |
| 业务 | [核心业务流程图](processes/core-flows.md) | 运营、财务、供应链 | 待确认 |
| 质量 | [非功能需求](requirements/nonfunctional.md)、[验收用例](requirements/acceptance.md) | 开发、测试、运维 | 草案 |
| 架构 | [系统架构](architecture/overview.md)、[模块边界](architecture/modules.md) | 技术负责人 | 草案 |
| 数据 | [ER 图/数据字典](architecture/data-model.md)、[财务口径](architecture/finance-model.md) | 后端、财务 | 待确认 |
| 集成 | [同步引擎](architecture/sync-engine.md)、[SP-API](integrations/amazon-sp-api.md) | 集成负责人 | 待授权核对 |
| 安全 | [安全、隐私与权限](security/security-design.md) | 技术负责人、安全负责人 | 待评审 |
| 体验 | [导航与线框](ux/navigation.md) | 产品、UX | 草案 |
| 部署 | [部署拓扑](architecture/deployment.md)、[运维方案](ops/operations.md) | 运维、后端 | 草案 |
| 治理 | [图纸目录](diagrams/index.md)、[ADR](architecture/adrs/0001-modular-monolith.md)、[版本计划](roadmap.md) | 项目维护者 | 草案 |
| 决策 | [未决问题与资料清单](requirements/decision-questions.md)、[文档控制](governance/document-control.md) | 项目负责人 | 需输入 |

## 三个评审关口

- **G1 — 产品冻结**：P0 功能、目标卖家类型、站点、业务术语及利润口径被确认。
- **G2 — 架构冻结**：租户隔离、授权、实体关系、同步幂等性、安全方案被确认。
- **G3 — 开发就绪**：功能需求能对应验收用例；接口依赖、风险与 Mock 数据可用。

所有图均为可编辑 Mermaid；见 [图纸索引](diagrams/index.md)。不得将「草案」与「已实施」混同。

## 范围约定

- P0：第一版必须；P1：完成后迭代；P2：后续远景。
- SHOULD：推荐但允许在 ADR 中说明例外；MUST：进入验收的约束。
- MVP 初期只读 Amazon 数据；平台写入（改价/库存/Listing）后置。
- 初版适用于自部署卖家；公开应用向其他卖家授权属于另一个合规工作流。
