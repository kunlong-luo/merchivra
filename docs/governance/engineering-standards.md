# ENG-001 · Merchivra 工程实施与验收规范

**状态：CURRENT / 2026-11-22 · DOC-196**。现行执行规则的详细参考；Agent 必读约束见 [AGENTS.md](../../AGENTS.md)，具体事实见 [当前状态](../current-status.md)，任务顺序见 [当前计划](../roadmap/current-plan.md)。本文不是已签署的 G1/G2/G3 或生产许可。

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
