# Merchivra · 当前执行计划（2026-11-24 / V220 已验证完整 A3，V221 并发快照与真实来源仍待推进）

**状态：现行 P0 任务顺序，不是自动上线授权。** 以 [当前事实基线](../current-status.md)、[ENG-001 实施规范](../governance/engineering-standards.md)、[V194/V105 最终代码 SHA `9cdb9268020bdc9ae0c743e09d5d5775c377ce69`](https://github.com/kunlong-luo/merchivra/commit/9cdb9268020bdc9ae0c743e09d5d5775c377ce69) 的 [完整 A3 #45018945885（run_attempt=1，7/7 核心 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45018945885)；同一 SHA [快速 CI #45018945926 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45018945926) 为依据；早期 [V108 MVP 计划](mvp-freeze-release-v108.md) 与 [PLAN-001 长路线图](../roadmap.md) 保留为历史迭代记录。

## 北极星与边界

Merchivra 最重要的核心能力是**汇集订单/SKU/店铺/期间所有有证据的真实收入、费用、货物成本和退款回转，核对重复归属并形成可追溯的利润报表**。不能为了“有利润数字”绕开不可信来源、币种和会计政策。正式本地 UI 已切 Next.js 16，pnpm 是前端唯一包管理器；这两个工程完成项**不能**代替业务真值。

## 执行优先级（按证据闭环，而非展示卡数量）

| 优先级 | 要完成什么 | 现有可复用基础 | 完成与不可完成的判据 |
| --- | --- | --- | --- |
| P0-A | **第三方来源真实性/账单独立核验**：定义 Amazon Finances/Settlement、Ads、物流/头程/关税、银行证据的合法取得和身份复核流程 | V91–V95、V102/V181、V182–V184、本地身份冲突与不可变审计 | 每笔来源能追溯原始系统/报告/行/摘要、币种与独立批准；手工/合成绝不自动升级为 VERIFIED |
| P0-B | **全成本、退款 COGS 和唯一归属**：明确 SKU/订单/期间及跨月金额守恒、退款负费用/成本回转、真实重复扣款裁决 | V162、V164、V170–V188 的只读引擎、跨来源 hold、原币预览 | 有明确 G1/DEC-06/07/08 财务口径，整期全量而非仅当前页，缺来源为 UNKNOWN、重复不叠加 |
| P0-C | **可审计利润核算与报表**：让 V64 已知范围贡献与经过独立认证的费用接入同一可重算版本化证据链 | V64 冻结快照、V180 版本差、V183/V186/V187/V188 敏感度瀑布、Next 财务中心 | 仅在成本完整/已签署情况下给出“真实净利润”；此前 `realizedNetProfit=null`，展示阻断原因而非填写零 |
| P0-D | **可信 WMS 及库存/COGS**：把 B1 合成密钥持有权扩为独立 B2 外部运营主体与物理签收、获准 B3 库存/成本过账 | V173–V175 QC/回执和 V101 密钥持有权技术基础 | 不能用内部双/三 Owner、签名 JSON、上传公钥冒充真实外部身份或实收；G1 签署前不能改变 COGS |
| P0-E | **安全与真实目标环境**：TLS/代理头、KMS/密钥、AV、数据库 PITR/RPO/RTO、外部告警 ACK、权限、容量及人工审批 | 受控 Next/PG18/Chromium/Compose A3、[目标预检](../architecture/next-target-ingress-v151.md)、[生产阻断](../testing/release-readiness-v68.md) | [#104](https://github.com/kunlong-luo/merchivra/issues/104) 的 G1/G2/G3 分别签署，目标环境证据独立审核；任何合成 CI 绿灯不等于发布 |
| 最后 | **维护者本地真实 Seller/Ads 只读授权与 API 验收** | Mock Gateway、连接与同步代码 | 用户完成实际授权、合法角色/区域、分页/限流/撤销、凭证/银行真实性和隐私审查之后才可调整访问开关；写入另行审批 |

工作原则：先复用既有表、服务、Orval 客户端和差异审计；不新建重复“真实性系统”，不修改历史 Flyway；后端审计优先于增加展示卡片。

## V220 已实现并验收 · V112 非空本地费用、审核、双重归属、读时失效和 51 项全量上限（[V220 精确验收代码 `b821ecb`](https://github.com/kunlong-luo/merchivra/commit/b821ecbf0be925f4073c80cff0e146fbaf4b43f0) · [快速 CI #45314064511 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45314064511) · [完整 A3 #45314064469（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45314064469)）

[V220 原币非空费用来源的真 Spring/PG18 HTTP 验收契约](../finance/v220-receipt-source-population-real-http.md)：使用已有 V216 Owner/no-store GET 与真实 V91/V112 表、真实 PG18 triggers，在既有 V219 A Owner/员工-B Owner 的一次性隔离库中做两条已独立审核但外部未核实的人工原币候选：USD 100 source/current receipt 40、两个 receipt total 90；另一 USD 30/current 10。V165 同站点 claimed 文档摘要重叠产生潜在冲突 HOLD，不同站点相同摘要不能合并；V92 对活跃 V112 source 的重复归属由 DB 拒绝；PO 当前币种变更让既有审批读时过期。再将同一 receipt 从 2 扩充到 50 份计划并完整 200，51 份必须 409 不得返回截断。跨租户/Marketplace/Staff 权限沿用 V219 真实 HTTP，不新加财务过账或报表展示。不允许将本地提案升格为真实 landed cost、COGS 或 realized profit。**实际已验收**：[V220 精确验收代码 `b821ecb`](https://github.com/kunlong-luo/merchivra/commit/b821ecbf0be925f4073c80cff0e146fbaf4b43f0)： [快速 CI #45314064511 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45314064511)，[完整 A3 #45314064469（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45314064469)。PG18 Job #138176547718 原始日志明确打印 PASS V220 real Spring/PG18 V112 nonempty Owner HTTP，验证 40/90 USD 原币全收货金额、V165 同站点潜在碰撞/跨站点隔离、真实 V92 trigger 排斥、PO 币种变化导致读时 HOLD、50 完整 HTTP200 与 51 HTTP409 不回退局部数据，以及 A 非 Owner403/B 跨租户404/匿名401。全部仅为一次性 PG18 合成手工数据，不是外部 landed cost 核证。

**下一阶段 V221**：真实双 repeatable-read 快照读时 source/receipt population 并发变更、ABA 与 409 fail-closed 的独立验收；外部结算/发票/银行/货代证明及 G1/DEC 会计口径仍是实际净利润门槛。维持评分 70/100、realizedNetProfit=null、真实 Seller/Ads OAuth OFF、生产 #104 NO-GO。

## V219 已验收 · Spring Owner/Staff 与跨租户 Marketplace GET（[V219 代码 `6bec3c4`](https://github.com/kunlong-luo/merchivra/commit/6bec3c4b59f1e904a25c5512d0054a13ad9ca1d0) · [快 CI #45311583687 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45311583687) · [完整 A3 #45311583643（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45311583643)）

[V219 权限与 Session 真实 HTTP 契约](../finance/v219-real-owner-receipt-http-boundary.md)：复用 V216 GET 与已存在的 V15/V16 用户角色、Spring Security Filter、PG18 V20 receipt。CI 一次性库插入两套合成租户/商店/receipt 与 A 员工-B Owner 混合身份，用不同 JSESSIONID + CSRF 进行真实登录/切租户/HTTP。检验匿名 401、A 非 Owner 403、未授权 tenant 切换 403、跨租户/Marketplace/receipt 404、合法 Owner 200+no-store+UNKNOWN 非零，以及另一 Session 不受本会话注销影响。源码与 CI 仅新添测试文件及一行 PG18 脚本调用；不改变财务写路由、Flyway 和安全授权政策。前端对 Session QueryClient 清理增加具体 V216 GET AbortSignal 的 Vitest 回归。**实际本轮权限专项通过**：[完整 A3 #45311583643（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45311583643)；PG18 原始日志包含 `PASS V219 real Spring/PG18 HTTP`，证明不是浏览器 Mock 403，且没有真实外部费用核证。

**V220 已在上方完成并由 PG18 A3 7/7 验收。** 接下来 V221 专注双 repeatable-read 快照与变更竞态；原有 V112 计划仍只是本地人工提案，真实 Amazon/银行/物流/关税证明与 G1/DEC 未完成。评分 70/100、realizedNetProfit=null、真实 OAuth OFF、生产 #104 NO-GO。

## V218 已实现并验收（[V218 精确提交 `f89fef2`](https://github.com/kunlong-luo/merchivra/commit/f89fef2d14c0ac52dd11b3dc8ed08035867af337) 的 [完整合成 A3 #45306587159（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45306587159)；另有 [V218 CI 优化 `c3f7418`](https://github.com/kunlong-luo/merchivra/commit/c3f74185efc56b37ffb0d67459b3490a085c7de2) · [快速 CI #45307894546 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45307894546) · [完整 A3 #45307894610（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45307894610)）

[V218 交互/缓存门禁与浏览器场景](../finance/v218-receipt-cost-interaction-and-session-fail-closed.md)：审计证据不允许在再展开或重新校验时保留旧人工候选金额，新增每次显式检查的 `inspectionRevision`（同时保留 tenant/Marketplace/receipt key）、原生 fetch AbortSignal 透传，读取/刷新阶段不渲染旧数据。沿用现有 Node24 原生 CDP + Next16/Chromium 合成测试，不新建测试框架，实际点击采购 V46 批次及财务 UUID 表单；未点击不请求、全程 GET；HTTP 409/403/404/401 失败后只显示阻断；重新成功要独立请求；切换 Base UI Marketplace 不泄露前站点候选原币，另一站点无提案显示 UNKNOWN≠0。此浏览器脚本模拟的 403/404 只证明 UI 失败处理，**不能**替代后端真实非 Owner 和跨租户授权专项负例。[V218 精确提交 `f89fef2`](https://github.com/kunlong-luo/merchivra/commit/f89fef2d14c0ac52dd11b3dc8ed08035867af337) 的 [完整合成 A3 #45306587159（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45306587159) 已在真实隔离 PG18/HTTP/Chromium/Next/Java/Compose 7/7 通过；Chromium 日志分别包含 Vite 和 Next 的 `Chromium responsive UI smoke PASS (synthetic backend only)`。[V218 CI 优化 `c3f7418`](https://github.com/kunlong-luo/merchivra/commit/c3f74185efc56b37ffb0d67459b3490a085c7de2) · [快速 CI #45307894546 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45307894546) · [完整 A3 #45307894610（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45307894610) 的 browser-layout Job 再次 SUCCESS，CJK 安装从 488 秒降至 9 秒，整个 Chromium Job 从 574 秒降至 88 秒；第二轮同 SHA [A3 #45307894610（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45307894610) 也已在真实 PG18/HTTP/Chromium/Java25/Next16/Compose **7/7 全部通过**，并在 PG18 原始日志打印 V215/V216 原币守恒、V92 互斥和匿名 Owner GET 401。

**V219 与 V220 已分别以真 Spring/PG18 HTTP A3 通过**，下一阶段 V221 的并发/ABA 不能用 50/51 上限 409 冒充。

## V217 已完成（合成验收） · Next.js 收货单级人工费用证据联动 · Next.js 收货单级人工费用证据联动（[代码 `a790f1c`](https://github.com/kunlong-luo/merchivra/commit/a790f1c297034e2a13fd66332717c3a67284161e) · [快 CI #45304018584 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45304018584)；[V217 同 SHA 完整 A3 #45304346700（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45304346700)）

[V217 界面与字段契约](../finance/v217-receipt-cost-evidence-workbench.md)：财务工作台在成本准备度区新增原采购 receipt UUID 主动查询，采购 V46 批次页新增“核对本收货单的费用来源”入口；选择 V91 来源 Marketplace 后按需执行复用的 V216 Owner GET。严格 Zod 对象在客户端拒绝任何假外部核证、COGS/利润/过账字段、原币超额、来源重复或 incomplete page；所有候选金额仅作为待核证的原币来源，不聚合币种、不产生新账本。TanStack Query 与会话/站点/receipt 分区缓存、只在明确提交后请求、409 显示错误而非局部成本，复用 Base UI 选择器。新增 Vitest 正反例；在代码 SHA `a790f1c` 上 pnpm/Next16 快 CI 已 SUCCESS，PG18 快 CI SKIPPED；`451cfa9` 同代码+文档 [V217 同 SHA 完整 A3 #45304346700（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45304346700) 7/7 SUCCESS，PG18 原始日志确认 V215/V216 合成回归与匿名 Owner 401。V217 专属按钮交互的完整浏览器 E2E 负例仍留到 V218，不能把 A3 基线验收扩大为外部财务核证。

**下一阶段 V218**：对 V216 Owner 权限与 GET 错误界面做真正 HTTP/browser 回归，核查本地财务来源失效后 stale UI 清空与跨租户/站点只读保护；G1 资本化/FX/退款口径与外部真实账单身份未完成以前，不讨论实际利润解锁。70/100、`realizedNetProfit=null`、OAuth OFF、生产 #104 NO-GO。

## V216 已验收 · V112 收货单费用证据读时失效与 V165 跨来源碰撞（[V216 精确代码 `64b7dd5`](https://github.com/kunlong-luo/merchivra/commit/64b7dd51cee3944888a31f34bdd6373c627c3847) · [同 SHA 快速 CI #45302466898 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45302466898) · [A3 #45302466911（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45302466911)）

[V216 契约](../finance/v216-receipt-local-cost-integrity-read-gate.md)：复用 V215 已落地的 V112 数据库提案及 V165 已有八类身份碰撞/共享结算报告行探测，新增 owner/no-store `GET /api/v1/finance/marketplaces/{marketplaceId}/purchase-receipts/{receiptId}/manual-cost-integrity`；每次读取按原源币、全 plan 的 receipt 分摊、来源版本、V92 互斥、PG 审计和 V165 潜在重叠独立双 RR 复算。最多 50 项，超额 HTTP409；**V20 receipt 只属于 tenant，Marketplace 限定的是 V91 费用源，不能误称 receipt 专属店铺**。新增 Java 守卫及真实 PG18 TEMP 原 SQL 合成回归，匿名新 GET 401。没有任何 V112 创建/修改 API 或凭证出站，局部候选金额不能参与真实 landed cost/净利润。精确 SHA 的 PG18 原始日志含 `PASS V216 PG18 exact receipt/V91/V92 evidence SQL` 和 `PASS V216 anonymous Owner GET 401`；真实合成 A3 7/7 SUCCESS，不证明外部费用真实性。

**V217 已转入上方当前开发项**；V216 留存为后端已通过的合成基线，真实外部单据及 G1 审核仍缺，不得转记已核证成本。

## V215 已交付 · 精确收货单级人工成本来源身份与双向防重复（[代码 `709fbd0`](https://github.com/kunlong-luo/merchivra/commit/709fbd0bd702af2694d9f351b6e0cbb367b7a6c1) · [快 CI #45295653983 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45295653983) · [完整 A3 #45295654148（7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45295654148)）

[契约与测试范围](../finance/v215-receipt-cost-claim-and-v92-exclusivity.md)：新增 Flyway V112（V91 同源不可变提案，精确 V20 receipt FK，原币单笔与总量上限、≤100 条、一次独立审核、PG 自动追加审计）。对 V92 的活跃 SKU/ORDER/PERIOD 分摊新增双向阻断，不让同一人工来源在新收货维度重复分配；既有 V92 历史迁移保持不变。新增 PG18 真实已安装触发器的 TEMP/ROLLBACK 合成负例脚本并接入完整 CI。**没有**新的对外人工写 API、没有 G1 核准的资本化/FX/期间规则，实际 COGS 和净利润仍 NULL。精确 SHA `709fbd0` 已完成 Java25/Next16/PG18/HTTP/Chromium/Compose 合成 A3 7/7；PG18 原日志含 `PASS V215 PG18 exact V112 triggers`。首次 A3 #45295153716 因 V91 合成 reviewed_by/reviewed_at/review_reason 缺失而失败，已在新 SHA 修正测试夹具并重测，不降低任何约束。

**下一阶段 V216**：独立只读核对 V112 最新状态、原币来源和 V92 历史冲突、跨不同 V91 来源的潜在重复发票；复用已有审批而不新增第二套认证。G1/DEC 决策和外部 Amazon/银行/WMS 原始来源未获核证前继续 #104 NO-GO，70/100。

## V214 已提交 · V91/V92 原币费用到采购收货单身份缺口（[代码 `9de829f`](https://github.com/kunlong-luo/merchivra/commit/9de829fd8d302663e7589c80b3ec5c920a5ea949) · [同 SHA 快 CI #45193406175 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45193406175)；PG18/A3 未执行）

[实现与反例](../finance/v214-manual-cost-receipt-provenance-hold.md)：在现有 V91/V92/186 和 V213 上做**只读**可追溯性审计，发现 V91 只存人工来源金额与 Marketplace、V92 仅可分 SKU/ORDER/PERIOD，均无原采购收货单身份；不能凭来源编号、采购 SKU 或相似时间把头程/进口税/FX 分摊到 receipt。V214 Owner/no-store GET 对 Marketplace 全体人工候选费用分类计数、审查状态、V92 活跃方案状态进行 bounded 双读检查；绝不提供采购收货单实际分摊金额和利润。新 Java 契约测试在精确代码 SHA 的快速 CI 已通过；PG18 专项在快速 CI 中 SKIPPED，真正 PG18/HTTP/完整 A3 还未执行；Next.js 按需展示尚未接入。

**下一步 V215/P0-B**：先获 G1/DEC 关于资本化、原币/FX、期间与退款回转政策；再设计可追溯采购收货单身份、外部费用单据核证、跨收货单金额守恒和去重审计。未取得真实可信来源时继续报告 UNKNOWN，不能以 V91 审核或 V92 APPROVED_UNVERIFIED 冒充已证实 landed cost。70/100、`realizedNetProfit=null`、真实 Seller/Ads OFF、#104 NO-GO。

## 已交付 · V213 全订单原采购收货容量与落地成本阻断（[V213 代码 `e560b63`](https://github.com/kunlong-luo/merchivra/commit/e560b63c9c56987811c98b4a793e72bcc69e4fde) · [同 SHA 快速 CI #45177212591 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45177212591) · [完整 A3 #45177212593（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45177212593)）

[详细契约](../finance/v213-global-receipt-capacity-and-landed-cost-hold.md)：复用 V212 原订单退款/批次证据和 V106 已有不可变领取记录，跨同租户所有订单重算同一采购收货单的原始领取数量（含历史 RETRACTED）；对比冻结 QC 接受量、原采购币种/参考单价版本及当前订单分量。合法共享批次不代表会计重复费用，采购 PO 单价不是已核证最终落地成本，无法推断逐件退货的原批次。所有真实 COGS、净利润保持 NULL；真实 Amazon/Ads OAuth OFF，70/100，生产 #104 NO-GO。 V106 已有 receipt 行锁及总领取不能超 QC accepted 的写时保障；本轮读时不重造约束，只在 V212 重算退货来源后，对原收货单的所有本地销售订单认领进行全局核对，超 1000 行 HTTP 409，跨两次 RR 读取出现差异必须重查。Next.js 在已有 V212 财务订单里按需展示全局共享、占用和参考价格；明确实际落地 COGS / 原批次退货归属 / 真实净利润全部未知。同 SHA PG18/HTTP/Chromium/Next16/Java25/Compose A3 7/7 SUCCESS，实际 PG18 日志 `PASS V213 PG18 exact global V106 receipt-capacity SQL`；其 TEMP V106 影子查询不是已经核验真实来源/落地成本的证明。

**后续 V214**：围绕原采购收货单、物流/关税/支付汇兑真实费用单据的唯一身份和资本化范围建立可审计缺证清单，复用 V91/V92 与现有结算行审查，避免跨期间费用重复扣为经营费用与入库成本；真实授权与 G1/DEC 许可前不能自动过账或假设金额。

## 已交付 · V212 退款 × 原销售批次 × 本地返仓数量包络（[V212 精确代码 `921c2e2`](https://github.com/kunlong-luo/merchivra/commit/921c2e250ad4554349fb290e777c650b279fc259) · [同 SHA 快速 CI #45170646181 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45170646181) · [完整 A3 #45170646128（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45170646128)）

[V212 财务数量来源契约](../finance/v212-refund-original-sale-lot-quantity-envelope.md)：不造新表、新内部认证系统或重复审批，直接复用 V210 全事件退款审核数量、V203/V106 采购批次原销售认领与原采购单价/币种封存、V171/V96 人工退货声称、V172/V97 本地包裹 ATTESTED/PENDING 见证。新增 Owner/no-store GET，先分别独立重算 V210 和 V203，在 bounded RR 源快照里读取**全部** V34 退款明细、V106 原认领、V97/V96 收货关系，每源最多 1000 行、越界 HTTP409 不显示部分金额，再执行 V210/V203 与原始源的二次完整读取；旧源、数量超额、SKU 改变、同原收据参考成本不一致均阻断。按原订单行显示累计退款与认领数量、声称返仓与本地收货数量、两类缺口和采购参考原币单价；**不把任一退货事件归属到某个原采购批次，也不乘出真实退货 COGS 或写库存**。Next.js V211 已审核退款单选择器按需调用，避免 100 单自动并发重算。正式利润、实际 COGS、银行和 G1/DEC 仍不可信，生产 #104 NO-GO、70/100、真实 Seller/Ads OAuth OFF 不变。 此版本精确 SHA 同时通过 Java25/Next16/Chromium/Compose/真实 PG18/HTTP A3 7/7；PG18 真实日志的 `PASS V212 PG18 exact V34/V106/V96/V97 source SELECT scope` 只验证 V212 原 SQL 作用域，脚本中的 V106 VIEW 为 TEMP 影子而非真实 V106 trigger 测试。匿名新接口 401 实测通过。

**后续 V213**：重点从真实外部物流/仓库回执追查退货实物与原销售批次的逐件归属、采购最终落地成本、退款/仓储/广告及银行结算的审计归属与 G1 跨期间确认。授权前继续只读缺证与冲突处理，不采用 FIFO 猜测或内部签名代替外部真实认证。

## 已交付 · V211 期间退款、跨期与重叠快照利润阻断（[V211 代码 `fec6371`](https://github.com/kunlong-luo/merchivra/commit/fec6371386e5f1a550be6b12856d627ad94b515f) · [同 SHA 快速 CI #45165293616 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45165293616) · [完整 A3 #45165293551（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45165293551)）

[财务契约与 Next.js 交互](../finance/v211-period-refund-profit-inclusion-gate.md)：V64 按订单 `order_time` UTC 选择固定订单集合，LIVE Finances 退款按 `posted_at` UTC 在不同期间出现，V91 费用另用人工业务日期。**不能将这三个日期轴合并，也不能从已有 V188 有符号销售收入里再扣一次退款。** 新增只读 Owner/no-store GET：先经 V188/194 验证冻结订单/原币成本来源，然后读取本期所有已分类 LIVE 退款（包括无 MATCHED 订单/多重归属/非 V64 冻结订单）、冻结订单跨所有时间的退款订单（含 V34 孤儿），以及其他重叠 V64 报表共同的订单。每期来源最多 1000 条、退款订单 100 单、快照重叠 100 条，越界明确 HTTP 409 而非截断金额。事务结束后逐订单调用 V210 做全量身份/金额/日期重算；跨期、来源漂移与快照重叠都有独立阻断码，重叠仅是不同报表版本，不等于已发生重复账本扣款。Next.js V64 利润中心 V188 下新增按需展开的 V211 诊断，禁止将 `refundAdjustedContribution` 与 `realizedNetProfit` 伪装成有值。尚无外部官方退款覆盖、真实物流实收、G1/DEC、银行核验；生产 #104 NO-GO、评分 70/100 不变。 同 SHA A3 7/7 SUCCESS，真实 PG18 日志含 `PASS V211 PG18 source-exact period/refund cohort/overlap SQL`，验证实际执行源码三个 SQL 原文、匿名路由 401；并不证明真实 Amazon、物流或成本来源。

**下一阶段 V212** 优先在明确 G1 决议与真实 Amazon/Ads/银行/第三方授权可取得后，继续退款 COGS 对真实原始批次唯一归属、跨期退款归属规则和真实可核验成本闭环，不再堆叠新的自登记真实性档案；没有获授权前只允许继续解决现有只读守恒与测试缺口。

## 已交付 · V210 全订单退款数量与原币守恒（[V210 精确代码 `dc7e1b3`](https://github.com/kunlong-luo/merchivra/commit/dc7e1b336bc5d1ac762c2172db657b5961a8bbdd) · [同 SHA 快速 CI #45159869191 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45159869191) · [完整 A3 #45159869175（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45159869175)）

[V210 退款 COGS 敏感度与整单数量核对契约](../finance/v210-whole-order-refund-cogs-conservation.md)：由此前 P0-D 的本地 WMS 身份准备链回到 P0-B 财务主线。**不新造 Flyway 表、退款过账服务或第二套审批**，直接用 V34/V35/V63/V197/V201。新增 Order/Marketplace/tenant Owner no-store 只读 API，一次 RR 快照读取当前 LIVE 订单行、该订单**所有** V34 已审查数量和已 MATCHED 的全部 LIVE `PRODUCT_REFUND` 事件，按原币保留带符号金额；1000 行每维上限，超出 409 fail-closed，不将当前页合计冒充整单。V63 查询放在快照事务外避免正常 409 导致 RollbackOnly/500；跨事件累计数量超额、老订单行数量变化、V34/V63 处置或数量漂移、缺任一审核、孤儿来源或重复关联令**整个订单的手工历史成本和声称返仓成本风险敏感度 = NULL**，不是 0。即使人工量钱守恒，`actualCogsReversal=null`、`realizedNetProfit=null` 以及外部 Amazon/银行/G1 审批硬阻断保留。

下一步 **V211/P0-C** 应把这份完整订单级退款数量/源状态与 V64/V188 版本化利润及期间收益阻断合并，着重识别跨期间成本/退款重复归属，绝不绕过完整财务来源认证或实际退款成本入账。精确代码 SHA 的 PG18 真实回归日志已包含 `PASS V210 PG18 exact full-order LIVE refund source SQL`，同 SHA A3 全部 7/7 SUCCESS；仍只是合成完整订单证据和只读成本敏感度，并非官方退款来源/实物/成本过账确认。正式评分 70/100、真实 Seller/Ads OFF、生产 #104 NO-GO 不变。

## 已交付 · V209 部署侧离线 PKIX/CRL 只读技术准备度（[V209 精确代码 `0a9dc09`](https://github.com/kunlong-luo/merchivra/commit/0a9dc0903a92b64d5891c03011f5b3ef85fbc560) · [同 SHA 快速 CI #45155665615 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45155665615) · [完整 A3 #45155665675（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45155665675) · [同 SHA Redis Session 集成 #45155665716 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45155665716)）

[离线证书链安全契约和部署方配置](../finance/v209-operator-offline-pkix-crl-readiness.md)：V208 的 tenant-enrolled Ed25519 签名并不证明机构的法定身份。V209 **不增加另一套 DB/内部 Owner 批准系统**，而是新增默认 OFF 的本地运维证书准入：部署人员配置受限 PEM 根 CA、发行证书包及 7 日有效的签名 CRL，运营商登记/承运交接独立 DNS 白名单；Java 25 只从本地文件构造证书路径并执行 PKIX 签名和有效期验证、SPKI SHA 对比、精确 SAN DNS、CRL 签名/撤销校验。每次 V208 Preview 已真实 Ed25519 核验后再做离线复核，所有无有效资料、失效/被撤销证书与旧来源都返回技术阻断。即使通过也只是 `OFFLINE_PKIX_AND_CRL_MATCH_TENANT_KEY_EXTERNAL_IDENTITY_UNVERIFIED`，真实身份、交接、Amazon 和实际 COGS 固定假；无新 Flyway、无本阶段持久性真值或公网调用。本轮同 SHA 快速 CI、Redis Session 及完整 Java/PG18/HTTP/Next/Chromium/Compose A3 已全部 SUCCESS，A3 7/7；V209 没有新的数据库迁移，测试的 PostgreSQL 18 工作流仅证明现有数据库/服务回归无破坏。

**下一阶段 V210 优先回到 P0-B 退款 COGS 与可审计利润的缺证盘点/守恒校验**，避免继续堆叠没有真实证据来源的本地认证环节。真正的 B2 外部签发方，需要维护者通过独立合法渠道获得真实 CA 和运营主体、证书吊销与实体交付证明；目标生产 #104 NO-GO、`realizedNetProfit=null`、Seller/Ads OAuth OFF、正式评分 70/100 保持不变。

## 已交付 · V208 声称外部签发方密钥/撤销与 Ed25519 原件签名复核（[V208 精确代码 `6678558`](https://github.com/kunlong-luo/merchivra/commit/66785586f1856fc5647c48bbf4a975152a16fce7) · [同 SHA 快速 CI #45150207475 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45150207475) · [完整 A3 #45150207574（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45150207574)）

[V111 原始签名契约、外部信任边界和 V1 canonical 原文](../finance/v208-claimed-issuer-ed25519-signature-integrity.md)：复用现有 WMS Ed25519 实现，不新建重复密码库。签发公钥来源仍是租户 Owner 自行登记，另一 Owner 仅能记录 `REVIEWED_UNVERIFIED`，永久撤销后旧签名不得复活。V111 数据库冻结两个不同类别的文件 SHA、订单、V204 出库 ID、V205 档案编号及源公钥；请求者必须独立于密钥登记人、原件上传人及档案提交人；每次读时由 Java 独立重算真正 Ed25519/规范化原文，绝不以数据库记录冒充 cryptographically verified。提供 Owner/Session/CSRF/no-store 登记、内部审核、撤销、导入及只读诊断。**身份/履约/实际成本 FLAG 固定假，COGS/利润不得写入。** 本轮精确代码 [V208 精确代码 `6678558`](https://github.com/kunlong-luo/merchivra/commit/66785586f1856fc5647c48bbf4a975152a16fce7) 的真实 PostgreSQL18/Java25/Next16/Chromium/Compose/HTTP 完整 A3 已 7/7 SUCCESS，PG18 日志含 `PASS V208 PG18 V111 exact source capture/key guards/view`；其中 SQL 只核实本地来源/租户/快照，真正 Ed25519 正反例由 Java 执行，均不建立外部 CA/企业真值。

下一阶段 V209/B2 只有维护者本地取得经授权的**真实外部签发机关/运营公司/承运机构**可核验证书链、信任锚和撤销记录后才能考虑独立核实，不能仅增加第三个 Owner 或提供一把自行登记的密钥。P0-B 真实退款 COGS、Amazon/Ads/银行核验与 G1 财务口径签署仍是核心目标；生产 #104 NO-GO、`realizedNetProfit=null`、正式评分 70/100 不变。

## 已交付 · V207 私有原件治理与访问审计（[V207 精确代码 `8eb1b86`](https://github.com/kunlong-luo/merchivra/commit/8eb1b86a26caf8f73149e9dab6215e65c9a2574d) · [同 SHA 快速 CI #45140330888 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45140330888) · [完整 A3 #45140331187（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45140331187)）

[证据法律保全、不可变访问审计、留存双人复核契约](../finance/v207-custody-original-retention-governance.md)：Flyway V110 复用 V138 的保留策略和现有 append-only，所有 V109 原件永久 `legalHold=true` 且 `deletionPermitted=false`；与上传人不同的 Owner 提议保留期限、另一名独立 Owner 只可记录 `REVIEWED_NO_PURGE` 或拒绝。真实 PG18 触发器读取 V109 源状态，禁止被撤销的签名/摘要冲突上继续批准；读取单文件 SHA、大小、类型时同步写入数据库自己捕获原始长度/哈希的不可变审计。增加 Owner/CSRF/no-store 容量摘要和元数据治理 API。**无 DELETE、无 hold 解除、无后台 purge、无真实第三方接口、无任何实际 COGS 过账。** 本轮精确代码 SHA 的 Java25/Next16/Chromium/Compose/HTTP 与真实 PostgreSQL18 完整 A3 已 7/7 SUCCESS，真实 PG18 日志 `PASS V207 PG18 V110 exact guards and source view`，覆盖源撤销、跨租户、独立提案/复核、PG 审计快照与法务保全/过账硬阻断；仍只有合成工程证据。

下一阶段 V208 仅在维护者本地许可及信任/隐私合规齐备时处理真实外部机构签发方/承运商核验回执（另需证书链/撤销复核）；真实 Amazon Seller/Ads OAuth 和 G1/DEC 会计授权未到位，仍须阻断完整净利润与生产 #104，正式评分 70/100。

## 已交付 · V206 外部运营商/承运原件内容独立 SHA-256（[V206 精确代码 `c85feba`](https://github.com/kunlong-luo/merchivra/commit/c85feba8cf77919e89a1626d9ddc0981f13deb74) · [同 SHA 快速 CI #45138354698 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45138354698) · [完整 A3 #45138354709（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45138354709)）

[契约、私有原件、服务端扫描与 PG18 重算](../finance/v206-private-custody-original-content-integrity.md)：复用现有 V52/V137 的 MIME 检测、2 MiB/ClamAV，而不是重复造安全上传组件。Flyway V109 用 PostgreSQL 18 `sha256(BYTEA)` 生成原件摘要，触发器对照 V205 **提交者声称的**运营商与承运文件 SHA，限同租户两种文件每类一份、单租户总量 256 MiB；读时重新 SHA-256、追踪 V203–V205 来源状态和复核过期，Owner/CSRF 仅私有 multipart 上传与 no-store 只读检查，绝不公开文件字节或声称外部主体已被认证。该精确 SHA 的 Java/PG18 V109/HTTP/Chromium/Compose A3 已 7/7 SUCCESS，PG18 实际日志含 `PASS V206 PG18 V109`，确认 SHA-256 原字节生成/读时重算、文件魔数、缺原件、跨租户、不可变与实际 COGS 硬阻断；工程通过不证明外部机构或真实 Amazon 核证。

下一阶段 **V207** 优先独立的真实外部注册机构/运营主体、承运签收来源认证回执及证据存储治理；合法的真实 Amazon 只读联调仅在维护者本地明确授权和 G1/G2/G3 审批后进行。当前 `realizedNetProfit=null`、真实 OAuth OFF、生产 #104 **NO-GO**、评分 70/100。

## 已交付 · V205 外部运营主体/承运交接审计档案（[V205 精确代码 `99ef9b9`](https://github.com/kunlong-luo/merchivra/commit/99ef9b988de101f3413e0821817071bcce85c370) · [同 SHA 快速 CI #45132910751 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45132910751) · [完整 A3 #45132910387（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45132910387)）

[契约、外部身份信任边界与来源冲突规则](../finance/v205-external-dispatch-custody-dossier.md)：新 V108 不再自造另一套 WMS 密钥、批准和真实库存账本；在 V204 当前签名出库观察上，登记外部运营者/承运原件的声称引用与 SHA-256，数据库自行冻结关联签名、仓库、出库编号和数量。运营商编号与承运交接单号全租户范围检测**相同外部引用的不同声称文件摘要**，独立 Owner 文件核对不得由档案提交人、WMS 签名导入人或公钥登记人执行；来源撤销/批次漂移后旧审查自动失效。Java/PG18 拒绝从内部审查状态升级外部身份、实物交接、Amazon 履约或实际成本。已通过精确代码提交的 PG18 V108 原始触发器/视图、合成 HTTP、Java/Next/Chromium/Compose 完整 A3 7/7；PG18 Job 日志包含 `PASS V205 PG18 V108`。用户未提供真实外部原件/可信核验渠道，**不能标记 B2 VERIFIED；本阶段交付是可审计、fail-closed 的取证准备链**。

下一阶段 **V206/P0-D/P0-A** 需要在真实维护者本地授权之后建立可信第三方登记机构与承运方核验渠道、来源原件留存/摘要重算、受控 Amazon 订单履约交叉核验和退款/运费证据链，另由 G1 审签入账成本/原币政策。生产 #104 继续 NO-GO、实际利润继续 NULL、评分 70/100。

## 已交付 · V204 签名出库观察与可重算失效链（[V204 精确代码 `f89485f`](https://github.com/kunlong-luo/merchivra/commit/f89485fe0b85cb63f7e12614f254c4ba57ed9be9) · [同 SHA 快速 CI #45122956620 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45122956620) · [完整 A3 #45122956610（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45122956610)）

[设计、规范化 Ed25519 字节契约、读时失效与安全边界](../finance/v204-signed-sale-dispatch-identity-gate.md)：不造第二套 WMS 公钥治理，沿用 V98/V101 独立密钥持有权证明、V203 的全批次归属校验和 V200 历史订单 SKU 门禁。V107 不可变签名出库观察在写时要求登记源已批准、密钥持有权已证明、V106 本地归属有效，按精确数量/仓库/订单/批次保存规范化签名与指纹；读时对来源撤销、归属失效、历史 SKU 漂移、原始签名字节与 SHA-256 独立重算。没有 Amazon 外部订单项履约 API 的真实授权与外部 WMS 法人/设备身份认证，仍**只得到 SIGNATURE_ONLY_UNVERIFIED，不得确认实际 COGS**。本轮精确代码提交的 Java Ed25519 真值负例、PG18 V107 真触发器和原 SQL、HTTP、Chromium/Compose 已完成 7/7 合成 A3；真实外部 WMS/Amazon 与 G1 审批仍独立阻断。

下阶段 V205/P0-D 应建立独立真实外部仓库运营主体/设备身份和具合法来源的发货/交付证明流程，另在维护者本地同意且权限、审计、合规完成后才安排 Seller/Ads 只读联调；真实账务、退款 COGS 和 G1 会计签署仍需单独批准。生产 #104 **NO-GO**，评分 **70/100**，已实现逻辑不代表真实利润完成。

## 已交付 · V203 销售出库对采购批次的不可变关联声明（[精确代码 `3d94550`](https://github.com/kunlong-luo/merchivra/commit/3d94550b9a2ef5a53c15db358b390ed88b60478b) · [同 SHA 快 CI #45116800143 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45116800143) · [完整 A3 #45116800085（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45116800085)）

[契约、数量守恒与审计边界](../finance/v203-sale-to-lot-link-integrity-contract.md)：优先复用 V48 pick line、V40 OUTBOUND、V46 QC 与 V20 receipt/PO 身份，新增 Flyway V106 的原子捕获和独立 PostgreSQL 重算视图；同一订单项/pick line/收货批次全历史数量守恒，独立追加撤销不释放旧数量，更新源数据需显示 STALE。Owner/no-store 只读 API 复核 V200 历史 SKU 映射。该代码精确 SHA 已通过快 CI 及 PG18/HTTP/Chromium/Compose 完整 A3 7/7；所有验证为合成隔离环境，不是实物发货与 G1 核证。本地声明和摘要不构成 Amazon 或外部 WMS 真实证明；不得生成实际 COGS/GL、退款冲回或已核实利润。

下一阶段优先 **P0-D/B**：实际外部 WMS 主体独立身份与出库履约证明、平台订单项↔外部运输/交付的来源认证、退款实收与 COGS 转回、头程/运费/关税归属/原币会计政策审批。需要正式 G1/G2/G3 审签；生产 #104 保持 NO-GO，实际净利润继续 NULL，评分 **70/100**。

## 已交付 · V202 本地收货与销售批次成本证据核对（[精确代码 `4d07d87`](https://github.com/kunlong-luo/merchivra/commit/4d07d87c92366b661ad9c5400b759ac73d98362f) · [快速 CI #45105511319 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45105511319) · [完整 A3 #45105511271（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45105511271)）

[技术边界](../finance/v202-order-local-batch-cost-provenance.md)：现有 V20 PO 单价/V46 QC 批次/V39 库存流水仅能证明**本地采购和操作**，不能匹配 Amazon 实际订单的出库批次。复用 V200 有效期订单 SKU 准备度，汇总同租户且收货在订单时间之前的候选本地记录，独立检测收货↔PO SKU/仓库/数量、QC↔receipt、QC_RELEASE↔accepted units 以及多原币/单价冲突；结果永久不允许 `saleLotAttributed=true` 或 `realizedBatchCogs` 非空。Next 复用订单成本面板展示，不堆盈利卡片。之后需要真实销售→批次签证、完整库存流转守恒和 G1/外部 WMS 证明后才能计算实际批次 COGS。本轮完整 A3 已 7/7 成功，但测试全程仅隔离合成，仍不能证明 Amazon 实际销售批次或 G1 认可的批次成本。此阶段不启用 Seller/Ads/生产写入。


## 已交付 · V201 跨退款事件数量守恒读时门禁（[精确代码 `c47873c`](https://github.com/kunlong-luo/merchivra/commit/c47873cd1ad3e9963f57c9d69dc19b2ba5a17c90) · [快速 CI #45024903306 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45024903306) · [完整 A3 #45024903265（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45024903265)）

沿用 [V34/V35/V63](../finance/refund-recovery-v63.md) 原审核/金额分摊/成本敏感度路径。在原 LIVE 订单有效数量变化后，所有退款事件的历史审核数量合计可能超额；已有 V34 写时校验并不足以保证过去的 V63 审核仍有效。[V201](../finance/v201-refund-cross-event-quantity-read-gate.md) 增加按整单与当前有效项重新读取的数量守恒，冲突时禁止展示 COGS 情景值与继续财务复核，保留完整人工审计。精确代码的完整合成 A3 已 7/7 SUCCESS，含真实 PG18 原 Java SQL 的数量冲突负例；不生成真实退款凭据或过账。下一阶段仍需核证采购批次、真实退货收货与第三方来源，生产不放行。


## 已交付 · V200 历史 SKU 成本歧义 fail-closed（[精确 V200 代码 `2715529`](https://github.com/kunlong-luo/merchivra/commit/271552911b55a451e66662cdf3aab94c2db21341) · [快速 CI #45023721155 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45023721155) · [完整 A3 #45023721170（7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45023721170)）

在已有 V19 生效期 SKU 成本与订单时点历史 SKU 映射上，修复仅按版本排序取首条而未识别冲突的风险：同一订单日期存在**多条有效 SKU 映射**或**多条历史成本版本**时，订单成本必须是 `INCOMPLETE`、`provisionalCost=null`；不能选任意成本。参见 [V200](../finance/v200-historical-sku-cost-ambiguity.md)。不改变已有历史迁移、订单/退款成本规则、实物库存和已冻结利润。精确 SHA A3 已在原 Java SQL 的 PG18 TEMP/ROLLBACK 场景验证多候选和缺成本，整体 7/7 SUCCESS。仍未核实采购批次实物成本/真实 Amazon/银行数据；下阶段 P0 应先补历史批次与退款数量唯一归属的独立证据，再考虑任何 COGS 冲回。


## 已交付 · V199（[精确 `30fd25c` 提交](https://github.com/kunlong-luo/merchivra/commit/30fd25c9205c10234fced28626d69e0df2b81889) 的 [完整 A3 #45022799341（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45022799341) 与 [快速 CI #45022799357](https://github.com/kunlong-luo/merchivra/actions/runs/45022799357) SUCCESS）

V198 完整 A3 已通过，但其 V54 报告 `declared_net` 原本允许 NULL；[V199](../finance/v199-settlement-declared-net-fail-closed.md) 禁止将**无声明净额**而其他两项金额相等的结算证据判为 `SOURCE_AMOUNTS_AGREE_UNVERIFIED`。复用现有审核与前端，不生成零值、过账或新审批。本次完整 A3 通过 Java/Next/Chromium、真实 PG18/Flyway、HTTP/Compose 合成回归；仍未验证真实 Amazon/银行来源或批准财务过账。

## 已交付 · V198（[精确代码 `11864a7`](https://github.com/kunlong-luo/merchivra/commit/11864a739451f0e91c0bd9639b2c4bfe441b518e) 的 [完整 A3 #45021678686（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45021678686)，及 [同 SHA 快 CI #45021678580](https://github.com/kunlong-luo/merchivra/actions/runs/45021678580) SUCCESS）

继续 P0-A 原始费用来源/结算归属校验：复用 V58 报告、V31 Finances `SETTLEMENT_ID` 和 V56 结算双人审核，新增 [V198 多重引用、重复报告、报告行数与混币完整性阻断](../finance/v198-settlement-source-claim-integrity.md)。新状态在源比较产生，而不是等到最终人工批准才发现；旧审批依现有证据状态变更而标 STALE。PG18 脚本使用 TEMP 和 ROLLBACK 执行实际 Java 比较 SQL。仍不尝试凭金额相等自动归属、生成真实净利润或放开生产。最终精确提交 `11864a7` 的完整 A3 已通过真实 PG18 的原 Java SQL 对照、Next/Vite Chromium、Java、React、HTTP/Compose 7/7；此前 `f5bc560` 因旧测试夹具不兼容失败，现已修复，记录见 [V198](../finance/v198-settlement-source-claim-integrity.md)。这些测试不代表外部真实性或生产批准。

## 已交付 · V197（[V197 完整 A3 #45020338812（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45020338812)）

V194/V105 7/7 A3 后进入**退款 COGS 与真实来源验证准备**。已沿用 V34/V35/V63/V170 的人工复核、历史成本敏感度和原币来源，新增 [V197 后端只读阻断与跨事务漂移检查](../finance/v197-refund-cogs-source-consistency-barriers.md)。先核对相同退款事件、订单、币种与有符号金额，变更后隐藏敏感度并显示 STALE；再列出缺审、旧证据、处置未定及外部/G1/实收/会计硬阻断。没有独立实收/第三方来源认证或财务规则批准前依然 `realizedNetProfit=null`，不做 GL 或库存过账。

下一步应优先让真实 Seller/Ads/供应链来源经过授权后的**独立来源证据**与合规 G1 决策进入已有账单关联机制，而不是复制另一套审批或增加盈利展示卡片。本阶段已在 [精确代码 `db9d88b`](https://github.com/kunlong-luo/merchivra/commit/db9d88be81ec326194242f75596661cb950cbea7) 的 [同 SHA 快 CI #45020338752](https://github.com/kunlong-luo/merchivra/actions/runs/45020338752) 及 [V197 完整 A3 #45020338812（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45020338812) 验收：Gradle/React/Next、PG18/HTTP、Chromium、Compose 的合成范围通过。真实 Seller/Ads/银行和外部实收仍为独立未知，不因此关闭 G1 或生产 #104。

## 最近已交付工程基线

- **V194/V105（完整 PG18/A3 7/7 已通过）**：版本化 V1 冻结订单/SKU 证据捕获和独立重算，显式隔离历史 V62 摘要与新指纹；金额汇总、CSV、V188 瀑布发现旧证据或错误时 fail closed。仍不能解锁真实净利润。[设计与测试边界](../finance/v194-versioned-profit-capture-fingerprints.md)。

- **V195（快 CI 已通过）**：本地 Session 5xx/代理失败不再误导为“无法验证身份”；区分 401、403、连接错误和无效响应，有限自动重试与展开排错。**用户本机后端 HTTP 500 根因未证实**，不能把 UI 错误态优化当后端修复。见 [本地启动排错](../development.md#本地启动时显示服务暂时未就绪)。
- **V188B**：只读原币期间成本瀑布，修复固定 V64 OpenAPI 九路由边界；仍不是已核验利润。参见 [V188 交付说明](../finance/v188-period-profit-cost-waterfall-preview.md)。
- **V189**：移除不可达 PR-only CI 分支判断，并按用户要求维护 **main 直接开发**。
- **V190**：`pnpm` 以外包管理迁移前，默认本地 Next.js 16 Web 和受控 Vite fallback 已以 7/7 A3 验证。
- **V193**：复用 V62 表、V64 批处理和 V192 只读守恒，Flyway V103 将已采集订单、SKU、已完成任务及审计历史锁为**数据库级不可变**，阻止跨批次晚插 SKU；[说明](../finance/v193-frozen-profit-snapshot-database-guards.md)。本地旧 SHA-256 不升格外部验真，真实净利润仍不可解锁。
- **V192**：基于现有 V62/V188 在后端只读 SQL 增加**逐订单—SKU 五项原币金额守恒**，不让相互抵消的明细异常进入期间敏感度；对应 [V188/V192 说明](../finance/v188-period-profit-cost-waterfall-preview.md)。仍不计算已核验净利润。
- **V191**：统一 Node 24 / **pnpm 12.20.0**、冻结锁文件、Docker 与 GitHub Actions，并在精确 [main SHA](https://github.com/kunlong-luo/merchivra/commit/8dab97147010fdc440ee81abcfa67cd1d1d57261) 完成 [完整 A3 7/7](https://github.com/kunlong-luo/merchivra/actions/runs/44882890834)。

## 近期必须补齐的验证与文档

1. **V194 / V104**：**已完成**：在 [A3 #45018945885](https://github.com/kunlong-luo/merchivra/actions/runs/45018945885) 对 `9cdb926` 真实 PG18/Flyway V105、匿名/Owner HTTP、历史旧证据和半指纹双向阻断及 V103 不变性完成隔离合成验收。仍需后续专项覆盖“完整可用订单/SKU 捕获”的真实全链路正例；测试绿灯不能证明真实费用来源。
2. **本地 HTTP 500**：取得用户本机 Spring/数据库日志与两条 curl 的可复现结果，定位真正故障；界面 V195 已友好化，但不是根因修复。
3. **文档治理**：每轮只维护 [单一当前状态](../current-status.md)、本计划、[ENG-001](../governance/engineering-standards.md) 与实际改变的运行指南；历史 Vxx 不再作为并列实时计划。

## 测试纪律与 NO-GO

每次 `main` 改动运行快速 CI；身份、租户、金额、库存、审核/迁移改变必须跑相应高价值负例，跨边界重大变更按实际风险运行完整 PG18/HTTP/Session/CSRF/Chromium/Compose A3。测试需要**记录执行的精确提交**，不能把上次 green 当本次 green。只读合成 GitHub Pages 不是真正 ERP 部署。真实 Seller/Ads OAuth、SP-API 出站/写入保持关闭，生产 #104 NO-GO。

跟踪：[全成本利润 #12](https://github.com/kunlong-luo/merchivra/issues/12) · [财务对账 #11](https://github.com/kunlong-luo/merchivra/issues/11) · [生产 G1/G2/G3 #104](https://github.com/kunlong-luo/merchivra/issues/104) · [需求决策 #15](https://github.com/kunlong-luo/merchivra/issues/15)。历史计划中已完成或关闭的技术 Issue 不要重复当成新 P0。
