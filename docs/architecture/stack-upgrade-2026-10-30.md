# DEP-002 · 2026-10-30 前后端基础技术栈升级实施记录

**状态：已在 `main` 实施并通过轻量 CI（Java 单元测试、Vue 类型检查/Vitest/Vite 构建）。这不是 PostgreSQL/Compose 完整运行或生产安全验收。**

## 版本对照

| 组件 | 原版本 | 实施后版本/范围 | 依据 |
|---|---|---|---|
| Java | 21 | **25 LTS** | Java 25 toolchain，Maven/CI/Docker 一致 |
| Spring Boot | 3.5.16 | **4.1.2** | 使用稳定 GA，不选 4.2 RC |
| Spring Security | 6.x | **7.1.x（由 Boot BOM 管理）** | 保留 Session/CSRF，无需引入 OAuth Resource Server |
| Jackson | 2.x | **Jackson 3.x（由 Boot BOM 管理）** | `DemoRepository` 使用 `tools.jackson.databind.json.JsonMapper` |
| Maven | 3.9+ | **继续使用 Maven** | 构建工具无须从 Maven 转 Gradle |
| Node | 22 | **24 LTS** | `apps/web/.nvmrc`、npm engines、CI、Docker |
| Vue | 3.5.x | **继续使用 Vue 3.5.x** | 不引入尚未稳定的 Vue 3.6 |
| Vite | 7.1.x | **8.3.5** | 使用 Rolldown 的 Vite 8 |
| TypeScript | 5.9.x | **6.0.3** | 与当前 vue-tsc 3.3.x 组合经 CI 类型检查验证；并非宣称 2026 最新 TS |
| Vitest | 5.0.x | **5.0.5** | 与 Vite 8 的 peer 范围相容；不增加 E2E |
| @vitejs/plugin-vue | 6.0.0 | **6.0.10** | 兼容 Vite 8 |
| vue-tsc | 3.1.x | **3.3.x** | Vue SFC 类型检查 |
| npm | 未固定依赖图 | **lockfile v3 / `npm ci`** | Node 24 上真实 registry 解析，CI 与 Docker 强制使用锁文件 |

> TS 7 已在 2026 年发布，但本次按维护者已商定的 TS 6.0 基线升级；优先保证 Vue SFC 工具链兼容性，后续可单独评估 TS 7，而不是在同一批次叠加更多大版本风险。

## Spring Boot 4 真实兼容迁移

- **Web**：`spring-boot-starter-web` → `spring-boot-starter-webmvc`。
- **Flyway**：原第三方 `flyway-core` → Boot 4 官方 `org.springframework.boot:spring-boot-starter-flyway`，保留 `flyway-database-postgresql`；首轮 CI 发现并更正了误写的 `org.flywaydb` groupId。
- **MVC Test**：`spring-boot-starter-webmvc-test`；`@WebMvcTest` 迁至 `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`。
- **Security 7**：显式 `@EnableWebSecurity`，确保 MVC 测试切片加载 `HttpSecurity` 后可继续验证匿名 401 和缺 CSRF 403；保留安全负例测试，没有禁用测试绕过错误。
- **Jackson 3**：`com.fasterxml.jackson.databind.ObjectMapper` → `tools.jackson.databind.json.JsonMapper`，`JsonProcessingException` → `JacksonException`；不长期依赖已弃用的 Jackson 2 兼容模块。
- **Java**：在 Maven POM 指定 `<java.version>25</java.version>`，CI 使用 Temurin 25，Docker 分别使用 Maven 3.9/Temurin 25 和 Temurin 25 JRE。

## 构建一致性

- 前端 `package-lock.json` 是通过一次性的 GitHub Actions 在真实 npm registry 使用 `npm install --package-lock-only --ignore-scripts` 生成，然后在 Node 24 上执行 `npm ci`、`typecheck`、`test`、`build` 并提交。一次性 workflow 提交时自动删除自身。**没有伪造依赖完整性哈希或手工修改 lockfile**。
- GitHub CI：后端 Maven 快速测试（Java 25）、前端 Node 24 `npm ci`/typecheck/Vitest/build；`actions/checkout`、`setup-java` 和 `setup-node` 使用 Node 24 运行时的 v5。
- Docker：后端 `maven:3.9-eclipse-temurin-25`、`eclipse-temurin:25-jre`，前端 `node:24-alpine`；Node 构建阶段复制锁文件并运行 `npm ci`。

## 明确未增加的内容

没有为追求“2026 最全依赖”引入 Kafka、IoT/InfluxDB、空间数据库、JPA/Redis Repository、WebSocket、Zen Engine、Spring OAuth Resource Server、OpenTelemetry Java Agent 或 Gradle。它们有明确业务用途时再引入，保持早期模块化单体和轻量测试策略。

**未宣称通过的验证**：真实 Docker Compose 全栈启动、PostgreSQL V1–V3 Flyway 迁移、实际 session/cookie/CSRF 浏览器联调、真实 Amazon 凭证与财务业务流程、生产安全审查。升级版本不改变 [G1/G2 待批准清单](../governance/decision-register.md)。

## 参考

- [Spring Boot 4 官方迁移指南](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Spring Boot 4.1.2 官方发布](https://spring.io/blog/2026/10/23/spring-boot-4-1-2-available-now/)
- [Vite 8 官网](https://vite.dev/)
- [Node.js 版本发布计划](https://github.com/nodejs/Release)
- [Vitest 5 迁移指南](https://vitest.dev/guide/migration/)
