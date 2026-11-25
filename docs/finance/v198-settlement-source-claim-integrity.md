# Merchivra V198 · 结算编号唯一归属与来源完整性冲突拦截

**状态：2026-11-22 · V198 代码及隔离合成 A3 已完成。** 这次只改已有 V58 报告 / V31 Finances 引用的只读比较判定、Next.js 展示与合成回归。不发起 Amazon/Ads 调用、不增会计账本、不得解除外部验真或生产门禁。验收：[精确代码 `11864a7`](https://github.com/kunlong-luo/merchivra/commit/11864a739451f0e91c0bd9639b2c4bfe441b518e) 的 [完整 A3 #45021678686（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45021678686)，及 [同 SHA 快 CI #45021678580](https://github.com/kunlong-luo/merchivra/actions/runs/45021678580) SUCCESS。最初 `f5bc560` 的 [快 CI #45021496206](https://github.com/kunlong-luo/merchivra/actions/runs/45021496206) 和 [A3 #45021496225](https://github.com/kunlong-luo/merchivra/actions/runs/45021496225) 曾因旧 Java 构造测试与旧 Vitest 响应夹具缺五项计数而失败；修复的是测试数据，不是删除冲突守护。旧失败结果保留作为回归证据。

## 为什么要修复，不重复造一个财务流程

V58 的 `spapi_settlement_report` / `spapi_settlement_report_line` 已保存原始报告编号、文档 SHA-256、声明净额和明细数。V31 的 `finance_transaction_reference` 保存 Finances 的 `SETTLEMENT_ID` 文本引用；已有对账比较只按租户/Marketplace/结算 ID/币种加总。V56 已提供独立 Owner 双人审核，且最终 approve 时会防止同一结算 ID 的多报告归属，**本轮不复制这层审批**。

实测代码审计可见以下尚未覆盖的假阳性入口：

1. V31 的主键仅阻止同一事件+同一 SETTLEMENT_ID 值重复写入；**一个 Finances 事件可以持有不同的 SETTLEMENT_ID**。旧比较把它算入匹配报告，可能同时支持另一份报告。
2. 相同 `settlement_reference` 在同站点多份报告中出现时，旧只读比较不显示这一冲突；即使人工 approve 后端已有拦截，前端仍可错误显示金额一致。
3. V54 报告导入时冻结的 `line_count` 与当前明细行数可能分离；特别是缺失一行零金额明细，**SUM 仍可能完全相同**，旧比较没有行数守护。
4. 若旧表或后续数据库数据出现不同原币的报告明细行，单纯相加会产生无意义的混币金额，必须在报告端单独拦截。

## 复用现有服务实现

在 `SettlementReportComparisonService.COMPARISON_SQL` **同一 SQL 语句**按租户、Marketplace、当前报告进行完整范围检查，新增五个非负证据字段：

| 返回字段 | 证明的只是本地事实 |
| --- | --- |
| `expectedReportLineCount` | 导入报告时存储的预期行数 |
| `actualReportLineCount` | PostgreSQL 实际报告行数 |
| `foreignCurrencyReportLineCount` | 报告内不属于报告原币的明细行数 |
| `multiplyReferencedFinancesEventCount` | 属于当前 Settlement ID、但同一事件还拥有其他 SETTLEMENT_ID 的 Finances 交易数 |
| `otherReportSameSettlementCount` | 同租户/Marketplace 使用同一结算编号的**其他**导入报告数量 |

冲突优先于金额相等，新增：

- `REPORT_LINE_COUNT_MISMATCH`：预期/实存数量不同；`REPORT_LINE_CURRENCY_CONFLICT`：报告内部混币；
- `FINANCES_EVENT_MULTI_SETTLEMENT_CLAIM`：同一 Finances 事件属于多个结算参考；`SETTLEMENT_REFERENCE_SHARED_REPORTS`：同编号多报告竞争。

只有无上述矛盾、原报表声明/明细及 Finances 来源金额均数值一致时，才继续返回**原有** `SOURCE_AMOUNTS_AGREE_UNVERIFIED`。仍不是 `VERIFIED` 或银行确认。后端 `reconciled=false`、`bankVerified=false` 恒不变。现有 V56 审核会在比较状态变化后将先前证据标为 `STALE`，且 approve 仅允许 `SOURCE_AMOUNTS_AGREE_UNVERIFIED`。禁止为了规避冲突修改历史 Flyway 或回填多结算归属。

Next.js 原有结算报告工作台显示数值和中文原因；`settlement-comparison.ts` 与 `settlement-source-review.ts` 的 Zod 联动同步新状态和字段，拒绝把缺少冲突计数的响应默认解释为“0 个冲突”。没有第二套前端组件、认证或 API。

## 测试及限制

- Java `SettlementReportComparisonServiceTest` 包含原有原币/缺来源/错额检查及新增报告行数异常、内部混币、交易多结算引用、多报告相同 ID、无冲突金额一致的负例，检查 SQL 的租户/Marketplace 关联条件。
- pnpm/Vitest `settlement-comparison.test.ts`、`settlement-source-review.test.ts` 分别保护只读请求、冲突字段必填、状态枚举和“不能伪造银行已确认/对账完成”。
- **真实 PG18 SQL**：`MERCHIVRA_V198_DISPOSABLE_ONLY=true python3 scripts/smoke_settlement_source_identity_v198.py`，从 Java 原文件提取 **COMPARISON_SQL 原文**，在同一 PostgreSQL 18 会话的 TEMP 表和最终 ROLLBACK 中检测同交易两个结算 ID、同 ID 两份报告、跨租户/站点隔离、缺零金额行与内部混币。既不导入真实 Amazon 文件，也不更改公共业务表。
- **仍未完成的商业链路**：有合法授权后的 Amazon Settlement/Finances 交叉证据独立验真、到账银行真实核证、已批准的退款 COGS/税费/外币会计处理和可过账利润。历史多归属**只生成诊断/拦截，不自动裁决或删除**。`realizedNetProfit=null`，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) 继续 **NO-GO**。

现行开发规范以 [AGENTS.md](../../AGENTS.md)、[ENG-001](../governance/engineering-standards.md) 为准；评分仍按 [68/100 基线](../governance/project-scorecard-2026-11-22.md) 审视外部真值，不因仅增加合成测试而自动提高。


## V271 · 订单原始参考分组守恒（待验收）

V271：在 V198 同 SQL 快照比较中对当前 tenant/marketplace/report 的原币报告行及精确 settlement ID LIVE Finances 交易按原始 external_order_id 分组，NULL 单独分组、不得猜测归属。总额一致但任一分组缺一侧（含零金额）或原币净额不同，返回 ORDER_REFERENCE_AMOUNT_DIFFERENCE，旧 V56 只允许 SOURCE_AMOUNTS_AGREE_UNVERIFIED 的批准规则继续阻断；保留原状态优先级。V56 指纹升级 V271（旧审核需重审，不回填），包含报告实际明细、声明净额、Finances 原订单参考/类别/source_type/UTC epoch；状态读取 RR，未宣称全域并发或 ABA 证明。Next 原工作台及 Zod 接受新阻断状态；扩展 V198 真 PG18 TEMP/ROLLBACK 原 SQL、Java/Vitest 反例，待 A3。属于订单净额分组一致性门禁，不等同逐行匹配或证明全部费用、非订单费用、实际到账、FX/COGS/会计签署；真实 E1–E5 未完成，realizedNetProfit=null、Seller/Ads OAuth OFF、#104 NO-GO。


V271 [代码 6a17618dfbd2](https://github.com/kunlong-luo/merchivra/commit/6a17618dfbd2156c9b0b8f36b076e9ddbe423ad9) [快 CI #45611812174 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45611812174) [完整 A3 #45611813480 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45611813480)，[只读 Pages #45611811764 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45611811764)。

## V272 · 末级明细与正负方向守恒（待验收）

V272：现有 V198/V271 比较单 SQL 快照对每笔精确 Settlement ID、同 tenant/marketplace/currency LIVE Finances 事件核验 1–2000 个末级明细、非 NULL 金额/原币一致、末级净额等于事件净额；父节点不计入金额。缺口 FINANCES_LEAF_COVERAGE_INCOMPLETE，覆盖后按原始订单参考（NULL 单独）+金额正负方向核对 Reports 明细与 Finances leaf 合计，差异 ORDER_DIRECTION_AMOUNT_DIFFERENCE。零额无正负方向，仍由 V271 分组覆盖约束。保留旧阻断优先级、V56 审批仅允许 SOURCE_AMOUNTS_AGREE_UNVERIFIED。V272 指纹含当前全部拆分节点的 path/parent/type/amount/currency/is_leaf，旧指纹失效，未回填；前端 Zod 和提示同步；Java/Vitest 及 V198 真 PG18 TEMP 回归待 A3。此为金额方向守恒，不把正数一律认定商品收入、负数一律认定佣金，不凭名称猜测跨来源费用分类；同方向类别抵消、逐行身份、item/SKU 归属、实际原件与会计批准仍未解决。缺历史拆分的审核会阻断，须合法重新获取完整来源。独立 Redis smoke V270 失败具体原因未核实，不改测试或宣称修复。realizedNetProfit=null、OAuth OFF、#104 NO-GO。


V272 [代码 75abfb7b8993](https://github.com/kunlong-luo/merchivra/commit/75abfb7b8993d2c47042504c82d15d8499df02cf) [快 CI #45615522059 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45615522059)，[只读 Pages #45615522250 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45615522250)，[完整 A3 #45615522448](https://github.com/kunlong-luo/merchivra/actions/runs/45615522448) 核对时仍运行中。

## V273 · 显式跨来源费用分类预检（待验收）

V273：新 POST category-comparison-preview（Owner Session/CSRF/no-store，只读 RR），复用 V272 全源比较及现有 V28 ACTIVE Finances leaf source_type 分类，Reports 显式输入完整 transaction_type/amount_type/amount_description 三元组映射（最多200，不预设 Amazon 对应关系）。按原始 order_ref（NULL 单独）+V28 category+金额方向（含零）独立比较，任一缺映射、UNCLASSIFIED、未使用映射或超限（30000行）不给类别总额；完全映射后报告冲突分组数和原币分类双方合计，不暴露订单编号。相同方向类别抵消可识别，但 V28 现有五类仍粗粒度，同一类内部佣金/其他子费用不能区分。映射仅为本次请求假设，不持久化、不写入 V28、没有独立批准/版本审计或接入 V56 审批门禁；mappingApproved/bankVerified/ledgerPosted=false，realizedNetProfit=null。未新增迁移、前端 UI 或第三方依赖。JUnit 合成金额反例及既有 V198 真 PG18 TEMP SQL 扩展验证站点/租户、当前 ACTIVE、末级和未映射，待 A3；真实 HTTP 授权和真实财务样本未验收。V270 Redis smoke 失败仍未修复。

### 本地 Owner API 合同

`POST /api/v1/finance/marketplaces/{marketplaceId}/reports/{reportId}/category-comparison-preview`，使用现有 Session Cookie/CSRF 与 JSON 请求数组。不接受 tenantId。下面仅为合成类型演示，真实映射须根据合法原件和财务政策复核，不可视为 Amazon 官方分类：

```json
[{"reportType":{"transactionType":"Order","amountType":"ItemFees","amountDescription":"Commission"},"category":"AMAZON_FEE"}]
```

实际请求需覆盖该报告全部原始类型组合，Finances 末级分类由当前 V28 ACTIVE 规则读取。`CATEGORY_MAPPING_INCOMPLETE` 不返回局部总额；`ORDER_CATEGORY_AMOUNT_DIFFERENCE` 表示按订单/类别/方向至少一个分组差异，汇总两侧金额仍可能抵消。`LOCAL_CATEGORY_AMOUNTS_AGREE_UNVERIFIED` 只说明给定映射下本地金额相符。无授权、无真实外部证据、无会计签署时禁止把结果用作正式利润。尚无持久化映射与 UI，后续基于真实原件设计独立审核、版本及失效契约，而非自动批准。
