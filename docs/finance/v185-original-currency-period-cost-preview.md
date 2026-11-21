# V185：基于 V92 原币期间分摊的跨月算术预览（非会计入账）

状态：**业务实现已合并，精确业务 main SHA 完整合成 A3 7/7 通过**。真实 Amazon Seller/Ads OAuth OFF；生产 #104 NO-GO。

**V185 原币跨月期间分摊技术验收（2026-11-21）**：业务 [PR #188](https://github.com/kunlong-luo/merchivra/pull/188) 已合并，精确业务 main SHA `c344aec2b9d4911f60b53912f5aaf60ba329d120`；合并前 [快速 CI #44864483406](https://github.com/kunlong-luo/merchivra/actions/runs/44864483406) Java25/Gradle、React19/Next16/Vite 和 Compose **SUCCESS**；精确业务 SHA [完整 A3 #44864616569](https://github.com/kunlong-luo/merchivra/actions/runs/44864616569) `run_attempt=1`、**7/7 核心任务 SUCCESS**（PG18/Flyway 合成 V92 双 Owner HTTP、新跨月 10.000001 = 5.000001 + 5.000000 原币守恒、Session/CSRF、Java、Next/Vite Chromium、Compose 恢复；独立设计专项策略 SKIPPED）。V185 只从原 V92 不可变 PERIOD 明细生成自然日微币跨月金额敏感度预览，Next.js 原来源抽屉可查看；**不批准真实会计摊销，不改变 V162/V64、不汇率转换、不冲回退款 COGS、不入账、不清除 V95 hold、不认证第三方 Amazon/银行费用真实性**。财务 G1/DEC-06/07/08 仍待批准；真实 Seller/Ads OAuth OFF，生产 #104 NO-GO。


## 事实边界

V164 已有按 `SKU / ORDER / PERIOD` 的互斥人工费用分摊方案、最多 100 行、最多 92 天的半开期间、不可变来源和审计、独立 Owner 审核、`APPROVED_UNVERIFIED` 状态。V183/V184 提供全期间核算证据阻断与批量查询，但没有任何已批准的 G1 费用摊销、FX 和退款 COGS 政策。

V185 **不新增来源真实性认证、不修改 V92 金额、不将成本写入 V162/V64 真实利润报表**。使用既有 V92 `PERIOD` 分摊明细，提供按自然日均摊、归入日历月份的六位原币敏感度演算，让审核者能核对一笔人工费用跨月份的比例与尾差；月度金额不等于会计确认额。

## 已有业务链路上的新读接口

`GET /api/v1/finance/marketplaces/{marketplaceId}/cost-sources/{sourceId}/allocations/{allocationId}/periodization-preview`

- 必须有同一租户活动站点和正确 V91 来源/V92 分摊批次，Owner Session/现有登录权限，缺源或跨站点/跨来源返回 404；不是 `PERIOD` 的方案返回 409。`Cache-Control: no-store`，无写方法、无 CSRF 豁免与无外部调用。
- 单个 `REPEATABLE_READ` 只读事务加载来源原币、V92 审核状态、不可修改的期间明细。既有 `PENDING_REVIEW`、`APPROVED_UNVERIFIED`、`REJECTED` 皆保留原状态；任何计算结果都不等于已经通过真正费用和会计审核。
- `PeriodCostAccrualPreviewV185` 先调用原 V164 `CostAllocationEngine.evaluate` 重核原币/金额、目标互斥、六位精度、最多 100 行与 92 天，然后将每一行的整数微币单位（1e-6）按**半开服务日期**等分。除法余数依日期从早到晚分配 1 个微币单位，合并为 `YYYY-MM` 自然月；不使用 double、汇率、隐式小数位舍入。
- `sum(months[*].projectedAmount) = allocatedAmount`、`allocatedAmount + unallocatedAmount = sourceAmount` 严格在同一原币精确成立。跨闰年、月底、跨年亦按自然日归属；多条不相同的期间可合并。同来源存在重叠服务日则返回 `overlappingServiceDays=true` 和 `OVERLAPPING_SERVICE_DAYS_REQUIRE_POLICY_REVIEW`，**不推断一定重复收费**；原币来源总额守恒仍然必要。
- 所有 `externalSourceVerified`、`accountingPolicyApproved`、`currencyConverted`、`refundCogsReversalVerified`、`ledgerPosted`、`netProfitEligible` 恒为 `false`，`realizedNetProfit=null`。即使 `APPROVED_UNVERIFIED`、未分摊余额零或缺外部来源，也绝不升级为 G1 已批准成本。
- 版本标签固定为 `ILLUSTRATIVE_DAILY_STRAIGHT_LINE_MICRO_UNITS_V1`，不可自动以此替代卖家/财务决定的服务期间、会计确认、资本化或税收规则。币种单独列示，不创建未经批准的 FX 换算；退款成本回转仍需 V63 + WMS 实收及独立财务政策。

## Next.js 16 / React 19 展示

在现有费用来源审计抽屉内仅对当前选中的来源查询最多 50 份既有 V92 方案；存在待审或已批准未核验 `PERIOD` 方案时，再按方案 ID 查询跨月模拟金额、未分摊余额与原因代码。复用 TanStack Query、Base UI 抽屉和 Zod **全为 false 的财务认证字段校验**。没有期间方案时明确展示“未提供”，而不是显示零成本；SKU 或 ORDER 方案不被自动投影成期间费用。

## 验收及尚未解决的政策

Java25 单元覆盖六位微币尾差、闰年、半开边界、部分分摊、跨月/同月合并、重叠期间、非法目标/零负金额/超额和真实净利润保持 null。React/Zod/Next16 负例防止“已认证/已入账”伪造响应。实际 PG18 + HTTP 使用合成 V91/V92 双 Owner、Session/CSRF、前后审核状态、跨 11/12 月 10.000001 的 **5.000001 + 5.000000**、来源/分摊 ID 404、无 Finances/Ads/bank/profit 写入验证。真实 HTTP 结果必须由合并后精确 SHA A3 确认。

**V185 没有解决**：G1/DEC-06/07/08 对摊销方式、退货资本化、税费和原币/本位币汇率的批准；可信第三方费用明细和 WMS 实收；跨订单、SKU、期间的多维单一事实成本桥接和可认证完整净利润。自动应用 FX、负费用抵扣、退款 COGS 回转或将手工审核金额认定为 V162 `REVIEWED_COST` 都是错误升级。继续保持 Amazon Seller/Ads OAuth OFF、生产 #104 NO-GO。
