# TEST-003 · 当前 CI / 完整 A3 验收策略（V195 / DOC-196）

> **2026-11-22 · 当前维护约定**：日常开发与文档修改直接提交 `main`，不为每个 Vxxx 开发重复创建分支/PR。快速 CI 保证构建与高价值单测；PG18 全链路、Chromium/Next、真实 Docker Compose 是有条件执行的**隔离合成**里程碑验收；两者均**不是**真实 Amazon/银行核证或生产许可。见 [事实基线](../current-status.md)、[发布阻断](release-readiness-v68.md)。

## 实际 GitHub Actions 入口

| 工作流 | 触发/预算 | 通过说明 |
| --- | --- | --- |
| [`ci.yml`](../../.github/workflows/ci.yml) | 每次 `main` push，或手动/可复用工作流调用；普通 push 为快 CI | Java 25 Gradle tests、Node24/pnpm12 **冻结安装**、Orval、类型、Vitest、Next 默认生产构建、Vite 回退构建、Compose 静态安全/文档检查 |
| [`milestone-validation.yml`](../../.github/workflows/milestone-validation.yml) | Actions 手动触发或修改此工作流时在 `main` 触发；按需执行完整 A3 | 精确 SHA，调用 `ci.yml(full_suite=true)` + 独立 Chromium 轨迹 + [真实合成 Compose 链路](../../.github/workflows/mock-full-chain.yml) |
| [`browser-layout-smoke.yml`](../../.github/workflows/browser-layout-smoke.yml) | A3 引用或手动 | Vite 回退与 Next App Router 的真实 Chromium 浏览器合成场景 |
| [`mock-full-chain.yml`](../../.github/workflows/mock-full-chain.yml) | A3 引用或手动 | 一次性 PostgreSQL18/HTTP、Session/CSRF、Next 独立 Node、受控 HTTPS Next→Vite→Next、持久恢复；不连接生产 |
| [`github-pages.yml`](../../.github/workflows/github-pages.yml) | `main` 前端变更或手动 | `VITE_PUBLIC_DEMO` 虚构只读静态展示，**不是**有鉴权的 ERP 发布 |

## 何时必须增加测试

1. **每次代码/文档提交**：核实快速 CI 的**该 SHA**结果，不能借前一个提交的 green 冒充新提交已验收。
2. **资金/币种/审核/库存/授权等高风险变更**：立即运行聚焦的后端/PG18/HTTP/CSRF 负例，不因集中 A3 策略拖延安全性验证。
3. **跨数据库/进程、版本化快照、Next 容器或部署回退**：按风险触发[完整 A3](https://github.com/kunlong-luo/merchivra/actions/workflows/milestone-validation.yml)；除业务绿灯外必须核对未运行/跳过的任务及 SHA。
4. **真实目标上线/卖家接入**：必须独立取得合法角色/地区/银行及第三方凭证，G1/G2/G3 人工批准、TLS/KMS/备份/PITR、外部告警送达/ACK、撤销、恢复与性能证据；**不存在**“A3 绿色就自动部署”的路径。

## V199 财务负例验证范围

V199 拒绝 V54 结算 `declared_net=NULL` 被视为三方来源金额一致：Java 的缺值/真零金额负例、Next 状态合同、PG18 TEMP/ROLLBACK 原 SQL 检查、原 V56 独立审批必须继续 fail-closed。本次 [精确 `30fd25c` 提交](https://github.com/kunlong-luo/merchivra/commit/30fd25c9205c10234fced28626d69e0df2b81889) 的 [完整 A3 #45022799341（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45022799341) 与 [快速 CI #45022799357](https://github.com/kunlong-luo/merchivra/actions/runs/45022799357) SUCCESS；合成 PG18 原 SQL 的 NULL 证明与 Java 缺证 fail-closed 负例分开验证，不能冒充真实外部结算认证。

## V200 历史成本重叠专项（[精确 V200 代码 `2715529`](https://github.com/kunlong-luo/merchivra/commit/271552911b55a451e66662cdf3aab94c2db21341) · [快速 CI #45023721155 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45023721155) · [完整 A3 #45023721170（7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45023721170)）

后端 `OrderCostEngineTest` 负例检测多映射、重叠成本区间、候选数未知，且任何歧义不能进入 `provisionalCost`；真实 PG18 合成测试 `scripts/smoke_order_cost_ambiguity_v200.py` 在 TEMP/ROLLBACK 中运行同一 Java SQL 并检查正常、重叠、缺失情形。Next 原有订单成本面板显示清晰阻断原因。精确 V200 源码的完整 A3 已通过 7/7；上述负例是隔离合成数据库技术验证，不代表外部 Amazon 单据、批次实收或实际会计放行。


## V201 跨事件退货数量读时阻断（[精确代码 `c47873c`](https://github.com/kunlong-luo/merchivra/commit/c47873cd1ad3e9963f57c9d69dc19b2ba5a17c90) · [快速 CI #45024903306 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45024903306) · [完整 A3 #45024903265（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45024903265)）

- Java `RefundItemReviewServiceV201Test` 检查多事件汇总 SQL 作用域、当前 LIVE 订单项约束和冲突优先序；Next `refund-review-v201.test.ts` 检查明确冲突状态和不可信实收标志的拒绝。
- PG18 `MERCHIVRA_V201_DISPOSABLE_ONLY=true python3 scripts/smoke_refund_cross_event_quantity_v201.py` 在 TEMP 表 + ROLLBACK 执行**Java 生产源 SQL 原文**，包含已审 2+1 件、订单后改数量、异租户/订单隔离、非 LIVE/旧项与另一商品项的反例。
- 本次代码精确 SHA 的快 CI 与完整 A3 已通过 7/7，测试使用隔离 PG18 原 Java SQL 与合成订单/退款行；真实物理退货、成本总账与银行仍完全不在 CI 范围内。


## V202 本地采购批次原币来源核对（[精确代码 `4d07d87`](https://github.com/kunlong-luo/merchivra/commit/4d07d87c92366b661ad9c5400b759ac73d98362f) · [快速 CI #45105511319 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45105511319) · [完整 A3 #45105511271（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45105511271)）

`OrderLotProvenanceV202Test` 与 Next Zod/Vitest 检查 Owner/租户隔离、订单成本不 READY 时不开采购来源扫描、冲突/多币种不可任选金额、且响应恒为 `actualBatchCogsVerified=false`。`scripts/smoke_local_lot_cost_provenance_v202.py` 使用 **生产 Java 的同一 SQL**、PG18 TEMP/ROLLBACK 演示隔离时间范围、legacy receipt、QC hold/放行、QC 错连、批次 SKU 冲突、ledger 数量差、多原币/单价。本轮精确 SHA 快 CI/完整 A3 分别 SUCCESS，A3 首次 7/7 包含 PG18 对生产 Java SQL 的隔离负例；任何合成绿灯都不是 Amazon 出库批次来源或 G1 的真实确认。


## 最新已确认工程技术证据

- **V198 / `11864a739451f0e91c0bd9639b2c4bfe441b518e`**：[精确代码 `11864a7`](https://github.com/kunlong-luo/merchivra/commit/11864a739451f0e91c0bd9639b2c4bfe441b518e) 的 [完整 A3 #45021678686（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45021678686)，及 [同 SHA 快 CI #45021678580](https://github.com/kunlong-luo/merchivra/actions/runs/45021678580) SUCCESS。首次 `f5bc560` 的 [完整 A3 #45021496225](https://github.com/kunlong-luo/merchivra/actions/runs/45021496225) **FAILED**，原因是老版 `SettlementSourceReviewServiceTest` 构造参数以及旧 Vitest 比较夹具缺少五个新字段；并非放宽判定后通过，现已修正。实际 PostgreSQL 18 阶段先前已通过并有 `PASS V198 real PG18 exact Java SQL` 日志证据。旧 V58/V31 只读比较补齐重复结算归属/重复报告/缺失行与混币检测，Java/Vitest 合同负例及原 SQL 的 PG18 TEMP 表 `ROLLBACK` 验证。修改 `ci.yml` full_suite 跑真实 PG18；V198 修复后的同 SHA 完整 A3 7/7 验证独立成立，与 V197 的历史绿灯互不借用。


- **V197 / `db9d88be81ec326194242f75596661cb950cbea7`**：[V197 完整 A3 #45020338812（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45020338812)、[同 SHA 快 CI #45020338752](https://github.com/kunlong-luo/merchivra/actions/runs/45020338752)，两者 **SUCCESS**。在退款 V170/V63 只读 API 增加带符号金额/币种/事件/订单跨事务一致性核对及结构化 COGS 阻断码；配套 Java 单测、Next Zod 合同负例与已有页面展开明细。无新迁移，不启用真实 Amazon/Ads，不能仅因测试通过就允许退款库存/COGS 过账。


- **V194/V105 / `9cdb9268020bdc9ae0c743e09d5d5775c377ce69`**：[最终完整 A3 #45018945885（run_attempt=1）](https://github.com/kunlong-luo/merchivra/actions/runs/45018945885) **7/7 核心任务 SUCCESS**：真实 PG18/Flyway V105、V103 不可变、V194 NULL-safe 双向配对负例、Session/HTTP、Java/Gradle、Next16/React19/Chromium、Compose 安全与恢复；[同 SHA 快 CI #45018945926](https://github.com/kunlong-luo/merchivra/actions/runs/45018945926) **SUCCESS**。全部只含合成数据，不代表外部 Amazon/银行/税/退款 COGS 核证。

- **V195 / `2136375590abaae2373c565a1de930aa4ce45483`**：[快速 CI #45016097852（run_attempt=1）](https://github.com/kunlong-luo/merchivra/actions/runs/45016097852) **SUCCESS**：Java25、Next16/React19/pnpm/Orval、Vitest、Next 与 Vite 构建、Compose；**PG18 完整 job 在快速 CI 中按策略 SKIPPED**。V195 仅改善会话故障 UI，用户本机 HTTP 500 根因未定位。
- **V194/V105 前四轮失败历史（已通过第五轮完整复测）**：首次 [A3 #45017612506](https://github.com/kunlong-luo/merchivra/actions/runs/45017612506) 发现旧 V188 对不完整报告期望 200；第二次 [A3 #45017957824](https://github.com/kunlong-luo/merchivra/actions/runs/45017957824) 发现重叠 CHECK 的报错顺序断言；第三次 [A3 #45018224081](https://github.com/kunlong-luo/merchivra/actions/runs/45018224081) **真实证明 V104 CHECK 因 SQL NULL/UNKNOWN 可放行单边指纹**。V105 前进迁移补明确 IS NOT NULL 与双向半指纹负例，最终由上述 `9cdb926` 完整 A3 验证通过；失败历史和复测记录保留。
- **V194 / `9d4ebadb7abe45369ea55a7bb6c33b09e920d985`**：[快速 CI #45014853040](https://github.com/kunlong-luo/merchivra/actions/runs/45014853040) **SUCCESS**；V104 指纹仍待自己的完整 PG18/Flyway/Owner HTTP/A3，不能借用 V193 成果。
- **V193 / `600883236a3669f51b6703229447d93499b271cd`**：[完整 A3 #45012495202（run_attempt=2，SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45012495202) 验证其精确 SHA 的 V103 冻结数据库 DML 守护及合成 PG18；**不覆盖**后续 V194/V195。
- **V192 / `189932197ddeaadf1470f8692aa6be88d5e015c1`**：[完整 A3 #44896004578（run_attempt 2，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/44896004578) 验证真实 PG18 V188 逐订单 SKU 金额守恒，失败的首次验证与修复记录保留；仍无真实费用核验。
- **V191 / `8dab97147010fdc440ee81abcfa67cd1d1d57261`**：[快速 CI #44882890791](https://github.com/kunlong-luo/merchivra/actions/runs/44882890791) 与 [完整 A3 #44882890834](https://github.com/kunlong-luo/merchivra/actions/runs/44882890834) SUCCESS，完整 A3 **7/7 核心任务成功**；真实 Seller/Ads/银行**未启用**。
- [GitHub Pages #44882736480](https://github.com/kunlong-luo/merchivra/actions/runs/44882736480) 只验证虚构静态展示构建与隔离，不表示公开 ERP 已上线。
- **文档提交后的新 HEAD** 必须查该 SHA 的 CI，不能自动继承 V191/V193 业务 SHA 的 A3 结果。文档规则见 [ENG-001](../governance/engineering-standards.md)、[GOV-001](../governance/document-control.md)。

## 本地快速命令

```bash
./apps/server/gradlew -p apps/server --no-daemon test
(cd apps/web && pnpm install --frozen-lockfile && pnpm api:check && pnpm typecheck && pnpm test && pnpm build && pnpm build:vite)
python3 scripts/check_ci_cadence_v108.py
python3 scripts/check_docs_consistency_v109.py
python3 scripts/check_next_runtime_v122.py
```

**核心限制**：真实费用来源、重复扣费真实性、退款 COGS 回转、外部 WMS 物理实收、跨币种会计政策和银行结算均未因上述合成测试自动核验。不得把 `realizedNetProfit=null` 重写为已确认利润。生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) 一律 NO-GO；历史 [V108 CI 策略](../roadmap/mvp-freeze-release-v108.md) 为保留的阶段性记录，不优先于此页。

## V203 销售批次归属声明数量守恒与失效（[精确代码 `3d94550`](https://github.com/kunlong-luo/merchivra/commit/3d94550b9a2ef5a53c15db358b390ed88b60478b) · [同 SHA 快 CI #45116800143 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45116800143) · [完整 A3 #45116800085（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45116800085)）

- Java `OrderLotAssignmentIntegrityV203Test` 检查 Owner/no-store、source item 与历史 SKU 映射变更、无声明和状态变更时不得确认实际 COGS，任何前端/后端均不得将本地一致性误当外部证明。
- 隔离 PostgreSQL 18：`MERCHIVRA_V203_DISPOSABLE_ONLY=true python3 scripts/smoke_sale_lot_assignment_v203.py`，在 Flyway V106 已运行的 disposable 数据库，以 TEMP + ROLLBACK 执行实际数据库触发器和源 SQL 视图；分别验证 item/pick/receipt 三维累计数量、唯一归属、并发锁来源、QC 和出库漂移、撤销不释放已分配数量、不可 UPDATE/DELETE 和布尔硬阻断。生产真实身份/签名/财务入账不在合成验证范围。
- V203 精确代码提交的快 CI 和完整 A3 7/7 经过 GitHub 核对，包含 Flyway V106 与匿名 HTTP 401；第一次和第二次 A3 的负例测试夹具错误均保留为历史失败，本次真实 PG18 测试修正后已通过。代码 SHA 是 [精确代码 `3d94550`](https://github.com/kunlong-luo/merchivra/commit/3d94550b9a2ef5a53c15db358b390ed88b60478b)，绝不引用 V202 的 green 或将合成验证当成外部真实性。

## V204 签名出库观察（[V204 精确代码 `f89485f`](https://github.com/kunlong-luo/merchivra/commit/f89485fe0b85cb63f7e12614f254c4ba57ed9be9) · [同 SHA 快速 CI #45122956620 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45122956620) · [完整 A3 #45122956610（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45122956610)）

- Java `SaleLotSignedDispatchV204Test` 在 JDK 25 生成真实 Ed25519 测试公私钥并验证规范化签名，对 claim/source/quantity/external dispatch 字段做篡改负例，验证无证据与跨租户 Owner/no-store 端点仍不把 WMS 观察升级为实际 COGS。
- `MERCHIVRA_V204_DISPOSABLE_ONLY=true python3 scripts/smoke_signed_sale_dispatch_v204.py` 要求 Flyway V107 已应用且使用 PostgreSQL 18；用 TEMP 阴影表和安装的真实触发器 + 源 SQL 视图检查无密钥证明、唯一归属、防重放、密钥撤销、原 claim 失效、数量漂移、跨租户、禁止删改和数据库 `CHECK(FALSE)` 阻止伪造真实财务信号。纯 SQL 无法独立重算 Ed25519，必须与 Java 真实签名测试组合；全链路应验证未登录 GET 和 POST 均返回 HTTP 401，CSRF 仍启用。
- V204 精确代码提交已在 GitHub 对应快 CI 与完整 A3 确认 SUCCESS，PG18 真实触发器 + 独立视图日志含明确 `PASS V204`。首次工作流过长导致两个 Actions 运行未创建任何 jobs；将冗长内联注释归档至文档、保留独立 Python V204 smoke 调用后恢复正常。**工作流立即 failure 且 0 jobs 不等于测试失败；必须先核查 YAML/行长/步骤大小，再核查正常任务的真实日志。** 所有通过仅为隔离合成数据，不构成 Amazon/FBA/FBM 或 WMS 外部主体认证。

## V205 证据档案与独立内部复核（[V205 精确代码 `99ef9b9`](https://github.com/kunlong-luo/merchivra/commit/99ef9b988de101f3413e0821817071bcce85c370) · [同 SHA 快速 CI #45132910751 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45132910751) · [完整 A3 #45132910387（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45132910387)）

- Java `DispatchCustodyDossierV205Test` 直接验证危险编号/散列输入、想越级到身份 VERIFIED 的状态、自审/来源签名更新的独立失效逻辑、GET/POST Owner/no-store 接口与无外部证据时实际 COGS 的硬阻断。
- `MERCHIVRA_V205_DISPOSABLE_ONLY=true python3 scripts/smoke_dispatch_custody_dossier_v205.py`：检查 PG18/Flyway V108 的真实触发器/完整原始 SQL 视图，TEMP 阴影表 + ROLLBACK，不调用生产 WMS/Amazon。覆盖注册来源撤销、签名漂移、数据库快照覆盖伪参数、档案/复核不可变、三类有利益冲突的人不得独立复核、相同交接编号摘要冲突、重复提交、跨租户及实际身份/成本布尔值永假。
- CI 中加入单行 Python V205 smoke 调用，避免超大 Shell 工作流拒绝创建 jobs；Owner 匿名 HTTP 401/拒绝 POST 和 CSRF 控制不可关闭。精确 SHA 的快 CI 和完整 A3 已成功，完整 A3 7/7 SUCCESS，原始 PG18 job 日志含 `PASS V205 PG18 V108 original capture/review guards and source view`；不等于真实 B2 外部运营主体认证。

## V206 私有原件安全与数据库字节重算（[V206 精确代码 `c85feba`](https://github.com/kunlong-luo/merchivra/commit/c85feba8cf77919e89a1626d9ddc0981f13deb74) · [同 SHA 快速 CI #45138354698 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45138354698) · [完整 A3 #45138354709（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45138354709)）

- Java `DispatchCustodyOriginalV206Test`：复用 V52 文件魔数、SHA-256、2 MiB 输入限制；V137 clamd 失败先阻断，不应尝试读取或写入数据库；测试 Owner/Session/CSRF/no-store、上游来源失效优先、没有两份原件时无法解除外部主体/实际 COGS 阻断。
- `MERCHIVRA_V206_DISPOSABLE_ONLY=true python3 scripts/smoke_custody_original_content_v206.py`：真实 PostgreSQL18 安装 V109 后，在 TEMP 表上复用原 V109 触发器和源 SQL 视图、整事务回滚；检验 BYTEA 生成散列与读时 SHA-256 独立复算、两份缺一份、非真实魔数 MIME、错误声称散列、同种重复文件、跨租户、来源撤销、不可变及硬性 FALSE 身份/过账约束。CI 除上述脚本外须验证匿名 GET/POST 返回 401、Next/Java/Compose/Chromium 回归。
- 由于这是安全/财务/数据库变更，该 V206 精确代码 SHA 快 CI 和完整 A3 7/7 已 SUCCESS，PG18 job 原始日志含 `PASS V206 PG18 V109 exact original trigger and source view`，不可套用 V205 结果；绿灯不代表生产 ClamAV、外部法人、承运商、真实平台来源或 G1 审批已经核证。

## V207 法律保全/留存期限复核与私有原件元数据访问审计（[V207 精确代码 `8eb1b86`](https://github.com/kunlong-luo/merchivra/commit/8eb1b86a26caf8f73149e9dab6215e65c9a2574d) · [同 SHA 快速 CI #45140330888 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45140330888) · [完整 A3 #45140331187（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45140331187)）

- Java `DispatchCustodyOriginalGovernanceV207Test` 拒绝删除/解除 hold/外部核验的伪决策、过短/超长理由、过期/超长保留期限、无单文件证据时披露和未授权 Owner 操作；API 仅 no-store 元数据。
- `MERCHIVRA_V207_DISPOSABLE_ONLY=true python3 scripts/smoke_custody_original_governance_v207.py` 需要应用的**真实 PG18 Flyway V110**，使用安装的 V110 原始触发器和 V109/V110 源视图在 TEMP 中逐条验证默认永久 hold、分离上传人/提案人/复核人、上游证据撤销、跨租户访问、数据库覆盖伪 SHA/长度、禁止假标记二进制外流、不可变提案/复核/审计和永假财务核验，最后 ROLLBACK。
- 精确 V207 代码 SHA 的完整 A3 已 Java、Next、Chromium、HTTP、Compose、PG18 7/7 SUCCESS，原始 PG18 Job 日志含 `PASS V207 PG18 V110 exact guards and source view`；快 CI 中 PG18 skip 并未计为专项验收。所有合成原件不认证机构和承运商，内部留存审批不能解除保全或执行删除。

## V208 外部签发公钥的密码学完整性与来源撤销（[V208 精确代码 `6678558`](https://github.com/kunlong-luo/merchivra/commit/66785586f1856fc5647c48bbf4a975152a16fce7) · [同 SHA 快速 CI #45150207475 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45150207475) · [完整 A3 #45150207574（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45150207574)）

- Java `CustodyIssuerAttestationV208Test`：运行时真实 Ed25519 公/私钥正例、文档类型/SHA/文件和档案 UUID/公钥篡改负例、换行注入、过界签发时间、未知来源无 COGS、Controller 租户 Owner/no-store；真实签名验证仍不证明外部签发方身份。
- `MERCHIVRA_V208_DISPOSABLE_ONLY=true python3 scripts/smoke_custody_claimed_issuer_signatures_v208.py`：**真实 PG18 + Flyway V111**，TEMP 表执行原始部署的公钥 review/撤销/签名 snapshot PL/pgSQL 及 V109/V111 完整源视图，ROLLBACK；覆盖双角色分离、来源 key 类型不一致、跨租户、独立导入人、数据库自行冻结 SHA/订单/出库/公钥引用、不可变/重复签名、已撤销密钥与上游签名失效、外部身份/COGS 永假。该 SQL 层不能证明真实 Ed25519，必须依赖 Java 正反例。
- CI 在合成 PG18 路径插入 V208 smoke 与匿名 GET/POST 401 断言，并通过 Java、Next、Chromium、Compose、真实 HTTP 全 A3 7/7；快 CI 的 PG18 skipped 不等于专项通过。同 V208 代码 SHA 的快速 CI 与完整 A3 7/7 已 SUCCESS；PG18 原始 Job 日志明确 `PASS V208 PG18 V111 exact source capture/key guards/view`，不能用快 CI 中的 PG18 skip 冒充专项验收。外部权威 CA/企业/承运实际核验仍不可宣称。

## V209 部署侧离线证书链与 CRL 失效（[V209 精确代码 `0a9dc09`](https://github.com/kunlong-luo/merchivra/commit/0a9dc0903a92b64d5891c03011f5b3ef85fbc560) · [同 SHA 快速 CI #45155665615 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45155665615) · [完整 A3 #45155665675（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45155665675) · [同 SHA Redis Session 集成 #45155665716 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45155665716)）

- `OfflineIssuerCertificateTrustV209Test`：仅使用 `src/test/resources/v209/` 下的**合成公钥证书和签名 CRL，绝不提交私钥**；固定时钟执行 Java 25 PKIX 对受限根+Ed25519 leaf+DNS SAN+SPKI 哈希+签名 CRL 的技术正例。对同实例 CRL 新签发撤销、CRL 过期、错误 SAN/角色、错误 SPKI、缺失文件、伪造根证书、禁用开关及没有 V208 签名/来源的情况测试失败关闭。生产不可用这些合成根证书。
- CI 新增 `GET /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/custody-external-trust-readiness` 匿名 401 检验；读操作无新数据库迁移。安全敏感行为仍按精确 SHA 跑 Java25/Next16/Chromium/Compose/HTTP/PG18 A3；不以快速 CI 中的 PostgreSQL skipped 冒充 PG18 联调。同一 V209 SHA 快速 CI、Redis Session 与完整 A3（7/7）已 SUCCESS，后者包括真实 PG18/HTTP 回归、Java PKIX/签名 CRL 合成正反例。由于没有 V209 新迁移，不能声称运行了“V209 Flyway 源触发器”；离线链和 CRL 通过仍不能证明根证书分发来源、真实运营公司、实体签收或净利润。

## V210 全订单退款数量守恒与退款 COGS 风险（[V210 精确代码 `dc7e1b3`](https://github.com/kunlong-luo/merchivra/commit/dc7e1b336bc5d1ac762c2172db657b5961a8bbdd) · [同 SHA 快速 CI #45159869191 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45159869191) · [完整 A3 #45159869175（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45159869175)）

- Java `RefundOrderConservationV210Test`：以两笔 partial refund、原单数量 5、手工退款数量 2+1、USD 来源 -20/-10 验证不重复的原币与未核证历史成本 24 / 声称返仓敏感度 16；跨事件数量 3+3>5、V34/V63 数量/处置漂移、来源金额/订单/币种漂移、V34 孤儿、未做 V63、缺原单数量、正数“退款”、币种隔离和重复 source/item 都须 fail-closed。完整本地人工平衡不自动放开真实 COGS 或利润。
- `MERCHIVRA_V210_DISPOSABLE_ONLY=true python3 scripts/smoke_refund_order_conservation_v210.py`：真实 PG18、现有 Flyway 不变，直接抽取 V210 Java 三个 SQL text block，在 TEMP 合成测试数据验证**两次全量匹配事件、原币金额不重乘、跨租户/订单、旧/虚构订单行与当前数量更新**；事务末 ROLLBACK。无 V210 新迁移，不能声称新 Flyway 版本验收。
- 本轮同 SHA Java25、真实 PG18/HTTP、Next16、Chromium、Compose 完整 A3 已 7/7 SUCCESS，PG18 Job 原始日志明确 `PASS V210 PG18 exact full-order LIVE refund source SQL`；V210 没有单独新增 HTTP 匿名 401 smoke，仍由现有 Spring Security 全局 `anyRequest().authenticated()` 和 Controller Owner/no-store 单元测试覆盖，端点级 401 独立回归属后续补充。快 CI 的 PG18 skipped 不算数据库专项。退款真实来源、B2/B3、G1/DEC 与 `realizedNetProfit=null` 不变。

## V211 期间退款跨期/重复快照与 V188/V210 阻断（[V211 代码 `fec6371`](https://github.com/kunlong-luo/merchivra/commit/fec6371386e5f1a550be6b12856d627ad94b515f) · [同 SHA 快速 CI #45165293616 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45165293616) · [完整 A3 #45165293551（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45165293551)）

- Java `PeriodRefundProfitGuardV211Test`：同一订单的本期及下期退款 + 不同原币事件、源 0/2 匹配、超出 V64 冻结订单的旧单退款、V210 新/变更的原币/时间事件、重复源/订单、两个重叠 V64 报表不是实际重复入账、无分类来源不能认 0、Owner/no-store GET。全部真实成本/净利润 null。
- `MERCHIVRA_V211_DISPOSABLE_ONLY=true python3 scripts/smoke_period_refund_profit_guard_v211.py`：使用本次 Java 服务的**三个原始 SQL**于已安装现有 Flyway 的真实 PG18 TEMP 合成数据中验证事件不因 2 个 MATCHED 关联被放大、USD/EUR 分币、漏归属/错订单/错租户、冻结订单下期退款仍可发现、其他 V64 快照区分本次报表，末尾 ROLLBACK。V211 不新增 Flyway，不能声称 V112 检查。
- 完整 A3 同一代码 SHA Java25/PG18/HTTP/Next16/Chromium/Compose 7/7；单独新 GET 匿名 401 保护。React/Vitest 以 Zod 断言收入/成本认定字段硬 false/NULL、只在用户点击后获取较重的源核对；快 CI 的 PG18 skipped 不算全链路。真实财务/第三方身份仍未经认证。精确代码 SHA 的 A3 7/7 SUCCESS，PG18 真实日志 `PASS V211 PG18 source-exact period/refund cohort/overlap SQL`；实际执行了三个 Java 原文查询、未匹配/重复关联/跨期测试和匿名 401。快 CI 中 PG18 为 skipped，不得冒充这一结果。

- **GitHub Workflow 维护注意**：本阶段 `ci.yml` 增量膨胀至约 41 KiB 时曾出现 GitHub Actions 在创建 Job 之前失败（0 Job）；删除旧版注释、压缩到约 40 KiB 后恢复正常调度。之后应抽出 PG18/HTTP 专项到受版本控制的脚本，保持 YAML 精简；不能将“0 Job 失败”当作 Java/PG18 已运行的验收结论，也不应依赖未经证实的 GitHub 具体大小上限。

## V212 退款原销售批次与返仓数量包络（[V212 精确代码 `921c2e2`](https://github.com/kunlong-luo/merchivra/commit/921c2e250ad4554349fb290e777c650b279fc259) · [同 SHA 快速 CI #45170646181 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45170646181) · [完整 A3 #45170646128（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45170646128)）

- Java `RefundBatchLineageV212Test`：多退款事件 2+1、同订单原批次认领 2+1 分别 USD/EUR、V171/V172 声称返仓与收货 2；原批次不足、V203 撤销/当前 SKU 失效、pending 不算物理见证、上游 V171 未审核、双包裹超额、重复 V106/V34、无实际退款 COGS 或非 NULL 净利润、Owner/no-store。
- 真 PG18 通过 `MERCHIVRA_V212_DISPOSABLE_ONLY=true python3 scripts/smoke_refund_batch_lineage_v212.py`：源源码的 V212 3 条 SQL 原文，在 TEMP 合成 V34/V106/V96/V97 上验证两个本地原销售批次不乘法放大、出入库与人工声称的关联、原币成本参考、跨租户/Marketplace/订单排除和读时失效。**V106 integrity 视图在这个专项中是 TEMP 影子，不是对真实 V106 VIEW 的再验证；原 V106/PG18 由 V203 旧专项核验**。所有新测试写入仅在隔离库且 ROLLBACK；V212 无新 Flyway 版本。
- 同 SHA 快速 CI + 完整 A3 Java25/Next16/Chromium/PG18/HTTP/Compose 7/7，匿名 GET 必须 401。未知不能填 0、不得宣布独立 Amazon/仓库/银行的真实性已完成。
- **实际验收（V212，代码 `921c2e2`）**：同 SHA [同 SHA 快速 CI #45170646181 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45170646181)、[完整 A3 #45170646128（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45170646128) 均 SUCCESS，A3 7/7。PG18 作业原始日志含 `PASS V212 PG18 exact V34/V106/V96/V97 source SELECT scope`，检查了两原始销售批次 2+1、不同采购原币参考、V171/V172 见证与 pending、租户/订单隔离及来源变更。此脚本用 TEMP 阴影 V106 view，不能代替实际 V106 VIEW/Flyway V106 触发器专项的证明；HTTP 新 GET 匿名 401 断言执行且通过。
- **CI/Chromium 实际修复**：最初增量后 `ci.yml` 约 40.6 KiB 时 Actions 未创建 Job，不能当作 Java 或 PG18 失败；复用九条 Owner 401 URL 的公共 shell 路径将文件精简至约 39.8 KiB，调度恢复且没有删除任何 401 断言。首次 V212 完整 A3 的 V15 邀请页面检查在 Next 路由短暂卸载间隙返回 `undefined`；将“成员行及邀请表单存在”改为在同一个稳定页面内重试，**保留原 V15 安全检查**，新同 SHA A3 的 Chromium 任务 SUCCESS。避免以后无限向单个 YAML 添加长 HTTP 命令，优先抽脚本，且不能将此现象归因于未经证实的某个 GitHub 硬性字节限制。

## V213 原采购收货跨订单全局容量（本轮精确代码验收待回填）

- `RefundGlobalReceiptCapacityV213Test`：2–3 本地销售订单共享同一收货批次，原 QC 仍足够、不合法跨订单总领取超 QC、历史撤销仍占容量、不同采购价格/币种/接受量快照版本、原单领取超销量、重复/缺失来源、Owner/no-store 和实际 COGS/利润 false/null。
- `MERCHIVRA_V213_DISPOSABLE_ONLY=true python3 scripts/smoke_refund_global_receipt_capacity_v213.py`：真实 PG18 与 V111 Flyway 前置，使用 Java 真实聚合 SQL 和 TEMP V106 claim/view 影子模拟跨三单共享、USD/EUR、撤销仍占容量、不同租户/价格/ QC 数量漂移，末尾 ROLLBACK。TEMP 视图不是 V106 真触发器或真实采购成本/仓库核证。
- 同一精确代码 SHA 完整 A3 Java25/Next16/Chromium/PG18/HTTP/Compose 7/7，匿名新 Owner GET 401；快 CI 的 PG18 skipped 不算新数据库专项。避免继续加长已有 YAML，优先用现有脚本。
