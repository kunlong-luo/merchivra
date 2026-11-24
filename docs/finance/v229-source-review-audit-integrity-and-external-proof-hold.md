# V229 · V91 原始来源双人审计完整性与外部证据准入边界

**2026-11-24：实现已提交 `main`，精确 SHA 真实 PG18 完整 A3 #45363362444 尚待验收。** 本阶段专门补齐在 [V228](v228-manual-cost-orval-batch-ux.md) / [V226](v226-bounded-owner-manual-source-batch.md) 的费用**暂存**入口之后，现有 V182/V183/V187 已经检查 V94/V102 结算关联、重复身份与报告行审核，却尚未提供直接针对 V91 **来源本身**的 STAGED + 一次独立复核审计链是否完整的只读检查。**本轮不重复实现 V182 结算关联分类，不新增来源真实性数据库字段，也绝不将本地核对结果提升到可信 Amazon/Ads/银行原始费用。**

## 真实缺口与最小实现

- `finance_cost_source_v91` 始终是本地 `MANUAL_UNVERIFIED`、用户声明的 `source_document_sha256`，没有真实文档字节核对、签发方/资金流身份。第二名 Owner 的 `REVIEWED_UNVERIFIED` 只表示本地元数据已审核，**不证明真实第三方费用**。
- V91 来源表的状态 CHECK 与独立 reviewer 约束、V92 附加的 `finance_cost_source_audit_append_only_v92` 只保证不删除、不可改写已写的审计，**不能保证 STAGED 审计存在、不能保证同一来源审计没有被额外追加两次**。若未来组件只判断来源状态，很容易把缺失审计的本地记录当作完整证据。
- 新 `CostSourceAuditIntegrityServiceV229` 复用 V91 source + audit、已有 `seller_marketplace` / `seller_account` / active tenant；在**一个 read-only REPEATABLE_READ** SQL 中对精确 tenant + Marketplace + source UUID 查询全量该来源审计。严格计数：STAGED 必须恰好 1 个与 creator 匹配的审计且总数 1；REVIEWED_UNVERIFIED/REJECTED 必须恰好 1 个 STAGED 与恰好 1 个**不同于 maker**的 reviewer/决策匹配审计且总数 2。缺/多/错误身份一律 `V91_SOURCE_AUDIT_INCONSISTENT`。
- 新只读 `GET /api/v1/finance/marketplaces/{marketplaceId}/cost-sources/{sourceId}/audit-integrity`（Springdoc operationId `getManualCostAuditIntegrityV229`）沿用已登录 Session tenant 推导、现有权限和 `Cache-Control: no-store`。不存在、跨租户或跨 Marketplace 来源返回 404，不返回原始发票、SHA/审核理由、其他租户身份。
- 每个成功响应必定包含 `CLAIMED_DOCUMENT_SHA256_NOT_BYTES_VERIFIED`、`INDEPENDENT_EXTERNAL_ISSUER_NOT_ATTESTED`、`EXTERNAL_CHARGE_NOT_RECONCILED`、`REAL_COGS_AND_PROFIT_POLICY_NOT_APPROVED`；`claimedDocumentBytesVerified=false`、`independentExternalIssuerVerified=false`、`externalChargeVerified=false`、`actualCogsVerified=false`、`eligibleForRealizedProfit=false`、`realizedNetProfit=null`，**即使 `localAuditComplete=true` 也绝不清除外部阻断**。
- 不新增 Flyway、不修改历史 V91/V92/V113 trigger、不删除 V182/183/187 独立风险/费用关联引擎、不改变 V162/V64、未批准的退款 COGS 或 Seller/Ads OAuth 开关。

## 真正的正反验收

`apps/server/src/test/java/org/merchivra/erp/finance/CostSourceAuditIntegrityV229Test.java`：六组测试覆盖 STAGED 1条，独立复核/拒绝的 2条，STAGED 缺失/重复/错误 maker、终态缺 reviewer 或不独立、终态 extra audit、Session 租户/不缓存以及 SQL 不含财务写操作。**这只是代码级状态分类与 HTTP Controller 测试，不代替真实 DB。**

`scripts/smoke_cost_source_audit_integrity_v229.py`：仅允许本仓库 `main` GitHub Actions、`ci-v222`、真正 disposable localhost PG18、固定一次性用户密码、SP-API/OAuth OFF 和显式 `MERCHIVRA_V229_DISPOSABLE_ONLY=true`。它先通过**真实 Owner/CSRF HTTP** 创建一个 1 来源 V91 batch，检查 V229 `GET` 返回本地 STAGED 审计完整、外部真实性和利润全为 false/null、no-store，GET 不增加 V113 租户版本。还要断言匿名 GET=401、跨 Marketplace GET=404、未知 source=404。在一次性 PG18 中额外构造**缺失 STAGED 审计的源**与**向合法源再追加一次 STAGED 审计**，验证前者 `auditEventCount=0`、后者 `auditEventCount=2` 皆为 `localAuditComplete=false` 且有明确审计阻断，同时不改变实际 COGS。测试**不得**在本地真实仓库数据中执行，也不允许禁用 PostgreSQL 触发器伪造“成功”证明。

完整 A3 必须重跑 V221–V228 先前真实 PG18/Owner 负例，打印 `V229_REAL_OWNER_AUDIT` 与 `PASS V229`，并在精确代码 SHA 的 **7/7** 成功后才宣告技术完成。当前 A3 运行：[V229 #45363362444](https://github.com/kunlong-luo/merchivra/actions/runs/45363362444)，结果尚待核实；之前 V228 7/7 不可以替代 V229。

## 项目评分与下一阶段

旧 [V228 73/100](../governance/project-scorecard-v228-2026-11-24.md) 是现行最近完成的**独立定性评分**。若 V229 真实 PG18 全部通过，可以讨论按**同一六维规则**是否给安全/证据审计能力 +1；但单条审计完整**不等于源真伪已核验**，真实全成本/利润仍 **10/25**，不能以技术测试绿灯“刷分”。实际外部材料尚须：Amazon Finances/Settlement/Ads、货代/关税/银行、真实第三方 WMS/实收的合法原始 ID、文档字节/来源授权、独立复核、费用归属守恒与会计决策 G1/DEC-06/07/08。

**V230 候选**：先复用 V182/V187、V229 的原有 read-only 结算关联与来源审核信号，为每笔费用提供“本地身份/金额/审计完整”到“第三方来源真实可证”之间清晰的独立材料要求/验证清单；如果没有真实权限，先把可靠的合同与失败原因标准化，绝不新增本地 owner 审批可直接变为 VERIFIED 的危险接口。

项目 `realizedNetProfit=null`，真实 Amazon Seller/Ads OAuth OFF，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。
