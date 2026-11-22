# Merchivra V198 · 结算编号唯一归属与来源完整性冲突拦截

**状态：2026-11-22 · 代码实施并待对应精确 main SHA 验证。** 这次只改已有 V58 报告 / V31 Finances 引用的只读比较判定、Next.js 展示与合成回归。不发起 Amazon/Ads 调用、不增会计账本、不得解除外部验真或生产门禁。功能验证的精确提交、快 CI 与完整 A3 应在完成后另行记录。

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
