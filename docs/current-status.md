# Merchivra · 当前事实与验收状态

**CURRENT · 2026-11-24 · V234 的 V116 真 PG18 回归已通过完整 A3 7/7；V235 快速 CI 通过；V236 验收受 CI 阻断；V237 修复工作流与文档引用待验收。** 这里记录“实际可以做什么”和“绝对不能推断什么”，不是各次版本新闻滚动记录。历史精确 CI/PR/SHA 保留在 [Vxxx 专项证据](README.md)、[Git 历史](https://github.com/kunlong-luo/merchivra/commits/main/)和 [旧文档归档](roadmap.md)，现行任务仅看 [current-plan.md](roadmap/current-plan.md)。

## 核心项目状态

| 项目 | 当前可独立证实的状态 |
| --- | --- |
| 代码主线与栈 | **`main` 直接开发**；Java 25 / Spring Boot 4.1.2 / **Gradle** / PostgreSQL 18 Flyway；React 19 / **Next.js 16.4.5** 默认 / Node 24 / **pnpm 12.20.0 唯一前端包管理器**。Vite 只为明确回退或虚构 Pages |
| 独立成熟度 | **[76/100（V231）](governance/project-scorecard-v231-2026-11-24.md)**；V230 75、V229 74、V228 73、V200 70 均为历史评分 |
| 真实全成本与净利润 | **10/25 的真实性子分不变**；`realizedNetProfit=null`、`bankReconciled=false`、`externallyVerified=false`，未取得真正完整可审计的 SKU/订单/整期净利润 |
| Amazon Seller / Ads | 模拟、本地安全 API 框架/契约已存在，但**真实 OAuth 授权 OFF**；实际 Seller/Ads/Finances/Settlement 未取得可独立验证的完整凭证 |
| 真实费用与退款 COGS | V91 手工声明和本地 Maker/Checker、结算行关联、来源归属和文件摘要声明**不等于**第三方原始签发证明；外仓实收、真实运输/关税/支付、退货批次成本冲回以及 DEC-06/07/08/G1 未签署 |
| 生产发布 | **pre-alpha / [#104](https://github.com/kunlong-luo/merchivra/issues/104) NO-GO**；G1/G2/G3、KMS、目标 TLS/备份恢复、实际告警接收、合法运营数据均未获独立签署 |

## 已实际实现与能够使用的范围

- **核心经营链路**：租户、Seller 账户/Marketplace、Catalog/Listing 与内部 SKU、采购/供应商、FBA 货件/在途和库存批次、退款和异常工作台；本地读写经 Spring Boot Session/CSRF/Owner 与 PostgreSQL 18 事务及关键数量约束保护。真实 Seller/Ads OAuth 仍须由维护者在合法环境中本地授权。
- **财务事实与报告**：V64 仅限已知范围贡献、版本化捕获与读时独立重算；V162/V183/V188 按整期显示成本覆盖缺失与原币只读敏感度，而非伪造实际净利润。费用的非零原币/单据身份、V91 本地元数据审计、V94/V102 结算行关联与跨源防重、采购批次/发货与退货 COGS 门禁均为内部证据链，不代表真实平台已签发账单。
- **V226–V228C**：正式 Next 财务页允许 Owner 粘贴 1–20 条原币费用 TSV，Orval/Zod 预览/整批事务暂存、原 UUID 幂等回放、错误整批回滚、Reviewer 不可自审；所有来源保持 `MANUAL_UNVERIFIED`。详情 [V228](finance/v228-manual-cost-orval-batch-ux.md)。
- **V229**：`GET /cost-sources/{sourceId}/audit-integrity` 在单一 REPEATABLE_READ 中重新核对 V91 审计：初始 1 条完整、缺失 0 条/重复 2 条阻断；并核对后续独立 reviewer 的一条决策记录。详见 [V229](finance/v229-source-review-audit-integrity-and-external-proof-hold.md)。
- **V230B**：从 V228 暂存或重放来源点击“查看缺口”；单个 RR 快照复用 V229 和 V182/V165/V94/V102 的审计/结算/重复候选，按八类来源列出哪些**真正独立第三方原始文件、签发主体、原币收费与成本守恒**尚未证明。Next/Orval/Zod 一律拒绝伪造的 `VERIFIED`、跨 Marketplace、错误原币、非 null 真实利润等响应；报告没有外部真实过账行为。详见 [V230](finance/v230-external-source-proof-dossier-and-owner-workflow.md)。
- **V231 已验收（代码 SHA `9ff02147de17`、完整 A3 7/7）**：新增浏览器本地 SHA-256 按字节重算，最多读取 8 MiB 用户合法持有的文件，与 V91 **用户自述**摘要比较；不上传/保存文件或文件名，MATCH 仅称 `LOCAL_BYTES_MATCH_USER_CLAIM_ONLY`，不变更 V91/V182 审计、外部真实性或 `realizedNetProfit`。另外删去重复 `Agent.md` 和过时 V120 草稿，重建根中英文 README 目录用途、文档索引与自动目录守卫。详见 [V231 交付与清理](finance/v231-local-byte-claim-check-and-repository-pruning.md)。

## V232 E1-A · 在途 Amazon 原件授权一致性（待验收）

本轮针对已有 Amazon Reports Worker/Scan Service 收紧 scope 比较，防止检测到刷新凭证/角色/站点变更后仍继续下载、入库或提交分页进度。新增两组 Java 合成回归；[精确 SHA `96e6d9750993`](https://github.com/kunlong-luo/merchivra/commit/96e6d97509930c88c85e31f013eb7bd483d90ce9) 的 [CI #45378894200](https://github.com/kunlong-luo/merchivra/actions/runs/45378894200) Java25/Gradle 与 React/Next 成功，Compose job 的文档长度守卫失败，PG18 跳过；整轮 FAILURE，未完成 A3，也没有真实授权报告证据。此项只是观察到的授权变化阻断，不能证明未发生 ABA 或真正第三方签发身份。V231 76/100、真实利润10/25、OAuth OFF、`realizedNetProfit=null` 和生产 NO-GO 仍是当前状态。具体任务见[现行计划](roadmap/current-plan.md)。

## V233 · 授权行版本与文档收敛（待验收）

V116 为 Seller grant 与 Finance role 行 INSERT/UPDATE 分配非重用序列 revision；Reports scope、扫描任务和新报告绑定版本对，旧任务缺版本需 Owner 重启，旧报告保持 NULL，禁止历史补证。Worker 比较原请求版本，导入和 checkpoint 重查并锁授权行。覆盖 grant/role 行版本变更，不是全部 tenant/member/Marketplace 资格输入的 ABA 保证。新增 Java 版本差异负例；[精确 V233 SHA](https://github.com/kunlong-luo/merchivra/commit/e8f2f199e24b2cdd99a3e3719510003f061dad01) 的 [快速 CI #45381222988 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45381222988) Java、Next、Compose 均成功，PG18 SKIPPED；完整 A3 尚待独立验证。真实外部原件及会计签署未取得，V231 76/100 不变。

## V234 · V116 真 PG18 回归（已验收）

新增 `scripts/smoke_reports_auth_revision_v234.py`，仅固定 GitHub main disposable PG18、OAuth OFF 运行。验证真实安装的 grant/role revision、两次独立提交 ABA、rollback、删除重建、跨租户 row lock、旧任务版本不匹配及原报告版本不可改写。SQL 测试不等于实际 Worker HTTP/真实签发验收；精确 [SHA `42ea8c9b4fad`](https://github.com/kunlong-luo/merchivra/commit/42ea8c9b4fadb6bcdb9a1446cb943c5518338e85) 的 [A3 #45386913964](https://github.com/kunlong-luo/merchivra/actions/runs/45386913964)（run_attempt=1，7/7 SUCCESS）及 [CI #45386913906](https://github.com/kunlong-luo/merchivra/actions/runs/45386913906) SUCCESS；PG18 job #138420153895 实际输出 PASS V234 actual PG18 V116 grant/role revisions。快 CI 的 PG18 按策略跳过。文档治理移除工作流重复历史注释，保留原始历史于 Git，不缩减执行任务。

## V235 · Reports 授权失败游标清除（快速 CI 已验收）

Owner start 在任务行锁后复查并锁定 grant/role 版本；授权缺失、版本不符或远程 401/403/本地 SecurityException 暂停时清除旧 token/offset，禁止自动重试授权错误；一般网络错误保留游标供有界重试，失效分页重启。新增 Java 分类负例；[精确 SHA bbdeed878892](https://github.com/kunlong-luo/merchivra/commit/bbdeed878892db67c40dc737972235d443c60078) 的 [CI #45392374653](https://github.com/kunlong-luo/merchivra/actions/runs/45392374653) SUCCESS；实际 Owner restart/跨进程服务验收仍需完成。仅本地工程证据，不提高外部真实性评分。

## V236 · Java 服务与 PG18 联动（待验收）

在既有 SettlementReportScanServiceTest 内追加仅 GitHub main disposable PG18 可运行的回归，由 A3 调用真实 JdbcTemplate、事务管理器、Seller vault 与 start/step/fenced import；远程 Worker 为合成 mock。覆盖旧缺版本任务暂停、Owner restart、旧 token 清除、grant revision 变化、在途 role revision 变化拒绝入库、重新启动后原始摘要与版本对冻结。未执行真实 Amazon API/真实会计认证。合并 docs/ops 至 docs/operations，保留运维手册，新增旧链接守卫。

## V237 · V236 验收阻断修复（待验收）

V236 首次提交 `9ddce626c205` 的 [CI #45394392278](https://github.com/kunlong-luo/merchivra/actions/runs/45394392278) Java/Next SUCCESS，Compose 文档检查因 architecture/deployment.md 仍引用已移动的 docs/ops/operations.md 失败，PG18 SKIPPED。随后精确 `0988f5f2ed92` 的 [快速 CI #45394458129](https://github.com/kunlong-luo/merchivra/actions/runs/45394458129) 与 [完整 A3 #45394460077](https://github.com/kunlong-luo/merchivra/actions/runs/45394460077) 均 FAILURE 且 API 返回 0 jobs；未取得具体 GitHub 工作流拒绝诊断，不能认定根因。V237 修复剩余旧链接，将 JUnit 非跳过验证移至独立 stdlib Python 脚本，保留原 A3 执行步骤和严格校验，重新触发验收。V236 服务测试尚未获真实 PG18 执行证明。

## 最近精确验收（切勿混用不同 SHA 的 CI）

| 阶段 | 精确代码 / 测试 | 技术证明边界 |
| --- | --- | --- |
| **V234** | [SHA `42ea8c9b4fad`](https://github.com/kunlong-luo/merchivra/commit/42ea8c9b4fadb6bcdb9a1446cb943c5518338e85) · [A3 #45386913964](https://github.com/kunlong-luo/merchivra/actions/runs/45386913964)（7/7 SUCCESS） · [CI #45386913906](https://github.com/kunlong-luo/merchivra/actions/runs/45386913906)（SUCCESS） | 实际 PG18 V116 两连接 ABA、rollback/reinsert、行锁、旧任务版本差异、冻结报告版本；未验证真实 Seller 或第三方签发 |
| **V231** | [SHA `9ff02147de17`](https://github.com/kunlong-luo/merchivra/commit/9ff02147de17996d28ec95875a4eaa6445e1c197) · [完整 A3 #45373462870（7/7）](https://github.com/kunlong-luo/merchivra/actions/runs/45373462870) · [快 CI #45373462897（SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45373462897) | V231 本地 Crypto 原字节比对 5 项新 Vitest、94文件461测试；Compose DOC-196 26入口/504链接 PASS；Node24/pnpm/Next/Java/PG18/Chromium 全通过。MATCH 仅自述一致不认证外部发票 |
| V230B | [SHA `a8e5eba353d5`](https://github.com/kunlong-luo/merchivra/commit/a8e5eba353d501de9761685cdd0d05de1d340982) · [完整 A3 #45368066154（7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45368066154) | 真 PG18 Job #138355672453 输出 `PASS V230`，真实 Springdoc/Orval、四类费用 1/0/2 审计、V165 本地碰撞、Owner401/404、读版本0 tick；Chromium、Java/Gradle、Next、Compose均通过 |
| V230 首轮失败 | [SHA `6baef5a3230d`](https://github.com/kunlong-luo/merchivra/commit/6baef5a3230dd799815b4dbf230ce74de55d82a3) · [A3 #45367537947（仅6/7）](https://github.com/kunlong-luo/merchivra/actions/runs/45367537947) | 真 PG18 通过，但 Next App Router DOM 重挂载使未限定表格选择器读空；下一 SHA 修复且不移除订单身份/列数断言；失败必须保留 |
| V229 | [SHA `3c857c788b6e`](https://github.com/kunlong-luo/merchivra/commit/3c857c788b6eeac5db8fa770caff14b7a253ab0a) · [A3 #45363362444（7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45363362444) | 真 PG18 1/0/2 审计负例，401/404/no-store、读版本0 tick，本地完整仍外部未核证 |
| V228C | [SHA `e268af7c9972`](https://github.com/kunlong-luo/merchivra/commit/e268af7c9972dcd5198c134187bb49879c1531c3) · [A3 #45360965067（7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45360965067) | Orval 真 Springdoc V91 Draft/Source 对齐；两次原始 6/7 检测失败保留专项说明 |
| V194/V195 历史 | [V194/V105 完整 A3 #45018945885（7/7）](https://github.com/kunlong-luo/merchivra/actions/runs/45018945885) · [V195 快 CI #45016097852（SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45016097852) | V194 财务捕获指纹/NULL 安全负例；V195 本地身份 500 呈现友好错误分类，**未**定位用户本机 HTTP500 实际服务端根因 |

V231 已在 [精确代码 SHA `9ff02147de17`](https://github.com/kunlong-luo/merchivra/commit/9ff02147de17996d28ec95875a4eaa6445e1c197) 的 [完整 A3 #45373462870（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45373462870) 和 [同 SHA 快速 CI #45373462897（SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45373462897) 独立验收，因此按原六维 [复评分 76/100](governance/project-scorecard-v231-2026-11-24.md)，仅业务完整性 +1；真实全成本与利润仍10/25。之后仅改动文档的提交另按其精确 SHA 快速 CI 检查，不继承 A3。

## 目前仍在阻断真正生产经营净利润的证据

1. **合法独立来源**：真正第三方签发主体、原始文件字节及可重复重算 SHA、合法授权/撤销、平台/银行/物流/报关/WMS 的原始行级交易键及其保密保存；浏览器本地字节与手工声明相符不满足独立签发方核验。
2. **真实成本归属及退款 COGS**：货物、FBA/FBM、进口、广告、退款和支付币种/期间分摊守恒、跨源重复扣款排除、外仓实际收件/处置及原出库批次成本冲回，在真实账单和实物上独立复核。
3. **经批准的会计与上线边界**：G1/DEC-06/07/08 的正负费用和账期确认、目标发布 TLS/KMS/PITR/RPO/RTO、真实监控告警接收及 G1/G2/G3 签署。**未满足就保持 `realizedNetProfit=null` 与 [#104 NO-GO](https://github.com/kunlong-luo/merchivra/issues/104)**。

## 本地使用和文档清理入口

- 默认 Next 本地 `pnpm dev` 只启动前端；Spring Boot 127.0.0.1:8080 与 PostgreSQL18 必须另行正常运行。HTTP401 是未登录，HTTP500/502/503/504 是服务端/代理异常，不能当作口令错误或绕过身份校验。参阅[本地会话排错](development.md)。
- 已删除无独立规则的 `Agent.md` 兼容别名和重复的 V120 旧英文待 CI 草稿；保留根 `AGENTS.md`、正式 V120 文档与历史 Git；清理原因与每个目录用途见[根中文 README](../README.zh-CN.md)、[英文 README](../README.md) 与 [文档中心](README.md)。旧版带精确 SHA 的历史 A3/财务文件和 Flyway 不属于无用文件。
- 新文档/目录一致性检查是 `python3 scripts/check_docs_consistency_v109.py`；不要重复引入 npm、Maven、PostgreSQL17、过期默认 Vite 或第二份现行计划。
