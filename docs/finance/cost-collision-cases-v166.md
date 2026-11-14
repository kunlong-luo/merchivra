# V166 · 来源身份冲突案件：可追溯候选、一次性独立研判

## 已实现（技术范围）

在 [V165 跨来源身份线索](cost-identity-collision-v165.md) 的同租户、同 Marketplace、8 类精确身份键 / **操作者宣称** SHA 匹配基础上，V166 增加 **Flyway V93 持久化案件**、实际候选 UUID、本地审核与不可变审计。这里的“研判”**不代表账单核销、独立外部证明、双扣成立、可解除 409 或真实利润**；不需要 Amazon Seller/Ads OAuth 或远程写入。

- `finance_cost_identity_case_v93`：`(tenant_id, source_id, signal_code, candidate_id)` 唯一；同一来源与精确匹配候选只允许一个案件。保存来源、Marketplace、线索编码、候选本地 UUID、登记者 UUID、时间、人工研判状态。**不存原始来源键、原始 SHA、外部票据、买家 PII、财务凭证附件或令牌**。
- `finance_cost_identity_case_audit_v93`：只追加固定操作码、操作人 UUID、时间；触发器禁止 UPDATE / DELETE。案件也不能删除或篡改候选来源身份。
- **仅服务器查询实际命中**：不接受浏览器随意指定 `candidateId`。服务端从 8 类旧来源表按相同租户 / Marketplace 的精确引用/摘要检索并最多处理 **100 项候选**，超过上限中止整个事务，避免半截案件。每次扫描先锁来源行；相同案件重新扫描不会新增审计记录。
- **独立 Owner 双人审核**：一次性 `OPEN` → `ESCALATED_NEEDS_PROOF` / `POTENTIAL_DUPLICATE` / `NOT_SAME_CHARGE_UNVERIFIED`。审核者不得是原来源创建人或案件登记者，必须填写 10–500 字单行理由；状态及候选一旦审核不可改写。**这些全是人工初步判断**，不存在 `CLEAR`、`VERIFIED_NOT_DUPLICATE` 或可触发财务入账的审核状态。
- **保留 V165 实际阻断**：无论案件是 `OPEN` 或已有人工作出“未证实同笔费用”研判，`CostAllocationService.decide(accept=true)` 仍调用 V165 当前身份扫描。只要精确重叠存在仍 **409**；案件审核不能调用任何免审/核销逻辑。候选不相等、金额相等也不能凭数值误判重复。

## Owner Session + CSRF HTTP

基础路径：`/api/v1/finance/marketplaces/{marketplaceId}/cost-sources/{sourceId}/collision-cases`

- `POST /scan`：服务器从当前租户/Marketplace 的**实际本地匹配**生成案件，重复扫描幂等，返回至多 100 个最近案件。CSRF 必须有效。
- `GET /`：只读最近 100 个案件及固定状态（不提供审核理由或敏感原文），`Cache-Control: no-store`。
- `POST /{caseId}/review`：例如 `{"disposition":"POTENTIAL_DUPLICATE","reason":"Independent local candidate identity review"}`；仅另一 Owner 可一次性审核。任何“已证实可不扣费/已证实可入账”状态均被拒绝。
- `GET /{caseId}/audit`：最近 100 个追加操作码/操作者/时间，先验证案件属于当前租户及当前来源；响应不缓存。

## 正确解释技术验证

`CostIdentityCaseServiceTest` 包含双人分权、自审拦截、非法财务放行状态、审核理由控制、Owner 会话推导、不可变 DDL 与候选 SQL 只依赖精确身份的负例。

[实际 PG18/HTTP 测试 `smoke_cost_identity_v165.py`](../../scripts/smoke_cost_identity_v165.py) 扩充 V166：生成与手工 Ads 来源键一致的候选，`POST /scan` 创建含真实候选 UUID 的单一案件，重复扫描不生成重复审计；原 Owner 自审 403、伪造 `CLEAR` 400、独立 Owner 作出初步判断后再次审核 409；PG18 SQL UPDATE/DELETE 案件与审计均失败。随后即使另一张自称相同 SHA 的单据被人工标为 `NOT_SAME_CHARGE_UNVERIFIED`，V165 仍报告候选重叠，**不得自动解除风险**。V164 金额守恒和未分摊余额保持。

**真实完整 A3 的精确 SHA 结果必须在通过后补写**。快速 CI 只能证明 Java 25/Next/Compose 编译和单测，不是 PG18 V93 的实际验收。

## 尚未完成

人工财务核查真实发票与账户对账、不同来源每笔交易同一性证据、被复核后**合法解除冲突的独立规则/可重算凭证链**、来源进口并发的一致性/冻结整期快照、退款/税/汇兑政策、V162 → V64 / Next 真正全成本利润与导出。对卖家来说，现阶段只是**可靠的待处理风险案件登记与分权研判**，不是财务裁决闭环。

生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) 保持 OPEN/NO-GO、历史 TLS [#115](https://github.com/kunlong-luo/merchivra/issues/115) 仍 OPEN，Seller/Ads 实际授权继续关闭，Next.js 是正式 ERP 目标而 Vite 仅暂留回退。
