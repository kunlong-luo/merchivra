# Merchivra · 现行 Agent 与开发守则

> **CURRENT · 2026-11-22 / DOC-196。唯一生效的 Agent 指令入口。** 旧 `Agent.md` 只是跳转；V1–V195 版本说明和 Git 历史是证据，不是叠加的永续指令。发生冲突先核对实际代码、当前决策和精确 SHA 的测试，按[文档控制规则](docs/governance/document-control.md)处理。此文件规定执行边界；具体实践见[工程规范](docs/governance/engineering-standards.md)。

## 必须遵守的工程基线

- **Java 25 / Spring Boot 4.1.2 / Gradle Wrapper / PostgreSQL 18 / Flyway**；不引入 Maven 或 PostgreSQL 17 作为目标。
- **React 19 / Next.js 16.4.5 App Router / TypeScript 6 / Node 24 / pnpm 12.20.0**。唯一 JS 锁文件 `apps/web/pnpm-lock.yaml`。只用 pnpm 命令及冻结安装；默认 `pnpm dev`、`pnpm build` 是 Next.js。Vite 仅为受控回退和虚构的公开 Pages 展示。
- 后端 Session/CSRF/Owner/租户/Marketplace 执行权限；OpenAPI/Springdoc + Orval、Zod、TanStack、Base UI 优先复用现有成熟组件。Redis 8 可选，不得新增平行事实库、第二套鉴权或重复手写通用 HTTP 客户端。
- 日常功能、修复、文档和依赖变更**直接在 `main`** 进行，不创建例行功能分支/PR。更新前检查 `main` HEAD，提交时保护并发更新，**禁止强制覆盖 `main`**。不用 PR 不意味着省略审核、测试或安全门禁。

## 绝对不可突破的业务与安全边界

1. 项目 **pre-alpha**，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。真实 Amazon Seller/Ads OAuth、实际 API 出站/写入和真实银行核证关闭；只能使用明确隔离的合成数据。未经维护者本地明确授权、外部合规与 G1/G2/G3 审批不得擅自开启。
2. **UNKNOWN ≠ 0**；跨币种不得未经批准合并；金额使用 BigDecimal/PostgreSQL NUMERIC 和原币精度，保留报告/行/来源版本、归属、审计及独立复核。人工 `APPROVED_UNVERIFIED` 不代表外部真伪，不自动入账。
3. V64 是**已知范围贡献**，V188 是**原币敏感度预览**；V194 指纹只证明本地冻结数据能独立重算，不能证明来源真实性。退款 COGS 回转和 G1/DEC-06/07/08 尚未签署；`realizedNetProfit=null` 必须继续保持，不得用合成费用伪造“净利润”。
4. 库存及 WMS 签名不自动证明第三方运营商真实身份或物理实收，不以内部三方 QC 替代外部确认；库存过账与退款回转必须受证据和批准限制。
5. 不提交或输出任何实际 Seller/Ads、银行、客户或 WMS 凭据、密钥、会话和私有证据。不要关闭 Session/CSRF、绕过 Owner/租户校验或公开 Actuator 来解决本地启动错误。

## 每次执行的固定步骤

1. **先核对事实**：读 [当前状态](docs/current-status.md)、[执行计划](docs/roadmap/current-plan.md)、相关 Controller/Flyway/React 页面/测试，明确“已有能力、真实缺口、外部待批准”。先复用，再扩展；不要重复造账本、审批或 API 客户端。
2. **缩小变更范围**：写清楚输入、原币/来源状态、租户与权限、未知/冲突处理、回滚兼容性、将受影响的文档。任何数据库更改均追加 Flyway 新迁移，不能改已应用迁移或补造历史证据。
3. **随改随测**：新增聚焦的正反例。每次 `main` 提交查**该精确 SHA** 的快速 CI；涉及钱、权限、库存、数据库、会话、回退与跨进程的变更按 [CI 分层策略](docs/testing/ci-policy.md)开展 PG18/HTTP/Chromium/Compose/A3，明确列出跳过或未跑的任务。
4. **更新权威文档**：当前事实写入 `docs/current-status.md`，下一步写 `docs/roadmap/current-plan.md`，规范写 `docs/governance/engineering-standards.md`，研发/部署/测试指令分别维护单一入口。Vxxx 记录只保留该版本可复核的历史证据。
5. **真实状态如实报告**：区分“写入 main、快 CI 通过、精确 SHA 完整 A3 通过、外部核验/生产已批准”。一项未验收不得借用旧 SHA 的 green。

## UI / 本地启动不可退让的规则

- 正式 ERP 默认 Next.js 16（3000）+ Spring API（8080）+ PostgreSQL 18。只执行 `pnpm dev` 不会自动启动后端或数据库。
- `/api/v1/auth/me`：**401 = 未登录**，**403 = 权限问题**，**5xx/连接异常 = 服务尚未准备**，无效成功响应不得当已登录或 guest。界面用清晰可恢复的状态呈现，诊断折叠展示，**不能通过禁用鉴权掩盖错误**。当前尚无用户机器上的 Spring 日志，不能断言其 HTTP 500 根因已修复。见[本地排错](docs/development.md#本地启动时显示服务暂时未就绪)。
- 在 360px 及桌面视口检查真实业务页面的加载、空态、错误态、权限拒绝、原始数据来源与键盘操作；错误文案不得暗示认证/利润已通过。设计原则参考 [DESIGN.md](DESIGN.md)。

## 查阅顺序与文档生命周期

[文档导航](docs/README.md) → [当前事实](docs/current-status.md) → [唯一执行计划](docs/roadmap/current-plan.md) → [工程规范](docs/governance/engineering-standards.md) → [文档控制](docs/governance/document-control.md) → [开发指南](docs/development.md) → [CI 规则](docs/testing/ci-policy.md)。

G1/G2/ADR 的 **Proposed** 不等于 **Accepted**；版本归档中的“最新”“未做”仅针对当年提交。**本 Agent 文件不再重复拼贴 100 个版本的历史执行日志。**历史全量内容可用 [本轮整理前的 Git 文件](https://github.com/kunlong-luo/merchivra/blob/2136375590abaae2373c565a1de930aa4ce45483/AGENTS.md) 查看。
