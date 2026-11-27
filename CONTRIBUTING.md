# Contributing / 贡献指南

> **现行维护策略（2026-11-21）**：Merchivra 仍为 **pre-alpha / production NO-GO**。项目维护者已决定**直接在 `main` 开发与提交**，不为日常功能、修复和文档工作创建临时开发分支或例行 PR。贡献者请先通过 Issue 讨论范围；此处不承诺外部直接 push 权限，也不要求维护者再建立重复分支。生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104)、真实 Seller/Ads 授权均保持关闭。

先读：[当前事实基线](docs/current-status.md) · [现行执行计划](docs/roadmap/current-plan.md) · [文档导航](docs/README.md) · [分层测试](docs/testing/ci-policy.md) · [安全政策](SECURITY.md) · [Agent 约束](AGENTS.md) · [工程实施规范](docs/governance/engineering-standards.md) · [文档管理](docs/governance/document-control.md)。

## 已采用的工具（不要重新引入旧栈）

| 层 | 唯一/默认选择 |
| --- | --- |
| 后端 | Java 25、Spring Boot 4.1.2、Gradle Wrapper、PostgreSQL 18 + Flyway |
| 前端 | React 19、Next.js 16.4.5 App Router、TypeScript 6、Node 24 |
| 包管理 | **pnpm 12.20.0**，唯一锁文件 `apps/web/pnpm-lock.yaml`，不再维护另一个 JS lockfile |
| API/组件 | Springdoc/OpenAPI + Orval、Zod、TanStack Query/Table、Base UI |
| 运行 | 唯一认证前端 Next 独立 Node；Vite 仅用于 Pages 虚构展示与测试；Redis 可选 |

## 开发与自检

```bash
git switch main
git pull --ff-only origin main

# 后端（Java 25）
./apps/server/gradlew -p apps/server --no-daemon test

# 前端（Node 24，pnpm 12.20.0）
cd apps/web
corepack enable
pnpm install --frozen-lockfile
pnpm api:check
pnpm typecheck
pnpm test
pnpm build
pnpm build:pages # 仅公开虚构展示，非 ERP
cd ../..

python3 scripts/check_docs_consistency_v109.py
python3 scripts/check_ci_cadence_v108.py
python3 scripts/check_next_runtime_v122.py
```

在本地同时开发时，先启动 Spring API（`127.0.0.1:8080`），前端执行 `pnpm dev`（Next，`127.0.0.1:3000`）。完整 Docker/spark-db、备份安全和敏感环境变量操作请按[部署指南](docs/deployment/local-spark-db.md)执行；**严禁**对共享数据库运行 `down -v` 或合成恢复脚本。

## 提交与验收要求

1. 先审查现有模块、Flyway、OpenAPI/Orval、人工复核和重叠费用门禁；优先复用，不造平行账本、审批机制或又一套 REST 客户端。
2. 对每个修改记录：需求/Issue、代码与测试范围、原始币种及来源状态、是否触碰租户/会话/审核/持久化；不把测试全绿写成真实 Amazon 已核验。
3. 直接修改和提交 `main` 前核对 HEAD，保留可追溯提交消息；每次提交后检查 [快速 CI](https://github.com/kunlong-luo/merchivra/actions/workflows/ci.yml)。身份、金额、库存、Flyway、外部证据或跨进程变化需附相应真实 PG18/HTTP/浏览器证据，必要时运行 [完整 A3](https://github.com/kunlong-luo/merchivra/actions/workflows/milestone-validation.yml) 并记录精确 SHA。
4. 后端必须做租户/Marketplace、Session、CSRF、Owner 双人审批和原币精度负例。不要把 `UNKNOWN` 费用填零、将人工 `APPROVED_UNVERIFIED` 当可入账凭证，或让 WMS 内部签名替代外部实收。
5. 涉及用户当前操作的变更同步更新 [事实基线](docs/current-status.md)、[现行计划](docs/roadmap/current-plan.md)、[开发](docs/development.md)、[部署](docs/deployment/local-spark-db.md) 或 [CI](docs/testing/ci-policy.md) 的相关部分；旧里程碑文档保留历史时态。

请勿向仓库、CI、Pages、日志或示例提交真实 Seller/Ads、银行、客户或 WMS 私有凭证。安全漏洞请使用[私密报告渠道](SECURITY.md)。
