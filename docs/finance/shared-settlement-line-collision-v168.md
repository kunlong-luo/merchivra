# V168 · Settlement 同一交易行的多来源归属冲突隔离（P0）

## 交付范围与原则

V167 已能让手工 V91 费用关联 V54 Settlement 已入库的**具体交易行**。但同一个 `(tenant_id, marketplace_id, report_id, line_number)` 可以被两份不同的手工凭证关联。V168 专门解决**这个可观察的反向来源归属冲突**：为 V94 链接创建反向索引，在 V165/V164 审批时校验同一笔报告行是否出现不同手工费用来源，并提供可钻取的冲突行摘要。它不解决真实银行/Ads 发票同一性、是否真正重复扣费、VAT/汇率/退款、FBA 所有成本入账或净利润。

**规则：同一报告行被多来源关联，仅构成 `POTENTIAL_SETTLEMENT_LINE_COLLISION_HOLD`。** 即便两份来源金额不同、参考编号不同，也必须让财务人员查明原因；金额相等或引用相同**绝不**自动判定重复扣费或释放冲突。V167 的 `NOT_SAME_CHARGE_UNVERIFIED` 初判并非外部证明。

## 服务端精确范围和审批门禁

现有 Owner 只读 `GET /api/v1/finance/marketplaces/{marketplaceId}/cost-sources/{sourceId}/identity-check` 继续返回 V165 原有 8 类来源身份 `signals[]`，并增加：

- `linkedSettlementLineCount`：该来源已经登记的 V94 具体交易行总数。**不等于实际平台全部费用行已导入**。
- `sharedSettlementLineCount`：其中有**另一份不同 V91 来源**也关联同一 Marketplace + Settlement report UUID + line number 的行数；查询不因显示上限 100 而漏计。
- `lineChargeOwnershipVerified: false`：一律不声称真实唯一扣费归属或真实净利润。
- `approvalHeld: true`：任意原 V165 信号命中或 `sharedSettlementLineCount > 0`。只发生 V168 共用行、8 类线索均未命中时，`status=POTENTIAL_SETTLEMENT_LINE_COLLISION_HOLD`；若原有精确身份信号也命中，保留原 V165 状态并通过 `sharedSettlementLineCount` 同时提示。

新增 `GET .../identity-check/shared-settlement-lines`：返回该来源 `totalLinkedLines`、`sharedLineCount`、最近最多 100 条 `examples[]`（report UUID、line number、其他人工来源数量、本地证据状态）、`examplesTruncated`。**精确计数不受示例上限影响**；不泄露其他来源原始来源键、Amazon 订单编号、文件 SHA、买家 PII 或审核原因；Session Owner 推出租户、响应 `no-store`。

V164 `CostAllocationService.decide(accept=true)` 已在同一事务内持有来源行锁并调用 V165 的 `requireNoPotentialOverlap`，故 V168 自动在分摊批准前重读最新共用行事实、返回 HTTP 409、保留原 `PENDING_REVIEW` 和未改变的审计。拒绝费用方案无需绕过风险，不影响 V164 金额守恒。V95 同时加 PostgreSQL `BEFORE UPDATE` 防线，阻止绕过 HTTP 直接把当前存在多来源共用行的方案批准。**未授权任何 `CLEAR` 或确认真实双扣的状态**。

## PostgreSQL 18 迁移 V95

`V95__settlement_line_multi_source_collision.sql` 仅向前新增：

- `(tenant_id, seller_marketplace_id, report_id, line_number, source_id)` 反向索引。当前 V94 原始 UUID 关联和原币快照、唯一性、追加审计不变，不对多份来源强加“必然重复”的唯一约束。
- V95 行级触发器阻止对**已被 V94 费用来源关联**的原始 Settlement 交易行进行 SQL UPDATE/DELETE，避免金额、币种、参考键发生静默变化而旧本地分类仍在。
- V95 `finance_cost_allocation_shared_line_guard_v95` 数据库触发器阻止对已存在多来源共享行的分摊批次进行 `APPROVED_UNVERIFIED` 更新。其他未有外部真实性凭证的批次仍只能叫 `APPROVED_UNVERIFIED`。

## 并发、回溯、边界

这是**当前本地快照**的反向冲突隔离，不是永久的交易行独占领取。迟到另一份关联仍可进入来源库；已在此前批准的分摊不会被不诚实地改写为未经批准状态，但**下一次 GET 即会出现冲突和 `approvalHeld=true`**，且没有任何自动净利润归集。以后必须增加锁序、费用交易级唯一外部 ID、可撤销独立证明和报告冻结/重算，才能形成**真正不可双计财务账本**。对于来源从未关联 V94 交易行，`sharedSettlementLineCount=0` **不是不存在重复扣费的证据**。

V162 仍是纯成本覆盖预览，V64 是已知来源范围贡献报表；本次不把 V91/V94 的人工费用直接扣入 V162/V64、不会声称已核对 Ads/Bank，也不修改 Next.js 利润中心的实数金额。完成正式利润必须先有真实凭证认证、费用归属规则、税与退款/汇率决策、冻结版本与 G1 复核。

## 验收和上线

针对性 JUnit 测试：8 类 V165 线索数量兼容、单份链接无虚假确认、多来源共享行独立触发审批持有、Session/Cache-Control 不回归、DDL 不可变声明。现有 `scripts/smoke_cost_identity_v165.py` 增加真实 PG18/Spring HTTP 合成条件：不同来源键/不同 SHA 的新费用也因**同一具体报告行**进入 409；Owner 只读反向归属计数、跨 Marketplace 404、数据库直写审批和 Settlement 已链接行 UPDATE/DELETE 拒绝；旧 V167 测试仍运行。

**合并前的 CI 与合并后精确 main SHA 完整 A3 均必须以实际 GitHub Actions 结果为准**，不得将未运行的测试写成已通过。技术验收仅使用合成隔离数据，不能代替用户本地真实 Seller/Ads/银行验证。

生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) 保持 OPEN/**NO-GO**，真实 Amazon Seller/Ads OAuth/远程写入关闭；正式 ERP 仍以 Next.js 16 为目标，Vite 回退保留至正式目标切流独立批准。
