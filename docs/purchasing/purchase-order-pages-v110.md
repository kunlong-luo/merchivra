# V110 · 本地采购订单服务器全库游标分页（A1 / Issue #62 首批）

> **开发范围与事实边界：** 本轮只修 `/api/v1/purchasing/orders` 的读取前 100 条导致的“找不到旧采购单”问题，不扩大为供应商/库存/流水分页已完成，不启用 Amazon LIVE 或远程下单。旧 `GET /orders` 原样保留供兼容调用，仍然只返回前 100 条；新版页面不再消费此旧结果。采用项目原有 `LocalKeysetPage`、Spring Session/CSRF、TanStack Query、Zod 和 TanStack Table，不新引入框架。

## 当前实际接口

`GET /api/v1/purchasing/orders/page?limit=50&status=ALL&search=PO-&cursor=...`

- **访问控制：** 不接受用户传入 `tenantId`，服务端通过 `TenantAccessService.currentTenant` 读取有效租户；SQL 每次均带 `WHERE tenant_id=?`。没有修改采购审批、收货、取消与 Maker/Checker。
- **过滤：** `status` 为 `ALL` / `DRAFT` / `SUBMITTED` / `APPROVED` / `RECEIVING` / `RECEIVED` / `CANCELLED`。单号 `search` 允许 0–80 位大写英数字、下划线和连字符（输入会 `strip` + uppercase）；SQL `POSITION(? IN UPPER(order_number))>0` **参数绑定**，非 LIKE 通配符拼接，也不按旧浏览器已加载记录过滤。
- **游标：** 读取 `limit+1` 条以计算 `hasMore`，`limit` 1–100，稳定 `ORDER BY updated_at DESC,id DESC`，请求携带可逆但不含凭据的 base64url 版本/租户/状态/搜索/最后时间及 UUID。重复请求、错误 tenant/状态/单号、格式错误均拒绝，而不是退回第 1 页；业务 SQL 再次按照当前 Session 作用域过滤。游标不提供防篡改签名，不可被理解成授权令牌或可移植审计快照。
- **数据格式：** `{items: PurchaseOrder[],nextCursor: string|null,hasMore:boolean,pageSize:number}`，每行保持原有原币、状态、负责人和本地更新时间。成功响应 `Cache-Control: no-store`。
- **客户端：** `PurchaseOrdersTable` 在组件内以 `tenantId/status/search/cursor` 为 TanStack Query 键，单号查询由服务端执行，状态切换/单号提交重置游标栈，提供上一页/下一页；表格内的确认、审批与明细调用保持原实现。无“全库总条数”数据时绝不从当前页猜测总数。切换 tenant 后组件按 tenant key 重新挂载，旧游标不可沿用。

## 已新增的验证

- **快速 Java 单测**：`PurchaseOrdersPageTest` 用 125 条订单模拟 50+50+25 页访问，含重复 ID 检查、参数绑定、跨 tenant/状态/查询游标错用、非法时间/上限/非法查询等。**这些只检查代码侧约束，不等于真实 PG18 运行。**
- **快速前端测试**：`purchasing-page-v110.test.ts` 验证 Zod page 合约和服务端查询参数；禁止无意回退成旧 `/orders` 数组读取。
- **完整 PG18 HTTP 冒烟（仅手动/里程碑）**：`scripts/smoke_purchase_orders_page_v110.py` 在一次性数据库插入 125 个完整本地 PO，校验 50+50+25、状态/精确单号全库过滤、旧接口兼容、无缓存及伪造 tenant/filter 游标 400。已经挂到 `.github/workflows/ci.yml` 的 `full_suite`，**普通 PR 跳过**；需在 [V108 完整里程碑入口](https://github.com/kunlong-luo/merchivra/actions/workflows/milestone-validation.yml) 后核实真正 PG18 运行结果，不能提前声称已通过。

## 不变的剩余 P0

- 供应商目录/采购草稿下拉框：旧 `suppliers LIMIT 200`，待 `/suppliers/page`、供应商全库选择器。
- 本地仓库历史：`ledger LIMIT 100`、`warehouse-stock LIMIT 500`、`pending-receipts LIMIT 500`，待 server keyset 与 tenant/warehouse 负例。列表没查到 SKU **不等于**余额为零。
- 本轮不增加真实会计应付、采购付款、Amazon FBA 收货、供应商自动下单或导出。相关候选见 [BENCH-001 开源 ERP 源码对标](../architecture/open-source-erp-benchmark-2026-11-08.md)，需 G1 与真实源审核。
- 当前 `erp_purchase_order_recent` 已有基于 `tenant_id,updated_at DESC,id` 的索引。需在大样本 PostgreSQL 18 **EXPLAIN** 后判断是否要新增 `id DESC` 组合索引，而不是盲目追加迁移；若新增只能前向 Flyway。

## 验收标准与限制

1. 真实登录 Owner 可以搜索第 101–125 条本地采购单、查看详情并提交/取消符合原权限的记录。
2. 100+ 页无重复、无漏行；状态/单号筛选确实由服务器执行，错误租户/过滤条件游标 400。
3. 既有 Owner/CSRF/双人审批规则未改；分页不读取真实 Amazon Seller、FBA 或银行账户，不把 Mock 说成 LIVE。
4. **分页不是强一致审计快照**：翻页之间采购单可能更新导致排序变化，需用户刷新复核；有法律/财务含义的审计应依赖不可变事件台账而不是活动订单页。
5. #62 是包含采购、供应商和仓库的**总 P0**，本轮完成订单部分后仍保持 Issue 开启，不直接标记整个 P0 完成。
