# Merchivra ERP（麦奇维拉）

**面向 Amazon Seller 的开源、自托管 ERP，以可追溯的全成本与利润核算为核心目标。**

> **当前版本基线：2026-11-22 / V195 会话体验、V194 财务证据。** 项目仍为 **pre-alpha**，只供本地/隔离环境开发测试。Java 25 / Spring Boot 4.1.2 / Gradle / PostgreSQL 18、React 19 / **Next.js 16.4.5** / Node 24、**pnpm 12.20.0（唯一前端包管理器）**。默认 Web 已是 Next.js；Vite 只用于受控回退与 GitHub Pages 虚构静态展示。
>
> **技术验收 ≠ 生产批准。** 最近一次独立 [V193 完整 A3 #45012495202](https://github.com/kunlong-luo/merchivra/actions/runs/45012495202) 针对精确 [`6008832`](https://github.com/kunlong-luo/merchivra/commit/600883236a3669f51b6703229447d93499b271cd) 成功；更新的 V195 [快速 CI #45016097852](https://github.com/kunlong-luo/merchivra/actions/runs/45016097852) 已通过，但**V194 仍需在含新代码的精确 SHA 上完整运行 PG18/A3**。真实 Seller/Ads OAuth、真实平台与银行核验均未授权；[生产门禁 #104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。

[English](README.md) · [当前状态](docs/current-status.md) · [现行开发计划](docs/roadmap/current-plan.md) · [文档中心](docs/README.md) · [工程规范](docs/governance/engineering-standards.md) · [部署指南](docs/deployment/local-spark-db.md) · [开发指南](docs/development.md) · [安全说明](SECURITY.md)

## 当前真正能做什么

已实现本地店铺/租户、SKU/订单、采购/FBA、库存批次/异常、会话及审计等业务链路，提供 Next.js 多工作台、Spring API、PostgreSQL 持久化与模拟接口。财务已实现 V64 冻结的**已知范围贡献**和 V91–V104、V162–V194 的人工成本存证、分摊守恒、结算身份、整期阻断、唯一归属与原币瀑布**只读预览**。

**尚未实现可信的全成本净利润结论**：独立外部 Amazon/Ads/银行真实性核验、退货 COGS 回转、跨币种 FX/税费政策、外仓实体签收/库存入账和财务 G1/DEC-06/07/08 审批仍不完整。人工 `APPROVED_UNVERIFIED` 不是已证实成本，`realizedNetProfit=null` 不能伪装为零或已确认利润。见 [事实基线](docs/current-status.md) 和 [V188 利润瀑布](docs/finance/v188-period-profit-cost-waterfall-preview.md)。

## 一、本地启动（内置 PostgreSQL 18）

需要 Docker Engine 与 Compose v2。从仓库根目录：

```bash
git clone https://github.com/kunlong-luo/merchivra.git
cd merchivra
cp .env.example .env
# 在 .env 填写 MERCHIVRA_DB_PASSWORD
# 并设置 MERCHIVRA_BOOTSTRAP_USERNAME 与至少 16 位的强密码
docker compose --env-file .env -f deploy/compose.yaml config -q
docker compose --env-file .env -f deploy/compose.yaml up -d --build
```

默认三项服务为 `postgres`、`server`、`web`（Next.js Node），浏览器访问 **http://127.0.0.1:3000**，API 健康检查 **http://127.0.0.1:8080/api/v1/ping**。所有宿主端口仅绑定回环地址；本地服务不是公网生产部署。

已有单独管理的 spark-db PostgreSQL 18，可选**只启动** Merchivra `server + web`，不触碰现有 `iot_farm` 数据库或数据卷。必须先按 [现有库的独立角色/数据库初始化步骤](docs/deployment/local-spark-db.md) 处理，然后运行：

```bash
docker compose --env-file .env -f deploy/compose.spark-db.yaml config -q
docker compose --env-file .env -f deploy/compose.spark-db.yaml up -d --build
```

不要同时叠加两种基础 Compose 文件，也不要执行 `docker compose down -v` 误删数据。

## 二、源码开发：只用 pnpm

需要 Java 25、Node 24、pnpm **12.20.0**、可访问的 PostgreSQL 18 与本地 Spring API。先让后端在 **8080** 运行，再启动 Next：

```bash
cd apps/web
corepack enable
pnpm install --frozen-lockfile
pnpm dev
```

- **`pnpm dev` / `pnpm build`**：默认 Next.js 16，开发端口 3000。
- **`pnpm api:check`、`pnpm typecheck`、`pnpm test`**：合同生成校验、类型检查和单测。
- **`pnpm dev:vite` / `pnpm build:vite`**：仅在明确回退或测试静态展示时使用；普通本地 Compose 不会自动启动 `vite-web`。
- 唯一前端锁文件为 `apps/web/pnpm-lock.yaml`，不得恢复第二份依赖锁。

完整环境变量、spark-db 低权限初始化、Redis 可选配置、备份与卸载见 [部署指南](docs/deployment/local-spark-db.md)，OpenAPI/Orval 协议约束见 [API 文档](docs/api/README.md)。

## 三、质量门禁与开发方向

日常修改直接提交 **`main`**，不为常规开发创建分支/PR；每次推送跑快速 CI。资金、库存、权限、Flyway 等高风险变更仍需专项测试，必要时重新运行精确 SHA 的完整 PG18/HTTP/Chromium/Compose A3。详见 [CI 策略](docs/testing/ci-policy.md)。最新优先级是独立费用真实性、全成本/退款 COGS、**真实可审计利润报表**和外仓可信证据；参见 [现行计划](docs/roadmap/current-plan.md)。

[公开 GitHub Pages](https://kunlong-luo.github.io/merchivra/) **只有虚构的静态演示**：无真实登录、数据库、Seller OAuth、银行或业务写入，不能作为线上 ERP。

**License**：见 [LICENSE](LICENSE)。
