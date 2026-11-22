# TEST-003 · 当前 CI / 完整 A3 验收策略（V195 / DOC-196）

> **2026-11-22 · 当前维护约定**：日常开发与文档修改直接提交 `main`，不为每个 Vxxx 开发重复创建分支/PR。快速 CI 保证构建与高价值单测；PG18 全链路、Chromium/Next、真实 Docker Compose 是有条件执行的**隔离合成**里程碑验收；两者均**不是**真实 Amazon/银行核证或生产许可。见 [事实基线](../current-status.md)、[发布阻断](release-readiness-v68.md)。

## 实际 GitHub Actions 入口

| 工作流 | 触发/预算 | 通过说明 |
| --- | --- | --- |
| [`ci.yml`](../../.github/workflows/ci.yml) | 每次 `main` push，或手动/可复用工作流调用；普通 push 为快 CI | Java 25 Gradle tests、Node24/pnpm12 **冻结安装**、Orval、类型、Vitest、Next 默认生产构建、Vite 回退构建、Compose 静态安全/文档检查 |
| [`milestone-validation.yml`](../../.github/workflows/milestone-validation.yml) | Actions 手动触发或修改此工作流时在 `main` 触发；按需执行完整 A3 | 精确 SHA，调用 `ci.yml(full_suite=true)` + 独立 Chromium 轨迹 + [真实合成 Compose 链路](../../.github/workflows/mock-full-chain.yml) |
| [`browser-layout-smoke.yml`](../../.github/workflows/browser-layout-smoke.yml) | A3 引用或手动 | Vite 回退与 Next App Router 的真实 Chromium 浏览器合成场景 |
| [`mock-full-chain.yml`](../../.github/workflows/mock-full-chain.yml) | A3 引用或手动 | 一次性 PostgreSQL18/HTTP、Session/CSRF、Next 独立 Node、受控 HTTPS Next→Vite→Next、持久恢复；不连接生产 |
| [`github-pages.yml`](../../.github/workflows/github-pages.yml) | `main` 前端变更或手动 | `VITE_PUBLIC_DEMO` 虚构只读静态展示，**不是**有鉴权的 ERP 发布 |

## 何时必须增加测试

1. **每次代码/文档提交**：核实快速 CI 的**该 SHA**结果，不能借前一个提交的 green 冒充新提交已验收。
2. **资金/币种/审核/库存/授权等高风险变更**：立即运行聚焦的后端/PG18/HTTP/CSRF 负例，不因集中 A3 策略拖延安全性验证。
3. **跨数据库/进程、版本化快照、Next 容器或部署回退**：按风险触发[完整 A3](https://github.com/kunlong-luo/merchivra/actions/workflows/milestone-validation.yml)；除业务绿灯外必须核对未运行/跳过的任务及 SHA。
4. **真实目标上线/卖家接入**：必须独立取得合法角色/地区/银行及第三方凭证，G1/G2/G3 人工批准、TLS/KMS/备份/PITR、外部告警送达/ACK、撤销、恢复与性能证据；**不存在**“A3 绿色就自动部署”的路径。

## 最新已确认工程技术证据

- **V194/V105 / `9cdb9268020bdc9ae0c743e09d5d5775c377ce69`**：[最终完整 A3 #45018945885（run_attempt=1）](https://github.com/kunlong-luo/merchivra/actions/runs/45018945885) **7/7 核心任务 SUCCESS**：真实 PG18/Flyway V105、V103 不可变、V194 NULL-safe 双向配对负例、Session/HTTP、Java/Gradle、Next16/React19/Chromium、Compose 安全与恢复；[同 SHA 快 CI #45018945926](https://github.com/kunlong-luo/merchivra/actions/runs/45018945926) **SUCCESS**。全部只含合成数据，不代表外部 Amazon/银行/税/退款 COGS 核证。

- **V195 / `2136375590abaae2373c565a1de930aa4ce45483`**：[快速 CI #45016097852（run_attempt=1）](https://github.com/kunlong-luo/merchivra/actions/runs/45016097852) **SUCCESS**：Java25、Next16/React19/pnpm/Orval、Vitest、Next 与 Vite 构建、Compose；**PG18 完整 job 在快速 CI 中按策略 SKIPPED**。V195 仅改善会话故障 UI，用户本机 HTTP 500 根因未定位。
- **V194/V105 前四轮失败历史（已通过第五轮完整复测）**：首次 [A3 #45017612506](https://github.com/kunlong-luo/merchivra/actions/runs/45017612506) 发现旧 V188 对不完整报告期望 200；第二次 [A3 #45017957824](https://github.com/kunlong-luo/merchivra/actions/runs/45017957824) 发现重叠 CHECK 的报错顺序断言；第三次 [A3 #45018224081](https://github.com/kunlong-luo/merchivra/actions/runs/45018224081) **真实证明 V104 CHECK 因 SQL NULL/UNKNOWN 可放行单边指纹**。V105 前进迁移补明确 IS NOT NULL 与双向半指纹负例，最终由上述 `9cdb926` 完整 A3 验证通过；失败历史和复测记录保留。
- **V194 / `9d4ebadb7abe45369ea55a7bb6c33b09e920d985`**：[快速 CI #45014853040](https://github.com/kunlong-luo/merchivra/actions/runs/45014853040) **SUCCESS**；V104 指纹仍待自己的完整 PG18/Flyway/Owner HTTP/A3，不能借用 V193 成果。
- **V193 / `600883236a3669f51b6703229447d93499b271cd`**：[完整 A3 #45012495202（run_attempt=2，SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45012495202) 验证其精确 SHA 的 V103 冻结数据库 DML 守护及合成 PG18；**不覆盖**后续 V194/V195。
- **V192 / `189932197ddeaadf1470f8692aa6be88d5e015c1`**：[完整 A3 #44896004578（run_attempt 2，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/44896004578) 验证真实 PG18 V188 逐订单 SKU 金额守恒，失败的首次验证与修复记录保留；仍无真实费用核验。
- **V191 / `8dab97147010fdc440ee81abcfa67cd1d1d57261`**：[快速 CI #44882890791](https://github.com/kunlong-luo/merchivra/actions/runs/44882890791) 与 [完整 A3 #44882890834](https://github.com/kunlong-luo/merchivra/actions/runs/44882890834) SUCCESS，完整 A3 **7/7 核心任务成功**；真实 Seller/Ads/银行**未启用**。
- [GitHub Pages #44882736480](https://github.com/kunlong-luo/merchivra/actions/runs/44882736480) 只验证虚构静态展示构建与隔离，不表示公开 ERP 已上线。
- **文档提交后的新 HEAD** 必须查该 SHA 的 CI，不能自动继承 V191/V193 业务 SHA 的 A3 结果。文档规则见 [ENG-001](../governance/engineering-standards.md)、[GOV-001](../governance/document-control.md)。

## 本地快速命令

```bash
./apps/server/gradlew -p apps/server --no-daemon test
(cd apps/web && pnpm install --frozen-lockfile && pnpm api:check && pnpm typecheck && pnpm test && pnpm build && pnpm build:vite)
python3 scripts/check_ci_cadence_v108.py
python3 scripts/check_docs_consistency_v109.py
python3 scripts/check_next_runtime_v122.py
```

**核心限制**：真实费用来源、重复扣费真实性、退款 COGS 回转、外部 WMS 物理实收、跨币种会计政策和银行结算均未因上述合成测试自动核验。不得把 `realizedNetProfit=null` 重写为已确认利润。生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) 一律 NO-GO；历史 [V108 CI 策略](../roadmap/mvp-freeze-release-v108.md) 为保留的阶段性记录，不优先于此页。
