# A1 · 首批 P0 容量与查询断点核查（V108，2026-11-08）

> 此文档是 **代码级静态审计结果**，不是“修复完成”或 PG18/真实 Amazon 验收报告。维护者批准按 [V108 上线计划](../roadmap/mvp-freeze-release-v108.md) 先完成 P0，再集中回归。本清单按对完整业务操作和审计造成的遗漏风险排序。

## 已核实的后端查询

| 模块 / URI | 代码证据 | 实际当前行为 | 优先级 |
| --- | --- | --- | --- |
| `GET /api/v1/purchasing/orders` | `PurchasingController.orders` → `PurchasingService.orders` | `WHERE tenant_id=? ORDER BY updated_at DESC,id DESC LIMIT 100`；第 101 条以后不能在 V107 采购单表格中出现，即使前端本地搜索/筛选也无效 | **P0** |
| `GET /api/v1/purchasing/suppliers` | `PurchasingController.suppliers` → `PurchasingService.suppliers` | `WHERE tenant_id=? ORDER BY supplier_code LIMIT 200`；旧的采购草稿供应商选择器和 V107 目录依赖这份数组，后续供应商可能不可选 | **P0** |
| `GET /api/v1/purchasing/warehouse-ledger?warehouseId=` | `PurchasingController.ledger` → `WarehouseInventoryService.ledger` | `WHERE tenant_id=? AND warehouse_id=? ORDER BY created_at DESC,id DESC LIMIT 100`；V107 明确仅展示最近 100 条，不能当完整仓库审计历史 | **P0** |
| `GET /api/v1/purchasing/warehouse-stock?warehouseId=` | `WarehouseInventoryService.stock` | 按 SKU 汇总本地库存，`ORDER BY s.internal_sku LIMIT 500`；库存 SKU 大于上限时页面不能代表完整本地库存，不能将未加载项当 0 | **P0** |
| `GET /api/v1/purchasing/pending-receipts` | `WarehouseInventoryService.pendingReceipts` | `ORDER BY o.order_number,l.id LIMIT 500`；第 501 条待收货记录缺少主界面入口 | **P0** |
| 本地分页公用工具 | `purchasing/LocalKeysetPage.java` | 已具备 limit 1–100、URL-safe opaque cursor 校验、`hasMore` 和稳定 page 打包；可以安全复用而不是重写 | **可复用** |

以上是代码中确切的 `LIMIT` 和调用点；未声称已审完所有采购附属模块或跨租户集成测试。

## A1 开发顺序与验收条件

1. **订单列表**：兼容旧 `/orders`，新增按租户授权、更新时间+UUID 稳定 keyset 的 `/orders/page`；支持服务端单号字面量搜索与状态过滤，校验游标和请求条件绑定，拒绝跨租户/跨状态/非法游标。前端使用 TanStack Query 缓存游标栈，隐藏当前列表本地过滤全量的误导提示，现有详情/审批/取消原样保留。
2. **供应商目录/选择器**：新增同范围的 `/suppliers/page`、`supplier_code,id` keyset，服务端 prefix/status 过滤，并解决采购草稿下拉框只能选择旧数组中供应商的问题；编辑所选供应商必须经服务端重新验证 ACTIVE/tenant。
3. **库存余额与待收货**：新增仓库作用域稳定排序的完整查询/分页，查清现有数量汇总的可信事实边界；不把不存在或尚未加载的 SKU 判断为已观察零。保留仓库操作时服务端重新核对余额、expected-on-hand 和原有幂等/CSRF。
4. **完整流水**：新增只读 `/warehouse-ledger/page`，以 `created_at,id` keyset 和 tenant+warehouse bound cursor，允许跨页查账；不可把 UI 的过滤当财务或法定审计快照，禁止自动累计/篡改已存在 signed delta。
5. **容量/安全定向测试**：每项至少 125 条（或超过当前硬上限），检验 50+50+25、非法/错仓/错过滤/错租户游标、历史来源/排序、未修改库存和审批。**属于立即应有的业务/租户安全负例**，不等 MVP 冻结才执行；随后 A3 再对最终候选集中跑 PG18/Chromium/Compose。

不改变 Java 25、Gradle、PostgreSQL 18、React/Next、Orval 及正式 Amazon 出站默认关闭的规则。数据库索引如需新建只允许前向 Flyway 迁移；禁止静默回改旧版本或使用 OFFSET 代替稳定游标。A1 代码完成后再标记 P0 问题关闭。

## 下一阶段的 P0 验证

- **Next 正式切换**：Next App Router 目前是可运行候选，正式 Vite/Nginx 容器尚未替换。Vite 回退、HTTPS same-origin、Session/CSRF、Next 反代、文件/上传和 OAuth 回调敏感重定向需要真实本地 HTTP 验证。
- **业务/数据验收**：现有 G1/G2/G3 核心审查未签署；Amazon Seller/Ads、真实银行来源和外部物流/供应商证据仍没有完整真实联调记录。所有证据在 [发布验收清单](release-readiness-v68.md) 内分级，禁止把 CI 的 Fake source 当真商家数据。
