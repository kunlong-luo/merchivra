# Merchivra 开发指南（当前 · V191）

> **2026-11-21 的当前规范**：Java 25 / Spring Boot 4.1.2 / Gradle Wrapper / PostgreSQL 18；React 19 + **Next.js 16.4.5 App Router**；Node 24 + **pnpm 12.20.0（只用这一种前端包管理器）**。默认本地 `web` 为 Next Node，不是 Vite SPA。项目处于 pre-alpha、生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) NO-GO，Seller/Ads OAuth 和真实写入禁止启用。完整状态见 [当前事实基线](current-status.md)。

## 1. 安装和版本约束

- JDK **25**，Spring Boot **4.1.2**，使用 `apps/server/gradlew`（不要新建 Maven/pom）。
- Node **24.x**（`apps/web/.nvmrc`），通过 Corepack 提供 `package.json` 固定的 pnpm **12.20.0**。
- PostgreSQL **18**；使用 [内置 Compose](deployment/local-spark-db.md) 或经专有角色隔离的 spark-db；Redis 8 是可选项。
- Web 依赖只从 `apps/web/pnpm-lock.yaml` 进行冻结安装。Next 16 是 App Router，Vite 是回退/Pages 构建；前端请求通过 Next `/api` rewrite 访问 Spring Boot。

## 2. 最简单的三容器本地环境

```bash
cp .env.example .env
# 本机编辑 .env，填数据库专用强密码和首次管理员账号/至少 16 位密码
docker compose --env-file .env -f deploy/compose.yaml config -q
docker compose --env-file .env -f deploy/compose.yaml up -d --build
docker compose --env-file .env -f deploy/compose.yaml ps
```

默认只启动 `postgres`、`server`、`web`。访问 `http://127.0.0.1:3000`（Next），后端 `http://127.0.0.1:8080/api/v1/ping`。真实 OAuth/SP-API 不启用；演示配置只可用于合成场景。退出可执行 `docker compose --env-file .env -f deploy/compose.yaml down`，**不要加 `-v`**。若需要复用独立 spark-db，必须遵循[专用角色和数据库初始化](deployment/local-spark-db.md)，基础 Compose 文件不可合并使用。

## 3. 分别启动开发进程

首先为 PostgreSQL 18 中的 `merchivra` 数据库创建独立账号（参照 [部署指南](deployment/local-spark-db.md)）；宿主机上启动 Spring：

```bash
export DB_HOST=127.0.0.1 DB_PORT=5432
export DB_NAME=merchivra DB_USER=merchivra
# 使用本地安全方式注入 DB_PASSWORD；不得提交真实凭据
export MERCHIVRA_BOOTSTRAP_USERNAME=your_admin
# 使用本地安全方式注入至少 16 位的 MERCHIVRA_BOOTSTRAP_PASSWORD
export MERCHIVRA_DEMO_ENABLED=false
./apps/server/gradlew -p apps/server --no-daemon bootRun
```

另一个终端：

```bash
cd apps/web
nvm use
corepack enable
pnpm install --frozen-lockfile
pnpm dev
```

**Next.js 在 127.0.0.1:3000**，本地后端在 **127.0.0.1:8080**；默认 `next.config.ts` 将 `/api/**` 同源代理至后端。8080 不可用时前端不会凭空提供真实业务数据。只有显式执行 `pnpm dev:vite` 才会用 Vite 开发回退（默认端口 5173）。

## 4. 常用命令

| 任务 | 命令 |
| --- | --- |
| Spring 单元测试 | `./apps/server/gradlew -p apps/server --no-daemon test` |
| 前端冻结安装 | `cd apps/web && pnpm install --frozen-lockfile` |
| Next 默认开发/构建 | `pnpm dev` / `pnpm build` |
| 类型/单元测试 | `pnpm typecheck` / `pnpm test` |
| API 生成合同与漂移核对 | `pnpm api:check` |
| Vite 受控回退构建 | `pnpm build:vite` |
| 文档与开发节奏检查 | `python3 scripts/check_docs_consistency_v109.py`、`python3 scripts/check_ci_cadence_v108.py` |
| Next/Docker 静态安全检查 | `python3 scripts/check_next_runtime_v122.py` |
| 完整 PG18/Chromium/Compose A3 | [GitHub Actions · MVP milestone full validation](https://github.com/kunlong-luo/merchivra/actions/workflows/milestone-validation.yml)（针对精确 `main` SHA） |

`api:check` 使用已审查 OpenAPI 契约和 Orval，不能将旧 G2 草案直接当生产 SDK。接口变更按 [API 合同](api/README.md) 完成 Springdoc 真实导出、规范化、Orval 生成、源码审计及漂移回归；业务服务必须保留租户/Marketplace、Owner、CSRF、金额原币与版本约束。

## 5. 测试与环境边界

- **日常**：直接提交 `main`，每次推送快速 CI（Java、pnpm、Next/Vite 构建、Orval、测试及 Compose 静态安全门禁）。不因免 PR 而不测试。
- **有资金/库存/权限/迁移风险**：同批次覆盖独立 Owner、错误租户、重复归属、无证据和原币精度负例；跨进程/PG18 或回退变更按需跑完整 A3，引用精确 SHA。
- **完全禁止**：真实 Seller/Ads 授权、写入、真实银行/买家凭据进入源码/CI/Pages；把 `UNKNOWN` 当 0、将合成费用写成已核验净利润、声称 WMS 签名等于物理实收。
- **故障排查**：确认 3000 是 `pnpm dev` 或 Compose `web` 的 Next 进程，8080 是 Spring；旧 Vite 5173 进程和旧容器可造成“仍在用 Vite”的错觉。任何数据初始化/回滚操作先看 [部署卸载安全说明](deployment/local-spark-db.md)。

产品/财务现行任务见 [P0 计划](roadmap/current-plan.md)，技术架构见 [ARCH-001](architecture/overview.md)，合成浏览器/PG18/恢复覆盖见 [CI 策略](testing/ci-policy.md)。历史 V6–V145 同步、采购、OpenAPI 试验说明继续保存在各自版本文档，但**不再是当前开发入口**。
