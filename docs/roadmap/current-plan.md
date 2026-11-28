# Merchivra · 唯一现行 P0 执行计划

**CURRENT · 2026-11-27 · 单任务会话队列。** 以 [AGENTS.md](../../AGENTS.md) 为规则，[当前事实](../current-status.md) 为验收事实；main / Gradle / pnpm，#104 NO-GO，realizedNetProfit=null。优先级不代表生产授权。

## 当前任务

### V291 · 单一真实订单/批次/结算财务证据闭环

- Task: V291
- State: BLOCKED
- Priority: P0
- Basis: V290 精确候选 `f5c42fa4669cf8089363ddcb6c7d884c414d0aae` [快 CI #45812139153](https://github.com/kunlong-luo/merchivra/actions/runs/45812139153) 与 [完整 A3 #45812139166](https://github.com/kunlong-luo/merchivra/actions/runs/45812139166) SUCCESS（run_attempt=1，7/7），已 VERIFIED (synthetic)，见[当前事实](../current-status.md)。此后配置与前端维护不等于 V291 外部材料验收。
- 目标：对单一合法、可独立复算的真实订单/批次/结算，按 E1–E5 核验签发主体、原始字节、原币费用、货物与退款 COGS、银行归属和正式会计批准。
- 范围：只在合法本地/受控环境取得证据并复用已有模型；仅针对真实样本证明的缺口设计最小改动和正反例。
- 不做：上传原件、凭据或隐私到 Git；擅自开启真实 Seller/Ads OAuth、银行过账或实际利润；重复合成 UI、越界 V292、批准生产。
- 依赖/阻塞：维护者提供合法真实样本、授权与独立财务批准；尚未取得。E1–E5、DEC-06/07/08、G1 未证。
- 验收：按下列 E1–E5 的真实材料与可复算条件逐项取得独立证据，记录脱敏证据索引、范围、原币、归属、覆盖、未知项与签署状态；样本不等于完整期间，不解除生产 #104 NO-GO。
- 2026-11-28 维护者回执：本人私有应用，US/NA（ATVPDKIKX0DER）；LWA Client ID/Secret/Refresh Token 已备；Product Listing、Amazon Fulfillment、Finance and Accounting、Inventory and Order Tracking 报告已获批。未提交真实值、审批原件或 Seller ID；实际 Seller ID、独立环境、Redis 配额、受控出站批准及真实核验仍待确认。
- 唯一下一动作：按 [INT-005 US R0/R1](../integrations/private-seller-import.md) 第2A节实际建立安全隔离环境、私有配置与 US 占位，回传 R0/受控审批结论；获准后执行第3节一次性导入及单站点验证。R1 NOT_RUN；V291 BLOCKED。E1 可同时从合法官方后台取得原件，真实出站不得擅自启用。
- 精确基线 [`ffc599896a55f08a6461d8ac7543b32dd5118c5b`](https://github.com/kunlong-luo/merchivra/commit/ffc599896a55f08a6461d8ac7543b32dd5118c5b) [快 CI #46101466457](https://github.com/kunlong-luo/merchivra/actions/runs/46101466457) SUCCESS：Java #140844637319、Next #140844637491、Compose #140844637545 成功；PG18 #140844638203、JaCoCo、完整 Next HTTP 按快 CI 范围跳过。
- 交接核验：精确基线 [`d66e9942074184e15930fb86ae9dac73643b4652`](https://github.com/kunlong-luo/merchivra/commit/d66e9942074184e15930fb86ae9dac73643b4652) [快 CI #46005594115](https://github.com/kunlong-luo/merchivra/actions/runs/46005594115) SUCCESS：Next #140527812894、Compose #140527813341、Java #140527813459 成功；PG18 #140527815372、JaCoCo、完整 Next HTTP 按快 CI 范围跳过。 完整 A3 精确候选 `efe94b5841b05dfa14de6fea0167a0bb3badebd2` [#46004710175](https://github.com/kunlong-luo/merchivra/actions/runs/46004710175) SUCCESS（run_attempt=1，7/7）。本轮核查真实 job 原始日志：[Compose #140524895041](https://github.com/kunlong-luo/merchivra/actions/runs/46004710175/job/140524895041) 实际 PASS V144/V122（11 路由、真实 Spring Session/CSRF、退出失效）、V146（合成 CA 验证、双 Next 同一 Cookie 切换及返回、拒绝伪造 Host、Secure Session）；[PG18 #140524895440](https://github.com/kunlong-luo/merchivra/actions/runs/46004710175/job/140524895440) 实际 PG18.7、PASS V250/V254/V255–V269/V278/V280 对应非跳过 JUnit marker（成本、退款证据、并发及分类审核）；[Chromium #140524894832](https://github.com/kunlong-luo/merchivra/actions/runs/46004710175/job/140524894832) 实际 PASS V254/V285/V286/V289。此为隔离合成工程验收，不证明真实旧版本镜像回滚、目标 TLS、真实 Amazon 或银行认证。 历史维护见[当前事实](../current-status.md)。
- 维护者本地验收回执（2026-11-27，执行人：维护者；精确本地 SHA、用例输入、逐项日志/截图未提供）：管理员登录/退出、左侧菜单滚动及视觉、商品与 SKU、供应商与采购、仓库及库存、订单与退款、FBA 货件、财务报表及利润页面、Redis 本机认证，9/9 报告通过，无报告缺陷。记录为维护者功能级验收，不声称逐操作覆盖、独立重算或当前 SHA 自动化验证；Redis 认证不等于本机启用 Redis Session/限流。 本机启动已获日志，不重复建库/重置管理员或重复九项验收，除非发生回归。V291 未 VERIFIED 前不得领取 V292；不得仅为回填自身文档 CI 反复提交。

- 私有应用方案已按当前代码及 Amazon 官方文档核对：实际 SP-API 强制 Redis 限流、仅 US/DE/JP 预设、独立角色双人审核。精确基线 [`7dd29280438be1d53d3788b400e5cfc4b523ec0c`](https://github.com/kunlong-luo/merchivra/commit/7dd29280438be1d53d3788b400e5cfc4b523ec0c) [快 CI #46005938792](https://github.com/kunlong-luo/merchivra/actions/runs/46005938792) SUCCESS：Next #140528987913、Java #140528988203、Compose #140528988268；PG18 #140528990185、JaCoCo、完整 Next HTTP 按快 CI 范围跳过。完整 A3 仍对应 efe94b5841b05dfa14de6fea0167a0bb3badebd2，7/7。 无真实样本或批准；先准备，不开启真实开关，后续按 INT-005 R1→R6 逐步受控联调，不重复本地九项或完整 A3，不新增 V292。

### V291 本地 E1 操作清单（维护者执行）

本机功能验收已由维护者报告 9/9 通过；第1–2步保留作复现及环境安全检查，非重复任务。当前重点为第3–4步 E1，真实材料必须使用私有安全配置和独立受控环境。

1. 在本地 `git switch main`、`git pull --ff-only`、`git rev-parse HEAD`；记录精确 SHA。按[开发指南](../development.md)及[数据库指南](../deployment/local-spark-db.md)使用独立 PG18 数据库/低权限角色，Java25/Gradle 与 Next16/pnpm；已有 spark-db 可用 `deploy/compose.spark-db.yaml`，不要和内置 Compose 合并。内置 `deploy/compose.yaml` **强制 Demo=true**，只能测试虚构数据，禁止导入真实材料。真实证据先在应用外的受控本地目录保存，不上传到应用或 Git。
2. 非 Demo 本地配置 `DEMO_ENABLED=false`，保持 `SPAPI_ENABLED=false`、`SPAPI_OAUTH_ENABLED=false`、`SPAPI_ALLOW_WRITES=false`、真实同步和 Seller/Ads OAuth 均关闭；数据库密码和首次管理员密码只在本地注入（管理员密码至少16位）。共存本机使用 IDEA 的 local profile 或 `./apps/server/gradlew -p apps/server --no-daemon bootRun --args='--spring.profiles.active=local'`（8081）；前端 `cd apps/web`、`pnpm install --frozen-lockfile`、`pnpm dev:local`（3001）。若走独立 Compose/普通开发，按对应指南使用 8080/3000，不混用。`curl -i http://127.0.0.1:8081/api/v1/ping` 与 `curl -i http://127.0.0.1:3001/api/v1/ping` 应200且 Spring 身份一致，匿名 `curl -i http://127.0.0.1:3001/api/v1/auth/me` 应401。使用已有 OWNER 本地登录、打开财务/订单/库存/采购、退出后再次检查会话401；短窗口滚动到导航底部并确认轨道隐藏；共享 Redis 仅用 `docker exec -it spark-redis redis-cli --askpass ping` 只读检查 PONG，不清空、不修改配置，不启用共享 Session/限流。公开测试默认密码仅用于一次性数据库，非一次性/真实材料环境必须以私有配置覆盖，并更换已创建测试账号密码，不把密码/Session 写入验收记录。不得用关闭鉴权解决5xx。
3. 通过维护者合法授权的 Seller Central/Ads 官方渠道、供应商、货代、报关、WMS、银行等独立来源在应用外取得单一真实订单相关原始文件；按原始字节计算 SHA-256（Linux `sha256sum`、macOS `shasum -a 256`、Windows PowerShell `Get-FileHash -Algorithm SHA256`）。保存可验证的签发来源、账户/站点、报告版本、行键、授权与更正链；仅哈希匹配不证明签发者。
4. 仅回传脱敏索引：样本代号、来源类别、币种、期间、文件/行数量、摘要核验结果、签发主体及授权核验 PASS/FAIL/UNKNOWN、独立复核状态、缺失或冲突项；禁止上传真实文件、凭据、银行账号或客户信息。E1 通过后继续同一 V291 的 E2–E5，未证保持 UNKNOWN/hold。样本闭环不代表整期或生产放行。

### V291 E1 回执及 V270 既有工具（当前唯一执行项）

**先只选一个真实订单、一个 Marketplace 和一种结算原币；不得为凑齐材料而编造记录。** 若暂时没有合法 Seller 资料，只反馈 `E1=UNKNOWN（未取得材料）`，不重复已通过的九项本机测试。此阶段无需开启 Merchivra Seller/Ads OAuth；可从本人已获授权的官方后台导出，并从供应商、货代、仓储及银行各自合法渠道取证。不要将真实资料导入当前公开测试密码环境；先采用独立受控环境、私有强密码、最小权限。

| 原件类别（按样本适用性） | 本地独立检查 |
| --- | --- |
| Amazon 订单、退款及结算/费用原报告 | 同一账户、站点、订单/行键、结算期和原币；原始字节、签发渠道和授权可追溯 |
| Ads 费用（仅适用时） | 官方实际收费/报表、适用期间及分配依据；未取得不得按0 |
| 采购发票、收货/出库、物流/关税 | 原采购与实际销售批次、数量及原币费用唯一归属 |
| 退款及仓库实际处置（如有） | 原销售批次、返仓/质检/处置、数量守恒；退款 COGS 未证不得回转 |
| 结算对应银行真实回单/流水 | 账户归属和原币实际到账、费用及差异；银行原件在本地留存 |

在私有目录保留原文件及原始字节 SHA-256；不要上传原件、摘要原文、真实订单号、金额、账号、凭据或签署件。V230 [缺口清单](../finance/v230-external-source-proof-dossier-and-owner-workflow.md) 的本地审计结果、V231 [浏览器摘要对照](../finance/v231-local-byte-claim-check-and-repository-pruning.md) 与 V270 本地金额一致都不是独立签发证明。V231 的浏览器上限为8 MiB；更大的原件使用受控本机散列，不绕过边界。

**如已取得符合 V270 合同的同币种、单一正向结算及唯一对应银行条目**，复用[现有结算样本工具](../finance/manual-bank-payout-v60.md#v270--首笔真实结算样本本地核对代码待-a3真实材料-blocked)，不用启动 Spring 或数据库，不新增 API。先离线准备标准化银行 CSV（派生文件，不是银行原件），在仓库根执行，以下路径仅占位：

```bash
export MERCHIVRA_SAMPLE_REPORT=/private/path/settlement.tsv
export MERCHIVRA_SAMPLE_BANK=/private/path/normalized-bank.csv
export MERCHIVRA_SAMPLE_PRIVATE_DETAILS=false
# 原报告为 gzip 时才设置 MERCHIVRA_SAMPLE_GZIP=true，否则 false
export MERCHIVRA_SAMPLE_GZIP=false
./apps/server/gradlew -p apps/server --offline --no-daemon --console=plain checkLocalSettlementSample
unset MERCHIVRA_SAMPLE_REPORT MERCHIVRA_SAMPLE_BANK MERCHIVRA_SAMPLE_GZIP MERCHIVRA_SAMPLE_PRIVATE_DETAILS
```

仅回传状态码，不转发完整 Gradle 输出。 `LOCAL_SAMPLE_AMOUNTS_AGREE_UNVERIFIED` 只说明有限算术一致；输入不支持、分拆合并、跨币种、冲正、差异保持 UNKNOWN/HOLD，不能强行修数据。无材料则 NOT_RUN。E1 签发及授权须由合法渠道与独立复核人员判定，V270 不能代替 E1/E2–E5。

**脱敏回执（复制填写，UNKNOWN/NOT_RUN 为初始值，不是假通过）：**

```text
样本代号：SAMPLE-01（自拟）
本机代码 SHA：
Marketplace 范围：单站点/UNKNOWN
结算原币：UNKNOWN
原件类别及数量：UNKNOWN
原始字节重算：UNKNOWN
独立签发来源核验：UNKNOWN
合法授权与更正链：UNKNOWN
独立复核状态：UNKNOWN
V270 状态：NOT_RUN
缺失/冲突类别：
E1：UNKNOWN
```

只有实际授权、原始材料及独立验证均满足才由维护者确认 E1；再推进同一 V291 的 E2–E5。DEC-06/07/08 尚待正式确认，见[决策登记](../governance/decision-register.md)，不自行签署或改变利润状态。

## 后续队列（一次只领取一项）

| ID | 状态 | 前置 | 验收标准 |
| --- | --- | --- | --- |
| V289 | VERIFIED | V287 | 快 CI、独立 Chromium、Pages 全部成功，见当前事实 |
| V290 | VERIFIED | V289 VERIFIED | 精确候选 SHA 完整 A3 7/7、登录退出/缓存隔离及非法路由回归通过；仅 RC 工程验收 |
| V291 | BLOCKED | 维护者提供合法真实样本/授权 | E1–E5 单一真实订单/批次/结算及独立复算、G1/DEC 批准；未证不激活 |
| V292 | BLOCKED | 目标环境及负责人 | G2/G3 实际安全、备份恢复及告警签署；#104 独立发布决定 |

### V232 执行顺序及真实验收（BLOCKED：未取得外部原件/签署）

停止以新增只读缺口 UI、本地摘要、自述证明或同类审批作为 V232 的主要成果。按一条合法、脱敏、可独立复算的真实订单/批次/结算闭环先验收，再扩大到完整期间；样本通过不等于期间完整。

| 关卡 | 必须取得的独立材料 | 可复算的通过条件 |
| --- | --- | --- |
| E1 签发与原件 | 授权维护者本地取得 Seller/Ads 原始响应或原始报告；货代、供应商、报关、银行、WMS 的独立官方渠道或可验证签章原件 | 保密保存原字节，独立重算 SHA-256；通过可信渠道绑定签发主体、账户/站点、报告版本与行键；验证授权范围及有效期、撤销与更正链。单方声明/自签公钥不算 |
| E2 费用与付款 | 原币账单行、发票/贷项通知、实际支付或结算明细 | 复用 V91/V94/V102/V165/V182/V229/V230；按来源行、币种、符号与业务归属排除重复扣款；对非 Amazon 费用使用其合法支付证据，不强求 Amazon 结算行 |
| E3 货物与退款 COGS | 实际采购发票、原收货批次、运输/关税账单、原出库领用、真实外仓返仓/QC/处置 | 复用 V106/V112 与原销售批次认领；原币/数量/费用分配守恒，退款冲回不超过可追溯原销售成本；不可售、销毁、未返仓或归属不明分别按已签会计政策处理，未知不置零 |
| E4 资金归属 | 原结算报告、实际银行到账/扣款、银行费用、外汇与调整记录 | 独立复核结算与付款的多对多归属及原币金额；待结算、跨期、预留、冲销、手续费和汇兑差额单独解释，不强行归零，不将银行到账重复当收入 |
| E5 会计与完整期间 | 财务/产品/运营授权负责人正式签署 G1 与 DEC-06/07/08，保留批准版本、生效日期、适用范围和撤销条件 | 对已签规则独立复算收入、退款、费用、COGS、退款成本回转与资金对账；逐项核对整期覆盖、原币和更正链，任一缺口继续 UNKNOWN/hold；只有批准范围内完整证据才允许讨论实现真实利润，生产发布仍须独立 G2/G3 |

**执行约束**：维护者尚未提供合法真实材料；不得在公开 Git 中保存发票、银行流水、客户信息、令牌或签署原件，不得擅自开启 Seller/Ads OAuth。先核对已有适配器/证据模型，仅针对真实样本揭示的可验证缺口追加最小代码和负例（签发方不可信/撤销、原件改动、跨租户、重复行、退款超量、结算差异、会计批准缺失）。无样本时可做合成契约回归，但不能宣称 E1–E5 已完成，不能自动把内部复核升级为外部核证。

**评分闸门**：工程成熟度依据独立新验收证据调整；真实利润10/25仅凭真实财务证据调整。评分不得替代 G1/G2/G3，保持 realizedNetProfit=null、Seller/Ads OAuth OFF、#104 NO-GO。

## 任务生命周期与文档规则

READY → IN_PROGRESS → IMPLEMENTED_PENDING_VERIFICATION → VERIFIED；FAILED/BLOCKED 可续同一 ID。会话结束必须记录当前任务状态、代码 SHA（或可确定的最后任务提交）、测试运行/失败/跳过、未完成项和唯一下一动作。只有全部验收 VERIFIED 才称任务完成。未完成也可以关会话，下一会话恢复同一 ID。不要将提交成功视为任务关闭。
现行事实、现行队列与长期规范分开；历史证据保留领域文档或精确 Git 快照。详细见 [文档控制](../governance/document-control.md)。
