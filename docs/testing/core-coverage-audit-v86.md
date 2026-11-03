# V86 · P0/P1 核心业务闭环审计与订单全量分页

日期：2026-11-03。审计基线：[PR #35 / V85 main](https://github.com/kunlong-luo/merchivra/commit/551e713c2acee55e0daa6b5d155764d573e9ea39)。本报告按 `docs/requirements/mvp.md`、`functional.md` 与 `traceability.md` 对照**当前真实代码与既有模拟 CI 覆盖**，而不是把早期 Roadmap 中的“未实现”视为最新事实。

## 判断口径

“有代码”≠“模拟测试通过”≠“本地真实 Amazon/银行联调”≠“生产发布验收”。已知的 V85 PostgreSQL18 HTTP + Java/React/Docker CI 绿灯仅覆盖虚构、自建数据；所有 Seller/Ads OAuth 默认关闭，因而下述模块**均不能因为 V86 审计而宣称已通过真实接口验收**。产品 G1/G2/G3 决策仍未签署；多币种 FX、财务收入确认与净利润口径仍需由业务负责人批准。

状态说明：**代码+模拟覆盖**表示代码已进入 `main`，在既有 CI/测试脚本中存在覆盖；**局部闭环**表示有本地操作和来源追溯，但平台外部确认尚无实测；**待补关键点**明确后续优先项。审计没有模拟或伪造真实 Amazon 上游数据。

## P0 需求追踪（FR-001）

| FR | 实际服务/代码证据 | 当前代码/Mock 结论 | 尚缺或不可宣称 |
|---|---|---|---|
| SYS-01/02/03 | `security/AuthController`、`LocalTeamService`、V15/V16、V84/V85 权限、V68 HTTP | Session/CSRF/本地自举、Owner/只读成员与相关业务权限均有代码及模拟覆盖 | 生产 MFA/SSO、集中凭证/KMS、正式权限审查 |
| SHOP-01/02 | `spapi/SellerOauthController`、`PrivateSellerController`、`SellerVerificationController` | 公有/私有 Seller 授权状态及 Seller v1 角色审核门禁代码已存在 | 本地真实授权、角色/API scope、Token 撤销与限流验证 |
| CAT-01/02/03 | `catalog/ProductCatalogController`、`catalogsync/CatalogSyncController`、`MarketplaceReadController` | SKU、Listing、人审映射及只读同步/Mock 数据 | Listing 核心页仍仅取前 50；未完成全库过滤/分页；真实 Catalog API 验收 |
| ORD-01/02/03/04 | `orderssync/` Worker、`catalog/MarketplaceReadController`、`order_item`、V23/V35 | 订单/订单项、来源重放及履约/退款/财务状态分列；**V86 新增订单服务端前缀搜索与基于来源时间+UUID的游标分页** | 真实订单角色/分页/限额与大数据高并发；真正全局一致性快照；全量订单导出 |
| INV-01/02/03 | `catalogsync/` FBA 观测、`MarketplaceReadController.inventory`、V22/V69 库存规则 | UNKNOWN/ZERO/STALE 独立，实际来源时间、低库存建议；本地 FBA 货件/V65 | 核心 FBA 库存页仍只取最多 50 SKU；真实 FBA Inventory/Inbound 来源验收 |
| COST-01 | `catalog/SkuCostController`、`finance/OrderCostReadinessService` | 时间生效的成本、货币、无成本阻断；逐订单来源回溯 | 原始供应商/结算/物流费用真实性需人工核对 |
| FIN-01/02/03 | `finance/FinanceController`、来源和退款人工审核、`ProfitReportJobController`、V58–V64 | 财务事件/归因/结算区分，V64 整期订单候选集、逐项贡献、持久化和安全 CSV | 银行/Ads 真 LIVE、税务与 FX、全局同点快照、正式净利润批准均**未完成** |
| SYNC-01/02/03 | `orderssync/`、`spapi/` 只读/限流适配、`operations/SyncTaskMonitorController` | 持久化 Stream/Run/Lease/429 及重试、任务监控；纯虚构数据断点与重放覆盖 | 真实 SP-API 接口、生产高并发、不同区域/角色与故障演练 |
| DASH-01 | `CoreWorkspace`、财务 V61/V64 贡献页/CSV、Ops 工作台 | 站点查询与原币估算，财务整期报表可导出；订单核心页 V86 翻页 | Listing/FBA 全页检索、跨站点多币种禁止直接相加、可信全局管理汇总/导出 |
| MOCK-01 / API-01 | `scripts/smoke_mock_chain.py`、`scripts/smoke_http_security_v68.py`、API Session/Orval、PG18 Compose | 可丢弃假数据回放、CSRF/租户隔离/重放测试、OpenAPI 驱动的部分客户端 | 正式 G1/G2 业务签署、浏览器全旅程覆盖、所有模块契约一致性审计 |

## P1/P2 与发布 gate

| 范围 | 已实际实现的核心能力 | 外部/运营 Gate |
|---|---|---|
| PO/供应商/仓储 | V20、V39–52、V71–73 本地 PO→异人审批→ASN→分批到货→质检/库位/批次→差异/退货、采购来源证据 | 真实供应商履约/仓储物理库存验收；不可把本地收货说成 FBA 入仓 |
| FBA 货件/在途 | V65、V75–V85 本地货件、PO/ASN 关联、身份/差异复核、Ops 人工异常与审计 | Amazon FBA Inbound/承运商的实时验证未执行 |
| Ads | V66 离线 Profile/每日费用、双 Owner 复核及本地归因 | 独立 Ads OAuth 与 Ads Reports 真实来源未接通 |
| 补货自动化 | V22/V70/V71/V74 库存与 28d 订单历史观测、供应商 MOQ/箱规与人工 DRAFT  | 混源/缺失/UNKNOWN 阻断；不能宣称 AI 需求预测或自动采购 |
| M4 发布 | PG18/Docker/Gradle/React CI、V68 Mock 备份恢复演练 | 生产端到端 Seller 测试、KMS、TLS、压测、RPO/RTO、签审尚未完成 |

## V86 实际解决：P0 订单读取容量缺口

旧 `GET /api/v1/seller-marketplaces/{marketplaceId}/orders?limit=50` 保持兼容，不影响已有调用；新接口：

`GET /api/v1/seller-marketplaces/{marketplaceId}/orders/page?limit=50&search=ORDER-PREFIX&cursor=...`

- 当前登录 Session 解析真实 tenant/actor，沿用 V16 Owner 和精确 `OPERATOR_READ` seller_account 授权；跨租户/其他站点返回 404，匿名 401。不会接受客户端的 tenantId、Seller 密钥或买家 PII。
- `search`：最多 80 个可打印字符的 **订单号前缀**，大小写不敏感，SQL `LIKE` 通配符 `%`、`_`、反斜杠都需按字面量转义。空字符串表示当前站点全部已入库订单，而不是仓储/财务全表检索。
- 排序：`source_updated_at DESC NULLS LAST, id DESC`。分页采用与排序对应的 PostgreSQL 两列 Keyset 比较，`-infinity` 只作查询中 NULL 时间的**内部游标排序哨兵**，不是业务时间、不会向 API 输出伪造时间。返回本页 1–100 条，额外读取一行判断 `hasMore`。
- `nextCursor` 是 URL-safe Base64 编码的站点、归一化搜索串、末行来源时间（可空）和 ID。它只是定位，不是凭证；跨站点/变更搜索/无效游标拒绝 400，原始授权必须每页重新执行。浏览器页码以实际遍历的页数计；上页用已保存的历史游标，不用 OFFSET。
- 结果 `items` 复用原 `OrderView`，保留 `itemsObservation` 未观测与真正已观测的区别，以及独立履约/退款/财务状态。新接口 `Cache-Control:no-store`，浏览器仅显示当前页真实记录。读期间新订单/来源时间改变可能影响下一页位置，所以**不是同一时刻的冻结完整数据集、不能用作审计/结算导出**；财务快照仍用已有 V64 任务。
- React 核心页订单分栏改成明确提交的全站点订单号前缀搜索 + 上/下页，只有后端确认的 `nextCursor` 才能进入下一页，切站点和重新搜索清空旧游标与订单项详情。Listing/FBA 的旧本页搜索不会被虚假标为全库搜索。
- 测试：Java 单测限定查询/参数与越界；Vitest Zod/API 参数/数据质量；V86 PostgreSQL18 HTTP 脚本向可抛弃的 V76–V85 模拟站点加 64 条假订单，检验 50/14 分页、空时间戳、游标错站/错搜索、恶意值、通配符按字面量、来源状态、无库存写入。最终验收仍以 GitHub Actions 成绩为准。

## 下一步：按优先级补齐核心功能（不是再扩展异常工作台）

1. **V87（P0）**：Listing 全量服务端分页、Seller SKU/ASIN 过滤与确认映射引用；保持映射版本和来源状态，不把未知 SKU 丢失。
2. **V88（P0）**：最新 FBA 库存每 SKU 的全库分页与来源质量、观测时间过滤；不将 UNKNOWN 自动转为零。
3. **V89（P0）**：综合销售/库存/财务数据质量和导出入口，优先复用 V64 已有的持久化快照/CSV；原币展示，禁止无汇率依据相加。
4. **最后真实联调（用户本地）**：Seller/Ads 角色与 OAuth，Orders/FBA/Finances Live 只读及分页/429/撤销，银行来源可信度，签署 G1/G2/G3 和安全发布 gate。CI 模拟成功不替代这一阶段。

上述缺口来自实际请求/页面实现，并非在缺真实 Seller 授权前就宣称缺少全部核心代码。
