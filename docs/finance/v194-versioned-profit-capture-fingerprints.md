# Merchivra V194 · 版本化订单/SKU 冻结捕获指纹与独立重算

**状态（2026-11-22）**：V194 实现提交 [`61c8678`](https://github.com/kunlong-luo/merchivra/commit/61c8678288697d20476c44478fafae8bd7739349)，Next 展示 fail-closed 修补 [`9d4ebad`](https://github.com/kunlong-luo/merchivra/commit/9d4ebadb7abe45369ea55a7bb6c33b09e920d985)；后者 [快速 CI #45014853040](https://github.com/kunlong-luo/merchivra/actions/runs/45014853040) **SUCCESS**，并在 [最终完整 A3 #45018945885](https://github.com/kunlong-luo/merchivra/actions/runs/45018945885)（SHA `9cdb926`, attempt=1）实现**7/7 核心 SUCCESS**，同 SHA [快速 CI #45018945926](https://github.com/kunlong-luo/merchivra/actions/runs/45018945926) SUCCESS；本说明不是外部数据、生产发布或财务 G1 批准。

## 既有基础与实际缺口

- V62 / 产品 V64：按固定订单集合分批生成局部贡献快照；`extract_sha256` 是当时在 Java 内用拼接字符串生成的本地摘要。历史记录没有版本标识，不能从保存后的 SKU 子行可靠、无歧义地复算，更不是 Amazon 签章。
- V192：订单/SKU 逐笔原币五项守恒，防止不同订单明细金额差异互相抵消。
- V193 / Flyway V103：数据库触发器保护冻结后的订单、SKU、成功任务与审计事件不可改写。**迁移之前的历史真实性不能追认。**

## V194 的实现边界

Flyway **V104** 对 `finance_profit_report_order` 和 `finance_profit_report_sku` 各增加可空 `capture_version_v194` 与 `capture_sha256_v194`：新订单批次在同一个 `REPEATABLE_READ` 事务计算并写入版本 1 指纹，SKU 成员指纹先生成、排序后写入订单根指纹。所有指纹采用 SHA-256、带版本和用途域标识的 UTF-8 **长度前缀编码**；空值单独编码，金额统一 NUMERIC(20,6) 精度，不可无声四舍五入。订单根指纹绑定租户、Marketplace、批次、订单、状态、准备度、币种、复核事件数、五项金额、历史摘要值和排序后的每个 SKU 指纹；SKU 指纹绑定租户、批次、订单、内部 SKU、件数和五项金额。新版本只是**本地冻结数据一致性证明**。

独立读时校验 `ProfitCaptureVerifierV194` 使用冻结后的 V62 订单/SKU 行重算，不重新读取当前 Amazon、Ads 或银行数据、不根据历史时间戳补造证据、不修改旧数据。按整批检查（最多 5,000 订单，至多 100,000 SKU 子行的显式保护）：

| 状态或风险 | 必须采取的行为 |
| --- | --- |
| `capture_version_v194 = null`、指纹缺失的旧 V62 行 | 明确统计 `historicalLegacyOrders`，不把旧 `extract_sha256` 升级为 V1 |
| 新行的 SKU 指纹不符、成员缺失/增多、金额不守恒、订单根指纹不符、跨租户或批次 | 统计 `fingerprintMismatchOrders`，禁止金额汇总或瀑布继续 |
| 有 `PENDING`、`BLOCKED`、未完成/空批次、订单计数冲突 | 非完整利润基线；未知不等于零 |
| 已完成、非空、全部订单 ELIGIBLE 且 V1 重算一致 | 仅允许显示**局部已复核来源贡献算术**；绝不能自动标为净利润 |

新的 Owner Session 只读 GET `/api/v1/finance/marketplaces/{marketplaceId}/capture-verification/{jobId}` 显示完整批次的版本化/历史/异常/等待计数和阻断码；这个路由**不侵入**既有 V64 九操作 OpenAPI/Orval 契约。旧 V64 `extractSha256` 字段不改名，新增的 V1 指纹仅存后端冻结表，不伪称旧摘要的含义。非完整局部证据时，V64 期间金额汇总设为 **null**、SKU 汇总为空（订单原始冻结记录仍可用于取证）；已完成报表的 CSV 拒绝将未核证捕获导出为计算结果。V188 成本瀑布在读取原币金额前调用同一独立校验，发现缺证直接 HTTP 409，V192 金额守恒检查仍保持。

### 2026-11-22 首轮完整 A3 的真实失败与兼容修复

在精确 [`2ef7fc0`](https://github.com/kunlong-luo/merchivra/commit/2ef7fc0d1a7d5d4ae089c090d782a06cb72ff01c) 触发的 [完整 A3 #45017612506](https://github.com/kunlong-luo/merchivra/actions/runs/45017612506) 中，PG18 作业 **FAILED**，其余 6 个核心作业 SUCCESS。失败发生在旧 V164/V188 合成 HTTP 测试：创建尚未完成冻结捕获的 V64 报表后，测试仍要求瀑布 GET 返回 200，V194 后端实际正确地 fail-closed 返回 409。该旧测试在新财务规则下已过时，**不能通过关闭校验或返回伪造金额来修复**。

修复更新 `scripts/smoke_cost_allocation_v164.py`：读取 Owner/no-store 的 V194 独立证据分类，断言无伪历史与无指纹损坏、缺完整基线保持拒绝，并验证匿名/错误站点/错误任务拒绝和 V188 的 HTTP 409 稳定性。另将 `scripts/smoke_profit_capture_v194.sql` 接到完整 PG18 作业，使用回滚的 TEMP V62 表检查真实 Flyway V104 四列、三个检查约束、旧行不自动升级、版本/摘要双字段配对、PENDING 禁指纹、V103 冻结后不可修改。首次 A3 在旧 V164 断言处中止，**尚未执行新 V194 专项 SQL**，必须在新精确 SHA 完整重跑才可评价其是否通过。

### V194B A3 第二轮发现的约束断言问题

[`18f3213`](https://github.com/kunlong-luo/merchivra/commit/18f3213ba3374a72378eedd9d4572776174c5380) 的 [完整 A3 #45017957824](https://github.com/kunlong-luo/merchivra/actions/runs/45017957824) 已越过 V164/188 的旧断言，实际运行到新 V104 SQL 脚本，但在第 136 行失败：一个 `PENDING` 行同时违反 `finance_profit_report_order_pending_v104` 和 `capture_pair_v104`，PG18 先报告 PENDING 约束，旧测试却只匹配 pair 错误。**非法记录已被数据库拒绝**，问题是负例没有单独隔离所测试的约束。

V194C 将缺哈希负例改为正常 `PENDING → ELIGIBLE` 捕获转移，只让版本/摘要配对检查失败；PENDING 禁止提前指纹另设独立负例。不能因为曾发生两个失败就写成 V194 完整验收通过，新精确 SHA 的 PG18/A3 仍须查看实际运行结论。

### 第三轮真实 PG18 发现 V104 NULL/UNKNOWN 漏洞与 V105 修复

[`6cc2747`](https://github.com/kunlong-luo/merchivra/commit/6cc2747a9636c72fff33e4f66e8887de18cc76a6) 的 [完整 A3 #45018224081](https://github.com/kunlong-luo/merchivra/actions/runs/45018224081) 执行 V104 数据库负例时发现：**ELIGIBLE 行 `capture_version_v194=1`、`capture_sha256_v194=NULL` 被 PostgreSQL 18 放行**。原因是原 V104 `CHECK` 的布尔表达式结果为 `UNKNOWN`，而 PostgreSQL 对 CHECK UNKNOWN 视为合格。因此这是**真实 Schema 缺口**，不是可以删掉的误判测试。

新增**仅前进的 Flyway V105**，不改 V104 原文件，不补写任何历史摘要。V105 为订单/SKU 都增加明确 `version IS NOT NULL AND sha IS NOT NULL` 的严格 `CHECK`，只允许成对 NULL（旧快照）或成对有效 V1/SHA-256；已有异常数据导致迁移失败而非静默修复。新 PG18 测试覆盖两个方向的半指纹，PENDING 拦截、正常 V1 捕获及 V103 不变性。外部真实性、退款 COGS 与 `realizedNetProfit=null` 仍保持不变。**V105 是否验收需以包含该迁移的精确新 SHA 的 A3 结果为准。**

### V194D A3 第四轮：V105 双向约束通过，夹具作用域与浏览器时序待修

在 [`f687e0e`](https://github.com/kunlong-luo/merchivra/commit/f687e0e642ea5c81ae008fe3f19bbc475827338d) 的 [完整 A3 #45018665789](https://github.com/kunlong-luo/merchivra/actions/runs/45018665789) 中，V105 的真实 PG18 约束成功拒绝了缺 SHA/缺版本、错误版本和非法摘要的负例，**未发生 V104 原先的半指纹放行**；但脚本末尾误用无 WHERE 的合成 `finance_profit_report_job` 完成 UPDATE，连同用于校验 PENDING 约束的第二个一次性任务也被更新为 processed=2，触发已有 `processed<=total` CHECK，故 **PG18 job 仍 FAILED**。新修订限定 `WHERE id=<测试主任务>`，只结束主测试批次、不放松生产约束。

同一 A3 的 Next Chromium 专项在路由短暂切换时直接 `evaluate` 查找 Core 子页标签，未等组件挂载就抛 `Uncaught`；调整为最多有限次等待目标真实 tab 出现并点击，原有订单/FBA/来源/布局断言全部保留。**首次/二次/三次/四次失败历史不能删改，更不能把某条 PG18 约束通过说成整套 A3 成功。**修复后必须对新的精确 `main` SHA 重新执行并检查所有作业。

### V194E · 最终 PG18/Chromium/Compose 复测结论

在 [精确 `main` SHA `9cdb926`](https://github.com/kunlong-luo/merchivra/commit/9cdb9268020bdc9ae0c743e09d5d5775c377ce69) 的 [完整 A3 #45018945885](https://github.com/kunlong-luo/merchivra/actions/runs/45018945885)，**run_attempt=1，7/7 核心任务 SUCCESS**：真实 PostgreSQL18/Flyway V104/V105、历史 null/null 的保守兼容、ORDER 与 SKU 半指纹两个方向的拒绝、非法版本/SHA、PENDING 状态、V103 不可变证据、V188 不完整利润基线 fail-closed 409、Owner/匿名作用域、Next/Vite Chromium、Compose HTTP/Session/CSRF/恢复及 Java25。此前四轮失败已保留在上文，不允许重写为“首次即通过”。[同精确 SHA 快 CI #45018945926](https://github.com/kunlong-luo/merchivra/actions/runs/45018945926) SUCCESS。若后续仅改文档，不能自动将这个 A3 的代码范围扩展到新 HEAD。

**剩余覆盖边界**：本轮专项充分证明版本/摘要数据库完整性和不完整基线的安全阻断，但它不等同于使用真实外部费用的正例核证，也未独立证明每种完整已捕获订单/SKU 的端到端实际利润可入账；外部来源、退款 COGS、财务 G1、生产授权继续未完成。

## 测试与限制

`ProfitCaptureFingerprintV194Test` 覆盖指纹输入顺序、非英文和特殊 SKU、固定六位金额、不可舍入、跨租户/批次、旧数据不可升级、SKU 缺失/篡改、未完成与 BLOCKED 场景，另验证 V104 及服务门禁代码。不应以静态或 Mockito 单测代替 PostgreSQL 18 Flyway + 合成 Owner HTTP 的专项回归；后续完整 A3 必须记录精确 main SHA、run attempt、结果和失败修复情况。

**严格保持**：V1 证明本地冻结行可重算，**不证明**原始 Seller Finances / Settlement、Ads、物流、银行真实性或手续费齐全。退款 COGS 真实回转、G1/DEC-06/07/08 会计政策、FX 和完整 V162 净利润仍未完成；所有 `realizedNetProfit=null`，Seller/Ads OAuth 与生产写入关闭，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。默认 Web Next.js 16，前端包管理仅 pnpm；V194 不添加展示卡、授权或独立会计引擎。
