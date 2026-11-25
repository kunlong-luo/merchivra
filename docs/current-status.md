# Merchivra · 当前事实与验收状态

**CURRENT · 2026-11-25 · V237 完整 A3 7/7（含 V236 真 PG18 服务回归），V238–V240 快 CI 成功；V241 完整 A3 7/7；V242 完整 A3 7/7；V243 快 CI 通过但 PG18 步骤失败；V244 验收修复待验收。** 这里记录“实际可以做什么”和“绝对不能推断什么”，不是各次版本新闻滚动记录。历史精确 CI/PR/SHA 保留在 [Vxxx 专项证据](README.md)、[Git 历史](https://github.com/kunlong-luo/merchivra/commits/main/)和 [旧文档归档](roadmap.md)，现行任务仅看 [current-plan.md](roadmap/current-plan.md)。

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

## V232–V239 · Reports 授权证据链与目录治理

- V232 [SHA 96e6d9750993](https://github.com/kunlong-luo/merchivra/commit/96e6d97509930c88c85e31f013eb7bd483d90ce9)：Worker/Scan scope 比较阻断观察到的凭证/角色变更；[CI #45378894200](https://github.com/kunlong-luo/merchivra/actions/runs/45378894200) Java/Next SUCCESS，Compose 文档长度守卫 FAILURE，PG18 SKIPPED。
- V233 [SHA e8f2f199e24b](https://github.com/kunlong-luo/merchivra/commit/e8f2f199e24b2cdd99a3e3719510003f061dad01)：Flyway V116 grant/role 非重用 revision，绑定扫描及新报告；旧报告 NULL 且不可补写，旧任务必须 Owner 重启。[CI #45381222988](https://github.com/kunlong-luo/merchivra/actions/runs/45381222988) SUCCESS（PG18 SKIPPED）。V234 实际 PG18 两连接 ABA、rollback、删除重建、行锁及报告冻结通过完整 A3，见下表。
- V235 [SHA bbdeed878892](https://github.com/kunlong-luo/merchivra/commit/bbdeed878892db67c40dc737972235d443c60078)：授权失效、版本改变、401/403 清除游标且不自动重试，网络错误有界重试，Owner restart 锁定版本。[CI #45392374653](https://github.com/kunlong-luo/merchivra/actions/runs/45392374653) SUCCESS。
- V236 首次 [SHA 9ddce626c205](https://github.com/kunlong-luo/merchivra/commit/9ddce626c20540c66e7765a64315a02389f85df7) 的 [CI #45394392278](https://github.com/kunlong-luo/merchivra/actions/runs/45394392278) Java/Next SUCCESS，Compose 因移动运维手册的残余链接 FAILURE、PG18 SKIPPED；后续 [SHA 0988f5f2ed92](https://github.com/kunlong-luo/merchivra/commit/0988f5f2ed92022776b3d7b96b78ed85a9f7fb6e) 的 [CI #45394458129](https://github.com/kunlong-luo/merchivra/actions/runs/45394458129) 与 [A3 #45394460077](https://github.com/kunlong-luo/merchivra/actions/runs/45394460077) FAILURE（0 jobs，未取得具体拒绝诊断）。
- V237 修复旧链接与独立 JUnit 非跳过守卫；完整 A3 7/7。PG18 job #138459187403 实际输出 PASS V236 actual PG18 Java service test executed (not skipped); remote worker synthetic。真实 JdbcTemplate/事务/vault/start/step/fenced import 验证旧缺版本任务暂停、Owner restart、旧 token 清除、grant/role 变化拒绝旧入库、重新启动后新版本及摘要冻结。并非真实 Amazon 网络或所有 tenant/member/Marketplace 输入 ABA 证明。
- V238 合并 UX 文档，精确快 CI 成功；V239 将图纸索引、业务流程、ADR 模板移入已有领域并统一目录守卫。删除旧路径，不删除实际业务代码、迁移或独立验收证据；V239 快 CI 已通过（Java/Next/Compose，PG18 SKIPPED）。详见 [目录治理](../README.zh-CN.md)。

V240 归并 V181/V181B、TEST-001/TEST-003、DEP-001/V64.1；删除三份重复入口，现行 CI 策略移除重复历史流水账，精确原文由各正式入口链接。仅文档及守卫修改，未增加真实财务证据；V240 快速 CI 已通过（PG18 SKIPPED）。

V241：Reports 入库、Owner start 和 checkpoint 共用数据库资格行锁；除 V116 grant/role 外，同时锁住当前 tenant、seller_account、seller_marketplace、Marketplace verification、审核人 app_user/membership/Owner/SECURITY_REVIEWER 行。锁内不调用网络；Marketplace UUID 不匹配拒绝，合法多个 reviewer binding 归并同一个 Marketplace。扩展 V236 真 PG18 Java 回归：独立连接尝试修改五类原未锁定行必须报 55P03，释放锁后撤销 reviewer 后拒绝旧 scope 入库/重启；保留原测试，完整 A3 已通过，精确 SHA 与运行见下表。仅证明当前资格行提交期间的并发 fence，不证明锁前完成的 ABA、锁后自然过期或真实外部身份；无新 Flyway、无利润放行。

V242：原始 Reports 明细没有显式摘要币种时不得从相邻行推断缺失币种；有摘要时允许继承且拒绝混币。解析与 SHA-256 使用同一私有原字节副本。扩展解析负例与实际 PG18 入库拒绝测试，JUnit 证据守卫要求 V241/V242 完成标记，防止合成验证步骤被静默绕过。详见 [Reports 契约](finance/settlement-report-ingestion-v58.md)。快 CI 已成功；完整 A3 7/7 SUCCESS；历史记录不改写、不认证外部签发或真实利润。

V243：V216 收货费用原币归属核对不再使用仅 EXISTS 的审核判断；V91 源恰好 2 条、V112 待审恰好 1 条/终态恰好 2 条，匹配 actor 与独立性。复用既有 V113 双 RR 和 hold 状态，不改变 API、不新增 Flyway。PG18 精确 SQL 增加重复、缺失 STAGED、同人审核负例；V220 真实 HTTP 夹具补齐此前缺失的 STAGED 审计。详见 [V216 契约](finance/v216-receipt-local-cost-integrity-read-gate.md)。快速 CI 已通过；完整 A3 PG18 步骤失败，见 V244 修复；不能证明外部发票或实际 landed cost。

V244：V243 的 Python PG18 TEMP 脚本三处 SQL dollar-quote 分隔符为单个美元符号，已纠正；PG18 日志确认 SQL 语法失败。 保留原负例并扩展 V220 真 Owner HTTP：追加重复 STAGED 事件后原计划审计 hold，原币归属金额保持 40/90，其他计划碰撞状态不变，实际成本/利润 null。无业务 API、Flyway 或历史数据改写。新 SHA 完整 A3 待验收。

## 最近精确验收（切勿混用不同 SHA 的 CI）

| 阶段 | 精确代码 / 测试 | 技术证明边界 |
| --- | --- | --- |
| V243 | [SHA 7d9a29c6772b](https://github.com/kunlong-luo/merchivra/commit/7d9a29c6772bb0ada9cc64ea834276d2b65a9067) · [CI #45557483634](https://github.com/kunlong-luo/merchivra/actions/runs/45557483634)（SUCCESS） · [A3 #45557483520](https://github.com/kunlong-luo/merchivra/actions/runs/45557483520)（FAILURE） | PG18 job #139013261860 的 Start actual Spring Boot 步骤失败，V244 修复脚本分隔符 |
| V242 | [SHA 42c9112c53e8](https://github.com/kunlong-luo/merchivra/commit/42c9112c53e8b650e82b8c78dcb2da08531e1957) · [CI #45557051859](https://github.com/kunlong-luo/merchivra/actions/runs/45557051859)（SUCCESS） · [A3 #45557051970](https://github.com/kunlong-luo/merchivra/actions/runs/45557051970)（完整 A3 7/7 SUCCESS） | 原币解析及私有字节摘要；快 CI PG18 SKIPPED |
| **V241** | [SHA f31c2573e93c](https://github.com/kunlong-luo/merchivra/commit/f31c2573e93cb96a2c0369425d4620e4c1dd46fd) · [A3 #45554418444](https://github.com/kunlong-luo/merchivra/actions/runs/45554418444)（7/7 SUCCESS） · [CI #45554418547](https://github.com/kunlong-luo/merchivra/actions/runs/45554418547)（SUCCESS） | PG18 job #139002952870 的 V236 扩展 Java 服务回归非跳过成功；远程 mock，快 CI PG18 SKIPPED |
| V240 | [SHA 16551d4610f0](https://github.com/kunlong-luo/merchivra/commit/16551d4610f0691b5c5506382984b6dc3dcee914) · [CI #45553670581](https://github.com/kunlong-luo/merchivra/actions/runs/45553670581)（SUCCESS） | 领域文档归并；Java/Next/Compose 成功，PG18 SKIPPED |
| V239 | [SHA 2c2265036df3](https://github.com/kunlong-luo/merchivra/commit/2c2265036df33f0afc7269ff392eea8a5a5059e4) · [CI #45399334898](https://github.com/kunlong-luo/merchivra/actions/runs/45399334898)（SUCCESS） | 17 个领域目录与迁移守卫，快 CI PG18 SKIPPED |
| **V237** | [SHA c6ef07a3250a](https://github.com/kunlong-luo/merchivra/commit/c6ef07a3250aa52922f35ae7ee2f075083c94047) · [A3 #45398094170](https://github.com/kunlong-luo/merchivra/actions/runs/45398094170)（7/7 SUCCESS） · [CI #45398093903](https://github.com/kunlong-luo/merchivra/actions/runs/45398093903)（SUCCESS） | 真 PG18 Java 服务回归实际执行，远程 Worker mock；快 CI PG18 SKIPPED |
| V238 | [SHA 96d5d21a1c76](https://github.com/kunlong-luo/merchivra/commit/96d5d21a1c76773bdbc97cdc5d921da7c9ab8310) · [CI #45398539716](https://github.com/kunlong-luo/merchivra/actions/runs/45398539716)（SUCCESS） | 设计文档合并，Java/Next/Compose 通过，PG18 SKIPPED |
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
