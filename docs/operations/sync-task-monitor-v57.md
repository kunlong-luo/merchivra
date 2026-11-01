# V57 · 按租户隔离的后台同步任务监控

## 范围
在上一阶段 Redis 8 原子配额、429 退避和 PostgreSQL 租约调度基础上，为已登录 Owner 提供**只读**的任务队列健康状况。部署仍使用 Java 25 / Spring Boot 4.1.2 / Gradle 9.2.1 / PostgreSQL 18 / React 19。无需真实 Amazon 授权。

## 安全 API
- GET /api/v1/operations/sync-tasks?kind=ALL&state=ALL&limit=50
- tenantId **永远从服务端认证 Session 与 TenantAccessService.currentTenant 推导**，HTTP 请求没有 tenantId 参数，也没有跨租户运营端点。
- 查询各表时独立 `WHERE tenant_id=?`，来源为 orders_sync_job、spapi_catalog_sync_job、spapi_finances_sync_job。三种功能即使仍默认关闭，该 GET 仍可返回当前租户已保存的历史作业；不会自动开启轮询、自动授权、重新同步或远程写操作。
- kind 支持 ALL、ORDERS、CATALOG_LISTINGS、CATALOG_FBA、FINANCES；state 支持 ALL、READY、RUNNING、RETRY、WAITING_RETRY、PAUSED、SUCCEEDED。非法筛选拒绝，不拼接用户 SQL。
- 返回任务状态、数据来源 MOCK/LIVE、站点 UUID、已提交页数、重试次数、下次重试时间、最后失败代码、更新时间和租约是否过期。绝不 select next_token、refresh_token、lease_owner、Seller ID、订单原文或 Amazon 响应体。
- 统计是当前租户所有任务的总数和状态分类；列表分页按最近更新降序，最多 100 条，使用 limit+1 计算 hasMore，避免误报全量数据。
- 仅 Owner 工作区显示该面板；服务端继续使用 `currentTenant` 的 Owner 权限而不是仅依赖 React 隐藏。
- API 返回 `Cache-Control: no-store`，前端 Zod 校验响应，认证失效和权限不足显示错误而不是假装任务为空。

## React 页面
- 进入「运营自动化」，在异常待办下方查看「后台同步任务监控」。
- 支持筛选和手动刷新，但不自动访问 Amazon、提交下一页或重试。过期的租约只报告状态，不自动回收。
- 面板默认显示 50 条，`hasMore` 为 true 时告知存在更多匹配记录，不将“没有任务”误解为“Amazon 已同步”。

## 验证与下一阶段
- Java 单测：安全 SQL 模板不含秘密字段，三张表独立租户过滤，非法参数在 SQL 前拒绝；Controller 仅获取 Session 租户并设置 no-store。
- React Vitest：DTO 结构校验、同源 Cookie、不暴露租户请求参数和 403 错误。
- CI：Java 25 Gradle + PostgreSQL 18 Flyway + React 19 测试/构建 + Mock Compose。
- 后续：新增受限运维级别的队列水位趋势（以 tenant 授权隔离），以及其他已审核官方 SDK 操作的逐方法配额保护；不要公开跨租户全局指标，也不要绕过授权。
