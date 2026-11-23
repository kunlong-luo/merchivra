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
