# V165 · 跨来源成本身份冲突隔离（当前本地快照）

## 目标与可交付结果

V164 允许经过**本地手工元数据复核**的费用按 SKU / 订单 / 期间进行一次性分摊。**同一项费用仍可能同时出现于 V91 手工凭证、V36 手工 Ads、V64 离线 Ads、V21 Finances 事件、V54 Settlement 报告与 V30 手工结算参考。** V165 引入跨来源 **同租户、同 Marketplace** 的身份重叠扫描及保守审批拦截，避免一笔来源在尚未查明时被机械地继续批准。

新只读 Owner 接口：

`GET /api/v1/finance/marketplaces/{marketplaceId}/cost-sources/{sourceId}/identity-check`

返回来源 id、V91 元数据审核状态、`status`、八项固定 `signals[]`（编码及是否精确本地身份匹配）、`approvalHeld`，以及永远为 `false` 的 `independentExternalEvidenceVerified`、`completeSourceCoverage`、`netProfitVerified`。响应带 `Cache-Control: no-store`；接口不会泄露可能重叠的另一条来源的原始键、原件、凭证摘要、客户信息或金额。租户从 Spring Session/Owner 解析，调用者不可提供。

## 八类精确身份线索

1. `OTHER_MANUAL_COST_SAME_CLAIMED_DOCUMENT`：同 Marketplace 不同 V91 来源宣称同一 SHA-256 单据摘要；摘要由操作人**自称**，没有真实文件摘要核验
2. `MANUAL_ADS_SPEND_SAME_KEY`：V36 同 Marketplace 手工 Ads 来源键和 V91 来源键完全一致
3. `OFFLINE_ADS_REPORT_SAME_REFERENCE`：V64 非拒绝 Ads 离线报告引用与来源键一致
4. `OFFLINE_ADS_REPORT_SAME_CLAIMED_DOCUMENT`：V64 非拒绝 Ads 报告 SHA 与手工声明的 SHA 一致；报告级文件不等于单笔广告费用
5. `SPAPI_FINANCES_EVENT_SAME_KEY`：Finances 事件的来源事件键和手工键完全相同；数据字段标记 LIVE **不代表 CI 的合成数据是真的**
6. `SPAPI_SETTLEMENT_REPORT_SAME_REFERENCE`：V54 Settlement 的 Report ID / Document ID / Settlement Reference 与来源键一致
7. `SPAPI_SETTLEMENT_REPORT_SAME_CLAIMED_DOCUMENT`：V54 Settlement 报告摘要与手工声明的摘要相同
8. `MANUAL_SETTLEMENT_STATEMENT_SAME_REFERENCE`：V30 手工结算单引用与来源键一致

这些是 **来源身份命名空间之间的候选线索**，不是同一笔费用已经经过实质核对的证明。即使有同名的引用，原始交易的入账分类、币种、退款、日期和金额仍需独立财务审核。**只按金额/日期相同不会判重**，防止误杀大量正常卖家订单与账单。

状态只有：

- `POTENTIAL_IDENTITY_COLLISION_HOLD`：当前本地观察到一个或多个精确来源身份重叠，`approvalHeld=true`
- `NO_LOCAL_IDENTITY_MATCH_UNVERIFIED`：当前仓库上述八项均未匹配，**并不代表不存在重复费用**，尤其未接入真实 Amazon Seller/Ads、没有验证外部发票时；`approvalHeld=false` 也不是利润已核验

V165 在 V164 `CostAllocationService.decide(..., accept=true)` 的**同一个数据库事务**内，于持有费用来源行锁后重新扫描。出现重叠即返回 **HTTP 409**，保留 `PENDING_REVIEW` 并不追加批准审计；拒绝方案仍可执行。该机制防止**当前可见候选冲突在审批瞬间被忽视**，不保证对其他表未来导入的事务持续清除冲突；之后新导入的费用会出现在最新扫描中，已有 `APPROVED_UNVERIFIED` 仍不能变成已核验费用。现阶段不提供“我确认不是重复”的绕过按钮，也不自动改财务报表与账本。

## 真实隔离测试与限制

`CostIdentityReconciliationServiceTest` 检查八项独立命中、未命中仍不能视作真实、租户会话和 no-store、只读及无外部 API。完整 A3 调用 `scripts/smoke_cost_identity_v165.py` 使用**隔离 PostgreSQL 18 + 真实 Spring HTTP + 两个合成 Owner**：同金额不同来源键不误判；向 V36 插入相同来源键后，已提交的 V164 方案审批 409 而仍为待审；无冲突方案可按 V164 规则独立批准；若之后出现另一张宣称相同 SHA 的 V91 单据，只在快照中标记风险，绝不自动变成已验证利润。正常财务报表、Ads Reviewed Cost、银行回款表行数必须保持不变。

**尚未完成：** 来源身份的人工裁决工作台/经审计的解除流程、各种平台报表的行级交易同一性、永久防双计因果账本、并发新来源入库的统一锁序与回溯处理、退款/税/汇率证据与冻结期间重算、V162 → V64 可靠全成本并入及 Next.js 正式净利润报表、真实 Seller/Ads 本地授权与财务 G1 签署。

这是 **技术上的保守风险拦截**，不是完成跨来源财务对账，也不是生产就绪证据。生产 #104 OPEN/NO-GO；历史 TLS #115 根因仍未确定；正式前端目标 Next.js 16，Vite 暂留回滚。

[利润 P0 #12](https://github.com/kunlong-luo/merchivra/issues/12) · [V164 已分摊费用](cost-allocation-v164.md) · [产品主线](../product/profit-first-next-primary-v161.md)
