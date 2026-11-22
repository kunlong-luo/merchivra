# Merchivra 文档中心

> **现行入口 · 2026-11-22 / V193。** 这里只索引**当前执行规范**和有证据的业务说明。V1–V190 的版本文档、早期设计草案和当时的测试结论保留历史原貌，**不能因为标题含“当前/最新”就优先于本页**。当前正式本地前端为 Next.js 16、唯一前端包管理器 pnpm 12.20.0；生产 #104 仍 NO-GO。

## 先读这四份

| 目的 | 文档 |
| --- | --- |
| **到底实现了什么、哪些仍未证实** | [当前技术与业务事实基线](current-status.md) |
| **现在应该开发什么** | [现行 P0 执行计划：利润与财务证据链优先](roadmap/current-plan.md) |
| **本地如何启动及开发** | [开发指南](development.md) · [两种 Compose 部署/卸载](deployment/local-spark-db.md) |
| **何时测试、什么不能上线** | [分层 CI / 完整 A3](testing/ci-policy.md) · [安全与发布门禁](testing/release-readiness-v68.md) |

## 按业务领域阅读

| 领域 | 入口 | 状态解释 |
| --- | --- | --- |
| 架构/技术选择 | [系统架构](architecture/overview.md) · [ADR](architecture/adrs/0001-modular-monolith.md) · [OpenAPI/Orval](api/README.md) | Java25 / Spring Boot4.1.2 / Gradle / PG18 / React19 / Next16 / pnpm；不使用 Vue/Maven |
| 产品与财务口径 | [利润优先方向 V161](product/profit-first-next-primary-v161.md) · [G1 财务规则](product/finance-rulebook.md) | 产品方向成立；G1/DEC-06/07/08 不是已签署 |
| 全成本与人工来源 | [V162 成本覆盖](finance/full-cost-coverage-v162.md) · [V163 手工来源](finance/cost-source-review-v163.md) · [V164 金额守恒](finance/cost-allocation-v164.md) | 技术能力/人工元数据核查，不等于真实可入账费用 |
| 结算与真实性隔离 | [V181 原始身份快照](finance/v181-immutable-settlement-line-identity.md) · [V182 来源归属判定](finance/v182-settlement-attribution-evidence.md) · [V183 整期阻断](finance/v183-full-period-profit-evidence-gates.md) | 外部报告独立来源真实性仍待核查 |
| 期间利润与防重 | [V185 跨月预览](finance/v185-original-currency-period-cost-preview.md) · [V186 单一来源归属](finance/v186-single-cost-source-ownership-bridge.md) · [V187 跨来源风险](finance/v187-full-period-cross-source-risk.md) · [V188 原币瀑布](finance/v188-period-profit-cost-waterfall-preview.md) · [V193 冻结证据不可变性](finance/v193-frozen-profit-snapshot-database-guards.md) | **敏感度计算 ≠ 已认证真实净利润** |
| 退货与外仓 WMS | [退货成本准备度](finance/refund-cost-readiness-v170.md) · [外仓运营主体信任合同](finance/wms-operator-trust-contract-v175-b.md) | B1 技术基础已有，B2/B3 真实外部验证和库存/COGS 过账未完成 |
| 采购与库存 | [采购入口](purchasing/local-purchase-workflow.md) · [库存与批次审计](inventory/fba-purchase-link-v75.md) | 本地持久业务与合成测试，不意味着真实 Amazon FBA 签收 |
| 安全/集成 | [安全政策](../SECURITY.md) · [Seller OAuth 代码边界](security/seller-oauth.md) · [Amazon Gateway](integrations/amazon-sp-api.md) | 真实 Seller/Ads OAuth 与外部 API 均关闭 |
| UI 与设计 | [设计系统](../DESIGN.md) · [Next.js 迁移技术记录](architecture/next-node-cutover-v122.md) | Next 已是**本地**默认；真实生产 TLS/切流仍 NO-GO |

## 如何判断文档的有效性

1. **Current / 现行**：本页、[current-status.md](current-status.md)、[current-plan.md](roadmap/current-plan.md)、开发/部署/CI/安全文档。更新当前版本时同步修改，不新增相互冲突的“最新 Vxxx”首页。
2. **Specification / 待批准**：G1/G2、需求与财务规则中的候选方案。代码存在、CI 通过或草案归档**不会**使其自动签署。
3. **Evidence / 历史验收**：每篇 Vxxx 业务记录只说明其注明的 SHA/Actions/合成数据。注意失败与复测，不能推断今天的 HEAD 已跑过相同回归。
4. **Archive / 历史计划**：[V108 MVP 计划](roadmap/mvp-freeze-release-v108.md)、[PLAN-001 长路线图](roadmap.md)、[V148 RTM 入口快照](requirements/implementation-acceptance-rtm-v148.md)、[V109 文档审计](governance/docs-audit-v109.md)。保留可追溯性，不再作为当前待办清单。

## 文档修改规则

日常改动直接在 `main`，不为例行功能/文档创建分支或 PR。真实事实须指向代码路径、精确提交、CI 运行或尚未签署的 G1/G2/G3 记录。财务来源、币种、证据状态和 `realizedNetProfit=null` 不可被文案“升级”。本地检查：

```bash
python3 scripts/check_docs_consistency_v109.py
python3 scripts/check_ci_cadence_v108.py
(cd apps/web && pnpm install --frozen-lockfile && pnpm api:check && pnpm typecheck && pnpm test && pnpm build)
```

文档治理记录见 [V191 文档清理说明](governance/docs-audit-v191.md)。**静态 GitHub Pages 只显示虚构数据，不是带登录的线上 ERP。**
