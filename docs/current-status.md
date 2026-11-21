# Merchivra · 当前状态与验收边界

> **更新：2026-11-21 · 技术阶段 V191（pnpm-only + Next.js 默认本地运行时）**
>
> **事实依据**：V191 代码基线 [main \`8dab97147010fdc440ee81abcfa67cd1d1d57261\`](https://github.com/kunlong-luo/merchivra/commit/8dab97147010fdc440ee81abcfa67cd1d1d57261)，[完整 A3 #44882890834](https://github.com/kunlong-luo/merchivra/actions/runs/44882890834) **7/7 SUCCESS**，[快速 CI #44882890791](https://github.com/kunlong-luo/merchivra/actions/runs/44882890791) **SUCCESS**，最近一次 [公开静态展示构建 #44882736480](https://github.com/kunlong-luo/merchivra/actions/runs/44882736480) **SUCCESS**。这些结果仅证明**该代码 SHA** 在隔离环境的对应测试通过；文档更新不会自动扩展为新的业务验证。
>
> **上线结论：pre-alpha / production NO-GO**。[#104](https://github.com/kunlong-luo/merchivra/issues/104) 尚开放，真实 Amazon Seller/Ads OAuth、远程 API 读取/写入、外部银行事实均未获准启用；不能把合成证据或静态 GitHub Pages 说成真实利润或生产验收。

## 1. 技术栈与启动事实

| 维度 | 当前代码与入口 | 不应误读 |
| --- | --- | --- |
| 后端 | Java 25 · Spring Boot **4.1.2** · Gradle Wrapper · Spring Security Session/CSRF · Flyway | **不用 Maven**，不存在“先授权真实 Amazon 才能开发”的前提 |
| 持久化 | **PostgreSQL 18**；内置 Compose 或隔离复用现有 spark-db | Flyway 已实现 ≠ 目标数据库 PITR/备份签署 |
| 正式本地 Web | React 19 · **Next.js 16.4.5 App Router** · TypeScript 6 · Node 24 | \`pnpm dev\`（3000）/ \`pnpm build\`；Next 不是只读预览 |
| 包管理 | **只使用 pnpm 12.20.0**，\`apps/web/pnpm-lock.yaml\`，\`pnpm install --frozen-lockfile\` | 不再维护第二份前端锁文件；\`packageManager\` 固定版本 |
| API 客户端 | Springdoc/OpenAPI + **Orval**、Zod、TanStack React Query/Table、Base UI | 不绕过生成契约新增重复手写接口 |
| 容器 | \`deploy/compose.yaml\` 的 \`postgres + server + web\`；复用模式 \`server + web\` | \`web\` 为 Next Node；\`/api\` 同源代理 Spring，私有 Actuator 不对公网暴露 |
| Vite | **仅** \`pnpm dev:vite\`、\`pnpm build:vite\`、\`vite-web\` profile 或 Pages 合成展示 | 回退/静态展示存在 ≠ Vite 仍是默认 ERP |
| Redis 8 | 可选的 Session/限流配置，默认不启用 | 不是 PostgreSQL 的事实来源或必要启动条件 |
| 主线策略 | 日常代码与文档**直接提交 main**，不创建例行开发分支/PR | 不绕开快 CI、专项单测和适用的完整 A3 |

配置和实际启停以 [开发指南](development.md)、[部署指南](deployment/local-spark-db.md)、[CI 策略](testing/ci-policy.md) 为准；GitHub Actions 的 Dependabot \`package-ecosystem: npm\` 是 GitHub 对 JavaScript（包括 pnpm）的生态标识，**不是**授权使用另一套包管理 CLI。

## 2. 功能与证据状态：不要把“有代码”变成“真实利润”

| 业务链路 | 已实现的技术能力 | 仍然不能声称 |
| --- | --- | --- |
| 店铺、SKU、订单、采购、FBA、库存、异常 | 租户/Marketplace 边界、部分完整历史游标分页、本地持久数据与模拟同步、人工审批及运营工作台 | 真实 Seller API 已授权、真实 FBA 到货/可售数量自动核证 |
| 基础财务 | V33/V61/V64 已知范围贡献、冻结的期间查询、订单来源与版本对照 | 广告、税、运费、退货和银行全成本净利润 |
| 成本来源与分摊 | V91/V92、V163–V168：人工来源、独立 Owner 元数据复核、单一主维度分摊、局部身份冲突/共用结算行阻断 | 人工复核即平台真实性认证；本地同 ID/同摘要即真实重复收费 |
| 结算证据链 | V181/V181B（V102）：报告原始标识、文档 SHA-256、行身份与不可变快照；V182/V183：逐来源与整期间阻断；V184：批量读取优化 | 历史无指纹行可事后伪装完整；本地行关联即独立外部证据 |
| 期间成本与利润预览 | V185 原币跨月算术、V186 来源唯一归属、V187 整期跨来源风险、**V188/V188B 只读原币瀑布** | \`APPROVED_UNVERIFIED\` 已入账；\`realizedNetProfit\` 不再为 \`null\`；跨币种 FX/会计政策已批准 |
| 退货与 WMS | V170–V175 的本地退款风险、证据、Ed25519 回执、数量/处置差异、独立 QC 与密钥持有权基础 | 可信外部运营商身份/物理实收、正式库存/COGS 回转已经实现；WMS B2/B3 仍欠缺 |
| 浏览器与发布 | 合成 Chromium、PG18 HTTP、受控 Next→Vite→Next TLS 回退及 CI 技术验证 | 目标真实 TLS/KMS/AV/PITR/Alertmanager/人工审批/上线许可证 |

财务详细证据：[V162 八类全成本边界](finance/full-cost-coverage-v162.md) → [V181 结算行身份](finance/v181-immutable-settlement-line-identity.md) → [V183 整期阻断](finance/v183-full-period-profit-evidence-gates.md) → [V186 单一归属](finance/v186-single-cost-source-ownership-bridge.md) → [V187 跨来源风险](finance/v187-full-period-cross-source-risk.md) → [V188 原币瀑布](finance/v188-period-profit-cost-waterfall-preview.md)。详见 [当前路线](roadmap/current-plan.md)。

## 3. 可复核的状态词

- **Implemented**：代码路径存在并有指向明确的测试；不能代表真实第三方核证。
- **Synthetic A3 passed**：记录精确 SHA、Actions run、PG18/HTTP/Chromium/Compose 范围；不代表 production GO。
- **Reviewed unverified**：人工来源/归属的本地审查结论；不是 Amazon/银行真伪证明，更不是财务可入账。
- **Planned / G1 pending**：业务政策、角色、币种/税费/退款处理或外部信任链尚未签署，不得自动解锁。
- **Production NO-GO**：[#104](https://github.com/kunlong-luo/merchivra/issues/104) 未关闭，且未有独立发布批准。

## 4. 下一步与维护

**唯一当前优先级**在 [当前执行计划](roadmap/current-plan.md)。已有 V108/V148/V161 等长文属于**历史里程碑/当时判断**，不能优先于本页；保留原始 CI 链接是为审计追溯，不是最新验收声称。维护本页时必须同时更新 [文档导航](README.md)、[开发](development.md)、[部署](deployment/local-spark-db.md) 及 [测试](testing/ci-policy.md) 中被改动的事实；任何高风险变更重新提供相应 SHA 的验证链接。
