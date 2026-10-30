# DEMO-001 · M1 本地 Mock 运营工作台（持久化版本）

**当前能力：仅虚构 Mock 数据；无真实 Amazon 授权、订单入库或生产权限隔离。** M1 试验性的 PostgreSQL 持久化已经落地，但**不能**用于真实卖家业务数据。

## 架构与数据边界

- `MockMarketplaceOverviewGateway`：唯一虚构数据发生源，提供两个假 Seller 账号、三条 Listing、三笔订单和三条 FBA 观测。
- `DemoSyncService`：仅当 `MERCHIVRA_DEMO_ENABLED=true` 时启用，启动时使用“**仅不存在时插入**”策略写入初始数据；重启不会清除保存的快照或同步历史。
- `DemoRepository`：Spring JDBC + Jackson JSONB。Flyway `V1__demo_snapshot_and_sync_history.sql` 创建 **`demo_snapshot` 和 `demo_sync_run`**，没有建立生产租户/订单模型，也没有将财务数字加入账簿。
- `POST /api/v1/demo/sync-runs`：在单一 PostgreSQL 事务中校验幂等键、写入模拟运行状态和快照；同一幂等键同一参数不重复刷新，**更换参数会得到 409**。
- 可选 `simulateFailure=true`：生成 `FAILED_RETRYABLE` 模拟失败记录，不覆盖快照；以后用**新幂等键**发起新任务。这里没有后台 Worker、自动 retry 或真实 SP-API 限流。
- 当前演示以 JSONB **完整快照**保存测试数据，便于快速形成纵向闭环。正式 ERP 应使用已有 [DATA-003](architecture/physical-data-design.md) 提议的租户复合外键及独立来源事实表；**不能直接把 demo_snapshot 当成生产订单数据库**。

## API 清单（全部只对虚构数据生效）

| 路径 | 功能 | 约束 |
|---|---|---|
| `GET /api/v1/demo/overview` | 读取持久 Mock 快照与按账号摘要 | 必须标 `mode=MOCK` |
| `GET /api/v1/demo/accounts` | 虚构店铺列表 | 无真实 Seller ID |
| `GET /api/v1/demo/orders?accountId=...` | 单店铺订单列表 | 未知账号返回 404，不回退全量 |
| `GET /api/v1/demo/listings?accountId=...` | 单店铺 Listing 列表 | 原始 Seller SKU 与内部 SKU 分开 |
| `GET /api/v1/demo/inventory/fba?accountId=...` | 单店铺 FBA 观测 | `null=UNKNOWN`、明确 `0=0` |
| `POST /api/v1/demo/sync-runs` | 模拟刷新快照、记录运行 | Header `Idempotency-Key`（8–128 字符）；JSON `simulateFailure` 可选 |
| `GET /api/v1/demo/sync-runs` | 最近 20 次运行记录 | 仅本地演示 |
| `GET /api/v1/demo/sync-runs/{id}` | 按 UUID 读取任务 | 未知任务 404 |

**注意**：所有路径都在 `/demo` 命名空间，与拟议中的受保护 `/api/v1/orders`、`/api/v1/sync-runs` 是两套不同 API。当前未实现用户认证；不能对公众开放。

## 本地启动

```bash
cp .env.example .env
# 先将 MERCHIVRA_DB_PASSWORD 改为随机强密码
docker compose -f deploy/compose.yaml up --build

# 浏览器：http://localhost:3000
# 仅本机读取：http://localhost:8080/api/v1/demo/overview
```

Compose 会在后端显式配置 `MERCHIVRA_DEMO_ENABLED=true`，Web 与 API 端口绑定 `127.0.0.1`。单独启动 Java 服务时：

```bash
MERCHIVRA_DEMO_ENABLED=true DB_PASSWORD=your_local_db_password mvn -f apps/server/pom.xml spring-boot:run
```

默认设置为 **false**，Demo Controller 不会注册。不要输入真实账号密钥或真实订单，也不要将演示端口转发到公网。

## 演示同步及失败恢复

```bash
# 默认重新写入纯虚构快照，并留下 SUCCEEDED 记录
curl -X POST http://127.0.0.1:8080/api/v1/demo/sync-runs \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-reseed-001' \
  -d '{"simulateFailure":false}'

# 模拟失败：新增 FAILED_RETRYABLE 状态，但保留已保存快照
curl -X POST http://127.0.0.1:8080/api/v1/demo/sync-runs \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-fail-001' \
  -d '{"simulateFailure":true}'

# 重新执行使用新键，不会覆盖失败记录
curl -X POST http://127.0.0.1:8080/api/v1/demo/sync-runs \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-retry-002' \
  -d '{"simulateFailure":false}'
```

也可以在 Web 左侧的 **模拟同步** 页面操作。返回的 `source=MOCK` 和 `fixtureCount` 是本地快照记录数，**不是 Amazon 抓取数量**。

## 数据口径与风险

- 按 USD/EUR **独立**汇总预计订单金额，绝不在无汇率时合并。
- 退款与履约状态可同时为 `PARTIAL + SHIPPED`，财务仍 `NOT_OBSERVED`。
- FBA `fulfillable=null` 是未知，**不等于**观测值零。
- 因费用/成本未取得，预估贡献额“不可计算”，不显示看似精确的净利润。
- JSONB demo 快照不是 `sales_order` 或 `finance_transaction`；没有真实业务独立入库、源事实留存和多租户权限。
- **测试状态**：现阶段只有快速 Java / Vue 单元测试、类型检查和构建；未进行真实 PostgreSQL 集成测试、Docker Compose 运行验收或 E2E，不能把 CI 通过描述为可正式部署。

## 下一轮

先做真正的租户/账号服务端作用域和有审计来源的基础业务实体（需要 G1/G2 决策），再把 Mock 输入迁移到正式业务数据写入协议。同步的分页游标、fencing lease、失败补偿会分模块实施，并在真正使用 Amazon API 前验证。

涉及 [Issue #5 Mock](https://github.com/kunlong-luo/merchivra/issues/5)、[Issue #6 同步](https://github.com/kunlong-luo/merchivra/issues/6)、[Issue #7 SKU](https://github.com/kunlong-luo/merchivra/issues/7)。
