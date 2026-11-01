# V53 · Redis 8：共享 Session 与跨实例 SP-API 限流

> 2026-11-01。Merchivra 的业务数据、审计、财务和 Worker checkpoint 始终在 PostgreSQL 18。Redis **仅在显式启用后**承载共享会话/CSRF 和 Amazon 访问冷却，不是事实数据库，也不要求 InfluxDB/Kafka。

## 服务数量与复用你现有的 spark-db

仍然是 **Merchivra 前端 Nginx + Java 后端两个新容器**；后端连接你已经有的 `spark-postgres` 和 `spark-redis`，两者都在 `spark-iot-net`。它们不由 Merchivra Compose 启停或卸载。

- 当前 `spark-db`：Redis 8，容器名 `spark-redis`，容器内端口 `6379`，`--requirepass` 密码由你原 `spark-db` 的 `REDIS_PASSWORD` 定义。
- **在 Merchivra 仓库根目录的本地 `.env`** 配置 `MERCHIVRA_REDIS_PASSWORD`，其值为现有 `spark-redis` 的密码。不要把原密码提交到仓库，也不要通过浏览器请求传入。
- Redis 在 **`spark-iot-net`** 通过 `spark-redis:6379` 连接，不使用容器内的 `localhost`。Compose 已配好 `MERCHIVRA_REDIS_HOST=spark-redis`。
- 原 `iot_farm`/PostGIS/pg_cron、Redis 其他业务的 Key、InfluxDB 仍归 `spark-db` 所有。Merchivra Redis 只写入带独立前缀的 `merchivra:session:v1:*` 和 `merchivra:spapi:quota:v1:*` 键；不得执行 `FLUSHDB`、`FLUSHALL` 或删除 Redis 数据卷。

## 启用步骤（本地、非真实 Amazon）

1. 先按 [spark-db 部署指南](local-spark-db.md) 建好独立 `merchivra` PostgreSQL 18 数据库，启用现有 `spark-postgres` 和 `spark-redis`。
2. 修改 Merchivra 根目录 `.env`：

   ```dotenv
   MERCHIVRA_REDIS_PASSWORD=<你现有 spark-db 中的 REDIS_PASSWORD>
   MERCHIVRA_REDIS_DATABASE=0
   MERCHIVRA_REDIS_SESSIONS_ENABLED=true
   MERCHIVRA_SPAPI_RATE_LIMIT_ENABLED=true
   MERCHIVRA_SPAPI_RATE_LIMIT_COOLDOWN_MS=2000
   # 真实 Amazon 仍保持禁止直到你准备独立授权和测试：
   MERCHIVRA_SPAPI_ENABLED=false
   MERCHIVRA_SPAPI_OAUTH_ENABLED=false
   ```

3. 只重建 Merchivra 容器：

   ```bash
   docker compose --env-file .env -f deploy/compose.spark-db.yaml up -d --build
   docker compose --env-file .env -f deploy/compose.spark-db.yaml logs --tail=100 server
   ```

4. 打开 `http://127.0.0.1:3000`，**重新登录**；切换为 Redis Session 后，原本保存在 Tomcat 内存中的旧 Session 不会自动迁移。
5. 验证共享会话重启不丢失：登录后执行 `docker compose --env-file .env -f deploy/compose.spark-db.yaml restart server`，浏览器刷新后应仍然登录。Redis 不可用时已登录会话的请求应失败，而不是悄悄变成可信本地 Session。

**注意：** 不要在命令行中输出 Redis 密码，也不要将它写在 README、CI 日志或版本控制中。为了谨慎排查，可在原 `spark-db` 容器内部启动安全的 `redis-cli` 交互工具，避免把 `-a <password>` 直接作为命令行参数留在历史记录中。

若使用 **自带数据库的 `deploy/compose.yaml`**，原 `redis` 是可选 profile，且仅供本地虚构环境；需要显式启动：

```bash
docker compose --env-file .env -f deploy/compose.yaml --profile optional-cache up -d --build
```

该临时 Redis 无密码、**无公网端口映射**；仅限 Docker 私有网络中的开发测试。

## Spring Session / CSRF 的具体行为

- `MERCHIVRA_REDIS_SESSIONS_ENABLED=false`（默认）：原有 Tomcat 内存 Session；适合一个后端实例，不支持跨实例复用。
- `true`：`@EnableRedisHttpSession` 使用 Spring Session Data Redis，把 Session 和原有 `HttpSessionCsrfTokenRepository` 一并保存在 Redis；最大闲置时间 **4 小时**，Session Key 前缀为 `merchivra:session:v1`。
- Spring Security 的角色授权和 Owner/tenant 访问仍在 PostgreSQL 按请求核验；Redis Session **绝不是用户权限的权威来源**。
- Redis 出现连接错误不会自动降级为内存 Session。一次授权请求即使返回 500/503 也比错误地接受已过期或无法核验的 Session 更安全。
- 切换回单实例 Session 会让原 Redis 会话失效并需要重新登录；不执行任何针对共享 Redis 的全局清理操作。
- 当前默认关闭 Redis Actuator 健康贡献项（防止 Redis 未启用时把整个独立部署标红）；需要用 Redis 支撑实际会话时应设置 `MERCHIVRA_REDIS_HEALTH_ENABLED=true` 并配置容器探活、重试和运营告警。

## Amazon SP-API 分布式冷却

- `MERCHIVRA_SPAPI_RATE_LIMIT_ENABLED=false`（默认）：不创建 Redis 限流服务，真实 SP-API 出站仍默认禁止。
- 启用时使用 Redis 原子 `SET NX PX`，每个已验证的 Seller ID 的 SHA-256、区域和 Amazon 官方 SDK API family 共用一个冷却 Key，默认 **2000ms**。多个后端实例共用此限制。真实 Seller ID、刷新令牌和 PII 不进入 Redis Key 明文。
- 尚未获得 Seller ID 的 Sellers 验证使用每区域共享的更保守冷却；旧的手动 HTTP 读路径按服务器授予的刷新令牌摘要与 operation 单独限制。
- Redis 连不上、返回不确定或冷却 Key 尚未到期时，请求拒绝继续向 Amazon 发出；后端已有 PostgreSQL Worker checkpoint、重试状态与 lease，不把它们迁入 Redis。
- **生产环境官方 SDK 初始化已增加保护：当 `MERCHIVRA_SPAPI_ENABLED=true` 且 `MERCHIVRA_SPAPI_SANDBOX=false` 时必须显式开启 `MERCHIVRA_SPAPI_RATE_LIMIT_ENABLED=true`。** 但这仅是初始保守冷却，不是 Amazon 官方动态 Usage Plan、每接口精细 TPS、Burst 或 `Retry-After` 自适应实现，也未经过真实 Amazon API 验收。未来需要在真实 API 联调前增加针对 operation 的动态配额、429 退避和公平队列。
- 不要在 Redis 断线期间临时关闭分布式限流来继续调用 Amazon；应修复基础设施，或暂停受影响的真实 Worker。

## 你现有 Redis 的 `allkeys-lru` 风险（重要）

你当前的 `spark-redis` 配置有 `--maxmemory-policy allkeys-lru`，且内存额度较低。这意味着在压力下，Redis **可以任意淘汰会话 Key 与限流 Key**：

- Session Key 被淘汰会令用户意外退出登录。
- 限流 Key 被淘汰可能让下一个请求过早获得许可，因此 `allkeys-lru` **不能为生产 SP-API 配额安全提供可靠保障**。

在开发机上可以试用这个现有 Redis，但在生产同时依赖共享 Session / 限流之前，必须安排独立的 Redis 实例或与现有项目共同评审 `noeviction`、充足的内存、监控、ACL/TLS、重启持久化以及多实例访问策略。**不能由 Merchivra 自动改变 `spark-db` 的全局 `maxmemory-policy`，否则会影响你其他系统缓存。** 设置不同 Redis DB 编号或 Key 前缀无法解决全局内存淘汰问题。

## 测试边界、更新与卸载

```bash
cd apps/server
./gradlew test
```

还有独立 [Redis 8 / PostgreSQL 18 / Spring Session 实际容器集成工作流](https://github.com/kunlong-luo/merchivra/actions/workflows/redis-session-smoke.yml)，验证真实 CSRF 登录、后端容器重启后 Session 仍在、Redis 停止后已有 Cookie 无法继续授权。**只有工作流成功时才能宣称通过，不等于已在你的本地实例测试。**

- 启停应用：`docker compose --env-file .env -f deploy/compose.spark-db.yaml down --remove-orphans`，只影响 Merchivra 前后端。
- 卸载 Merchivra 不会删除 `spark-redis`、`spark-postgres`、它们的数据卷或 `iot_farm`。
- 不做全量缓存清理；如确实需要清理已过期的 Merchivra 临时 Key，应单独审核前缀并手动批量处理，不要对共享 Redis 使用 `FLUSHALL`。
- 业务持久化内容完整保留在独立的 `merchivra` PostgreSQL 18 库内，继续按既有 [备份与卸载指南](local-spark-db.md) 操作。
