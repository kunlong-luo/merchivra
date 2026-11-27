# Merchivra · 唯一现行 P0 执行计划

**CURRENT · 2026-11-27 · 单任务会话队列。** 以 [AGENTS.md](../../AGENTS.md) 为规则，[当前事实](../current-status.md) 为验收事实；main / Gradle / pnpm，#104 NO-GO，realizedNetProfit=null。优先级不代表生产授权。

## 当前任务

### V291 · 单一真实订单/批次/结算财务证据闭环

- Task: V291
- State: BLOCKED
- Priority: P0
- Basis: V290 精确候选 `f5c42fa4669cf8089363ddcb6c7d884c414d0aae` [快 CI #45812139153](https://github.com/kunlong-luo/merchivra/actions/runs/45812139153) 与 [完整 A3 #45812139166](https://github.com/kunlong-luo/merchivra/actions/runs/45812139166) SUCCESS（run_attempt=1，7/7），已 VERIFIED (synthetic)，见[当前事实](../current-status.md)。本次 V290 会话只做关闭交接，不实施 V291。
- 目标：对单一合法、可独立复算的真实订单/批次/结算，按 E1–E5 核验签发主体、原始字节、原币费用、货物与退款 COGS、银行归属和正式会计批准。
- 范围：只在合法本地/受控环境取得证据并复用已有模型；仅针对真实样本证明的缺口设计最小改动和正反例。
- 不做：上传原件、凭据或隐私到 Git；擅自开启真实 Seller/Ads OAuth、银行过账或实际利润；重复合成 UI、越界 V292、批准生产。
- 依赖/阻塞：维护者提供合法真实样本、授权与独立财务批准；尚未取得。E1–E5、DEC-06/07/08、G1 未证。
- 验收：按下列 E1–E5 的真实材料与可复算条件逐项取得独立证据，记录脱敏证据索引、范围、原币、归属、覆盖、未知项与签署状态；样本不等于完整期间，不解除生产 #104 NO-GO。
- 唯一下一动作：维护者在受控环境准备一个真实订单/批次/结算证据包及授权范围，先核验 E1 签发主体和原件真实性；没有材料继续 BLOCKED。
- 交接核验：精确核验 HEAD [`d98f9601ddf2b32e2707bd5daba9ac515a164e86`](https://github.com/kunlong-luo/merchivra/commit/d98f9601ddf2b32e2707bd5daba9ac515a164e86)，[快 CI #45931034746](https://github.com/kunlong-luo/merchivra/actions/runs/45931034746) SUCCESS（run_attempt=1）：Java/Gradle #140283452228、Compose/cadence/文档与生产门禁守卫 #140283452063、Next/Vite/Orval/TypeScript/单元测试 #140283452319 成功；PG18 #140283453728、JaCoCo、完整 Next HTTP 按快 CI 范围跳过；无失败 job。与 V290 候选相比仅三份权威文档变更（3 commits），完整 A3 7/7 仅属于 V290 候选。该 SHA 的 CostProfitReadinessService.java 285–320 行仍 false/false/false/null，check_production_gate_v143.py 232–253 行仍禁止生产批准和部署；#104 OPEN，NO-GO。
- 本轮仅回填已独立核实的快 CI 与明确本地 E1 操作步骤，不改业务代码、迁移或工作流。提交标题 `docs(v291): record verified handoff and local E1 checklist`，parent 为 `d98f9601ddf2b32e2707bd5daba9ac515a164e86`。执行环境 DNS 无法解析 GitHub，无法克隆并运行提交前文档/cadence 守卫；新提交快 CI 尚待按精确 SHA 核对，不继承已有绿灯。无真实材料继续 BLOCKED；不得仅为回填自身文档 CI 反复提交，下一次可在提供真实材料时合并记录。 V291 未 VERIFIED 前不得领取 V292。

### V291 本地 E1 操作清单（维护者执行）

1. 在本地 `git switch main`、`git pull --ff-only`、`git rev-parse HEAD`；记录精确 SHA。按[开发指南](../development.md)及[数据库指南](../deployment/local-spark-db.md)使用独立 PG18 数据库/低权限角色，Java25/Gradle 与 Next16/pnpm；已有 spark-db 可用 `deploy/compose.spark-db.yaml`，不要和内置 Compose 合并。内置 `deploy/compose.yaml` **强制 Demo=true**，只能测试虚构数据，禁止导入真实材料。真实证据先在应用外的受控本地目录保存，不上传到应用或 Git。
2. 非 Demo 本地配置 `MERCHIVRA_DEMO_ENABLED=false`，保持 `MERCHIVRA_SPAPI_ENABLED=false`、`MERCHIVRA_SPAPI_OAUTH_ENABLED=false`、`MERCHIVRA_SPAPI_ALLOW_WRITES=false`、真实同步和 Seller/Ads OAuth 均关闭；数据库密码和首次管理员密码只在本地注入（管理员密码至少16位）。通过宿主机 Gradle `./apps/server/gradlew -p apps/server --no-daemon bootRun`，另一个终端 `cd apps/web`、`pnpm install --frozen-lockfile`、`pnpm dev`；或按 spark-db 指南启动两容器。`curl -i http://127.0.0.1:8080/api/v1/ping` 应200，匿名 `curl -i http://127.0.0.1:3000/api/v1/auth/me` 应401；登录后检查财务、订单、库存页面。不得用关闭鉴权解决 5xx。
3. 通过维护者合法授权的 Seller Central/Ads 官方渠道、供应商、货代、报关、WMS、银行等独立来源在应用外取得单一真实订单相关原始文件；按原始字节计算 SHA-256（Linux `sha256sum`、macOS `shasum -a 256`、Windows PowerShell `Get-FileHash -Algorithm SHA256`）。保存可验证的签发来源、账户/站点、报告版本、行键、授权与更正链；仅哈希匹配不证明签发者。
4. 仅回传脱敏索引：样本代号、来源类别、币种、期间、文件/行数量、摘要核验结果、签发主体及授权核验 PASS/FAIL/UNKNOWN、独立复核状态、缺失或冲突项；禁止上传真实文件、凭据、银行账号或客户信息。E1 通过后继续同一 V291 的 E2–E5，未证保持 UNKNOWN/hold。样本闭环不代表整期或生产放行。

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
