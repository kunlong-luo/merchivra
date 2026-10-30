# TEST-004 · 纯虚构 SP-API 全链路模拟联调

> **2026-10-30 / Pre-alpha。仅开发环境、只使用虚构事实。** 这是对 [INT-002 操作矩阵](../integrations/sp-api-operation-matrix.md)、[INT-003 Canonical Contract](../integrations/amazon-data-contract.md) 和 [SYNC-002 同步流设计](../architecture/sync-stream-contract.md) 中可安全模拟的部分做纵向联调；不是正式 Amazon SDK/HTTP 响应测试，也不证明权限已批准。

## 为什么需要这条链

以前的 `/api/v1/demo/sync-runs` 只把全局 Mock Fixture 投影进**固定虚构租户**。用户登录后新建的本地 `UNCONFIGURED` 站点仍然为空，无法从模拟读取一路验证当前登录租户的 Listing/Order/FBA 查询。因此新增**手动触发、仅开发模式启用**的 Sandbox 回放入口。

## 模拟的操作语义

| 模拟操作 | 实现 | 明确限制 |
|---|---|---|
| `searchOrders`（Orders v2026-01-01） | 本地 US Fixture 两页（每页一单），DE 一页；保留外部订单号、履约、退款、财务未观测 | Token `MOCK-PAGE-1` 只是模拟页标记，**没有真实 token 过期、限流、永久 checkpoint 或 Worker** |
| `getListingsItem` / Listing 数据 | US 两条（有映射/无映射），DE 一条；独立 Seller SKU/内部 SKU | 假 ASIN 与固定虚构 SKU，非官方 DTO |
| `getInventorySummaries` | US 可售 12 和 UNKNOWN(`null`)，DE 已观测可售 0 | 不推测缺失字段为 0；不跨 Marketplace 加总 |
| `listTransactions`（Finances） | **`NOT_AUTHORIZED`，不写入任何财务事件或结算事实** | 模拟环境没有 Finances role，不伪造财务 API 或利润 |
| 本地账号/站点配置 | Session Owner + CSRF + 服务端 tenant scope 创建 `LOCAL-` / `UNCONFIGURED` 记录 | 无真实 Seller ID / OAuth / SP-API 密钥 |
| 模拟同步 | 在同一个 PostgreSQL 事务写入幂等 `sandbox_sync_run`、结构化 Listing/订单/订单项/FBA、局部配置审计 | 不是真实后台调度、Lease fencing 或生产级追踪 |

V5 Flyway 新增 `sandbox_sync_run` 和业务事实的 `data_origin`（`MOCK` 或 `UNVERIFIED`）。模拟回放只写入显式 `MOCK` 来源，正式查询 API 和 Vue 工作台都会显示来源标志。旧表行在迁移后标记为 `UNVERIFIED`，它不等于已认证真实 Amazon 数据。

## API（所有写操作要求登录 Session + CSRF）

| 方法 | 路径 | 请求 |
|---|---|---|
| POST | `/api/v1/demo/sandbox/{marketplaceUuid}/replay` | Header `Idempotency-Key`；JSON `{"simulateFailure":false}` |
| GET | `/api/v1/demo/sandbox/{marketplaceUuid}/runs` | 当前租户/Marketplace 最近 20 次模拟运行 |

使用条件：后端 `MERCHIVRA_DEMO_ENABLED=true`，当前 Owner 租户内 `LOCAL-` + `UNCONFIGURED` 的美国/德国站点。日本站明确返回 422，因为**没有虚构业务样本**；跨租户/不存在的 Marketplace 返回 404。重复键同参数返回已存在运行，改参数返回 409；模拟失败写入可重试失败记录但不会污染商品/订单/库存。重试须新任务键。

## 使用方式

仓库根目录准备自己的本地 `.env`，至少设置 `MERCHIVRA_DB_PASSWORD`、`MERCHIVRA_BOOTSTRAP_USERNAME`、`MERCHIVRA_BOOTSTRAP_PASSWORD`（至少 16 位）：

```bash
docker compose --env-file .env -f deploy/compose.yaml up --build -d
# 前端 http://localhost:3000 → 登录 → 核心业务数据 → 本地 Seller Account 配置
# 新建美国或德国本地店铺 → 添加站点 → 点击「模拟拉取并入库」
# 然后查看同一 Marketplace 的 Listing、订单、FBA 和模拟运行记录。
```

可使用仓库里的**标准库 Python** 复核脚本（**会向本地临时数据库写入纯虚构测试数据，不适用于已有业务数据的环境**）：

```bash
set -a
. ./.env
set +a
python3 scripts/smoke_mock_chain.py --stage initial
```

脚本只接受 `http://127.0.0.1:*` 或 `http://localhost:*`，防止误操作远程/生产地址。若要验证容器重启后存活，可先保留脚本的 `--state` 文件，重启 Server 再执行 `--stage restart`。

GitHub Actions 可手动运行 [Mock full-chain Compose validation](https://github.com/kunlong-luo/merchivra/actions/workflows/mock-full-chain.yml)，该工作流**不随日常代码变更执行**，只在显式触发或工作流文件自身更改时执行。它会生成一次性随机密码、构建 Nginx/Java/PG、运行全链路、重启后复核、最后销毁临时数据库。

## 当前验收与明确尚缺

- 可测：匿名 API 401、登录/CSRF、Local Seller 创建/幂等/区域、固定 Demo 隔离、US/DE 模拟分页、已入库数据的查询、来源标识、订单部分退款、FBA null/0、失败/成功/重复/冲突、审计计数、重启持久化、登出。
- 不能因此宣称已接通：真实 Amazon Seller 授权或凭证、真实 Orders/Listing/FBA DTO 与分页重试、限流/token 过期恢复、同步 Worker 的租约与 checkpoint、Finances/成本/汇兑/利润/结算、生产 RBAC/安全审计/备份。
- G1/G2 业务与安全决策仍 Proposed。**运行环境测试成功与生产就绪是两个完全不同的里程碑。**
