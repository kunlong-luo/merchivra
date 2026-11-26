# Merchivra · 当前事实与验收状态

**CURRENT · 2026-11-25 · V237 完整 A3 7/7（含 V236 真 PG18 服务回归），V238–V240 快 CI 成功；V241 完整 A3 7/7；V242 完整 A3 7/7；V243 快 CI 通过但 PG18 步骤失败；V244 完整 A3 7/7；V245 快 CI 成功；V246 快 CI 成功；V247 快 CI 成功、完整 A3 6/7；V248 完整 A3 7/7；V249 快 CI 成功；V250 工作流未启动；V251 工作流任务已恢复启动；V252 完整 A3 7/7；V253 快 CI 成功；V254 完整 A3 7/7；V255 完整 A3 7/7；V256 A3 6/7（PG18 变量冲突）；V257 快 CI 成功；V258 完整 A3 7/7；V259 快 CI 通过；V260 完整 A3 7/7；V261/V262 完整 A3 通过；V263 快 CI 通过、A3 取消；V264 完整 A3 通过；V265/V266/V267 完整 A3 通过；V268/V269 完整 A3 通过；V270 完整 A3 通过、独立 Redis smoke 启动失败；V271 完整 A3 通过；V272 完整 A3 通过；V273 A3 6/7（PG18 失败）；V274 快 CI 通过、未触发 A3；V275 分类 SQL 通过但 A3 6/7（补偿唯一键竞争）；V276 快 CI 通过；V277 完整 A3 7/7 通过；V278 完整 A3 7/7 通过；V279 快 CI/只读 Pages 通过；V280 r2 完整 A3 7/7 通过；V281 Redis 独立验收通过；V282 快 CI 节奏检查及 Next Chromium 类型发现失败；V283 快 CI/只读 Pages 通过、Next 分类选择仍超时；V284 快 CI 通过、Chromium 发现重复报告控件；V285 单一持久工作台待验收。** 这里记录“实际可以做什么”和“绝对不能推断什么”，不是各次版本新闻滚动记录。历史精确 CI/PR/SHA 保留在 [Vxxx 专项证据](README.md)、[Git 历史](https://github.com/kunlong-luo/merchivra/commits/main/)和 [旧文档归档](roadmap.md)，现行任务仅看 [current-plan.md](roadmap/current-plan.md)。

## 核心项目状态

| 项目 | 当前可独立证实的状态 |
| --- | --- |
| 代码主线与栈 | **`main` 直接开发**；Java 25 / Spring Boot 4.1.2 / **Gradle** / PostgreSQL 18 Flyway；React 19 / **Next.js 16.4.5** 默认 / Node 24 / **pnpm 12.20.0 唯一前端包管理器**。Vite 只为明确回退或虚构 Pages |
| 独立成熟度 | **[78/100（V282 复评，V281 Redis 已验收）](governance/project-scorecard-v231-2026-11-24.md#v282-复评2026-11-26)**；V280 77；V231 76；V230 75、V229 74、V228 73、V200 70 均为历史评分 |
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

V244：V243 的 Python PG18 TEMP 脚本三处 SQL dollar-quote 分隔符为单个美元符号，已纠正；PG18 日志确认 SQL 语法失败。 保留原负例并扩展 V220 真 Owner HTTP：追加重复 STAGED 事件后原计划审计 hold，原币归属金额保持 40/90，其他计划碰撞状态不变，实际成本/利润 null。无业务 API、Flyway 或历史数据改写。完整 A3 已通过，PG18 日志确认 V243 SQL 负例与 V244 真实 HTTP hold。

V245：V29 历史 COGS 计算拒绝零候选携带成本值、重复/空 item ID、负数或超 NUMERIC(20,6) 单位价；独立调用用只读 RR 事务固定订单头与明细读取，外层事务隔离仍由调用者决定。整单不完整则 provisionalCost=null，重复或超 1000 行返回 409。未改变外部成本、实际采购批次 COGS 或利润认定。详见 [V29 契约](finance/order-cost-readiness-v29.md)。新增 Java 负例，快 CI 已通过。

V246：复用 CostAllocationEngine 增加原币批次金额区间分摊候选算法，累计六位小数向下取整差值保证同一原金额/分母上的连续区间金额守恒；拒绝重叠/重复/越界，保留缺口金额和数量。支持原范围内子区间重算及500组独立整数微单位回归。详见 [V112 领域契约](finance/v215-receipt-cost-claim-and-v92-exclusivity.md)。这是纯 Java 算术，未接应用服务/API/UI、未持久化真实区间、不保证跨调用唯一归属、未批准会计政策。快 CI 已通过。

V247：Flyway V117 追加 V106 claim 的显式本地计算区间；每 claim 唯一，冻结原 receipt/QC accepted 快照为候选分母，长度等于 assigned_quantity，同收货行锁及 READ COMMITTED 新快照检查相交区间/分母；撤销不回收，记录不可修改删除，旧 claim 不补写。未绑定区间保持 UNKNOWN，不等于物理序列/外部出库证明。复用 V203 真 PG18 TEMP 测试已安装触发器；双连接并发、应用调用和实际成本组合仍待实现/验证，不改变 V112、G1 或利润门禁。详见 [V106/V117 契约](finance/v203-sale-to-lot-link-integrity-contract.md)。快 CI 成功；完整 A3 6/7，PG18 因 old.tenant_id 别名冲突失败。

V248：Flyway V118 CREATE OR REPLACE V117 capture 函数，修复 old 与 OLD 触发器变量歧义，不改历史迁移/数据/约束。新增实际 PG18 双连接一次性共享 schema 回归，等待真实行锁、提交重叠拒绝/回滚释放/RR 拒绝。仅安装函数及合成影子表，未测试生产 FK 或并发撤销，不接业务 API、实际 COGS。完整 A3 7/7 已通过，PG18 确认 V247/V248 PASS。

V249：新增内部 ReceiptCostCompositionServiceV249，独立 RR 复用 V216 来源校验、全历史 V106/V117 与 V246，逐来源分摊 PO 参考金额、已归属收货单的头程/关税，保留来源及 claim 明细和未分配金额。跨站点、缺失/失效范围、拒收口径、缺费用、碰撞或 FX 不明均 hold；未接 HTTP、未冻结正式会计成本版本，actualBatchCogs/realizedNetProfit 恒 null。Java 回归快 CI 已通过；真实 PG18 服务集成由 V250 补充。详见 [V112 契约](finance/v215-receipt-cost-claim-and-v92-exclusivity.md)。

V250：实际 PG18 Java 服务集成回归通过真实业务表、FK、V106/V112/V117/V118 与 V165/V216/V249，合成 PO/头程/关税逐来源45/30/15、尾差及缺区间/费用、价格漂移、跨租户、撤销和跨站点 hold。CI 仅一次性库且 OAuth OFF；复用 JUnit XML 守卫禁止静默跳过。快 CI 和完整 A3 都 FAILURE 且 jobs=[]，未执行测试，尚无业务 HTTP/UI 或外部证据。详见 [V112/V249/V250 契约](finance/v215-receipt-cost-claim-and-v92-exclusivity.md)。

V251：V250 两工作流在分配 job 前失败，拒绝注释未取得。PG18 大 run scalar 接近平台限制，作为预防性修复，逐行去 YAML 缩进原样外置为 `scripts/run_pg18_full_ci.sh`，保留所有验收命令及顺序，新增一次性 CI 环境守卫和 EXIT 清理；快 CI 增 bash -n、JUnit checker 语法及关键调用守卫。[快 CI #45565714489](https://github.com/kunlong-luo/merchivra/actions/runs/45565714489) / [A3 #45565714565](https://github.com/kunlong-luo/merchivra/actions/runs/45565714565) 已恢复创建任务；本轮检查时仍运行，Java/Compose 快任务已成功。外置脚本仍遗留 job.services.postgres.id 表达式，V252 修复。

V252：外置 shell 不应包含 GitHub Actions 表达式，workflow env 传递 PG18_SERVICE_CONTAINER_ID，静态禁止残留；新增 Owner GET cost-composition-candidate，复用 V249，返回紧凑逐来源/claim 候选、未知 null，显式当前租户 Owner、no-store、禁止实际 COGS/过账。扩展 V219 真 HTTP 空候选及 401/403/404/退出测试，新增 Controller 单测；完整 A3 7/7，V250 非空真实 PG18 服务和 V252 真 HTTP HOLD/权限通过；非空 HTTP 仍未验证。详见 [V112 契约](finance/v215-receipt-cost-claim-and-v92-exclusivity.md)。

V253：复用 V228 Orval bundle 与 V217 共享收货面板，新增候选成本按需读取、Session scope/revision/cancel、Zod + BigInt 原币六位守恒/重复/空值门禁、来源和销售认领金额表及阻断文案。无新路由、写入或过账；快 CI 与完整 A3 7/7，实际 Springdoc 字段通过。V254 补非空 HTTP 与新面板专属 Chromium。详见 [V112/V253](finance/v215-receipt-cost-claim-and-v92-exclusivity.md)。

V254：复用 V250 真实 PG18 夹具新增独立合成 Owner Cookie/CSRF HTTP 非空及 HOLD/价格漂移/恢复/退出验证，JUnit 强制完成标记；扩展现有 Next Chromium 合成 fixture 显式查询、金额/认领、409 隐藏旧值、切站点 HOLD 及截图。未改变业务 API/会计规则，快 CI 通过，Chromium 专属 PASS 已核对原始日志；完整 A3 7/7，非空 HTTP JUnit marker 已核对；退款实际回转尚未实现。

V255：V249 同一 RR 内提取 V106 scope 与 V117 区间及逐来源金额，版本 SHA-256，内部 `RefundOriginalCostSliceEngineV255` 独立重算、显式退款子区间/数量/事件去重与守恒。仅内存候选观察，未保存销售时成本、未验证真实退款人口或跨调用归属；无 API/写入/正式回转，actualRefundCogsReversal/realizedNetProfit=null。单元与实际 PG18 完整 A3 7/7。详见 [原批次退款契约](finance/v212-refund-original-sale-lot-quantity-envelope.md)。

V256：Flyway V119 每原 claim 不可变保存规范 V255 payload+SHA、同 RR 内部 persistRefundBasis、数据库独立范围/原币/金额/来源检查；退款 reservation 绑定 V34 review/V21 matched refund，RC item 行锁、重叠/重复/越界及 event/item 数量守恒。V256 快 CI 成功、A3 6/7，PG18 source_amount 变量冲突失败；V258 追加修复；双连接、跨 claim 负例、读时失效、实际返仓与历史会计成本未完成，无 HTTP 写入、无正式回转。详见 [V212/V256](finance/v212-refund-original-sale-lot-quantity-envelope.md)。

V257：V249 同 RR 读取 V119 预留及 V34/V21 当前状态、同 event/item peer 数量和认领状态，重算原候选指纹，HOLD/null 不释放历史；真实 JDBC 双连接回滚及跨认领提交竞争回归，待新 A3。无 HTTP、完整 peer 成本复算、ABA 防护或实际过账。详见 [V212/V257](finance/v212-refund-original-sale-lot-quantity-envelope.md)。

V258：V256 A3 原日志 `column reference "source_amount" is ambiguous`，追加 V120 修复 V119 触发器变量名、保留历史。V249 单 RR 全订单原币成本候选汇总复用全部 V257 claim/指纹及本地 V34/V21 事件，原销售数量和每 event/item reservation 覆盖必须精确完整，任何缺口整单 HOLD；实际 PG18 V250 夹具新增测试与 JUnit marker，待新 A3。无 HTTP/UI、ABA 防护、真实返仓或实际过账。详见 [V212/V258](finance/v212-refund-original-sale-lot-quantity-envelope.md)。

## 最近精确验收（切勿混用不同 SHA 的 CI）

| 阶段 | 精确代码 / 测试 | 技术证明边界 |
| --- | --- | --- |
| V257 | [SHA 566843cb2fab](https://github.com/kunlong-luo/merchivra/commit/566843cb2fab6fc3cb40aac422682436f06d8130) · [CI #45570223126](https://github.com/kunlong-luo/merchivra/actions/runs/45570223126)（SUCCESS） · [A3 #45570223235](https://github.com/kunlong-luo/merchivra/actions/runs/45570223235) | 快 CI 通过，完整 A3 尚未确认 |
| V256 | [SHA 2a62363fd14b](https://github.com/kunlong-luo/merchivra/commit/2a62363fd14bb0db0971727a344243f26004f1da) · [CI #45569699760](https://github.com/kunlong-luo/merchivra/actions/runs/45569699760) · [A3 #45569699719](https://github.com/kunlong-luo/merchivra/actions/runs/45569699719)（6/7 FAILURE） | 快 CI SUCCESS；PG18 job #139053562401 source_amount ambiguous，V258 修复 |
| V255 | [SHA 859287e1b34f](https://github.com/kunlong-luo/merchivra/commit/859287e1b34fcc3dc237b141c4dd5980d86ed0c5) · [CI #45569211952](https://github.com/kunlong-luo/merchivra/actions/runs/45569211952) · [A3 #45569211987](https://github.com/kunlong-luo/merchivra/actions/runs/45569211987) | 快 CI SUCCESS；A3 7/7 SUCCESS；PG18 job #139051534636 PASS V255 |
| V254 | [SHA 4c115528fafd](https://github.com/kunlong-luo/merchivra/commit/4c115528fafd52132f47b3c40409b5049915ed8c) · [CI #45568637219](https://github.com/kunlong-luo/merchivra/actions/runs/45568637219)（SUCCESS） · [A3 #45568637200](https://github.com/kunlong-luo/merchivra/actions/runs/45568637200) | A3 7/7 SUCCESS；Chromium job #139049675072 PASS V254 Next Chromium candidate；PG18 job #139049675782 PASS V254 actual nonempty Owner HTTP JUnit marker |
| V253 | [SHA 3d1a41218b22](https://github.com/kunlong-luo/merchivra/commit/3d1a41218b229c63079e534117d7cc1e670718f8) · [CI #45568099720](https://github.com/kunlong-luo/merchivra/actions/runs/45568099720)（SUCCESS） · [A3 #45568099866](https://github.com/kunlong-luo/merchivra/actions/runs/45568099866) | A3 7/7 SUCCESS；PG18 job #139047797199 PASS V253 actual Springdoc |
| **V252** | [SHA 65128ba2eb93](https://github.com/kunlong-luo/merchivra/commit/65128ba2eb938574d600ee281b83301603b16fd2) · [CI #45566116953](https://github.com/kunlong-luo/merchivra/actions/runs/45566116953)（SUCCESS） · [A3 #45566116963](https://github.com/kunlong-luo/merchivra/actions/runs/45566116963)（7/7 SUCCESS） | PG18 job #139041361728：PASS V250 实际非空 Java 组合与 PASS V252 Owner HTTP HOLD/权限 |
| V251 | [SHA 38ebd6fcfdee](https://github.com/kunlong-luo/merchivra/commit/38ebd6fcfdee20e9868cb9e40ab707e7d502442a) · [CI #45565714489](https://github.com/kunlong-luo/merchivra/actions/runs/45565714489) · [A3 #45565714565](https://github.com/kunlong-luo/merchivra/actions/runs/45565714565) | 快 CI SUCCESS，A3 FAILURE（PG18 bad substitution）；V252 修复 shell 上下文表达式 |
| V250 | [SHA 201957f5da48](https://github.com/kunlong-luo/merchivra/commit/201957f5da4836a3b64067a6486f11d933c69ad8) · [CI #45564608797](https://github.com/kunlong-luo/merchivra/actions/runs/45564608797)（FAILURE） · [A3 #45564610271](https://github.com/kunlong-luo/merchivra/actions/runs/45564610271)（FAILURE） | 两者 jobs=[]，未执行；拒绝原因尚无注释证据，V251 外置脚本 |
| V249 | [SHA 9a41dc9b7613](https://github.com/kunlong-luo/merchivra/commit/9a41dc9b76139583ac77007720a21ed624eaab21) · [CI #45563638010](https://github.com/kunlong-luo/merchivra/actions/runs/45563638010)（SUCCESS） | Java/React/Compose 成功，PG18 SKIPPED，无独立完整 A3 |
| **V248** | [SHA 95d8ebd634fd](https://github.com/kunlong-luo/merchivra/commit/95d8ebd634fdf4997481c824023dbfdf405f9eb2) · [CI #45562454305](https://github.com/kunlong-luo/merchivra/actions/runs/45562454305)（SUCCESS） · [A3 #45562454277](https://github.com/kunlong-luo/merchivra/actions/runs/45562454277)（7/7 SUCCESS） | PG18 job #139029197065：PASS V247 installed range trigger；PASS V248 actual PG18 V118 range race |
| V247 | [SHA 82dcc54c9c0a](https://github.com/kunlong-luo/merchivra/commit/82dcc54c9c0a18327f1ee3d8a3e2cd2d3e7f3810) · [CI #45561759237](https://github.com/kunlong-luo/merchivra/actions/runs/45561759237)（SUCCESS） · [A3 #45561759490](https://github.com/kunlong-luo/merchivra/actions/runs/45561759490)（6/7 FAILURE） | PG18 job #139026923921：column reference old.tenant_id is ambiguous；V248 追加迁移修复 |
| V246 | [SHA 8ab8a93a53b3](https://github.com/kunlong-luo/merchivra/commit/8ab8a93a53b348a7a329e0292b992007bd426efb) · [CI #45560785767](https://github.com/kunlong-luo/merchivra/actions/runs/45560785767)（SUCCESS） | Java/React/Compose 成功，PG18 SKIPPED，无独立完整 A3 |
| V245 | [SHA 8a40eb91562e](https://github.com/kunlong-luo/merchivra/commit/8a40eb91562ec0ab19707a641285f4296ec1baca) · [CI #45559136844](https://github.com/kunlong-luo/merchivra/actions/runs/45559136844)（SUCCESS） | Java/React/Compose 成功，PG18 SKIPPED，无独立完整 A3 |
| **V244** | [SHA 3ec2c68b44d7](https://github.com/kunlong-luo/merchivra/commit/3ec2c68b44d7290688f25db83aa2b4dc45f02056) · [CI #45557992334](https://github.com/kunlong-luo/merchivra/actions/runs/45557992334)（SUCCESS） · [A3 #45557992299](https://github.com/kunlong-luo/merchivra/actions/runs/45557992299)（7/7 SUCCESS） | PG18 job #139014636905：PASS V243 PG18 exact receipt audit cardinality, missing stage and self-review；PASS V244 real Owner HTTP duplicate source audit holds original receipt cost |
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


V259：在 V258 同一 RR 内读取全部相关 V35 分摊（上限1000），逐 event/item 与 V34 审核严格匹配；复用 RefundAmountAllocationEngine 比对 LIVE 原始 PRODUCT_REFUND 符号、原币及合计，缺行、部分金额或不一致整单 HOLD、currencies 空。扩展实际 PG18 V250 夹具补齐 V35 合成行，负例为部分金额和缺分摊、恢复后候选一致。仅原始事件金额与本地 V35 核对，不证明 V22 分类、V63 独立复核或实际外部退款/返仓；CI/A3 未核对，无正式回转，realizedNetProfit=null。见[既有退款契约](finance/v212-refund-original-sale-lot-quantity-envelope.md)。


V258 验收回填：[代码 SHA 97ee40d99c1d](https://github.com/kunlong-luo/merchivra/commit/97ee40d99c1dec7f1bc9f2118fa1e8a7219c18e3)、[快 CI #45570749711 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45570749711)、[完整 A3 #45570749671 SUCCESS 7/7](https://github.com/kunlong-luo/merchivra/actions/runs/45570749671)，PG18 job #139057514459 成功。V259 [代码 SHA 3610ac70bae6](https://github.com/kunlong-luo/merchivra/commit/3610ac70bae682070cb2cd249db73ed185cce63c) [快 CI #45571409601 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45571409601)，该 SHA 未见完整 A3，不能把 V258 结果用于 V259。


V260：全单候选在同一独立 RR 中复用 V203 preview（V29 历史成本和销售时 SKU 映射、V106 批次完整性），逐 claim 对齐 ID、订单行、原 receipt、数量、effectiveState；缺历史成本、映射重定向或任一 claim 失效则整单 HOLD，金额为空。底层 scope/conflict 异常仍失败封闭，不在参与事务内部吞掉异常。扩展 V250 真实 PG18 合成回归：缺映射 hold，补历史成本及映射后恢复候选，改到另一个同价有效 SKU hold，恢复原映射后候选恢复；保留 V259 金额负例并要求新 JUnit marker。V260 完整 A3 待验收；该门禁不证明销售时实际 COGS 或 ABA，不包含 V63/外部物理返仓/G1，实际回转与利润仍 null。无新迁移/API/目录。


V260 验收回填：[代码 SHA f0b020b80aba](https://github.com/kunlong-luo/merchivra/commit/f0b020b80abada92da7faed7c2f3a7e013070cfd)、[快 CI #45572837644 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45572837644)、[完整 A3 #45572837650 SUCCESS 7/7](https://github.com/kunlong-luo/merchivra/actions/runs/45572837650)，PG18 job #139063491336 成功（包含 V259 金额回归）。

V261：单 RR 全单候选在 V259 金额、V260 SKU 和 V119 预留完整后，复用 V63 preview 重算证据 SHA 与 V197 source parity；每订单最多100个 V63 事件，要求 REVIEWED/current、原币一致和完整本地金额。V63 同 revision 精确请求/决策审计二条，actor/digest/time 匹配，审核人不得为当前 V34/V35 作者。缺审、待审、变更、重复审计或同人冲突整单 HOLD，参与事务的业务异常仍失败封闭。V250 实际 PG18 夹具扩展真实 V63 request/decide、未审/待审、V35 revision 改变、同作者、重复审计及新审核恢复；JUnit marker 强制，待新 SHA A3。尚无跨修订完整审计/不可重用版本、ABA、现行 Owner 资格重核、真实返仓或外部签发证明。旧审计不能代替 G1，不实际过账，realizedNetProfit=null。无新迁移或 API。


V262：追加 Flyway V121，V34/V35 每行增加数据库全局序列不可重用版本；任何实际 UPDATE/INSERT（含恢复原值、删除重建、伪造旧版本）重分配，完全 no-op 保持，回滚不改变已提交版本。V63 证据摘要显式版本化并绑定同 RR 的两组有界行版本，旧 V63 指纹不能自动升级，必须重新发起并独立审批。V250 真实 PG18 夹具增加金额/作者/数量 ABA、撤回重建（携旧版本）、no-op、rollback 和复审恢复，JUnit marker 强制；V261 既有审计负例保留。仅覆盖 V34/V35 行生命周期，不涵盖 SKU/成本/源分类/订单/授权 ABA、管理员篡改序列或 V119 预留版本；不改历史迁移或原金额，不增加 API/UI。V261 CI/A3 尚未最终核对，V262 待独立 A3。真实返仓与外部来源/G1 缺失，实际回转与 realizedNetProfit=null。


V261 [代码 e76e520fe904](https://github.com/kunlong-luo/merchivra/commit/e76e520fe904ec8b2e630b83447eef5a1196f151) [快 CI #45576590997 CANCELLED](https://github.com/kunlong-luo/merchivra/actions/runs/45576590997)，[A3 #45576590955](https://github.com/kunlong-luo/merchivra/actions/runs/45576590955) 核对时仍运行，6 个 job 成功、PG18 未结束。V262 [代码 40071cb9a80d](https://github.com/kunlong-luo/merchivra/commit/40071cb9a80d2f0cdc37ccba92280a427d4f6f30) [快 CI #45576841371 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45576841371)，[A3 #45576841081](https://github.com/kunlong-luo/merchivra/actions/runs/45576841081) 待最终核对。

V263：Flyway V122 追加 V119 不可变预留的 V34 evidence_revision_v121 捕获列。V119 原有 READ COMMITTED 订单行锁与源/审核行共享锁继续有效，按触发器名字顺序在既有捕获后核实身份并覆盖调用者版本；同 event/item 旧/不同版本 peer 阻断新预留。读取同 RR 比较捕获版本和当前 V34 版本及 peer，旧 NULL 一律 HOLD、不回填、不释放区间。V34 ABA 即使 V63 新复审也不能恢复原预留；V35 更新仍由 V63 重审处理，不绑定到早于 V35 的 V119 捕获。V250 PG18 增加版本匹配和永久 HOLD 回归，保留其他正例与竞争测试；新 marker 强制，待 A3。无撤销/替换协议，历史预留可能需后续经批准的补偿契约；不覆盖源/SKU/成本/授权 ABA，现行 Owner 资格待补，真实外部原件/返仓/G1 缺失。无新 API/UI、旧迁移不改，actualRefundCogsReversal/realizedNetProfit=null。


V261 [代码 e76e520fe904](https://github.com/kunlong-luo/merchivra/commit/e76e520fe904ec8b2e630b83447eef5a1196f151) [A3 #45576590955 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45576590955) 已完成；同 SHA 快 CI 取消。V263 [代码 4f1fcc7f999a](https://github.com/kunlong-luo/merchivra/commit/4f1fcc7f999aeb4d22a60ec53600d95efa8b3531) [快 CI #45577315150 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45577315150)，[A3 #45577315191](https://github.com/kunlong-luo/merchivra/actions/runs/45577315191) 尚未完成；V262 A3 尚未确认。

V264：V63 request/decide 复用 TenantAccessService 同等 active tenant/user/membership + 全局 OWNER 资格条件；decide 重核请求人当前资格与 request 后 OWNER_REVOKED 审计，历史失效 pending 可重新发起；全订单 read 在原 RR 内同时核查两位 Owner 与各自 request/decision 之后的撤销记录。V250 PG18 合成夹具扩展非 Owner、禁用用户、撤销角色、仅 seller-scoped Owner、membership revoke、待审请求人撤销后恢复及重新审核，强制 JUnit marker，待 A3。没有新 API/UI/迁移；不是全域授权版本或并发撤销线性化保证，未审计的直接数据库 ABA 尚未封闭。V119 stale/legacy 预留继续不可释放，缺乏经批准的补偿规则不开放替换/释放，下一步须明确补偿权限、幂等、不可重复归属及原始区间守恒后再实施；真实外部原件/返仓/G1 缺失，实际回转/realizedNetProfit=null。


V262 [代码 40071cb9a80d](https://github.com/kunlong-luo/merchivra/commit/40071cb9a80d2f0cdc37ccba92280a427d4f6f30) [A3 #45576841081 SUCCESS 7/7](https://github.com/kunlong-luo/merchivra/actions/runs/45576841081)，PG18 #139078036767 成功。V263 [A3 #45577315191 CANCELLED](https://github.com/kunlong-luo/merchivra/actions/runs/45577315191)，快 CI 成功，不记为完整通过。V264 [代码 9802fad16e46](https://github.com/kunlong-luo/merchivra/commit/9802fad16e466e4d95f02837b587a70cc8e8b81a) [快 CI #45577963575 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45577963575)，[A3 #45577963556](https://github.com/kunlong-luo/merchivra/actions/runs/45577963556) 待最终核对。

V265：内部 RR 同区间补偿预检，限定单 tenant/marketplace/event/item、最多50旧 claim/20 receipt；必须完整覆盖该 item 当前 V34 数量、同一已失效非 NULL 旧版本、旧数量不变、当前 V63 独立审核及原币/订单一致、V203 和 V249/V255 原批次成本指纹仍一致。逐原区间重算原币候选，提案 SHA 包含原身份/区间/指纹/旧与新数量版本/当前 V63 revision。相同快照输出确定性提案，复审 revision 变化生成新 ID；不是写入幂等键或审批。现行预留、历史 NULL、数量改变、来源或成本冲突不生成此类提案。V250 PG18 回归覆盖 current hold、复审前 hold、复审后同坐标 dry run、跨租户、重复读和新审核 revision；新 marker 强制，待 A3。没有迁移/HTTP/UI、没有写入提案/撤销/释放/替换、没有整单补偿激活或实际过账。旧预留依旧 HOLD 并占用区间；真实外部材料/G1 未签，actualRefundCogsReversal/realizedNetProfit=null。后续审批、持久化幂等、全单守恒、并发复核和激活必须另行验收。


V264 [A3 #45577963556 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45577963556) 已完成；V265 [代码 9b8218f9064c](https://github.com/kunlong-luo/merchivra/commit/9b8218f9064c70ac340ba03f06b5ef9c859b5772) [快 CI #45578849046 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45578849046)，[A3 #45578849118](https://github.com/kunlong-luo/merchivra/actions/runs/45578849118) 核对时运行中。

V266：追加 Flyway V123 不可变同区间补偿提案及单独批准表；主键 tenant/proposal SHA，原 V265 canonical payload 独立 SQL SHA256 核对，另存 V63 revision/digest、scope、actor、理由及时间。内部 request/approve 各在独立 RR 重算 V265，校验 expected SHA、当前 Owner，审批人与提案人、V63 两位审核者及当前 V34/V35 作者均不同，已记录的请求人撤销阻断。相同当前快照、同人同理由的重复提交/批准复用记录；冲突理由/actor/过期摘要拒绝；数据库唯一键阻止重复行，RR 并发冲突可能返回 serialization failure，调用者须完整重试，尚无两连接并发回归。V250 PG18 加入真实持久化、重复、独立性、不可变 UPDATE/DELETE、复审过期与无激活场景，强制 JUnit marker，待 A3。批准是历史本地证据，不是激活令牌；不读取为有效预留，不修改/释放 V119，不支持整单补偿守恒或跨期记账，不开放 HTTP/UI。未经 G1/DEC 批准、外部真实材料、授权连续性/并发验收前保持 actualRefundCogsReversal/realizedNetProfit=null。


V266 [代码 d39c2e79c15f](https://github.com/kunlong-luo/merchivra/commit/d39c2e79c15fcb4ed185473172f9b114611f4d71) [快 CI #45579559372 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45579559372) [完整 A3 #45579559266 SUCCESS 7/7](https://github.com/kunlong-luo/merchivra/actions/runs/45579559266)，实际 PG18 job #139085964217 成功。

V267：同 RR 独立预检已记录的 V123 补偿批准，复算 V265/V63、核对当前 proposal SHA、payload、review revision/digest、双方 Owner、审批人与 V63/当前 V34/V35 作者独立、批准时间不早于请求、请求/批准后 OWNER_REVOKED 记录。record/approve 幂等重读复用同一门禁；撤销后恢复资格不能复活原批准，重新 V63 复核产生新的提案与批准，保留原记录。V250 实际 PG18 新增未批准、正常重复读、跨租户、账号禁用、审核人撤销恢复、复审新 ID 场景，强制 marker；待新 A3。无新迁移/API/UI、无激活令牌或实际释放、替换。不是全域授权不可重用版本或并发撤销线性化；V266 RR 并发幂等仍需两连接回归、整单补偿守恒及 G1/DEC 授权。旧预留继续 HOLD，actualRefundCogsReversal/realizedNetProfit=null。


V265 [代码 9b8218f9064c](https://github.com/kunlong-luo/merchivra/commit/9b8218f9064c70ac340ba03f06b5ef9c859b5772) [快 CI #45578849046 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45578849046) [A3 #45578849118 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45578849118)。V267 [代码 f6ad63c11e52](https://github.com/kunlong-luo/merchivra/commit/f6ad63c11e52719432e6516574b34618adb83357) [快 CI #45583165883 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45583165883) [A3 #45583165906 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45583165906)。

V268：V123 请求/批准独立 REQUIRES_NEW RR 写事务仅对 PostgreSQL SQLSTATE 40001 完整重试，最多3次总尝试，每次重新计算当前 V265/V63、角色与撤销状态、原指纹及幂等 actor/reason；耗尽返回409，业务冲突/权限/其他 SQL 异常不重试。不降低隔离级别，不重复记录；无激活/释放/替换。V250 PG18 夹具使用独立 gate 连接 SHARE table lock 强制两真实 service RR insert 等待，pg_blocking_pids 证实并发，同请求/同批准返回同 receipt、不同批准理由只一成功一409，并检查行数；新 JUnit marker 强制，待 A3。此为本地合成并发契约，不是全域授权线性化或补偿全单守恒、正式会计批准。后续需全单只读补偿组合与跨事件/批次守恒，E1/G1 外部真实材料优先，actualRefundCogsReversal/realizedNetProfit=null。


V268 [代码 bfbc124ca446](https://github.com/kunlong-luo/merchivra/commit/bfbc124ca446ce63b3b8fb0afac25baf1e1f1ec0) [快 CI #45586332715 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45586332715)，[A3 #45586332750](https://github.com/kunlong-luo/merchivra/actions/runs/45586332750) 核对时未结束。

V269：内部显式选择最多20个已批准 event/item/proposal SHA，在单 RR 复用 V258–V267 整单人口/原币/历史 SKU/原成本及审核校验；V123 当前批准复核后仅对精确 stale claim/event 原坐标模拟补偿，保留其余 V119 通用条件、旧 peer 一致、原成本 digest、原区间与逐事件金额，必须逐一消费所有选定坐标、整单 event/item 数量守恒、原币成本守恒。普通读取仍无选择、stale HOLD；模拟结果单独标识 LOCAL_ORDER_COMPENSATION_SIMULATION_UNVERIFIED、COMPENSATION_NOT_ACTIVATED，未批准、缺覆盖、重复/过期/跨租户或其他证据失效不得产生完整候选，部分业务异常仍以403/404/409传播。V250 PG18 夹具两 claim 两事件，旧 A 已批准补偿 + 当前 B，正例与旧指纹/重复/跨租户/另一个 stale 负例，强制 marker，待 A3。没有写入、释放、替换、激活、公开 API/UI 或正式会计回转；未新增迁移。不是实际外部完整性、全域授权 ABA 或跨期会计批准，actualRefundCogsReversal/realizedNetProfit=null。


V268 [代码 bfbc124ca446](https://github.com/kunlong-luo/merchivra/commit/bfbc124ca446ce63b3b8fb0afac25baf1e1f1ec0) [完整 A3 #45586332750 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45586332750)。V269 [代码 1f1240d93a17](https://github.com/kunlong-luo/merchivra/commit/1f1240d93a171199c81a85fbb99e2748b3effb8c) [快 CI #45587205802 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45587205802) [完整 A3 #45587205992 SUCCESS 7/7](https://github.com/kunlong-luo/merchivra/actions/runs/45587205992)，实际 PG18 job #139111767787 成功。

V270：复用 SettlementReportParser 与 BankPayoutCsv，新增 operator-local Gradle checkLocalSettlementSample（无 Spring 启动、数据库、网络调用、上传、持久化），读取合法本地单原报告原字节（含 gzip）与单条标准化银行 CSV，精确 Settlement ID、原币、声明净额/行合计、贷记+扣留费用与声明净额比较，差额不容忍隐式舍入；缺净额、混币/FX、多个银行条目、缺关联、非正净额、格式/长度错误阻断。默认 stdout 固定状态/false/null 不含路径、ID、金额、币种或哈希；本地显式 private details 才显示两文件字节 SHA、原币和差额，不得在 CI/Git/聊天公开；normalized bank hash 不是银行原始文件证明。Java JUnit 合成临时夹具覆盖正/负、gzip 与隐私；待 A3。仅 E1/E4 首笔材料算术准备，不包含真实 Finances 交易行完整性、来源签发、真实到账、整期覆盖、G1/DEC；尚无真实材料，真实验证 BLOCKED，realizedNetProfit=null，Seller/Ads OAuth OFF，#104 NO-GO。


V270 [代码 89edb8f76b98](https://github.com/kunlong-luo/merchivra/commit/89edb8f76b983c170430740b7fccce9215f62fae) [快 CI #45588988874 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45588988874) [A3 #45588988944 SUCCESS 7/7](https://github.com/kunlong-luo/merchivra/actions/runs/45588988944)，PG18 job #139118070723 成功；另 [Redis 8 Session smoke #45588988904 FAILURE](https://github.com/kunlong-luo/merchivra/actions/runs/45588988904)，job #139117867603 的容器启动步骤失败，未确认具体原因、未修复，不视为通过。

V271：在 V198 同 SQL 快照比较中对当前 tenant/marketplace/report 的原币报告行及精确 settlement ID LIVE Finances 交易按原始 external_order_id 分组，NULL 单独分组、不得猜测归属。总额一致但任一分组缺一侧（含零金额）或原币净额不同，返回 ORDER_REFERENCE_AMOUNT_DIFFERENCE，旧 V56 只允许 SOURCE_AMOUNTS_AGREE_UNVERIFIED 的批准规则继续阻断；保留原状态优先级。V56 指纹升级 V271（旧审核需重审，不回填），包含报告实际明细、声明净额、Finances 原订单参考/类别/source_type/UTC epoch；状态读取 RR，未宣称全域并发或 ABA 证明。Next 原工作台及 Zod 接受新阻断状态；扩展 V198 真 PG18 TEMP/ROLLBACK 原 SQL、Java/Vitest 反例，待 A3。属于订单净额分组一致性门禁，不等同逐行匹配或证明全部费用、非订单费用、实际到账、FX/COGS/会计签署；真实 E1–E5 未完成，realizedNetProfit=null、Seller/Ads OAuth OFF、#104 NO-GO。


V271 [代码 6a17618dfbd2](https://github.com/kunlong-luo/merchivra/commit/6a17618dfbd2156c9b0b8f36b076e9ddbe423ad9) [快 CI #45611812174 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45611812174) [完整 A3 #45611813480 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45611813480)，[只读 Pages #45611811764 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45611811764)。

V272：现有 V198/V271 比较单 SQL 快照对每笔精确 Settlement ID、同 tenant/marketplace/currency LIVE Finances 事件核验 1–2000 个末级明细、非 NULL 金额/原币一致、末级净额等于事件净额；父节点不计入金额。缺口 FINANCES_LEAF_COVERAGE_INCOMPLETE，覆盖后按原始订单参考（NULL 单独）+金额正负方向核对 Reports 明细与 Finances leaf 合计，差异 ORDER_DIRECTION_AMOUNT_DIFFERENCE。零额无正负方向，仍由 V271 分组覆盖约束。保留旧阻断优先级、V56 审批仅允许 SOURCE_AMOUNTS_AGREE_UNVERIFIED。V272 指纹含当前全部拆分节点的 path/parent/type/amount/currency/is_leaf，旧指纹失效，未回填；前端 Zod 和提示同步；Java/Vitest 及 V198 真 PG18 TEMP 回归待 A3。此为金额方向守恒，不把正数一律认定商品收入、负数一律认定佣金，不凭名称猜测跨来源费用分类；同方向类别抵消、逐行身份、item/SKU 归属、实际原件与会计批准仍未解决。缺历史拆分的审核会阻断，须合法重新获取完整来源。独立 Redis smoke V270 失败具体原因未核实，不改测试或宣称修复。realizedNetProfit=null、OAuth OFF、#104 NO-GO。


V272 [代码 75abfb7b8993](https://github.com/kunlong-luo/merchivra/commit/75abfb7b8993d2c47042504c82d15d8499df02cf) [快 CI #45615522059 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45615522059)，[只读 Pages #45615522250 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45615522250)，[完整 A3 #45615522448](https://github.com/kunlong-luo/merchivra/actions/runs/45615522448) 核对时仍运行中。

V273：新 POST category-comparison-preview（Owner Session/CSRF/no-store，只读 RR），复用 V272 全源比较及现有 V28 ACTIVE Finances leaf source_type 分类，Reports 显式输入完整 transaction_type/amount_type/amount_description 三元组映射（最多200，不预设 Amazon 对应关系）。按原始 order_ref（NULL 单独）+V28 category+金额方向（含零）独立比较，任一缺映射、UNCLASSIFIED、未使用映射或超限（30000行）不给类别总额；完全映射后报告冲突分组数和原币分类双方合计，不暴露订单编号。相同方向类别抵消可识别，但 V28 现有五类仍粗粒度，同一类内部佣金/其他子费用不能区分。映射仅为本次请求假设，不持久化、不写入 V28、没有独立批准/版本审计或接入 V56 审批门禁；mappingApproved/bankVerified/ledgerPosted=false，realizedNetProfit=null。未新增迁移、前端 UI 或第三方依赖。JUnit 合成金额反例及既有 V198 真 PG18 TEMP SQL 扩展验证站点/租户、当前 ACTIVE、末级和未映射，待 A3；真实 HTTP 授权和真实财务样本未验收。V270 Redis smoke 失败仍未修复。


V273 [代码 9955a7b93751](https://github.com/kunlong-luo/merchivra/commit/9955a7b93751e34d8589bed31deb1e2cfd4cc6e9) [快 CI #45616101485 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45616101485)，[A3 #45616101623](https://github.com/kunlong-luo/merchivra/actions/runs/45616101623) 核对时尚未开始；[V272 A3 #45615522448](https://github.com/kunlong-luo/merchivra/actions/runs/45615522448) 核对时运行中。

V274：Flyway V124 报告范围不可变分类映射草稿（tenant/report/content SHA-256 主键，报告与创建者复合外键、内容哈希 DB CHECK、复用不可变 UPDATE/DELETE trigger）；复用 V273 验证器，三元组 NULL 显式编码、按内容排序，提交顺序不改变身份，修改生成新 SHA，相同内容 ON CONFLICT DO NOTHING 幂等保存。读取强制当前 ACTIVE tenant、marketplace/report，重新校验 SHA、规范编码及类型映射；保存不隐含当前证据一致。保存的映射预检在 RR 重读 V272/V273 和当前 V28 规则，旧草稿不缓存财务结果。新增三个 Owner Session/CSRF（写）/no-store API；无自动映射、独立批准、签署、细分类别扩展、V56 门禁或 UI，mappingApproved/bankVerified/ledgerPosted=false，realizedNetProfit=null。JUnit 覆盖规范化及范围 SQL，扩展既有 V198 PG18 TEMP 测试执行 V124 DDL 的临时表副本及不可变 trigger；实际 Flyway 与新 HTTP 行为仍待 A3/后续端到端验证。本轮暂不修改现有 A3 workflow 定义，避免取消运行中的 V272/V273 验收，V274 自身完整 A3 需后续明确触发。Redis smoke V270 启动失败尚未修复。真实 E1–E5 未完成，Seller/Ads OAuth OFF，#104 NO-GO。


V272 [完整 A3 #45615522448 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45615522448)。V273 [完整 A3 #45616101623 FAILURE 6/7](https://github.com/kunlong-luo/merchivra/actions/runs/45616101623)，PG18 job #139213034617 的综合脚本步骤失败，日志接口不可读取；静态核查发现 V273/V274 新增 DO $ BEGIN / END $; 非法分隔符，已改为具名 dollar quote，不宣称为唯一失败根因。V274 [代码 dc203b85c4f2](https://github.com/kunlong-luo/merchivra/commit/dc203b85c4f216069c28ca4f8f66af004ae26598) [快 CI #45616598969 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45616598969)，未触发完整 A3。

V275：修复 V273/V274 PG18 TEMP SQL DO 分隔符，V124 回归改为直接提取真实 Java SAVE_MAPPING_SQL/READ_MAPPING_SQL，验证内容 SHA、重复提交仅一条、错误 Marketplace 无结果、无变化 UPDATE 与 DELETE 必须触发 V106 不可变拒绝，而非把任意异常当成功。quick CI 增加该 Python 文件语法检查（不能替代 PG18 执行）。V274 保存映射预检仅在 LOCAL_CATEGORY_AMOUNTS_AGREE_UNVERIFIED 时新增 evidenceSha256，绑定 tenant/marketplace/report UUID、mapping SHA、V56/V272 源证据指纹与当前实际使用 V28 ACTIVE 规则 ID/type/category/revision/state 指纹；全在 RR 事务，同内容稳定、正常规则修订变更使证据失效，非相关规则不参与。缺覆盖/冲突不返回证据摘要。新增 Java digest 反例及真 PG18 规则修订与范围测试，触发完整 A3 待验收。不持久化证据、不建立独立审批、不改变 V56 门禁、UI、源事实或利润；不是签章、授权令牌或全域 ABA 防护。真实 HTTP、Redis smoke V270 失败、外部 E1–E5 与 G1/DEC 仍待完成。mappingApproved=false、realizedNetProfit=null、OAuth OFF、#104 NO-GO。


V275 [代码 4a8af88ffb2e](https://github.com/kunlong-luo/merchivra/commit/4a8af88ffb2e11d69795a32f16a7c3e475ab46e6) [快 CI #45618142971 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45618142971)，[完整 A3 #45618143096](https://github.com/kunlong-luo/merchivra/actions/runs/45618143096) 核对时 PG18 与 HTTP job 尚未结束。

V276：Flyway V125 新增内部不可变分类申请/批准两表，精确绑定 tenant/report/mapping SHA/evidence SHA，外键绑定草稿、申请及 membership，申请/批准分别唯一且 UPDATE/DELETE 拒绝。SettlementCategoryReviewV276 在 RR 内调用 V275 当前预检，只有一致且 evidence SHA 完全匹配才接受；复用现有 active 全租户 Owner 校验，审批人与申请人、原映射创建人均不同；读取及重放重查双方资格、审批时间与 OWNER_REVOKED 审计。相同 actor/reason 可幂等复用，冲突拒绝；并发 RR 序列化失败未做自动重试。原证据撤销后同 SHA 不可重新申请（后续需设计重开与授权代际）；非全域 ABA/撤销线性化保证。仅内部服务，无 controller/UI、无 V56 门禁或正式激活；mappingActivated/bankVerified/ledgerPosted=false、realizedNetProfit=null。新增 JUnit 过期、独立性、缺请求与 SQL 契约，扩展 V198 真 PG18 TEMP 精确 V125 DDL、申请/批准 SQL、重复、self approval、站点隔离、不可变触发器；实际迁移与服务端到端未验收。为保留 V275 正在进行的完整验收，本轮不修改 A3 workflow；待 V275 结果明确后另行触发 V276 A3。真实 E1–E5、G1/DEC、Redis smoke 启动问题仍阻塞；Seller/Ads OAuth OFF、#104 NO-GO。


V275 [完整 A3 #45618143096 FAILURE 6/7](https://github.com/kunlong-luo/merchivra/actions/runs/45618143096)，[PG18 job #139218878361](https://github.com/kunlong-luo/merchivra/actions/runs/45618143096/job/139218878361) 完整日志已核实：V198/V271/V273/V274/V275 SQL marker 均通过，V250 Java 并发测试第 612/713 行 DuplicateKeyException，DB 记录 finance_refund_compensation_p_tenant_id_proposal_sha256_req_key 唯一冲突，V123 INSERT 仅指定 PK 仲裁目标。HTTP、Java 常规、Next、Chromium、Compose、基线其余6项成功。V276 [代码 9ffb348a325e](https://github.com/kunlong-luo/merchivra/commit/9ffb348a325e98638956ceef315f36c79641c514) [快 CI #45618870958](https://github.com/kunlong-luo/merchivra/actions/runs/45618870958) 核对时运行中，未独立触发 A3。

V277：修复 V123 请求双唯一约束仲裁漏洞，INSERT 使用无目标 ON CONFLICT DO NOTHING，使 PK 和为审批外键保留的 UNIQUE 均参与冲突仲裁；无操作后仍必须精确复核现行证据、canonical payload、actor/reason 及授权，非唯一键异常不吞掉，保留 V268 40001 整 RR 事务最多3次总尝试。V125 分类申请存在相同结构，预防性调整为全唯一仲裁，审批只有单主键维持原式；不新增事务重试或开放接口。V250 真 PG18 两连接表锁回归保留相同请求/批准与不同批准理由竞争，另增加不同请求理由同键竞争（一个成功、一个409、唯一记录）；强制 V277 JUnit marker，触发新 A3 待验收。无迁移、依赖、前端、补偿激活或 V56 分类门禁变化；分类审批服务的完整并发及 HTTP 验收仍待完成。真实 E1–E5、G1/DEC、Redis 独立 smoke 失败仍待解决；realizedNetProfit=null、OAuth OFF、#104 NO-GO。


V276 [快 CI #45618870958 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45618870958)；V277 [代码 73485ed88c03](https://github.com/kunlong-luo/merchivra/commit/73485ed88c03249905d94b2c41e6383e96c5da46) [快 CI #45619345494 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45619345494) [完整 A3 #45619345828 SUCCESS 7/7](https://github.com/kunlong-luo/merchivra/actions/runs/45619345828)，PG18 job #139223062078 成功，含 V277 强制并发 JUnit marker 及 V125 TEMP SQL；历史 V273/V275 失败仍保留。

V278：开放 V276 本地分类审核 HTTP（Owner Session/CSRF、no-store、tenant/actor 服务端解析），明确选择 report/mapping/evidence SHA；GET 当前有效审核、POST request/approve，复用 V125 记录及当前证据/角色/撤销门禁，不新增审批状态或 DB 迁移。CannotSerializeTransactionException 映射409要求重新加载，而非自动重试；其他错误不伪装成成功。新增 V250 实际 Spring HTTP/PG18 合成报告、真实 DB 交易/拆分/规则夹具及两个独立 Owner session，覆盖 draft/preview/request/approve/current、匿名、缺 CSRF、无申请、旧指纹、同人、跨站点、规则 revision 变化及不可激活字段，并强制 V278 JUnit marker，待新 A3。尚无专门 staff/跨租户 HTTP 新用例（复用既有权限边界）、前端、审核重开、V56 门禁、实际激活或真实 Amazon/银行/会计验证；mappingActivated/bankVerified/ledgerPosted=false、realizedNetProfit=null。Redis 独立 smoke 失败仍待修复；真实 Seller/Ads OAuth OFF，#104 NO-GO。


V278 [代码 a36377ae452c](https://github.com/kunlong-luo/merchivra/commit/a36377ae452ccd928d5ebe7b671b1f9d91953353) [快 CI #45741837204 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45741837204)；[完整 A3 #45741837209](https://github.com/kunlong-luo/merchivra/actions/runs/45741837209) 核对时仍运行，基线、Java、Next、Chromium、Compose 成功，真实 PG18/HTTP 未结束。最新完整验收仍 V277。

V279：在既有结算报告工作台中新增费用分类操作区，复用 React/Next 工作台及现有 Zod、CSRF、Session API。可从可见前100行提取不同原始三元组（不猜分类）、手工补充/删除类型、最多200项、明确 NULL 语义；保存 V274 不可变版本、按 SHA 加载（第二 Owner 可加载同一草稿）、重新核算 V275、查看未映射数/未分类数/差异分组与原币双方金额，调用 V278 本地申请、批准、当前读取。编辑/切换报告或证据错误清除旧结果；409 明示无当前有效审核或失效，不假称尚未提交；需重新核算。Zod 强制 false/null 与响应身份，禁止自审批响应、错误摘要等；新增 Vitest 反例。全量类型自动发现、草稿列表、当前操作者主动前端独立性识别、审核重开、新工作台 Chromium 手动验收尚未实现；服务端仍执行全部权限/覆盖校验。仅使用合成测试，不是财务批准或激活。为避免抢占 V278 完整 A3 本轮不修改 A3 workflow；V279 快 CI 待核，独立完整 A3 需后续触发。真实 Seller/Ads OAuth OFF，realizedNetProfit=null，#104 NO-GO。


### 2026-11-26 · V279 上线复评（仍 NO-GO）

沿用 V231 六维评分 18/20 架构、18/20 业务、10/25 真实利润、14/15 安全、9/10 前端、7/10 测试运维 = **76/100**；V279 前端及 V278 专门 HTTP 尚未完整验收，不提升评分。评分非上线许可，即便90+也须独立完成 #104 G1/G2/G3。V277 完整 A3 7/7 是隔离合成工程证据，不是目标部署证明。

| 发布层级 | 当前结论 | 条件 |
| --- | --- | --- |
| 受控本地试用 / 隔离合成测试 | 可 | 最新变更仍需 CI；OAuth OFF、无真实业务写入、私有环境 |
| 私有预生产环境验证 | 有条件可测试 | 不连接真实 Amazon，不使用未审核真实数据；目标 TLS/Session/权限、备份/恢复和告警演练；不称正式上线 |
| 真实店铺生产运营 | NO-GO | E1–E5、DEC/G1 财务签署、G2 真实安全、G3 真实目标恢复/告警和独立变更批准；单独批准真实 OAuth |

[Issue #104](https://github.com/kunlong-luo/merchivra/issues/104) 核对仍 OPEN/BLOCKED。优先执行：1) 真实单笔合法报告/Finances/银行回单、采购头程关税、退款 COGS 与完整 SKU/订单/期间净利润复算及 G1；2) 目标 TLS/Session/KMS/ClamAV/租户隔离及 G2；3) 实际 PG18 加密离站备份、PITR、RPO/RTO 与值班 ACK/G3。Redis 独立 smoke 启动问题未确认修复。目标证据与签署不公开、不伪造；未签署不部署生产或启用真实授权。


V278 [代码 a36377ae452c](https://github.com/kunlong-luo/merchivra/commit/a36377ae452ccd928d5ebe7b671b1f9d91953353) [快 CI #45741837204 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45741837204) [完整 A3 #45741837209 SUCCESS 7/7](https://github.com/kunlong-luo/merchivra/actions/runs/45741837209)，实际 PG18 job #139648487996 日志确认 PASS V278 actual category review HTTP JUnit marker verified (not skipped)。V279 [代码 f1994297ab75](https://github.com/kunlong-luo/merchivra/commit/f1994297ab754597b1a3b2b47358336605f392c2) [快 CI #45742309207 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45742309207) [只读 Pages #45742309241 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45742309241)，未单独 A3 或专项分类 UI Chromium 验收。

V280：新增 Owner no-store GET `/api/v1/finance/marketplaces/{marketplaceId}/reports/{reportId}/category-types`，在一个 RR 事务检查报告声明行数、实际行数（1–10000）及原币，然后按精确原始类型三元组全量 GROUP BY，读取最多201组以检测超出200项映射契约；每组 rowCount、NULL 保留，无猜测分类或金额。非全量、混币、超限、不可映射类型均返回 HOLD 状态及空数组，跨 Marketplace 404。前端不再从前100行初始化映射，使用全量查询及 Zod 覆盖/身份/重复校验，刷新重新挂载工作台并清除旧证据；原明细表保持100行。新增 Java/Vitest 反例；V250 实际 HTTP 夹具扩为101行、最后一行佣金、两类完整映射及复用 V278 双人审核，强制 V280 JUnit marker；触发完整 A3 待核。无迁移、依赖、真实 API 授权、财务激活、审批重开或正式入账；独立 Redis 启动失败尚未排除。


## V280 复评（2026-11-26）

沿用原六维定性成熟度：架构18/20、**业务19/20（+1）**、真实全成本/净利润10/25、安全14/15、前端9/10、测试运维7/10，合计 **77/100**。唯一加分基于 V278 精确 SHA 的完整 A3 7/7 及 PG18 JUnit 非跳过 marker，证明两位独立 Owner 可以经真实 Spring HTTP 完成分类保存、预检、申请、批准、规则修订后拒绝旧证据的本地业务闭环；V279 快 CI 和 Pages 已通过，但不额外加前端分，V280 待验收不计分。真实 E1–E5、DEC/G1 会计签署、G2/G3 目标证明仍缺，#104 OPEN/BLOCKED；`realizedNetProfit=null`、OAuth OFF。受控本地/隔离测试可进行，私有预生产仅有条件验证，正式生产 NO-GO；评分不是上线许可。

V280 补正：类型初始化使用泛型保留原明细测试夹具额外字段；完整类型查询不在窗口聚焦/网络重连时自动刷新，避免清空未保存的编辑。用户明确刷新会清空编辑与旧证据；以新精确 SHA 的 r2 完整 A3 为准，原运行不作为该 SHA 的验收证据。


V280 [r2 SHA 07fccfb4be98](https://github.com/kunlong-luo/merchivra/commit/07fccfb4be98cdaee4d46f592c804dd8c5826d75) [快 CI #45742981287 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45742981287) [只读 Pages #45742981345 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45742981345)，[完整 A3 #45742981332](https://github.com/kunlong-luo/merchivra/actions/runs/45742981332) 核对时 pending。原 V280 快 CI cancelled（被新版取代），原 A3 运行中；不能继承新版验收。V270 [Redis #45588988904](https://github.com/kunlong-luo/merchivra/actions/runs/45588988904) [job #139117867603](https://github.com/kunlong-luo/merchivra/actions/runs/45588988904/job/139117867603) 完整日志已核实：初始 checkout 网络错误后恢复，server Dockerfile 的 Gradle bootJar 三次均因 Maven Central HTTP 429（Boot buildpack-platform/loader-tools、commons-compress、spring-core 等 classpath）失败。容器启动及 Redis quota/login/restart/outage 未执行；不能推断 Redis Session 业务缺陷或已通过。

V281：只修改现有 Redis 独立 smoke workflow/script，不改变生产应用、Compose 权限、依赖版本或镜像 Dockerfile。拆分当前 SHA server/web 构建和 no-build 启动，server Dockerfile 原三次有限重试不变，外层仅对官方 artifact GET/HEAD 429/502/503/504 再冷却90秒、最多额外一次整体构建；其他失败不吞掉、不复用旧镜像。用 Compose --wait 与 PG18/Redis/Next 健康链，核对 server/web 镜像 revision 标签与当前 GitHub SHA（标签不是供应链签名），明确 Seller/OAuth/写入 OFF。Redis namespace scan 完整消费，重启核对 StartedAt 已变化、健康就绪及同一 cookie 的 userId/activeTenantId；私有临时 cookie/身份文件，始终清理。断 Redis 后检查容器已停止、匿名 ping 可访问，旧认证 cookie 必须实际返回 401/403/500/502/503/504，网络断开不计为通过；增强故障诊断，不输出配置/凭据。Redis 8/noeviction、原 GCRA 测试保留。独立 smoke 新 SHA 待验收；外部仓库持续限流仍可能失败。为保留 V280 r2 A3 不修改 milestone workflow；V281 独立 smoke 不能替代生产 TLS/KMS/恢复/财务签署。


## V281 复评（2026-11-26）

维持 V280 **77/100**：架构18/20、业务19/20、真实全成本及净利润10/25、安全14/15、前端9/10、测试运维7/10。V280 r2 快 CI/Pages 成功但完整 A3 尚未核实；V281 定位历史 Redis 构建429并强化验收，但未取得独立运行通过证据，不加分。生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) 仍 OPEN/BLOCKED。受控本地及隔离合成测试可进行；私有预生产只用于有条件验证，不能真实生产运营。真实 E1–E5 与 DEC/G1、G2/G3 目标独立签署未完成，`realizedNetProfit=null`、Seller/Ads OAuth OFF；评分不是上线批准。


V281 [代码 cb9bb88eea15](https://github.com/kunlong-luo/merchivra/commit/cb9bb88eea150384fa87a121c60e2a11d4bf42cf) [快 CI #45743424984 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45743424984)，[独立 Redis smoke #45743425032](https://github.com/kunlong-luo/merchivra/actions/runs/45743425032) [job #139653656144 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45743425032/job/139653656144) 实际日志 redis_version:8.10.1、GCRA PASS、真实登录/CSRF、重启后同一 userId/tenantId、断 Redis 后旧 cookie 不授权均通过；无生产凭据。V280 r2 [完整 A3 #45742981332](https://github.com/kunlong-luo/merchivra/actions/runs/45742981332) 核查时5项通过，PG18及完整 HTTP 尚运行。

V282：复用 `scripts/smoke_browser_layout.mjs` 现有 Chromium/CDP 与 localhost 合成 API，新增费用分类工作台专项流程：报告101行而可见100行、全量两个原始类型（第101行佣金）、无自动分类、精确映射及 CSRF 保存、原币预检与当前证据、申请写入、模拟自审批403、清除旧证据、重算新证据与跨 Marketplace 清除面板；保存两个360px截图。断言针对真实 DOM 操作及合成请求，不能替代 V278/V280 真实 HTTP 或真实 Amazon 财务验真。未新增前端框架/依赖、迁移、业务激活或正式会计功能。V282 专项浏览器尚待独立运行；不改 A3 工作流，保留 V280 r2 完整验收。


## V282 复评（2026-11-26）

原六维 **78/100**：架构18/20、业务19/20、真实全成本及净利润10/25、安全14/15、前端9/10、**测试运维8/10（+1）**。唯一新增分值基于 V281 精确 SHA 已完成且日志可核的独立 Redis 8 运行验收，消除长期未运行的 Session/配额专项证据缺口；不是生产环境证明。V280 r2 A3 与 V282 浏览器尚待核，不加分。真实 E1–E5、DEC/G1、G2/G3 目标环境签署仍缺；#104 OPEN/BLOCKED、realizedNetProfit=null、Seller/Ads OAuth OFF。可受控本地/隔离测试，有条件私有预生产验证，不批准商业生产。

V282 补充：独立 Chromium 工作流新增仅浏览器测试契约文件变更的 main push 触发，不对每次 TSX/CSS 修改执行完整浏览器，也不修改/取消现有 A3；验收以最新精确 SHA 为准。


V280 r2 [完整 A3 #45742981332 7/7](https://github.com/kunlong-luo/merchivra/actions/runs/45742981332)，七个 job 均 SUCCESS；[PG18 job #139654014988](https://github.com/kunlong-luo/merchivra/actions/runs/45742981332/job/139654014988) 日志确认 `PASS V280 actual full report type discovery JUnit marker verified (not skipped)`。V281 独立 Redis 通过证据保持。V282 [快 CI #45743906965](https://github.com/kunlong-luo/merchivra/actions/runs/45743906965) 的 [Compose job #139655319948](https://github.com/kunlong-luo/merchivra/actions/runs/45743906965/job/139655319948) 失败于 V108 cadence AssertionError: browser；[Chromium #45743907063 job #139655232452](https://github.com/kunlong-luo/merchivra/actions/runs/45743907063/job/139655232452) Vite 阶段通过，Next 阶段在 `V282 full type discovery` 超时，未证明后续保存/审核等动作成功；原因尚未确证。

V283：修正 V108 快速 CI cadence guard，要求 Chromium 的 main push 路径精确等于四个测试契约文件，禁止无过滤 push、常规前端文件自动全量触发及 PR；Compose 和 PG18 原集中测试门禁保留。修正 V282 Chromium 断言：为完整类型覆盖加入明确语义标签，要求选中精确 report、101行/2组覆盖文字（仅归一空白）、两个分类控件且实际 `/category-types` GET；失败记录仅合成请求路径/DOM 摘要及截图，仍抛出超时。动作等待真实可操作（包括 fieldset disabled），自审403必须出现实际 alert 而非静态说明，新证据必须重新 GET 审核。未删原独立性或金额断言，未改变服务端财务门禁。针对浏览器超时尚未证实根因/修复效果，本轮自动触发快 CI 与独立 Chromium 待核，不重跑已通过的 V280 精确 SHA 全 A3。无数据库迁移、依赖或真实 API 调用。


## V283 复评（2026-11-26）

维持 **78/100**：架构18/20、业务19/20、真实全成本及净利润10/25、安全14/15、前端9/10、测试运维8/10。V280 r2 完整 A3 与101行真实 HTTP 合成回归通过，确认此前技术闭环但业务评分已19/20；V282 发现新浏览器回归失败，V283 尚未通过，不增加前端或运维分。不以工程合成数据证明实际利润。#104 仍 OPEN/BLOCKED，真实 E1–E5、DEC/G1、目标 G2/G3 未验收；realizedNetProfit=null、Seller/Ads OAuth OFF。受控本地/隔离合成测试可以；私有预生产仅有条件验证；正式商业生产 NO-GO。


V283 [SHA 60a74d5c48e3](https://github.com/kunlong-luo/merchivra/commit/60a74d5c48e30375145efa3a2cf47613a7afc11c) [快 CI #45744211055 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45744211055) [只读 Pages #45744211134 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45744211134)；[独立 Chromium #45744211076 FAILURE](https://github.com/kunlong-luo/merchivra/actions/runs/45744211076)，[job #139656222559](https://github.com/kunlong-luo/merchivra/actions/runs/45744211076/job/139656222559) Next 阶段日志显示 selected 为空、分类报告子请求为空、报告列表和人工参考仍在加载。说明失败发生在分类服务请求之前；可能为 App Router 初次挂载/重挂载时序，尚未证明唯一根因或生产 UI 无缺陷。V280 r2 A3 7/7、V281 独立 Redis 成功保持。

V284：将原 V282 分类专项旅程移动至既有财务页面明细分页和证据检查成功之后、切换 Marketplace 之前；要求 `/finance` 路由、精确合成报告选项、完整101行/2组覆盖及分类控件共同满足。只在原生 select 被重置时重复相同只读选择，写入按钮不重复操作；等待 fieldset 启用后才输入审核理由；实际报告列表及 `/category-types` GET、保存及申请的精确请求体、审批拒绝 alert、新证据 GET、跨站点清除等断言全部保留。补充根列表请求、路径及选项诊断，使用不与旧 SKU 夹具重复的报告 UUID。未增加超时时间、未跳过失败、未修改业务/财务服务、未开放真实授权。当前仅提交修复，实际浏览器验证仍待执行；如果再次失败按新日志定位应用或测试缺陷，不宣称已完成浏览器验收。


## V284 复评（2026-11-26）

维持 **78/100**：架构18/20、业务19/20、真实全成本及净利润10/25、安全14/15、前端9/10、测试运维8/10。V283 快 CI 失败已修复并通过、Pages 成功，但专门 Next 分类旅程仍失败，V284 修复未验收，不提高评分。当前主线不应称发布候选已通过。#104 OPEN/BLOCKED；真实 E1–E5、DEC/G1、G2/G3 目标证据及签署未完成，realizedNetProfit=null、Seller/Ads OAuth OFF。可以使用已验收基线在受控本地/隔离合成环境试用；私有预生产仅有条件验证；正式生产 NO-GO。下一个可验证目标是独立 Chromium 全绿及精确发布 SHA 完整验收，随后真实财务与目标基础设施；评分不替代上线批准。


V284 [SHA b6d51157f980](https://github.com/kunlong-luo/merchivra/commit/b6d51157f980b52f2b52aaa9d147a60d2758bfe5) [快 CI #45745780681 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45745780681)；[Chromium #45745780588 FAILURE](https://github.com/kunlong-luo/merchivra/actions/runs/45745780588) [job #139661363183](https://github.com/kunlong-luo/merchivra/actions/runs/45745780588/job/139661363183) Next 日志：两次报告列表 GET、没有分类子请求；DOM 有六组相同报告选项，首个 select 仍空。确认重复 DOM 控件是实际观察到的缺陷。代码检查发现每个缓存路由页面分别挂载 Portal/QueryClient/Root，且各 Portal 使用全局 pathname 渲染同一当前视图，符合重复控件现象；是否为唯一根因及修复效果仍待浏览器复验。V280 r2 A3 7/7 和 V281 Redis 独立运行证据保持。

V285：实际调整 Next App Router 结构，将唯一持久 Portal 提升至 `app/layout.tsx`，根路径及 `[view]` 页面仅为路由标记/合法路径校验（非法路径保留 notFound）；不再在每个缓存页面生成独立 QueryClient/Root。Portal 使用当前 pathname，单一 Root 负责 Session 验证及 V144 缓存清理；无有效路由不渲染工作台。Vite 回退、后端权限、OAuth、财务门禁不变。Chromium 新增跨路由同一 DOM 根实例标记和唯一 Portal/报告选择/分类工作台断言（包含隐藏 DOM），只操作唯一可见且非 inert/hidden 控件，保留 V282/V283/V284 所有金额、实际 HTTP、精确写入、拒绝审批、旧证据与跨 Marketplace 检查。未将测试重复点击作为应用修复，不放宽原断言；目前没有完整验证新结构的返回/前进、登录退出及非法路由，须验证本轮浏览器和快 CI，再对精确发布 SHA 做完整 A3。无新增依赖、迁移、真实数据或生产授权。


## V285 复评（2026-11-26）

维持 **78/100**：架构18/20、业务19/20、真实全成本及净利润10/25、安全14/15、前端9/10、测试运维8/10。V284 快 CI 成功但 Next 分类专项失败；V285 修复重复工作台结构尚未通过专项及精确 SHA 完整 A3，不提高分值。评分为定性成熟度而非生产准入。#104 OPEN/BLOCKED；真实 E1–E5、DEC/G1、G2/G3 目标证据与签署未完成，realizedNetProfit=null、Seller/Ads OAuth OFF。已验收基线可受控本地/隔离合成试用，私有预生产仅有条件验证，最新主线不能宣称 RC 已通过，正式商业生产 NO-GO。
