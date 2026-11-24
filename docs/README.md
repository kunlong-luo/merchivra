# Merchivra 文档中心 · 唯一现行入口

> **CURRENT · 2026-11-24 / V230B 与 V231 均已按各自精确代码 SHA 通过完整 A3 7/7；现行六维评分76/100。** Java 25/Spring Boot 4.1.2/**Gradle**/PostgreSQL 18、React19/**Next.js 16.4.5**/Node24/**pnpm 12.20.0 唯一包管理器**。本仓库仍是 **pre-alpha**；真实 Seller/Ads OAuth 关闭，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**；`realizedNetProfit=null`。所有现行代码和文档直接维护在 `main`，本入口不把本地手工账单或模拟 CI 冒充真实净利润。

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
| `architecture/` | Spring/Next/PG18 领域划分与 ADR、历史技术选型取舍 | [架构总览](architecture/overview.md) · [十年演进基线与模块](architecture/modules.md) |
| `requirements/` | 功能、非功能、业务验收与双向追踪 | [需求与实现追踪](requirements/traceability.md) |
| `product/` | 经营范围、Sku、财务 G1 规则、竞品和场景 | [利润会计规则（待批准）](product/finance-rulebook.md) |
| `purchasing/`, `inventory/`, `catalog/` | 供应商/采购、实物入库、批次、Listing/SKU 的实现与历史合成证据 | [正式供应商退货 V120](purchasing/supplier-followup-return-traces-v120.md) |
| `operations/`, `analytics/` | 经营来源质量、异常工单、外部可核证来源所需运营证据 | [V89 经营来源质量](analytics/business-source-quality-v89.md) |
| `security/`, `integrations/` | Session/CSRF/tenant、Seller OAuth、SP-API/Ads 限流与访问边界 | [授权设计](security/seller-oauth.md) · [SP-API](integrations/amazon-sp-api.md) |
| `design/`, `ux/` | Next16 财务/订单交互与页面状态约束 | [菜单导航](ux/navigation.md) |
| `diagrams/`, `processes/` | Mermaid C4/业务流程、同步/费用/采购链路 | [图纸目录](diagrams/index.md) · [主业务流程](processes/core-flows.md) |
| `templates/` | 标准 ADR 模板；单文件目录仍有复用价值 | [ADR 模板](templates/adr-template.md) |
| `ops/` | 运维概要（区别于根 `ops/` 实际监控/发布工具） | [operations.md](ops/operations.md) |

不存在单纯为了“目录看起来少”而删除活动代码、测试、历史迁移、回退资产或可核查的版本证据的理由。详见根 [README 中文版](../README.zh-CN.md) 和 [英文版](../README.md) 的**仓库目录用途表**。

## 清理与删除结果（V231 / V233 / V234）

V234：移除 A3 工作流冗余历史注释、合并现行计划中的重复已完成版本叙述，执行步骤与原验收证据保留。V233：缩短 AGENTS、去除根 README 的历史流水账与 Demo 过期启动说明，纠正中文 README 现行评分。Git Tree 未发现完全相同的 Markdown 文件对；仅保留有独立用途的文件。详细目录职责以根 README 为准。

- **删除**：根目录大小写重复的 `Agent.md` 兼容别名。它没有独立规则；标准入口是 [AGENTS.md](../AGENTS.md)。历史仍可由 Git log 查看。
- **删除**：`purchasing/supplier-followup-return-trace-v120.md`（旧英文短稿还停留在“PR/CI 待验收”）。保留信息和范围更完整的 [`supplier-followup-return-traces-v120.md`](purchasing/supplier-followup-return-traces-v120.md)，不重复维护两份“V120”。
- **保留**：`docs/roadmap.md`、`roadmap/mvp-freeze-release-v108.md` 和所有带精确 SHA/A3 原始结果的 Vxxx/评分文档，作为**历史证据而非现行指令**。保留 `architecture/adrs/`、`templates/` 和单文档目录中有用途的文件。
- **不应出现**：`node_modules/`、`dist/`、`.next/`、`build/`、运行日志、单独 `pom.xml`、npm/yarn 第二份锁文件、重复 Agent 规则。这些无需手动清理 Git 中的 tracked 文件；当前 Git 树没有跟踪这种误提交的构建垃圾。

文档真实正确性由 `python3 scripts/check_docs_consistency_v109.py` 与 V231 已实现的删除文件/目录映射检查守护；[精确代码 SHA `9ff02147de17`](https://github.com/kunlong-luo/merchivra/commit/9ff02147de17996d28ec95875a4eaa6445e1c197) 的 [完整 A3 #45373462870（7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45373462870) 中真实输出 **DOC-196 26份入口/504条相对链接 PASS**。删掉文件后**不再重新创建同名占位符**；失效旧链接应更新到上述保留的正式入口。过时结论在 Git 历史中可溯源，不擅自把旧失败改写为成功。

**V231 证据与删除记录**：[浏览器原字节摘要核验及仓库文档/目录安全裁剪](finance/v231-local-byte-claim-check-and-repository-pruning.md)，[完整 A3 #45373462870（7/7）](https://github.com/kunlong-luo/merchivra/actions/runs/45373462870)，94 个前端测试文件/461 项、DOC-196 26 入口/504 链接均成功；与外部真实费用认证分离。

## 财务资料的分类与真实性边界

V230 的来源级清单把费用分为广告、FBA 仓储、FBM/头程货代、退货、关税、银行汇兑与其他经营费用；对每类要求**外部签发主体、原始字节与重算 SHA、真实收费/原币、归属守恒、独立审核与正式会计决定**。在没有合法取得的 Seller/Ads/银行/仓库原始证据之前，所有“本地审计完整”“本地金额与来源相似”均不是外部签发认证。

V231 可选择在**本地浏览器**对手工声明摘要重算用户自己持有文件的 SHA-256，不上传原件/文件名，不过账；即使匹配，也只能称 **LOCAL_BYTES_MATCH_USER_CLAIM_ONLY**，不能写成外部认证或真实净利润。该增量已由[自身精确代码 SHA `9ff02147de17`](https://github.com/kunlong-luo/merchivra/commit/9ff02147de17996d28ec95875a4eaa6445e1c197) 的[完整 A3 #45373462870（7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45373462870) 和[快速 CI #45373462897（SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45373462897) 验收，详见 [V231 专项](finance/v231-local-byte-claim-check-and-repository-pruning.md)。

**最新独立评分 [V231 76/100](governance/project-scorecard-v231-2026-11-24.md)**（V230 历史75分），唯一加分来自浏览器真实字节与手工声明 SHA 可操作核对，未获得任何真正外部签发方证明；尚未取得财务 G1/DEC 和外部真实成本时 `realizedNetProfit=null`，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) 保持 **NO-GO**。
