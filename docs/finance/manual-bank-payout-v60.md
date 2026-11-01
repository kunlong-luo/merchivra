# V60 · 银行回款人工参考导入、差异分析与双人复核

**状态：已开发代码及 Mock/离线验证。没有受信银行 API、银行签名源或实际到账证明，不可称为银行到账确认。**

## 实现范围

- Flyway V57：`bank_payout_import_batch` 和 `bank_payout_observation`，带租户、Marketplace、导入批次、脱敏来源 ID、SHA-256、日期、贷记、扣留手续费、银行币种和申报汇率；`source_origin`/`evidence_origin` 永远为 `MANUAL_UNVERIFIED`，不保存账户号、原始 CSV、银行密钥或收款人信息。CSV 与条目编号均不可变；重复同内容幂等，重复编号不同内容 409。
- Flyway V58：`bank_payout_manual_review` 和只追加审计表；申请人与审核人不同，**银行人工参考上传人也不可担任审核人**。只有同币种且净额与银行贷记+扣留手续费完全相等，并且 Reports/Finances 来源审核现时有效时才可 `REVIEWED`；跨币种仅展示差异，不能直接核准。审批状态绝不等于银行确认。
- Flyway V59：复合外键保证银行条目与导入批次拥有相同 tenant+Marketplace；审核所用报告和银行条目也同时按 tenant+Marketplace 联合约束。
- 前端 React 19：标准化 CSV 文件上传、批次和单条条目检查、同 Settlement ID 关联诊断、不同币种及手续费差异、人工复核申请/驳回/审批、审计、来源变更失效提示。
- 回款金额计算仅为人工审核线索：结算币种与银行币种相同，要求 `credit_amount + withheld_fee = Amazon declared_net`；跨币种则使用**人工声明**的 `fx_rate` 做 `credit_amount + withheld_fee` 与 `declared_net × fx_rate` 的比较；从不静默假设 1:1 汇率。负数银行退款不在本轮 CSV v1 输入范围。

## CSV v1 合同

固定 ASCII 表头（不可添加额外列，避免误吞 PII）：

```csv
entry_id,settlement_id,value_date,currency,credit_amount,withheld_fee,fx_rate
ENTRY-2026-001,SETTLE-2026-001,2026-10-30,USD,98.00,2.00,
ENTRY-2026-002,SETTLE-2026-002,2026-10-31,EUR,88.00,2.00,0.90000000
```

- `entry_id`：脱敏且同站点唯一的 8–160 位参考 ID，不得填银行账号；`settlement_id`：Amazon Settlement ID；日期 `YYYY-MM-DD`；币种三个大写字母。
- `credit_amount` 必须为正数，`withheld_fee` 非负且仅表示从预计转账额扣留的银行手续费，最多 6 位小数；汇率 `fx_rate` 可为空，若填写必须正数、最多 8 位小数。不得上传银行名称、个人名字、账户、IBAN、卡号、客户资料、网银凭证或签名 URL。
- UTF-8 无引号 CSV，最多 50 条/64 KiB，禁止额外列、重复 entry ID、异常金额/非法日期。旧式含逗号引号的银行原始 CSV 应先由用户在本地离线整理为此标准化格式；后端不会直接解析未知的银行格式。
- 该来源是用户手工提供，文件哈希只证明重导入时内容一致，不证明文件来自银行、没有被改动、或钱已经到账。

## 安全 API（Session Owner + CSRF + no-store）

- `GET /api/v1/finance/marketplaces/{marketplaceId}/bank-payout-imports`
- `POST /api/v1/finance/marketplaces/{marketplaceId}/bank-payout-imports/{statementRef}`，Content-Type 为 `text/plain`、Body 为标准化 CSV 字节；不允许传 tenantId、bank account、任意下载 URL。
- `GET /api/v1/finance/marketplaces/{marketplaceId}/bank-payout-imports/{batchId}/entries`
- `GET /api/v1/finance/marketplaces/{marketplaceId}/reports/{reportId}/bank-comparison`
- `GET/POST /api/v1/finance/marketplaces/{marketplaceId}/reports/{reportId}/manual-bank-review` 下的 `/request`、`/approve`、`/reject` 和 `/audit`；操作说明 10–500 字，不可包含敏感资料。

## 结果与防误判

按 tenant、Marketplace、精确 Settlement ID 关联。重复 Amazon 报告、多个银行条目、缺银行参考、缺原币净额、缺 Reports/Finances 双人审批、币种冲突和差额均独立提示。即使来源金额一致也只能显示 `MANUAL_AMOUNT_MATCH_UNVERIFIED` 或 `MANUAL_FX_MATCH_UNVERIFIED`。银行人工双人复核 `REVIEWED` 不能替代银行真实证明：`bankVerified=false`、`ledgerPosted=false`。

## 未完成（不可将 Mock 误称为生产闭环）

真实银行提供的可信原始流水/开放银行 API 接入、资金账户归属授权、银行回单及来源认证、付款分拆合并、退款/冲正、不同币种真实汇率来源和小数取整规则、FX 手续费核验、实际到账和外部银行差异检测、银行安全联调、加密审计存储、端到端真实数据验收。所有真实 Amazon 读写仍按现有开关关闭；本轮没有连接银行或改变财务账。

## 验收

- Java 25 / Spring Boot 4.1.2 / Gradle：CSV 限制、跨租户 Controller 范围、不同来源匹配状态、双人审核角色和银行 false 不变量。
- PostgreSQL 18：Flyway V57–V59 真迁移与复合外键。
- React 19 / TypeScript：数据模式严格验证、CSRF 上传、文件大小和敏感请求约束、只读来源比较及审批状态。
