# Merchivra · 当前事实与验收状态

**CURRENT · 2026-11-27 · V290 RC 工程验收已完成；V291 外部材料阻塞。** 这是当前快照，不是版本流水账。每次开工先核对实际 main HEAD 与 Actions；本页可能滞后于正在运行的 CI。任务顺序仅看 [current-plan.md](roadmap/current-plan.md)。

## 当前任务交接

- Task: V291
- State: BLOCKED
- Basis: V290 已 VERIFIED (synthetic)，精确候选 `f5c42fa4669cf8089363ddcb6c7d884c414d0aae`，完整 A3 7/7；本会话只关闭 V290，未领取或实施 V291。
- 阻塞：维护者未提供合法真实第三方原件/授权及独立财务批准；E1–E5、DEC-06/07/08、G1 未证。真实 Seller/Ads OAuth OFF，realizedNetProfit=null，#104 NO-GO。
- 唯一下一动作：维护者在合法本地/受控环境准备一个可独立复算的真实订单/批次/结算证据包（不上传原件或凭据），按现行计划从 E1 签发主体及原件真实性开始；材料缺失继续 BLOCKED，不重复合成 UI 或跳转 V292。
- 精确核验 HEAD [`d98f9601ddf2b32e2707bd5daba9ac515a164e86`](https://github.com/kunlong-luo/merchivra/commit/d98f9601ddf2b32e2707bd5daba9ac515a164e86)，[快 CI #45931034746](https://github.com/kunlong-luo/merchivra/actions/runs/45931034746) SUCCESS（run_attempt=1）：Java/Gradle #140283452228、Compose/cadence/文档与生产门禁守卫 #140283452063、Next/Vite/Orval/TypeScript/单元测试 #140283452319 成功；PG18 #140283453728、JaCoCo、完整 Next HTTP 按快 CI 范围跳过；无失败 job。与 V290 候选相比仅三份权威文档变更（3 commits），完整 A3 7/7 仅属于 V290 候选。该 SHA 的 CostProfitReadinessService.java 285–320 行仍 false/false/false/null，check_production_gate_v143.py 232–253 行仍禁止生产批准和部署；#104 OPEN，NO-GO。
- 本轮仅回填已独立核实的快 CI 与明确本地 E1 操作步骤，不改业务代码、迁移或工作流。提交标题 `docs(v291): record verified handoff and local E1 checklist`，parent 为 `d98f9601ddf2b32e2707bd5daba9ac515a164e86`。执行环境 DNS 无法解析 GitHub，无法克隆并运行提交前文档/cadence 守卫；新提交快 CI 尚待按精确 SHA 核对，不继承已有绿灯。无真实材料继续 BLOCKED；不得仅为回填自身文档 CI 反复提交，下一次可在提供真实材料时合并记录。
- [前次 V291 交接证据（不可变快照）](https://github.com/kunlong-luo/merchivra/blob/d98f9601ddf2b32e2707bd5daba9ac515a164e86/docs/current-status.md)。

## V291 本地配置维护（维护者明确要求）

- 基线 [`5bacf013ee1d58d3a9102a7252596b81bf86b8ba`](https://github.com/kunlong-luo/merchivra/commit/5bacf013ee1d58d3a9102a7252596b81bf86b8ba) [快 CI #45936142246](https://github.com/kunlong-luo/merchivra/actions/runs/45936142246) SUCCESS：Compose #140299680899、Next #140299681236、Java #140299681273 成功，PG18 #140299682184 跳过。
- 仅将 ClamAV 主机环境变量改为 `EVIDENCE_AV_HOST`，同步 Spring、生产 Compose、环境模板、发布门禁及正反测试；REQUIRED/PORT 和内部 Spring 属性不改名，扫描要求与生产门禁不放宽。迁移说明见[开发指南](development.md)。提交标题 `fix(config): rename evidence AV host environment variable`，parent 为 `5bacf013ee1d58d3a9102a7252596b81bf86b8ba`；本地未执行测试，精确新提交快 CI/运行验证待核，不继承旧 CI。V291 真实材料仍 BLOCKED，评分 78/100，realizedNetProfit=null、OAuth OFF、#104 NO-GO；不领取 V292。

- 后续维护者要求参考 Lanveriq 配置结构：新增可选 `DB_URL`，保留旧分段 DB_* 与密码无默认值，显式 SERVER_PORT 默认8080、graceful shutdown 与 Flyway validate-on-migrate；开发指南新增仓库外 IDEA 私有 YAML、8081/3001 共存方式。不引入 JPA、不批量改名、不改安全默认开关。基线 [`a024d43fc4771f7efb18455c612679b89135f5b6`](https://github.com/kunlong-luo/merchivra/commit/a024d43fc4771f7efb18455c612679b89135f5b6) [快 CI #45989310743](https://github.com/kunlong-luo/merchivra/actions/runs/45989310743) SUCCESS：Java #140472799372、Next #140472799808、Compose #140472799861 成功；PG18 #140472801322、JaCoCo、完整 Next HTTP 跳过；[Redis 专项 #45989311176](https://github.com/kunlong-luo/merchivra/actions/runs/45989311176) 核查时运行中。新提交标题 `fix(config): support local JDBC URL and private IDE overrides`，parent 为 `a024d43fc4771f7efb18455c612679b89135f5b6`；未本地运行，新增配置测试与 CI 待核；不改变 V291 BLOCKED、78/100、NO-GO。

- 维护者要求直接修改仓库适配共享 spark-db：新增显式 local profile（PG 127.0.0.1:5432/merchivra、Redis 127.0.0.1:6379、HTTP 仅 loopback 8081、自动读取用户私有配置）、共享 IDEA Gradle 运行配置、Linux `pnpm dev:local`（3001→8081），保留普通 Compose/CI 默认端口与安全开关；不写入密码、不启用共享 Redis Session/限流。基线 [`c13b19542b8a5b1fd382349dfb40b421e0da9cc7`](https://github.com/kunlong-luo/merchivra/commit/c13b19542b8a5b1fd382349dfb40b421e0da9cc7) [快 CI #45989680837](https://github.com/kunlong-luo/merchivra/actions/runs/45989680837) SUCCESS：Compose #140474039631、Next #140474040102、Java #140474040429 成功，PG18 #140474041962 跳过；[Redis #45989680815](https://github.com/kunlong-luo/merchivra/actions/runs/45989680815) SUCCESS。提交标题 `feat(dev): add shared spark-db local launch configuration`，parent 为 `c13b19542b8a5b1fd382349dfb40b421e0da9cc7`；本机及新增测试、精确新 SHA CI 待验收。V291 继续 BLOCKED，评分78/100，NO-GO。

- 维护者要求精简后端配置命名：对 65 个既有 Spring YAML 环境变量增加去项目名前缀的本机别名，旧显式输入优先；扫描 HOST 沿用已迁移短名。保留内部 Spring 属性、Compose/CI 接口、默认值、生产和外部授权门禁；同步 local profile、首次启动提示、回归测试、开发/工程规范。基线 [`6b54e6bbebdaa123406686da7163ebd1df14d213`](https://github.com/kunlong-luo/merchivra/commit/6b54e6bbebdaa123406686da7163ebd1df14d213) [快 CI #45990832176](https://github.com/kunlong-luo/merchivra/actions/runs/45990832176) SUCCESS：Compose #140477945924、Java #140477946351、Next #140477946363 成功，PG18 #140477948275 跳过；[Pages #45990832044](https://github.com/kunlong-luo/merchivra/actions/runs/45990832044) SUCCESS，非生产验证。新提交 `refactor(config): support concise backend environment names`，本机及精确新 SHA CI 待验收。V291 BLOCKED、78/100、OAuth OFF、NO-GO 不变。

- 维护者明确要求提交 local profile 的测试默认密码（公开仓库，值不得用于非一次性环境），仅调整本地 profile，不改变基础/生产密码注入与真实 OAuth 门禁，新增/同步默认值断言。基线 [`21b17127e35eef56ebf845a1d665624c07f6c010`](https://github.com/kunlong-luo/merchivra/commit/21b17127e35eef56ebf845a1d665624c07f6c010) [快 CI #45991284097](https://github.com/kunlong-luo/merchivra/actions/runs/45991284097) SUCCESS（Compose #140479452052、Next #140479452395、Java #140479452402；PG18 #140479453629 跳过），[Redis #45991284091](https://github.com/kunlong-luo/merchivra/actions/runs/45991284091) SUCCESS。新提交 `chore(dev): set explicitly requested local test defaults`，本机认证及精确新 SHA CI 待验证。V291 BLOCKED、78/100、NO-GO 不变。

- 维护者再次明确要求 YAML 内彻底去项目名前缀：两份后端 YAML 移除 65 项旧名嵌套，内部 Spring 属性/默认值不变；Compose 保留宿主机输入契约但 server 环境统一短名；PG18 启动、TLS 检查、生产门禁及测试同步，拒绝残留旧容器环境。基线 [`83b58a67c4c72a6062cd7960e9cbe7520a6a3c49`](https://github.com/kunlong-luo/merchivra/commit/83b58a67c4c72a6062cd7960e9cbe7520a6a3c49) [快 CI #45992239406](https://github.com/kunlong-luo/merchivra/actions/runs/45992239406) SUCCESS（仅基线）；新提交 `refactor(config): remove legacy prefixes from backend YAML`，新 SHA CI、完整 PG18/Compose HTTP 与本机测试尚待验收；V291 BLOCKED、78/100、OAuth OFF、NO-GO 不变。

## 最近已关闭任务：V290 · VERIFIED (synthetic)

- V290 候选精确 SHA [`f5c42fa4669cf8089363ddcb6c7d884c414d0aae`](https://github.com/kunlong-luo/merchivra/commit/f5c42fa4669cf8089363ddcb6c7d884c414d0aae)，run_attempt=1；[快 CI #45812139153](https://github.com/kunlong-luo/merchivra/actions/runs/45812139153) SUCCESS，Java25/Gradle、Next/Vite/Orval/TypeScript/Vitest、Compose 与 cadence/文档守卫成功；PG18 job、JaCoCo、完整 Next HTTP 按快 CI 范围跳过。[完整 A3 #45812139166](https://github.com/kunlong-luo/merchivra/actions/runs/45812139166) SUCCESS 7/7：基线 #139882418732、Compose/mock-chain #139882455787、Chromium #139882456008、Next/React #139882456066、Compose 静态 #139882456115、Java/JaCoCo #139882456169、实际 PG18/Flyway #139882456626 均成功；仅失败诊断步骤跳过，无失败 job。
- 原始日志：基线 MILESTONE=V290-A3-release-candidate；[HTTP/Compose job](https://github.com/kunlong-luo/merchivra/actions/runs/45812139166/job/139882455787) PASS V144（11 routes、真实 Spring Session/CSRF、匿名隔离、退出失效）、V122、V146；[Chromium job](https://github.com/kunlong-luo/merchivra/actions/runs/45812139166/job/139882456008) PASS V286 committed route, persistent session root, history back/forward, no retained category selection or replay；PASS V289 finance reconciliation and full category browser journey, including unique history re-entry。[PG18 job](https://github.com/kunlong-luo/merchivra/actions/runs/45812139166/job/139882456626) PostgreSQL 18.7、PASS V144、实际 PITR/Alertmanager、非跳过 PG18 JUnit marker；[Next job](https://github.com/kunlong-luo/merchivra/actions/runs/45812139166/job/139882456066) PASS unknown-route 404。精确源码 scripts/smoke_next_session_csrf_v101.py 确认 no-store、11 路由 SSR 无私密数据、独立匿名客户端、非法路由404及退出后401断言。全部为隔离合成证据。
- 候选相对于领取时 Basis `7a2471e1fe4d2982d4a3e45bfaa9f10ee5cdb1f3` 仅改变 milestone 默认标签/可选项/日志及三个权威文档；没有业务实现、测试逻辑或触发范围变更。无需重复修复 V289。RC 工程范围已验收，不是生产批准；评分仍 78/100，真实利润 10/25。
- [候选提交时待验证事实（不可变快照）](https://github.com/kunlong-luo/merchivra/blob/f5c42fa4669cf8089363ddcb6c7d884c414d0aae/docs/current-status.md)。

## 最近已关闭任务：V289 · VERIFIED (synthetic)

V289 精确代码 [`6583c63e57cfc0c7279e2f64dd1989719d23696b`](https://github.com/kunlong-luo/merchivra/commit/6583c63e57cfc0c7279e2f64dd1989719d23696b)；以下均为 run_attempt=1：

- [快 CI #45809305462](https://github.com/kunlong-luo/merchivra/actions/runs/45809305462) SUCCESS：Java25/Gradle、Compose（含文档与 cadence 守卫）、Orval、TypeScript、Vitest 97 个测试文件（含 [AST 专项 job #139872701718](https://github.com/kunlong-luo/merchivra/actions/runs/45809305462/job/139872701718) 1 项）、Next/Vite 构建通过；PG18 job、JaCoCo、完整 Next HTTP smoke 跳过。
- [独立 Chromium #45809305581](https://github.com/kunlong-luo/merchivra/actions/runs/45809305581) SUCCESS：[job #139872673779](https://github.com/kunlong-luo/merchivra/actions/runs/45809305581/job/139872673779) Vite 与 Next 实际 Chromium 合成场景通过；原始日志确认 `PASS V286 committed route, persistent session root, history back/forward, no retained category selection or replay` 与 `PASS V289 finance reconciliation and full category browser journey, including unique history re-entry`。不再重复 V289 修复。
- [Pages #45809305362](https://github.com/kunlong-luo/merchivra/actions/runs/45809305362) SUCCESS：只读静态构建、无 live OAuth 检查与公开演示部署成功，不等于生产 ERP。
- 实际差异仅 AST `?raw` 导入、浏览器断言/marker 与任务文档；无依赖、运行时权限、金额/租户契约变更。V289 无新增失败；后续 V290 精确候选已完整 A3 验收。原 V286/V287 失败和修复前交接见 [V289 旧事实快照](https://github.com/kunlong-luo/merchivra/blob/6583c63e57cfc0c7279e2f64dd1989719d23696b/docs/current-status.md)。

## 核心项目状态

| 项目 | 当前可独立证实的状态 |
| --- | --- |
| 技术栈 | main 直接开发；Java 25 / Spring Boot 4.1.2 / Gradle / PostgreSQL 18 / Flyway；React 19 / Next.js 16.4.5 / Node 24 / pnpm 12.20.0；Vite 仅回退及虚构 Pages |
| 独立成熟度 | [78/100（V290 已验收复评）](governance/project-scorecard-v231-2026-11-24.md)；真实利润10/25，非生产就绪比例 |
| 历史完整 A3 | V280 r2 `07fccfb4be98cdaee4d46f592c804dd8c5826d75` [#45742981332](https://github.com/kunlong-luo/merchivra/actions/runs/45742981332) 7/7；含实际 PG18 101行全量类型发现及双人审核 |
| 独立 Redis | V281 `cb9bb88eea150384fa87a121c60e2a11d4bf42cf` [#45743425032](https://github.com/kunlong-luo/merchivra/actions/runs/45743425032) SUCCESS：Redis8、真实登录/CSRF、重启身份、断连拒绝、GCRA |
| 最新主线发布候选 | V290 精确候选快 CI 与完整 A3 7/7 已成功；V290 交接及 V291 文档快 CI 分别成功；本次回填文档快 CI 另核 |
| 财务及授权 | `realizedNetProfit=null`、`bankReconciled=false`、`externallyVerified=false`；Seller/Ads OAuth OFF；真实 E1–E5/DEC/G1 未证 |
| 生产 | pre-alpha；[#104](https://github.com/kunlong-luo/merchivra/issues/104) NO-GO；目标 G2/G3 TLS/KMS/备份恢复/告警及正式签署缺失 |

## 已实际实现与能够使用的范围

- **核心经营链路**：租户、Seller 账户/Marketplace、Catalog/Listing 与内部 SKU、采购/供应商、FBA 货件/在途和库存批次、退款和异常工作台；本地读写经 Spring Boot Session/CSRF/Owner 与 PostgreSQL 18 事务及关键数量约束保护。真实 Seller/Ads OAuth 仍须由维护者在合法环境中本地授权。
- **财务事实与报告**：V64 仅限已知范围贡献、版本化捕获与读时独立重算；V162/V183/V188 按整期显示成本覆盖缺失与原币只读敏感度，而非伪造实际净利润。费用的非零原币/单据身份、V91 本地元数据审计、V94/V102 结算行关联与跨源防重、采购批次/发货与退货 COGS 门禁均为内部证据链，不代表真实平台已签发账单。
- **V226–V228C**：正式 Next 财务页允许 Owner 粘贴 1–20 条原币费用 TSV，Orval/Zod 预览/整批事务暂存、原 UUID 幂等回放、错误整批回滚、Reviewer 不可自审；所有来源保持 `MANUAL_UNVERIFIED`。详情 [V228](finance/v228-manual-cost-orval-batch-ux.md)。
- **V229**：`GET /cost-sources/{sourceId}/audit-integrity` 在单一 REPEATABLE_READ 中重新核对 V91 审计：初始 1 条完整、缺失 0 条/重复 2 条阻断；并核对后续独立 reviewer 的一条决策记录。详见 [V229](finance/v229-source-review-audit-integrity-and-external-proof-hold.md)。
- **V230B**：从 V228 暂存或重放来源点击“查看缺口”；单个 RR 快照复用 V229 和 V182/V165/V94/V102 的审计/结算/重复候选，按八类来源列出哪些**真正独立第三方原始文件、签发主体、原币收费与成本守恒**尚未证明。Next/Orval/Zod 一律拒绝伪造的 `VERIFIED`、跨 Marketplace、错误原币、非 null 真实利润等响应；报告没有外部真实过账行为。详见 [V230](finance/v230-external-source-proof-dossier-and-owner-workflow.md)。
- **V231 已验收（代码 SHA `9ff02147de17`、完整 A3 7/7）**：新增浏览器本地 SHA-256 按字节重算，最多读取 8 MiB 用户合法持有的文件，与 V91 **用户自述**摘要比较；不上传/保存文件或文件名，MATCH 仅称 `LOCAL_BYTES_MATCH_USER_CLAIM_ONLY`，不变更 V91/V182 审计、外部真实性或 `realizedNetProfit`。另外删去重复 `Agent.md` 和过时 V120 草稿，重建根中英文 README 目录用途、文档索引与自动目录守卫。详见 [V231 交付与清理](finance/v231-local-byte-claim-check-and-repository-pruning.md)。

- **后续已实现**：V249–V269 本地批次/退款成本候选、证据与双人复核（无实际回转），见 [退款成本契约](finance/v212-refund-original-sale-lot-quantity-envelope.md)；V270 本地报告/银行样本只读比较，见 [V60/V270](finance/manual-bank-payout-v60.md)；V271–V280 结算来源分组、末级分类、不可变映射、独立审核 HTTP、Next 工作台与全量类型发现，见 [结算契约](finance/v198-settlement-source-claim-integrity.md)。V285/V286 单根布局与路由调整、V287 key 修复及 V289 类型修复已通过快 CI 与独立 Chromium；完整 A3 已由 V290 精确候选通过。

## 生产阻塞与本地运行

- 真实第三方原件、签发主体、全部费用、采购/运输/关税及退货 COGS、银行归属、整期利润复算和 DEC/G1 未证；目标 G2/G3 未签。详见 [生产准入](operations/v143-production-release-preflight.md) 与 [现行 E1–E5](roadmap/current-plan.md)。
- 本地仅用受控合成环境，Next 3000、Spring 8080、PG18；见 [开发指南](development.md)。禁止上传凭据/真实财务原件，禁止对生产库运行合成破坏性脚本。

## 历史证据索引

- [V287 之前完整状态原文（不可变 Git 快照）](https://github.com/kunlong-luo/merchivra/blob/ca11f7a93d2b0b7bcff7bb5447e5b45436b51ba5/docs/current-status.md)：保留 V194/V195（#45016097852）、所有后续成功、失败、跳过及原始链接。不是现行计划。
- [领域证据导航](README.md) · [历史计划](roadmap.md) · [Git 提交历史](https://github.com/kunlong-luo/merchivra/commits/main/) · [文档控制](governance/document-control.md)。
