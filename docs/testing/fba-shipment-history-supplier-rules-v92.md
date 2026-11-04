# V92 · 完整本地 FBA 货件历史与供应商规则全量 SKU 选择

基线：V91 `main`，日期 2026-11-04。继续审计跨模块的“可读取记录 ≠ 可从业务操作页选择”的 P0 断点。V91 已解决本地 FBA 草稿的 50 条 Listing 选择与产品目录的首 100 SKU；V92 查明两个仍存在的问题：

- 原 `GET /api/v1/fba/marketplaces/{marketplaceId}/shipments` 的 `FbaInboundService.list` 固定按创建时间降序 `LIMIT 100`，界面只能从这 100 个本地货件选择并继续人工入仓、差异复核与采购来源关联；旧货件虽然可通过 ID 查询详情，但操作人员无法从列表定位。
- `SupplierPurchasePlanningPanel` 的内部 SKU 选择仍依赖 `PurchasingWorkspace` 原先传来的首 100 条数组，导致第 101+ SKU 虽可在 V91 创建本地采购草稿，却无法在界面配置 MOQ、包装倍数、参考交期和原币供应商采购规则。

## 复用与新增功能

**本地 FBA 货件历史**：新开只读 `GET /api/v1/fba/marketplaces/{marketplaceId}/shipments/page?limit=50&search=V92-&state=ALL&cursor=...`。默认 50、上限 100，使用原 `fba_inbound_shipment` 的不变 ID 和创建时间，`ORDER BY created_at DESC,id DESC` 及 `(created_at,id)<(?,?)` keyset。货件编号为最长 80 个可打印字符的大小写不敏感**字面量前缀**；SQL 的 `%`、`_`、`!` 都有转义。状态仅允许 `ALL,DRAFT,IN_TRANSIT,RECEIVING,REVIEWING,RECONCILED`。下一页游标包含 Marketplace、前缀、状态、来源时间、UUID；变更 Marketplace/前缀/状态后旧游标 400，不能跨授权使用。每次都重新通过 `TenantAccessService.currentTenant` 和 `requireMarketplace` 做租户/站点 Owner 校验；匿名 401、未知/跨站点 404。响应 `items,nextCursor,hasMore,pageSize,searchPrefix,state`，HTTP Cache-Control no-store。

原 V65 的 `/shipments`（最多 100）继续保持兼容；新分页不修改原货件、状态机、入仓事件、采购来源映射或 Amazon SP-API。Flyway V81 只增加两个 PostgreSQL18 索引：`(tenant,marketplace,created_at DESC,id DESC)` 和 `lower(reference)` 的 B-tree 前缀表达式索引。属于本地自建货件事实，不可称 Amazon 真正接单、核验入仓或 FBA 可售库存。

**Web**：FbaInboundPanel 改用完整历史查询，允许按本地货件编号/状态检索全部历史并在 50 条页面之间导航。每次更改查询/翻页会取消已选择的旧货件 ID；用户须重新点击本页货件，后端原详情/人工入仓/差异对账 API 继续校验其状态和 Marketplace。Spring MVC 新 GET 操作被纳入 V92 的 10 个操作 `normalize-fba-openapi.mjs` + `apps/web/openapi/fba-inbound.json` + Orval 生成校验；前端实际请求仍使用现有 Orval 的安全 `merchivraFetch` 同源 Session/CSRF 传输，加 Zod 分页断言，不自行传 tenant/授权 token。

**供应商规则**：复用 V91 `InternalSkuPicker` 和已有 Owner `GET /api/v1/catalog/skus?prefix&after&limit=100`，为第 101+ ACTIVE 内部 SKU 配置/修改本地规则；编辑旧规则时必须从服务端重新选中**同一** SKU UUID 才能保存，避免换页或旧数据让“编辑规则”变成意外给另一 SKU 新建规则。供应商身份、ACTIVE 商品与 SKU、原币、MOQ、倍数、交期仍由原 `SupplierPurchasePlanService.setRule` 核验；不自动触发采购、报价变更或审批。原供应商规则清单 `LIMIT 500` 仍如实标识为局部历史展示，不宣称显示全部历史；完整规则检索将另行补齐。

## 验证矩阵

CI `scripts/smoke_fba_local_history_supplier_sku_v92.py` 在 V91 已有本地 Mock 数据后执行，强制 PG18 localhost、Demo 和 Seller OAuth/SP-API 双关闭：

1. 生成 125 个虚构本地货件（一个 IN_TRANSIT、其余 DRAFT），HTTP 50+50+25 遍历，UUID 不重复，最旧第 125 个仍可按原详情 API 读取，未生成 Amazon FBA 来源核验或可售库存。
2. 状态 DRAFT 共 124 个并分页；IN_TRANSIT 精确一条；前缀不将 SQL `%`/`_`/`!` 当通配符；换状态/前缀/站点的游标拒绝 400/404；匿名 401；旧 GET 仍受 100 条上限兼容。
3. 使用 V91 第 130 个 ACTIVE SKU 和原有虚构供应商，调用原供应商规则 POST 创建/编辑 MOQ 与包装倍数，同一 `supplier_id + product_sku_id` 没有重复规则，并检查规则审计；未自动生成采购单。
4. 前后 FBA 源观测行数、本地仓库库存流水和采购单数不因选择、分页或新增本地货件而改变。Java25/Gradle、React19/TS6/Vitest/Vite8、PG18/Flyway V81、Docker Compose 四项 CI 最终全部通过后才合并。

## 仍需核查的缺口（V93 优先）

- 原供应商规则清单上限 500、计划/建议各 500 条可能隐藏更早记录。要按实际规则/计划键、授权与业务审批边界设计服务端过滤及分页，不直接取消 SQL 限制。
- 部分货位/批次页面依赖收货单/库位的局部查询（包括最近 100/500 条的库存流水）；需要逐个分析库存核对是否把局部结果错误地当作完整总量。不能用浏览器无限拉取替代可信账本查询。
- 真实 Amazon OAuth、SP-API、Ads/银行接入仍然**关闭**，按用户要求等核心功能完成后由用户本地授权联调。
