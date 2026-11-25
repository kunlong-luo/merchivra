# Merchivra V216 · 采购收货单人工费用来源完整性与跨来源重叠只读核对

**2026-11-24 / 无 Flyway / Owner Session GET / 不新增费用账本或审批。** 本阶段仅核对 V215/V112 的本地费用归属提案，并复用 V165 八类跨来源身份碰撞检查。[V216 精确代码 `64b7dd5`](https://github.com/kunlong-luo/merchivra/commit/64b7dd51cee3944888a31f34bdd6373c627c3847) · [同 SHA 快速 CI #45302466898 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45302466898) · [A3 #45302466911（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45302466911)。真实 PG18/Flyway 的合成 TEMP 测试明确输出 `PASS V216 PG18 exact receipt/V91/V92 evidence SQL` 及 `PASS V216 anonymous Owner GET 401`；不得因合成测试将任何 V91/V112 金额升级为真实 landed cost 或 COGS。

## 不重复造轮子的核查

V112 已有精确 V20 采购收货行 FK、同来源最多 100 行原币守恒、审核信息、不可变审计、V92 双向互斥。V165 `CostIdentityReconciliationService.previewBatch` 已提供同租户+Marketplace 的八类潜在重复身份：其他 V91 文件摘要、人工 Ads 键、离线 Ads 来源键/摘要、Finances 来源键、结算报告引用/摘要及人工结算引用，并跟踪可能被两份人工费用共同声明的同一结算报告行。V166 的独立碰撞案例是已有治理，不另建一套审批。

## V216 后端只读接口

`GET /api/v1/finance/marketplaces/{marketplaceId}/purchase-receipts/{receiptId}/manual-cost-integrity`

- Session/Owner/tenant 权限来自既有 `TenantAccessService.currentTenant`，响应 `no-store`，只读 GET。V20 receipt **只以 tenant 定义**，不把其误称 Marketplace 独享；请求 Marketplace 仅严格限定 V91 人工来源的 `seller_marketplace_id`，其他店铺来源不可混入。
- 先检查 active tenant + Marketplace + V20 receipt 身份。以 `finance_receipt_cost_line_v112` 定位此 receipt 的全部提案（`ORDER BY p.id LIMIT 51`，多于 50 返回 HTTP 409），并从 V91 的原币和现态读取 source。对每份 plan **跨全部关联收货单**独立聚合 `SUM(amount)`、总行数、当前收货/PO 及币种一致行数、源原币/金额版本、V92 活跃分配冲突及 V91 与 V112 的 append-only 审计存在性，而不是只计算当前 receipt 的金额或当前页。
- 同一个 RR 快照使用已存在的 V165 **批量 1–50 源**校验八类潜在重叠及共享 settlement line，不泄露其他来源的原始发票键或未经允许的完整文档；两次独立 RR 读取 V112/V91/V92 与 V165 结果，跨读变化返回 HTTP 409。双读不保证已证明外部账单真实性，也不保证两个独立事务间不存在 ABA 变化。
- 每项状态明确为 `LOCAL_RECEIPT_ALLOCATION_INTACT_UNVERIFIED`、`POTENTIAL_EXTERNAL_CHARGE_IDENTITY_COLLISION_HOLD`、`LOCAL_RECEIPT_EVIDENCE_STALE_OR_UNAUDITED`、`PENDING_LOCAL_REVIEW` 或 `REJECTED_LOCAL_PROPOSAL`，不会使用 `VERIFIED` 作为外部真值。源金额/币种、receipt PO 币种、分配总额、V91/V112 审计或 V92 活跃冲突失配均阻断。仅相似来源身份不等于真实双重扣费；没匹配也不能证明所有真实费用均已采集。
- 逐 receipt 返回的原币金额字段明确命名为 `localReceiptCandidateAmount` 与 `localAllReceiptsCandidateAmount`；这是本地人工提案金额，不是已核证单位 landed cost，也**不与 V64/V188 利润聚合相加**。若没有来源，`NO_LOCAL_RECEIPT_COST_PROPOSAL_UNKNOWN_NOT_ZERO`，不能把缺证当零成本。
- 所有响应固定 `completeExternalCostCoverage=false`、`receiptLandedCostVerified=false`、`actualBatchCogsVerified=false`、`actualBatchCogs=null`、`postingAllowed=false`、`realizedNetProfit=null`。V112 仍无对外写/审批 API，V216 无真实 Amazon、银行、承运、海关核证。

## 测试范围与记录原则

- Java `ReceiptCostIntegrityV216Test`：完整原币参考与未分配尾额、审核仅本地有效、跨来源信号保持 hold、当前 PO/来源币种与金额漂移、V92 重叠、V91/V112 审计缺失、重复计划或不完整 V165 源 fail-closed、no-store Owner、无计划 UNKNOWN 非零。
- PostgreSQL 18 `MERCHIVRA_V216_DISPOSABLE_ONLY=true python3 scripts/smoke_receipt_cost_integrity_v216.py`：在 Flyway V112 已应用的真实 PG18 上，以 **TEMP 影子源表**运行从 Java 服务提取的原始查询，验证整计划而非当前 receipt 求和、审核事件、跨币种、V92 活跃冲突、当前 PO 币种漂移、租户/Marketplace 隔离。使用 ROLLBACK，不把 SQL TEMP 测试说成真实外部数据/财务凭证。
- 完整 A3 已在上述精确 SHA 的 7 项 Job 全部 SUCCESS（PG18 实际运行而非 SKIPPED）；匿名 GET 401 有真实 Spring HTTP 原始日志证明。该结论仅限隔离合成数据，不可宣称外部费率/发票/结算真实性。不可拿上一阶段 V215 的精确绿色替代本次。

## V217 与发布边界

V217 前端已进入 [采购与财务工作台的按需检查计划](v217-receipt-cost-evidence-workbench.md)，Next.js 仅调用既有 V216 GET，不新建人工来源认证流程；V217 自己的测试需要独立新 SHA。

下一阶段优先把有根据的 V112/V165 审计结果接入 Next.js 采购/财务按需核对，而不是增加新账本或进行真实金额过账；另需核对不同 V91 金额是否属于同一外部费用的**可信外部证明**、G1 落地成本资本化/FX/跨期政策、退款物理原批次和 Amazon/银行真实性。缺乏这些证明，不允许自动打开实际成本、退款回转、已核实利润或生产写权限。

**正式评分 70/100 · `realizedNetProfit=null` · 真实 Seller/Ads OAuth OFF · 生产 #104 NO-GO。**

## 本轮工作流校验记录（2026-11-24）

首个 V216 提交 `3c118a4` 的 CI #45301958692 和 A3 #45301960709 均 **FAILED / 0 jobs**，后续 `9ccce8a` 同样 0 jobs；这属于工作流配置未被 GitHub 调度，不能记为运行 PG18 或 Java 测试。将 CI 恢复到上阶段可用基线后，`84ad784` 的 CI/A3 已实际创建 jobs；最小追加 V216 PG18 脚本调用的 `3c2a42d` 快速 CI 也创建 jobs。匿名 Owner 401 测试改在该 PG18 Python 脚本里通过真实 HTTP 执行（而不是继续扩张大段 YAML shell），完整 A3 及成功日志须在本轮新精确代码 SHA 上另行确认。

## V243 · 审核事件精确数量及职责独立性（待验收）

V216 复用 V229 的审计完整性原则：V91 必须恰好一条录入、一条独立审核，V112 待审恰好一条提案、终态恰好一条提案和一条独立决策，所有 actor 与冻结字段一致。重复、缺失、额外事件或计划审核人等于来源创建人/提案人时保持本地证据 hold，不修改历史或允许实际成本入账。沿用 V113 双 RR 版本，既有 API/响应字段不变。精确 Java SQL PG18 TEMP 负例与真实 V220 HTTP 夹具一并更新；V220 原夹具曾漏录 STAGED 审计，此次补齐合成夹具而非放宽规则。正式验收见 [当前事实](../current-status.md)。
