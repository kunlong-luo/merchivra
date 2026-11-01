# 部署与卸载指南：内置 PostgreSQL 18 或复用 spark-db

> 最后核对：2026-11-01。Merchivra 仍为 **pre-alpha**，请先在本机或隔离的测试环境使用。Amazon Seller OAuth / 真实 SP-API 默认关闭，真实卖家授权由使用者在所有核心功能开发完成后自行在本地测试。

## 一、架构与运行服务数

Merchivra 目前是 **三层服务**，而不是三个必须独立采购的数据库/中间件：

| 层 | 容器/进程 | 用途 | 本地端口 |
| --- | --- | --- | --- |
| 前端 | `web`（React 19 编译产物 + Nginx） | SPA、同源代理 `/api/` | `127.0.0.1:3000` |
| 后端 | `server`（Java 25 / Spring Boot 4.1.2） | 登录、业务 API、Flyway、模拟工作流 | `127.0.0.1:8080` |
| 数据库 | `postgres` **或**现有 `spark-postgres` | PostgreSQL **18**，库存、订单、审计和证明文件 | 内置不发布端口；spark-db 原有 `127.0.0.1:5432` |

当前仓库的 Redis 只作为可选的未来缓存预留，**业务代码无需 Redis 才能启动**，内置 Compose 通过 `--profile optional-cache` 才会启动它。用户 `spark-db` 内的 `spark-redis`（Redis 8）、InfluxDB 3、InfluxDB Explorer **不需要**启动给 Merchivra 使用，也不要在 Merchivra 的卸载过程中管理或删除。

## 二、方式 A：一条命令启动三个独立容器（内置数据库）

适用于没有现成数据库的开发机，要求 Docker Engine + Docker Compose v2：

```bash
git clone https://github.com/kunlong-luo/merchivra.git
cd merchivra
cp .env.example .env
# 修改 .env 的 MERCHIVRA_DB_PASSWORD，设置独立强密码；
# 配置 MERCHIVRA_BOOTSTRAP_USERNAME 与 16 位以上管理员密码。
docker compose --env-file .env -f deploy/compose.yaml config -q
docker compose --env-file .env -f deploy/compose.yaml up -d --build
docker compose --env-file .env -f deploy/compose.yaml ps
```

- 访问 `http://127.0.0.1:3000`，使用首次初始化的账号登录。后端直接健康检查：`http://127.0.0.1:8080/api/v1/ping` 或 `/actuator/health`。
- 后端首次连接空 PostgreSQL **18** 时由 Spring Flyway 自动创建数据库内的表。该方式 `deploy/compose.yaml` 固定数据库名为 `merchivra`，运行时内部主机名 `postgres`。
- 默认仅 `postgres`、`server`、`web` 三个容器。若确实需要调试预留缓存，再使用 `docker compose --env-file .env -f deploy/compose.yaml --profile optional-cache up -d`；这不会自动配置 Spring Boot 使用 Redis。
- 日志与重启：`docker compose --env-file .env -f deploy/compose.yaml logs -f server`，或 `docker compose --env-file .env -f deploy/compose.yaml restart server web`。
- **不要**为了“重装”执行 `down -v`，它会删除内置数据库数据卷。

## 三、方式 B：**推荐给已有 spark-db 的用户**，只启动两个 Merchivra 容器

你的 `spark-db` Compose 已提供：

- PostgreSQL **18 + PostGIS 3.6**：容器名 `spark-postgres`；位于 Docker external network **`spark-iot-net`**；宿主机端口通常仅 `127.0.0.1:5432`。
- 原数据库默认是 `iot_farm`，数据库管理员角色可能为 `root`。**Merchivra 必须使用新建的独立 `merchivra` 数据库与低权限 `merchivra` 角色**。不要让 Flyway 在已有 `iot_farm` 中建表，不要直接使用 PostgreSQL 管理员 `root` 给应用连接。
- 原实例可能有 `pg_cron`、`pg_stat_statements`，但 Merchivra 当前不依赖这些扩展，不要修改现有 `cron.database_name` 或 `shared_preload_libraries`。
- **无需**复制或移动 `spark-db` 的数据卷，且 `deploy/compose.spark-db.yaml` 不会启动任何新的 PostgreSQL、Redis 或 InfluxDB。

### B1. 确认已有 spark-db 实际在线

先在你原有的 `spark-db` 工作目录用它自己的 Compose 命令启动它（需要你已有的外部 `spark-iot-net` 和 `spark-iot-internal-net` 网络）：

```bash
# 在 spark-db 的原目录执行：
docker compose up -d postgres
# 从任意目录验证：
docker network inspect spark-iot-net
docker inspect spark-postgres --format '{{json .NetworkSettings.Networks}}'
docker exec spark-postgres psql -U root -d postgres -Atc 'SHOW server_version;'
```

数据库版本应为 **18.x**，网络输出中必须存在 **spark-iot-net**；若原项目重命名网络、服务容器名，先对应调整 `deploy/compose.spark-db.yaml` 的 `spark_db.name` 和 `DB_HOST`。

**注意：** `spark-postgres` 容器内的 `localhost`、Merchivra `server` 容器内的 `localhost` 和本机 `127.0.0.1` 是不同的网络命名空间。两个容器通过 `spark-iot-net` 通信时，应使用 **`spark-postgres:5432`**，而不是 `127.0.0.1:5432`。

### B2. 只在 spark-postgres 内建立 Merchivra 自己的库和角色

此步骤只需做一次，**不会更改或重建 `iot_farm`**：

```bash
docker exec -it spark-postgres psql -U root -d postgres
```

在出现的 `postgres=#` 提示符后逐条输入，`\password` 命令会在终端交互询问密码，避免在 shell 历史留下明文：

```sql
CREATE ROLE merchivra LOGIN;
\password merchivra
CREATE DATABASE merchivra OWNER merchivra;
\l
\q
```

如果库/角色已经存在，不要重复创建，更不要删除重建。使用该角色连接 `merchivra`，让 Flyway 仅在该库内管理自己的 `public` schema。创建的用户名密码需要与下述环境文件一致。请勿把给 Spark 系统使用的现有超级管理员密码复用为 Merchivra 的业务数据库密码。

可选：检查新角色与库存在（仍不访问 `iot_farm`）：

```bash
docker exec spark-postgres psql -U root -d postgres -Atc \
  "SELECT datname FROM pg_database WHERE datname='merchivra'"
docker exec spark-postgres psql -U root -d merchivra -Atc 'SHOW server_version_num;'
```

### B3. 启动复用数据库的 Merchivra 前后端

在 **Merchivra 仓库根目录**：

```bash
cp .env.example .env
# 配置：
# MERCHIVRA_DB_NAME=merchivra
# MERCHIVRA_DB_USER=merchivra
# MERCHIVRA_DB_PASSWORD=<B2 中为 merchivra 角色设置的专用密码>
# MERCHIVRA_BOOTSTRAP_USERNAME=<你的本地管理员账号>
# MERCHIVRA_BOOTSTRAP_PASSWORD=<至少 16 位的管理员密码>
# MERCHIVRA_DEMO_ENABLED=false   # 可选；只在本地虚构演示时改为 true

docker compose --env-file .env -f deploy/compose.spark-db.yaml config -q
docker compose --env-file .env -f deploy/compose.spark-db.yaml up -d --build
docker compose --env-file .env -f deploy/compose.spark-db.yaml ps
```

- 只新建 **`merchivra-server-1`、`merchivra-web-1`** 两个容器，数据库继续使用已有 `spark-postgres`。这就是 **三层服务，两个新增容器** 的结构。
- `server` 同时接入项目自己的默认内部网络（供 `web` 的 Nginx 使用）及 `spark-iot-net`（仅供连接共享 PostgreSQL）；`web` 不直接连接共享数据库网络。
- 首次启动时 Spring Boot 对 **`merchivra` 数据库** 自动执行 Flyway V1–V52（以及后续新增版本）。启动完成后访问 `http://127.0.0.1:3000`，登录后查看业务模块。
- 当前 Compose 仍只在 `127.0.0.1` 公开 Web/API 调试端口，不具备公网 TLS 入口。你可以在 `.env` 使用 `MERCHIVRA_WEB_PORT` / `MERCHIVRA_API_PORT` 调整端口，避免占用其他项目的 3000/8080。

排错：

```bash
docker compose --env-file .env -f deploy/compose.spark-db.yaml logs --tail=120 server
docker compose --env-file .env -f deploy/compose.spark-db.yaml logs --tail=120 web
docker exec spark-postgres psql -U root -d merchivra -Atc \
  "SELECT max(installed_rank) FROM flyway_schema_history WHERE success=true;"
```

如果出现连接失败，先确认 `spark-db` 的 PostgreSQL 容器在线、网络 `spark-iot-net` 存在、该容器实际在此网络上、角色密码正确且 `merchivra` 数据库存在；不要用 `down -v`“修复”登录错误。

### B4. 使用独立前后端进程进行开发（共享同一数据库）

需要 Java **25**、Gradle Wrapper **9.2.1**、Node.js **24.11+**、npm（以及正在运行的 spark-db PostgreSQL 18）：

在终端 A 配置后端环境（变量仅供示例，密码请在本地安全注入）：

```bash
export DB_HOST=127.0.0.1 DB_PORT=5432
export DB_NAME=merchivra DB_USER=merchivra
# 将下方值换成本地真正的 merchivra 角色密码；不要提交到 Git
export DB_PASSWORD='your-dedicated-database-password'
export MERCHIVRA_BOOTSTRAP_USERNAME=your_admin
export MERCHIVRA_BOOTSTRAP_PASSWORD='your-unique-16-plus-character-password'
export MERCHIVRA_DEMO_ENABLED=false
(cd apps/server && ./gradlew bootRun)
```

终端 B：

```bash
cd apps/web
npm ci
npm run dev
```

Vite 开发模式通常使用 `http://127.0.0.1:5173`，`apps/web/vite.config.ts` 自动把 `/api` 转发到 `127.0.0.1:8080`。后端在宿主机运行时访问本地 `spark-db` 已映射的 `127.0.0.1:5432`；**与容器方式不同**，不使用 `spark-postgres` 主机名。

### B5. 仅用于隔离测试的非 Demo 覆盖层

若要在具备独立 TLS 终端代理的环境禁用演示功能，可使用现有 `deploy/compose.production.yaml`：

```bash
docker compose --env-file .env \
  -f deploy/compose.spark-db.yaml -f deploy/compose.production.yaml \
  up -d --build
```

此覆盖层会关闭 Demo、强制安全 Cookie，但**不会提供 HTTPS/TLS、密钥管理、自动数据库备份、恶意文件扫描或真实 Amazon 授权**。如果浏览器只是访问 `http://127.0.0.1:3000`，不要将 `MERCHIVRA_SESSION_COOKIE_SECURE=true` 用作“可通过 HTTP 正常登录”的方案；正式 HTTPS 必须配置受信任的反向代理。所有真实 Amazon API 默认为关闭。

## 四、停止、升级、备份及卸载

### A 方案（内置 PostgreSQL）

```bash
# 停止（保留所有容器和数据）
docker compose --env-file .env -f deploy/compose.yaml stop

# 升级最新镜像/重新构建（原数据库卷保留）
docker compose --env-file .env -f deploy/compose.yaml up -d --build

# 卸载应用容器及项目网络，默认保留 pgdata18 数据卷
docker compose --env-file .env -f deploy/compose.yaml down --remove-orphans
```

**仅在你已确认无须保留内置 Merchivra 数据并完成备份时**才删除项目卷。不要在有其他项目容器共用的 Docker 上批量删除所有卷。

```bash
# ⚠️ 高风险，仅 A 方案；会永久删除内置 postgres 的 merchivra 数据
docker compose --env-file .env -f deploy/compose.yaml down -v
```

### B 方案（复用 spark-db）

```bash
# 停止 Merchivra，不停止 spark-db PostgreSQL / Redis / InfluxDB
docker compose --env-file .env -f deploy/compose.spark-db.yaml stop

# 卸载这两个 Merchivra 容器及自己的默认网络；数据库完全保留
docker compose --env-file .env -f deploy/compose.spark-db.yaml down --remove-orphans
```

不要通过 `spark-db` 的 Compose 执行 `down -v`，也不要删除 `spark-iot-net`、`postgres-data`、`redis-data`、`iot_farm` 等共享对象。

如果明确希望在 B 方案下 **单独永久销毁 Merchivra 数据**，先停掉所有 Merchivra 后端和 Worker、确认备份能恢复，再在 `spark-postgres` 容器中**人工逐条核对名称**：

```bash
# 先保存独立 merchivra 数据库的逻辑备份到当前宿主机工作目录
docker exec spark-postgres pg_dump -U root -d merchivra -Fc > merchivra-before-removal.dump
# 强烈建议将备份放到受保护的外部介质并演练恢复
docker exec -it spark-postgres psql -U root -d postgres
```

以下语句只在你确实决定删除独立 `merchivra` 数据库时于 psql 中**人工执行**；**不要**加入自动卸载脚本：

```sql
-- ⚠️ 永久删除全部 Merchivra 用户、订单、审计和私有凭据附件
DROP DATABASE merchivra WITH (FORCE);
-- 仅在确认该角色不用于任何其他数据库时才考虑：
DROP ROLE merchivra;
```

此操作不删除 `spark-db` 的数据库容器、`iot_farm`、其他数据库、PostGIS/pg_cron/Redis/InfluxDB 或其数据卷。

### 备份与恢复（两种方式均适用）

- 内置数据库：`docker compose --env-file .env -f deploy/compose.yaml exec -T postgres pg_dump -U merchivra -d merchivra -Fc > merchivra.backup`
- 复用 spark-db：`docker exec spark-postgres pg_dump -U root -d merchivra -Fc > merchivra.backup`
- 只在**干净且已确认为目标的独立 `merchivra` 数据库**中恢复，按需使用 `pg_restore --no-owner --no-privileges`，谨慎处理目标库已有对象，先在测试环境演练。
- V52 真实文件附件存储于 PostgreSQL BYTEA；逻辑备份会包含它们，因此应为备份设置访问控制、加密、保留周期和可恢复性检查。

## 五、验证与前端功能状态

```bash
curl -f http://127.0.0.1:8080/api/v1/ping
curl -f http://127.0.0.1:8080/actuator/health
# 业务操作需要先在 http://127.0.0.1:3000 登录
(cd apps/server && ./gradlew test)
cd apps/web && npm ci && npm run typecheck && npm run test && npm run build
```

React 19 前端**已经实现并构建成功**，有实际登录/会话、核心数据、卖家授权代码入口、运营自动化、团队权限、采购与仓储（含 V49–V52 库位、盘点、退货与凭据）、财务来源与对账页面、Mock 演示，以及宽度响应式验证。它不是空白脚手架，也不是 Vue；但目前仍是 pre-alpha，尚未完成真实 Amazon 授权后的全业务联调、外部承运商回执核验或面向公网的安全认证，因此不能承诺“前端和整套 ERP 已达到生产可用”。

近期实际独立 PG18 / Flyway、Spring 启动、Java 与 React 构建成功：[2026-11-01 CI](https://github.com/kunlong-luo/merchivra/actions/runs/41270021997)。这验证的是**空库迁移与构建**，并不表示当前用户的 spark-db 实例已被直接连接或修改。

## 可选 Redis 8 接入（V54）

现在不必新增 Docker 容器即可复用 `spark-redis`：将 `.env` 中 `MERCHIVRA_REDIS_PASSWORD` 设置为已有 `spark-db` 中的 Redis 密码，启用 `MERCHIVRA_REDIS_SESSIONS_ENABLED=true` 和（需要正式 Amazon 限流时）`MERCHIVRA_SPAPI_RATE_LIMIT_ENABLED=true`。不会自动修改 `spark-db` 的 `allkeys-lru` 全局策略，不得把共享 Redis 当成已经通过生产会话/配额认证。注意打开 Redis Session 后首次需重新登录；Redis 连接故障必须失败而非自动退回本机会话。完整说明和 Redis 8 实际容器验收见 [V54 Redis 指南](redis-session-spapi-v53.md)。
