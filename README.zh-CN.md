# Merchivra ERP（麦奇维拉）

> **V108 现行开发与上线计划：** 项目转入 **剩余 P0 功能收尾 → MVP 冻结 → 一键集中回归 → 维护者本地真实 Seller/Ads 联调 → Beta/正式生产验收**。常规 PR 只运行快速 CI；完整 PG18/Chromium/Compose 验收在里程碑集中触发，关键安全/金额/库存变更仍需即时检查。详见 [V108 执行计划](docs/roadmap/mvp-freeze-release-v108.md) 与 [分层测试政策](docs/testing/ci-policy.md)。GitHub Pages 仍仅为虚构数据只读展示。**现阶段不得向公网开放真实 ERP。**

> **Next.js 16 迁移预览（V99）：** 已开始直接实现 Next.js App Router，复用现有会话保护的真实 ERP 页面；现阶段还**没有**替换线上 Vite/Nginx。可在 `apps/web` 中运行 `npm ci`、`npm run build:next`、`npm run dev:next`（端口 3001，须启动本地 Spring Boot 8080 后端）。[迁移验收门槛](docs/architecture/next16-ab-migration-v99.md)。GitHub Pages 仍只提供虚构数据静态演示。
\n> **GitHub Pages 公开只读演示：** [查看虚构数据的静态 React 界面](https://kunlong-luo.github.io/merchivra/)（需要仓库先启用 GitHub Pages）。**它不是可登录的 ERP 服务**：没有数据库、真实 Amazon 授权或采购/财务写入。[部署与首次启用说明](docs/deployment/github-pages-public-demo-v98.md)。\n
**开源、自托管的 Amazon Seller ERP。** 采用 Java 25 / Spring Boot 4.1、React 19 / TypeScript、PostgreSQL 18，优先把核心业务代码、模拟工作流与本地部署打通；真实 Amazon 授权留给使用者在功能开发完成后本地进行。

[English](README.md) · [完整部署与卸载指南](docs/deployment/local-spark-db.md) · [架构设计](docs/architecture/overview.md) · [功能路线图](docs/roadmap.md) · [贡献指南](CONTRIBUTING.md) · [安全说明](SECURITY.md)

> **当前为 pre-alpha，不适合直接对公网开放。** React 前端可构建、可登录并操作已经实现的本地业务模块；真实 Amazon OAuth/SP-API、外部供应商/承运商及生产安全尚未全链路验收。不要把 Mock 演示数据当成真实店铺交易数据。

## 一、Merchivra 需要几个服务？

默认运行 **三个服务**：

| 服务 | 技术 | 职责 | 地址 |
| --- | --- | --- | --- |
| `web` 前端 | React 19 + Vite 构建、Nginx | 工作台和 `/api` 反向代理 | http://127.0.0.1:3000 |
| `server` 后端 | Java 25 + Spring Boot 4.1.2 | Session/CSRF、业务 API、Flyway | http://127.0.0.1:8080 |
| 数据库 | PostgreSQL **18** | 商品、采购、库存、订单、财务来源、审计和凭据原件 | 内置 `postgres` 或你的 `spark-postgres` |

**Redis 8 已接入（默认关闭）。** 可用于共享 Spring Security Session/CSRF 和跨实例 Amazon SP-API 原子限流；单实例开发仍可不启动 Redis。复用 `spark-db` 时使用已有的 `spark-redis`，不会增加 Merchivra 容器、修改 `iot_farm` 或删除其他项目的 Key。你的 Redis 当前 `allkeys-lru` 不适合直接承担生产级 Session/限流，需要独立实例或评审无淘汰策略。详见 [Redis 8 部署与安全指南](docs/deployment/redis-session-spapi-v53.md)。

<details>
<summary>当前主要技术选型</summary>

- 后端：Java 25、Spring Boot 4.1.2、Spring Security 7、Gradle 9.2.1、Flyway、官方 Amazon SP-API Java SDK（真实出站默认关闭）。
- 前端：React 19、TypeScript 6、Vite 8、Node.js 24、TanStack React Query、Zod、Vitest。**已经完全弃用 Vue。**
- 数据：PostgreSQL 18，支持自建独立数据库；Docker Compose 可选择内置 PostgreSQL 或复用现有 PG18 服务。
- 架构：模块化单体，业务数据有来源标记，关键仓储写入使用事务、证据、审计与操作人复核。

</details>

## 二、快速启动方案 A：自带数据库（全部三个容器）

要求 Docker + Compose v2，无须预先安装 Java、Node 或 PostgreSQL：

```bash
git clone https://github.com/kunlong-luo/merchivra.git
cd merchivra
cp .env.example .env
# 编辑 .env：设置强随机 MERCHIVRA_DB_PASSWORD
# 设置 MERCHIVRA_BOOTSTRAP_USERNAME 与至少 16 位的 MERCHIVRA_BOOTSTRAP_PASSWORD

docker compose --env-file .env -f deploy/compose.yaml config -q
docker compose --env-file .env -f deploy/compose.yaml up -d --build
docker compose --env-file .env -f deploy/compose.yaml ps
```

打开 **http://127.0.0.1:3000** 登录。默认仅启动 `postgres` + `server` + `web`；`MERCHIVRA_DEMO_ENABLED=true` 仅在此内置本地 Compose 中启用虚构演示。后端首次启动自动运行 Flyway，数据库卷持久化。

如需在本地验证**可选 Redis 8**：`docker compose --env-file .env -f deploy/compose.yaml --profile optional-cache up -d`。

## 三、快速启动方案 B：复用你已有的 spark-db（推荐）

你的现有数据库服务是：

- 容器：**`spark-postgres`**；数据库引擎：**PostgreSQL 18**（PostGIS 3.6）。
- 外部 Docker 网络：**`spark-iot-net`**；容器间使用 **`spark-postgres:5432`**。
- 当前系统数据库：`iot_farm`。**Merchivra 只创建和使用单独的 `merchivra` 数据库，不修改 `iot_farm`。**

**步骤 1：** 启动已有的 spark-db 并确认网络可用：

```bash
# 在 spark-db 原有工作目录
docker compose up -d postgres
# 可在任意目录检查
docker network inspect spark-iot-net
docker exec spark-postgres psql -U root -d postgres -Atc 'SHOW server_version;'
```

**步骤 2：** 只在新的、尚不存在 `merchivra` 角色及数据库的受控 PostgreSQL 18 集群上，手工执行**一次性纯 SQL 初始化**。已存在其中任一对象时停止并人工检查，不允许重跑覆盖：

```bash
# 在仓库根目录执行。先确认 PostgreSQL 管理员连接的是正确的 postgres 维护库：
docker exec spark-postgres psql -X -U root -d postgres \
  -c "SELECT current_database(),current_user,current_setting('server_version');"
docker exec spark-postgres psql -X -U root -d postgres \
  -c "SELECT rolname,rolcanlogin FROM pg_roles WHERE rolname='merchivra';"
docker exec spark-postgres psql -X -U root -d postgres \
  -c "SELECT datname FROM pg_database WHERE datname='merchivra';"

# 只有角色与库都不存在，才由授权管理员显式手动执行；不得添加 -1
docker exec -i spark-postgres psql -X -v ON_ERROR_STOP=1 \
  -U root -d postgres < apps/server/scripts/provision-merchivra-database.sql
```

脚本先创建 `merchivra NOLOGIN`，再创建独立数据库并撤销数据库级 PUBLIC 权限；**不设置密码、不启用 LOGIN、不碰 `iot_farm`，也不会被 Flyway、CI、Docker 或应用自动执行**。

然后管理员单独通过安全 GUI 设置专用 SCRAM 密码；或在互动 `psql` 中检查 `SHOW password_encryption;` 后执行 `\password merchivra`。**`\password` 只能在 psql 交互提示符中执行，不是 SQL 编辑器语句。** 密码已设置后，再单独运行 `ALTER ROLE merchivra LOGIN;`。从宿主机通过 `psql -W` 测试 TCP 连接后，再配置 `.env` 或 IntelliJ。详见 [安全初始化与重试说明](docs/deployment/local-spark-db.md#b2-只在-spark-postgres-内初始化-merchivra-专用角色与数据库一次性)。

> 如果角色或数据库已存在，跳过初始化、先核对现状；密码不得写入 Git、SQL 文件或命令行。**Compose 内置数据库模式不需要也不得运行此脚本。**

**步骤 3：** 在 Merchivra 仓库根目录准备环境并只启动前端、后端：

```bash
cp .env.example .env
# 将以下参数写入 .env：
# MERCHIVRA_DB_NAME=merchivra
# MERCHIVRA_DB_USER=merchivra
# MERCHIVRA_DB_PASSWORD=<步骤 2 中单独设置的密码>
# MERCHIVRA_BOOTSTRAP_USERNAME=<你设置的管理员用户名>
# MERCHIVRA_BOOTSTRAP_PASSWORD=<至少 16 位的强密码>
# MERCHIVRA_DEMO_ENABLED=false

docker compose --env-file .env -f deploy/compose.spark-db.yaml config -q
docker compose --env-file .env -f deploy/compose.spark-db.yaml up -d --build
docker compose --env-file .env -f deploy/compose.spark-db.yaml ps
```

**这一方案只新增两个 Merchivra 容器：`server` 和 `web`。第三层数据库复用 `spark-postgres`。** 不启动新 PostgreSQL，不占用你已有的 5432 端口，不修改其他 spark-db 数据卷；`spark-redis`、InfluxDB 等保持由原项目管理。可选择把 Redis 8 接入共享 Session 与 API 限流（详见[启用指南](docs/deployment/redis-session-spapi-v53.md)），不需要新 Redis 容器。

默认前端仍为 http://127.0.0.1:3000，API 为 http://127.0.0.1:8080。若端口冲突，可在 `.env` 设置 `MERCHIVRA_WEB_PORT` / `MERCHIVRA_API_PORT`。

[查看完整 spark-db 适配说明、备份、故障排查与网络示意](docs/deployment/local-spark-db.md)。

## 四、源码本地开发（React 热更新 + Spring Boot）

前提：**Java 25、Gradle 9.2.1（项目内 Gradle Wrapper）、Node.js 24.11+** 和运行中的 PostgreSQL 18。可复用上述 `spark-postgres`，它已经将 PG 端口映射到本机 `127.0.0.1:5432`。

终端 1：

```bash
export DB_HOST=127.0.0.1 DB_PORT=5432 DB_NAME=merchivra DB_USER=merchivra
export DB_PASSWORD='your-dedicated-merchivra-password'
export MERCHIVRA_BOOTSTRAP_USERNAME=your_admin
export MERCHIVRA_BOOTSTRAP_PASSWORD='your-unique-16-plus-character-password'
(cd apps/server && ./gradlew bootRun)
```

终端 2：

```bash
cd apps/web
npm ci
npm run dev
```

开发页面通常是 http://127.0.0.1:5173；Vite 自动代理 `/api` 至 `127.0.0.1:8080`。**注意**：本机运行 Java 连接 `127.0.0.1:5432`，Docker 内运行 Java 连接 `spark-postgres:5432`。

## 五、部署、升级、停止和卸载

两种 Compose 模式**不要组合使用**：`deploy/compose.yaml` 是自带数据库的三容器部署；`deploy/compose.spark-db.yaml` 是复用数据库的两容器部署。现有 `deploy/compose.production.yaml` 只提供生产环境变量覆盖层，不配置 TLS 或安全合规，不能直接当成公网部署方案。

### 自带数据库方案 A

```bash
# 停止，保留数据
docker compose --env-file .env -f deploy/compose.yaml stop
# 更新/重建，保留数据库卷
docker compose --env-file .env -f deploy/compose.yaml up -d --build
# 卸载容器与项目网络，保留数据库数据
docker compose --env-file .env -f deploy/compose.yaml down --remove-orphans
```

只有确认数据不要了且**已有可恢复备份**时，才考虑 `docker compose --env-file .env -f deploy/compose.yaml down -v`；该命令会永久删除本项目的内置 PostgreSQL 数据卷。

### 复用 spark-db 方案 B

```bash
# 停止前后端，保留 spark-db 所有服务与数据
docker compose --env-file .env -f deploy/compose.spark-db.yaml stop
# 卸载 Merchivra 两个容器，完全保留 spark-postgres 和 merchivra 数据库
docker compose --env-file .env -f deploy/compose.spark-db.yaml down --remove-orphans
```

**绝不要通过 `spark-db` 执行 `down -v` 来卸载 Merchivra。** 如果以后明确需要彻底删除 `merchivra` 数据，先备份，再由数据库管理员仅删除 **`merchivra` 数据库及不再使用的专用角色**；不要删除 `iot_farm`、`postgres-data` 或 `spark-iot-net`。手工备份和删除 SQL 已写在[完整指南](docs/deployment/local-spark-db.md)。

## 六、前端做好了吗？目前能用什么？

**前端已经做出来并通过构建，不是空项目。** 当前 React 工作台包括：

- 会话登录与管理员初始化、核心商品/Listing/订单/FBA 数据、卖家授权入口、模拟同步。
- 运营自动化、团队与只读员工权限、采购单/供应商/到货/QC、仓库与批次位置管理。
- V49–V52 盘点、跨仓收货差异处理、隔离品处置、真实 PDF/PNG/JPEG 凭据上传与 SHA-256 校验、原供应商退货的本地人工核对。
- 财务来源事件、分类与核对工作台；仍明确区分实际外部数据和手工/模拟来源。

**但尚不能称“全部前端功能和业务 API 都通过真实店铺验收”。** 真实 Amazon Seller OAuth、实时 Orders/FBA/Finances 账号联调、承运商与供应商外部回执、生产权限/文件安全和可靠性需要进一步测试。目前所有真实 Amazon 出站默认关闭，Mock 仅适合本地。

最新确认的 [Java + React + 真正 PostgreSQL 18 / Flyway CI](https://github.com/kunlong-luo/merchivra/actions/runs/41270021997) 已通过；这不意味着已经实际访问过你个人的 spark-db。

## 七、常用诊断

```bash
curl -f http://127.0.0.1:8080/api/v1/ping
curl -f http://127.0.0.1:8080/actuator/health

(cd apps/server && ./gradlew test)
cd apps/web && npm ci && npm run typecheck && npm run test && npm run build
```

遇到启动失败，先看容器日志，并确认 `spark-postgres`、`spark-iot-net`、独立数据库角色和密码；**不要删除数据卷解决连接错误**。

[API 设计](docs/api/README.md) · [开发指南](docs/development.md) · [完整部署、备份、卸载指南](docs/deployment/local-spark-db.md) · [V52 原件与供应商回执安全边界](docs/purchasing/lot-proof-files-supplier-trace-v52.md)

## 许可证

[Apache License 2.0](LICENSE)。欢迎贡献；不要提交真实 Amazon 凭据、`.env`、卖家订单数据或敏感客户信息。
