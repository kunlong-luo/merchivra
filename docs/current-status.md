# Merchivra · 当前事实与验收状态

**CURRENT · 2026-11-26 · V290 候选已提交，完整验收待核。** 这是当前快照，不是版本流水账。每次开工先核对实际 main HEAD 与 Actions；本页可能滞后于正在运行的 CI。任务顺序仅看 [current-plan.md](roadmap/current-plan.md)。

## 当前任务交接

- Task: V290
- State: IMPLEMENTED_PENDING_VERIFICATION
- Basis: `7a2471e1fe4d2982d4a3e45bfaa9f10ee5cdb1f3`（领取时 main HEAD；V289 交接文档快 CI 已独立核实）。
- 候选：本次唯一提交标题 `ci(v290): select release candidate A3 and record pending verification`，parent 为上述 Basis；下一会话以实际 Git SHA 与 Actions head_sha 确认。仅改里程碑标识及三个权威文档，无业务代码、测试逻辑、授权或触发范围改变。
- 基线快 CI [#45809895628](https://github.com/kunlong-luo/merchivra/actions/runs/45809895628) SUCCESS（run_attempt=1，精确 Basis）：Java/Gradle、Next/Vite、Compose 静态检查（含 cadence/文档守卫）成功；PG18 job、JaCoCo、Next 完整 HTTP smoke 跳过。不是候选的新 CI。
- 源码核查：`scripts/smoke_next_session_csrf_v101.py` 已断言真实 Session/CSRF、no-store、匿名客户端隔离、11 路由无 SSR 私密信息、非法路由 404、退出后 401；`scripts/smoke_browser_layout.mjs` 已断言 V286/V289 持久根、前后导航及无遗留选择/重放。复用完整 A3 原有 7 job 结构，未重复实现。
- 当前 GitHub 连接没有 workflow_dispatch 写入能力；采用现行 milestone 工作流自带 main 路径变更触发器，更新默认 run-name、实际日志标识和可选里程碑为 V290-A3-release-candidate。未扩大 CI cadence。候选的运行 ID、run_attempt、7/7 job 和日志 marker 未取得前一律待验证；未本地执行守卫，须以候选 CI 为准。
- 唯一下一动作：查询候选精确 SHA 的快 CI 和完整 A3，核实全部 job、V144 与 V286/V289 marker 及 PG18/Compose 实跑；未创建 run 则由维护者在同一 main HEAD 手动运行 release-candidate 或 V290-A3-release-candidate，失败续 V290，全部通过才关闭并转交 V291。#104 NO-GO 不变。

## 最近已关闭任务：V289 · VERIFIED (synthetic)

V289 精确代码 [`6583c63e57cfc0c7279e2f64dd1989719d23696b`](https://github.com/kunlong-luo/merchivra/commit/6583c63e57cfc0c7279e2f64dd1989719d23696b)；以下均为 run_attempt=1：

- [快 CI #45809305462](https://github.com/kunlong-luo/merchivra/actions/runs/45809305462) SUCCESS：Java25/Gradle、Compose（含文档与 cadence 守卫）、Orval、TypeScript、Vitest 97 个测试文件（含 [AST 专项 job #139872701718](https://github.com/kunlong-luo/merchivra/actions/runs/45809305462/job/139872701718) 1 项）、Next/Vite 构建通过；PG18 job、JaCoCo、完整 Next HTTP smoke 跳过。
- [独立 Chromium #45809305581](https://github.com/kunlong-luo/merchivra/actions/runs/45809305581) SUCCESS：[job #139872673779](https://github.com/kunlong-luo/merchivra/actions/runs/45809305581/job/139872673779) Vite 与 Next 实际 Chromium 合成场景通过；原始日志确认 `PASS V286 committed route, persistent session root, history back/forward, no retained category selection or replay` 与 `PASS V289 finance reconciliation and full category browser journey, including unique history re-entry`。不再重复 V289 修复。
- [Pages #45809305362](https://github.com/kunlong-luo/merchivra/actions/runs/45809305362) SUCCESS：只读静态构建、无 live OAuth 检查与公开演示部署成功，不等于生产 ERP。
- 实际差异仅 AST `?raw` 导入、浏览器断言/marker 与任务文档；无依赖、运行时权限、金额/租户契约变更。V289 无新增失败，完整 A3 尚未执行。原 V286/V287 失败和修复前交接见 [V289 旧事实快照](https://github.com/kunlong-luo/merchivra/blob/6583c63e57cfc0c7279e2f64dd1989719d23696b/docs/current-status.md)。

## 核心项目状态

| 项目 | 当前可独立证实的状态 |
| --- | --- |
| 技术栈 | main 直接开发；Java 25 / Spring Boot 4.1.2 / Gradle / PostgreSQL 18 / Flyway；React 19 / Next.js 16.4.5 / Node 24 / pnpm 12.20.0；Vite 仅回退及虚构 Pages |
| 独立成熟度 | [78/100（V290 待验收复评）](governance/project-scorecard-v231-2026-11-24.md)；真实利润10/25，非生产就绪比例 |
| 历史完整 A3 | V280 r2 `07fccfb4be98cdaee4d46f592c804dd8c5826d75` [#45742981332](https://github.com/kunlong-luo/merchivra/actions/runs/45742981332) 7/7；含实际 PG18 101行全量类型发现及双人审核 |
| 独立 Redis | V281 `cb9bb88eea150384fa87a121c60e2a11d4bf42cf` [#45743425032](https://github.com/kunlong-luo/merchivra/actions/runs/45743425032) SUCCESS：Redis8、真实登录/CSRF、重启身份、断连拒绝、GCRA |
| 最新主线发布候选 | V290 候选完整 A3 待验证；V289 精确代码快 CI/独立 Chromium/Pages 全绿，交接 Basis 快 CI 独立成功；旧 SHA 绿灯不能继承 |
| 财务及授权 | `realizedNetProfit=null`、`bankReconciled=false`、`externallyVerified=false`；Seller/Ads OAuth OFF；真实 E1–E5/DEC/G1 未证 |
| 生产 | pre-alpha；[#104](https://github.com/kunlong-luo/merchivra/issues/104) NO-GO；目标 G2/G3 TLS/KMS/备份恢复/告警及正式签署缺失 |

## 已实际实现与能够使用的范围

- **核心经营链路**：租户、Seller 账户/Marketplace、Catalog/Listing 与内部 SKU、采购/供应商、FBA 货件/在途和库存批次、退款和异常工作台；本地读写经 Spring Boot Session/CSRF/Owner 与 PostgreSQL 18 事务及关键数量约束保护。真实 Seller/Ads OAuth 仍须由维护者在合法环境中本地授权。
- **财务事实与报告**：V64 仅限已知范围贡献、版本化捕获与读时独立重算；V162/V183/V188 按整期显示成本覆盖缺失与原币只读敏感度，而非伪造实际净利润。费用的非零原币/单据身份、V91 本地元数据审计、V94/V102 结算行关联与跨源防重、采购批次/发货与退货 COGS 门禁均为内部证据链，不代表真实平台已签发账单。
- **V226–V228C**：正式 Next 财务页允许 Owner 粘贴 1–20 条原币费用 TSV，Orval/Zod 预览/整批事务暂存、原 UUID 幂等回放、错误整批回滚、Reviewer 不可自审；所有来源保持 `MANUAL_UNVERIFIED`。详情 [V228](finance/v228-manual-cost-orval-batch-ux.md)。
- **V229**：`GET /cost-sources/{sourceId}/audit-integrity` 在单一 REPEATABLE_READ 中重新核对 V91 审计：初始 1 条完整、缺失 0 条/重复 2 条阻断；并核对后续独立 reviewer 的一条决策记录。详见 [V229](finance/v229-source-review-audit-integrity-and-external-proof-hold.md)。
- **V230B**：从 V228 暂存或重放来源点击“查看缺口”；单个 RR 快照复用 V229 和 V182/V165/V94/V102 的审计/结算/重复候选，按八类来源列出哪些**真正独立第三方原始文件、签发主体、原币收费与成本守恒**尚未证明。Next/Orval/Zod 一律拒绝伪造的 `VERIFIED`、跨 Marketplace、错误原币、非 null 真实利润等响应；报告没有外部真实过账行为。详见 [V230](finance/v230-external-source-proof-dossier-and-owner-workflow.md)。
- **V231 已验收（代码 SHA `9ff02147de17`、完整 A3 7/7）**：新增浏览器本地 SHA-256 按字节重算，最多读取 8 MiB 用户合法持有的文件，与 V91 **用户自述**摘要比较；不上传/保存文件或文件名，MATCH 仅称 `LOCAL_BYTES_MATCH_USER_CLAIM_ONLY`，不变更 V91/V182 审计、外部真实性或 `realizedNetProfit`。另外删去重复 `Agent.md` 和过时 V120 草稿，重建根中英文 README 目录用途、文档索引与自动目录守卫。详见 [V231 交付与清理](finance/v231-local-byte-claim-check-and-repository-pruning.md)。

- **后续已实现**：V249–V269 本地批次/退款成本候选、证据与双人复核（无实际回转），见 [退款成本契约](finance/v212-refund-original-sale-lot-quantity-envelope.md)；V270 本地报告/银行样本只读比较，见 [V60/V270](finance/manual-bank-payout-v60.md)；V271–V280 结算来源分组、末级分类、不可变映射、独立审核 HTTP、Next 工作台与全量类型发现，见 [结算契约](finance/v198-settlement-source-claim-integrity.md)。V285/V286 单根布局与路由调整、V287 key 修复及 V289 类型修复已通过快 CI 与独立 Chromium；完整 A3 待 V290。

## 生产阻塞与本地运行

- 真实第三方原件、签发主体、全部费用、采购/运输/关税及退货 COGS、银行归属、整期利润复算和 DEC/G1 未证；目标 G2/G3 未签。详见 [生产准入](operations/v143-production-release-preflight.md) 与 [现行 E1–E5](roadmap/current-plan.md)。
- 本地仅用受控合成环境，Next 3000、Spring 8080、PG18；见 [开发指南](development.md)。禁止上传凭据/真实财务原件，禁止对生产库运行合成破坏性脚本。

## 历史证据索引

- [V287 之前完整状态原文（不可变 Git 快照）](https://github.com/kunlong-luo/merchivra/blob/ca11f7a93d2b0b7bcff7bb5447e5b45436b51ba5/docs/current-status.md)：保留 V194/V195（#45016097852）、所有后续成功、失败、跳过及原始链接。不是现行计划。
- [领域证据导航](README.md) · [历史计划](roadmap.md) · [Git 提交历史](https://github.com/kunlong-luo/merchivra/commits/main/) · [文档控制](governance/document-control.md)。
