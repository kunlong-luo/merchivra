# Merchivra 开发指南（CURRENT · 2026-11-22 / V195）

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

### ClamAV 主机环境变量

扫描主机统一使用 `EVIDENCE_AV_HOST`；已有本地 `.env`、IDEA 运行配置及部署环境请将旧 `MERCHIVRA_EVIDENCE_AV_HOST` 改名，并删除旧项。`MERCHIVRA_EVIDENCE_AV_REQUIRED` 与 `MERCHIVRA_EVIDENCE_AV_PORT` 本次不改名。生产 Compose 仍强制扫描，空主机时拒绝启动；非 Demo 未启用扫描时仍拒绝原件传输。没有私网 clamd 时不要填虚构主机或关闭真实环境的扫描要求。

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

### 与 spark-iot-farm 共存：仓库内置 local profile（推荐 Pop!_OS）

宿主机可通过完整 `DB_URL` 或原有 `DB_HOST/DB_PORT/DB_NAME` 配置 JDBC；完整 URL 优先，用户名仍为 `DB_USER`，密码仍为 `DB_PASSWORD`（无默认密码）。Compose 与 CI 的默认 8080/3000 不变。不要将连接凭据写入共享 YAML 或 Git。

如已有其他应用占用 8080/3000，可在仓库外的 `~/.config/merchivra/application.yml` 设置以下本地覆盖（真实密码自行填入；仅文件所有者可读写）：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://127.0.0.1:5432/merchivra
    username: merchivra
    password: YOUR_LOCAL_DATABASE_PASSWORD
server:
  address: 127.0.0.1
  port: 8081
merchivra:
  bootstrap:
    username: admin
    password: YOUR_LOCAL_ADMIN_PASSWORD
  demo:
    enabled: false
```

仓库内置 `application-local.yml` 默认连接 127.0.0.1:5432/merchivra 和 127.0.0.1:6379，后端只监听 127.0.0.1:8081，并自动读取上述用户私有 YAML（设置 `chmod 700 ~/.config/merchivra; chmod 600 ~/.config/merchivra/application.yml`）。IDEA 导入 Gradle 项目后直接选择共享运行配置 `Merchivra Local (spark-db)`；或者在已有 Spring Boot 运行配置将 Active profiles 设为 `local`，或执行 `./apps/server/gradlew -p apps/server --no-daemon bootRun --args='--spring.profiles.active=local'`。删去冲突的旧启动参数；首次管理员密码至少16位。Redis 密码如需使用也仅在私有 YAML 或环境中注入，默认 Session/限流关闭；不得清空/改动共享实例。保持 Seller/Ads OAuth、同步、真实写入关闭，非 Demo 未核证文件仍不能传输。

前端使用新增的 Pop!_OS/Linux 专用 Next 开发脚本（本地 3001，自动代理 8081）：

```bash
cd apps/web
pnpm install --frozen-lockfile
pnpm dev:local
curl -i http://127.0.0.1:8081/api/v1/ping
curl -i http://127.0.0.1:3001/api/v1/auth/me
```

预期 ping 200、匿名 auth/me 401。上述端口为宿主机开发示例，不修改 Compose 容器内端口或生产监听方式。

### 本地启动时显示“服务暂时未就绪”

前端 `pnpm dev` **只启动 Next.js**，不会启动数据库或后端。首页先在同源代理 `GET /api/v1/auth/me` 检查 Session：**401 表示未登录（正常展示登录页）**；500/502/503/504、代理连接中断和后端异常表示无法完成检查，**不能当作密码错误，也不能绕过身份验证**。

V195 将原来的“无法验证身份 / 会话检测失败 (HTTP 500)”改为不含原始异常的友好连接状态，启动瞬时 5xx 会有限次自动重试，随后显示“重新连接”和折叠的诊断说明；真实已验证的业务数据仍需 Session 才能访问。若持续异常，按顺序检查：

```bash
# PostgreSQL 18 和 Spring Boot 均应处于运行状态
curl -i http://127.0.0.1:8080/api/v1/ping
# 再检查 Next 的同源代理（未登录时 401 是预期结果）
curl -i http://127.0.0.1:3000/api/v1/auth/me
```

- 8080 不能连接：检查 Spring 进程、数据库连接、环境变量及其启动日志（如 Flyway 迁移失败）。
- 8080 正常而 3000 的 `/api/v1/auth/me` 失败：检查 `apps/web/next.config.ts` 的 `MERCHIVRA_API_INTERNAL_URL` 是否指向可访问的后端。修改该变量后重新启动 Next。
- 直接访问 8080 的 `/api/v1/auth/me` 返回 500：后端已接到请求但会话处理异常，应查看 Spring Boot 日志，不能仅通过刷新前端修复。
- 即使错误页现在更友好，也不代表数据源、权限、银行或外部 Amazon 已准备好；不要在本地为绕过这个提示关闭身份校验。


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

工程规范见 [AGENTS](../AGENTS.md) · [ENG-001](governance/engineering-standards.md) · [文档控制](governance/document-control.md)。产品/财务现行任务见 [P0 计划](roadmap/current-plan.md)，技术架构见 [ARCH-001](architecture/overview.md)，合成浏览器/PG18/恢复覆盖见 [CI 策略](testing/ci-policy.md)。历史 V6–V145 同步、采购、OpenAPI 试验说明继续保存在各自版本文档，但**不再是当前开发入口**。
