# V162 · 全成本覆盖核算引擎（P0-A 第一段）

**状态：已实现纯 Java 核心与严格离线负例，尚未接到真实费用来源/订单/SKU/多维 API。** 这不是已核验净利润报表、银行结算或 G1 财务口径通过证明。真实 Amazon Seller/Ads OAuth 与外部读写保持关闭。

## 为什么要先补这一层

现有 V33/V61/V64 只按已复核的 Finances 销售、签名 Amazon/FBA 费用及历史商品成本计算「已知费用贡献额」。广告、FBA 仓储、FBM 运费、头程及关税、退货处置、银行/汇兑和其他经营成本不能静默视作零。V162 新增 `FullCostCoverageEngine`：一套可单元验证、可复用、不触发数据库/网络的确定性成本类别、来源去重与利润瀑布**预览**引擎。

### 八大额外成本科目（不包含 V33 已核过的基础费用）

| 类别 | 费用含义 | 归属提示 |
| --- | --- | --- |
| `ADS_SPEND` | Sponsored Ads 等 | Ads API 来源独立，需要归因/分摊与 Finances 防重 |
| `INBOUND_FREIGHT` | 供应链头程 | 运单/发票、批次/货件分摊 |
| `FBA_STORAGE` | FBA 仓储及超龄费 | 按站点/库存/期间归属，检查与平台分类是否重复 |
| `FBM_SHIPPING` | 卖家发货配送 | FBA 订单不适用也需要经审核的「不适用」证据 |
| `RETURN_HANDLING` | 退货处理及逆向物流 | 不等于退款金额，也不能自行冲回 COGS |
| `IMPORT_DUTY` | 清关、关税及进口环节可归属费用 | 税费归属/资本化政策须 G1 批准 |
| `PAYMENT_AND_FX` | 银行支付费及汇兑成本 | 汇率/日期/来源、正负汇差另行政策确认 |
| `OTHER_OPERATING` | 其他应分摊经营成本 | 需要期间与分摊依据，不能默认 0 |

每个类别**最多一条**输入覆盖状态；缺条目即 `UNKNOWN`。后续为多个凭证和分摊提供持久化明细，但在进入本引擎前必须先完成该类别的有证据汇总和金额守恒。

### 输入状态与安全含义

- `UNKNOWN`：金额和来源必须都为 NULL，不能把未知当作 0。
- `UNVERIFIED_COST`：保留外部/手工声称的金额和来源，**不扣减贡献**、计入缺项；不能冒充已复核。
- `REVIEWED_COST`：金额必须为正数、同一 ISO 原币、来源标识唯一，并显式声明外部来源已复核、不在 V33 基线内；**才允许在已知费用预览中扣除**。
- `REVIEWED_ZERO`：有明确经复核的零成本证据，不能仅凭没有发票就填 0。
- `REVIEWED_NOT_APPLICABLE`：有经复核的不适用证据，例如 FBA 订单不承担 FBM 配送费；不是零支出伪装。

所有有来源条目不允许在不同费用科目中复用同一 `(sourceType, sourceId)`；`FINANCES`、`SETTLEMENT`、`ORDERS` 不允许作为新增费用来源，因为其金额已经可能在 V33 的销售与带符号 Amazon/FBA 费用里，重复扣除将误算利润。跨币、负费用/退款冲回、虚假零成本、非法精度、超界金额、来源重复均失败关闭。退款是额外的单独规则，不能以负成本「自动加回利润」。

### 输出和不可升级边界

在 V33 基线满足完整保守门槛且金额守恒时：

`已知费用扣除后贡献（预览）= V33 signed sales + signed Amazon fees + signed FBA fees − 历史 COGS − V162 有证据的新增正费用`

- `baselineContribution` 仍是 V33 的来源预览；`additionalReviewedExpense` 只汇总有来源和复核声明的正费用；`knownCostAdjustedContribution` 是**未完成全成本的保守预览**。
- `categories` / `missingCategories` / `coveredCategories` 指明每类的观察状态。`ALL_CATEGORIES_REFERENCED_PREVIEW_ONLY` **只表示八种类别已有状态声明**，**不代表财务来源完整**。
- 即使八类全部填了已审来源，`fullCostSourceComplete=false`、`bankReconciled=false`、`ledgerPosted=false`、`realizedNetProfitVerified=false` 和 `realizedNetProfit=null` 仍保持不变。
- V33 基线本身未达到条件，`BASE_CONTRIBUTION_NOT_READY` 下总金额字段全部为 NULL，不能用额外费用的数值补造收入/利润。
- 引擎只接受代码调用者提供的证据结构：**纯布尔复核标志并不是独立审核的证明**。禁止把用户输入直接标成 REVIEWED 送入此计算。服务层须使用受权限保护的审批、来源不可变身份、金额守恒与原始文件审查证据；本阶段还**没有**这种多成本服务层归集，更没有新前端完整利润数据页面。

### 测试和下一步

`./apps/server/gradlew -p apps/server --no-daemon --console plain test --tests 'org.merchivra.erp.finance.FullCostCoverageEngineTest'`

验证 V33 signed fee 负值不二次扣除、币种与金额精度、跨类来源 ID 去重、退款负成本拒绝、手工广告不扣、缺项与零成本分离、全类别有声明时仍不显示最终利润、基线不可计算时金额为 NULL、数据结构确定性及不可变性。常规 PR 快速 Gradle 单测和静态文档检查足够验证**未连接任何运行时的新纯数学引擎**；下一阶段若集成来源和改变生产财务路由，则需要真实 PG18/HTTP、Session/租户隔离和财务 G1 用例的专项验收。

下阶段 P0-A/P0-B：设计租户/Marketplace/期间的不可变来源入库、单据金额守恒与外部来源去重，建立受审（maker-checker）成本分摊服务；通过证据后才让 V162 接入 V64 版本化期间报表和 Next 利润首页。**不能在未得到卖家批准前调用真实 Amazon/Ads。**

[产品利润主线](../product/profit-first-next-primary-v161.md) · [V64 冻结报表](period-profit-report-v64.md) · [财务规则及 DEC-06/07/08](../product/finance-rulebook.md) · [生产 NO-GO #104](https://github.com/kunlong-luo/merchivra/issues/104)
