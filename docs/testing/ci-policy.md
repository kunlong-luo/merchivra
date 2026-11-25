# TEST-003 · 当前 CI / 完整 A3 验收策略（TEST-003 / DOC-196）

> **2026-11-25 · 当前维护约定**：日常开发与文档修改直接提交 `main`，不为每个 Vxxx 开发重复创建分支/PR。快速 CI 保证构建与高价值单测；PG18 全链路、Chromium/Next、真实 Docker Compose 是有条件执行的**隔离合成**里程碑验收；两者均**不是**真实 Amazon/银行核证或生产许可。见 [事实基线](../current-status.md)、[发布阻断](release-readiness-v68.md)。

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

## 质量金字塔

1. 领域单元测试：成本有效日期、SKU 映射、金额计算、状态迁移。
2. Adapter 契约测试：Amazon DTO → 标准化 DTO，含不同站点/错误结构。
3. PostgreSQL 集成测试：Flyway、事务、唯一键、分页检查点和幂等。
4. API 安全测试：未授权、角色权限、跨租户负例和导出权限。
5. UI 端到端：管理员接入 Mock、查看订单、库存、预估利润与同步失败。
6. 运维演练：限流、Worker 崩溃、数据库恢复、凭证失效。

## Mock 数据集

- 2 个租户、2 个卖家账户、3 个 Marketplace，不包含真实卖家 ID。
- 同 ASIN 对应不同账号 Seller SKU，两个不同币种的订单。
- FBA 可售/预留/在途快照；过期、未获取与实际 0 三种场景。
- 一条先来退款、后到订单；一条重复财务事件；缺失成本/汇率案例。
- 429、401、500、分页过期、网络中断样例，不包含真实响应 PII。

## 测试记录格式

每个 AC 记录：用例编号、构建 SHA、测试环境、输入 fixture、执行人、结果、证据链接、缺陷编号。通过率不能替代高优先级缺陷清零与安全评审。


参见 [验收标准](../requirements/acceptance.md)、[需求追踪](../requirements/traceability.md)、[E2E 场景集](end-to-end-scenarios.md)。TEST-001 原早期测试计划已归并；历史 M0/M2 尚未实施 E2E 的状态不再是现行约束。

## 本地快速命令

```bash
./apps/server/gradlew -p apps/server --no-daemon test
(cd apps/web && pnpm install --frozen-lockfile && pnpm api:check && pnpm typecheck && pnpm test && pnpm build && pnpm build:vite)
python3 scripts/check_ci_cadence_v108.py
python3 scripts/check_docs_consistency_v109.py
python3 scripts/check_next_runtime_v122.py
```

**核心限制**：真实费用来源、重复扣费真实性、退款 COGS 回转、外部 WMS 物理实收、跨币种会计政策和银行结算均未因上述合成测试自动核验。不得把 `realizedNetProfit=null` 重写为已确认利润。生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) 一律 NO-GO；历史 [V108 CI 策略](../roadmap/mvp-freeze-release-v108.md) 为保留的阶段性记录，不优先于此页。

## 历史验收索引（非现行策略）

具体测试范围、成功/失败/跳过及精确 SHA 统一在 [当前事实](../current-status.md)、各领域专项证据维护；不在策略正文复制历史流水账。以下精确 Git 快照保留原 CI 策略内全部 V191–V222 叙述、首次失败及执行边界（含原有章节标题），以及原 TEST-001：

- [V239 CI 策略完整历史快照](https://github.com/kunlong-luo/merchivra/blob/2c2265036df33f0afc7269ff392eea8a5a5059e4/docs/testing/ci-policy.md)
- [TEST-001 原文](https://github.com/kunlong-luo/merchivra/blob/2c2265036df33f0afc7269ff392eea8a5a5059e4/docs/testing/test-plan.md)

原版本的验收链接与源日志仍可逐项追溯。V241 最新确认完整 A3 以及 V238–V242 快 CI 结果见当前事实；新提交必须独立验收，不继承旧 SHA。
