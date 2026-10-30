# DEMO-001 · M1 本地 Mock 运营工作台（持久化版本）

**当前能力：仅虚构 Mock 数据；现已需要本地账号 Session 登录，但无真实 Amazon 授权或完整生产 IAM。** M2 已增加核心实体表及固定虚构租户的 Mock 导入，仍**不能用于真实卖家业务数据**。

## 架构与数据边界

- `MockMarketplaceOverviewGateway`：唯一虚构数据发生源，提供两个假 Seller 账号、三条 Listing、三笔订单和三条 FBA 观测。
- `DemoSyncService`：仅当 `MERCHIVRA_DEMO_ENABLED=true` 时启用，启动时使用“**仅不存在时插入**”策略写入初始数据；重启不会清除保存的快照或同步历史。
- `DemoRepository`：Spring JDBC + Jackson JSONB。Flyway `V1__demo_snapshot_and_sync_history.sql` 创建 **`demo_snapshot` 和 `demo_sync_run`**，没有建立生产租户/订单模型，也没有将财务数字加入账簿。
- `POST /api/v1/demo/sync-runs`：在单一 PostgreSQL 事务中校验幂等键、写入模拟运行状态和快照；同一幂等键同一参数不重复刷新，**更换参数会得到 409**。
- 可选 `simulateFailure=true`：生成 `FAILED_RETRYABLE` 模拟失败记录，不覆盖快照；以后用**新幂等键**发起新任务。这里没有后台 Worker、自动 retry 或真实 SP-API 限流。
- `demo_snapshot` 仍为前端只读工作台的数据来源。成功手动 Mock 同步后，[DATA-004](architecture/core-domain-foundation.md) 的 `DemoCoreImporter` 同事务把两个固定虚构 Seller 账号、SKU、Listing、订单项和库存观测导入 V2 核心业务表，**失败模拟与幂等重放不会再次导入**。新表并不是已授权的生产数据服务。

## API 清单（全部只对虚构数据生效）

| 路径 | 功能 | 约束 |
|---|---|---|
| `GET /api/v1/demo/overview` | 读取持久 Mock 快照与按账号摘要 | 必须标 `mode=MOCK` |
| `GET /api/v1/demo/accounts` | 虚构店铺列表 | 无真实 Seller ID |
| `GET /api/v1/demo/core-status` | 核心表的固定虚构租户计数 | 不接受客户端 tenantId，仅 Mock 开关启用时存在 |
| `GET /api/v1/demo/orders?accountId=...` | 单店铺订单列表 | 未知账号返回 404，不回退全量 |
| `GET /api/v1/demo/listings?accountId=...` | 单店铺 Listing 列表 | 原始 Seller SKU 与内部 SKU 分开 |
| `GET /api/v1/demo/inventory/fba?accountId=...` | 单店铺 FBA 观测 | `null=UNKNOWN`、明确 `0=0` |
| `POST /api/v1/demo/sync-runs` | 模拟刷新快照、记录运行 | Header `Idempotency-Key`（8–128 字符）；JSON `simulateFailure` 可选 |
| `GET /api/v1/demo/sync-runs` | 最近 20 次运行记录 | 仅本地演示 |
| `GET /api/v1/demo/sync-runs/{id}` | 按 UUID 读取任务 | 未知任务 404 |

**注意**：所有路径都在 `/demo` 命名空间，与拟议中的受保护 `/api/v1/orders`、`/api/v1/sync-runs` 是两套不同 API。当前所有 `/demo` API 均需要先进行 Session 登录（含 POST CSRF）；仍不能对公众开放。

## 本地启动

```bash
cp .env.example .env
# 先将 MERCHIVRA_DB_PASSWORD 改为随机强密码
# 另设置 MERCHIVRA_BOOTSTRAP_USERNAME 和 MERCHIVRA_BOOTSTRAP_PASSWORD（自设至少 16 位）
docker compose --env-file .env -f deploy/compose.yaml up --build

# 浏览器：http://localhost:3000
# 仅本机读取：http://localhost:8080/api/v1/demo/overview
```

Compose 会在后端显式配置 `MERCHIVRA_DEMO_ENABLED=true`，Web 与 API 端口绑定 `127.0.0.1`。单独启动 Java 服务时：

```bash
MERCHIVRA_DEMO_ENABLED=true MERCHIVRA_BOOTSTRAP_USERNAME=your_admin MERCHIVRA_BOOTSTRAP_PASSWORD=your_random_password DB_PASSWORD=your_local_db_password mvn -f apps/server/pom.xml spring-boot:run
```

默认设置为 **false**，Demo Controller 不会注册。不要输入真实账号密钥或真实订单，也不要将演示端口转发到公网。

## 演示同步及失败恢复

**请优先在登录后的 Web 工作台中操作。** 下方旧的 curl 示例在新 Session/CSRF 安全边界下不会单独成功：必须额外先取得 Session Cookie、登录成功并在 POST 请求中提交最新的 `X-CSRF-TOKEN`。参见 [本地登录说明](security/local-auth.md)；不建议在命令行中传递真实密码。


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
- JSONB 快照现在能**投影到 V2 结构化核心表**（含 `sales_order`/`order_item`），但只允许固定虚构数据，没有真实 Amazon 源事实流、Finances 事件或经过认证的多租户数据权限。
- **测试状态**：现阶段只有快速 Java / Vue 单元测试、类型检查和构建；未进行真实 PostgreSQL 集成测试、Docker Compose 运行验收或 E2E，不能把 CI 通过描述为可正式部署。

## 下一轮

先在真实 PostgreSQL 中验证 V1/V2 迁移、核心表唯一约束与跨租户负例，再落实已审核的身份认证、租户/账号服务端作用域及真实来源事实协议。同步的分页游标、fencing lease、失败补偿会分模块实施，并在真正使用 Amazon API 前验证。

涉及 [Issue #5 Mock](https://github.com/kunlong-luo/merchivra/issues/5)、[Issue #6 同步](https://github.com/kunlong-luo/merchivra/issues/6)、[Issue #7 SKU](https://github.com/kunlong-luo/merchivra/issues/7)。

## 本轮新增：结构化 Mock 入库演示

进入左侧“模拟同步”，点击“开始模拟同步”，即可查看新增加的“PostgreSQL · 核心实体入库”计数。

按目前虚构 fixture，**预期**为 2 店铺、2 Marketplace、1 内部 SKU、3 Listing、3 订单、3 订单项与 3 条 FBA 库存观测。再次同步相同来源时，这些计数预期不会翻倍；模拟失败不会覆盖已保存快照及结构化实体。

直接读取计数接口：

```bash
curl http://127.0.0.1:8080/api/v1/demo/core-status
```

所有导入通过 `DemoCoreImporter`，使用预留的固定 Mock Tenant ID；该 API 不接受用户传入的租户 ID，也不允许读取真实卖家数据。它与生产中的 tenant session/权限模型**不是一回事**。

**验证限制：** Maven/Vue/Vitest 快速 CI 已通过，但尚未有 PostgreSQL 容器执行 V1/V2 的运行证据。上述计数是代码与 fixture 的预期值，不是实际数据库验收结果。

## V5 Sandbox：从模拟 API 到当前登录租户核心表

旧 `/demo/sync-runs` 仍只落在固定虚构 Demo Tenant。另有 [TEST-004](testing/mock-full-chain.md) 的 `/api/v1/demo/sandbox/{marketplaceId}/replay`，允许当前登录 OWNER **手动**把固定 US/DE 虚构订单（分页）、Listing、FBA 投影到自己创建的本地 `LOCAL-`/`UNCONFIGURED` Marketplace。使用 `dataOrigin=MOCK`，不会注入真实账号、也不创建 Finances 事件；日本没有对应场景会返回 422。

[Compose 全链路成功运行（2026-10-30）](https://github.com/kunlong-luo/merchivra/actions/runs/41061679196) 已实测该链路，包括重启后持久化和登出失效。不能据此说已接通真实 SP-API。
