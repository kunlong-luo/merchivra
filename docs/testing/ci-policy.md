# TEST-003 · 分层 CI 与集中里程碑测试（V108 / 2026-11-08）

**这是现行策略**；详细阶段与验收边界以 [V108 MVP 冻结计划](../roadmap/mvp-freeze-release-v108.md) 为准。文档后半部分仅作历史参考。用户决定先完成剩余 P0，再集中执行完整 Mock 集成与上线测试；不是不测试，也不是正式 Amazon 生产验收已通过。

## 实际 GitHub Actions 触发

| 场景 | 自动运行 | 有意不自动运行 |
| --- | --- | --- |
| 每个 PR / main push | `ci.yml`：Gradle test、React/Orval/typecheck/Vitest/Vite build/Next TS、Compose 静态部署检查 | PG18 完整 Mock HTTP/备份恢复、Next 生产 build/HTTP、Chromium 双前端和真 Compose 容器链 |
| 普通 PR | **不**再额外运行 public Pages build（除主 CI） | 公开展示部署与截图套餐 |
| main 更新前端 | GitHub Pages 合成只读展示构建/发布（非 ERP 部署） | 不因此触发浏览器/真 Compose |
| A3 MVP 冻结或 Beta/Release 候选、重要安全/数据库变更 | 在 main 上人工启动 `milestone-validation.yml`，一次调度完整 CI + 双前端 Chromium + 真 Compose | 无真实 Amazon Token、无生产 PG/银行数据 |
| 单项故障定位 | 独立手工启动 `ci.yml` 的 `full_suite`，或独立 Chromium/Compose；必要时只重跑失败 job | 不强制对与改动无关的模块再跑所有回归 |

在 `ci.yml` 选择 `workflow_dispatch: full_suite=true` 会包括真实一次性 PG18/Flyway + HTTP/安全/恢复，以及 Next production build/HTTP；常规 PR 会**跳过**上述重型阶段。集中 workflow 调用相同已有工作流，避免复制测试脚本和网络环境逻辑。

**不能推迟的安全底线：** 改动租户/Marketplace 鉴权、Session/CSRF、Seller Vault/OAuth、采购审批、库存原子写、财务币种/金额与重复入账、同步幂等或租约时，必须在同批开发执行对应测试；若跨数据库/跨进程高风险，人工运行专项 PG18/Compose 检查后才能认为该项完成。业务数据上线前仍需真授权、恢复、性能、G1/G2/G3 审批。

**合并标准**：普通 PR 通过快速 CI + 受影响的高价值单测；集中里程碑使用最终 commit 跑全部工作流全绿，留存 Actions URL。降低成本不得用先前成功的 commit 冒充当前候选已验收。生产准入仍以 `release-readiness-v68.md` 和新的 V108 放行条件为准。

---

## 早期阶段政策（历史归档，不再代表 V108 的触发逻辑）

**状态：项目当前开发策略。** 遵照项目负责人决策：前期不追求严格 CI 和 E2E 覆盖率，以能持续编译、及时发现简单回归为主；在核心业务接近完整时，再补自动化端到端与发布质量门禁。

## 当前 main 的必需工作流（M0～M2）

- 后端：`./apps/server/gradlew -p apps/server test`（只运行当前存在的快速单元测试）。
- 前端：`npm ci`（已有真实解析的 `package-lock.json`）、`npm run typecheck`、`npm run test`、`npm run build`。
- 无 Playwright / Chromium / Firefox / WebKit、无 E2E 执行、无覆盖率阈值、无 Docker 全栈 CI、无性能预算或安全扫描的阻塞型门禁。
- 不要求每个早期功能都拥有浏览器级自动化场景，不为 Mock 占位页创建 E2E 测试。

## 各阶段何时加测试

| 阶段 | CI 策略 | 非阻塞验证 | 暂缓项 |
|---|---|---|---|
| M0 骨架 | 快速单测 + 类型检查 + 构建 | 开发者手动跑一次 Compose/健康检查 | 全栈自动化、E2E |
| M1 账号与同步 | 同上；为身份/敏感数据/同步幂等加少量定向单测或集成用例 | Mock 授权和失败恢复手工走查 | 大型 API/浏览器回归矩阵 |
| M2 商品/订单/库存 | 同上；仅针对金额、状态、租户越权、库存 UNKNOWN 做关键负例 | 真实业务流程的手动验证记录 | E2E 作为合并门禁 |
| M3 财务报表 | 视业务成熟度开始**编写**核心流程 E2E；初期仅手动/非阻塞运行 | 订单→费用→成本→利润→对账的模拟验算 | 浏览器全矩阵和严格覆盖率 |
| M4 Beta/公开部署前 | 加入核心 Playwright E2E、必要的 DB/安全集成测试与部署验收 | 灰度/备份恢复演练 | 非关键边角场景可后续补充 |

## 不可因 CI 轻量而省略的底线

- 任何真实授权/租户数据投入使用前，必须验证服务端授权及隔离；不能仅因有 Spring Security 依赖就声称安全。
- SKU/订单金额/退款/库存与异步同步状态的关键公式和不变量，开发时保留**少量高价值测试**；不复制成熟库自己的内部单测。
- 源码与 GitHub Actions 中不能放真实 Amazon Secret、Refresh Token、买家 PII；不得将无鉴权的 M0 应用暴露到公网。
- CI 当前通过，只表示已有的快速检查通过；**不表示已完成完整验收或生产就绪**。
- 用户指定任何时候可以提前运行手动或单独测试；只是不设置繁重的早期自动阻塞门禁。

## 延后执行触发点

产品核心业务流程基本可以走通且数据模型相对稳定时，创建 Playwright/DB 端到端契约测试；在 M4 Beta 上线前纳入必要的主分支质量门禁。具体任务跟踪见 [M0 Issue #2](https://github.com/kunlong-luo/merchivra/issues/2)。

相关：[TEST-001](test-plan.md)、[验收用例](../requirements/acceptance.md)、[Pagevela 借鉴](../architecture/dependency-adoption.md)。

## V68 实施情况（2026-11-01）

本项目已从早期 M0/M2 演进到 V68 发布前准备：新增 PostgreSQL18 一次性恢复演练、Spring Security MockMvc/真实 HTTP Session/CSRF 检查、35 次本地 GET 性能冒烟、Docker Compose 生产 overlay 静态安全检查。原先“无 PG18 / 无 Chromium / 无容器”等陈述仅保留为历史早期策略，**不再代表当前主线 CI 的真实覆盖范围**。实测以 GitHub Actions 记录为准，详细清单见 [V68](release-readiness-v68.md)。
