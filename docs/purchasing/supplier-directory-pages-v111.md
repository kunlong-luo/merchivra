# V111 · 供应商目录全库分页与采购草稿第 201+ 供应商选择

> A1 / Issue #62 第二批 P0。**代码与快速单元测试已纳入本分支；正式 PostgreSQL 18 HTTP 冒烟需在显式 full_suite / MVP 集中里程碑中运行。** 不将未运行的数据库/浏览器测试标为通过。本轮不修改 Amazon 授权、采购审批/收货状态机或库存记账。

## 断点与实际修复

原 `GET /api/v1/purchasing/suppliers` 在 `PurchasingService.suppliers` 中强制 `LIMIT 200`；旧采购草稿、供应商规则输入和供应商目录共享这 200 条，导致第 201 条之后的供应商无法选择或发现。旧接口为兼容暂留，不得将其结果视为完整租户清单。

新增 `GET /api/v1/purchasing/suppliers/page?limit=50&status=ACTIVE&prefix=SUP-&cursor=...`：

- **服务端完整检索**：查询均使用 Session 的 `TenantAccessService.currentTenant`，不提供可伪造的 tenant 参数。过滤 `ALL/ACTIVE/ARCHIVED` 和供应商编号 `prefix`；prefix 去首尾空格、转大写，只接受 0–60 位安全字符，按**字面**前缀比较，不将下划线识别成 SQL 通配符。
- **稳定游标**：按 `supplier_code ASC,id ASC`，每次读取 `limit+1`（limit 为 1–100）。Base64url 游标绑定版本、tenant、状态、前缀与最后编码及 UUID；不同租户/状态/前缀、错误格式一律 400。编码可解，不是授权凭据，也不是强一致审计快照。
- **响应**：`{items,nextCursor,hasMore,pageSize}`，`Cache-Control: no-store`。未返回全库 count；不能用当前页行数推断全库数量。
- **现有数据库约束**：`erp_supplier` 的 `UNIQUE(tenant_id,supplier_code)` 已可保证编码唯一；本轮不盲目添加 Flyway 索引。生产级高基数数据下需 PG18 EXPLAIN/容量验证，再决策索引调整。

## 前端真实入口

- `SupplierDirectoryTable`：服务器编号前缀检索、状态过滤、上一页/下一页，过滤器变化清除游标；TanStack Query 键包含 tenant、status、prefix、cursor，TanStack Table 只管理当前页呈现。
- `SupplierPicker`：复用在**采购草稿**和**供应商 SKU 规则编辑/创建**。仅显示当前已验证页的 ACTIVE 供应商，允许搜索与翻页，搜索/翻页会清空之前的选中项。查询错误时不把旧数据作为可选择记录；已归档供应商不能被选取。
- **写入再验证**：`PurchasingService.create` 仍执行 `tenant_id + id + status='ACTIVE'` 检查；采购规则后端原有校验保持有效。现有 Owner 权限、Session/CSRF、Maker/Checker 与收货事务不变。编辑历史供应商采购规则时，必须重新选原供应商，避免将规则错误写给他方。
- 旧供应商列表暂保留做兼容 API。订单行和采购计划的**只读名称辅助映射**仍可能只知道第一页的名称，因此无匹配时保留供应商 UUID，而不是错误显示其他供应商；完整关联名称需后续按后端关联 DTO 改进，**不影响第 201+ 条的写入选择**。

## 测试与结果边界

- `SuppliersPageTest`：225 条模拟供应商跨 5 页（50+50+50+50+25）、重复 ID、前缀参数绑定、错租户/错过滤游标、非法上限和状态，以及创建草稿所需的 ACTIVE 租户校验。**属于 Mockito 单测，不是实际 PostgreSQL 查询验证。**
- `suppliers-page-v111.test.ts`：Zod 页契约、真实 `/suppliers/page` URL、status/prefix/cursor 参数、拒绝旧数组与 `hasMore`/cursor 不一致数据。
- `scripts/smoke_supplier_pages_v111.py`：仅一次性 localhost PG18 + MOCK 授权关闭场景；SQL 创建 225 条 ACTIVE、1 条 ARCHIVED，HTTP 跨页查询、服务器前缀/状态/错租户游标、`no-store`、使用第 201 个供应商 HTTP 创建本地 DRAFT、拒绝 archived 供应商。已挂到 `.github/workflows/ci.yml` `full_suite`，常规 PR 不启动此重型用例。
- 本次不修改 `docs/api/openapi.json`：它标明自身是 **G2 未实现设计稿**，不是采购运行契约。本端点使用与 V110 相同的前端 Zod 运行时校验；采购模块整体生成式 Springdoc/OpenAPI + Orval 接入属于仍需跟踪的合同治理缺口，不能将其误写为已生成。

## 下一步（Issue #62 保持开启）

1. `warehouse-ledger LIMIT 100` → 全历史、租户+仓库+时间+UUID keyset，保留原始正负数量、原因及业务来源 ID。
2. `warehouse-stock LIMIT 500`、`pending-receipts LIMIT 500` → 后端完整选择、服务端检索与分页；未知/未加载库存绝不能伪装成 0。
3. 继续使用 V108 快速 PR CI，风险升级时定向校验；全部 P0 完成后再运行里程碑 PG18/Next/Compose/Chromium。真实 Seller 授权最后由维护者本地完成。
