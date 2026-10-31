# V30 · 结算参考批次与财务来源交易差异核对

## 使用前提

本版本为**人工参考结算单**，并非真实 Amazon Settlement API 回执，也不是银行收款凭证。你可以继续不配置 Seller 授权，待所有核心功能完成后再本地联调。

- V30 仅在本地数据库创建 `finance_settlement_statement`，原币 reported_net、半开时间窗口、结算参考号、站点、创建人以及 `evidence_origin=MANUAL_UNVERIFIED`。
- 原始 LIVE Finances 交易维持 V21 的不可变结构；人工未核验 `finance_source_event` 永远不能被关联为已验证 SP-API 来源。
- `finance_settlement_event_link` 绑定同 tenant、Marketplace、币种的原始 `SPAPI_FINANCES/LIVE` 事件。数据库 `UNIQUE(tenant_id,event_id)` 避免同一来源交易重复分配到不同参考单；已有同单分配幂等；解除需显式操作且审计历史保留。
- `finance_settlement_audit` 追加 CREATED / EVENT_LINKED / EVENT_UNLINKED 与真实本地 Actor。没有删除参考单、修改参考金额或自动银行确认的入口。

## 对账预览

`SettlementReconciliationEngine.compare` 仅对**当前手动分配到批次的原始有符号交易总额**求和，并以 reported_net 减来源合计得到 signedDifference。不会把 V27 的 breakdown 父子金额累加到交易级合计。

状态包括 NO_LINKED_EVENTS、CURRENCY_MISMATCH、OUTSIDE_STATEMENT_PERIOD、SIGNED_TOTAL_DIFFERS、SIGNED_TOTAL_MATCHES_UNVERIFIED。即使金额相等，`bankVerified` 仍永远是 false；不能声称已结算或已到账。

额外输出当前期间内未分配的 LIVE 交易数量，供人工查漏。由于部分来源可能有迟到交易、税费调整、款项冻结或非该批次交易，**未分配数量不等于真实漏单数，金额相等也不能证明覆盖完整**。跨币种不折算；来源期间外关联保留醒目标识。

## Owner 工作台与 REST

仅本地 Owner Session + CSRF，可在财务页面创建参考单、查看/手动分配候选交易、解除、查看审计与差异。浏览器不提交 Seller ID、Amazon Token、任意 SP-API URL 或外部银行授权信息。

- `GET/POST /api/v1/finance/marketplaces/{marketplaceId}/settlements`
- `GET /api/v1/finance/marketplaces/{marketplaceId}/settlements/{id}/candidates`
- `GET /api/v1/finance/marketplaces/{marketplaceId}/settlements/{id}/preview`
- `POST/DELETE /api/v1/finance/marketplaces/{marketplaceId}/settlements/{id}/events/{eventId}`
- `GET /api/v1/finance/marketplaces/{marketplaceId}/settlements/{id}/audit`

来源交易属于同站点且 LIVE 才能参与，业务币种必须等于 Marketplace 原币。防止以不同来源/不同店铺金额拼出虚假“已结算”。

## 后续待开发

真正的 Settlement ID / Disbursement ID 官方 API 关联、Amazon 结算报告/报表的结构化导入、银行流水自动匹配和人审、税费/汇率/对账差异归因、已到账凭证追溯以及真正的利润和多仓成本。没有这些来源证明，不把 V30 结果标为银行实收、结算已完成或财务结账。

## 数据库迁移注意

此前 V25 使用了与 V14 相同的索引名，导致新建 PG18 数据库 Flyway 中断；已在开发主分支修正 V25 索引名称。已执行过旧版 V25 的持久化数据库需要按 Flyway checksum 管理规范手动检查/修复，不能在生产环境直接删表或盲目执行 `repair`。本项目尚未进行真实 Seller 数据迁移验收。


## V31 官方来源文档编号匹配

V31 增加 `SETTLEMENT_ID`、`DISBURSEMENT_ID`、`FINANCIAL_EVENT_GROUP_ID` 精确候选优先提示。显示“来源 ID 匹配”并不自动确认银行到账或改变本地参考单；详情见 [V31 说明](settlement-source-references-v31.md)。
