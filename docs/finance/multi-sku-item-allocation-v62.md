# V62 · 多 SKU 订单 LIVE Finances 逐订单项人工金额归属

## 目的

**已实现**：将 V61 保守单 SKU 贡献利润预览扩展到多 SKU 订单，但**仅当每笔已人工匹配的 LIVE Finances 事件的所有可分配类别都被明确、完全、独立审批**，并且各订单项具备原始下单日期有效的内部 SKU 映射与历史成本，才将金额归入单个 SKU。原币种、金额正负号、事件 ID、订单项 ID、人工依据编号和版本审计必须保留；没有任何自动按件数、收入比例或拍脑袋分摊的算法。

## Flyway V60 数据结构

- `finance_item_allocation_review`：租户、Marketplace、LIVE event、已关联 order、状态 `DRAFT/PENDING/APPROVED/REJECTED`、来源证据 SHA-256、编制人、独立审批人、版本与时间。
- `finance_item_allocation_line`：一条 `event × external_item_id × category` 的原币有符号金额和脱敏分摊依据编号；数据库 FK 强制属于同一订单，来源事件、订单、Marketplace 在复合 FK 中同时约束；同一组来源只能保存一条金额。
- `finance_item_allocation_audit`：不可覆盖的增量审计，记录保存、撤销、提交、审批、驳回及重开，含动作、版本、操作者、说明、证据哈希与可选行金额。审计由服务端写，不接受前端提供 actor 或 tenant。

## 业务门禁

1. Finances 事件必须 `SPAPI_FINANCES/LIVE`，并在 `finance_event_link_review` 中由人审核为 `MATCHED`，只允许同租户同 Marketplace 下的 `sales_order.data_origin=LIVE`。
2. 金额来自 V28 `FinanceClassificationService.preview()` 已完成 `RECONCILED_UNVERIFIED` 的官方 breakdown **叶节点**归类合计；只接受 `PRODUCT_SALES/AMAZON_FEE/FBA_FEE`，退款、调整和未分类必须先通过独立流程，不能在此暗自计入。
3. 订单必须 `COGS_READY_PREVIEW`，拥有 2+ 不同内部 SKU、100 条以内的当前商品项，全部订单项独立唯一、数量为正、原币相同、来源 LIVE、映射和成本完整。分摊明细只能指向这些真实订单项。
4. 同类别的分配有符号方向必须与原始类别一致；任何时候金额绝对值累计不大于来源绝对值。未分配差额必须显示；**全部类别残差为 0** 才允许提交第二 Owner 审核。
5. 只有原编制人能编辑 DRAFT 并提交；另一位 Owner 必须审批。修改、源分类/成本/映射变化或审批明细字节变化后标 `STALE`，旧结果禁止再进入 SKU 报表；重开会清空旧分配，必须重新输入/审核。审批摘要哈希同时绑定来源快照和**完整的审核行金额/依据编号**。
6. V61 店铺与 SKU 报表仍是单页 30 单、UTC 1–92 日。只有**一张订单下的所有关联 Finances 事件都已批准**、无待处理退款/调整、每笔来源守恒、原币事件合计和历史商品成本与订单贡献余额都完全吻合时，多 SKU 贡献金额才计入页总额和各 SKU。
7. 多 SKU 被纳入的订单在订单总数只计算一次，每个真实 SKU 计入对应贡献；任一来源无效时**整笔订单不得部分计入**。完整期间金融来源覆盖、税费、广告、银行实际到账和会计记账仍均未验证；服务端返回 `financialSourceComplete=false`, `bankVerified=false`, `ledgerPosted=false`。

## API 和界面

- GET `/api/v1/finance/marketplaces/{marketplaceId}/item-allocations/{eventId}`：当前来源项目、SKU、逐类别已分/未分、审核状态与证据时效；GET 结尾 `/audit` 获取有界审计。
- PUT 同路径 `/line`：`{itemId, category, signedAmount, currency, evidenceReference, reason}`；`signedAmount=null` 只撤销对应项目；写入前校验总额。
- POST `/submit`, `/approve`, `/reject`, `/reopen`：仅 `reason`。所有动作根据当前服务器 Session 解析 Owner/tenant，写入需要 CSRF，返回 no-store。
- React 财务事件在 `MATCHED` 且 LIVE 时显示 V62 项目归属工作台；可查看剩余金额、选择 Amazon 原始订单项、输入有符号数和来源证据、提交/独立批准/驳回/重开及审计。V61 贡献报表同时展示已批准多 SKU 的来源金额和排除原因，旧 CSV 保持只导出当前页。

## 不应声称已完成

- **没有**跨来源真实 Amazon Seller 账号联调、银行可信回单、正式税务或 Ads 明细、退款成本回转、跨货币汇总、完整周期账务冻结，也不进行 Amazon 写入/银行支付。
- 单笔订单在不同时间内可能出现晚到 Finances/退款，分页报表读取采用同一 PostgreSQL REPEATABLE_READ 快照，但 `financialSourceComplete=false` 永远保留，不能把今天的单页贡献当作最终利润。
- 此归属仅通过人审数据与来源哈希提供可追溯估算，不能在未经验证的财务合规系统中当作正式凭证。部署可持续在无真实 Amazon 授权状态下运行 Mock。

## CI

- Java25 Gradle：类别守恒/符号、禁止过分配、禁止自审、源或明细篡改摘要失效、多 SKU 完整分配后的严格双重求和。
- PostgreSQL18：Flyway V60 约束和 Spring Boot 启动。
- React19 TypeScript/Vitest/Vite：Schema 强制财务来源不完整、CSRF、无浏览器 Tenant、逐订单项写入。
- 可选 Compose Mock 全链路与 Chromium UI 检查保持原有工作流。真实 Seller 授权和业务 E2E 待 Owner 在全部代码完成后本地执行。