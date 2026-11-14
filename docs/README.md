# Merchivra ERP · 文档导航与事实基线

> **当前文档入口（2026-11-14 / V161 利润优先产品方向与 Next 统一前端；V160 完整 A3 7/7 基线）。** 本页用于区分已经实现的代码、模拟测试、尚未批准的设计方案及真正的生产发布条件。早期 G1/G2 评审文件和 V1–V92 阶段记录仍保留原始历史意义，不再作为当前工程状态的依据。

## 从这里开始

| 想了解什么 | 当前权威文档 | 注意 |
| --- | --- | --- |
| 最新 P0 优先级、MVP 冻结与上线顺序 | [V108 MVP 收尾与集中验收计划](roadmap/mvp-freeze-release-v108.md) | **现行执行原则 + 顶部 V161 利润优先产品主线**；覆盖历史 roadmap 的“当前/下一步” |
| V163 手工成本来源、双人复核与审计 | [V163 成本凭证入库及复核](finance/cost-source-review-v163.md) | Flyway V91、OWNER Session/CSRF、唯一凭证键和不可变来源；审核仍未验证真实发票/自动入利润 |
| V162 已实现的额外成本核算预览引擎 | [V162 全成本覆盖与金额防重](finance/full-cost-coverage-v162.md) | 8 类费用状态、原币和来源去重、已知费用扣除预览；**没有**真实来源自动归集/最终净利润/前端财务接入 |
| **利润优先、全成本核算及 Next 单一前端目标** | [V161 利润优先产品计划](product/profit-first-next-primary-v161.md) · [财务口径 G1 候选](product/finance-rulebook.md) | 现有 V33/V61/V64 是贡献预览，不是已经完成全成本净利润；目标正式 ERP 采用 Next，Vite 暂作回滚 |
| 采购订单 V110 服务端分页实施与证据边界 | [订单游标/筛选契约](purchasing/purchase-order-pages-v110.md) | V110–V144 已完成主要采购、供应商、仓储全库分页；[#62](https://github.com/kunlong-luo/merchivra/issues/62) 已关闭 |
| 成熟开源 ERP 源码对标与复用决策 | [BENCH-001 · Wimoor / ERPNext / metasfresh / Amazon SDK](architecture/open-source-erp-benchmark-2026-11-08.md) | 以当前后端查询与权限事实作差距判断；不复制 GPL 源码、不盲目扩充 P0 |
| 各模块代码/真实来源/验收状态 | [RTM-002 V148 代码与验收追踪](requirements/implementation-acceptance-rtm-v148.md) · [V109 文档审计](governance/docs-audit-v109.md) | 25 条 P0 分离代码/测试/真实来源/签署；V86 仅历史快照 |
| 当前架构、模块与依赖 | [总体架构](architecture/overview.md) · [V99–V100 Next 迁移门槛](architecture/next16-ab-migration-v99.md) | 正式目标是 **Next 16 唯一 ERP 前端**；实际目标切换前仍保留 Vite/Nginx 旧默认部署和回滚 |
| 本地运行、开发和环境变量 | [开发指南](development.md) · [Docker/PG18 部署](deployment/local-spark-db.md) | Java 25 / Gradle / PostgreSQL **18** / React 19 |
| FBA 采购来源 V147–V148 | [V147 真实分页及范围](inventory/fba-purchase-pagination-v147.md) · [V75 本地证据](inventory/fba-purchase-link-v75.md) | [V148 A3 #43356548084](https://github.com/kunlong-luo/merchivra/actions/runs/43356548084) 二次尝试 7/7；[#108](https://github.com/kunlong-luo/merchivra/issues/108) 合成技术关闭，真实 Amazon 来源未验收 |
| API 代码生成与原始合同 | [API 文档入口](api/README.md) · [Orval/真实 Controller](architecture/mature-tool-first-api-client-2026-11-01.md) | 设计版 OpenAPI JSON **不是**当前全量后端契约 |
| GitHub Actions 测试与质量门槛 | [V108 CI 策略](testing/ci-policy.md) · [发布安全/恢复清单](testing/release-readiness-v68.md) | 普通 PR 快速检查；[完整里程碑 workflow](https://github.com/kunlong-luo/merchivra/actions/workflows/milestone-validation.yml) 手动集中执行 |
| 本地权限、凭据与生产前安全 | [Security Policy](../SECURITY.md) · [Seller OAuth](security/seller-oauth.md) · [安全门槛](testing/release-readiness-v68.md) | Seller/Ads 真实授权与远程写入默认关闭 |
| 目标环境 Next HTTPS 匿名入口预检 | [V151–V159 目标 TLS/Ingress 只读核查](architecture/next-target-ingress-v151.md) | **仅授权运维手工调用**；CI 不访问目标，退出 3 也不代表可部署 |
| 当前 G1/G2/G3 缺项和分工 | [V158 生产证据清单](operations/v158-production-evidence-worklist.md) · [V143 生产预检](operations/v143-production-release-preflight.md) | 仅 V143 脱敏结果、始终 NO-GO；真实证据和授权签署须由组织人员提供 |
| 目标镜像身份与发布候选核对 | [V160 运行容器 OCI revision 和不可变镜像 ID 核对](operations/v160-target-image-evidence.md) | 三个自构建镜像只读目标检查；需独立可信的预期 ID 与签名/制品审查，永远不能自动放行 |
| 本地可复现的合成端到端 | [Compose Mock 全链路](testing/mock-full-chain.md) · [Chromium UI 冒烟](testing/browser-ui.md) | 合成数据不能替代 Amazon/Ads/银行真数据联调 |

## 当前已实现与仍需验证的边界

**已经有可运行的工程与业务代码**，并非只有设计文档：Spring Security Session/CSRF 与本地 Owner/员工权限、Seller/Marketplace 未授权配置、商品/SKU/Listing、订单与 FBA 观测、采购/供应商/仓储/质检、财务来源与成本报表、运营异常和部分同步 Worker。V99–V146 继续完成 Next App Router 工作台、Base UI/TanStack、全库分页、FBA 合同、真实隔离 PG18 验证与本地 HTTPS/同会话回滚，但正式公网 Next 切流仍未批准。参见 [功能审计历史](testing/core-coverage-audit-v86.md) 和 [P0 硬上限证据](testing/p0-purchasing-read-cap-audit-v108.md)。

**尚未生产放行**（V146 本地 HTTPS 回滚与隔离 PITR/告警已验证，但目标环境仍未验收）：真实 Seller OAuth/SP-API 与独立 Ads OAuth 的本地联调、银行/外部物流来源、生产级密钥与最小权限、正式 HTTPS、恢复演练与 RPO/RTO、负载/异常恢复、财务口径及 G1/G2/G3 人工签署。不能以 GitHub Actions 绿灯宣称这些项目完成。[当前缺口和准入顺序](roadmap/mvp-freeze-release-v108.md)。

**部署差别**：默认私有 ERP 是 React/Vite 构建 + Nginx、Spring Boot 和 PostgreSQL 18；Next 16 技术跟踪 [Issue #63](https://github.com/kunlong-luo/merchivra/issues/63) 已由维护者关闭；**这不代表生产流量切换、目标真实 TLS 或发布审批已完成**；唯一生产准入仍由 [#104](https://github.com/kunlong-luo/merchivra/issues/104) 跟踪。[GitHub Pages](https://kunlong-luo.github.io/merchivra/) 只托管不含真实账号/数据库/业务写入的合成数据展示页，并不是 SaaS 或可登录 ERP。

**证据分级**：设计提案 → 代码实现 → 快速 CI → 隔离 PG18/Compose/Chromium 模拟验收 → 用户本地真实接口联调 → 业务、安全、生产正式批准。任何较早级别都不能自动升级为较晚级别。最新完整合成里程碑：[V160 main SHA a960f7cf93c3dfc8a0f8ab96b534fd0a7252e49a · A3 run_attempt=1 7/7 success](https://github.com/kunlong-luo/merchivra/actions/runs/43428867257)（严格 TLS/重定向/后端身份及 HTTPS 回切；仅该 SHA 的隔离环境；V148 首轮 HTTPS canary 失败根因仍由 [#115](https://github.com/kunlong-luo/merchivra/issues/115) 跟踪）。FBA 来源分页 [#108](https://github.com/kunlong-luo/merchivra/issues/108) 已按技术范围关闭；[#63](https://github.com/kunlong-luo/merchivra/issues/63) 已按维护者技术跟踪决定关闭，但真实目标切流仍缺证据；当前权威生产签署门禁仍是 [#104](https://github.com/kunlong-luo/merchivra/issues/104)。

## 设计与决策文件（作为评审资料，未被自动签署）

| 主题 | 设计和审查 |
| --- | --- |
| 产品范围/FR/NFR | [PRD](requirements/mvp.md) · [功能需求](requirements/functional.md) · [非功能需求](requirements/nonfunctional.md) |
| 业务场景和规则 | [G1 评审包](product/g1-review-package.md) · [业务案例](product/business-scenarios.md) · [财务规则候选](product/finance-rulebook.md) |
| 概念系统/数据 | [C4 总览](architecture/overview.md) · [概念 ERD](architecture/data-model.md) · [来源对账](architecture/source-reconciliation.md) · [事务一致性](architecture/consistency-boundaries.md) |
| 平台外部契约 | [SP-API 设计](integrations/amazon-sp-api.md) · [操作矩阵](integrations/sp-api-operation-matrix.md) · [数据字段规则](integrations/amazon-data-contract.md) |
| 认证与租户设计 | [Session 安全](security/local-auth.md) · [租户权限执行候选](security/tenant-enforcement.md) · [G2 OpenAPI 设计草案](api/README.md) |
| 验收与签署 | [原需求追踪矩阵](requirements/traceability.md) · [G1 手工验收](requirements/g1-business-acceptance.md) · [G2 架构验收](requirements/g2-architecture-acceptance.md) · [决策登记](governance/decision-register.md) |
| 历史评审/原型 | [评审归档](governance/review-report-2026-10-30.md) · [图纸](diagrams/index.md) · [设计规范](../DESIGN.md) |

G1/G2/G3 仍属于待批准的业务/架构/发布门槛。设计文件中“未实现”可能仅指该设计**在写作时的状态**；它不应覆盖 V108 的实际代码和 [GitHub Actions](https://github.com/kunlong-luo/merchivra/actions) 结果。也不能因代码已存在就自动批准设计里的税费、币种、库存、数据源或权限决策。

## 历史资料与文档维护

- [阶段开发路线历史](roadmap.md) 以 V108 计划顶部的 V161 状态补丁为最新入口；V92 及更早“当前”的说法属于当时记录。
- [早期架构评审报告](governance/g2-review-2026-10-30.md)、[文档状态管理](governance/document-control.md)、[风险登记](governance/risk-register.md) 作为决策背景保留。
- 历史版本、测试运行 URL 和旧技术栈应保留“当时”标识，**不得**把已淘汰的 Vue、Maven、PostgreSQL 17 或无 CI/E2E 当成最新技术决策。
- 修改部署、安全、授权、数据模型或 P0 接口时，应同步 [Agent 规则](../AGENTS.md)、本页、[开发指南](development.md) 和 [现行计划](roadmap/mvp-freeze-release-v108.md)；可运行 `python3 scripts/check_docs_consistency_v109.py` 验证核心文档本地链接与权威引用。
