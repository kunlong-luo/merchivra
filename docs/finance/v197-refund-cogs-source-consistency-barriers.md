# V197 · 退款 COGS 冲回的逐笔证据阻断与跨事务来源一致性

**状态：2026-11-22 实施说明。** V197 初始实现 [\`02828d0\`](https://github.com/kunlong-luo/merchivra/commit/02828d0f8f4f23611ddba247ef97abacb2d610d2) 对应 [快速 CI #45020050129](https://github.com/kunlong-luo/merchivra/actions/runs/45020050129) **SUCCESS**；服务级回归及后续完整 A3 应以各自最新精确 main SHA 和实际结果为准。 本次只完善 V170/V63 的只读风险判定，不批准 G1 冲回政策、不新增审批表、不产生库存/COGS/GL 写入。真实 Seller/Ads OAuth 和银行独立核证仍关闭；生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**，`realizedNetProfit=null`。

## 先检查现有链路而不是再造一套

- V34/V35：人工商品退款数量、原币退款金额的归属与历史 SKU 单位成本来源；[V170 原说明](refund-cost-readiness-v170.md) 标准化了来源筛选、按原币汇总和 50 条 keyset。
- V63：已存在发起人/独立 Owner 审核、来源哈希与修订、`REVIEWED/STALE`，并提供 `historicalCostExposure` 与 `claimedRestockCostSensitivity`；任何人工回库声明都**不**是物理实收、正式退货 COGS 过账。
- V170 为避免 V63 正常业务 409 使外层 `REPEATABLE_READ` 事务 rollback-only，先完成原始 Finances 事件快照，再**在事务外**逐事件复核。这是必要的隔离，但两次读取之间可能发生人工订单归属或来源状态变更。

## V197 修复的两个具体缺口

1. **跨事务身份和金额漂移**：在 V170 来源快照与 V63 当次复核之间，使用同一事件 UUID、原订单 UUID、币种、原始有符号 `BigDecimal` 退款金额逐项核验。金额按数字比较，不因 `-20.00` 与 `-20.000000` 的不同小数位数判错。事件、订单、币种或金额不符时，返回 `evidenceState=STALE`、`evidenceCurrent=false`、金额敞口/回库敏感度 **null**，并标明 `REFUND_SOURCE_CHANGED_BETWEEN_SNAPSHOT_AND_REVIEW`；不能让两个时刻不同的事实拼成可信 COGS 证据。
2. **缺少可操作的阻断代码**：新增只读 `cogsReversalBlockerCodes: string[]` 到现有 V170 单事件响应，不另建会计引擎。区分无匹配订单/不同事件、原币金额漂移、V34/V35/历史成本不完整、独立审核未完成/已过期、退款商品处置 `UNKNOWN`。**每条事件始终保留以下四个硬阻断**，即使独立 Owner 已审且人工回库敏感度完整：
   - `EXTERNAL_REFUND_SOURCE_AUTHENTICITY_UNVERIFIED`：现有 `LIVE` 来源标签不等于独立第三方来源真实性证明；
   - `PHYSICAL_RETURN_RECEIPT_AND_DISPOSITION_UNVERIFIED`：本地人工回库声明不是可信 WMS/FBA 物理收货；
   - `G1_REFUND_COGS_REVERSAL_POLICY_NOT_APPROVED`：DEC-06/07/08 及实际退款成本回转时点、可售处置、批次版本、跨期等尚未签署；
   - `INVENTORY_AND_ACCOUNTING_POSTING_NOT_AUTHORIZED`：尚不具备真正库存/COGS/账务过账的权限或证据。

原有 `actualCogsReversalAllowed=false`、`actualCogsReversal=null`、`bankReconciled=false`、`realizedNetProfitVerified=false` 等字段保持原状。服务端仍按租户/Marketplace/Owner Session 权限、`no-store`、UTC 半开区间、按原币汇总，不把退款当新增成本重复扣除。客户端 Zod 要求结构化阻断数组非空并拒绝伪造 `actualCogsReversal`，Next 已有退款风险表仅展开查看阻断原因，**未添加利润卡、自动冲回操作**。

## 测试与未完成事项

- `RefundCogsReversalGateV197Test`：无复核、来源快照订单不符、相同订单但币种/有符号金额不符、数值等价的小数精度、过期/驳回/未知处置、同人审批、人工审核已通过但外部/实收/G1 仍不可放行，以及乱报的物理/会计状态不能覆盖硬阻断。
- `RefundCostReadinessServiceTest`：保持服务端 LIVE/UTC/Owner/no-store/金额 null、双事务边界，并断言引入来源核对；`RefundCostReadinessServiceV197Test` 直接模拟 V63 在两次读取之间出现事件/订单/币种/金额漂移，验证真实 `describe` 接线会使 `evidenceState=STALE` 且金额敏感度为 null；正常 409 是明确缺证，不导致整个来源列表 500。前端 `refund-cost-readiness.test.ts` 验证同源 GET、来源有符号原币、硬阻断保留及无阻断或假造冲回时 Zod 拒绝。
- **仍未实现**：真实外部第三方退款/实物收货核证、退款与结算行独立防重匹配、G1 批准的货物批次/历史成本回转规则、跨期会计以及真正的双人认可库存与会计过账。**这些缺口不是再补一份本地 SHA 或 UI 提示即可关闭**。当前评估分数仍以 [68/100 基线](../governance/project-scorecard-2026-11-22.md) 为准，不能将本轮只读判定视为正式利润功能完成。
