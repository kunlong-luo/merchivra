# V64 · 持久化整期店铺/SKU 来源贡献报表

## 现阶段能力与不能声称的事项

V64 复用 V61 保守订单贡献引擎及 V62 多 SKU 逐项独立审核门禁，不重新计算 Orders 预计销售、不把 Reports 与 Finances 重复相加、不按数量猜分、也绝不将 V63 人工退货处置当成已验证成本回转。

**整期只代表在任务创建时选定的 LIVE 订单候选集合**；不是完整的全球财务数据同步。用户指定 `after`（UTC 包含）与 `before`（UTC 不包含）的 1–92 天窗口，最多 5,000 个订单、1,000 个不同内部 SKU，且一个任务只包含一个 Marketplace 的一种原始币种。过大的查询明确拒绝或暂停，不能隐式截断并标为 SUCCEEDED。

### 最重要的快照语义

- 创建任务时，在同一个 `REPEATABLE_READ` 数据库事务里验证账户和币种，计算候选订单数，然后按照 `order_time,id` 排序把 LIVE 订单 UUID 全部存入不可重排的 `finance_profit_report_order`。这是**固定候选订单集合**，不包括创建任务后才到达、或后来更改业务日期而进入范围的订单。
- 每个后台批次有自己的 PostgreSQL `REPEATABLE_READ` 事务，批量最多 10 单；逐单调用 `OrderCostReadinessService`、`OrderContributionService`、`MultiSkuContributionService` 和 V61/V62 计算器，再冻结订单结果、SKU 原币分解、可解释的 BLOCKED 原因和 `extract_sha256`（只指向**提取结果内容**，不能冒充整份上游财务来源的完整证据摘要）。每个批次事务原子提交；异常和断电会回滚这一批，重新处理未提交的 PENDING 单。
- **这些来源不是整期同一时刻的全局数据库快照！** 已完成订单结果不再被后台自动更新。来源晚到、分类修订或成本调整后应创建新 `requestKey` 重生成，并人工比较版本。API 明确固定 `cohortFrozen=true` 与 `globalSourceSnapshot=false`。每单提取时间和摘要留存。
- 每笔订单只有满足审核门禁才是 ELIGIBLE，金额才存入和汇总；退款成本回转、调整、未知来源、未分配 SKU 以及多币种全部 BLOCKED，金额存 NULL 而非凭空填写 0。所有合格 SKU 分解和订单合计必须等于已有 V61/V62 的贡献计算，不单独建立平行财务公式。
- 即使 SUCCEEDED 且所有候选订单均被处理，`financialSourceComplete=false`、`bankVerified=false`、`ledgerPosted=false` 仍为固定值。`complete` 只表示任务候选集处理完成，不代表银行结算、广告真实成本、税费或会计利润完整。

## Flyway V62 数据模型

- `finance_profit_report_job`：Tenant/Marketplace/Owner、请求幂等键、UTC 窗口、原始币种、状态 READY/RETRY/PAUSED/SUCCEEDED、候选/已处理计数、错误码与最多五次重试、退避时刻、创建时人工 Ads 来源参考及时间。每 Marketplace 最多 3 个未结束任务。
- `finance_profit_report_order`：任务创建时全部冻结的订单 ID 与 0-based 顺序，逐单 PENDING → ELIGIBLE / BLOCKED，来源状态、原币 signed sales/platform/FBA、历史 COGS、估算贡献与提取摘要；金额数据库约束守恒。
- `finance_profit_report_sku`：仅 ELIGIBLE 订单的 SKU 分解，按 tenant/job/order/sku 唯一，金额每行数据库 CHECK 守恒，跨订单 SQL SUM 汇总不改变归属。
- `finance_profit_report_audit`：CREATED、BATCH_COMMITTED、RETRY_SCHEDULED、PAUSED、RESUMED、SUCCEEDED 仅追加，记录批次和受控错误码，绝不存储数据库原始错误文本、密码、客户资料或 Amazon Token。

## 任务 API（Session OWNER + CSRF）

HTTP 全部 no-store，绝不接受浏览器传入 tenantId、seller ID 或审批人来扩大权限。所有 GET 通过当前 Session 的 Owner 租户 + Marketplace；所有 POST 由现有 CSRF 防护。

- `POST /api/v1/finance/marketplaces/{marketplaceId}/profit-reports`：`{ "requestKey": "UUID", "after": "2026-10-01", "before": "2026-11-01" }`。同一幂等键与窗口返回已有任务；同键不同窗口拒绝。需要重建当前时期快照请生成新 UUID。
- `GET .../profit-reports`：最近最多 20 个任务状态。
- `GET .../profit-reports/{jobId}?offset=0`：SQL 已冻结 SKU 汇总、候选订单每页 100、当前处理覆盖率和缺失金额 NULL。offset 必须是 100 的倍数且不超过 5000。
- `POST .../profit-reports/{jobId}/step`：手动执行一个最多 10 单的批次。数据库 `FOR UPDATE SKIP LOCKED` 锁住 Job，多个 worker 不能重复计算同一批；成功事务提交，失败自动回滚并写入安全重试代码。
- `POST .../profit-reports/{jobId}/resume`：失败次数到 5 后进入 PAUSED，Owner 检查和修复来源后才允许手动重置有限重试次数。
- `GET .../profit-reports/{jobId}/audit`：最近 100 次任务变化；`GET .../profit-reports/{jobId}/export.csv`：**仅 SUCCEEDED** 可导出完整候选集（最多 5000 订单）和整个 SKU 汇总的固定表头 CSV，逾期金额为空，逐 cell 防 `=+-@` spreadsheet 公式注入，不重复查询 LIVE 财务结果。

## 自动化与运行开关

完全由 Owner 显式创建报表；无定时创建任务或 Amazon API 请求。默认 `MERCHIVRA_PROFIT_REPORT_SCHEDULER_ENABLED=false`，开启后本地 Worker 使用 `MERCHIVRA_PROFIT_REPORT_SCHEDULER_MS=15000` 定期继续已经由 Owner 创建、READY/RETRY 且到期的任务，最多同时扫描 3 个 Job。不开启也可以在 React 页面手动点击“处理下一批”。无需 Redis；事务级行锁保障多节点不会同时处理同一个任务，PostgreSQL 18 重启后可继续。

## 单元测试和验收边界

Java25/Gradle：严格日期/100 行分页、Session Owner、幂等入参、失败暂停、CSV 不把已阻塞的金额填零、公式注入保护。React19/TypeScript/Vitest：读写 Session/CSRF、不可伪造资金到账、完整财务来源与全局同点快照标志。PostgreSQL18：Flyway V62 + Spring Boot 启动，Compose 两种模式，UI smoke 仍按原有工作流。

**尚未完成或不应声称完成**：真实 Seller Amazon OAuth / Ads API 账单接入、真实银行/回单、物流可售退货回转、跨币种 FX、税务与会计净利润、跨所有财务数据的全局原子快照、生产规模性能及容灾演练。下一阶段 V65 仍以 FBA 货件/入仓只读对账、可信来源证据为重点，核心代码全部完成后再由用户本地授权真实 API。