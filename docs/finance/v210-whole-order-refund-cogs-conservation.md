# Merchivra V210 · 订单级全量退款数量守恒与 COGS 回转阻断

**2026-11-23 · 无新 Flyway 迁移 · 精确代码 SHA/CI/A3 本轮核验后回填。**

## 为什么必须以“整单全量”核对，而非当前页/某次退货

Merchivra 的首要核心能力是**有证据的真实收入、全部费用、原始货物成本和退款成本回转，最后可审计地形成订单/SKU/期间利润**。现有功能已有：

- V34 手工退款商品与数量审查；V35 原币带符号的商品级退款金额分摊；V63 双 Owner 审核、历史 SKU 成本的 `MANUAL_EVIDENCE_BALANCED_UNVERIFIED` 敏感度；
- V170 以 50 条为单位的全期间退款源诊断、V197 在 source snapshot 与 V63 两次读之间对事件 ID / 订单 / 币种 / 带符号金额做防漂移；
- V201 在读时重算**同订单行跨所有 V34 事件的累计人工退款数量**，拒绝超过当前 LIVE 原始订购数量；
- V202–V209 对本地货物批次、WMS 签名、第三方原件/自登记公钥及运维侧证书链建立技术证据，仍**不证明真实发货、实际批次 COGS 或退货物理实收**。

V210 不重造退款表、独立复核机制或财务台账。核心缺口是**整单源是否完整、跨两次/多次部分退款有没有重复计算数量与成本、来源旧审核是否发生漂移**。如果只汇总 V170 当前页上的“可用成本敏感度”，就可能将单次看似平衡的退款错误累加。V210 只提供新的订单级、只读、fail-closed 聚合，绝不解锁真实金额。

## 订单级数据契约与一致性

`RefundOrderConservationServiceV210` 首先验证当前 Session Owner 的 `tenant,marketplace,order` 属于 ACTIVE 租户和当前 LIVE 销售订单。以 PostgreSQL `REPEATABLE READ` + 只读独立事务冻结下列三个结果：

1. 当前 `order_item` 订单行：`is_current=true AND data_origin='LIVE'` 的 `external_item_id` 及当前原单数量；缺失项数量为 **UNKNOWN，不等于 0**。
2. 该订单的**所有** `finance_refund_item_review`（V34）人工已审核行，跨事件集合、不按当前页或最近一笔退款截断；按 `event_id,external_item_id` 去重和累计，并追踪来源没有对应 LIVE 已匹配退款事件的孤儿审查。
3. 同一 Marketplace 下当前 `finance_source_event` 的 `source_family='SPAPI_FINANCES'`、`data_origin='LIVE'`、`category='PRODUCT_REFUND'` 且经 `finance_event_link_review.state='MATCHED'` 归属该订单的**全部**事件原始金额、币种、时间、UUID。使用原币 `NUMERIC` 对每一币种分别求和，不能把 USD+EUR 凑成单个数字。

每类最多 **1000 条**；SQL 用 `LIMIT 1001` 主动检测越界。一旦任一维超过上限，返回 HTTP 409 **禁止展示部分总额**；不得无提示从第 50 条或前 1000 条得出整单结论。

随后在原始快照事务**结束后**逐笔调用 V63（V63 的正常 404/409 不能把外层事务污染成 HTTP 500），复用 V197 检查事件 ID、订单 ID、币种与金额和当前复核是否相符。对当前 `REVIEWED` 且独立审核、当前来源 V34/V35 手工平衡的事件，再核对 V63 返回的每个 `itemId/quantity/disposition` 是否逐项等于已冻结的 V34 原始行。不能用两个同 SKU 但不同订单行 ID 冒充同一数量。

`RefundOrderConservationEngineV210` 用 Java `BigDecimal` 计算原币带符号退款总额，并将相同商品各事件手工退货数量相加，与当前订单行数量比较。即使多个事件各自都通过 V63，若**累计人工退款数量超原单、原订单行不存在、一个原件/事件重复、来源类别变化、V34/V63 数量或处置漂移、有未识别的 V34 孤儿事件或 ANY 一笔退款没有独立完整审核**，则：

- 保留原始来源金额、事件和冲突诊断，不能用 `0` 掩盖；
- **该订单所有币种的 `historicalCostExposureUnverified` 与 `claimedRestockCostSensitivityUnverified` 汇总均 `null`**，对应事件级的失效风险也不显示过时敏感度；
- 每个结果都写明 `CROSS_EVENT_REFUNDED_QUANTITY_EXCEEDS_ORIGINAL`、`V34_V63_REVIEW_QUANTITY_OR_DISPOSITION_DRIFT`、`REFUND_AMOUNT_OR_CURRENCY_CHANGED_SINCE_SNAPSHOT`、`ORPHANED_OR_CHANGED_REFUND_REVIEW_SOURCE`、`ORIGINAL_LIVE_ITEM_QUANTITY_UNKNOWN` 等原因；
- 即便全部本地人工数量和钱款恰好守恒，所得也只是**手工历史成本风险敏感度**，实际批次成本/退货回转/银行清算/净利润仍缺真实外部授权与 G1 口径。

聚合**永假**：`fullFinancesRefundCoverageVerified=false`（只见已分类且匹配本订单的 LIVE 来源，不知道是否漏掉未分类 Finances 行）、`independentlyVerifiedActualBatchCogs=false`、`physicalReturnReceiptVerified=false`、`actualCogsReversalsAllowed=false`。**`actualCogsReversal=null`，`realizedNetProfit=null`。**

## 新只读 Owner API

`GET /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/refund-cogs-conservation`

沿用现有 Session、Owner、租户与 Marketplace 范围、`Cache-Control: no-store`。返回：

- 来源事件与手工审核总数、跨事件数量冲突数、每订单行的原订购数量和人工累计退款数量（包括不同事件数）；
- 每一原币的原始带符号 `PRODUCT_REFUND` 总额，以及仅在**整单本地人工证据一致**时可显示的*未核证历史成本/退货敏感度*，**没有 FX 汇总、没有利润挂账**；
- 每个事件的 V197 快照核对、V34/V63 数量一致、缺失/超额/旧来源阻断，以及固定真实财务门禁；
- 返回整单 `completeLocalManualConservation` 仅表示本地证据一致，**不表示**实际退款来源完整或任何真实会计准入。

此 API 不更改任何 V34/V35/V63 现有证据，不提供批量审核/撤销/删除/过账接口，不改退款识别、不发起 Amazon Seller/Ads、WMS、银行网络请求。

## 回归测试（与本次代码精确 SHA 绑定）

- Java `RefundOrderConservationV210Test`：两笔部分退款按 SKU/订单行累计数量 2+1≤5、signed USD -20/-10、claimed restock 成本风险敏感度 16、原始历史成本风险 24；跨事件 3+3>5、V34/V63 数量偏移、旧退款金额/币种/事件/订单漂移、V34 孤儿审查、缺 V63、原单数量未知、正数“退款”、不同币种不得汇率合并、重复来源/行、无源不能当完整，以及 Scope/no-store。
- PG18 `MERCHIVRA_V210_DISPOSABLE_ONLY=true python3 scripts/smoke_refund_order_conservation_v210.py`：在安装现行 Flyway 的**实际 PG18** 上，用 V210 Java 文件内三个真实 SQL text block，对 TEMP 合成 LIVE 订单行、两次退款、未匹配事件、其他订单、其他租户、已过时/合成订单行做全源检查；变更当前订单行数量应让重算看到缩减与多次退款数量冲突。最后 `ROLLBACK`。
- CI 同一精确提交的 Java25/Next16/Chromium/PG18/Flyway/HTTP/Compose 完整 A3、匿名 401 拦截；快 CI 中 PG18 跳过不能视作专项成功。V210 无新增 Flyway，不能声称 V112 或生产资金真实核证。

## 未完成与下一步 V211

即使 V210 完整通过，真实 `realizedNetProfit` 依旧不能计算：Amazon/Ads/银行外部真实来源未授权核实，退款 COGS 库存收回与实物处置未获 B2/B3、G1/DEC-06/07/08 会计签署；历史 SKU 单价估值不等于实际某收货批次的净入库成本。下一阶段应把整单风险诊断接入 V64/V188 的**版本化利润/敏感度阻断汇总**，审查跨期间双重费用归属与退款计入重复的问题，绝不能直接写实账本。

**正式评分 70/100；`realizedNetProfit=null`；真实 Amazon Seller/Ads OAuth OFF；生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) NO-GO。**
