# V58 · Amazon Reports 结算明细的只读获取与隔离来源入库

## 完成范围与尚未完成

- 已实现 Reports v2021-06-30 只读操作级限流入口：GET_REPORTS、GET_REPORT_DOCUMENT，要求已审核 Finance and Accounting 角色；前者只筛选 Amazon 自动生成的 GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE_V2，绝不调用 createReport。
- 已实现默认关闭的内部 SpApiSettlementReportsWorker；只有服务端持有 VERIFIED/CONNECTED Seller grant、独立财务角色证据且 Marketplace 一致时才允许请求。最多扫描 4 个列表页、每次最多导入 2 个新文档；按租户+Seller+Marketplace+reportId 跳过已导入的相同 documentId，避免重复下载及旧报告挤占额度。同一 reportId 的不同 documentId 冲突拒绝。尚未创建持久发现队列与 Cron，未暴露任何 HTTP 触发 Amazon 调用的 API。
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

## V58B · 持久化发现调度与来源差异（2026-11-01）

- Flyway V55：spapi_settlement_scan_job，每个租户/Marketplace 一条显式 Owner 发起的报告扫描任务，包含固定 14 天来源窗口、48h 延迟、nextToken、页内 rowOffset、页数、代次、lease_epoch/owner/TTL、retry_at、8 次上限、脱敏错误码和来源已导入计数。
- 即使同一 Amazon 分页含 >2 份新文档，Worker 每次最多处理 2 份，将页内位置持久保存，之后从同一 Token/偏移继续；进程崩溃时用报告 ID/documentID + 原件摘要幂等重放。不将预签名 URL 保存到 PostgreSQL，重试重新获取 document 元数据/新 URL。
- Token 超过保守 15 分钟有效期或上游明确报无效时重置分页 Token/页内偏移并重扫固定窗口，旧 Worker 若 lease 过期/epoch 变化，则无法在当前 PostgreSQL 事务提交新来源记录。
- 仅当 `MERCHIVRA_SPAPI_REPORTS_ENABLED=true` 且 verified LIVE、Finance 角色审核、Redis quota、production endpoint、Demo 和写操作关闭时注册 Worker/Owner API。可选 `MERCHIVRA_SPAPI_REPORTS_SCHEDULER_ENABLED=true` 的周期调度只继续 **Owner 显式创建**的任务，不自动发现/添加新 Seller，也不调用 `createReport`。
- 同源 Owner+CSRF 接口：`GET /api/v1/settlement-report-scan/{marketplaceId}`、`POST .../start`、`POST .../step`。浏览器只传 Marketplace UUID，无租户 ID、凭证、报告 URL、时间窗或分页 Token。
- 只读 `GET /api/v1/finance/marketplaces/{marketplaceId}/reports/{reportId}/finance-comparison`：必须通过 tenant、marketplace、原始 `SETTLEMENT_ID` 精确匹配已获 Finance 权限的 LIVE Finances 来源交易，原币单独比较；报告声明净额和报告行净额另行比对。缺来源、跨币种或差额均返回不同状态，**即使金额一致，也仅标记 SOURCE_AMOUNTS_AGREE_UNVERIFIED，reconciled=false、bankVerified=false**。绝不自动写财务账或创建银行回执。
- V57 运营监控现显示 `REPORTS` 类型任务与积压/过期租约，只使用当前 Owner Session 所属租户聚合。
- 关键验证：Java25 Gradle、PG18 Flyway V55、React19 Vitest，租户隔离、重试筛选、来源金额状态；浏览器不接触敏感 Token/URL。仅在核心功能完善后由 Owner 本地实测 Amazon。
- 尚缺：真实 Amazon Report 文件变体/超大文件方案、分页 token 可靠性和完整压力测试、可信银行入账来源、人工正式确认/审计对账状态与运营级通知。不可宣称真实结算闭环已通过。
