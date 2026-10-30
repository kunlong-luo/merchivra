# API-004 · 本地 Seller Account / Marketplace 配置与审计

**2026-10-30 · Pre-alpha / 初版实现**。仅允许已登录的当前租户 `ACTIVE OWNER` 创建**本地占位** Seller Account 和 Marketplace 配置；接口不接收 Amazon Seller ID、OAuth Token、SP-API Client Secret，也不调用 Amazon API。该能力用于验证数据库作用域和前端配置流程，**不代表生产授权或真实 Amazon 连接**。

## 实施内容

- Flyway **V4**：在 `seller_account` 增加 `display_name` 与 `setup_request_key`；`(tenant_id,setup_request_key)` 非空值部分唯一索引；新建 `local_setup_audit`，包含 tenant、操作人 UUID、动作、实体类型、实体 UUID 和时间（不存请求体或凭证）。
- 所有 POST 经 Spring Security **Session + CSRF Token** 防护。服务端通过 `TenantAccessService` 获取当前活动租户及身份，**不接受任意 tenantId**。所有数据库写操作与审计位于同一个事务。
- 新店铺使用服务器生成的 UUID 和 `LOCAL-` 前缀内部标识，状态固定为 `UNCONFIGURED`；客户端不能自行声明 `CONNECTED`、平台卖家 ID 或真实凭证。
- 账户创建支持 8–128 字符安全 `Idempotency-Key`。同键同参数重复执行返回已有账户，不重复审计；同键改变店铺名或 Region 返回 409。
- 添加站点时必须查询 **当前租户下的本地未连接店铺**，未知、外租户或非本地店铺返回相同的 404；重复添加同一站点不会新增记录和审计事件。
- 目前仅有三组开发期**预设**，并不意味着 G1 已选择这些区域作为正式首发：

| 区域 | 预设站点 ID | 币种 | 时区 |
|---|---|---|---|
| NA | `ATVPDKIKX0DER` 美国 | USD | America/Los_Angeles |
| EU | `A1PA6795UKMFR9` 德国 | EUR | Europe/Berlin |
| FE | `A1VC38T7YXB528` 日本 | JPY | Asia/Tokyo |

这些时区是当前**本地配置默认值**，不是后续 Amazon 来源时间或结算账期的正式定义。

## API

| 方法 | 路径 | 行为 |
|---|---|---|
| GET | `/api/v1/seller-accounts` | 当前租户的店铺列表，返回 `displayName/region/connectionStatus` |
| POST | `/api/v1/seller-accounts` | `{"displayName":"Test Store","region":"NA"}`，要求 `Idempotency-Key` 和 CSRF |
| POST | `/api/v1/seller-accounts/{accountId}/marketplaces` | `{"marketplaceId":"ATVPDKIKX0DER"}`，要求 CSRF |
| GET | `/api/v1/seller-accounts/audit` | 当前租户最近 30 次配置事件，不包含秘密字段 |
| GET | `/api/v1/seller-marketplaces` | 当前租户站点只读列表；创建后展示 `UNCONFIGURED` |

前端操作：在登录后的**核心业务数据**页面顶部使用“本地 Seller Account 配置”。先创建店铺，再添加相应区域的 Marketplace；页面下方可查看配置审计。站点创建后，Listing / 订单 / FBA 仍为空，因为尚未接入任何真实平台数据。

## 安全及验证界限

- 无真实卖家连接、授权流程、账号 Token 存储、定时同步、正式角色矩阵或完整审计框架。审计只覆盖**本地店铺/站点配置事件**，不是全站管理操作审计。
- 当前仍只允许全租户 Owner；未批准 G1/G2 前不要把开发期预设当成正式业务范围。**不要输入真实店铺凭证或真实订单/买家资料**。
- CI 仅验证 Java 单测、Vue/TypeScript、Vitest 和构建；未完成真实 PostgreSQL Flyway V1–V4 迁移、事务/并发竞争、Docker Compose、浏览器或 Amazon SP-API 实测。提交代码与 CI 通过不等于上线资格。
- 后续正式 Seller 接入需经安全审查：Amazon 授权、证书/密钥生命周期、细粒度 Seller Account ACL、最小权限、审计安全、撤销授权、速率限制、SP-API 数据契约等。

关联：[API-003 核心只读数据](core-read-api.md)、[SEC-003](../security/local-auth.md)、[DATA-004](../architecture/core-domain-foundation.md)、[Agent 开发策略](../../Agent.md)。
