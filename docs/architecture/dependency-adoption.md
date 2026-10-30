# DEP-001 · 成熟依赖复用决策与 Pagevela 工程经验

**状态：已在 main 建立第一批基础依赖；并不代表已完成全部技术选型。**

参考：[Pagevela](https://github.com/kunlong-luo/pagevela) 的 CI、Vitest、Playwright、依赖更新、并发持久化协调器设计，而不是复制其简历产品业务代码。

## 直接复用（已在工程落地）

| 领域 | 选择 | 为什么 |
|---|---|---|
| Java API | Spring Boot 3.5 + Web / Actuator / JDBC | 保留稳定、已有测试的原骨架 |
| 数据层 | PostgreSQL + Flyway | 事务、约束与版本迁移，强业务事实在数据库侧校验 |
| Vue HTTP 读取 | TanStack Vue Query v5 | 成熟的后台刷新/请求状态/缓存，减少自造任务状态 |
| API 响应校验 | Zod v4 | 对外部 HTTP JSON 进行运行时解析，不只信 TypeScript 类型 |
| 前端单元测试 | Vitest v5 | 与 Vite 共用转换环境，针对 Schema 边界编写负例 |
| CI | GitHub Actions | Java 测试与前端类型检查/测试/构建独立执行；最小权限/并发取消 |
| 更新治理 | GitHub Dependabot | npm、Maven、GitHub Actions 每周更新，经过 CI 验证 |

前端依然使用 **Vue 3**。Pagevela 是 React + Zustand + Dexie 的浏览器本地应用，而 Merchivra 具有多租户后台服务；直接复制 React/Zustand/Dexie 会增加迁移成本且可能破坏交易事实的一致性。

## 待业务页面需要再引入（先不加空依赖）

- **Pinia**：存在跨页面的活动租户、Seller 账号/筛选 UI 状态时再引入。严格禁止以浏览器状态作为后台权限证明。
- **Vue Router**：有真正的多业务页面后引入，配合 server-side ACL；不以路由守卫作为唯一授权手段。
- **Playwright**：有可测试的登录/订单/库存工作流后接入 Chromium 核心 E2E。可以直接借鉴 Pagevela 的 trace/screenshot-on-failure 和必要的跨浏览器矩阵，避免在 M0 空页面空跑几十条测试。
- **Spring Security + Validation**：真正开始用户/租户授权时统一引入官方模块；不要在安全机制未设计好前以一个依赖宣称 API 已受保护。
- **Resilience4j**：明确 SP-API 调用和 operation 限流模型后考虑；不能把通用自动重试当作财务幂等策略。
- **金额**：服务端使用 Java `BigDecimal` 和 PostgreSQL `NUMERIC`，不要依赖 JavaScript Number 算利润；前端显示层使用 Decimal String + ISO 4217。

## 成熟库也保留必要测试

成熟依赖只能减少**库内部功能**的重复验证，不会替我们验证租户隔离、Amazon 源字段、订单财务双计、库存 UNKNOWN 与 0 区分。保留契约和财务样本测试；重点测对接和业务不变量，而不是重写库的内部单元测试。

## 尚存的 M0 阻塞

当前前端尚未提交 `package-lock.json`；CI 临时采用 `npm install`，可能因依赖解析变化而产生差异，**不具备 Pagevela 使用 pnpm frozen-lockfile 的可复现性**。应在可访问 npm registry 的环境运行：

```sh
cd apps/web
npm install --package-lock-only --ignore-scripts
npm ci
npm run typecheck
npm run test
npm run build
```

然后提交 `apps/web/package-lock.json`，将 CI/Dockerfile 中的 `npm install` 改为 `npm ci` 并启用 npm cache。由于当前执行环境无法访问 npm registry，本次**不伪造锁文件**；锁文件未锁定前开发仍不满足可重复构建门禁。参考 [Issue #2](https://github.com/kunlong-luo/merchivra/issues/2)。

## Pagevela 复用经验：并发与数据

Pagevela 的集中写入协调器思想可用于 ERP 的 **Sync Stream + lease/fencing epoch**：同一个事实在同一作用域只有一个可信的游标推进协议；不直接复用浏览器 IndexedDB/Web Locks。参见 [ARCH-003](consistency-boundaries.md) / [SYNC-002](sync-stream-contract.md)。

## 版权与许可

只借鉴公开工程策略，不直接复制 Pagevela 的品牌、UI 或业务文件；第三方依赖需确认 MIT/Apache 等许可证和后续安全更新策略。GitHub Actions 的依赖检查按可用版本升级，生产部署前固定操作 action SHA 和容器镜像 digest。
