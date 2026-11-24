# V217 · 采购收货费用证据在 Next.js 采购与财务工作台的按需核对

**2026-11-24 / 前端只读集成，零新数据库迁移与财务写入。** V217 复用已经在 [V216 精确 SHA `64b7dd5`](https://github.com/kunlong-luo/merchivra/commit/64b7dd51cee3944888a31f34bdd6373c627c3847) 通过的只读接口。该版本 [A3 #45302466911（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45302466911) 真实 PG18 日志含 `PASS V216 PG18 exact receipt/V91/V92 evidence SQL` 和 `PASS V216 anonymous Owner GET 401`；只验证**隔离合成数据**，不表示可计算真实 COGS。V217 本身的 CI/A3 需单独核对精确代码 SHA。

## 入口与交互

- **财务中心** `FinanceWorkspace`：在已有成本准备度区域放置按需 V217 证据核查，用户必须输入完整的原采购收货 UUID，再主动提交查询。切换 Marketplace 或 tenant 后立即清除选择和先前查询结果，不能把上一站点的元数据误用作新站点成本。
- **采购中心** `ReceiptBatchPanel`：从已由后端分页返回的 V46 原收货批次行，选择明确的 `receiptId` 并点击“核对本收货单的费用来源”；随后通过已有 `FinanceMarketplaceFilter` / Base UI 从 Session 可见的 Marketplace 中选取 V91 费用来源范围，再主动展开 V216 查询。切换订单/租户清除批次选择，切换 Marketplace 会重置证据组件。**采购 V20 receipt 是租户内身份，不是 Marketplace 独占批次**。当前页面的批次列表仅用于选择，不替代 V216 对整份 V112 计划全部 receipt 的独立汇总。
- 查询采用 TanStack Query 的独立键 `finance + tenant + marketplace + receipt`，`enabled` 只在明确选择及点击后为真；`retry:false`、`cache:'no-store'`、`credentials:'same-origin'`。失败及 409 时仅显示失败原因，不退回旧成本结果、不展示部分金额。新增客户端不使用 Amazon/Ads 实际 API，也不修改 V112。
- `apps/web/src/lib/receipt-cost-integrity-v217.ts` 对 V216 原始 Spring JSON 使用 Zod 强校验：严格 `ruleVersion/sourceBasis`、UUID/原币/金额格式、最多 50 计划、数量守恒、唯一 source/plan、UNKNOWN 不等于零；用六位原币最小单位 BigInt 比较同计划金额（不经过浮点换汇）。任何 `actualBatchCogs`、`realizedNetProfit` 非 null、已核证标志或 postingAllowed=true 都直接被拒绝。HTTP 401/403/404/409/500 分别有清晰错误信息。
- UI 仅显示所选收货行、本来源全收货行的**人工候选原币金额**、来源计划数、V165 潜在冲突计数、共享 settlement 行风险与审计阻断，不能从这些数据推导单位成本或利润，不能合并不同原币金额。若无提案，明确“缺证未知，不代表真实费用为零”；`APPROVED_UNVERIFIED` 始终标明内部已审核但非外部核证。

## 测试与最小安全契约

`receipt-cost-integrity-v217.test.ts` 覆盖合法本地未核证返回、全部外部核证与过账标志必须 false/null、重复/超过 50 的来源、超额/超过 6 位金额、跨 Marketplace/receipt 响应不匹配、无提案缺 UNKNOWN、非法 UUID 请求不出站、真实 no-store GET 方法和失败 HTTP 行为。

现有 Java Owner / no-store V216 接口继续保有租户和 Marketplace 双层限制。前端只有 GET，没有 POST、PATCH、批准或导出真实利润入口。不新增基础依赖或第二份锁文件，继续使用 Next.js 16 / React 19 / pnpm 12、TanStack、Zod、Base UI。正式签署 G1/DEC-06/07/08、银行/海关/物流/汇率单据、真实 Seller/Ads 授权和物理退货原批次仍缺，不能声明 landed cost、退款 COGS 或净利润完成。

## V218 后续验收重点

优先补充可靠的真实后端 E2E V216 401/403/404/409/Owner 响应负例与视图集成、核对财务 UI 在错误后是否正确清空结果，再考虑如何展示全量费用来源证据；真实外部来源签证和财务口径仍属 G1 阻断。所有本地已审核/合成绿色都不能通过生产 #104。

**评分 70/100，`realizedNetProfit=null`，真实 Amazon Seller/Ads OAuth OFF，生产 #104 NO-GO。**
