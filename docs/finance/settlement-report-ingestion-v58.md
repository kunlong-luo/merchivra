# V58 · Amazon Reports 结算明细的只读获取与隔离来源入库

## 完成范围与尚未完成

- 已实现 Reports v2021-06-30 只读操作级限流入口：GET_REPORTS、GET_REPORT_DOCUMENT，要求已审核 Finance and Accounting 角色；前者只筛选 Amazon 自动生成的 GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE_V2，绝不调用 createReport。
- 已实现默认关闭的内部 SpApiSettlementReportsWorker；只有服务端持有 VERIFIED/CONNECTED Seller grant、独立财务角色证据且 Marketplace 一致时才允许请求。最多扫描 4 个列表页、每次最多处理 2 个文档；尚未创建持久发现队列与 Cron，未暴露任何 HTTP 触发 Amazon 调用的 API。
- 元数据来自 getReports / getReportDocument；必须 status=DONE、报告类型匹配且报告只属于所选 Marketplace。否则跳过，不尝试下载。
- getReportDocument 返回的预签名 URL 有短期有效期。SignedReportDocumentDownloader 只允许 HTTPS、443/标准端口、*.amazonaws.com / *.cloudfront.net，禁用重定向，检查 DNS 非内网，限制下载 5 MiB，GZIP 解压最大 8 MiB，绝不把 URL、原始文档或凭证保存到数据库/磁盘。生产仍需网络出口层阻止内网和元数据 IP，防范 DNS rebinding。
- TSV 解析严格识别 Amazon 结算摘要头/明细头，拒绝重复表头、坏 UTF-8、异常/混合币种、无签名金额、超过 10000 行或未知报表结构。不写原始整行与未列入白名单的列，降低包含买家 PII 的风险。只解析交易类型、金额类别、描述、订单参考、有符号金额与原币种。
- Flyway V54 新建 spapi_settlement_report 与 spapi_settlement_report_line，按 tenant + marketplace 外键/唯一索引、Amazon reportId/documentId 和 SHA-256 不可变幂等；相同报告ID若改文档或财务事实会拒绝，避免静默覆盖。
- 所有记录是独立来源 staging。**永不直接写入 finance_source_event、finance_settlement_statement、银行到账、采购库存或利润投影**。Finances API 交易与 Reports 可能重复描述同一资金事实，不能盲目相加。
- React「财务与对账 → Amazon 结算报告」查询同一 Session Owner 所属 Marketplace 的报告列表、摘要、原币分类/明细前 100 行，明确标记还没有银行/Finances 核验。GET 接口 no-store，不能上传文件或提交 URL。

## 参数与边界

- `MERCHIVRA_SPAPI_ENABLED=false`、`MERCHIVRA_SPAPI_REPORTS_ENABLED=false`、`MERCHIVRA_SPAPI_ALLOW_WRITES=false` 仍是默认值。不会因为页面打开而触发 Amazon。
- 真实生产读仍需明确审批 Amazon Finance 角色、单独可靠的 Redis noeviction 配额服务、凭据加密和网络出口策略。Reports 配额缺少响应头时采用单操作至少 60 秒的保守间隔，收到实际 x-amzn-RateLimit-Limit 以后采用动态间隔；不能从速率头推断 burst。
- `GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE_V2` 属于由 Amazon 生成的结算类型，不应在这条链路使用 createReport。某些外部报告类型有 RDT / 其他角色要求，禁止通过本入口偷用通用 Report API。
- 对更大报表（超过 5 MiB）、未知 TSV 变体、报告跨多个 Marketplace、包含需要另行权限的 PII、错误/取消状态：拒绝或跳过，不伪装成导入成功。后续需要分流到受控扩展解析与原始文件加密存储方案。

## 本地与 CI 验收

- Java：`cd apps/server && ./gradlew test jacocoTestReport`
- PG18：V54 Flyway 真迁移启动；复合 Tenant/Marketplace 外键与不可覆盖报告 ID 约束。
- React 19：`cd apps/web && npm ci && npm run test && npm run build`
- 当前仅离线解析、SDK 签名编译、Mock 与安全边界测试；**不代表真实 Amazon 下载成功**。你决定在本地授权完成以后再启用生产调用。

## V58 尚需继续开发（不可冒称已完成）

1. PostgreSQL 持久报告发现/导入队列、任务租约与分页 cursor、审计、稳定重试与滞后告警。
2. 实际 Seller 身份/权限在本地授权后的 Reports 响应字段与类型确认、GZIP / URL 网络联调、下载限流和大文件策略。
3. Reports 来源行与 Finances transaction ID/Settlement ID 的可审计匹配、冲突隔离与人工确认；不能因为相等金额自动记账。
4. 完整跨租户 HTTP/并发和故障注入验收，以及加密文件存储需求决策。

## 官方资料

- https://developer-docs.amazon.com/sp-api/docs/retrieve-a-report
- https://developer-docs.amazon.com/sp-api/reference/getreports
- https://github.com/amzn/selling-partner-api-models/discussions/3626
