# V59 · Amazon Reports 与 Finances 双人来源复核（2026-11-01）

## 已实现
在上一轮 V58B 的只读 Reports/Finances 来源对比上增加有状态的双人审核（maker-checker）。Flyway V56 创建 finance_settlement_source_review 和 finance_settlement_source_review_audit，保存申请、驳回、审批、重开、理由、不同操作者及可比对的证据摘要。
审核仅在当前 Session Owner 租户、Marketplace 和报告 ID 范围内可访问。申请理由 10–500 字，后端统一校验。申请人和审批者必须为不同 Owner。
只有两种来源金额一致，且来源证据未变的情况下才可 APPROVED。若同一站点的 Settlement ID 对应多个 Reports 文档，则拒绝批准。
摘要为 SHA-256，包含不可变报告原始摘要以及精确 Settlement ID 关联的所有 LIVE Finances 交易 ID、金额、币种、发生时间。读取时重新计算；若变化则有效状态为 STALE，旧审核失效，须重新提交。
审核响应的 bankVerified=false、ledgerPosted=false 始终固定，不会变更原始交易、批准 Amazon 远程写入、产生利润或银行到账记录。

## 同源 API
- GET /api/v1/finance/marketplaces/{marketplaceId}/reports/{reportId}/source-review
- POST 同路径 /request、/approve、/reject，Body 仅 reason
- GET 同路径 /audit
所有响应 no-store；POST 受 Spring Security Session + CSRF 保护。React 财务页面提供审核理由、申请、跨 Owner 审批、驳回、历史及证据过期提示；UI 的限制不替代后端审批门禁。

## 金融与安全边界
Amazon Reports 和 Finances 仍是两个独立来源。APPROVED 仅表示当前来源有符号金额获人工双人复核，不表示 Amazon 已付款或银行已到账、费用覆盖完整、FX 合法或财务已过账。不可直接相加这两套来源。
审计摘要不是银行签名或真实结算证明，理由不得包含 PII 或 Secrets，真实 Amazon/银行验证留在项目负责人后续本地受控环境进行。
如果来源变化、令牌失效或角色被撤销，须重新审核；生产部署、可信银行流水/回款对账、汇率及税费专项验收仍未完成。

## 验收
Java 25 Gradle：原因边界、禁止自审、来源 SQL 作用域、无银行声明与 Controller Session Owner 测试；PG18 Flyway V56；React 19 TypeScript/Vitest CSRF 及 Zod 严格状态检查。实际 Amazon Seller 授权仍默认关闭。
