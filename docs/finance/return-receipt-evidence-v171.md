# V171 · 消费者退货实收核查单（人工证据；COGS 保守资格门禁）

## V171 精确主线技术验收（2026-11-16）

[功能 PR #153](https://github.com/kunlong-luo/merchivra/pull/153) 已合并到 `0b28fbaab4a28f865566c1f92a264609c4c85858`；该 SHA 的 [完整 A3 #43810447503](https://github.com/kunlong-luo/merchivra/actions/runs/43810447503) **run_attempt=1、7/7 SUCCESS**，包括 PostgreSQL 18 实际 Flyway V96、Spring Boot HTTP/Owner/CSRF、Java 25、Next.js16/React19、Chromium 与 Compose。

复核边界输入时发现 `Set.of(...).contains(null)` 可能导致 JSON 缺少处置状态时 HTTP 500，因此由 [修复 PR #154](https://github.com/kunlong-luo/merchivra/pull/154) 显式拒绝 null 并补 JUnit 及 PG18 HTTP 400 回归。最终代码 SHA `1b217cfb737567b6c06a47fce30ba1af9bfb59d7` 的 [完整 A3 #43810932033](https://github.com/kunlong-luo/merchivra/actions/runs/43810932033) **run_attempt=1、7/7 SUCCESS**。快速 CI [#43810746198](https://github.com/kunlong-luo/merchivra/actions/runs/43810746198) 亦成功。

这是**合成技术验收**：没有接通真实退货 WMS/FBA/FBM 数量和状态，没有独立外部退货签署，也没有真实 COGS 回转或银行/Ads/Seller 全源对账。`REVIEWED_MANUAL` 仍是未核验人工声明，净利润不予认列；G1/G2/G3 和生产 #104 继续 NO-GO，Amazon Seller/Ads OAuth OFF。

## 核心边界

V39/V40/V44 的本地仓库流水是采购收货、库内操作和采购收货质量管理，**不是消费者退货仓库实收**。V34 的 `RESTOCKED_CLAIMED` 只是人工声明，V63 的独立 Owner `REVIEWED` 也不能证明退货仓库真的收到了商品或商品已达到可售状态。

V171 的目标是先把**客户退款事件 → 原始订单商品项 → 历史成本/人工数量复核 → 实收声称 → 第二人核查**的来源链路显式记录，避免凭一条备注就冲回商品成本。不建立可信的 WMS、FBA/FBM 入库事实，不允许 V162/V64/Next 利润中心认列实际成本回转。

## 数据与规则

Flyway `V96__manual_return_receipt_claims.sql` 新增 `finance_return_receipt_claim_v96` 与 `finance_return_receipt_audit_v96`：

- 复合外键连接相同 tenant / Marketplace 的 `finance_source_event`、`sales_order`、V34 `finance_refund_item_review` 的完整 `(tenant,event,order,item)`；可选本地仓库 ID 只用作**核查地点线索**，对 FBA/FBM 不假设已有本地仓库。
- 必须先有**当前版本已独立 Owner 复核的 V63 退款证据**，金额准确分配且 `evidenceCurrent=true`；只接受 `RESTOCKED_CLAIMED` 订单项，申报 `claimedQuantity` 在 1 至该事件被审核的 `refundedQuantity` 间。每件商品有原订单商品行、当前 V63 审核版本、源证据 SHA-256、独立证据引用。
- 人工处置 `SELLABLE_CLAIMED` / `UNSELLABLE_CLAIMED` / `UNKNOWN`，每一种都表示**尚未验证的声明**。`PENDING` → 一次性 `REVIEWED_MANUAL` 或 `REJECTED`，要求另一个 Owner，且不能是该件 V34 退款数量、V35 金额分配的编制人。
- 数据库触发器限制核查单只有不可逆的单次决策；不允许直接修改件数、订单、来源或已决策状态，不允许删除核查单及其审计；同一事件/商品同一时间不能存在两个 `PENDING` 或 `REVIEWED_MANUAL` 核查单。拒绝记录永久保存，可以在提供新的材料后重新提交新核查单；历史不会覆盖。
- 只读返回动态对比当前 V63 审核版本、具体商品数量/处置、当前原币有符号退款、历史成本敞口和人工潜在回转额的指纹。任何来源变更均输出 `STALE`；不重新计算为库存或净利润。
- 数据库强制 `inventory_receipt_verified=false`、`cogs_reversal_posted=false`，接口强制 `physicalReceiptVerified=false`、`saleabilityVerified=false`、`cogsReversalEligible=false`、`actualCogsReversal=null`。**`REVIEWED_MANUAL` 不等于 `COGS_REVERSAL_ELIGIBLE`。**

## 接口及 UI

统一 Session Owner / CSRF（写）/ no-store；禁止用户自报 tenant 或审核人。

- `GET /api/v1/finance/marketplaces/{marketplaceId}/events/{eventId}/return-receipts`：最多 100 条核查单的当前状态；租户/Marketplace/真实来源不匹配 404。
- `POST .../return-receipts`：正文 `{itemId,quantity,condition,localWarehouseId?,evidenceReference,note}`。资料不完整/未满足 V63 复核，409；非法数量/编号，400。
- `POST .../return-receipts/{claimId}/approve` 或 `/reject`：正文 `{reason}`；独立 Owner 校验，同人或当前 V34/V35 作者禁止复核，旧证据/重复决策 409。
- `GET .../return-receipts/{claimId}/audit`：100 条以内不可变审计。
- React19 的 `ReturnReceiptEvidencePanel` 插入既有 `RefundRecoveryPanel`，Next.js16 与 Vite 回退复用；显示订单商品项/数量/声明/证据/双人决定/审计、明确不能冲回 COGS。Zod 强制所有“已验证”标志为 false。

## 事务与验收

遵循 V170 的失败经验：核查单的 `list` 先验证当前 LIVE 来源，再在**独立只读 V63 事务**中探测当前证据；正常 404/409 表示过期，不在另一个不可回滚的外层事务里吞掉错误。创建或决定时先读取 V63，经`REPEATABLE_READ`写事务锁原始 LIVE 订单和退款事件，再复核指纹，避免源金额/数量并发改写。V34/V35 在事务内校验独立复核参与人。

JUnit 覆盖单次审批、越权 Session、非法数量/处置、不可变 schema 与不记账静态保护；PG18/Spring HTTP 合成回归覆盖未关联来源不能创建、跨 Marketplace 404、无 CSRF 写请求被拦截、Flyway V96 两组不可变触发器存在。

**V171 技术验收不证明实物入库**。后续真正可以允许 COGS 回转，需要额外受信且不可变的退货仓库 WMS/FBA 货件/批次/数量证据，独立来源认证与归属，跨来源防重复、官方费用及汇率规则，G1 会计批准和正式冻结/追溯重算机制。未满足以前，G1/G2/G3 不放行，生产 #104 NO-GO，Amazon Seller/Ads OAuth OFF。
