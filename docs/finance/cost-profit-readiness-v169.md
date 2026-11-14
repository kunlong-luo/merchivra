# V169 · Marketplace 按业务月的全成本费用准备度（Next.js 可见）

## V169 精确 SHA 技术验收（2026-11-14）

[PR #147](https://github.com/kunlong-luo/merchivra/pull/147) 功能合并提交 `38d269a8c9ce05526a01bb08f8e110ead52b6068` 的首轮 [A3 #43587863847](https://github.com/kunlong-luo/merchivra/actions/runs/43587863847) **FAILED**：V169 PG18 合成用例错将一个来源的已分摊 7.25 断言为整类 INBOUND_FREIGHT 的合计，忽略前序 A3 测试留下的同类别其他有效来源；这是错误的**验收比较基准**，不能用来否定或证明业务 SQL。

[PR #148](https://github.com/kunlong-luo/merchivra/pull/148) 将其修改为：逐来源仍精确核验 7.250000 + 2.750000；整类别/原币的金额，独立使用 PostgreSQL 18 查询整个日期范围所有 `APPROVED_UNVERIFIED` V92 分摊，逐科目比对 API 的已批准未核验金额与未分配余额。修复合并提交 `8c12e042161d4eb0369fd64873eec9f91c30b7ac` 的 [完整 A3 #43588250536](https://github.com/kunlong-luo/merchivra/actions/runs/43588250536) **run_attempt=1，7/7 SUCCESS**：Java25/Gradle、PG18/Flyway、Spring HTTP、Next/Vite/Chromium、Compose 全部通过。修复 PR 快速 CI [#43588161941](https://github.com/kunlong-luo/merchivra/actions/runs/43588161941) 亦成功。

**只代表本地合成技术验收**；没有独立外部费用真实性签署、Finances/Ads/Bank 实际对账、G1 口径批准、真实净利润或生产 #104 放行。

## 本轮交付

在已有 V163–V168（费用原始来源、审核、分摊、8 类身份碰撞、Settlement 行关联/共享行碰撞）之上，新增一个**经营者真正可读**的成本清单和分类报表。它读取 PostgreSQL 的 V91/V92/V94 真实持久化事实，而非造一个演示利润数字。不新增账本或会计凭证；无数据库结构迁移，沿用 Flyway V95 的模型和索引。

Owner 可通过现有 FinanceWorkspace（Next.js 16 App Router 和 Vite 回退共用 React 19 组件）选择 Marketplace 与业务月，查看：

- 八类成本科目、按 **(category, original currency)** 分别汇总的 `claimedAmount`、`reviewedUnverifiedAmount`、`approvedUnverifiedAllocationAmount`、`approvedUnverifiedUnallocatedAmount`、来源数与存在共用 Settlement 行的来源数。
- 每份来源的业务日期、原币申报金额、第二 Owner 的元数据状态、V164 当前活动分摊、V165/V168 实时碰撞门禁以及关联交易行数。
- 八类外部来源仍未认证的清单；没有成本来源的科目仍是 **UNKNOWN ≠ ZERO**。`realizedNetProfit=null`、`realizedNetProfitVerified=false`、`fullCostCovered=false`、`bankReconciled=false`；所有来源 `externallyVerified=false` 和 `allowedToDeductFromProfit=false`。

这是管理信息**来源准备度**，不是实际经营净利润、已复核税务发票、可扣费用流水，也不意味着广告/FBA/头程等已全覆盖。

## API / 精确含义

`GET /api/v1/finance/marketplaces/{marketplaceId}/cost-profit-readiness?periodStart=YYYY-MM-DD&periodEndExclusive=YYYY-MM-DD[&beforeDate=YYYY-MM-DD&beforeId=UUID]`

- Session Owner `currentTenant` 推断租户，API 不接受浏览器传入的 tenant。活动租户/店铺/Marketplace 数据库校验，未知 Marketplace 返回 404；未授权拒绝。输出 `Cache-Control: no-store`。
- 半开业务日范围 `[periodStart, periodEndExclusive)`，最大 366 天；非法范围、跨期间游标及缺一半的游标返回 400。没有模糊自然月边界或隐式 UTC 跨币换算。
- 分组汇总覆盖**符合范围的全部来源**，与页面无关；来源列表稳定按 `(business_date DESC,id DESC)`，每页最多 50，查 51 行判断 `hasMore`，提供明确 `nextBeforeDate/nextBeforeId`。前端有前/后页导航。
- 财务字段是 **原币人工凭证申报**（不是带符号 Settlement/Finances 扣费）；仅 V92 `APPROVED_UNVERIFIED` 的分摊行参与 `approvedUnverifiedAllocationAmount`，并以 `source amount−allocated` 得到对应未分配余额。PENDING/REJECTED 不假装已分摊，来源行展示 null 而不是 0。
- 单个来源通过现有 V165 实时 identity-check 获取全部 8 信号摘要和 V168 共享交易行冲突。该查询在 PostgreSQL `REPEATABLE_READ` 只读事务中与分组及分页共享同一快照。即使 **V164 批准之后**才新增冲突，仍在本次报告展示 `approvalHeld=true`，但历史数据库审批状态不被伪造修改。
- 任一类别不存在来源时不返回假 `REVIEWED_ZERO`，不存在的分类由页面显式标识；即使八类全部有人工记录，也不改变全部外部认证类别仍未知的状态。

## V162、V64 和真实费用核销关系

V162 的 `REVIEWED_COST` 需要**独立外部来源核验且不在 V33 已知费用基线**的严格证明，绝不能把 V91 `REVIEWED_UNVERIFIED` 或 V92 `APPROVED_UNVERIFIED` 自动转换进去。V64 原有贡献报表继续独立冻结，不能将 `claimedAmount`、`approvedUnverifiedAllocationAmount` 与已含带符号的 Finances/Settlement 费用相加扣两次。用户要求的**完整可解释净利润**仍需要独立费用身份认证、退款/退货成本回转、Ads/Finances 交叉核销、G1 费率税务汇率政策、SKU/订单/期间版本化分摊、完整来源覆盖、对账与冻结重算。

这次可见的是实际来源驱动的 P0 成本工作台，不是正式财务利润核销。

## 测试范围

新增 JUnit：时间范围/游标拒绝、Owner Session 和 no-store、只汇总获批分摊、半开日期、原币/类别分组与不扣净利润的源码安全检查。扩展 `scripts/smoke_cost_identity_v165.py` 在 GitHub Actions 隔离 PG18 + Spring HTTP 中测：V163–V168 交叉来源真实数据库记录、V164 7.25/2.75 守恒与 pending-null、8 类 `UNKNOWN`、跨 Marketplace 404、匿名 401、错误范围 400、52 条合成来源造成的 50 条稳定 keyset 分页、分组汇总与跨页相同、无利润/银行/Ads 表变更。所有测试使用隔离**合成**来源而非 Amazon 实际发票。

技术 CI/完整 A3 的结论以 GitHub Actions 实际运行结果与精确合并 SHA 为准。

## 权限与上线

未启用任何远程 Amazon Seller/Ads 授权或远程写入。项目 [#104](https://github.com/kunlong-luo/merchivra/issues/104) 仍 **NO-GO**；G1 财务签署和真实平台/银行对账必须由用户在授权和本地实测后另行放行。正式 ERP 目标为 Next.js 16；保留 Vite 临时回退，不把技术 A3 混为正式生产发布。
