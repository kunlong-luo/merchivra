# V164 · 手工成本凭证分摊、未分摊余额与双人审批

**当前交付：Flyway V92、Java 精确金额守恒引擎、基于 V163 已复核手工凭证的不可变分摊方案、第二名 Owner 审批/驳回、审计与 HTTP 接口。** 这仅是可追溯分摊的本地工作流；**绝非**真实 Amazon Ads 费用核验、已入账费用、完整经营利润或会计报表。本版本不会自动扣减 V33/V64 的贡献额或将 V163 手工费转换为 V162 的真实 `REVIEWED_COST`。

## 核算语义

已记录在 `finance_cost_source_v91` 的源费用金额 (正数、原币、八类额外成本) 仍处于 `MANUAL_UNVERIFIED`，必须先由与来源登记者不同的 Owner 将其本地元数据审核为 `REVIEWED_UNVERIFIED`。之后可以创建 **按一种且仅一种维度** 的方案：

- `SKU`：每行指定一个本租户有效的产品 SKU `skuId`；此分摊是 SKU 维度的分布，而不自动证明该 SKU 在全部 Marketplace 的成本归属
- `ORDER`：每行指定一个确实属于来源 Marketplace 的订单 `orderId`
- `PERIOD`：每行指定半开业务期间 `periodAfter`（含）到 `periodBefore`（不含），最多 92 天

同一来源一次**不得同时用 SKU + ORDER + PERIOD 不同维度重复发放成本**；若以后需同一费用的不同维度视图，必须从受审单一事实衍生可追溯投影，而不是再扣一次。

`allocated = Σ allocationLines.amount`，`unallocated = source.amount − allocated`，必须 `0 < allocationLines.amount`、`allocated ≤ source.amount`，严格同一原币，精确到六位小数，不进行浮点舍入/隐式汇率换算。**API 的 sourceAmount、allocatedAmount、unallocatedAmount 统一以 6 位小数序列化（例如 7.250000，而不是 7.25）**，以消除源字符串小数位不同导致的报表快照差异。100 条以内、同一目标禁止重复，同一成本来源最多一份**待审或已审**方案。`unallocated > 0` 时显示 `PARTIAL_WITH_REMAINDER`；`unallocated = 0` 时显示 `FULLY_ASSIGNED_UNVERIFIED`，仅意味着这份人工来源已完全分配，**不代表全成本覆盖或实际利润可靠**。重复请求使用 UUID `requestKey`；完全相同的来源+方案+金额返回已有版本，不同事实返回冲突。

## 数据层保障

- V92 `finance_cost_allocation_batch_v92` 和 `finance_cost_allocation_line_v92`，复合租户/来源、租户/订单和租户/SKU 外键；SKU、订单、期间具有互斥的 CHECK 约束。
- `UNIQUE NULLS NOT DISTINCT` 防止利用 NULL 多次分配同一目标；部分唯一索引确保一个来源无法同时存在第二个待审或已审计划。被驳回的方案完整保留，可用**新的 requestKey** 建立新版本。
- 数据库 INSERT 守卫再次核对来源在 `REVIEWED_UNVERIFIED`，行的原币及目标类型一致，订单 Marketplace 匹配；UPDATE/DELETE 行一律拒绝改写。数据库在最终批准时重新计算 `SUM(expense_amount)`，阻止空方案和超额分摊，拒绝第二次审核和变更原始来源/提案字段。
- 审批者必须是另一位 Owner，且**同时不同于来源登记者与分摊提出者**。审批结果是 `APPROVED_UNVERIFIED` 或 `REJECTED`，不可逆转；批准结果不等于第三方真实费用已核验。审计只保存固定操作码、操作者 UUID 和时间，不复制单据或审核理由。
- 事务里先锁源费用行，再写入批次/明细与审计；并发批次依靠 PostgreSQL 源锁和部分唯一索引共同阻断；API 不暴露租户 ID 作为授权输入。所有响应 no-store，写操作继承现有 Spring Session/CSRF。

## HTTP API（仅 Owner）

路径：`/api/v1/finance/marketplaces/{marketplaceId}/cost-sources/{sourceId}/allocations`

- `POST /`：`{"requestKey":"UUID","targetKind":"SKU","currency":"USD","lines":[{"skuId":"UUID","orderId":null,"periodAfter":null,"periodBefore":null,"amount":"3.14"}]}`；ORDER 使用 `orderId`，PERIOD 使用两日期；必须在一份方案里使用同一种维度。
- `GET /`：最近 50 个版本，包含当前状态、原币金额、已分摊/未分摊金额及明细（不是全库利润报表）。
- `POST /{allocationId}/decision`：`{"accept":true,"reason":"Independent invoice distribution review"}`；不可自审/重复审核，失败不应修改已冻结来源。
- `GET /{allocationId}/audit`：最近 100 条追加审计动作。

## 测试与剩余风险

`CostAllocationEngineTest` 覆盖 BigDecimal 部分与全部守恒、重复目标、超额、混用 SKU/ORDER/PERIOD、非法期间/精度、零与负数、Owner Session/no-store、来源创建人及方案提交人都不可审批，以及 DDL 防篡改约束。

**仍未实现并且不能冒充完成：**真实第三方费用证明、发票文件真实性、Amazon Finances / Settlement / Ads 跨来源防双计与退款资本化规则、按 SKU/订单/期间多维无重复的完整利润账、V162/V64 接入、Next 成本分摊界面和多维报表、真实 G1 财务审核。即使方案 `APPROVED_UNVERIFIED`，也不自动计入实际利润。

本轮改变 PG18 结构及财务写入接口，需要合并后**精确 main SHA** 完整 A3（真实 PG18/Flyway、Session/CSRF、Next/Vite Chromium 和合成 HTTPS）以及后续独立业务规则审查。合成 A3 不等于本功能所有真实 HTTP 审核和财务业务数据已经独立验收。

[利润 P0 #12](https://github.com/kunlong-luo/merchivra/issues/12) · [V163 来源审核](cost-source-review-v163.md) · [V162 已知费用贡献预览](full-cost-coverage-v162.md) · [生产 NO-GO #104](https://github.com/kunlong-luo/merchivra/issues/104)
