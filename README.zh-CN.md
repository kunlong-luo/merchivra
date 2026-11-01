# Merchivra ERP（麦奇维拉）

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

**Redis 不是当前运行必需服务。** 内置 Compose 的 Redis 已改为可选 profile；你现有 `spark-db` 中的 Redis、InfluxDB、PostGIS 和 pg_cron 都不需要为了 Merchivra 修改或删除。

<details>
<summary>当前主要技术选型</summary>

- 后端：Java 25、Spring Boot 4.1.2、Spring Security 7、Maven、Flyway、官方 Amazon SP-API Java SDK（真实出站默认关闭）。
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

若想额外运行**非业务必需**的 Redis：`docker compose --env-file .env -f deploy/compose.yaml --profile optional-cache up -d`。

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

**步骤 2：** 第一次在已有 `spark-postgres` 中创建专用数据库角色（不要使用 `root` 作为应用账号）：

```bash
docker exec -it spark-postgres psql -U root -d postgres
```

在 `psql` 交互提示符逐条执行：

```sql
CREATE ROLE merchivra LOGIN;
\password merchivra
CREATE DATABASE merchivra OWNER merchivra;
\q
```

> 若角色或数据库已存在，不要覆盖。为新角色使用独立强密码，**不要把你现有 spark-db 的管理员密码写进 Merchivra 配置**。

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

**这一方案只新增两个 Merchivra 容器：`server` 和 `web`。第三层数据库复用 `spark-postgres`。** 不启动新 PostgreSQL，不占用你已有的 5432 端口，不修改其他 spark-db 数据卷；`spark-redis`、InfluxDB 等保持由原项目管理。

默认前端仍为 http://127.0.0.1:3000，API 为 http://127.0.0.1:8080。若端口冲突，可在 `.env` 设置 `MERCHIVRA_WEB_PORT` / `MERCHIVRA_API_PORT`。

[查看完整 spark-db 适配说明、备份、故障排查与网络示意](docs/deployment/local-spark-db.md)。

## 四、源码本地开发（React 热更新 + Spring Boot）

前提：**Java 25、Maven 3.9+、Node.js 24.11+** 和运行中的 PostgreSQL 18。可复用上述 `spark-postgres`，它已经将 PG 端口映射到本机 `127.0.0.1:5432`。

终端 1：

```bash
export DB_HOST=127.0.0.1 DB_PORT=5432 DB_NAME=merchivra DB_USER=merchivra
export DB_PASSWORD='your-dedicated-merchivra-password'
export MERCHIVRA_BOOTSTRAP_USERNAME=your_admin
export MERCHIVRA_BOOTSTRAP_PASSWORD='your-unique-16-plus-character-password'
mvn -f apps/server/pom.xml spring-boot:run
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

mvn -f apps/server/pom.xml test
cd apps/web && npm ci && npm run typecheck && npm run test && npm run build
```

遇到启动失败，先看容器日志，并确认 `spark-postgres`、`spark-iot-net`、独立数据库角色和密码；**不要删除数据卷解决连接错误**。

[API 设计](docs/api/README.md) · [开发指南](docs/development.md) · [完整部署、备份、卸载指南](docs/deployment/local-spark-db.md) · [V52 原件与供应商回执安全边界](docs/purchasing/lot-proof-files-supplier-trace-v52.md)

## 许可证

[Apache License 2.0](LICENSE)。欢迎贡献；不要提交真实 Amazon 凭据、`.env`、卖家订单数据或敏感客户信息。
