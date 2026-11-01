# ADR · 成熟工具优先与现行 API 契约驱动生成（V64.1）

日期：2026-11-01
状态：**已实施；核心 Java/React/PG18/Compose CI 成功**

## 工程原则

优先复用成熟工具、官方 SDK 与现有框架原语。只有明确的不适用情况才自研通用基础设施，并保留许可证、安全漏洞、升级/维护成本和专项负例审查。保持 Java25、Spring Boot4.1.2、Gradle、PostgreSQL18、React19、TypeScript6、可选 Redis8，不引入 Maven/Vue/强制微服务。

## 实际接入

- 后端使用 org.springdoc:springdoc-openapi-starter-webmvc-api:3.2.0，从已实现的 V61 SKU 利润与 V64 整期利润 Controller 的 9 个实际操作导出 OpenAPI。Swagger UI 未安装，MERCHIVRA_CONTRACT_EXPORT_ENABLED 默认 false；只有显式开启且客户端为 127.0.0.1/::1 时才可读取 /v3/api-docs。生产不要启用。
- scripts/normalize-finance-openapi.mjs 检查操作 ID、稳定排序与 Session Cookie/CSRF 声明；已提交的现行契约是 apps/web/openapi/finance-profits.json。旧 docs/api/openapi.json 标注 DESIGN ONLY，禁止当作可运行生产 API 合约。
- Node24/npm registry 实际安装 Orval8.44.0 并提交 package-lock.json。apps/web/orval.config.ts 从现行契约生成 src/generated/finance-profits.ts、finance-models 与 TanStack Query5 Hooks。GET 按默认规则生成 Query，POST 为 Mutation。
- apps/web/src/lib/period-profit.ts 和 sku-profit.ts 现在调用 Orval 生成请求函数，不再维护独立的 fetch 和 API URL 组装。现有业务面板保留原 React Query 租户缓存键，Zod 继续验证金额、NULL、来源未核实等不变量。
- apps/web/src/lib/merchivra-mutator.ts 统一内部 /api/v1/ 相对路径、same-origin Session、no-store、写请求 CSRF、HTTP 错误处理，并返回 Orval8 正确的 {data,status,headers}。严禁手动改写生成文件。

## CI 和本地再生成

- CI frontend 使用 npm ci、npm run api:check、typecheck、Vitest、Vite build；api:check 检查已跟踪文件的差异和新生成的未跟踪文件。
- CI PG18 启动真实 Spring Boot + Flyway 并使用只限 loopback 的契约导出，规范化后与已提交 JSON 逐字节比较。构建和测试不需要真实 Amazon Seller 密钥。
- 本地操作：使用开发数据库、显式设置 MERCHIVRA_CONTRACT_EXPORT_ENABLED=true 启动 Gradle bootRun；curl 获取 http://127.0.0.1:8080/v3/api-docs/finance-profits 写入 /tmp/finance-live.json；根目录运行 node scripts/normalize-finance-openapi.mjs /tmp/finance-live.json apps/web/openapi/finance-profits.json；进入 apps/web 执行 npm ci、npm run api:generate、npm run api:check、npm run typecheck、npm test、npm run build，再提交契约和所有生成文件。
- 初次生成使用的临时 GitHub Actions contents:write 工作流已经移除，避免长期保留机器人写 main 的能力。后续开发者手动再生成并经过 CI 检查。

## 已验证与未覆盖

- [x] 从真实 Spring Controller 导出、生成并锁定 V61/V64 财务请求客户端及 Query Hooks。
- [x] Server-only Session/CSRF/no-store；未验证金额和来源仍由 Zod 与业务审批校验。
- [x] GitHub Java/Gradle、PostgreSQL18、React19、Compose CI 已通过（run 41293168205）。
- [ ] 其他 Orders、FBA、Finances、Ads 等全部 API 尚未迁移，V65 起按模块增量执行。
- [ ] 真实 Amazon Seller 授权、银行账单以及生产环境权限/灾备验收仍待 Owner 在完成核心开发后本地验证。

不得使用人工生成的旧设计草案替代实测 OpenAPI，也不得将自动生成的 API 类型冒充已实现的业务许可。
