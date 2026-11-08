# V114 — 本地仓库完整目录与采购分页 OpenAPI/Orval

> 2026-11-08 / A1 Issue #62。源代码实现与前端包合同是本地采购业务；
> 不涉及真实 Seller/Ads OAuth、远程 SP-API、FBA/FBM 可售库存或生产部署批准。

## 1. 仓库目录与选择入口

后端保留 `GET /api/v1/purchasing/warehouses` 旧兼容数组（最多 200 条），
新增：

- `GET /api/v1/purchasing/warehouses/page`：
  `limit=50` (1–100)、`status=ALL|ACTIVE|ARCHIVED`、
  `prefix`（仓库编码 0–60 位大小写不敏感**字面前缀**）和 `cursor`。
  使用 `(warehouse_code,id) ASC` keyset；令牌绑定版本、认证 session tenant、
  状态、标准化编码前缀与最后编码/UUID。错租户、状态、过滤、格式返回 400；
  参数化 SQL `LEFT` 不将 `_` 视为 LIKE 通配。
- `GET /api/v1/purchasing/warehouses/item?warehouseId=`：
  仅在认证租户内精确查询仓库；不存在/越权 404。两种新读取
  都禁止 HTTP 缓存（`Cache-Control: no-store`）。
- 共享 `WarehousePicker.tsx`：服务器 ACTIVE 目录搜索、上页/下页、
  精确验证旧选择/新建仓库、目的仓排除源仓、筛选/翻页清除旧选择。
  已替换采购收货、库存盘点/调拨、库位、批次可用库存、批次调拨、
  库位对账、异常、处置证据和来源凭据等实际仓库下拉入口。
- 库存人工操作只接收由认证 tenant+仓库精确核验为 ACTIVE 的源仓及目标仓；
  原 V113 精确 SKU 余额、事务重校验、幂等、CSRF、批次约束、Owner 权限不变。
  直接 API 写入仍由 Spring 后端权限和状态机校验，并非依赖 UI 保护。
- 旧 `warehouses` 数组还被部分辅助面板消费，**仅用于不完整的仓库名称
  显示和旧只读上下文**；未知编码展示 UUID，不声称完整仓库数量。
  不把第 201 个仓库当不存在。

## 2. OpenAPI / Orval 生成边界

基于 V110–V114 已审查的实际 Controller + DTO，新增
`apps/web/openapi/purchasing-pages.json`，包含采购单分页、供应商分页、
仓库目录页与精确仓库、仓库流水、库存页与精确 SKU、待收货分页，
以及供应商/仓库创建共 **10 条**实际路由。

`apps/web/orval.config.ts` 及 `orval.purchasing.config.ts`
使用已有 Orval 8 + TanStack Query + same-origin `merchivraFetch`。
编译时生成的 `src/generated/purchasing-pages.ts` 和 33 个模型文件
均已作为真实 Orval 产物提交（不是手写假冒生成文件）。前端
`lib/purchasing.ts` 的新旧分页读取及创建仓库/供应商调用转向生成的
请求函数，保留 Zod 运行时校验，`npm run api:check`
继续执行完整生成漂移检查。

**有意未涵盖的接口**：采购审批/取消/收货写入、
人工出入库/调拨操作和全部采购附属模块的 OpenAPI/Orval 合同仍沿用现有
Spring Session/CSRF 手写 Zod 请求。这些需要按真实 DTO 与授权状态机
逐个迁移，不能因 10 条路由已生成而宣称“全项目采购 API 100% 迁移”。

## 3. 验证与未执行项

- `WarehouseDirectoryPagesTest`：225 个仓库 50+50+50+50+25，201 号可达，
  错租户/状态/前缀/令牌拒绝、参数化 `_` 字面前缀、精确读取隔离。
- `warehouse-directory-v114.test.ts`：真实生成客户端包裹的同源 no-store
  请求、页合同、精确仓库及错误处理。
- `scripts/smoke_warehouse_directory_v114.py`：只在 disposable
  localhost PG18、Amazon 全开关关闭情况下插入 225 个真实本地仓库；
  第 201 仓可作为本地 COUNT 操作目标，ARCHIVED 仓被原有写入校验拒绝，
  并验证错租户/过滤/游标、no-store、旧 200 上限。
  脚本接入 `ci.yml` 的 `full_suite`，**普通 PR 不运行本脚本**；
  A3 集中回归之前不能将 PG18 HTTP/并发验收记为通过。

Issue #62 的核心仓库目录缺口已形成代码实现，但采购全域生成合同、
A3 完整 PG18/Next Mock 回归尚未完成。Issue #63 Next.js 正式部署仍需
真实 session/CSRF、反向代理与回退验证，Pages 继续仅为只读合成展示。
