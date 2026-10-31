# PostgreSQL 17 → 18：有数据部署必须先迁移

> 2026-10-31。新装的 Merchivra 使用 PostgreSQL **18**（`postgres:18-alpine`）。
> 本指南针对已运行过 `postgres:17-alpine` 且使用旧卷 `pgdata` 的部署。

## 为什么不能只改镜像

官方 PostgreSQL 18 镜像将默认 `PGDATA` 改为
`/var/lib/postgresql/18/docker`，卷的挂载点改为
`/var/lib/postgresql`。旧 Compose 将 `pgdata` 挂载到
`/var/lib/postgresql/data`，直接替换版本或把数据目录交给
18 容器会失败，**并不会自动做数据库文件升级**。

Merchivra 采用独立的新卷 `pgdata18`；旧 `pgdata` 原样保留。
因此，**如果以前已经有业务数据，切换 Compose 后看到新空库并不意味着
旧数据已迁入**。迁移前不要向新库写入生产数据。

## 升级前置要求

1. 安排停机窗口，停止所有 API/Worker 写操作；在旧 17 实例上核实实际数据库版本为 17。
2. 先做云盘/卷快照，并使用 PostgreSQL 17 的 `pg_dump` 或 `pg_dumpall`
   取得一致的逻辑备份（包括需迁移的角色/权限）。
3. 将备份保存到 Docker 容器和数据卷 **之外** 的受保护位置；
   验证备份大小、校验和，最好先在独立临时环境恢复演练。
4. 记录数据库用户名/角色，确认目标 18 使用一致的编码、扩展及字符排序规则；
   对不兼容的扩展先制订升级计划。

### 参考的逻辑备份/恢复示例（单库，需按照部署实例调整）

以旧 17 Compose 运行状态为例，先停止 API/Worker 写入，再通过旧的
PostgreSQL 17 容器进行逻辑备份：

```bash
# 在切换到新 Compose 配置前，在旧容器仍运行时：
docker compose --env-file .env -f deploy/compose.yaml exec -T postgres \
  pg_dump -U merchivra -d merchivra -Fc > merchivra-pg17.backup
sha256sum merchivra-pg17.backup > merchivra-pg17.backup.sha256
# 把备份及校验文件复制到受保护的外部存储
```

若当前仓库已更新为 PG18，可以在旧 17 部署工作目录执行相同备份命令，
或在停止写入后用旧镜像只读访问旧卷进行备份；**先确认卷对应的真实 Compose
项目名**，不得对未验证的数据目录直接运行版本 18。

完成备份后再使用 PG18 新卷启动新数据库，**不要运行 `down -v`**：

```bash
docker compose --env-file .env -f deploy/compose.yaml stop server web
docker compose --env-file .env -f deploy/compose.yaml up -d postgres
# 使用 18 的客户端工具恢复到已建立的全新 merchivra 数据库，
# 只有在目标库为空或已明确批准覆盖时才执行：
cat merchivra-pg17.backup | docker compose --env-file .env -f deploy/compose.yaml \
  exec -T postgres pg_restore -U merchivra -d merchivra --no-owner --no-privileges
# 校验源/目标表数、业务行数、外键、一致性、Flyway 版本、实际版本和备份恢复记录后再开放 API
docker compose --env-file .env -f deploy/compose.yaml exec -T postgres \
  psql -U merchivra -d merchivra -c 'SHOW server_version;'
docker compose --env-file .env -f deploy/compose.yaml up -d server web
```

**注意**：这段示例面向单库及已授权的停机恢复；生产若涉及多角色、多个库、
扩展、物化视图、权限、增量写入或较大数据库，请使用经过演练的
`pg_upgrade`/迁移方案。升级后逐项验证 Flyway V1–V11、Session、
Marketplace、Orders/Listing/FBA、审计和 Worker 检查点。
PostgreSQL 17 的原数据卷与备份应保留到业务回归和回滚窗口结束。

## 新装/CI

全新部署不涉及旧卷迁移，直接使用
`postgres:18-alpine` 和 `pgdata18:/var/lib/postgresql`。
CI 使用一次性空卷，断言 `server_version_num` 为 18 且 `PGDATA`
为 `/var/lib/postgresql/18/docker`，执行全量 Flyway 和 Mock
断点重启冒烟；**这种测试不能替代旧数据的真实迁移验收**。

参考：
- https://hub.docker.com/_/postgres
- https://docs.docker.com/guides/postgresql/immediate-setup-and-data-persistence/
