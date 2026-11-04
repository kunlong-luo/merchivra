# V91 · FBA 本地货件/内部 SKU/采购的跨模块全量选择入口

**基线：V90，2026-11-04。** V90 修复了补货和财务订单首 50 条操作选择器。本阶段继续针对真正会产生业务草稿、映射、成本或本地库存流水的界面审计，发现：

1. `FbaInboundPanel` 建立本地 FBA 草稿时，每个 Seller SKU 行仍从旧 `fetchCoreListings(marketplace)` 前 50 条下拉框选择。51+ 的 Listing 即使在 V87 核心列表可见，仍无法通过 UI 建立 FBA 草稿。
2. `SkuCatalogManager` 原来只读取前 100 个内部 SKU，只能维护/映射这 100 个；`SkuCostManager` 接收同一局部列表并隐式默认选择第一条，存在错误成本对象的操作风险。
3. `PurchasingWorkspace` 原有内部 SKU 下拉框也只加载前 100 条，导致更后创建的商品无法从界面加入采购草稿或本地库存操作。

这些是真实代码断点，而不是缺少 Amazon SP-API、本地 SQL 表或需要新搜索服务。优先复用既有 API，不重复开发领域模型。

## 修改范围

- **FBA 本地草稿**：每个草稿行复用 V90 `CoreMarketplacePicker`，通过 V87 `GET .../listings/page` 完整 Seller SKU 前缀检索与 50 条游标分页；多行分别缓存和核验，换搜索或翻页清除对应行的 SKU，继续保留 1–100 行、SKU 不重复与正整数数量校验。后端 `FbaInboundService.create` 在写入前仍验证每条 SKU 确实存在于 Session 当前 tenant/Marketplace 的原 Listing。**只能创建本地 DRAFT，不能自动调用 Amazon、发货、变成实际在途、入仓或可售库存。**
- **内部 SKU**：直接复用现有 Owner-only `GET /api/v1/catalog/skus?prefix=...&after=...&limit=100`。该 API 自带 `starts_with(lower(internal_sku),lower(?))` 的大小写不敏感**字面量前缀**和 `internal_sku ASC` keyset。没有新增 migration、索引、Redis、Elasticsearch 或重复 SKU 表。新增共享 `InternalSkuPicker`：显式前缀搜索、最多 100 条/页、上一页/检查下一页、当前页明确选取的 SKU UUID；当请求出错、目标不在当前已加载页、搜索或翻页时清理旧选择。默认只允许 ACTIVE SKU，ARCHIVED 不会成为新映射、历史成本和库存操作的有效目标。
- **内部 SKU 主数据工作台**：可以检索并浏览远超前 100 条的完整主数据，跨页修改名称/归档/恢复并维护现有 Listing 的历史版本映射。当前 Listing 仍来自 V87 指定 Marketplace 的服务端已加载页，映射写入仍由 `ProductCatalogService.mapListing` 重新验证当前租户、Listing 以及商品 ACTIVE 状态。
- **SKU 历史成本**：改为先明确检索与选择 ACTIVE 内部 SKU，不再把 UI 第一个商品静默选为默认成本对象。提交仍复用 `SkuCostService.add` 原币/生效日版本规则，不能据此推断真实结算或净利润。
- **采购与本地库存**：采购明细与仓库库存操作的 SKU 字段复用共享全库选择器，允许选择第 101+ 个 ACTIVE 商品。保留 PO DRAFT/人工提交/异人审批、库存余额 `expectedOnHand` 与业务理由原服务端验证；仅选择商品不会产生库存移动、PO 或 Amazon FBA 入仓。提交后物料仍按 UUID 保存，并在本地草稿行保留当前选中的 internalSku 标签，不会把未加载的 SKU 列表当完整商品字典。
- **兼容性与边界**：其他旧接口 `fetchSkus()` 不变，仍被采购的供应商计划和已放行批次等现有面板引用；这些页面的旧列表仍需后续单独审计，V91 不谎称全部 ERP 的所有 100 行限制都已清除。现有内库库存流水/货件历史界面还有独立 100/500 行展示上限，不能把最近 N 条记录称为全账本。

## 回归及安全门禁

Java 25 + Spring Boot 4.1.2 / Gradle、React19 TypeScript6、Zod4、TanStack Query、Vitest、PostgreSQL18/Docker 原技术栈，无新依赖。Vitest 的纯函数契约验证：

- 130 个内部 SKU 只能在前 100 + 后 30 页取到第 130 个；`after` 必须是最新实际 internalSku，不是猜测的 UUID；满 100 只能提示可能还有下一页，不能宣称总数完整。
- 已归档 SKU 不可成为主动映射/采购/成本目标；未在当前页/请求失败的旧选择不能用作新写入。
- GET 只采用 Owner Session 同源、无浏览器可伪造 tenantId，反复搜索使用后端 SQL keyset 而非把 130 个 SKU 一次塞进下拉框。

PostgreSQL18 CI 的 `scripts/smoke_fba_catalog_purchasing_full_selection_v91.py` 在 V90 模拟数据之后运行：

1. 插入 130 个纯虚构 ACTIVE 内部 SKU：真实 HTTP 分两页取到 100+30，精确取第 130 个，大小写前缀、字面量通配符、无效 limit/after 的 400 均验证。
2. 从 V87 的 64 条 Listing 穿越前 50、后 14 页选择第 64 条；将其人工绑定到第 130 个内部 SKU，检查真实 ACTIVE 映射版本与服务端返回值；再为第 130 个商品追加一条原币历史成本。
3. 使用第 64 条 Seller SKU 创建 **本地 MOCK FBA DRAFT**，再次确认 `amazonVerified=false`、`fbaStockCredited=false`；另一个 Marketplace 试图引用该 Listing 应被拒绝。
4. 使用第 130 个内部 SKU 创建本地采购单草稿，保持 DRAFT 和未收货，核对没有因选 SKU 而自动审批、增加仓库流水或 FBA 可售观测。
5. 严格限制本机 PostgreSQL18 demo 数据库 + Seller OAuth/SP-API 双关闭；上述 SQL 插入仅用于可丢弃 CI，不能复制到真实数据库。

## V92 建议

继续把仍保留局部商品列表的**供应商采购计划、货位/批次管理**及**FBA 货件历史 100 条**等实际操作限制逐一核对；涉及财务、库存与批次历史时不能用客户端无限加载或把分页界面当审计冻结快照。真实 Amazon Seller/Ads 授权按原约定，核心功能完成后才在用户本地逐项联调。
