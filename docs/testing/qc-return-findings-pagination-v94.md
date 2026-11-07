# V94：质检退货与异常工作台的全量可处理记录分页

## 真实缺口

V93 完成实际收货、质检和独立放行批次的分页后，下游仍有两处固定上限：

1. `PurchaseQualityService.returns()` 对一个质检单的供应商退货记录限 100 条，前端只能对这 100 条执行手工发出/取消草稿；遗漏第 101 条之后的可操作草稿。
2. `PurchaseQualityService.findings()` 对整个租户的质检待办限 100 条，影响后续记录认领、标记已处理和重新打开。
3. 前端用当前已加载 `returns` 数组计算预留，再从当前页收货推导 `heldQuantity`；当退货超限、检验来自全局异常入口、或收货已翻页时，余额可能错误或变成零。此类额度不应由部分页面数据决定。

## 复用与实施

使用既有 `LocalKeysetPage`、`JdbcTemplate`、`TanStack Query`、`Zod` 和 `CursorPageControls`，**没有新增数据库/组件基础设施**：

- `GET /api/v1/purchasing/quality/inspections/{inspectionId}/returns/page?limit=50&cursor=…`：`(created_at DESC,id DESC)` 稳定游标，租户 + 检验 ID 双范围校验，已验证的检验归属才显示记录。
- `GET /api/v1/purchasing/quality/inspections/{inspectionId}/returns/summary`：数据库对整笔检验聚合 `rejectedUnits`、`draftedUnits`、`dispatchedUnits`、`unallocatedUnits`；取消草稿不占额度。无论浏览哪个页面，合计始终是全量。API 读取失败时 UI 禁用新建退货草稿；写入事务继续校验数据库实际剩余数量和幂等键。
- `GET /api/v1/purchasing/quality/findings/page?filter=ACTIVE|ALL&limit=50&cursor=…`：`(changed_at DESC,id DESC)` 稳定游标，绑定租户和筛选；`ACTIVE` 仅显示 OPEN 和 ACKNOWLEDGED，`ALL` 包含 DONE/CLEARED。
- 单页 1–100、默认 50，`limit + 1` 判定下一页，返回 `items/nextCursor/hasMore/pageSize`，`Cache-Control: no-store`。旧版 `LIMIT 100` JSON 数组接口保留兼容，但不再供操作面板消费。
- `PurchaseQualityPanel` 的退货列表和异常列表各自具有上一页/下一页游标栈；切换状态筛选或检验对象时清除旧页游标。操作完成后查询缓存刷新；异常操作返回第一页以避免本页因排序/状态变化而误以为没有待办。
- 质检单退货详情移动到全局待办可以到达的界面区域；**没有选采购单时**也可以从对应异常打开实际检验及其退货记录。界面不把 `DRAFT` 当实际发出，也不把 `DISPATCHED` 当承运商签收或供应商退款。
- 操作仍由真实 Session/Owner + CSRF 控制；不自动发货、不自动采购、不调用 Amazon。

页游标是实时查询，不保证跨写入的快照一致性。正式金额、库存或审计不可直接依赖「逐页相加」作为冻结总账。

## PostgreSQL 18 Mock 测试

CI 在 V92/V93 隔离 Fixture 之后执行 `scripts/smoke_quality_returns_findings_v94.py`：

- 创建一笔额外的 V46 本地真实收货，登录 Owner 通过 HTTP 质检 220 件，其中 200 件拒收，另一 Owner 放行门禁仍存在；没有可用库存入账。
- 添加 125 条退货（70 DRAFT / 35 DISPATCHED / 20 CANCELLED），验证老接口仅有 100 条，而分页完整 3 页。整笔汇总：拒收 200、草稿 70、发出 35、未分配 95。
- 在**超过旧上限 100 条**的草稿上，通过实际 HTTP 发出与取消，检查审核记录、汇总变化及库存不变；新增草稿并测试幂等重放。
- 使用隔离 SQL Fixtures 插入 65 条额外质检与 130 条异常，分 3 页遍历；在最后页的真实发现 ID 上执行 `ACKNOWLEDGED` 和 `DONE`，验证追加的两条不可覆盖处理审计及 ACTIVE/ALL 正确区分。
- 校验未登录访问、错误租户范围的检验/游标、错误筛选与页长的拒绝；确认无额外采购单、库存流水或远端 Amazon 写入。

安全环境要求为 PG18 localhost disposable、`MERCHIVRA_SPAPI_ENABLED=false`、`MERCHIVRA_SPAPI_OAUTH_ENABLED=false`、`MERCHIVRA_SPAPI_ALLOW_WRITES=false`。

## 遗留与后续

旧版 API 为兼容保留上限，新增使用方应该调用分页接口。其他领域中的固定行数仍需要逐个核对是否影响人类操作或只读概览，避免无目的全量重写。既存独立 `Optional Chromium responsive UI smoke` 在 V16 只读员工数据点超时，需另行排查；不能用采购 Mock 通过冒充浏览器 UI 验收。
