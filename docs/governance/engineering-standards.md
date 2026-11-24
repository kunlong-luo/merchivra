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

- **V220 非空 V112 本地审核来源不能解锁真实 COGS**：真 PG18+Spring Owner GET 要核验原币 40/90、V91/V112 双独立审核、V165 同站点 claimed SHA 仅 potential collision、其他 Marketplace 来源不混合、V92 trigger 禁止重复归属、PO 币种变更让旧批准读时失效；恰好 50 份必须全部 200、51 份必须 409 且无旧金额或部分结果。真实 COGS/netProfit 全程 null，任何 Mock/TEMP/快 CI PG18 skipped 都不能冒充实际安全验收。见 [V220](../finance/v220-receipt-source-population-real-http.md)。

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
