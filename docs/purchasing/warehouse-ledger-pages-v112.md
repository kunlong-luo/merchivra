# V112 — 本地仓库库存流水全历史分页

## 状态与范围

基于 V111 main，新增 `GET /api/v1/purchasing/warehouse-ledger/page`。原
`GET /warehouse-ledger` 保持兼容但仍只返回最近 100 条。此变更**不**
调整库存结余、收货质检、FBA/FBM、盘点/调拨写入或真实 Amazon API。

- `warehouseId` 为必填 UUID，仓库读取由认证 session 的 tenant + 仓库联合约束，越权/不存在返回 404；没有可传入的 tenant 参数。
- `limit` 默认 50，范围 1–100；`movementType` 可为 `ALL` 或现有流水类型；
  `search` 是内部 SKU 的大小写不敏感**字面包含**搜索（至多 80 字符），
  PostgreSQL `POSITION` 防止 `_`/`%` 通配。
- `(m.created_at,m.id) DESC` 稳定 keyset；limit+1 探测 `hasMore`，
  游标绑定版本、tenant、warehouse、movementType、search、最后时间与 UUID。
  错仓/错租户/错筛选/非法游标返回 400，不回退到未过滤列表。
- 响应为 `{items,nextCursor,hasMore,pageSize}`，`Cache-Control: no-store`。
  保留正负 signed quantity、收货/人工操作/质检/批次处理来源 ID、原因；
  空页及未查到的 SKU **不等于本地库存 0**。
- Flyway **V82** 添加索引 `(tenant_id,warehouse_id,created_at DESC,id DESC)`。
  只读分页不会改变账本，连续分页是实时视图而非财务快照；
  并发插入可能改变后续浏览结果，审计快照应单独实现。
- React 使用 TanStack Query 的 tenant+warehouse+type+search+cursor 查询键，
  Base UI 类型筛选、内部 SKU 服务端搜索、TanStack Table 及
  `CursorPageControls`。切换仓库/租户时重新创建分页组件；
  上一页通过保存的游标栈返回，不把单页记录描述成完整账本。

## 测试证据及未运行项

- `WarehouseLedgerPageTest`：125 条跨 3 页（50/50/25），时间戳相同的 UUID
  顺序、签名数量、错租户/错仓/错类型/错搜索/非法 cursor、跨仓库读取 404、
  参数化 SKU 字面搜索。
- `warehouse-ledger-page-v112.test.ts`：no-store、请求条件及业务来源、
  拒绝旧数组及不一致的 nextCursor。
- `scripts/smoke_warehouse_ledger_pages_v112.py`：**仅**限 disposable PG18、
  localhost 和 Amazon 全关闭；先通过合法本地仓库/SKU 创建 125 条有源流水，
  校验负数和最终结余、同时间戳翻页、旧兼容接口、游标隔离及 no-store。
  接到 `ci.yml` 的 `full_suite`，**提交本批 PR 不等于运行过此 HTTP 验收**。

## 剩余 P0

Issue #62 仍未关闭：`warehouse-stock` 与 `pending-receipts` 各自存在 500 条
固定上限，仓库列表也存在 200 条旧上限；统一 OpenAPI/Orval 迁移需视现有
契约边界推进。Next 正式切换 Issue #63 未完成；无真实 Amazon 授权写入、
无正式生产部署批准。下一批优先处理仓库库存完整读取与未知余额语义。
