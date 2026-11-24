# Merchivra · 现行 Agent 与开发守则

> **CURRENT · 2026-11-24 / DOC-196。唯一生效的 Agent 指令入口。** 旧 `Agent.md` 只是跳转；V1–V195 版本说明和 Git 历史是证据，不是叠加的永续指令。发生冲突先核对实际代码、当前决策和精确 SHA 的测试，按[文档控制规则](docs/governance/document-control.md)处理。此文件规定执行边界；具体实践见[工程规范](docs/governance/engineering-standards.md)。

## 必须遵守的工程基线

- **Java 25 / Spring Boot 4.1.2 / Gradle Wrapper / PostgreSQL 18 / Flyway**；不引入 Maven 或 PostgreSQL 17 作为目标。
- **React 19 / Next.js 16.4.5 App Router / TypeScript 6 / Node 24 / pnpm 12.20.0**。唯一 JS 锁文件 `apps/web/pnpm-lock.yaml`。只用 pnpm 命令及冻结安装；默认 `pnpm dev`、`pnpm build` 是 Next.js。Vite 仅为受控回退和虚构的公开 Pages 展示。
- 后端 Session/CSRF/Owner/租户/Marketplace 执行权限；OpenAPI/Springdoc + Orval、Zod、TanStack、Base UI 优先复用现有成熟组件。Redis 8 可选，不得新增平行事实库、第二套鉴权或重复手写通用 HTTP 客户端。
- 日常功能、修复、文档和依赖变更**直接在 `main`** 进行，不创建例行功能分支/PR。更新前检查 `main` HEAD，提交时保护并发更新，**禁止强制覆盖 `main`**。不用 PR 不意味着省略审核、测试或安全门禁。

## 绝对不可突破的业务与安全边界

1. 项目 **pre-alpha**，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。真实 Amazon Seller/Ads OAuth、实际 API 出站/写入和真实银行核证关闭；只能使用明确隔离的合成数据。未经维护者本地明确授权、外部合规与 G1/G2/G3 审批不得擅自开启。
2. **UNKNOWN ≠ 0**；跨币种不得未经批准合并；金额使用 BigDecimal/PostgreSQL NUMERIC 和原币精度，保留报告/行/来源版本、归属、审计及独立复核。人工 `APPROVED_UNVERIFIED` 不代表外部真伪，不自动入账。
3. V64 是**已知范围贡献**，V188 是**原币敏感度预览**；V194 指纹只证明本地冻结数据能独立重算；V105 以严格 NULL-safe CHECK 修补 V104 半指纹漏洞，不能证明来源真实性。退款 COGS 回转和 G1/DEC-06/07/08 尚未签署；`realizedNetProfit=null` 必须继续保持，不得用合成费用伪造“净利润”。
   **退款期间守恒**：V64 订单创建时间、Finances 退款 `posted_at` 和 V91 费用业务日期互不等同；V210/V211 只核对本地来源，不能将退款从 V188 已有的有符号收入再扣一次，也不能将多个相同订单的 V64 快照直接相加成正式净利润。
   **退款批次成本边界**：V212 仅核对原销售已认领的本地采购批次数量与本地人工退款/返仓数量，不将同 SKU 的退货件自动归属到任一原采购批次；V106 原采购单价、内部收货审批或 V203 本地认领不足以计实际批次净入库 COGS。缺外部实物来源和 G1 审计时真实 COGS、退款回转、净利润始终 NULL。
   **V213 原采购收货跨订单容量**：不能只看一个退款订单就假设本地采购收货无其他订单领用；沿用 V106 不可变 claim/历史撤销的全局容量约束独立复核，原 PO 采购单价非已核证含运费/进口税/FX 的最终 landed cost，内部共享原收货单不等于会计重复入账。
   **V214 人工采购费用限制**：V91/V92 的运费/进口税/汇兑人工来源只按 Marketplace 原币和 SKU/ORDER/PERIOD 关联，缺原采购收货单 FK；V214 只能读候选元数据及费用分摊状态，不得按相同 SKU/日期自动嫁接 receipt，不得推导实际落地 COGS 或可入账利润。证据缺失/漂移按 UNKNOWN/409 阻断。
   **V215 收货费用归属门禁**：Flyway V112 仅建立 V91→本地 V20 receipt 的单来源一次性原币提案、独立复核、审计和 V92 双向互斥。无真实外部发票签证或 G1 会计批准时，不得开发绕过 V112 唯一归属/数量守恒的写接口，不得把 APPROVED_UNVERIFIED 计为实际 landed COGS/净利润。
   **V216 独立只读财务源门禁**：V112 人工收货级费用需按 tenant+Marketplace 原 V91 来源、全计划 receipt 原币金额、现 PO 币种、V92 冲突、V91/V112 PG 审计重新复算；V165 已有八类来源碰撞复用，不建新审批。上限 50 计划、超限/快照漂移 409，缺来源 UNKNOWN≠0；本地人工复核或 V165 无冲突不能解锁真实 landed cost、COGS 或净利润。
   **V217 Next.js 采购／财务本地费用查验**：复用 V216 Owner GET，必须先由用户选精确原收货 ID 和 V91 来源 Marketplace 再读取；TanStack Query 使用 tenant/Marketplace/receipt 键，使用 no-store/same-origin，切换上下文清空旧证据；Zod 必须拒绝任何 actual COGS/net profit 非 NULL、已核证/已过账 true、重复/超限来源及原币越界。不得从人工分配候选金额推导财务实际利润，不新增平行来源写入接口。具体见 [V217 契约](docs/finance/v217-receipt-cost-evidence-workbench.md)。
   **V218 审计读取新鲜度/HTTP UI 阻断**：每次用户主动收货费用核查必须创建单独 Query revision，旧成功缓存不可在刷新、错误或跨 Marketplace 视图显示；fetch 需传 TanStack 的 AbortSignal。真实 Chromium 操作采购 V46 行和财务 UUID 表单，模拟 401/403/404/409 均须失败封闭；Mock HTTP 403 不能冒充 Spring Security Owner 授权已验收。复用现有 CDP/Next/PG18 测试，不引入新前端测试工具。见 [V218](docs/finance/v218-receipt-cost-interaction-and-session-fail-closed.md)。
   **V219 真 Spring 角色/租户隔离优先于前端模拟**：新增敏感 GET 必须用真实 Boot Session+CSRF 与 disposable PG18 校验 Owner/非 Owner 的 401/403、跨租户 Marketplace/receipt 的 404、合法同租户 Owner 的 no-store 200 与成本 UNKNOWN≠0。单一用户在 B 有 Owner 不能借该权限读 A 的员工作用域。测试固定仅 CI loopback/一次性 PG18 且不保留外部费用或平台凭证，保留前端会话取消旧 Query 的 AbortSignal；新 A3 真实日志是唯一成功依据。参见 [V219](docs/finance/v219-real-owner-receipt-http-boundary.md)。
   **V220 非空人工成本原币读时门禁**：真实 Spring Owner/PG18 的 V91/V112 来源必须审核、原币 receipt/all receipts 数量守恒；用户声称 SHA 与 V165 同站点碰撞只归为潜在冲突，V92 触发器拒绝同 source 活跃归属，PO 原币改变必须令既有来源 stale。50 个 plan 完整 GET 200，51 个计划 GET 409 不得返回部分账本。不可把已审核内部费用当作已核实外部 landed cost 或利润；记录精确 A3 PG18 成功证据，快 CI SKIPPED 不算通过。见 [V220](docs/finance/v220-receipt-source-population-real-http.md)。
   **V221 事务提交顺序的 ABA 保护**：不能仅因双 RR 读取结果 equals 就假定 source/receipt/V165 在间隔中未变化；V113 tenant committed epoch 覆盖全部直接证据和 V165 本地碰撞输入变更，Java 在每个 RR 同视图检查 scope/plan/flags/epoch。提交 A→B→A 的 epoch 不同必须 HTTP 409，事务回滚不应留痕版本；跨租户隔离；新增 V165 来源要扩展触发器及测试。真实 PG18 两连接并发和 Java 409 单测分开陈述，不得伪称强制 HTTP 请求交错；外部真实财务核证、利润和生产权限仍关闭。见 [V221](docs/finance/v221-receipt-source-committed-epoch-aba-guard.md)。
   **V222 真 Owner HTTP ABA 409 隔离回归**：只在固定 GitHub Actions main disposable PG18 中以 Spring ci-v222 Profile + 明确开关 + bootstrap/DB/OAuth OFF 多门禁注册 CI 测试组件，正式环境绝不注册。指定合成收货单的真实 Owner Session GET 在第 1 RR commit 后等待，另一 PG18 会话提交 PO USD→EUR→USD，放行第 2 RR 必须 HTTP409，错误体无旧计划/COGS/净利润。不能用 V221 数据库交错或 V220 51 条上限代替真实 HTTP 请求级测试。详见 [V222](docs/finance/v222-real-http-receipt-aba-interleave.md)。
   **V228 Springdoc 源类型碰撞负例**：当真实 Springdoc 的 200 schema 与 Java `Source` 记录有属性缺失时，绝不可删除校验而放行生成客户端。必须使嵌套 `Draft/Source` 使用独立 `@Schema(name)`，真实 A3 对照服务实际 OpenAPI 的来源 ID、Marketplace、金额、币种和 claimed SHA；HTTP 响应媒体可能声明 `*/*`，允许媒体匹配但仍强制完整来源字段。V228 两次 6/7 失败证据分别在 [A3 #45359294822](https://github.com/kunlong-luo/merchivra/actions/runs/45359294822) 与 [#45360086678](https://github.com/kunlong-luo/merchivra/actions/runs/45360086678)，均不能算通过。
   **V228 手工费用 Orval/TSV 录入安全合同**：只复用 V226 Owner 1–20 来源 POST 的真实 Session/CSRF 交易，在 Next 财务页按当前 Marketplace 原币解析六列 TSV，Zod 校验 SHA/唯一编号/真实日期/正金额；Orval 生成 API 必须通过现有 `merchivraFetch`，不得编写平行 fetch、发送浏览器 tenant 或把手工来源升为 LIVE/已核验。响应需按原始顺序校验全部身份、金额与 no-store；历史回放不能计为新建，409 不得显示部分成功；切换站点清空输入。PG18 A3 必须核对 Springdoc 正式 Controller 操作及既有 V226/V227 ABA/租户审计回归。见 [V228](docs/finance/v228-manual-cost-orval-batch-ux.md)。
   **V227 已验收安全批量规模与多站点真实 HTTP 合同**：真实 Owner Cookie/CSRF 对 1/5/20 条全新及纯 replay、0/50/100% 混合 replay 保证新批次 V113 +2 / 纯 replay +0、无重复 STAGED 审计；2 Owner Session ×2 同租户 Marketplace 共16批/80条 V91 来源必须全局+32 tick；真实 `seller_marketplace FOR UPDATE` 锁阻塞/释放恢复不允许提前插入或绕过租户权限。精确代码 [`782c2121c2ce`](https://github.com/kunlong-luo/merchivra/commit/782c2121c2cee8091e9ae1ff5e976d59e4ad346a) 的 [PG18 A3 #45328944026 7/7](https://github.com/kunlong-luo/merchivra/actions/runs/45328944026) 已验证；4次短样本和全局 WAL 区间不能当生产 SLO 或单批 WAL；见 [V227](docs/finance/v227-manual-batch-scale-replay-and-lock-evidence.md)。
   **V226 有上限暂存 API 安全边界**：批量暂存只能复用原 V91 Owner/Session/CSRF、marketplace 行锁、唯一来源 key、完整不可变事实与来源审计，一次最多 20 行。整批不合规则原子 400/409，严格重放 0 新来源和 0 新 audit；新来源仅 STAGED/MANUAL_UNVERIFIED，每源有独立 audit、事务中集合 V91 INSERT/集合 audit INSERT，不准绕开 V91、V92、V165、V113，不能自动生成实际 COGS、净利润、真实财务确认或开启 Seller/Ads OAuth。真 PG18/HTTP 的 Owner CSRF、不可重复归属、回滚、跨租户与独立 ABA 负例必须通过精确 SHA 全 A3。见 [V226](docs/finance/v226-bounded-owner-manual-source-batch.md)。**已验收**：[V226 SHA `81ea17187dd`](https://github.com/kunlong-luo/merchivra/commit/81ea17187dd693da3c12344aa5e6a6da49c7cf7e) 的 [完整 A3 #45326774310（run_attempt=2，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45326774310)，真 PG18 20 源+20 独立 audit仅 2 tick、幂等回放 0 tick、冲突全回滚及权限负例全部通过。首轮 mock-chain Maven Central 429 失败，后以单 Job 重试通过；不得删失败或冒充生产审核准入。
   **V225 来源批量/长事务测试边界**：任何降低 V113 租户锁争用的优化必须先经真 PG18 核验全 15 表、V91 不可变/审计状态机、单个集合 SQL 与逐行 SQL 的版本守恒、长事务跨站点等待和跨租户独立写入，并用 V222 CI-only 真 Spring Owner HTTP 验证 V91 INSERT→DELETE 提交 ABA 仍 409，金额/净利润均 NULL。不能把合成混合读写 409 样本当生产 SLO，也不允许开启真实 Seller/Ads OAuth；详见 [V225](docs/finance/v225-epoch-bulk-write-long-transaction-and-source-aba.md)。**精确验收**：[V225B SHA `e360e74be8`](https://github.com/kunlong-luo/merchivra/commit/e360e74be8177d472434723b3516fd6340e918c0) 的 [完整 A3 #45325472831（7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45325472831)，真 PG18 32 并发已提交来源事务**全局 +64 tick**、3/3 真 V91 来源 ABA Owner HTTP409。不得使用每线程快照独立等于2作为共享租户版本守恒断言；首轮错误 [#45324675583 6/7](https://github.com/kunlong-luo/merchivra/actions/runs/45324675583) 保留。
   **V224/V115 V113 版本减噪边界**：只允许通过前向 Flyway V114 与 V115 的 UPDATE-only transition-table 独立 PL/pgSQL IF 修复（不能依赖 SQL `AND` 短路） 在 `erp_purchase_order`、`seller_marketplace` 的**完整旧/新行多重集双向相等**时跳过无意义 UPDATE tick，其他 13 张 V216/V165 表和所有实际变化保持 V113 事务有序版本、双 RR ABA fail-closed。每次变更要有真 PG18 无变化/真实改动/rollback/共享租户锁和真 Owner HTTP 200/409 及混合负载合成负例；不能宣称自然生产 409 SLO 已通过。见 [V224](docs/finance/v224-identical-update-epoch-and-concurrency.md)。**精确验收**：[V224B SHA `74a2d04b9`](https://github.com/kunlong-luo/merchivra/commit/74a2d04b93cac371396ccf4f9299e6b65f75f5ce) 的 [完整 A3 #45323010754（7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45323010754)，PG18 真证据为 INSERT/DELETE tick+2、无变化 UPDATE tick 0、真实 ABA tick+2 与 3/3 HTTP409；首轮 [#45322692498](https://github.com/kunlong-luo/merchivra/actions/runs/45322692498) 因 V114 触发器表达式导致 HTTP500 已经 V115 前向修复，失败历史不能删除。
   **V223 高写入租户/多 Marketplace 性能与 409 治理**：对 V113 按租户已提交版本的每条写入触发器做真实 PG18 并发基准，独立记录 TPS/p95/p99、Lock 等待采样、版本守恒、跨租户隔离与真实 Owner HTTP 强制交错 409。强制命中读间隙的 409 比例**不是**自然生产 409 率；性能数据只能引用精确提交的 A3 原始日志，未跑不声明通过。旧 V113 Flyway 不得原地重写；未来收窄版本范围前必须证明 tenant.status、共享 PO/receipt、V165 所有输入的变更不会错过 ABA。见 [V223 测量契约](docs/finance/v223-tenant-epoch-contention-and-cross-marketplace-409.md)。
4. 库存及 WMS 签名不自动证明第三方运营商真实身份或物理实收，不以内部三方 QC 替代外部确认；库存过账与退款回转必须受证据和批准限制。
5. 不提交或输出任何实际 Seller/Ads、银行、客户或 WMS 凭据、密钥、会话和私有证据。不要关闭 Session/CSRF、绕过 Owner/租户校验或公开 Actuator 来解决本地启动错误。

**评分控制**：2026-11-24 的现行独立复评分为 [73/100](docs/governance/project-scorecard-v228-2026-11-24.md)，原 V200 70 分是历史且不得更改。维持六维原权重：架构18、功能16、真实利润10、安全13、前端9、测试运营7；全部 GitHub A3 合成通过不会自动增加真实利润10/25，不能用费用手工摘要/本地 WMS 认领冒充真实 Seller/Ads/物流/银行证明。每次下一轮复评分应写新版本并引用精确 SHA 与独立外部证据；上线 #104 仍 NO-GO。

## 每次执行的固定步骤

1. **先核对事实**：读 [当前状态](docs/current-status.md)、[执行计划](docs/roadmap/current-plan.md)、相关 Controller/Flyway/React 页面/测试，明确“已有能力、真实缺口、外部待批准”。先复用，再扩展；不要重复造账本、审批或 API 客户端。
2. **缩小变更范围**：写清楚输入、原币/来源状态、租户与权限、未知/冲突处理、回滚兼容性、将受影响的文档。任何数据库更改均追加 Flyway 新迁移，不能改已应用迁移或补造历史证据。
3. **随改随测**：新增聚焦的正反例。每次 `main` 提交查**该精确 SHA** 的快速 CI；涉及钱、权限、库存、数据库、会话、回退与跨进程的变更按 [CI 分层策略](docs/testing/ci-policy.md)开展 PG18/HTTP/Chromium/Compose/A3，明确列出跳过或未跑的任务。
4. **更新权威文档**：当前事实写入 `docs/current-status.md`，下一步写 `docs/roadmap/current-plan.md`，规范写 `docs/governance/engineering-standards.md`，研发/部署/测试指令分别维护单一入口。Vxxx 记录只保留该版本可复核的历史证据。
5. **真实状态如实报告**：区分“写入 main、快 CI 通过、精确 SHA 完整 A3 通过、外部核验/生产已批准”。一项未验收不得借用旧 SHA 的 green。

## UI / 本地启动不可退让的规则

- 正式 ERP 默认 Next.js 16（3000）+ Spring API（8080）+ PostgreSQL 18。只执行 `pnpm dev` 不会自动启动后端或数据库。
- `/api/v1/auth/me`：**401 = 未登录**，**403 = 权限问题**，**5xx/连接异常 = 服务尚未准备**，无效成功响应不得当已登录或 guest。界面用清晰可恢复的状态呈现，诊断折叠展示，**不能通过禁用鉴权掩盖错误**。当前尚无用户机器上的 Spring 日志，不能断言其 HTTP 500 根因已修复。见[本地排错](docs/development.md#本地启动时显示服务暂时未就绪)。
- 在 360px 及桌面视口检查真实业务页面的加载、空态、错误态、权限拒绝、原始数据来源与键盘操作；错误文案不得暗示认证/利润已通过。设计原则参考 [DESIGN.md](DESIGN.md)。

## 查阅顺序与文档生命周期

[文档导航](docs/README.md) → [当前事实](docs/current-status.md) → [唯一执行计划](docs/roadmap/current-plan.md) → [工程规范](docs/governance/engineering-standards.md) → [文档控制](docs/governance/document-control.md) → [开发指南](docs/development.md) → [CI 规则](docs/testing/ci-policy.md)。

G1/G2/ADR 的 **Proposed** 不等于 **Accepted**；版本归档中的“最新”“未做”仅针对当年提交。**本 Agent 文件不再重复拼贴 100 个版本的历史执行日志。**历史全量内容可用 [本轮整理前的 Git 文件](https://github.com/kunlong-luo/merchivra/blob/2136375590abaae2373c565a1de930aa4ce45483/AGENTS.md) 查看。

- **V222 精确 HTTP ABA 验收**：[完整 A3 #45318994059（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45318994059) 的 PG18 Job #138192661605 真正打印 `PASS V222 REAL Spring Owner HTTP ABA`；仅 disposable CI 同步 Bean，正常环境不注册、不可把合成费用升格真实 COGS。
