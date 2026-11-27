# Merchivra · 当前事实与验收状态

**CURRENT · 2026-11-27 · V290 RC 工程验收已完成；V291 外部材料阻塞。** 这是当前快照，不是版本流水账。每次开工先核对实际 main HEAD 与 Actions；本页可能滞后于正在运行的 CI。任务顺序仅看 [current-plan.md](roadmap/current-plan.md)。

## 当前任务交接

- Task: V291
- State: BLOCKED
- Basis: V290 已 VERIFIED (synthetic)，精确候选 `f5c42fa4669cf8089363ddcb6c7d884c414d0aae`，完整 A3 7/7；仅属于该候选。后续本地配置与前端退役维护见下。
- 精确基线 [`5bebad3e773161edacc2b1b03becd430686c45fd`](https://github.com/kunlong-luo/merchivra/commit/5bebad3e773161edacc2b1b03becd430686c45fd) [快 CI #46002940789](https://github.com/kunlong-luo/merchivra/actions/runs/46002940789) SUCCESS：Next #140518916953、Java #140518916955、Compose #140518917216 成功；PG18 #140518918768、JaCoCo、完整 Next HTTP 跳过，无失败 job。前一代码 SHA `2e58a59ba8e8155aac2a1b12b447310892b21b2c` 的 [Chromium #46002602282](https://github.com/kunlong-luo/merchivra/actions/runs/46002602282)、[Pages #46002602307](https://github.com/kunlong-luo/merchivra/actions/runs/46002602307)、[Redis #46002602506](https://github.com/kunlong-luo/merchivra/actions/runs/46002602506) SUCCESS；随后只修复退役 Dockerfile 测试引用和文档。
- 真实本机：维护者提供 2026-11-27 20:47 (+08:00) 启动日志，Java25.0.4、Spring Boot 4.1.2、Hikari 连接 PG18.7、122 项 Flyway 校验成功、schema V125、8081 启动、初始 OWNER 创建成功；此为维护者日志证据，非当前 SHA 自动化验收。维护者本地验收回执（2026-11-27，执行人：维护者；精确本地 SHA、用例输入、逐项日志/截图未提供）：管理员登录/退出、左侧菜单滚动及视觉、商品与 SKU、供应商与采购、仓库及库存、订单与退款、FBA 货件、财务报表及利润页面、Redis 本机认证，9/9 报告通过，无报告缺陷。记录为维护者功能级验收，不声称逐操作覆盖、独立重算或当前 SHA 自动化验证；Redis 认证不等于本机启用 Redis Session/限流。
- 财务与上线：核对该基线 CostProfitReadinessService 第308–313行仍 false/false/false/null，application.yml 中 SPAPI/OAuth/写入默认 false，V143 gate 始终拒绝批准/部署，[#104](https://github.com/kunlong-luo/merchivra/issues/104) OPEN。E1–E5、DEC-06/07/08、G1 未证；78/100 不变，realizedNetProfit=null，OAuth OFF，NO-GO。
- 唯一下一动作：维护者按 [本地验收/E1 清单](roadmap/current-plan.md) 在受控环境准备单一合法原始证据包，回传脱敏索引；无材料 V291 继续 BLOCKED，不领取 V292。真实资料不得上传 Git 或聊天；公开测试凭据仅限一次性环境。

- 本轮核对：精确基线 [`563fdd83b8ffa3fe926aa356032bd40e0c8ea10b`](https://github.com/kunlong-luo/merchivra/commit/563fdd83b8ffa3fe926aa356032bd40e0c8ea10b) [快 CI #46005205927](https://github.com/kunlong-luo/merchivra/actions/runs/46005205927) SUCCESS：Java #140526526124、Next #140526526495、Compose #140526526527 成功；PG18 #140526528662、JaCoCo、完整 Next HTTP 按快速范围跳过。完整 A3 属于 `efe94b5841b05dfa14de6fea0167a0bb3badebd2` [#46004710175](https://github.com/kunlong-luo/merchivra/actions/runs/46004710175) run_attempt=1，核查时 SUCCESS，7/7 成功（#140524861405 success、#140524894832 success、#140524895041 success、#140524895375 success、#140524895440 success、#140524895605 success、#140524895685 success）。 V230 服务仍返回外部真实性 false/null，V231 仅本地 SHA 比对，V270 既有离线 `checkLocalSettlementSample` 仅限定单一同币种结算算术一致；DEC-06/07/08 登记仍待确认。未获真实样本，不运行真实工具、不新增业务代码。现行计划新增可直接执行的私有取证/V270 命令与脱敏回执；E1 BLOCKED，评分不变。新提交 `docs(v291): operationalize private evidence intake` 仅文档，独立快 CI 待核。

## V291 本地配置与前端维护

- 以 [精确历史快照](https://github.com/kunlong-luo/merchivra/blob/5bebad3e773161edacc2b1b03becd430686c45fd/docs/current-status.md) 保留全部配置维护、失败/修复与运行记录。旧认证 Vite 已退役，Next 是唯一认证 ERP；公开 Pages/Vitest 仍使用必要 Vite 工具链。左侧导航隐藏滚动条、保留可滚动性；Chromium 已在上述代码 SHA 合成验证，维护者人工视觉已报告通过。
- 部署变更（Next-only、双 Next TLS 切换、配置重命名）按 TEST-003 需要新的完整 A3。修正 milestone 的当前标识为 `NEXT16-A3-next-only-local-readiness` 与浏览器 job 名称，按既有 workflow 文件变更触发规则启动隔离完整 A3；不更改测试范围、权限、真实授权、业务代码或迁移。提交标题 `docs(ci): reconcile next-only acceptance and V291 handoff`，精确基线 [`efe94b5841b05dfa14de6fea0167a0bb3badebd2`](https://github.com/kunlong-luo/merchivra/commit/efe94b5841b05dfa14de6fea0167a0bb3badebd2) [快 CI #46004710153](https://github.com/kunlong-luo/merchivra/actions/runs/46004710153) SUCCESS：Next #140524860211、Java #140524860489、Compose #140524860757；PG18 #140524862581、JaCoCo、完整 Next HTTP 跳过。 [完整 A3 #46004710175](https://github.com/kunlong-luo/merchivra/actions/runs/46004710175) run_attempt=1，核查时 IN_PROGRESS，6/7 成功：#140524861405 success、#140524894832 success、#140524895041 success、#140524895375 success、#140524895440 in_progress、#140524895605 success、#140524895685 success；完整结论以该 run 为准。 本轮仅更新三份验收文档，新文档 SHA 快 CI 待核。双 Next 同版本合成切换不构成真实旧版本镜像回滚或生产证明；目标回滚及 G2/G3 仍待独立验收。

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
| 技术栈 | main 直接开发；Java 25 / Spring Boot 4.1.2 / Gradle / PostgreSQL 18 / Flyway；React 19 / Next.js 16.4.5 / Node 24 / pnpm 12.20.0；Vite 仅虚构 Pages 与测试工具链；认证 ERP 仅 Next |
| 独立成熟度 | [78/100（V290 已验收复评）](governance/project-scorecard-v231-2026-11-24.md)；真实利润10/25，非生产就绪比例 |
| 历史完整 A3 | V280 r2 `07fccfb4be98cdaee4d46f592c804dd8c5826d75` [#45742981332](https://github.com/kunlong-luo/merchivra/actions/runs/45742981332) 7/7；含实际 PG18 101行全量类型发现及双人审核 |
| 独立 Redis | V281 `cb9bb88eea150384fa87a121c60e2a11d4bf42cf` [#45743425032](https://github.com/kunlong-luo/merchivra/actions/runs/45743425032) SUCCESS：Redis8、真实登录/CSRF、重启身份、断连拒绝、GCRA |
| 最新主线发布候选 | V290 精确候选完整 A3 7/7；Next-only 基线快 CI 成功，新的完整 A3 7/7 成功（completed）；本机9/9维护者报告通过；上一文档 SHA 快 CI 已成功；本轮文档 SHA 另核 |
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
