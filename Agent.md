# Merchivra · Agent 开发策略

> 本文件是本仓库的长期开发约定，适用于自动化 Agent 和人工贡献者。
> 与根目录 `AGENTS.md` 保持一致。用户明确的新指示优先于本文；产品 G1/G2 待签署的业务方案不得因本文自动视为批准。

## 目标与协作

- 维护 **Merchivra（麦奇维拉）**：Apache-2.0、开源、自托管、Amazon Seller ERP；优先交付能运行的业务纵向闭环。
- 按维护者现行指示：**直接在 `main` 开发、逐步提交并验证 CI**；不再创建长寿命分支或 Draft PR，除非维护者更改要求。更新相关文档与 GitHub Issue，报告确切提交/检查结果。
- Amazon SP-API 接入**优先使用官方 Java SDK `software.amazon.spapi:spapi-sdk:1.13.0` 与官方 OpenAPI 模型**；已有 `SpApiOfficialSdkFactory` / `SpApiOfficialModules` 和保守 Core Read Adapter。未来新增模块不应优先手写无类型的 URL 接口，须按操作审查 DTO/角色/速率/权限。见 [INT-004](docs/integrations/sp-api-code-integration.md)。
- 优先使用经过广泛验证的官方 Starter/成熟库；不要重复实现基础设施，不因新潮就加入 Kafka、微服务、重型前端框架或与业务无关的依赖。
- 可参考 [Pagevela](https://github.com/kunlong-luo/pagevela) 的构建、依赖更新、持久化并发纪律，但不照搬其 React / Dexie 浏览器状态设计。

## 当前工程基线（2026-10-30）

- **后端**：Java 25 LTS、Spring Boot 4.1.2、Spring Security 7、Jackson 3、Maven、Spring JDBC + Flyway、PostgreSQL 17。
- **前端**：Node 24 LTS、Vue 3.5、TypeScript 6、Vite 8、TanStack Vue Query 5、Zod 4、Vitest；使用已提交的 `apps/web/package-lock.json` 与 **`npm ci`**，不要改回无锁安装。
- **部署**：单体 API + Vue 静态页面、Docker Compose；Redis 预留而非所有功能都必须用。Dockerfile 与 GitHub Actions 必须和本地版本一致。
- 不迁移到 Gradle，除非存在实质工程收益并经维护者确认；添加依赖前先判断当前阶段是否真正使用。

## 测试与开发节奏

- **M0～M2 轻量 CI**：后端 Maven 快速单元测试；前端 TypeScript 类型检查、少量 Vitest、Vite build。
- 不提前引入 Playwright/E2E、跨浏览器矩阵、83% 等覆盖率门禁、大型 Docker 集成 CI。M3 核心流程基本成熟时逐步补 E2E；M4 Beta 之前再加必要安全、数据库和上线验收。
- 保留**少量高价值负例**：租户/店铺越权、CSRF、同步幂等、金额/币种、退款状态、库存 `UNKNOWN ≠ 0`。成熟库内部行为无需重新测试。
- 每次修改后检查当前 `main` 的 **CI 实际结论**；失败时先诊断并修复。已完成的 PostgreSQL V1–V6 和真实 Compose Mock HTTP 全链路（含逐页重启恢复）冒烟应附准确证据，但**未执行的** Compose、并发、Amazon SP-API 及生产安全实测不应声称已通过。

## 安全与业务不变量

- `MERCHIVRA_SPAPI_ENABLED` 默认 **false**，SDK 原始远程方法不得开放为 HTTP 代理；`MERCHIVRA_SPAPI_ALLOW_WRITES` 默认 false。正式环境要有经过批准的 Seller OAuth、按租户隔离的凭证 Vault、权限核验和完整业务审计后才可开启出站。**SDK 包含大量 API ≠ ERP 全部真实调用已实现。**
- **V7 OAuth 协议与 Vault**：公有 Seller 授权需要 Start→Amazon Login URI（含 amazon_callback_uri/amazon_state）→最终 Code Redirect 两阶段；绑定短期一次性哈希 State + SameSite=Lax HttpOnly 交接 Cookie，使用固定回调 Origin 白名单，AES-256-GCM/AAD 绑定 tenant/account/seller，日志不得出现 Code/Token。成功后仅 `PENDING_VERIFICATION`，不得直接变为 `CONNECTED` 或默认给 Finance 权限。禁止在 Demo 模式开启真实 OAuth；未通过官方授权、安全评审、KMS 轮换与身份/角色核验前保持关闭。详见 [SEC-004](docs/security/seller-oauth.md)。
- **默认禁止接入真实 Amazon 卖家资料**：现阶段仅有本地 Session/OWNER 初版，不是完整生产 IAM。不能提交凭证、Token、买家 PII 或真实导出；不将无授权端点暴露公网。
- 所有正式业务端点必须从**服务端 Session/认证身份**获取活动租户及授权店铺范围，数据库查询显式过滤 `tenant_id` 与对应 marketplace/account；绝不能信任浏览器传入的 `tenantId`。
- 本地 Seller Account / Marketplace 配置只允许当前租户 Owner 创建 `UNCONFIGURED` 占位实体：服务端生成 `LOCAL-` 内部标识，区域/币种/时区使用受控预设，不能把其解释为 Amazon 已授权，也不能接受真实 Seller 凭证；创建保持幂等，动作在同事务记录无敏感内容的配置审计。详见 [API-004](docs/api/local-seller-setup.md)。
- 当前仅有 OWNER 全租户作用域；未实现的员工角色/账户级授权不能虚构。对于不属于当前租户的资源返回不可见/拒绝，不返回其他租户数据。
- PostgreSQL 迁移**只新增新版本**，不回改已有 V1/V2/V3/V4/V5/V6/V7；跨表关系保持 `(tenant_id,id)` 复合外键，来源唯一键以租户+店铺+Marketplace 确定。
- 金额用 `BigDecimal` / `NUMERIC` 与 ISO 币种，禁止将不同币种无汇率直接相加；预计商品收入、退款、平台入账、利润须分别表达。未知/缺失 != 零库存，订单履约/退款/财务状态分开。
- `/api/v1/demo/**` 只读/写入**虚构演示数据**，需显式启用 Mock 并经 Session/CSRF 保护；正式资源 API 不得读取/复用演示 JSON 快照。严禁以固定 Demo Tenant 当作真实登录租户。
- 已新增 **V6 Sandbox 手动逐页**：每次提交一个虚构 Orders 页，`sandbox_page_stream` / `sandbox_page_commit` 持久化进度、页面与递增代数；PostgreSQL NOWAIT 行锁 + 幂等键保护事务重放。它是演示用协议，**不是正式后台 Worker、分布式租约或 Amazon 分页/水位恢复**；需要明确区分已实现与 Proposed 的 SYNC-002。见 [TEST-004](docs/testing/mock-full-chain.md)。
- 正式同步 Stream 仍需幂等、稳定来源键、事务与 checkpoint/lease fencing，未做的 Worker/正式分页/真实授权不得描述为完成。

## 文档与验收

- 实现功能时同步维护 README、`docs/development.md`、`docs/architecture/`、`docs/security/`，并保持 `Agent.md` / `AGENTS.md` 一致。
- G1/G2 产品及安全架构尚有 Proposed 决策：区分「代码已实现」「CI 测试已通过」「真实 DB/容器已验收」「业务负责人已批准」，不可混淆。
- 当前已具备**会话租户/Marketplace 只读 API 与本地未连接 Seller 配置/审计初版**。已有一次性 PostgreSQL V1–V4 验证，以及 [Compose 全链路 Mock 实机成功](https://github.com/kunlong-luo/merchivra/actions/runs/41061679196)（Nginx/Session/CSRF/US+DE 模拟页/Listing/Orders/FBA/审计/重启）；这不表示 Amazon SP-API、浏览器 E2E、生产 IAM 或 Finances 已完成。此外 [V6 页级持久恢复的真实 Compose 运行](https://github.com/kunlong-luo/merchivra/actions/runs/41065562926) 已通过（页 1→重启→页 2→旧键重放不进位）。后续仍应加强真正的多租户/并发验证，然后再设计可审计、可撤销的 Amazon 授权流程；未经批准不得接入真实 Seller/财务数据。

参见 [轻量 CI 策略](docs/testing/ci-policy.md)、[安全初版](docs/security/local-auth.md)、[依赖与 Pagevela 借鉴](docs/architecture/dependency-adoption.md)、[已实施核心表](docs/architecture/core-domain-foundation.md)。
