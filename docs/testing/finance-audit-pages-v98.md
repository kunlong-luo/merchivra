# V98：金融复核与退款订单项审计完整查询

V97 后仍有三处用户可见的单案历史被原来的 LIMIT 100 截断：银行人工来源复核（bank_payout_manual_review_audit）、结算来源双人审核（finance_settlement_source_review_audit）、退款订单项人工判断（finance_refund_review_audit）。

## 实现

- 新增 /api/v1/finance/marketplaces/{mp}/reports/{report}/manual-bank-review/audit/page
- 新增 /api/v1/finance/marketplaces/{mp}/reports/{report}/source-review/audit/page
- 新增 /api/v1/finance/marketplaces/{mp}/events/{event}/refund-items/audit/page

查询默认为 50 条，最大 100。沿用 LocalKeysetPage（Keyset：occurred_at DESC,id DESC）、统一服务端 FinanceAuditPages、React CursorPageControls、Zod envelope、no-store。新游标绑定 tenant、Marketplace、源 UUID 及 BANK / SOURCE / REFUND 业务类型；错误范围、页长、时间与 UUID 均返回 400。

**每次分页前先执行既有的来源证明与作用域校验**：Bank 审核需要独立报告、唯一人工银行参考和文件摘要；Source 审核先核对报告/Finances 来源证据；Refund 审核必须是经人工关联的同站点 LIVE 订单与财务来源。保留原始只读数组 API 兼容其他调用。翻页不会触发新审批、银行付款、退款分类、库存或财务写入。原有 Maker-Checker 异人复核及不可覆盖审计表不变。

## PostgreSQL 18 完整链路

scripts/smoke_finance_audit_pages_v98.py 追加到之前 V1–V97 的一次性 PG18 Mock 链路末尾，构造：
- 135 条来源复核历史；
- 127 条银行人工参考历史；
- 125 条退款订单项审核历史。

先确认旧数组各最多 100 条，再遍历三页还原完整历史、验证版本/时间不重复与作用域绑定；测试匿名请求、错误筛选/游标/站点和边界页长，确认业务数据的银行核验字段仍为 false，前后本地库存流水和采购单数量不变。

测试专门注入了 V98-MOCK 命名的合成报告、银行观察、订单和财务来源，可能带 LIVE 数据库来源字段来测试既有 SQL 来源门禁，但**不是**真实 Amazon/API/银行数据；这些合成记录不得用于财务会计、真实店铺审批或实际库存收货。

GitHub Pages 静态公开展示另见 docs/deployment/github-pages-public-demo-v98.md。其独立构建不连接这些 PostgreSQL Mock 记录，也不对外开放任何应用 API。
