# TEST-004 · 纯虚构 SP-API 全链路模拟联调

> **2026-10-30 / Pre-alpha。仅开发环境、只使用虚构事实。** [Compose 全链路成功运行（2026-10-30）](https://github.com/kunlong-luo/merchivra/actions/runs/41061679196)：真实 Docker Compose（Nginx + Java 25/Spring Boot 4.1.2 + PostgreSQL 17）已成功启动并实际执行本页列出的全部 Python HTTP 验证；后端重启后重新登录，记录仍持久，退出后 401。没有连接 Amazon。 这是对 [INT-002 操作矩阵](../integrations/sp-api-operation-matrix.md)、[INT-003 Canonical Contract](../integrations/amazon-data-contract.md) 和 [SYNC-002 同步流设计](../architecture/sync-stream-contract.md) 中可安全模拟的部分做纵向联调；不是正式 Amazon SDK/HTTP 响应测试，也不证明权限已批准。

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

## 已运行的验证证据与明确尚缺

- 可测：匿名 API 401、登录/CSRF、Local Seller 创建/幂等/区域、固定 Demo 隔离、US/DE 模拟分页、已入库数据的查询、来源标识、订单部分退款、FBA null/0、失败/成功/重复/冲突、审计计数、重启持久化、登出。
- 不能因此宣称已接通：真实 Amazon Seller 授权或凭证、真实 Orders/Listing/FBA DTO 与分页重试、限流/token 过期恢复、同步 Worker 的租约与 checkpoint、Finances/成本/汇兑/利润/结算、生产 RBAC/安全审计/备份。
- G1/G2 业务与安全决策仍 Proposed。**运行环境测试成功与生产就绪是两个完全不同的里程碑。**

## 首次完整 Compose 联调发现并修复的缺陷

1. **根目录 `.env` 读取**：原命令 `docker compose -f deploy/compose.yaml` 没有正确加载根目录密码；使用显式 `docker compose --env-file .env -f deploy/compose.yaml` 后服务可以启动。此调整已同步 README、开发说明和安全指南。
2. **跨站点复用内部 SKU 的 FK 失败**：同一个虚构 `MV-CUP-BLUE` 在 US/DE Seller SKU 映射时，内部 SKU ID 必须按 `tenant_id + internal_sku` 而不是 `tenant_id + marketplace_id + internal_sku` 派生。将 Mock `product_sku` 改为租户级稳定 ID、Listing/Order/Inventory 继续保持 Marketplace 作用域后重新运行全链路通过。
3. **权限缓存清理**：登录/退出时清除 `core`、`setup`、`sandbox` 等前端 Query 缓存，防止上一个会话的租户内容被下一个用户看到。

**验证结果：[Compose 全链路成功运行（2026-10-30）](https://github.com/kunlong-luo/merchivra/actions/runs/41061679196)：** 完整 Compose 构建成功，实际脚本先后输出通过 Demo 隔离/幂等、US/DE 站点、Mock Orders 分页、核心表查询、财务权限 `NOT_AUTHORIZED`、库存 null/0、审计记录、后端重启持久化及登出后的 401；销毁临时服务和数据库成功。**此为可重复的模拟集成验证，不是浏览器 E2E，也不是正式 Amazon API 授权/数据准确性证明。**

## V6 · 手动逐页持久检查点（开发期验证）

原 `POST /api/v1/demo/sandbox/{marketplaceUuid}/replay` 是**整批单事务**，保留用于快速演示。V6 另外新增一条逐页协议，用来验证断点恢复：

| 方法 | 路径 | 行为 |
|---|---|---|
| GET | `/api/v1/demo/sandbox/{marketplaceUuid}/pages` | 返回当前 Mock 流状态 `NOT_STARTED / RUNNING / SUCCEEDED`、下一页号、总页数、已提交页面列表、单调增加的 `leaseEpoch` |
| POST | `/api/v1/demo/sandbox/{marketplaceUuid}/pages/advance` | 提交**下一页**；Header `Idempotency-Key` + Session/CSRF；每次调用恰好一个 PostgreSQL 事务 |

V6 `sandbox_page_stream` 对 `(tenant_id, seller_marketplace_id)` 唯一约束，`sandbox_page_commit` 对页面序号和幂等键分别唯一；业务写入、page commit 和进度的条件更新发生在同一事务。流锁使用 PostgreSQL `SELECT ... FOR UPDATE NOWAIT`；并发的分页请求不得将旧 `leaseEpoch` 覆盖进度。相同幂等键返回原页号，已完成后**不能以新键继续产生不存在的页面**。

具体行为：美国站 2 页，每次一页；第 1 页同时保存 Listing 和第 1 笔订单，第 2 页保存第 2 笔订单、FBA 观测和最终成功审计。德国站只有 1 页，所以一次提交完成。所有事实都继续标记 `MOCK`，不存在真实 Amazon `paginationToken` 和有效期。

**重要边界**：这里是**人工点击/HTTP 请求驱动的 PostgreSQL 事务锁协议**，不是 Worker 租约/租期续约、分布式调度、真实高水位、Token 过期处理或带版本的 Canonical Source Adapter；它不使用基础表里的正式 `sync_stream` / `sync_run` 推进生产同步流。固定样本规模改变会返回 409，需要以后受控创建新版本流，而不是直接重置老检查点。

可通过已有手动 Compose Workflow 验证 **第 1 页提交 → 后端重启 → 第 2 页继续 → 同键重新提交不重放 → 查询两页**，不增加日常 CI/E2E 门禁。
