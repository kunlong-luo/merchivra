# Merchivra · 当前状态与验收边界

> **CURRENT · 2026-11-22 / V198 结算多重归属阻断、V197 退款、V195 UI、V194 财务。** `main` 已在 [`2136375`](https://github.com/kunlong-luo/merchivra/commit/2136375590abaae2373c565a1de930aa4ce45483) 完成 V195 本地登录服务不可用的错误分类、友好界面及回归测试；该 SHA 的 [快速 CI #45016097852](https://github.com/kunlong-luo/merchivra/actions/runs/45016097852) **SUCCESS**（Java25、React/Next16、pnpm/Orval/Vitest、Compose）。**用户本机 HTTP 500 根因尚未定位**；界面修复不等于 Spring/PG18 服务故障修复。参见 [排错指南](development.md#本地启动时显示服务暂时未就绪)。
>
> **V194 / Flyway V104 + V105 严格修补**：版本化 V1 订单/SKU 捕获 SHA-256、独立读时从冻结证据复算，以及 V64 汇总/CSV、V188 瀑布 fail-closed 已在 [`61c8678`](https://github.com/kunlong-luo/merchivra/commit/61c8678288697d20476c44478fafae8bd7739349) 及 [`9d4ebad`](https://github.com/kunlong-luo/merchivra/commit/9d4ebadb7abe45369ea55a7bb6c33b09e920d985) 交付；[快速 CI #45014853040](https://github.com/kunlong-luo/merchivra/actions/runs/45014853040) **SUCCESS**，先前真实 PG18 A3 发现 V104 的 NULL/UNKNOWN CHECK 漏洞，已新增 V105 显式非空约束，**已由[V194/V105 最终代码 SHA `9cdb9268020bdc9ae0c743e09d5d5775c377ce69`](https://github.com/kunlong-luo/merchivra/commit/9cdb9268020bdc9ae0c743e09d5d5775c377ce69) 的 [完整 A3 #45018945885（run_attempt=1，7/7 核心 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45018945885)；同一 SHA [快速 CI #45018945926 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45018945926)验收**。旧 `extract_sha256` 不能当 V1 指纹，更不能当外部 Amazon 签章。见 [V194 证据边界](finance/v194-versioned-profit-capture-fingerprints.md)。
>
> **上一阶段完整 A3 为 V193（历史对照，并非最新）**：精确 [`600883236a3669f51b6703229447d93499b271cd`](https://github.com/kunlong-luo/merchivra/commit/600883236a3669f51b6703229447d93499b271cd) 的 [A3 #45012495202（run_attempt=2，SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45012495202)。V194/V105 已在上述新 SHA 完整验收；但这仍然只是隔离合成技术证据，不覆盖本次文档提交，也不验证外部真实性。
>
> **全局安全结论**：pre-alpha，真实 Seller/Ads OAuth、真实平台/银行来源核验、退款 COGS 与 G1/DEC-06/07/08 仍关闭或未批准；`realizedNetProfit=null`，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。文档规范以 [ENG-001](governance/engineering-standards.md) 和 [GOV-001](governance/document-control.md) 为准，不能从合成 CI 推出真实上线。

> **V197（[精确代码 `db9d88b`](https://github.com/kunlong-luo/merchivra/commit/db9d88be81ec326194242f75596661cb950cbea7) 的 [V197 完整 A3 #45020338812（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45020338812) 与 [同 SHA 快 CI #45020338752](https://github.com/kunlong-luo/merchivra/actions/runs/45020338752) 均已通过）**：在现有 V170/V63 退款只读准备度上增加 [逐事件 COGS 冲回阻断原因与跨事务来源一致性核验](finance/v197-refund-cogs-source-consistency-barriers.md)。比对原 Finances 退款事件、人工订单、原币、带符号金额；来源不一致时展示 STALE、隐藏不可信的成本敏感度，不自动回补库存/过账。人工独立审核后仍硬性保留外部真实性、实物收货、G1 会计政策和账务发布 NO-GO。完整 A3 验证合成 PG18/HTTP/Chromium/Compose 整体回归，跨事务漂移隐藏金额由本轮真实 Service 单元测试断言；仍不代表真实 Amazon 退款签证。

> **V198（[精确代码 `11864a7`](https://github.com/kunlong-luo/merchivra/commit/11864a739451f0e91c0bd9639b2c4bfe441b518e) 的 [完整 A3 #45021678686（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45021678686)，及 [同 SHA 快 CI #45021678580](https://github.com/kunlong-luo/merchivra/actions/runs/45021678580) SUCCESS）**：审计 V31 Finances 的 `SETTLEMENT_ID` 存储与 V58 结算报告发现，当同一事件携带多个 Settlement ID、同站点不同报告共用一个结算编号、报告行数缺失或内部混币时，旧比较即使金额相等也可能返回 `SOURCE_AMOUNTS_AGREE_UNVERIFIED`。现已在[原只读比较](finance/v198-settlement-source-claim-integrity.md)补全五个整范围计数及显式阻断状态；V56 双人复核仍复用，不允许人工“金额一致”解除冲突，更不代表真实银行到账。早期实现提交 `f5bc560` 的 [A3 #45021496225](https://github.com/kunlong-luo/merchivra/actions/runs/45021496225) 因旧 Java/Vitest 测试夹具缺新增字段而 FAILED，随后在 `11864a7` 完整修复并 7/7 验收；旧失败保留历史。全部为合成来源，仍不得入账。

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
