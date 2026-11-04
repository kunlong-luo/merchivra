# V89 · PostgreSQL 经营总览、来源质量与安全导出

**版本基线**：V88（PostgreSQL 18 Flyway V80、订单 V86 / Listing V87 / FBA V88 全库分页）。本阶段实现 P0/DASH-01 的第一部分：把左侧导航原先的 **Mock Gateway「经营总览」**替换成按当前 Owner Session 真实读取 PostgreSQL 核心业务表的经营来源**质量概览**。这里的“真实读取”只说明来自本地结构化数据库，**不意味着记录来自已授权的 Amazon 真实 API**。

## 可做与明确不能做

经营概览的每一行都代表一个 Marketplace + 一个原币，单个 HTTP 响应查询最多 100 个 Marketplace；若租户实际有超过 100 个，返回 409 并提示使用范围化查询，**不能静默只显示前 100 个还宣称全租户完整**。不为多币种做汇率换算或任何估算相加。

- Listing：从完整 marketplace_listing 表统计总数、UNMAPPED、CONFLICT、来源时间缺失以及 MOCK / UNVERIFIED / LIVE 分布。**不从浏览器已加载的 50 行汇总**，不把映射冲突算作审核通过。
- 订单：从完整 sales_order 表统计全部订单、订单项未观测、财务状态 NOT_OBSERVED、退款 UNKNOWN、来源更新时间缺失及三种来源。**不从订单商品小计推测净利润、未审退款或平台已结算金额**。
- FBA：复用 V88 的每个 Seller SKU 最新一条观测定义：observed_at DESC NULLS LAST，再按 ingested_at DESC / id DESC 打破并列。只在最新观测集合上统计 OBSERVED、PARTIAL、UNKNOWN、STALE、可售数量 NULL、已观测可售数量严格为 0、来源时间 NULL、来源分布。**历史 OBSERVED 不应填补最新 UNKNOWN；同 SKU 的历史来源行不应被重复计数**。
- V64 财务：仅显示当前 Marketplace 最新已存在的整期报表任务、原始币种、冻结的候选集大小、已处理/ELIGIBLE/BLOCKED 数量。**只有这个最新任务 SUCCEEDED 时**才读取并显示原来 finance_profit_report_order 中 ELIGIBLE 行已经持久化的 estimated_contribution 原币合计；没有合格行则是 null，未完成任务同样是 null。不是重新实现 V61/V62/V64 利润引擎，更不是来自 Sales Orders 的概算销售额。
- 即使 V64 SUCCEEDED，也明确 financialSourceComplete=false、bankVerified=false、ledgerPosted=false、globalSourceSnapshot=false。订单金额按不同批次来源审核并冻结，不是全球同一瞬间的历史数据快照，更不是净利润、到账结算或会计报表。

## Owner-only API（GET、无写入）

`GET /api/v1/business-overview` — 返回 capturedAt、sourceSnapshotFrozen=false、financialSourceComplete=false、bankVerified=false、ledgerPosted=false、crossCurrencyTotalsAvailable=false、mixedCurrencies（仅判断实际币种个数是否超过 1）以及 marketplaces 数组，包含精确行数量、来源/质量分桶和 latestReport 引用。

`GET /api/v1/business-overview/export.csv` — 同一查询结果的当前**来源质量清单 CSV**，不是冻结的 V64 财务明细，支持标准 UTF-8 BOM 和 `Content-Disposition: attachment`。CSV 每行保留 Marketplace UUID、原始币种、数据质量计数、对应最新 V64 任务状态、完成任务的**该币种**贡献额或空值及各项财务可靠性 false 标记。固定表头，不导出密码、买家 PII、Amazon OAuth Token、用户邮箱、银行账号或任何跨币种合计。直接复用现有 `ProfitReportCsv.encode -> SkuProfitCsv.cell` 的引号/公式注入防护，具有 `=+-@` 前缀的站点码不会被 Excel 当公式执行。导出失败原样 HTTP 报错，不能用浏览器自行拼接部分数据冒充完整 CSV。

两条路由均经 `TenantAccessService.currentTenant` 识别 **Session 当前 Owner 租户**。服务端 SQL 用 `WHERE mp.tenant_id=?` 限定范围；另外外层 `ReadOnlyStaffGuardFilter` 按默认拒绝处理只读员工，不授予这批人查看全租户数据或 V64 财务元数据的权利。所有响应 `Cache-Control:no-store`；后台单个查询使用 PostgreSQL REPEATABLE_READ 只读事务，保证当前请求语句在本地数据库的读取一致性，但不是跨多次请求或跨上游系统的快照。

## React 使用方式

用户在「经营总览」页面可看到逐站点质量表，选择某个 Marketplace 后查看 Listing 映射/来源、订单项/财务来源、最新 FBA 各种质量数量和 V64 报告状态。React 19/TanStack Query/Zod4 对所有数据做严格类型校验，包括每类来源数加和、FBA 四种质量加和、混合币种标志、报表币种与站点币种一致、未完成报表不得出现贡献金额；错误明确展示，不能将 API 失败当作 0。

导出区分两种操作：

1. **导出当前数据质量 CSV**：GET /api/v1/business-overview/export.csv；独立新查询，可与页面刷新后的内容略有变化，不是冻结文件。
2. **导出 V64 已冻结订单/SKU 来源贡献 CSV**：仅当最新报告 SUCCEEDED 时出现按钮，URL 仍由已有 Orval `getExportProfitReportCsvUrl` 生成，直接复用 `ProfitReportJobController/ProfitReportCsv`，包含每单不可重新计算的历史提取与 BLOCKED 原因；如果当前最新报告还在 READY/PAUSED/RETRY，就不从这个入口导出旧报告并冒称最新报告。

左侧其他「演示」菜单仍明确标记为 Demo。用户仍可回「核心业务数据」进行 V86/V87/V88 全库分页和人工审核，不在本阶段重新建设费用分摊、汇率系统、权限表、搜索服务器或财务模型。

## 可丢弃 PG18 验收

CI 新增 `scripts/smoke_business_source_quality_v89.py`，运行在 V88 已插入 64 个 SKU 和 V86/V87 64 条订单/Listing 的本地 Mock 环境：

- HTTP 匿名 401、Owner 200；全表计数严格等于 SQL 原始聚合，不能退回到 UI 前 50 条。FBA 质量来源分桶守恒；OBSERVED 0 与 UNKNOWN NULL 分开；来源时间未知不伪造。
- 插入另一个不同原币的本地 Marketplace，分别显示原始币种和来源计数，mixedCurrencies=true 而 crossCurrencyTotalsAvailable=false。CSV 站点码以 `=` 开头，验证已防公式注入，固定表头没有所谓全球利润。
- 插入仅供测试的 V64 SUCCEEDED 已审核订单与持久化 35.000000 原币贡献，读取当前数据库已有结果；再创建更新的 READY V64 任务，验证 latestReport 金额回到 null，CSV 不会输出过期贡献为最新任务利润。
- 通过已有 V15 邀请流程在模拟租户创建真正的 seller-account 范围 `OPERATOR_READ` 用户。可正常访问被授权的 V87 Listing page，但新总览与财务 V64 API 均 403，避免越权。
- 本阶段无任何 Amazon API、FBA 货件/采购流程自动写入、银行调用、真实 OAuth，也不修改源财务贡献公式。Java25/Gradle、React19/TypeScript6/Vitest/Vite8、PostgreSQL18/Flyway、Docker Compose 以最新 GitHub Actions 四项结果为准。

## V90 后续优先级

优先 **全模块核心业务用户验收清单与一键 Mock 演练报告**：订单、Listing、库存、采购、FBA、财务和运营规则的数据质量缺口/可配置性；区分自动化可安全执行的动作与必须人工确认的关键动作；补充跨区域/多币种和权限规模化场景。真实 Seller/Ads OAuth/SP-API 继续按照用户约定，在核心功能完成后由用户在本地完成授权联调。
