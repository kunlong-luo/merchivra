# V201 · 跨退款事件数量守恒的读取时复核

**状态：2026-11-22 已实现且通过精确 SHA 合成工程验收。** [精确代码 `c47873c`](https://github.com/kunlong-luo/merchivra/commit/c47873cd1ad3e9963f57c9d69dc19b2ba5a17c90) · [快速 CI #45024903306 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45024903306) · [完整 A3 #45024903265（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45024903265)。 沿用 V34/V35/V63/V170 以及 V197 的审核和成本敏感度链路，不新增库存账或利润确认。

## 发现的风险与触发条件

V34 的 `RefundItemReviewService.save` 已按订单加锁并在**保存时**检查同一订单项、其他退款事件已审的数量，避免新操作超出订单原数量。但历史记录可能在随后失效——例如原 LIVE 订单项数量由 3 更新成 2，而此前不同 Finances 退款事件分别审核 2、1 件。这时**每个事件自己的数量都不超过 2**，V63 原代码只逐事件计算成本敏感度，无法重新证明跨事件总量仍守恒。

不能因此把同一已售商品的成本冲回两次，更不能把缺少最新 LIVE 订单行当零数量或完整来源。

## 复用 V34 来源与 V63 独立复核

- 在原 `RefundItemReviewService.snapshot` 内增加 `QUANTITY_CONSERVATION_SQL`，按租户 + 原订单 + 外部订单商品项，对**所有 V34 退款事件**的当前 `reviewed_quantity` 求和；仅允许当前有效、`data_origin='LIVE'` 的订单商品数量作上限。
- 原订单项无当前 LIVE 证据、数量缺失/不正、或多事件总数量超过该上限时返回 `REFUND_QUANTITY_ACROSS_EVENTS_CONFLICT`。不能用 `COALESCE(quantity,0)` 冒充确定证据，也不能只检查某一个事件。
- 有数量冲突时，已有 V34 的 `restockedCostSensitivity` 全部隐藏，Next 的原有退款审核页面直接显示冲突说明，禁止继续新建该退款的 V34 审核；已有审核和审计不删除，允许按既有人工流程先撤销 V35 归属，再撤销/修订 V34。
- V35 的原 `RefundAmountAllocationService.evidence` 和 V63 的 `RefundRecoveryService.evidence` 均要求 `MANUAL_REFUND_ITEM_REVIEW`，因此新冲突状态**不可能被当作可独立复核的来源**。V170 的风险队列仍展示未完成证据，不把缺失当零或真实 COGS 冲回。
- 本轮只补读取时 fail-closed 守护：不更改 V34 已有按订单锁的并发写入，不擅自追溯删除历史记录、不改变财务金额、不调用 Amazon/Ads，不对现有生产/GL/库存授权。

## 验证与未完成项

- `RefundItemReviewServiceV201Test` 保护状态优先序、跨事件 SQL 作用域和 LIVE/current 筛选。
- Next `refund-review-v201.test.ts` 验证明确冲突状态、隐藏成本敏感度，以及拒绝伪造的库存签收通过。
- PG18 `MERCHIVRA_V201_DISPOSABLE_ONLY=true python3 scripts/smoke_refund_cross_event_quantity_v201.py` **从原 Java 文件提取实际 SQL**，在 TEMP 表 + 最后 ROLLBACK 中验证 2+1=3 的完整场景、订单后改成 2 时冲突、不同租户/订单隔离、非 LIVE/旧订单项不作为数量证据，以及不同商品项的冲突。真实 PG18 原查询与上述案例在该精确 SHA 的完整 A3 中通过；合成数据并非真实 Amazon 退款。
- 真实采购批次/销售商品出库成本、退款与物理 WMS 入库签收、复数退货的最终会计政策 G1、银行结算仍未核验。**本轮阻断的是数据一致性假阳性，不是完成 COGS 冲回。**

`realizedNetProfit=null`，Seller/Ads 真实 OAuth OFF，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。复评分保留 [V200 的 70/100 基线](../governance/project-scorecard-v200-2026-11-22.md)，不因新 CI 自动加分。
