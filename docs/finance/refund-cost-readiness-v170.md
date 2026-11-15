# V170 · 退款/退货历史 COGS 风险与核算准备度（Next.js 财务工作台）

## 背景与本轮交付

V63 已按单个 LIVE Finances 退款事件提供独立 Owner 的 V34/V35 证据复核、历史成本敞口与「人工声称已回库」敏感度，**不**真实回补库存、不冲回 COGS、不写 GL。V169 实现八类手工费用的原币准备度，但退款事件来自另一条 Finances 链路，不能当作 V91 的额外正数成本，也不能与 V64 已纳入的 Finances 有符号退款再次相加。

V170 新增只读退款风险队列，帮助经营者发现 **已经入库而尚未具备可信 COGS 回转依据的退款**；没有新的费率公式、数据库迁移、外部 Amazon 调用或财务记账。Next.js 16 财务工作台与 Vite 临时回退使用同一个 React 19 组件 `RefundCostReadinessPanel`，显示独立于 V169 人工费用科目之外的退款情况。

### API

`GET /api/v1/finance/marketplaces/{marketplaceId}/refund-cost-readiness?periodStart=YYYY-MM-DD&periodEndExclusive=YYYY-MM-DD[&beforeAt=ISO-8601Z&beforeId=UUID]`

- **来源**：只选择 `finance_source_event` 内 `source_family='SPAPI_FINANCES'`、`data_origin='LIVE'` 且当前来源层级 `category='PRODUCT_REFUND'` 的事件。这里的 `LIVE` 是已入库的来源标签，不代替真正的 Amazon/银行本地授权核验；未分类 breakdown 内潜在的退款**不计入**此选择条件，因此 `allFinancesRefundsCovered=false` 恒为假。
- **时间**：原始 `posted_at` 在 **UTC 半开区间** `[periodStart 00:00Z,periodEndExclusive 00:00Z)`，最多 366 日；不同于 V169 人工来源的 `business_date`，禁止以两者拼接为「已实现利润」。无效区间/游标 400。
- **权限与安全**：Session Owner 从后端选出 tenant；Marketplace 在租户及活跃店铺范围内校验，不存在或越权的 scope 返回 404/拒绝； `Cache-Control: no-store`；只 GET 不产生 CSRF 写操作，无传入客户敏感凭证或 AWS 调用。
- **分页**：全部期间每个原币独立汇总**来源事件数和来源有符号金额**；不按 `page` 截断。明细按 `(posted_at DESC, id DESC)` 的稳定 keyset 每次最多 50，`LIMIT 51` 得到 `hasMore`，同时给出 `nextBeforeAt/nextBeforeId`。PG18 只读 `REPEATABLE_READ` 确保单个响应的汇总/明细来源一致；不同请求间的迟到来源仍可能变化，不能声称冻结报表。
- **逐事件证据**：复用 `RefundRecoveryService.preview` 动态计算 V63 状态、来源指纹/修订一致性。只有 V34/V35 按原币、原有符号分配完整且历史成本齐备时才展示 `historicalCostExposure` 与 `claimedRestockCostSensitivity`；若资料不全或未关联可信订单则返回 `INCOMPLETE_EVIDENCE`、金额 null，**UNKNOWN ≠ 0**。V63 已审但后来费用、数量或历史单位成本改变则显示 `STALE`。`REVIEWED` 仅代表人工资料独立复核，不代表实物入库。
- **禁止会计冲回**：任何结果 `externalReceiptVerified=false`、`actualCogsReversalAllowed=false`、`actualCogsReversal=null`，整页 `actualCogsReversalsVerified=false`、`realizedNetProfitVerified=false`、`bankReconciled=false`。

### 为什么不把退款、退货和人工费用合并计算

`PRODUCT_REFUND` 来源的有符号金额可能已经参与已有 V33/V64 的贡献估算，**不得在 V162 当作新的正费用再减一次**。对退回商品，历史 COGS 是否应反冲涉及**实物确认、可售/不可售、原库存批次与成本版本、平台赔付/退款时间、运营/财务政策**。V63 的 `RESTOCKED_CLAIMED` 是用户声明，不是采购入库/退货实收证据。用户尚未授权 Amazon Seller/Ads 的正式接入核对，故即使 `REVIEWED`，V170 也不能直接把敏感度当作真实库存或销售利润。

同原币汇总的 signed refund 也**不是“亏损总额”**：净收入的已入账/预计税费、其他费种、跨期/赔偿必须以具体来源与 G1 确认的会计规则重建，且对不同货币不自动汇率换算。

### 验收与后续

新增 JUnit：UTC 边界、半开区间、含时间游标、Owner/no-store、安全禁止写财务等。将真实 PG18/Spring HTTP 合成回归扩展到 52 条同时间来源 + 1 条异币退款 + 1 条区间边界排除，验证超过 50 条时不漏不重、不同币种 signed refund 汇总不混合、无 V34/V35 时风险状态为 `INCOMPLETE_EVIDENCE`/金额 null、匿名访问 401、错误范围 400、跨站点 404、不改变已有利润/银行/Ads 汇总。

**技术 A3 只能证明隔离合成环境中的代码与 SQL 行为，不能视为已验证外部真账或生产放行。** P0 仍需后续 V171+：可信退货实收（含库存批次和可售条件）、真实费用/退款交易级一致性防重复、G1 批准的成本回转规则、冻结报告重算差异、可复核外部 Seller/Ads 数据。生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) 保持 NO-GO，Seller/Ads 真实 OAuth 关闭。
