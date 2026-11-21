# V114 — 全租户仓库目录选择与采购域 OpenAPI/Orval

2026-11-08，V113 main 基线。属于 [A1 Issue #62](https://github.com/kunlong-luo/merchivra/issues/62) 的容量/合同收尾，**不是 A3 完整 PG18 或真实 Seller 联调通过**。

## 仓库全库服务端选择

旧 `GET /api/v1/purchasing/warehouses` 使用 `LIMIT 200`，为兼容旧消费者继续保留，任何从其读取的数量都只代表本次返回的仓库，不是租户总数。

新增 `GET /api/v1/purchasing/warehouses/page?limit=50&status=ACTIVE&prefix=WH-&cursor=...`：

- 由 Session 鉴权提供 tenant，拒绝用户控制 tenant ID；`ACTIVE/ARCHIVED/ALL` 严格状态，编号安全字符前缀 0–60（`_` 是字面字符），按 `warehouse_code,id` ASC 排序，limit 1–100+1 探测 `hasMore`。
- `LocalKeysetPage` 6 段游标绑定版本、tenant、status、prefix、仓库编号、UUID。错误租户/过滤器/坏游标返回 400，禁止泄露其他仓库。
- `GET /warehouses/item?warehouseId=...` 用 Session tenant 按 ID 精确查询；不存在或外国租户返回 404，响应 no-store。归档行允许只读显示但不能在 `ACTIVE` 选择器用于写入。
- `WarehousePicker.tsx` 统一复用 TanStack Query、服务端仓库编号前缀搜索、前后页、精确选择校验。搜索/翻页取消前次选择；失效/归档/越权选择不会静默保留。

主工作台收货、库存查询、人工出库/盘点、仓间调拨都接入全库仓库选择；ASN、库位管理、已放行批次库存、批次生命周期和其他异常/对账/证据处理的仓库入口同样改用该组件。写入侧已有的 `requireActive`、仓库锁、CSRF、幂等、expected-on-hand 与来源不变量都不改变，仍由服务端复核。兼容只读仓库标签仍可能来自旧前 200 行，超出时退回 UUID，后续可替换为按 ID 精确标签。

## 正式 OpenAPI/Orval 入口

- `apps/web/openapi/purchasing-v114.json` 定义 V110–V114 的八项**只读**租户作用域采购页/精确查询，包含明确的 UUID 参数、筛选状态和源 DTO、`Page<T>` 返回形状。
- `apps/web/orval.purchasing.config.ts` 复用 Orval v8 的 `react-query` + `fetch`，和已有共享 `merchivraFetch`（Session 同源 Cookie、无缓存，POST/PUT/PATCH/DELETE CSRF）。`apps/web/src/lib/purchasing.ts` 的八个读取入口现在调用 **Orval 生成的请求函数**，并继续使用 Zod 对真实响应做 fail-closed 校验；写入暂留已有有保护的实现。
- 生成文件放在 `src/generated/purchasing-v114.ts` 与 `purchasing-models/`，**不手工维护**。与其他已签入的生成客户端不同，本部分由 `apps/web/scripts/ensure-purchasing-generated.mjs` 在 `npm run typecheck/test/build`、Next 对应命令及开发脚本前按照 OpenAPI SHA256 自动生成；新生成物在 `.gitignore`，可复现但**未签入 Git**。显式重建：`cd apps/web && npm run api:generate:purchasing`。现有 `api:check` 会运行购买域 Orval，但不能以未签入的内容作为 git-diff 漂移断言；类型检查/前端单测和原有签入客户端漂移仍在 CI 中。
- 本阶段的 OpenAPI 只针对上述八条**read API**。完整采购写入、供应商采购规则、ASN、质检、库位、库存批次等接口还未统一纳入 Orval，**不得称整个采购域全部完成生成迁移**。

## 测试与限制

- `WarehouseDirectoryPageTest`：225 仓库 50+50+50+50+25，201st 可发现、状态/字面编号前缀、非法/错 tenant 过滤游标、参数化与仓库精确查找。
- `warehouse-directory-v114.test.ts`：服务器分页 URL、no-store、精确仓库/404、旧数组/不一致游标拒绝。
- `scripts/smoke_warehouse_directory_v114.py`：**仅在 full_suite**、disposable PG18、localhost、Amazon 关闭的环境种入 225 仓库，并验证第 201 个仓库精确查找及人工 COUNT 写入后在该仓库精确读取到真实余额。**PR 快速 CI 不能替代本脚本已运行的证据。**

剩余 Issue #62：全采购域 OpenAPI/Orval 完整写入合同、旧标签兜底与 A3 PG18 HTTP 里程碑、Next 正式部署 Issue #63。GitHub Pages 合成只读展示不是 ERP 实际上线。
