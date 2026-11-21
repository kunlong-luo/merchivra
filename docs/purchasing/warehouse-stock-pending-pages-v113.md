# V113 — 仓库余额与已审批待收货全量分页、精确余额核验

> 2026-11-08，A1 / Issue #62。V110–V112 分别覆盖采购订单、供应商目录、仓库流水。本阶段消除工作台中库存余额与待收货明细被旧 **LIMIT 500** 截断的问题。老版接口仍保留，**不代表旧接口解除上限**。只读本地记录不等于 Amazon FBA/FBM 可售库存。

## 后端合同

| 接口 | 授权范围与行为 |
| --- | --- |
| `GET /api/v1/purchasing/warehouse-stock/page?warehouseId=...&limit=50&search=...&cursor=...` | Session tenant + 指定仓库，仓库无权限/不存在 404；先从已发生库存流水及待检批次构造 SKU key 集合，用 `(internal_sku,product_sku_id) ASC` 执行 1–100 条+1 游标，再针对当页 SKU 进行历史采购收货与带符号流水汇总。服务端内部 SKU 字面包含搜索 `POSITION`，不是 `LIKE` 模糊通配。游标绑定 tenant、warehouse 和 search。 |
| `GET /api/v1/purchasing/warehouse-stock/item?warehouseId=...&productSkuId=...` | **真正精确**查询任意此租户 ACTIVE SKU 的特定仓库余额。已验证存在且无记录返回显式零；不在当前列表页、非 ACTIVE、错仓或不存在等情况不能臆造零，后者返回 404。操作前读取此接口并要求成功，提交写入时现有 `WarehouseStockOperationService.post` 再在锁内核对 expectedOnHand、库存及 lot position，保留 CSRF、幂等和审批行为。 |
| `GET /api/v1/purchasing/pending-receipts/page?limit=50&status=ALL&search=...&cursor=...` | Session tenant；`APPROVED/RECEIVING` 及 `ALL`，支持采购单号或内部 SKU 字面搜索，`(order_number,line_id) ASC` keyset。服务端先聚合既有收货再 `HAVING ordered>received`；返回同一原币单位采购价、已收与剩余数量，不把待收货说成已发货/真实在途。 |
| 旧 `/warehouse-stock`、`/pending-receipts` | 保留原数组兼容，仍最多 500 条；**新工作台不再消费它们**。 |

统一 `{items,nextCursor,hasMore,pageSize}`，`Cache-Control: no-store`，limit 为 1–100。搜索只接受 0–80 位 `A-Z0-9_-`，大小写统一后按字面匹配。游标为 URL-safe opaque 数据绑定版本、tenant、仓库/状态、search 和最后排序列；错租户、错仓、错筛选或非法内容一律 400。页面只提供 live read，**不提供事务冻结的库存/财务快照**；并发写入会影响下一次刷新。

Flyway V83 增加 APPROVED/RECEIVING 采购单复合条件索引和仓库待检批次 SKU 索引；移动流水原有 V39 SKU 汇总索引和 V82 时间索引不变，无历史重写或新基础设施。

## 前端改造

- `WarehouseReadPages.tsx`：复用 TanStack Query、TanStack Table `ScopedTable`、Base UI Select 和 `CursorPageControls`；服务端全仓 SKU 字面检索、全租户待收货状态/订单号检索和上下页游标。
- `PurchasingWorkspace` 保留原收货、审批、库存人工操作、选择器与确认弹窗。操作数量的基准改为 `fetchWarehouseStockItem` 的精确观测；未知、请求失败、正在加载或无权限一律**禁止**人工盘点/出库/调拨；新选择重新查询，并在提交时由服务端事务检查 expectedOnHand。
- 当前页记录数量不能表示全部库存量/订单数。精确观测为零是可用事实；没有观测是 UNKNOWN，尤其不能因前 500 条缺失而默认零。
- 仍存在独立仓库目录的旧 `LIMIT 200`；若大量仓库超过该值，则仓库选项仍不全，需要下一批改造。未因本次分页改造声称仓库选择已经全量。

## 证据与后续

- JUnit `WarehouseReadPagesTest`：525 个不同仓库 SKU、525 条未收采购行各跨 11 页（50×10+25）；错 tenant/仓库/status/search/游标、服务端字面检索参数化、真实查到零与不存在的 404 语义。
- Vitest `warehouse-read-pages-v113.test.ts`：参数、no-store、API 合同，拒绝旧数组和不一致游标，精确查询不存在不能变零。
- `scripts/smoke_warehouse_read_pages_v113.py`：创建 525 个带业务操作来源的仓库库存 SKU 与 525 条已审批待收货明细，验证第 501 个 SKU、显式零/404、已收/剩余聚合、分页游标；只允许 **localhost、disposable PG18、Amazon 全开关禁用**。仅接入 `ci.yml` 的 `full_suite` / A3 集中验证。普通 PR 只运行 Gradle Java 测试、TypeScript/Vitest 等快速检查；**未执行 PG18 HTTP 之前不能声称其通过**。

剩余：Issue #62 的 OpenAPI/Orval 采购接口归一、仓库目录 200 上限及全链真实 PG18 验收；Issue #63 正式 Next 切换、A4 真实 Amazon 授权与 G1/G2/G3 生产准入尚未完成。项目仍默认 Vite/Nginx 正式打包；Pages 只是只读合成展示站。
