# Merchivra · 当前状态与验收边界

> **CURRENT · 2026-11-23 / V206 原件内容可重算、V205 外部交接档案、V204 签名出库。** `main` 已在 [`2136375`](https://github.com/kunlong-luo/merchivra/commit/2136375590abaae2373c565a1de930aa4ce45483) 完成 V195 本地登录服务不可用的错误分类、友好界面及回归测试；该 SHA 的 [快速 CI #45016097852](https://github.com/kunlong-luo/merchivra/actions/runs/45016097852) **SUCCESS**（Java25、React/Next16、pnpm/Orval/Vitest、Compose）。**用户本机 HTTP 500 根因尚未定位**；界面修复不等于 Spring/PG18 服务故障修复。参见 [排错指南](development.md#本地启动时显示服务暂时未就绪)。
>
> **V194 / Flyway V104 + V105 严格修补**：版本化 V1 订单/SKU 捕获 SHA-256、独立读时从冻结证据复算，以及 V64 汇总/CSV、V188 瀑布 fail-closed 已在 [`61c8678`](https://github.com/kunlong-luo/merchivra/commit/61c8678288697d20476c44478fafae8bd7739349) 及 [`9d4ebad`](https://github.com/kunlong-luo/merchivra/commit/9d4ebadb7abe45369ea55a7bb6c33b09e920d985) 交付；[快速 CI #45014853040](https://github.com/kunlong-luo/merchivra/actions/runs/45014853040) **SUCCESS**，先前真实 PG18 A3 发现 V104 的 NULL/UNKNOWN CHECK 漏洞，已新增 V105 显式非空约束，**已由[V194/V105 最终代码 SHA `9cdb9268020bdc9ae0c743e09d5d5775c377ce69`](https://github.com/kunlong-luo/merchivra/commit/9cdb9268020bdc9ae0c743e09d5d5775c377ce69) 的 [完整 A3 #45018945885（run_attempt=1，7/7 核心 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45018945885)；同一 SHA [快速 CI #45018945926 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45018945926)验收**。旧 `extract_sha256` 不能当 V1 指纹，更不能当外部 Amazon 签章。见 [V194 证据边界](finance/v194-versioned-profit-capture-fingerprints.md)。
>
> **上一阶段完整 A3 为 V193（历史对照，并非最新）**：精确 [`600883236a3669f51b6703229447d93499b271cd`](https://github.com/kunlong-luo/merchivra/commit/600883236a3669f51b6703229447d93499b271cd) 的 [A3 #45012495202（run_attempt=2，SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45012495202)。V194/V105 已在上述新 SHA 完整验收；但这仍然只是隔离合成技术证据，不覆盖本次文档提交，也不验证外部真实性。
>
> **全局安全结论**：pre-alpha，真实 Seller/Ads OAuth、真实平台/银行来源核验、退款 COGS 与 G1/DEC-06/07/08 仍关闭或未批准；`realizedNetProfit=null`，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。文档规范以 [ENG-001](governance/engineering-standards.md) 和 [GOV-001](governance/document-control.md) 为准，不能从合成 CI 推出真实上线。

> **V197（[精确代码 `db9d88b`](https://github.com/kunlong-luo/merchivra/commit/db9d88be81ec326194242f75596661cb950cbea7) 的 [V197 完整 A3 #45020338812（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45020338812) 与 [同 SHA 快 CI #45020338752](https://github.com/kunlong-luo/merchivra/actions/runs/45020338752) 均已通过）**：在现有 V170/V63 退款只读准备度上增加 [逐事件 COGS 冲回阻断原因与跨事务来源一致性核验](finance/v197-refund-cogs-source-consistency-barriers.md)。比对原 Finances 退款事件、人工订单、原币、带符号金额；来源不一致时展示 STALE、隐藏不可信的成本敏感度，不自动回补库存/过账。人工独立审核后仍硬性保留外部真实性、实物收货、G1 会计政策和账务发布 NO-GO。完整 A3 验证合成 PG18/HTTP/Chromium/Compose 整体回归，跨事务漂移隐藏金额由本轮真实 Service 单元测试断言；仍不代表真实 Amazon 退款签证。

> **V198（[精确代码 `11864a7`](https://github.com/kunlong-luo/merchivra/commit/11864a739451f0e91c0bd9639b2c4bfe441b518e) 的 [完整 A3 #45021678686（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45021678686)，及 [同 SHA 快 CI #45021678580](https://github.com/kunlong-luo/merchivra/actions/runs/45021678580) SUCCESS）**：审计 V31 Finances 的 `SETTLEMENT_ID` 存储与 V58 结算报告发现，当同一事件携带多个 Settlement ID、同站点不同报告共用一个结算编号、报告行数缺失或内部混币时，旧比较即使金额相等也可能返回 `SOURCE_AMOUNTS_AGREE_UNVERIFIED`。现已在[原只读比较](finance/v198-settlement-source-claim-integrity.md)补全五个整范围计数及显式阻断状态；V56 双人复核仍复用，不允许人工“金额一致”解除冲突，更不代表真实银行到账。早期实现提交 `f5bc560` 的 [A3 #45021496225](https://github.com/kunlong-luo/merchivra/actions/runs/45021496225) 因旧 Java/Vitest 测试夹具缺新增字段而 FAILED，随后在 `11864a7` 完整修复并 7/7 验收；旧失败保留历史。全部为合成来源，仍不得入账。

> **V199（[精确 `30fd25c` 提交](https://github.com/kunlong-luo/merchivra/commit/30fd25c9205c10234fced28626d69e0df2b81889) 的 [完整 A3 #45022799341（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45022799341) 与 [快速 CI #45022799357](https://github.com/kunlong-luo/merchivra/actions/runs/45022799357) SUCCESS）**：V54 结算报告声明净额可空，原 V198 判定可能在 `declared_net=NULL` 且明细/Finances 相等时误判为三方一致；[V199](finance/v199-settlement-declared-net-fail-closed.md) 保留 NULL/0 区别并返回 `REPORT_DECLARED_NET_UNAVAILABLE`，现有 V56 审核不能审批。合成验收不能替代独立 Amazon/银行验真。

> **V200（[精确 V200 代码 `2715529`](https://github.com/kunlong-luo/merchivra/commit/271552911b55a451e66662cdf3aab94c2db21341) · [快速 CI #45023721155 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45023721155) · [完整 A3 #45023721170（7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45023721170)）**：对历史订单 SKU 映射与按订单当地日期适用的 SKU 成本版本，在原 LATERAL 查询上增加**全候选计数**。多条有效映射或重叠成本区间不能再靠 `ORDER BY … LIMIT 1` 随意选择，应显示明确冲突、隐藏行成本并阻断订单 `COGS_READY_PREVIEW`；仍是成本准备度，不是实物采购批次成本或财务过账。详见 [V200 财务证据](finance/v200-historical-sku-cost-ambiguity.md)。正式独立复评为 [70/100](governance/project-scorecard-v200-2026-11-22.md)，不是 G1/生产发布批准。

> **V201（[精确代码 `c47873c`](https://github.com/kunlong-luo/merchivra/commit/c47873cd1ad3e9963f57c9d69dc19b2ba5a17c90) · [快速 CI #45024903306 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45024903306) · [完整 A3 #45024903265（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45024903265)）**：原 V34 在写入时检查跨事件退款数量，但旧订单有效数量后续变更可能使多个已审退款事件合计超过现存 LIVE 订单数量。V201 在原 V34 读取时按**整单所有退款事件**再核对累计数量；缺当前 LIVE 订单项或超额返回 `REFUND_QUANTITY_ACROSS_EVENTS_CONFLICT`，隐藏声称入库的 COGS 敏感度，阻断 V35/V63 后续独立金额/退货成本证据。见 [V201](finance/v201-refund-cross-event-quantity-read-gate.md)。上述 CI/A3 验证 Java/Next/PG18/HTTP/Chromium/Compose 7/7，并不宣称批次实收或真实净利润，复评分仍为 [70/100](governance/project-scorecard-v200-2026-11-22.md)。

> **V202（[精确代码 `4d07d87`](https://github.com/kunlong-luo/merchivra/commit/4d07d87c92366b661ad9c5400b759ac73d98362f) · [快速 CI #45105511319 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45105511319) · [完整 A3 #45105511271（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45105511271)）**：新增 [订单 SKU ↔ 本地采购批次成本来源只读核对](finance/v202-order-local-batch-cost-provenance.md)。复用已有 V20 采购行、V39/V46 出入库/QC、V47 批次来源及订单有效期成本，核对同 SKU 的收货时间、采购原币/单价、批次 SKU/仓库/数量、QC 检验数量与独立 QC_RELEASE 流水；不跨币种推断平均成本，也**不把同 SKU/相似数量/本地放行记录当成 Amazon 实际销售批次凭证**。新响应明确 `saleLotAttributed=false`、`actualBatchCogsVerified=false`、`realizedBatchCogs=null`，Next 在订单成本面板原区域诊断，无新会计过账。精确 SHA 的 PG18/HTTP/Chromium/Compose A3 已 7/7 SUCCESS。正式评分仍 [70/100](governance/project-scorecard-v200-2026-11-22.md)，真实授权和 #104 NO-GO 仍未改变。

> **V203（Flyway V106，[精确代码 `3d94550`](https://github.com/kunlong-luo/merchivra/commit/3d94550b9a2ef5a53c15db358b390ed88b60478b) · [同 SHA 快 CI #45116800143 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45116800143) · [完整 A3 #45116800085（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45116800085)）**：新增 [销售订单项 → 本地 pick line → 收货批次的不可变归属声明契约](finance/v203-sale-to-lot-link-integrity-contract.md)。数据库写入时对订单项、pick line、QC 验收的整个历史声明数量实施行锁与数量守恒；数据库内冻结批次、出库、采购单价/币种快照，撤销事件仅追加且不回收原分配数量。读时重新验证订单/批次/出库/QC 身份和数量，发现漂移立即 STALE；Owner 的只读 GET 不开放声明写入或 COGS 过账。**这仅是可验证的本地关联声明，并非真实 Amazon 订单履约、外部仓库主体签证。** 实际 `realizedBatchCogs=null`、`realizedNetProfit=null`，Seller/Ads 关闭、生产 #104 NO-GO；评分仍 **70/100**。本次验收确属 V203 精确代码 SHA，7/7 仅证明隔离合成工程检查，不证明外部真实性。

> **V206（Flyway V109；本轮精确代码 CI/A3 待验证）**：基于 V205 的运营商登记和承运交接**声称**文件摘要，新增 [私有原件二进制与 SHA-256 独立重算](finance/v206-private-custody-original-content-integrity.md)。沿用 V52/V137 的文件魔数/2 MiB 限制和生产 ClamAV fail-closed；PG18 从 `BYTEA` 生成摘要、写入时与 V205 声称 SHA 核对，并在每次查询时从**实际保存的原件字节**重算。两类来源各限一份、错摘要/跨租户/上游撤销/重复文件及过量存储阻断；仅返回 Owner/no-store 元数据与缺口状态，不开放下载。**上传文件吻合租户声明 ≠ 第三方真实签发/实体运营商/承运实物交付/Amazon 履约核证**。实际 COGS/净利润继续 NULL，Seller/Ads 授权 OFF、生产 #104 NO-GO、评分 70/100 均不变。
>
> **V205（Flyway V108，[V205 精确代码 `99ef9b9`](https://github.com/kunlong-luo/merchivra/commit/99ef9b988de101f3413e0821817071bcce85c370) · [同 SHA 快速 CI #45132910751 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45132910751) · [完整 A3 #45132910387（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45132910387)）**：新增 [外部运营商与承运交接证据档案](finance/v205-external-dispatch-custody-dossier.md)，仅接受与 V204 当前签名观察绑定的租户**声明**的登记编号、原始文件 SHA-256 与交接单号，冻结 V107 身份并隔离审查人。数据库检测同登记编号/承运交接编号的跨订单**摘要冲突**，把 V204 签名失效、密钥撤销和批次变动传导到读时；即使另一 Owner 审查记录为 `DOCS_CONSISTENT_UNVERIFIED`，也不能核实第三方真实主体、承运实物交接、Amazon FBA/FBM 履约与实际 COGS。V205 所有真实性/过账标志仍 `CHECK(FALSE)`；**无外部原件、无真实 Amazon Seller/Ads OAuth、无生产写入**。 `realizedNetProfit=null`、生产 #104 **NO-GO**、评分 **70/100** 均不变；该精确代码的合成 PG18/Java/HTTP/Chromium/Compose A3 已 7/7 通过，PG18 日志明确 `PASS V205 PG18 V108`；只能证明本地数据库/应用契约，不能认定 B2 第三方真实运营主体。
>
> **V204（Flyway V107，[V204 精确代码 `f89485f`](https://github.com/kunlong-luo/merchivra/commit/f89485fe0b85cb63f7e12614f254c4ba57ed9be9) · [同 SHA 快速 CI #45122956620 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45122956620) · [完整 A3 #45122956610（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45122956610)）**：基于 V203 真实本地订单项/pick/receipt 关联及 V98/V101 的租户登记 Ed25519 公钥/密钥持有权证据，新增 [签名出库观察和独立重算阻断](finance/v204-signed-sale-dispatch-identity-gate.md)。对 claim/来源/仓库/数量/出库编号签名校验后追加不可变记录，读取时重新验证签名 SHA-256、密钥撤销、V106 归属撤销及 V200 历史 SKU 的失效状态。所有结果仅代表**租户登记密钥持有者曾签署与本地记录相符的观察**，不是外部第三方仓库主体身份、Amazon FBA/FBM 履约、实物发货或真实净利润的凭证。**无真实 Seller/Ads OAuth / 无生产写入**；`realizedBatchCogs=null`、`realizedNetProfit=null`、[#104](https://github.com/kunlong-luo/merchivra/issues/104) NO-GO、正式评分 **70/100** 都不变。已在同一代码 SHA 的合成 PG18/Flyway/HTTP/Java/Next/Chromium/Compose 全部 7/7 验收；真实外部身份与会计政策仍未批准。

## 1. 技术栈与启动事实

| 维度 | 当前代码与入口 | 不应误读 |
| --- | --- | --- |
| 后端 | Java 25 · Spring Boot **4.1.2** · Gradle Wrapper · Spring Security Session/CSRF · Flyway | **不用 Maven**，不存在“先授权真实 Amazon 才能开发”的前提 |
| 持久化 | **PostgreSQL 18**；内置 Compose 或隔离复用现有 spark-db | Flyway 已实现 ≠ 目标数据库 PITR/备份签署 |
| 正式本地 Web | React 19 · **Next.js 16.4.5 App Router** · TypeScript 6 · Node 24 | `pnpm dev`（3000）/ `pnpm build`；Next 不是只读预览 |
| 包管理 | **只使用 pnpm 12.20.0**，`apps/web/pnpm-lock.yaml`，`pnpm install --frozen-lockfile` | 不再维护第二份前端锁文件；`packageManager` 固定版本 |
| API 客户端 | Springdoc/OpenAPI + **Orval**、Zod、TanStack React Query/Table、Base UI | 不绕过生成契约新增重复手写接口 |
| 容器 | `deploy/compose.yaml` 的 `postgres + server + web`；复用模式 `server + web` | `web` 为 Next Node；`/api` 同源代理 Spring，私有 Actuator 不对公网暴露 |
| Vite | **仅** `pnpm dev:vite`、`pnpm build:vite`、`vite-web` profile 或 Pages 合成展示 | 回退/静态展示存在 ≠ Vite 仍是默认 ERP |
| Redis 8 | 可选的 Session/限流配置，默认不启用 | 不是 PostgreSQL 的事实来源或必要启动条件 |
| 主线策略 | 日常代码与文档**直接提交 main**，不创建例行开发分支/PR | 不绕开快 CI、专项单测和适用的完整 A3 |

配置和实际启停以 [开发指南](development.md)、[部署指南](deployment/local-spark-db.md)、[CI 策略](testing/ci-policy.md) 为准；GitHub Actions 的 Dependabot `package-ecosystem: npm` 是 GitHub 对 JavaScript（包括 pnpm）的生态标识，**不是**授权使用另一套包管理 CLI。

## 2. 功能与证据状态：不要把“有代码”变成“真实利润”

| 业务链路 | 已实现的技术能力 | 仍然不能声称 |
| --- | --- | --- |
| 店铺、SKU、订单、采购、FBA、库存、异常 | 租户/Marketplace 边界、部分完整历史游标分页、本地持久数据与模拟同步、人工审批及运营工作台 | 真实 Seller API 已授权、真实 FBA 到货/可售数量自动核证 |
| 基础财务 | V33/V61/V64 已知范围贡献、冻结的期间查询、订单来源与版本对照；**V193 / Flyway V103 冻结表 DML 守护 + V194 / V104 新订单/SKU 版本化指纹与独立重算** | 旧 V64 `extract_sha256` 等于外部验真；V103 自动证明迁移前历史没被改过；广告、税、运费、退货和银行全成本净利润 |
| 成本来源与分摊 | V91/V92、V163–V168：人工来源、独立 Owner 元数据复核、单一主维度分摊、局部身份冲突/共用结算行阻断 | 人工复核即平台真实性认证；本地同 ID/同摘要即真实重复收费 |
| 结算证据链 | V181/V181B（V102）：报告原始标识、文档 SHA-256、行身份与不可变快照；V182/V183：逐来源与整期间阻断；V184：批量读取优化 | 历史无指纹行可事后伪装完整；本地行关联即独立外部证据 |
| 期间成本与利润预览 | V185 原币跨月算术、V186 来源唯一归属、V187 整期跨来源风险、**V188/V188B 只读原币瀑布**；V192 增加五项金额守恒阻断；V194 增加完整订单/SKU 指纹一致性阻断 | `APPROVED_UNVERIFIED` 已入账；`realizedNetProfit` 不再为 `null`；跨币种 FX/会计政策已批准 |
| 退货与 WMS | V170–V175 的本地退款风险、证据、Ed25519 回执、数量/处置差异、独立 QC 与密钥持有权基础；V197 退款原始来源/复核跨事务一致性及冲回阻断码 | 可信外部运营商身份/物理实收、正式库存/COGS 回转已经实现；WMS B2/B3 仍欠缺 |
| 浏览器与发布 | 合成 Chromium、PG18 HTTP、受控 Next→Vite→Next TLS 回退及 CI 技术验证 | 目标真实 TLS/KMS/AV/PITR/Alertmanager/人工审批/上线许可证 |

财务详细证据：[V162 八类全成本边界](finance/full-cost-coverage-v162.md) → [V181 结算行身份](finance/v181-immutable-settlement-line-identity.md) → [V183 整期阻断](finance/v183-full-period-profit-evidence-gates.md) → [V186 单一归属](finance/v186-single-cost-source-ownership-bridge.md) → [V187 跨来源风险](finance/v187-full-period-cross-source-risk.md) → [V188 原币瀑布](finance/v188-period-profit-cost-waterfall-preview.md) → [V193 V64 冻结证据不可变性](finance/v193-frozen-profit-snapshot-database-guards.md) → [V194 版本化指纹与独立重算](finance/v194-versioned-profit-capture-fingerprints.md)。详见 [当前路线](roadmap/current-plan.md)。

## 3. 可复核的状态词

- **Implemented**：代码路径存在并有指向明确的测试；不能代表真实第三方核证。
- **Synthetic A3 passed**：记录精确 SHA、Actions run、PG18/HTTP/Chromium/Compose 范围；不代表 production GO。
- **Reviewed unverified**：人工来源/归属的本地审查结论；不是 Amazon/银行真伪证明，更不是财务可入账。
- **Planned / G1 pending**：业务政策、角色、币种/税费/退款处理或外部信任链尚未签署，不得自动解锁。
- **Production NO-GO**：[#104](https://github.com/kunlong-luo/merchivra/issues/104) 未关闭，且未有独立发布批准。

## 4. 下一步与维护

**唯一当前优先级**在 [当前执行计划](roadmap/current-plan.md)。已有 V108/V148/V161 等长文属于**历史里程碑/当时判断**，不能优先于本页；保留原始 CI 链接是为审计追溯，不是最新验收声称。维护本页时必须同时更新 [文档导航](README.md)、[开发](development.md)、[部署](deployment/local-spark-db.md) 及 [测试](testing/ci-policy.md) 中被改动的事实；任何高风险变更重新提供相应 SHA 的验证链接。
