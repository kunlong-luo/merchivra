# DEP-001 / V64.1 · 成熟依赖与 API 契约复用

**CURRENT · 2026-11-25。** 以实际构建文件、[工程规范](../governance/engineering-standards.md)、[API 入口](../api/README.md) 和 [当前事实](../current-status.md) 为准。早期 Vue、npm 锁文件、默认 Vite 及候选 TanStack Router 已不再作为当前指令。

## 复用边界

| 领域 | 现行选择 |
|---|---|
| 后端 | Java 25、Spring Boot 4.1.2、Gradle Wrapper、MVC/Actuator/JDBC/Validation；PostgreSQL18、Flyway 前向迁移 |
| 前端 | React19、Next.js 16.4.5 App Router、Node24、pnpm12.20.0；TanStack React Query/Table、Zod、Base UI、Vitest；Vite 仅显式回退或虚构展示 |
| 外部 API | Amazon 官方 SDK、Springdoc 实际 Controller 合同、Orval 生成客户端；不维护第二份手写通用 SDK |
| 安全与可靠性 | 复用既有 Session/CSRF/tenant/Marketplace/Owner 与限流机制；Redis 按批准范围使用；禁止把通用重试等同于财务幂等 |

参考 [Pagevela](https://github.com/kunlong-luo/pagevela) 的集中协调、依赖管理与 CI，而非复制其浏览器数据库/业务代码。ERP 用 [Sync Stream](sync-stream-contract.md) 和 [事务 fencing](consistency-boundaries.md) 保证真实事实作用域。选择新依赖须说明功能缺口、许可证、安全维护和必要负例；成熟库不能替代租户隔离、原币金额、退款双计、数量守恒和 UNKNOWN 与零的业务验证。

## V64.1 实际契约接入与维护

- 首批 Springdoc 从 V61/V64 实际 Controller 导出 9 个操作；生产默认不启用合同导出。明确启用的本地 loopback 才允许导出，具体命令见 [API 入口](../api/README.md) 与 [开发指南](../development.md)。
- `apps/web/openapi/` 保存已审查合同；`apps/web/orval*.config.ts` 生成 `src/generated/` 的客户端、类型、Query/Mutation。禁止手改生成文件，业务层继续使用 Zod 校验金额、未知来源和租户作用域。
- 统一 mutator 使用同源 Session、no-store、写请求 CSRF、HTTP 错误处理；调用合同不代表已经获得业务许可。CI 用 pnpm 冻结安装与 `pnpm api:check`，真实 Springdoc 导出与已提交合同漂移在隔离 PG18 验收。
- 仅生成实际实现的模块；不宣称全部 REST API 已迁移。真实 Amazon Seller/Ads、银行与生产权限仍待独立验收。
- V64.1 首批生成验收历史 [CI #41293168205](https://github.com/kunlong-luo/merchivra/actions/runs/41293168205)，V67 Validation/运营 API 复用详见 [V67 审查](reuse-audit-v67.md)，技术取舍另见 [源码对标](open-source-erp-benchmark-2026-11-08.md)。

历史原文：[DEP-001](https://github.com/kunlong-luo/merchivra/blob/2c2265036df33f0afc7269ff392eea8a5a5059e4/docs/architecture/dependency-adoption.md)、[V64.1](https://github.com/kunlong-luo/merchivra/blob/2c2265036df33f0afc7269ff392eea8a5a5059e4/docs/architecture/mature-tool-first-api-client-2026-11-01.md)；旧 npm/Vite 命令仅作历史追溯，禁止直接执行。正常开发在 main，使用 pnpm 和 Gradle；生产 #104 NO-GO。
