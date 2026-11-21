# V186：全期间人工费用单一来源归属桥接（防跨维度重复扣费）

状态：**业务 PR 已合并；精确业务 main SHA 完整 A3 7/7 通过**。本阶段只有原币人工费用的只读归属审计，不是自动财务费用计提、V162/V64 真实利润或 G1 准入。

V186 单一成本来源跨维度归属桥接验收（2026-11-21）：业务 [PR #190](https://github.com/kunlong-luo/merchivra/pull/190) 已合并，精确业务 main SHA `bc318851dee13df1857f7d1eb090a0d68b0a7532`；合并前 [快速 CI #44867190967](https://github.com/kunlong-luo/merchivra/actions/runs/44867190967) **SUCCESS**（Java25/Gradle、React19/Next16/Vite、Compose）；精确业务 SHA [完整 A3 #44867326080](https://github.com/kunlong-luo/merchivra/actions/runs/44867326080) **run_attempt=1，7/7 核心任务 SUCCESS**（PG18/Flyway、双 Owner V164/V185/V186 合成 HTTP、独立 V91 原币来源单表 SUM 与按 SKU/ORDER/PERIOD/UNASSIGNED 分组总额守恒、Java、React/Next16 Chromium、Compose HTTP/安全/恢复；额外专项设计检查按策略 SKIPPED）。V186 只把单个 V91 原币人工来源归入最多一个有效 V92 主分摊维度，不生成第二笔费用，不把缺来源视为零，不修改批准、历史审计或真实利润。**跨两个不同 V91 来源的真实重复扣费仍未认证；退货 COGS 回转、G1/DEC-06/07/08 会计政策、FX、外部 Amazon/Ads/银行证据均未完成，V162/V64 真实净利润继续禁止；Seller/Ads OAuth OFF，生产 #104 NO-GO。**


## 解决的真实问题

V92 已用 PostgreSQL 18 **部分唯一索引**限制一笔 V91 来源同时存在多个 `PENDING_REVIEW / APPROVED_UNVERIFIED` 分摊方案；审核、幂等与不可变证据由 V164 实现，绝不能新造并行审核表。V185 只能对 V92 `PERIOD` 方案进行按自然日原币跨月算术预览，不会生成任何订单/SKU 新费用。

但是财务工作台还缺**全期间跨分摊维度**的统一费用来源计数：财务人员同时浏览 SKU、ORDER、PERIOD 报表时，很容易将同一人工来源的金额分别相加三遍，或者将没有方案的来源视为 0。V186 为此提供只读归属桥。

## 主事实、衍生维度与计量口径

唯一主事实：**(tenant, marketplace, V91 source UUID, original currency, original claimed amount)**，不是 Amazon 交易 ID，也不是经核准的会计凭证。

- 按业务期间 `[periodStart, periodEndExclusive)` 查询 V91 `business_date`，最多 366 天；**不是** G1 会计确认日期、V185 跨月服务期或退款入账日期。每个 V91 来源在数据集中只出现一次。
- `source_claim` 先按来源整合 V92 有效方案（`PENDING_REVIEW` 或 `APPROVED_UNVERIFIED`），`active_count` 必须为 0 或 1；历史 `REJECTED` 版本不视为新来源和费用。其他情况 fail closed，不提供误导性金额。
- 单来源选定一个 `primaryDimension`：`SKU`、`ORDER`、`PERIOD`，没有有效方案时为 `UNASSIGNED`。这只是本地**主分摊维度**，不是经认证的费用所有权。其他维度可以引用该来源做未来经批准的投影，但**禁止新增对利润的再次扣除**。
- 按 `(category, currency, primaryDimension)` 汇总来源数量和申报金额；每条 V91 来源计数与金额都恰好一次，**绝对不能先 JOIN V92 lines 再 SUM(s.amount)**。
- 只有 `APPROVED_UNVERIFIED` 来源显示 `approvedUnverifiedAllocatedAmount` 和 `approvedUnverifiedRemainderAmount`，待审或无方案金额仍是未认证主张，不能自动视为已分摊或剩余零。当分组完全没有获批来源，相关金额为 `null`。
- 额外核对有无 V92 独立审核的不可变 audit 事件；缺失标出 `INDEPENDENT_V92_APPROVAL_AUDIT_MISSING`，绝不伪造回填。批准金额超源、空方案、重复有效方案、计数不一致，直接拒绝整份结果。

与 V185 的区别：V186 从 V91 **来源业务日**统计该期间发生的费用主张，V185 从 V92 **服务期间**演算每月成本比例。两者不能直接相加，也不能把 V185 金额按归属月份再视为新的成本来源。

## API 和前端

`GET /api/v1/finance/marketplaces/{marketplaceId}/cost-ownership-bridge?periodStart=YYYY-MM-DD&periodEndExclusive=YYYY-MM-DD`

- 仅已授权 Owner Spring Session，必须匹配当前 tenant、活动 Marketplace，跨租户与不存在的 Marketplace 返回 404，未认证 401，所有响应 `Cache-Control: no-store`。
- 在一个 `REPEATABLE_READ` 只读事务内校验 Marketplace 和全期间来源；**不受 V169 的 50 条分页影响**，返回 `sourceCount`、`buckets`、`categoriesWithoutSourceClaims`、`blockerCodes`。不会把不同币种金额相加。
- `doubleCountingAcrossDimensionsAllowed=false`；所有 `derivativeSkuOrderPeriodProfitDeductionsAllowed`、`externalChargeVerified`、`refundCogsReversalVerified`、`fxConversionApproved`、`bankReconciled`、`ledgerPosted`、`netProfitEligible` 恒为 `false`，`realizedNetProfit=null`；无实际来源仍展示“未知，不是零”。
- React19/Next16 继续复用现有八类成本工作台，新增一个不分页的“来源唯一主归属”表，按类别、原币、主维度展示原金额、尚未审、已审未验金额。Zod 严格拒绝外部认证或真实利润的伪造字段；无 POST、无客户端多币相加，Vite 受控回退保持兼容。

## 单独明确退款与重复收支来源边界

- 订单退款、V34/V35 人工退款归属、V63 历史成本敏感度，不是 WMS 可售实收或会计 COGS 回转，**本桥不抵消任何 COGS**。必须有可确认的退货实收/处置、G1 财务认可的成本回转及相关原币来源核对，才能在未来版本决定。
- V165/V168 的跨费用来源本地身份冲突和共享 Amazon 结算行、V182 冻结证据仍需在认定真正费用所有权前检查；**本桥的每来源“仅计一次”不证明两个不同 V91 来源并不重复收费**，也不清除任何 hold。
- G1/DEC-06/07/08 跨期会计确认、收入税费、汇率、退款资本化仍待卖家或财务批准。未核实的本地审批、外部广告/银行/结算真实性、费用类别覆盖缺失均保持阻断，不能让 V162/V64 返回已认证净利润。

## 测试与验收

Java 25 单元测试覆盖八类科目范围、SKU/ORDER/PERIOD/UNASSIGNED 分组、原币六位小数、重复组、超过来源余额、审计缺失、空来源绝不等于零、半开 366 天以及拒绝跨租户读取。React/Vitest 负例覆盖全部 false-only 金融证据 flag、返回 `null` 净利润、no-store + Session 同站点查询。现有合成 V164/V185 PG18 HTTP fixture 继续使用双 Owner 审核新增、已批准和没有分摊的来源，并对照**独立 V91 单表原币 SUM**，证明 V186 不会因为任意 V92 关联或跨维度分组重复计算来源金额；检查 401/400/404、退款/FX/银行阻断和零财务写入。

完整 A3 必须对**业务 PR 合并后的精确 main SHA**运行真实 PG18/Flyway/合成 Spring HTTP、Java 25/Gradle、React19/Next16/Chromium、Compose Session/CSRF/恢复。真实 Amazon Seller/Ads OAuth 仍然 OFF；生产 #104 为 NO-GO，不接受宣称全成本已核验。
