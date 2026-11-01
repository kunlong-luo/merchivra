# ADR · 成熟工具优先、API 契约驱动客户端生成（V64.1）

日期：2026-11-01  
状态：选型原则批准；Orval/springdoc 的项目安装和 CI 验收**尚未完成**  
触发：维护者明确要求优先成熟工具，避免重复造轮子。

## 选型纪律

先评估仓库现有依赖/官方 SDK/成熟第三方库，至少检查活跃维护、许可证、版本兼容、安全漏洞、升级负担、测试和可替换性。首选已有可靠工具，其次框架原语，再次少量适配层；确实无法复用且理由被审查后才允许自研通用设施。不可把重构当成增加 Kafka、微服务、Maven、Vue 或强制 Redis 的理由。领域金额守恒、租户隔离、审核流程仍保留专门代码和负例测试。

## 当前状况与 V64.1 目标

- 现有 apps/web/src/lib/period-profit.ts、sku-profit.ts 等使用手写 fetch + Zod。项目当前没有 Orval，也没有可供 Orval 直接生成正式客户端的**已验证的现行 API 契约**。docs/api/openapi.json 标为 DESIGN ONLY/未实现，是提案而非生产合同。
- 后端推荐 Spring Boot4.1.2 兼容的 springdoc-openapi 3.2.x 生成当前 Controller 的 OpenAPI；应验证路径、方法、响应 DTO、状态码、UUID、金额字符串和 Session Cookie + CSRF。真实 OpenAPI/Swagger 不得在生产无授权暴露，不访问真实 Amazon。
- 前端推荐 Orval8.x（开发期）、TanStack Query5 Hooks/Key/请求类型、可控 fetch mutator；统一 same-origin Cookie、no-store、CSRF 仅用于写入、受控错误、AbortSignal。已有 Zod4 的高风险金额和未核验标志继续做运行时验证。禁止在手写文件中维护另一份相同的 DTO/Hook。
- 用 Node24 + npm registry 真实解析依赖并提交 package-lock.json；必须 npm ci 成功。生成物、Orval 配置和准确 OpenAPI 一并纳入 CI 的重新生成漂移校验，并执行 typecheck、Vitest、Vite build、Java25/Gradle、PG18 Flyway 与基础 Compose。
- 从已实现的 V64 profit-reports 和 V61 sku-profit-preview 两组 API 渐进替换；保持旧版接口兼容。通过后再扩展 FBA、Orders、Finances。官方 Amazon SDK 继续独立承担 Amazon 接入；不得重写授权/限流/签名机制。

## 验收清单

- [ ] 当前实际 Controller 契约可离线生成并验证，生产安全边界未减弱。
- [ ] Orval 包与传递依赖经真实 npm registry 安装、提交正确 lockfile、npm ci 通过。
- [ ] 真实生成并使用至少 V61/V64 两组 API 的 TypeScript + Query 客户端，替换对应重复代码。
- [ ] Session/CSRF/no-store/401/403/租户作用域/财务未验证标志负例测试通过。
- [ ] GitHub CI 对 API 契约与生成结果检查漂移，Java/React/PostgreSQL18/Compose 全通过。
- [ ] Agent.md、AGENTS.md、路线图与真实完成状态一致。

本 ADR 目前只确认选型和迁移纪律；**不能称 Orval 已使用**。
