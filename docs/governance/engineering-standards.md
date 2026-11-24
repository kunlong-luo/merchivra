# ENG-001 · Merchivra 工程实施与验收规范

**状态：CURRENT / 2026-11-24 · DOC-196**。现行执行规则的详细参考；Agent 必读约束见 [AGENTS.md](../../AGENTS.md)，具体事实见 [当前状态](../current-status.md)，任务顺序见 [当前计划](../roadmap/current-plan.md)。本文不是已签署的 G1/G2/G3 或生产许可。

## 1. 规范优先级与禁止偏离

1. 用户已明确的现行需求、业务安全/生产门禁、实际可运行代码与真实验证结果，优先于历史描述。
2. [AGENTS.md](../../AGENTS.md) 规定不可破坏的执行边界；本文件详细规定工程操作；[文档控制](document-control.md) 规定冲突裁决及证明格式。
3. [当前事实](../current-status.md) 仅陈述已有代码/验证/未知；[当前计划](../roadmap/current-plan.md) 唯一负责未来工作顺序。历史版本文章仅作考古和回归依据。
4. 对相互矛盾的**未签署**财务、税收、G1/G2/ADR 方案，不自行挑选或以实现推导出批准；记录开放决策与具体受阻功能。

## 2. 唯一技术栈和依赖规则

| 层 | 现行基线 | 不允许出现 |
| --- | --- | --- |
| JVM/API | Java 25 · Spring Boot 4.1.2 · Gradle Wrapper · Spring Security · Springdoc | Maven、第二个登录权限体系 |
| 数据 | PostgreSQL 18 · Flyway 新增迁移 · Spring JDBC / BigDecimal | PG17 目标回退、直接覆盖已发布 Flyway、Redis 作为财务事实源 |
| Web | React 19 · Next.js 16.4.5 App Router · TS6 · Node24 | Vue/Nuxt、默认 Vite 双 ERP、前端伪造后端身份 |
| 包管理 | pnpm 12.20.0 · 唯一 `apps/web/pnpm-lock.yaml` | 第二份 JS 锁文件、混用其它 JS 包管理 CLI |
| UI/API | Base UI · TanStack Query/Table · Zod · Springdoc/Orval | 无必要的自研 UI 套件、手写重复 API/Hook/DTO |
| 部署 | Next Node 3000 + Spring 8080 + PG18；Redis 8 按需 | 在 Pages 上开放真实 ERP；用公开 Actuator 代替私有健康检查 |

新增依赖前先查已有实现与成熟维护库，评估授权许可、维护状态、CVE、体积与升级成本。仅为领域特殊逻辑自研；不要为了“重构”建第二套财务来源、数据库、租户或通用请求层。新操作优先沿用真实 Spring Controller → OpenAPI → Orval → Zod 的合同链。**V64 固定九操作契约**不能在未完成独立合同变更验证时被无意扩大。

## 3. 日常工作流程（仅 main）

1. 检查 main 最新 HEAD 和相关业务模块、Flyway 表、接口、现有测试，先做“重复功能审计”。把目标写成可测的业务状态和证据链。
2. 最小范围直接编辑 `main`，提交前再次确认基准 HEAD，禁止 force push；提交信息说明变更领域、原因与安全边界。**不用例行开发分支或 PR**，但需要对危险更改进行独立审查或留受控记录。
3. 同一变更包含代码、回归测试、相关当前文档。**快速 CI** 后标记精确 SHA 和 run；失败时定位日志并修复，直到同一最终 SHA 符合门禁，不把中间失败记为通过。
4. 外部 Seller/Ads、银行与 WMS 真实证据不由代理自动测试；用户在本地明确授权前仅运行隔离合成链。真实上线始终受 [#104](https://github.com/kunlong-luo/merchivra/issues/104) NO-GO 限制。

## 4. 测试分层和变更类型

| 变更 | 同步必须做 | 不能冒充 |
| --- | --- | --- |
| 纯文档、非运行时代码 | 文档相对链接/现行约束检查，当前 SHA 快 CI | 文档绿灯是 PG18/真实上线验收 |
| 一般前端 UI / API 读取 | 类型、Vitest、Next 生产构建、跨权限/空/错/加载状态；必要时 Chromium | 静态展示覆盖生产业务 |
| Session/CSRF/租户/Owner | 401/403/409/跨租户/CSRF 负例、错误响应不泄露身份；必要时合成 HTTP/浏览器 | HTTP 5xx 可视为未登录、取消服务端授权 |
| 金额/分摊/退款/跨来源 | 原币六位数/符号/守恒、UNKNOWN/缺来源/双计/跨期、独立复核与历史不变性；必要时 PG18 Owner HTTP | 合成手续费 = 真实手续费 |
| Flyway/约束/并发/持久化 | 新迁移、正反向 DML 守护、隔离租户、幂等/重复提交/原始证据；合成 PG18/Flyway/A3 | Java 静态测试足以证明真实 PG18 执行 |
| Next Node/Compose/回退 | Next /api 同源代理、Spring 身份/DB 健康、Session/CSRF、Docker/Chromium/回退 | 只要构建通过即生产部署通过 |

复杂 PG18/HTTP 回归脚本必须维护成独立 `scripts/smoke_*.py` 等文件，CI 工作流使用短调用；不要无限向单个 `run: |` 区块追加内联 shell，避免工作流解析失败且 0 jobs。每次先核对 Actions 是否真实创建 jobs，再核对源码 SHA 与测试结论；零个 job 不算已验证。

完整触发条件及实际任务参照 [CI 策略](../testing/ci-policy.md)。每次记录：`commit SHA`、`run URL`、`run_attempt`、`completed/failed/skipped`、验证目标和限制。被 SKIPPED 的专项不得计为已验证。

## 5. 财务与库存的不可破坏不变量

- 每笔来源绑定租户、Marketplace、来源类型及原始标识、币种、时间、身份/摘要/金额。来源重复身份或证据不足进入 hold，不允许通过更改前端标签或复用他人审核绕过。
- **UNKNOWN ≠ 0**：空、缺项、无授权、不完整覆盖与跨币种无批准 FX 不能生成零成本、净利润或虚构利润率。预览金额和已核证账务分离；现金结算与利润分离。
- V64 冻结记录由 V193 数据库守护；V194 为新记录保存版本化 V1 捕获 SHA-256，V105 以显式非空约束堵住 V104 SQL CHECK-UNKNOWN 半指纹缺口；独立重算冻结行，历史 V62 摘要不能升格。异常的局部金额、CSV、V188 瀑布要 fail closed；按来源和原币保留调查证据。
- 费用先经过真实来源验证、重复归属处理、原币分摊守恒和独立审核，再讨论可过账与利润；`APPROVED_UNVERIFIED` 不等于 `VERIFIED`。真实外部核证、退款 COGS、会计口径仍待签署，`realizedNetProfit=null`。
- WMS 的内部 Ed25519 签名与 QC 只证明特定本地消息和审批步骤，不证明第三方物理实收；B2/B3 未完成前不自动改库存/COGS。
- **销售批次签名证据分级**：V106 本地订单项/pick/receipt 的三级数量守恒只证明本地声明；V107 对租户登记的 WMS 公钥做 Ed25519 签名校验和可重算归属也仅证明密钥控制权。签名绑定的事件、来源、数量、SHA-256、原币及历史 SKU 必须永久留痕，签名重放/源撤销/源变更均 fail closed；真实外部主体、实物出库、Amazon 履约与 G1 会计批准不得互相替代或自动提升为已过账 COGS。
- **第三方主体/承运档案不是验证**：V108 保存租户声称的运营商登记与承运交接编号、原件摘要及独立 Owner 内部文件复核；第三方机构回执、签收原件、业务身份、原件内容及 SHA-256 必须通过独立可信通道取证重算。相同来源引用的不同摘要全租户冲突一律 hold；来源撤销和本地签名漂移即时 STALE。任何 `DOCS_CONSISTENT_UNVERIFIED` 不能替代 B2 第三方主体认证或 G1 实际 COGS 审批。
- **原件内容 SHA-256 与来源认证分离**：V109 在现有 ClamAV fail-closed 及媒体文件魔数检查之后保存受限 BYTEA，必须由 PG18 从实际原件字节生成和读时重算 SHA-256；与 V108 声称的文件散列一致只能证明**字节与声明匹配**，不能代替发件方法人资质、运输交接或 Amazon 履约。只保留 Owner 私有原件元数据，没有下载/公开文件接口；文件容量、隐私、保留和 G3 风险长期维持 NO-GO，禁止变相通过合成文件计算实际 COGS。
- **V207 私有原件法律保全**：默认所有 V109 原件法律保全且不得清除，内部 Owner 仅能由非上传者提议期限、第二名与上传人/提案人分离的 Owner 作出 `REVIEWED_NO_PURGE` 或 `REJECTED`；绝不因此解除 hold、自动清理或升级为真实第三方认证。每次单文件 SHA/size/留存状态的 Owner 元数据读取须在同事务写不可变审计，SHA 与 size 必须由 PG18 从持久原件捕获；审计不能包含原件 bytes 或公开下载。目标环境 PITR、WORM、加密和真实权威机构核验仍待独立 G3 审批。
- **V208 自登记签发密钥的安全标签**：任何声称的运营商登记机关或承运商 Ed25519 SPKI 若由租户 Owner 登记，即使内部 Owner 复核、签名验证、原件 SHA 与出库/订单/文件绑定全部通过，也只能记 `SIGNATURE_VALID_FOR_TENANT_KEY_ONLY_EXTERNAL_UNVERIFIED`。公钥必须按签发类别隔离且不可重用、不可篡改，记录审核人与不可逆撤销；导入签名人须不同于登记人/原件上传人/档案提交人。读时完整 Ed25519 原文重算并传播原件/历史/密钥撤销。未接独立外部信任锚/签发方权威目录/证书吊销源前，一律不放开 B2 真实身份、Amazon 履约、财务 G1 COGS。
- **V209 外部证书链门禁不得来源于租户**：现实签发方必须另外经权威渠道认证；只在部署运维层设置只读 X.509 根证书、证书包、新鲜签名 CRL 和运营商/承运商独立精确 DNS 白名单，默认关闭，不允许租户通过 REST 上传/审批 trusted root。Java PKIX 从本地材料构造证书路径（禁止 AIA/OCSP/CRLDP 出站）、验证 Leaf Ed25519 SPKI SHA-256=V208 公钥指纹与证书 SAN；缺 CRL、已吊销、过期、错误根/指纹/角色、V208 本身失效均 fail-closed。通过仅表示运维提供的信任材料在技术上自洽，**不得**将其作为真实法人身份、实物交接、Amazon 履约、实际成本或利润解锁条件。
- **V210 全订单退款守恒不得按当前页认账**：保留现有 V201 V34 跨所有退款事件的数量上界、V197 来源 ID/订单/币种/原币 signed amount 对账、V63 独立审核；将所有 LIVE 已匹配的产品退款及原单当前 LIVE item 与所有 V34 原始手工数量冻结后完整聚合。任何超过 1000 行、重复源、订单行未知、累计退货数量超额、漏检或证据漂移必须 fail-closed，不能以当前 50 行或零取代未知成本；不同币种只保留各自原币总额，实际 COGS 和净利润均 NULL。V63 风险预览要在源快照事务外读，避免正常 409 污染事务为 500。内部审核与数量/金额守恒仍不能替代 Amazon、实际物理返仓和经 G1 批准的 COGS 入账。
- **V211 期间利润不能把不同日期轴或快照混作实账**：V64 冻结订单 `order_time` UTC、Finances 退款 `posted_at` UTC、V91 费用业务日必须分别呈现。先复用 V188/V194 校验冻结利润基线，再在 bounded RR 下读取整期已分类 LIVE 产品退款（含孤儿/重复匹配/非冻结订单），并检查冻结订单全部时间的 V34/V210 退款；不按当前页拼出“全期”。同一 Finances 退款即使匹配两条订单也只能计一次原始金额；同订单出现在不同 V64 报表，只是可重复的报告快照，不足以证明实际双重记账。V210 跨事务读时发现来源变化必须 fail-closed，外部数据/真实 G1 未核证前 `refundAdjustedContribution=null`、`realizedNetProfit=null`，不能从已有 V188 signed revenue 再次扣回退款。
- **V212 退款不能凭相同 SKU 猜原采购批次**：复用 V203/V106 原销售采购批次人工认领、V210 同订单全事件退款、V171/V172 本地收货数量，先按原订单行和每个 `refundEventId + externalItemId` 重新核验，再计算“可覆盖多少件”，不创建第二套账本/身份认证。原批次历史认领撤销不回收原物理额度；V203 当期 SKU 映射更严格时使用 V203 失效状态。ATTESTED_LOCAL 仅本地人工见证，PENDING 不算实收，复数包裹及收货 Claim 数量不能超过退款审核；原采购单价不等于最终入库 COGS，同 SKU 多采购批次/币种不允许 FIFO 猜测。全部真实退款 COGS/利润字段必须 NULL、不可入账。整个订单每类 1000 条有界、源漂移必须 409，不可用截断当前页凑合计。
- **V213 全订单原收货容量不可仅按退款订单估算**：V106 已按 receipt 行锁限制物理 QC 接受数量，需跨所有本地订单连同历史撤销 claims 独立读时汇总；同一 receipt 多订单合法共享不能直接视作重复账务，而跨订单超额、原采购单价/币种或 QC 版本漂移必须 fail-closed。绝不将原 PO unit cost 当含运费/关税/FX 的已核证 landed cost，也不能从匿名批次分布推导退货逐件 COGS；外部凭据/G1/DEC 不全时净利润与退货成本 NULL。

- **V214 人工费用不能自动落到原采购批次**：V91 人工发票/承运来源和 V92 原币 SKU/ORDER/PERIOD 分摊只属于未核证 Marketplace 元数据；它们没有 `purchase_receipt_id` 身份，也没有已签 G1 分摊会计政策。不得把 V92 `APPROVED_UNVERIFIED`、V20 原采购参考单价或 V213 全订单收货容量重命名成已核证 landed cost；V214 只读候选行检查需租户/Marketplace 限定、最多 1000 行、双读不一致 409，并持续固定实际 COGS/退款冲回/净利润 NULL。参见 [V214 契约](../finance/v214-manual-cost-receipt-provenance-hold.md)。

- **V215 收货单级人工费用计划不等于已核证 landed cost**：V112 单来源终生一份、同租户原采购 receipt 复合 FK、来源原币金额与收货单金额守恒、独立复核、不可变审计；与 V92 活跃分配在 V91 来源行锁下双向互斥。不同 V91 仍可能是相同外部发票，必须检查 V165/V166/V182；币种不一致不得隐式换汇，源状态或采购单币种变化只能阻断，严禁以本地 APPROVED_UNVERIFIED 解锁实际 COGS、退款回转或净利润。V112 没有公共写 API；使用 [V215 契约](../finance/v215-receipt-cost-claim-and-v92-exclusivity.md) 和 PG18 精确 SHA 测试为准。

- **V216 费用读取要复核完整计划与跨来源身份**：按同 tenant+Marketplace 的 V91 费用和 V20 receipt 精确主键限定，不能从采购 SKU、时间或页面前 50 行推断全量；V112 每 plan 全 receipt 分配/PO 现币种/来源快照/V92 活跃冲突和 V91/V112 审计需读时重算，最多 50 份计划，超限/独立快照漂移返回 409。复用 V165 八类潜在身份和共享结算行信号，不造重复审批或自证真实双扣；本地 `LOCAL_RECEIPT_ALLOCATION_INTACT_UNVERIFIED` 不能产生 landed COGS 或净利润。参见 [V216](../finance/v216-receipt-local-cost-integrity-read-gate.md)。

- **V217 前端费用证据组件不允许将未核证人工来源升格**：Zod 对 V216 返回强校验 `actualBatchCogs=null`、`realizedNetProfit=null`、已核证/已过账标志始终 false、最多 50 个唯一 source/plan、UUID 及 six-decimal 原币金额；禁止 JS 浮点跨币种归总。采购 V46 批次页只用精确后端 receipt ID 选择与 Base UI Marketplace 来源过滤；财务中心仅明确 UUID 输入后按需 GET。TanStack Query 必须包括 tenant/Marketplace/receipt 键、no-store/same-origin、关闭自动重试，切换站点与租户清除前次结果；401/403/404/409 不许回退缓存或显示部分成本。见 [V217 UI/测试契约](../finance/v217-receipt-cost-evidence-workbench.md)。

- **V218 活证据不允许旧成功值冒充当前重查**：来源风险、费用归属或原币结果每次手动展开/重新核对必须生成新的唯一读取版本，避免 staleTime=0 或自动 refetch 前短暂渲染缓存。审计查询须传递 TanStack Query 的 AbortSignal，读取和失败时禁止显示旧金额；跨用户/租户/Marketplace 不能携带旧 receipt 选中或请求。必须跑**真实 Next16 Chromium DOM + 本地合成 HTTP 409/403/404/401**；模拟 403 只能验证 UI 错误策略，不能替代真实 Spring Security 的 Owner 拒绝测试。见 [V218](../finance/v218-receipt-cost-interaction-and-session-fail-closed.md)。

- **V219 必须区分真实 Owner 授权与 Mock 状态**：对费用来源和收货证据权限验收，至少使用 Spring Boot 真实登录、独立 JSESSIONID/CSRF、一个员工 A/Owner B 的混合角色用户与另一个 A Owner，实际 PG18 两租户/同租户多 Marketplace/独立 V20 receipts。禁止凭前端 Mock 403 声称安全；检验 A 员工 403，A Owner B 来源 404，跨租户 receipt 404，合法 Owner 200+no-store 并且缺成本 UNKNOWN≠0。测试记录必须仅在严格限定的一次性 CI DB 创建且禁止外部授权；V219 已由 [完整 A3 #45311583643（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45311583643) 的真 PG18/Session 200/401/403/404 验收，仍不得把人工来源升格真实外部核证。参考 [V219](../finance/v219-real-owner-receipt-http-boundary.md)。

- **V220 非空 V112 本地审核来源不能解锁真实 COGS**：真 PG18+Spring Owner GET 要核验原币 40/90、V91/V112 双独立审核、V165 同站点 claimed SHA 仅 potential collision、其他 Marketplace 来源不混合、V92 trigger 禁止重复归属、PO 币种变更让旧批准读时失效；恰好 50 份必须全部 200、51 份必须 409 且无旧金额或部分结果。真实 COGS/netProfit 全程 null，任何 Mock/TEMP/快 CI PG18 skipped 都不能冒充实际安全验收。上述非空本地原币/来源/全量合同已由 [完整 A3 #45314064469（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45314064469) 的真 PG18 Job #138176547718 独立通过；不代表真实外部账单、COGS 或利润核证。见 [V220](../finance/v220-receipt-source-population-real-http.md)。

- **V221 V113 ABA 版本覆盖规范**：不得仅对两次 V216 RR Source/V165 对象 equals 就宣称已防 ABA。来源/身份/receipt/V92/V165 所有输入的可提交写入须经过版本触发器；每个 PostgreSQL 语句每个租户一次版本增长，回滚不计入，tenant 之间不互相增长。每次 V216 RR 同步读取 tenant/Marketplace/receipt scope、全部计划、碰撞和提交有序 revision；两次版本不一致优先 409，内容不一致也 409。新增 V165 来源类型必须同步迁移触发覆盖与并发测试。租户级合并写入可能增加锁竞争与误拒绝，真实负载测试属于未来生产准入；不能以降低 409 为由回退至忽略 ABA。真实 PG18 双连接 MVCC 与 Java 测试需要分别注明范围；[完整 A3 #45317628690（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45317628690) 已验证两连接真实 ABA 与服务单测，但**没有强制真实 HTTP 请求中途交错**，该负例仍归 V222。见 [V221](../finance/v221-receipt-source-committed-epoch-aba-guard.md)。

- **V230 费用外部凭证准备度只能组合已有本地信号，不能冒充认证**：本地 V91 初始/独立 reviewer 审计必须由 V229 在同一 read-only REPEATABLE_READ PostgreSQL 快照内先核对，再复用 V165/V94/V102/V182 已存在的结算/跨来源风险，不新增单人可将手工摘要或账单行匹配升级为真正 VERIFIED 的写入接口；本地审计缺失/重复优先阻断，拒绝/待审核/跨源冲突各显式区分。报告必须按8类来源列出不同的独立签发方、原文件字节与 SHA 重算、原币金额/货件/订单/退款 COGS、独立支付/重复扣款排除和 G1/DEC 会计批准。**非 Amazon 货代/关税费用不能被错误要求全部匹配 Amazon Settlement 行**。所有 `requiredExternalProof[].independentlyVerified`、`originalBytesVerified`、`issuerIdentityVerified`、`originalChargeVerified`、`eligibleForRealizedProfit` 等都保持 false，`realizedNetProfit=null`；Owner GET 401/404、no-store、同站点 UUID/原币响应与 Orval/Zod 校验。真 PG18 只可在一次性 CI 插入缺/多审计负例，不能在真实客户 DB 执行。首次 [V230 A3 #45367537947](https://github.com/kunlong-luo/merchivra/actions/runs/45367537947) 浏览器 Next 重挂载节点竞态导致6/7，失败保留；现已由[精确代码 SHA `a8e5eba353d5`](https://github.com/kunlong-luo/merchivra/commit/a8e5eba353d501de9761685cdd0d05de1d340982) 的 [完整 A3 #45368066154（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45368066154) 验收。真实 PG18 Job #138355672453 原始日志 `PASS V230 live Springdoc`、`V230_SOURCE_PROOF_DOSSIER`、`PASS V230 real PG18 Owner`，并重跑 V229 与前序业务来源负例；不代表外部真实账单或 G1 已获批准。现行[75/100 独立复评分](project-scorecard-v230-2026-11-24.md) 仅业务完整性 +1、真实财务仍10/25。见 [V230](../finance/v230-external-source-proof-dossier-and-owner-workflow.md)。

- **V229 精确 PG18 反证验收与审计计数不变性**：[代码 SHA `3c857c788b6e`](https://github.com/kunlong-luo/merchivra/commit/3c857c788b6eeac5db8fa770caff14b7a253ab0a) [完整 A3 #45363362444（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45363362444)，真实 PG18 Job #138338765615 在旧 V221–V228 回归之后打印 `V229_REAL_OWNER_AUDIT`/`PASS V229`：V91 合法 STAGED+1 audit 仅 local complete、0 audit 本地不完整、2 audit 重复被阻断；401/404/no-store、GET 不增加 V113 tenant revision；所有3类都不得核证外部发票签发方/收费/真实 COGS/净利润。安全维度因该缺失/重复反证从13提升14的 [独立评分 74/100](project-scorecard-v229-2026-11-24.md) **不是**外部真实性/生产准入凭据。不能使用 V92 append-only 当作 audit complete 证明，也不能在真实数据执行 disposable 脏审计测试。见 [V229](../finance/v229-source-review-audit-integrity-and-external-proof-hold.md)。

- **V229 本地来源审计必须独立核对 V91 的精确事件完整性**：V182/V183 已核对 V94/V102 结算行和复核，但它不能替代初始 `finance_cost_source_audit_v91` 本身的审计守恒。只读 REPEATABLE_READ 单语句按 Session tenant/Marketplace/source UUID 校验：STAGED 来源应有且仅有 1 个与 maker 匹配的 STAGED audit；本地已复核/拒绝状态应有 1 个原 STAGED + 1 个与独立 reviewer/当前决策匹配的 audit，总数恰好 2；任何缺少、多加、错误操作者、错误状态都不得报告 `localAuditComplete=true`。检查必须在**未拆分的同一 SQL 快照**完成，错误来源/跨租户/跨站点 404，GET no-store。**即使本地审计完整也永远不能把用户声明 SHA-256 解释为已验证的外部文件内容/签发方/银行扣款或解除真实 COGS 与净利润阻断**；现行 V91 审核、V92 追加保护、V182 结算归属审查均复用，不新建财务写表。真 PG18 单独构造 1/0/2 事件正反例及 HTTP401/404，严守 disposable。见 [V229](../finance/v229-source-review-audit-integrity-and-external-proof-hold.md)。

- **V228C 精确 Springdoc/Orval 验收**：[代码 `e268af7c9972`](https://github.com/kunlong-luo/merchivra/commit/e268af7c9972dcd5198c134187bb49879c1531c3) 的 [真实 PG18 完整 A3 #45360965067（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45360965067)，PG18 Job #138330697669 输出 `PASS V228`，独立证实 `ManualCostDraftV228`/`ManualCostSourceV228` 的真实 Springdoc Operation/Request/Response 与审阅 Orval 合同字段吻合，V221–V227 原始安全回归通过。保留前两轮 6/7 失败与原因，不能因媒体 key `*/*` 容忍错误 Source schema；此验收**不是**实际外部发票、COGS 或净利润确认。见 [V228](../finance/v228-manual-cost-orval-batch-ux.md)。

- **V228 Springdoc 嵌套 DTO 模型必须有唯一稳定 Schema 身份**：真实 Java `CostSourceReviewService.Draft` / `Source` 等简单嵌套名称可能与其他 Springdoc `Draft/Source` 同名而导致 HTTP 正常但 OpenAPI 的响应字段指向错误 DTO。对供 Orval 使用的模型显式 `@Schema(name="ManualCostDraftV228" / "ManualCostSourceV228")`，并在真实 Spring Boot `/v3/api-docs` 自动核验请求和响应的真实字段，而非只校验操作存在。对于未标 `produces` 的 POST，200 的 media type 可由 Springdoc 声明为 `*/*`；允许 JSON 兼容媒体键，但**不能跳过 Source[] 的原始身份/币种/摘要/金额字段校验**。首次 A3 [#45359294822](https://github.com/kunlong-luo/merchivra/actions/runs/45359294822) 是媒体键错误，二次 [#45360086678](https://github.com/kunlong-luo/merchivra/actions/runs/45360086678) 是响应 Source 字段缺口，均仅 6/7，不得当通过；以 V228C 精确 SHA 新完整 A3 为验收。详见 [V228](../finance/v228-manual-cost-orval-batch-ux.md)。

- **V228 手工费用批量导入必须经过 Orval、安全传输与完整响应校验**：仅在 Owner 财务工作台、当前 Session Marketplace 原币下粘贴不带表头的 6 列 TSV（category、sourceKind、sourceKey、claimed documentSha256、businessDate、amount），1–20 行；Zod 必须逐行验证来源类别/种类、编号唯一、非零 SHA、真实 ISO 日期、严格正数原币六位精度，任一错误整批不可提交。复用由审阅 OpenAPI 快照生成的 Orval client 和现有 `merchivraFetch`，不可创建第二套 HTTP、弱化 Session/CSRF 或让用户指定 tenant；服务端真实 Controller 的 operationId/request/response schema 应在 PG18 A3 通过 Springdoc 复核。HTTP200 后仍须校验 no-store、Marketplace/币种/原始身份 SHA/金额全量一致（BigInt 六位原币单位），不能把已审核历史 replay 冒充本批新建或外部 VERIFIED。409 必须保留输入并显示全批冲突，不能假装知道具体冲突行或发生部分成功；当前站点变更时必须清空预览。新 UI **只暂存 MANUAL_UNVERIFIED**，真实 landed/return COGS、净利润和生产 OAuth 继续阻断。见 [V228](../finance/v228-manual-cost-orval-batch-ux.md)。

- **V227 批量写入双 Marketplace 版本守恒、重放及锁等待已由真实 PG18 验收**：沿用 V226 Owner Session/CSRF/1–20 来源临时暂存合同，对每次新来源批次+2、全量重放+0、同源原 UUID+1 STAGED 审计作真 PG18 检查；对 20 条 0/50/100% replay，不可再产生新旧事实重复归属。独立 Owner Sessions 在同租户两个 Marketplace 共16批 ×5 新来源必须是**80 个 UUID/80 条独立审计 +32 版本**。外部 DB 事务真实持有 `FOR UPDATE` 时 Owner HTTP 必须可观察等待且释放后安全恢复；不能用强制取消 tenant epoch 来降低延迟。精确 SHA [`782c2121c2ce`](https://github.com/kunlong-luo/merchivra/commit/782c2121c2cee8091e9ae1ff5e976d59e4ad346a) 的 [PG18 A3 #45328944026（7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45328944026) 证明上述小样本；`pg_current_wal_lsn()` 全局区间字节**不等于单批可归因 WAL**，P95/P99 亦非生产 SLO。见 [V227](../finance/v227-manual-batch-scale-replay-and-lock-evidence.md)。

- **V226 手工费用源批量暂存不是批量审核/真实过账**：只在当前已认证 OWNER Session + CSRF 的既有 Marketplace 路径下允许最多 20 条已验证原币 V91 Draft。必须使用原有 Marketplace `FOR UPDATE` 串行化，同 `(tenant,marketplace,kind,key)` 严格比较不可变原币事实；完整重放既不新增来源也不新增审计/版本；同请求键重复或任一来源事实冲突整批拒绝。仅将新增来源做一条集合 SQL INSERT，并为每条来源生成独立 UUID 的 STAGED 审计，再用一条集合 SQL INSERT，同一 Spring 事务下原子回滚；绝不能 `JdbcTemplate.batchUpdate` 多语句误称“一次 V113 tick”。必须由真实 PG18 + Spring Owner/CSRF HTTP 证明 20 新源+20 audit=2 tick，纯重放=0，5旧+3新=2，非法/冲突整个 batch 无部分写入，跨租户、非 Owner、匿名、缺 CSRF 被挡，V216 净利润/COGS 仍 null；不调整 46 triggers、不更改 V91 一次审核、V165 冲突及 V113 ABA。详见 [V226](../finance/v226-bounded-owner-manual-source-batch.md)。**已验收**：[精确代码 SHA `81ea17187dd`](https://github.com/kunlong-luo/merchivra/commit/81ea17187dd693da3c12344aa5e6a6da49c7cf7e) · [完整 A3 #45326774310（run_attempt=2，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45326774310)，PG18 Job #138218184069 证明 20 新源 +20 STAGED audit=2 tick、完全回放 0、新旧混合=2、409 原子回滚、Owner/CSRF/tenant 阻断与 V216 真实 COGS/净利润 NULL。run_attempt=1 的独立 Docker/maven HTTP429 导致 6/7，已在同一 SHA 单独重试 mock-chain Job #138219684488 SUCCESS；失败历史保留。

- **V225 租户级来源版本必须先验证批量语句守恒与跨站点 ABA**：针对全部 15 个受保护表，逐表核对 INSERT/UPDATE/DELETE transition triggers，不能靠 46 总数掩盖某张表缺保护。V91 本地来源记录的事实不可变、审核只能独立一次转换，`review_state=review_state` 必须由既有 BEFORE UPDATE 拒绝；无变化 SQL 不等于业务允许的幂等操作。PG18 中同事务 20 条来源按单条集合 INSERT+DELETE 必须仅 2 tick，20+20 单行 SQL 必须 40 tick，所有财务事实最后仍 0 条；失败 SQL/ROLLBACK 不增长。真实 V91 跨站点长事务锁等待和跨租户独立提交，以及真实 Owner 双 RR 内 INSERT→DELETE 两次已提交的来源 ABA 409、异租户 200 不得削弱。4 次低样本耗时和非强制 80 GET 混合结果不是生产吞吐/SLO。**先观察，不能擅自优化其余 13 表或删除 version guard**。见 [V225](../finance/v225-epoch-bulk-write-long-transaction-and-source-aba.md)。**已验收**：[代码 `e360e74be8`](https://github.com/kunlong-luo/merchivra/commit/e360e74be8177d472434723b3516fd6340e918c0) 的 [完整 A3 #45325472831（7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45325472831)，PG18 Job #138213939404 证明 20 行集合 SQL 2 tick、40 个单行 SQL 40 tick、真 V91 ABA 3/3 Owner HTTP409、32 个同时运行的批量事务全局严格 +64；首次 [A3 #45324675583 6/7](https://github.com/kunlong-luo/merchivra/actions/runs/45324675583) 的并发**测试错误**须保留，不能当版本丢失或生产被优化。

- **V224/V115 PostgreSQL transition relations 操作范围必须由 PL/pgSQL 外层 IF 保证**：旧 V114 将 `TG_OP='UPDATE' AND ... old_rows` 放进同一 IF 表达式，实际 PG18 在 INSERT 对其求值并报 `relation old_rows does not exist`；必须通过前向 V115 的独立 `IF TG_OP='UPDATE' THEN` 限定 UPDATE-only OLD/NEW，不能假定 SQL/PLPGSQL 布尔表达式一定短路或篡改旧 Flyway。真实 Marketplace INSERT/DELETE、真实 Owner HTTP、回滚与 ABA 均纳入 A3；首次失败 [#45322692498](https://github.com/kunlong-luo/merchivra/actions/runs/45322692498) 仅 5/7，不能算验收；前向 V115 修复后以 [精确代码 `74a2d04b9`](https://github.com/kunlong-luo/merchivra/commit/74a2d04b93cac371396ccf4f9299e6b65f75f5ce) 的 [完整 A3 #45323010754（7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45323010754) 真正证明 INSERT/DELETE、无变化 UPDATE、ABA 和真 Owner HTTP 正反例。

- **V224 相同数据 UPDATE 版本减噪仅允许严格全行等价**：Flyway V114 只为 `erp_purchase_order` / `seller_marketplace` 的 UPDATE 检查 transition OLD/NEW 全可见行 JSONB 多重集的**双向 EXCEPT ALL**；仅完全相等时不写 tenant epoch，其余 13 表与所有真实变化/回滚/租户隔离/tenant.status 和 V165 仍遵守 V113 提交有序规则。必须以**真实 PG18** 测零行/相同行/两行多重集/真正变更 ABA/rollback 和跨站点真实锁等待，再以**真实 Spring Owner HTTP 两次 RR** 同步负例证明无变化不误409、真正的另站点 ABA 仍409；非强制合成混合读写比率不得冒充生产自然409。V114 不能覆盖 V113 已发布历史，不能为了性能放松 HTTP 409 fail-closed。详见 [V224](../finance/v224-identical-update-epoch-and-concurrency.md)。

- **V223 租户 epoch 性能测试不得弱化安全**：复用 V219–V222 的仅 main GitHub Actions disposable PostgreSQL18/真实 Owner Session/CI-only 双 RR 边界，按真实 V91 statement triggers 的 INSERT→DELETE 提交测同租户单站点、多站点、跨租户 TPS、p95/p99 和锁等待采样，每事务 V113 tick 必须守恒；跨站点无关写入导致强制双 RR HTTP409 是目前保守行为。把强制交错的 409 条件比例与自然运营流量的 409 比率**明确分开**，不得以 A3 CI 的小样本声称已达到生产 SLO。V113 是历史 Flyway，不能原地修改；任何细粒度版本优化必须证明共享 PO/receipt、tenant.status 与跨站点 V165 的依赖覆盖，并新增事务提交/回滚、A→B→A 和真实 Owner HTTP 409 的独立负例；此后才评估取代租户级热点。详见 [V223](../finance/v223-tenant-epoch-contention-and-cross-marketplace-409.md)。

- **V222 真实在途 HTTP 测试 Bean 不得进入任何正常环境**：仅 `ci-v222` Profile + 显式 `merchivra.ci.v222.read-boundary` + GitHub Actions 本仓库 main 的固定一次性 localhost PG18 及禁 OAuth 全门禁满足才注册，且只对固定虚构 receipt 启用事务级 advisory-lock 同步。非法环境尝试启用应启动失败；禁止暴露额外调试 Controller、账户或生产可操作开关。真实 HTTP409 必须在第一 RR 已提交后由另一个真实连接提交 USD→EUR→USD 再释放的隔离 A3 验收，不得用 Java Mock 或 51 计划上限 409 冒充。V222 此门禁及真实 HTTP ABA/409 已由 [完整 A3 #45318994059（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45318994059) 7/7 SUCCESS、PG18 Job #138192661605 的 `PASS V222 REAL Spring Owner HTTP ABA` 实际验收。见 [V222](../finance/v222-real-http-receipt-aba-interleave.md)。

## 6. UX、会话与本地失败处理

- 用 Next.js 16 正式工作区设计；优先复用 Base UI 和现有设计 token。需检查 360px/桌面自适应、键盘焦点、对比度、数据来源、用户动作反馈、空态和错误态。
- 会话加载必须阻止显示旧租户缓存；切换登录身份必须清除业务缓存。401 是 guest，可显示登录；403 是权限问题；5xx/代理失败是服务不可用，保留错误状态及可重试控件，**不得假设错误=登出**；200 解析失败也不能放行。
- 本地 `pnpm dev` 仅启动 Next。先检查 PG18 和 Spring 8080 再查 Next 3000 同源 /api。对 HTTP 500 必须结合服务端日志定位：界面改善**不代表根因已解决**。见 [开发排错说明](../development.md#本地启动时显示服务暂时未就绪)。

## 7. 文档同步与交付完成定义

| 发生变化 | 需要核对的文档 |
| --- | --- |
| 默认框架、端口、运行脚本/配置 | `README.md`、`README.zh-CN.md`、`docs/development.md`、`docs/architecture/overview.md` |
| 已实现功能、已知缺口、CI 精确结果 | `docs/current-status.md`、`docs/testing/ci-policy.md` |
| 优先级、剩余 P0、阻塞因素 | `docs/roadmap/current-plan.md` |
| API/权限/币种/数据库证据变化 | `docs/api/README.md`、安全/财务相关规格、领域版本记录 |
| 规范/贡献方式/文档分类 | `AGENTS.md`、本文、`CONTRIBUTING.md`、`docs/governance/document-control.md`、`docs/README.md` |

**完成定义**：已有可读代码/文档、聚焦安全负例、当前版本成功的合适测试、没有把外部未知改为“已核验”、当前文档与真实代码吻合。若不能跑完整 A3，必须明确写**未跑/待办**，不能假定通过。
