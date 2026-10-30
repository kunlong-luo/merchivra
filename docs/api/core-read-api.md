# API-003 · 会话租户约束的核心只读 API（已实施初版）

**2026-10-30 / Pre-alpha，技术功能已提交**。所有接口从 **V2 PostgreSQL 结构化业务表**读取，不读取 `demo_snapshot`，不调用 Amazon SP-API。当前仅通过服务端 Session、ACTIVE Tenant Membership + 全租户 OWNER 获得读权限。**尚未通过真实 PostgreSQL / Docker Compose 集成验收，不可作为已获生产授权的 API 使用。**

## 已实现的接口

| 方法及路径 | 读取内容 | 授权范围 |
|---|---|---|
| `GET /api/v1/seller-marketplaces` | 当前租户的 Marketplace、Seller 账号、区域与币种 | ACTIVE OWNER；最多 100 个 |
| `GET /api/v1/seller-marketplaces/{marketplaceId}/listings?limit=50` | Listing + 当前 ACTIVE 内部 SKU 映射 | 服务端先验明 Marketplace 归当前租户，再按 `tenant_id + seller_marketplace_id` 查询 |
| `GET /api/v1/seller-marketplaces/{marketplaceId}/orders?limit=50` | 订单 ID、履约/退款/财务状态、订单项数量、更新时间 | 同上；不推算销售/财务/利润 |
| `GET /api/v1/seller-marketplaces/{marketplaceId}/inventory/fba?limit=50` | 各 Seller SKU 最新 FBA 观测及质量 | 同上；库存 UNKNOWN 返回 `null`，明确零为 `0` |

- 所有资源接口都默认必须登录，未经认证由 Spring Security 返回 401。
- 如果某 `marketplaceId` 不属于当前活动租户，返回与不存在相同的 **404**，不泄漏其他租户实体是否存在。
- `limit` 限定 **1～100**（不合法返回 400），当前默认 **50**。只返回有序前 N 条，**没有分页游标**；前端明确写出仅展示前 50 条，不得将其称为全部历史数据。
- 订单状态独立读取，不将平台未观察到的退款或结算误报为零。库存按 `seller_sku_raw` 选择最新可用观测；不做库存累加。
- 查询不允许客户端指定 `tenantId`；后台根据 `TenantAccessService.currentTenant(auth, request)` 获取，随后 SQL 在读列表时再次绑定 tenant ID。
- Listing 的 `internalSku` 仅取已有的当前 ACTIVE 映射，不把 ASIN/Seller SKU 自动当 ERP 内部 SKU。
- 页面入口：登录后左侧“**核心业务数据**”。切换 Marketplace 和 Listing/订单/FBA 标签页，保留独立的“经营总览/订单/商品/库存/模拟同步”纯虚构 Mock 导航。
- 新建的真实登录租户通常**没有 Seller Marketplace 数据**；此时显示正确空状态。固定虚构 Demo Tenant 的结构化测试数据**不应被映射到真实登录租户**。

## 安全约束与目前缺口

- 当前 `OWNER` 仅按**整个租户**授权，员工角色和店铺范围角色未上线；后续必须在真正的 Seller 授权流程前落实 [SEC-002](../security/tenant-enforcement.md)。
- 已有 [API-004 本地未连接 Seller Account 配置与局部审计](local-seller-setup.md)，但未实现**真实 Amazon Seller 注册/授权**、OAuth/SP-API、生产凭证加密、退款财务事实、全站审计或完整同步 Worker；**不能接入真实客户或外部 Seller 数据**。
- 未加入新的无鉴权 HTTP 路由、未改变 Mock Tenant；仅新增 **Owner 本地占位配置** POST，所有业务来源订单/商品/库存仍只读。
- 已做 Maven 单测中的租户作用域与无界分页负例，前端 Zod/Vitest 的响应契约检查；未执行数据库级读取、容器部署和 E2E。
- 正式上线前必须在独立 PG 租户夹具中执行跨租户 Marketplace 隐藏、访问撤销、多个 Marketplace 分页与库存 UNKNOWN 的真正集成验收。

**下一步**：在真实 PostgreSQL 验证 V4 本地店铺/Marketplace 配置与局部审计，并补充真正的游标分页；在数据来源与业务决策通过前不实现虚构 Amazon Adapter 的生产写入。

代码位置：`apps/server/src/main/java/org/merchivra/erp/catalog/MarketplaceReadController.java`、`apps/web/src/CoreWorkspace.vue`、`apps/web/src/lib/core.ts`。

关联：[SEC-003 Session](../security/local-auth.md)、[DATA-004 核心表](../architecture/core-domain-foundation.md)、[TEST-003 轻量 CI](../testing/ci-policy.md)、[Agent.md](../../Agent.md)。

## 2026-10-30 一次性运行态验证补记

[一次性 PostgreSQL 17 冒烟验证](https://github.com/kunlong-luo/merchivra/actions/runs/41059134533) 已在临时 PostgreSQL 17 验证 Flyway V1–V4、当前租户站点列表和陌生 Marketplace 查询 404（以及本地店铺配置链路）。该验证范围有限，**不能**代替复杂订单/Listing/FBA 带数据夹具的租户间 SQL 集成验证、完整分页、Docker Compose 或正式授权审查。

## V5 模拟来源标记与受保护查询

Listing、订单及 FBA 读结果新增 `dataOrigin`（`MOCK` / `UNVERIFIED`）。可在明确启用 Demo 的本地 `/api/v1/demo/sandbox/{marketplaceId}/replay` 中，按当前登录租户的 LOCAL/UNCONFIGURED Marketplace 模拟写入固定纯虚构数据；随后仍只使用本 API 的会话租户过滤读取。见 [TEST-004](../testing/mock-full-chain.md)。`UNVERIFIED` 并不代表已通过真实来源认证。
