# V61 · 店铺 / 单 SKU 贡献利润来源预览

## 模块目标与数据契约

V61 复用 V32–V36 的 `OrderCostReadinessService` + `OrderContributionService` 审核门禁，新增只读 **Marketplace 当前页与单一 SKU 可追溯贡献估算**、订单来源排除状态、有限期间筛选与安全 CSV 导出。**不是净利润、净销售额、已结算利润、银行到账或正式账簿。**

仅使用 `sales_order.data_origin='LIVE'` 的订单，按 `order_time` 过滤 UTC 起始日（包含）和结束日（不包含）。必须先通过日期有效 SKU mapping/历史成本检查，再通过订单与真实 Finances 事件人工绑定审核及分类完成度检查；收入和 signed Amazon/FBA fees 仅来自已审核 LIVE Finances 交易，不采用 Orders 商品预计金额或 Reports 结算金额。

只有**整个订单所有当前商品都被映射到同一个内部 SKU**、订单历史成本齐全、该订单没有需要额外验证的退款/调整项、交易绑定和分类齐全，才可把已有订单级贡献作为单 SKU 来源预览。多 SKU 订单在订单清单保留 `MULTI_SKU_UNALLOCATED`，**不按商品数量、订单额或比例猜分平台费用**；不完整来源行的 sales/fees/cogs/contribution 为 null 而不是零。

公式（仅满足所有门禁的订单，按原币）:

`estimatedContribution = signedSales + signedAmazonFees + signedFbaFees - provisionalCOGS`

Amazon/FBA 费用保留官方来源符号，不对负费用二次减法。多币种禁止汇总；币种来自所选 Marketplace。退款和需核验的成本回转一律阻塞；广告手动参考在单独指标中提示 `MANUAL_UNVERIFIED`，**不纳入上述贡献公式，亦不可当作真实 Amazon Ads API 支出**。

## HTTP / React 19

- `GET /api/v1/finance/marketplaces/{marketplaceId}/sku-profit-preview?after=YYYY-MM-DD&before=YYYY-MM-DD&offset=0`：仅 Session Owner，必须同一 Tenant/Marketplace；UTC 日期窗口 1–92 天、不能请求未来（不含结束日可到明天）、单页 30 单、offset 按 30 对齐且上限 3000。
- `GET .../sku-profit-preview/export.csv`：复用同一 preview 快照逻辑，不进行二次金融计算，导出**当前页** CSV，不会导出隐私字段、令牌、任意 SQL 或未核实的利润。CSV cell 使用双引号转义和公式字符前缀防 spreadsheet injection，且 Response 为 no-store。
- React FinanceWorkspace 加入“店铺 / SKU 贡献利润预览”，支持 UTC 起止日、分页、仅当前页内部 SKU 本地筛选、原币汇总、逐单排除状态和当前页 CSV 导出。显示“手动广告来源独立”“不是净利润”“金融来源未完整审核”标记。

## 来源完整度与运营注意

- `totals.scannedOrders`、`eligibleOrders`、`blockedOrders`、`multiSkuOrders`、`sourceEvents` 仅针对当前页。按 SKU 组汇总也只汇总当前页合格订单，不代表整期、跨页、所有订单或全部 SKU。
- `hasMore` 控制下一页；`periodTruncated` 提示处于非首页或存在下一页，必须使用后续分页查阅其余订单；`completePeriod` 只能表示**已遍历的分页范围的订单覆盖**，而 `financialSourceComplete=false`、`bankVerified=false`、`ledgerPosted=false` 永远固定，不能当作实际净利润或营收认可。
- 本轮无新迁移；直接读取已有订单、历史成本、财务分类/人工绑定与人工广告参考表。没有 Postgres 写入，也不会触发 Amazon 远程读写或银行连接。
- 生产级整期结算与利润、SKU 混合订单费用分配、销售退货成本回转、Ads 真正授权与明细、税费、汇兑、运营单据、分页结果快照一致性、大批量异步报表仍需后续阶段补齐。别以“当前页合格贡献”冒称完整 SKU 净利润。

## 回归

Java25 Gradle：签名金额计算、退款及混 SKU 阻塞、财务完整度/到账双 false、CSV 公式注入防护、日期与 offset 界限、基于 Session 的 Owner 范围。
React19 + Vitest + TypeScript：API Zod 强制 `financialSourceComplete=false`、`bankVerified=false`、`ledgerPosted=false`，来源必须 `MANUAL_UNVERIFIED` Ads，拒绝不合规分页及日期，CSV 地址不含 tenantId。
PostgreSQL18：本轮没有新迁移，CI 启动仍必须验证既有 Flyway V1–V59。所有真实 Amazon 和银行出站仍默认关闭。
