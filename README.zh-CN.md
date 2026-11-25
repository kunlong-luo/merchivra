# Merchivra · 开源 Amazon Seller ERP

以**可审计的订单、SKU、期间全成本与真实净利润**为核心目标的自托管 ERP，目前属于 **pre-alpha**，仅可在本地/隔离测试环境使用，**尚未批准生产上线**。

**技术基线（2026-11-24）**：Java 25、Spring Boot 4.1.2、**Gradle**、PostgreSQL 18/Flyway；React 19、**Next.js 16.4.5 App Router**、Node 24、**pnpm 12.20.0（唯一前端包管理器，不用 npm）**。默认使用 Next.js；Vite/Nginx 仅供明确回退与 GitHub Pages **虚构展示**，不得误认为默认正式前端。

- 现行独立成熟度评分：**[V231 76/100](docs/governance/project-scorecard-v231-2026-11-24.md)**（历史 V230 [75/100](docs/governance/project-scorecard-v230-2026-11-24.md)）。该分数不是生产就绪度，也不是真实净利润已核证的比例。
- **真实净利润仍不能确认**：`realizedNetProfit=null`；真实 Amazon Seller/Ads OAuth **关闭**，真实银行、货代、关税、独立 WMS、退款实际 COGS 与 G1/DEC 财务口径未核证；生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。

[English](README.md) · [当前事实](docs/current-status.md) · [唯一现行 P0 计划](docs/roadmap/current-plan.md) · [文档中心](docs/README.md) · [开发指南](docs/development.md) · [工程规范](docs/governance/engineering-standards.md) · [安全](SECURITY.md)

## 当前工程状态

V237 完整隔离 A3 7/7 通过，实际 PostgreSQL18 Java Reports 服务回归未跳过，远程 Worker 为合成模拟；V238–V240 快速 CI 通过；V241 完整 A3 7/7 已通过；V242 完整 A3 7/7；V243 快 CI 通过但 PG18 步骤失败；V244 完整 A3 7/7 已通过；V245 快 CI 通过；V246 快 CI 通过；V247 快 CI 通过、A3 6/7（PG18 别名冲突）；V248 完整 A3 7/7；V249 快 CI 通过；V250 快 CI/A3 均未启动 job；V251 快 CI 成功、A3 shell 变量失败；V252 完整 A3 7/7（实际 PG18 组合及 Owner HTTP 空状态）；V253 完整 A3 7/7；V254 完整 A3 7/7；V255 完整 A3 7/7；V256 快 CI 通过、A3 6/7（PG18 变量冲突）；V257 快 CI 成功；V258 V120 修复及全订单原币退款成本候选汇总尚未核对 A3；V259 追加 V35 原币退款金额/商品覆盖门禁，CI/A3 待验收，未正式回转。见[当前事实](docs/current-status.md)。

## 一、仓库根目录：保留哪些，分别有什么用？

以下目录经盘点都包含实际可执行代码、测试/运维配置或审计材料，不要因文件名含旧 Vxxx 就误删。**只有确认无引用、无保留义务的冗余文件才删除。**

| 目录 | 实际作用、保留原因 | 是否可删除 |
| --- | --- | --- |
| `apps/server/` | Spring Boot API、领域/财务/库存服务、Session/CSRF、**Gradle Wrapper**、不可重写的 Flyway Vxxx 迁移、Java 测试 | **运行必需**；禁止删历史迁移，绝不换 Maven |
| `apps/web/` | 正式 **Next.js** App Router、React 工作台、Zod、TanStack、Orval 合同/生成客户端、Vitest、唯一 `pnpm-lock.yaml` | **运行必需**；其中 `index.html`、`vite.config.ts`、部分 Nginx/Dockerfile 是明确回退/静态模拟测试资产，保留但**默认不运行 Vite** |
| `deploy/` | PostgreSQL18 + Spring + Next 本地 Compose、独立 `spark-db` 方案、生产参考和隔离 TLS 验收叠加 | **部署/验证必需**；两套基础 Compose 不可合并启动，不误删数据卷 |
| `ops/` | 监控、发布/运维脚本与证据检查 | **运维必需**；不能因不是 Web 源码而删除 |
| `scripts/` | 数据库/HTTP/浏览器/文档/Orval 合同校验、受控合成 A3、只在临时环境执行的安全负例 | **验证必需**；严禁在真实客户库运行一次性破坏性测试 |
| `.github/` | GitHub Actions 快速 CI、完整 A3、Chromium、Pages、依赖管理 | **自动化必需**，并非无用分支残留 |
| `docs/` | 现行事实、唯一计划、规范、业务决策与 Vxxx 真实 CI/PG18 证据归档 | **审计必需**；按下表区别“现行”与“历史”，不是每篇历史稿都应当作开发指令 |

根目录的 `.env.example`、`.gitignore`、`.editorconfig` 是开发配置；**`AGENTS.md` 是唯一 Agent 规则**；`DESIGN.md` 为前端设计约束，`SECURITY.md` 为安全/报告渠道，`CONTRIBUTING.md` 为贡献规则，`LICENSE` 为授权条款。旧大小写重复别名 **`Agent.md` 已从 `main` 删除**，禁止再恢复第二份规范。

## 二、`docs/` 子目录都有什么用途？

| 文档目录 | 用途；当前入口与归档规则 |
| --- | --- |
| `docs/roadmap/` | **现行唯一计划**：[current-plan.md](docs/roadmap/current-plan.md)；V108 和历史 A3 计划为旧证据。[docs/roadmap.md](docs/roadmap.md) 是标明“历史归档”的旧版本，**不能作为现行计划** |
| `docs/governance/` | 文档控制、[工程规范](docs/governance/engineering-standards.md)、G1/DEC 签署状态、分版本评分与历史审计；现行评分见 [V231 76/100](docs/governance/project-scorecard-v231-2026-11-24.md) |
| `docs/finance/` | 费用/结算/归属/退款 COGS、外部证据限制与利润核算的 Vxxx 实际证明；[V230 分类费用缺口清单](docs/finance/v230-external-source-proof-dossier-and-owner-workflow.md) |
| `docs/testing/`、`docs/deployment/`、`docs/api/` | 精确 SHA 的 A3/CI、PG18 部署和备份安全、真实 Springdoc/Orval API 接入规则 |
| `docs/architecture/` | 架构、[图纸索引](docs/architecture/diagram-index.md)、ADR 与 [ADR 模板](docs/architecture/adrs/template.md) |
| `docs/requirements/`、`docs/product/` | 需求验收、[核心业务流程](docs/product/core-flows.md)、业务场景与尚待批准的财务政策 |
| `docs/purchasing/`、`docs/inventory/`、`docs/catalog/`、`docs/operations/`、`docs/analytics/` | 采购、供应商、FBA、货位、Listing、异常/经营质量的分领域技术、运维手册与历史证据 |
| `docs/security/`、`docs/integrations/` | 租户权限、Session、Amazon SP-API/Ads 安全与授权流程合同 |
| `docs/design/` | Next 页面设计、导航与页面状态参考 |

**本次清理原则**：真正重复、没有现行用途的文件直接删除；前向 Flyway 迁移、真实 CI 失败历史、版本评分、源账目证明、回退机制与尚未签署的 G1/DEC 文件保留。已删除早期 V120“PR 还在等 CI”的重复短稿，正式[供应商退货历史 V120 文档](docs/purchasing/supplier-followup-return-traces-v120.md) 保留。删除历史仍可从 Git commit 查看。完整文档入口/分类见 [docs/README.md](docs/README.md)，判定依据见 [文档控制规范](docs/governance/document-control.md)。

### 目录维护与去重

现保留上表 17 个文档领域目录。已移除的旧路径：`docs/ops/` → [运维手册](docs/operations/operations.md)、`docs/ux/` → [设计参考](docs/design/navigation-and-page-states.md)、`docs/diagrams/` → [架构图纸](docs/architecture/diagram-index.md)、`docs/processes/` → [产品流程](docs/product/core-flows.md)、`docs/templates/` → [ADR 模板](docs/architecture/adrs/template.md)。旧重复稿与原文可从 Git 恢复；根 `ops/` 为实际工具，仍保留。V240 将 V181/V181B 归为[结算身份契约](docs/finance/v181-immutable-settlement-line-identity.md)、TEST-001 并入 [CI 策略](docs/testing/ci-policy.md)、早期 API 选型并入[依赖复用契约](docs/architecture/dependency-adoption.md)，各保留文档内以精确 Git SHA 链接原文。统一删留依据为[文档控制](docs/governance/document-control.md)，CI 禁止恢复旧目录、禁止遗漏现有文档目录用途。

## 三、本地启动（包含 PostgreSQL 18）

需要 Docker Engine / Compose v2。在仓库根目录执行：

```bash
git clone https://github.com/kunlong-luo/merchivra.git
cd merchivra
cp .env.example .env
# 手工设置 MERCHIVRA_DB_PASSWORD 专有强密码
# 设置 MERCHIVRA_BOOTSTRAP_USERNAME 和不少于 16 位的独立管理员密码
docker compose --env-file .env -f deploy/compose.yaml config -q
docker compose --env-file .env -f deploy/compose.yaml up -d --build
```

本地 Next 页面：**http://127.0.0.1:3000**；Spring API 健康检查：**http://127.0.0.1:8080/api/v1/ping**。本地运行不代表生产上线。若已有独立 PostgreSQL18 的 `spark-db`，必须先按[单独角色/库初始化指南](docs/deployment/local-spark-db.md)处理，再**仅用** `deploy/compose.spark-db.yaml`；不要混用两套基础 Compose，也不要对要保留的数据执行 `down -v`。

## 四、代码开发、测试与安全边界

先运行 PostgreSQL18 和本地 Spring API（`127.0.0.1:8080`），再执行：

```bash
cd apps/web
corepack enable
pnpm install --frozen-lockfile
pnpm dev        # 正式 Next.js 16，不是 Vite
pnpm api:check  # Orval 合同一致性
pnpm typecheck
pnpm test
pnpm build
```

Java 测试为 `./apps/server/gradlew -p apps/server --no-daemon test`；文档和目录检查为 `python3 scripts/check_docs_consistency_v109.py`、`python3 scripts/check_ci_cadence_v108.py`。隔离 A3 中的 PostgreSQL 负例**只允许** GitHub 临时数据库，禁止在实际业务库执行。

普通开发**直接提交 `main`**，不为常规开发新建 PR/分支；资金/身份/数据库边界仍必须基于精确 SHA 的真实 PG18/HTTP/Chromium 等验证。合成 CI 全通过既不等于真实 Amazon/Ads/银行核证，也不能替代财务和生产签署。

[公开 GitHub Pages 演示](https://kunlong-luo.github.io/merchivra/)仅为虚构数据，不提供正式 ERP 登录、银行或真实成本入账。
