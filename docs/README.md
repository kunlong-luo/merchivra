# Merchivra 文档中心 · 唯一现行入口

> **CURRENT · 2026-11-25 / V237 完整 A3 7/7；V238–V240 快 CI 通过；V241 完整 A3 7/7；V242 完整 A3 7/7；V243 PG18 步骤失败；V244 完整 A3 7/7；V245 快 CI 通过；V246 快 CI 通过；V247 A3 6/7；V248 完整 A3 7/7；V249 快 CI 通过；V250 工作流未启动；V251 A3 失败；V252 完整 A3 7/7；V253 完整 A3 7/7；V254 完整 A3 7/7；V255 完整 A3 7/7；V256 A3 6/7；V257 快 CI 通过；V258 完整 A3 7/7；V259 快 CI 通过；V260 完整 A3 7/7；V261 V63 独立审核待 A3；V262 V121 快 CI 通过、A3 待验收；V263 快 CI 通过、A3 待核；V264 快 CI 通过、A3 待核；V265 快 CI 通过、A3 待核；V266 V123 补偿提案与批准待 A3；现行六维评分76/100。** Java 25/Spring Boot 4.1.2/**Gradle**/PostgreSQL 18、React19/**Next.js 16.4.5**/Node24/**pnpm 12.20.0 唯一包管理器**。本仓库仍是 **pre-alpha**；真实 Seller/Ads OAuth 关闭，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**；`realizedNetProfit=null`。所有现行代码和文档直接维护在 `main`，本入口不把本地手工账单或模拟 CI 冒充真实净利润。

## 先读这些权威文档

| 你要解决的问题 | 仅采用这个现行入口 |
| --- | --- |
| 项目实际实现了什么，哪些验收与生产阻断还存在 | [当前事实与精确 SHA](current-status.md) |
| 下一阶段哪些 P0 应先做，完成标准是什么 | [唯一现行计划](roadmap/current-plan.md) |
| 最新项目成熟度、为什么不是 90 分 | **[V231 最新独立六维 76/100 评分](governance/project-scorecard-v231-2026-11-24.md)**；[V230 75分](governance/project-scorecard-v230-2026-11-24.md)、[V229 74分](governance/project-scorecard-v229-2026-11-24.md)、[V228 73分](governance/project-scorecard-v228-2026-11-24.md) 都是**历史** |
| 代理/工程实现应遵守的强制规则 | [唯一 AGENTS.md](../AGENTS.md) · [ENG-001 工程实施规范](governance/engineering-standards.md) |
| 文档状态、为何保留旧 CI/PR/失败记录以及何时能称 VERIFIED | [GOV-001 文档控制](governance/document-control.md) · [DOC-196 审计](governance/docs-audit-2026-11-22.md) · [V191 历史审计](governance/docs-audit-v191.md) |
| 本地如何运行以及 Session HTTP 500 的友好处理 | [开发和诊断](development.md) · [独立 spark-db/Compose 安全](deployment/local-spark-db.md) |
| 按什么测试矩阵验收 Java/Next/PG18/浏览器 | [分层 CI/A3](testing/ci-policy.md) · [生产门禁（历史）](testing/release-readiness-v68.md) |
| 如何贡献、设计 UI、报告安全问题 | [贡献指南](../CONTRIBUTING.md) · [设计规范](../DESIGN.md) · [安全披露](../SECURITY.md) |

**注意：** `docs/roadmap.md` 和 `roadmap/mvp-freeze-release-v108.md` 是已明确标记 **历史归档**的旧计划，保留真实 A3、失败/复验及当时 P0 语义以便追溯，但**不再指导开发**。旧 `Agent.md` 兼容别名已删除，只有根 [AGENTS.md](../AGENTS.md) 生效。

## 按领域保留的文档目录（每个目录的用途）

| 路径 | 内容、保留理由 | 入口或代表性证据 |
| --- | --- | --- |
| `finance/` | **最高 P0**：真实收入、各类原币费用、重复收费、结算行和实际批次/退款 COGS，V91–V231 本地/第三方缺口、原始文件字节核对和版本阻断 | [V230 八类费用外部证据缺口](finance/v230-external-source-proof-dossier-and-owner-workflow.md) · [V229 原始来源审计](finance/v229-source-review-audit-integrity-and-external-proof-hold.md) · [V182 归属复核](finance/v182-settlement-attribution-evidence.md) |
| `roadmap/` | **现行**单一 P0 顺序与旧 A3/发布计划证据 | [current-plan.md](roadmap/current-plan.md) |
| `governance/` | [文档控制](governance/document-control.md)、DEC/ADR/财务 G1、现行工程规范、独立评分/历史审计 | [决策登记](governance/decision-register.md) · [工程规范](governance/engineering-standards.md) |
| `testing/` | 真 PostgreSQL18、Flyway、Chrome、合成 HTTP/安全边界与首次失败保留 | [ci-policy.md](testing/ci-policy.md) |
| `deployment/` | Compose、spark-db PostgreSQL18、备份、只读公开展示、隔离部署验证 | [local-spark-db.md](deployment/local-spark-db.md) |
| `api/` | 实际 Springdoc/OpenAPI/Orval 合同与本地 Seller API 操作 | [api/README.md](api/README.md) |
| `architecture/` | Spring/Next/PG18 架构、Mermaid 图纸、ADR 与模板、历史技术取舍 | [架构总览](architecture/overview.md) · [图纸索引](architecture/diagram-index.md) · [ADR 模板](architecture/adrs/template.md) |
| `requirements/` | 功能、非功能、业务验收与双向追踪 | [需求与实现追踪](requirements/traceability.md) |
| `product/` | 经营范围、SKU、核心业务流程、G1 规则、竞品与场景 | [业务流程](product/core-flows.md) · [利润会计规则（待批准）](product/finance-rulebook.md) |
| `purchasing/`, `inventory/`, `catalog/` | 供应商/采购、实物入库、批次、Listing/SKU 的实现与历史合成证据 | [正式供应商退货 V120](purchasing/supplier-followup-return-traces-v120.md) |
| `operations/`, `analytics/` | 经营来源质量、异常工单、运维手册与运营证据 | [运维手册](operations/operations.md) · [V89 经营来源质量](analytics/business-source-quality-v89.md) |
| `security/`, `integrations/` | Session/CSRF/tenant、Seller OAuth、SP-API/Ads 限流与访问边界 | [授权设计](security/seller-oauth.md) · [SP-API](integrations/amazon-sp-api.md) |
| `design/` | Next16 页面设计、导航与页面状态参考（旧 UX 目录已合并） | [导航与状态](design/navigation-and-page-states.md) |

不存在单纯为了“目录看起来少”而删除活动代码、测试、历史迁移、回退资产或可核查的版本证据的理由。详见根 [README 中文版](../README.zh-CN.md) 和 [英文版](../README.md) 的**仓库目录用途表**。

## 文档维护与财务证据

目录的保留与删除由 [GOV-001 文档控制](governance/document-control.md) 统一管理；实际版本、CI 成败与外部未核证边界只在 [当前事实](current-status.md) 和对应领域证据维护，下一阶段只在 [现行 P0](roadmap/current-plan.md) 维护。V231 的删除记录与本地文件摘要限制见 [专项证据](finance/v231-local-byte-claim-check-and-repository-pruning.md)。不再在文档中心复制完整版本流水账。

财务 G1/DEC 未签署、第三方真实原件未核证时，不得把本地摘要匹配、授权版本或合成 CI 当成真实费用或净利润；`realizedNetProfit=null`，#104 NO-GO。
