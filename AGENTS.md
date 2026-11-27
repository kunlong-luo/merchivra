# Merchivra · 现行 Agent 与开发守则

> **CURRENT · 2026-11-24 / DOC-196。唯一生效的 Agent 指令入口。** 实际进度看 [当前事实](docs/current-status.md)，下一步只看 [现行 P0](docs/roadmap/current-plan.md)，工程实践见 [ENG-001](docs/governance/engineering-standards.md)。Vxxx 专项文档是历史证据，不是第二套开发指令。

## 工程基线

- Java 25 / Spring Boot 4.1.2 / Gradle Wrapper / PostgreSQL 18 / Flyway。只追加前向迁移，禁止改删已应用迁移；不使用 Maven。
- React 19 / Next.js 16.4.5 App Router / TypeScript 6 / Node 24 / pnpm 12.20.0。唯一 JS 锁文件为 `apps/web/pnpm-lock.yaml`。Next 是唯一认证 ERP 前端；Vite 只供公开虚构 Pages 构建（及 Vitest 工具链），不再有旧 ERP 回退入口。
- 复用 Session/CSRF/Owner/tenant/Marketplace、Springdoc/OpenAPI + Orval、Zod、TanStack、Base UI。Redis 按已批准运行边界使用，不另造事实库、鉴权、通用客户端。
- 普通变更直接提交 `main`，不新建例行分支/PR；写入前校验 HEAD，禁止 force push。代码、测试、文档须保持一致。

## 不可突破的财务及安全规则

1. 项目 pre-alpha；真实 Seller/Ads OAuth、实际出站/写入和银行核证关闭。生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**；真实材料只能在合法本地/受控环境取得。不得提交原件、凭据、密钥、银行和客户隐私。
2. UNKNOWN ≠ 0；金额用 BigDecimal/NUMERIC、保留原币，跨币折算须正式批准。原始报告/行/批次身份不可覆盖；更正追加并保留来源、归属和复核记录。内部 Maker/Checker、用户 SHA 或本地签名不等于独立外部签发。
3. V64/V188 仅已知贡献/敏感度，V194 捕获指纹不认证来源。原销售批次数量、实际采购/头程/关税、真实退货处置和 COGS 回转必须可独立验证；缺失则保持 NULL。退款收入不得重复扣除；V91/V92/V112 单来源归属、V94/V95/V102/V165 重复行门禁不可绕过。库存签名不自动证明物理实收。
4. 保持 V113 事务有序租户 epoch、V114/V115 无变化更新边界；证据读取的 ABA、跨站点、数量守恒、审计缺失/重复、超限或漂移必须失败封闭。历史未捕获证据不得事后补造。合成性能或 HTTP 强制交错不等于生产 SLO。
5. 原始 Reports 在途 scope 必须比较 Seller/tenant/Marketplace/区域/刷新凭证/角色及已实现的授权版本，原请求不能改归新授权；复用租约及数据库权限检查，不记录秘密，不将报告 staging 当作过账或真实认证。V208/V209 的内部签名、PKIX/CRL 只能按实际证明边界使用。
6. G1/DEC-06/07/08 正式签署、真实费用、资金归属及退款 COGS 完成前，`realizedNetProfit=null`。禁止用合成测试或展示页解锁财务状态。独立生产 G2/G3 签署另行验收。

## 执行与验收

- 先核对当前代码/迁移/API/测试与 [当前事实](docs/current-status.md)，只实现真正缺口，复用已有链路。
- 每次明确输入、原币、租户、未知/冲突、兼容性和正反例。财务、权限、库存、数据库和跨进程变更按 [CI/A3 分层规范](docs/testing/ci-policy.md) 验收；仅 disposable PG18 可执行破坏性合成脚本。
- 每次记录精确 SHA、实际通过/失败/跳过的任务，不能继承旧 SHA 的绿灯。评分只引用 [当前评分记录](docs/governance/project-scorecard-v231-2026-11-24.md) 的最新复评，不在本规则硬编码当前分数；不因版本数、重复 UI、内部自述或文档数量自动加分。V232/E1 及后续真实关卡以当前计划为准。
- 更新权威入口，不重复创建规则文件：事实进 current-status、计划进 current-plan、规范进 ENG-001，历史版本与精确 CI 进专项证据。具体删留遵循 [文档控制](docs/governance/document-control.md)，目录作用见[根 README](README.zh-CN.md)。禁止重建旧 `Agent.md`。
- 本地默认 Next 3000 + Spring 8080 + PG18；单独前端启动不会启动后端。401 未登录、403 权限不足、5xx/连接异常服务未准备；不得禁用鉴权或暴露 Actuator。UI 需验证移动/桌面、键盘、加载/空/错误/权限状态，见 [DESIGN.md](DESIGN.md) 与 [开发指南](docs/development.md)。

## 单任务会话（强制）

- 新会话先读本文件、[当前任务](docs/roadmap/current-plan.md)、[当前事实](docs/current-status.md)，核对 main HEAD 与该代码 SHA 的 Actions，禁止依赖旧聊天或直接递增 V 编号。
- 优先续办未 VERIFIED 的当前任务；同一会话只处理一个任务。按目标、范围、验收和依赖执行，未经维护者批准不越界。提交前核对 HEAD，不 force push。
- 会话退出前更新任务状态及交接：准确代码 SHA/可定位提交、变更边界、CI 成败及跳过、阻塞、唯一下一动作；没有完成验收写 IMPLEMENTED_PENDING_VERIFICATION 或 FAILED/BLOCKED，不称 VERIFIED。只在 VERIFIED 后领取下一编号。CI 可跨会话完成；新会话必须复核，不重做已经证明的工作。
- 历史验收只在领域文档、精确 Git 快照及 Actions 保留，入口不复制流水账。详细状态定义和关闭规则见 [GOV-001](docs/governance/document-control.md)。

## 查阅顺序

[文档导航](docs/README.md) → [当前事实](docs/current-status.md) → [现行计划](docs/roadmap/current-plan.md) → [工程规范](docs/governance/engineering-standards.md) → [文档控制](docs/governance/document-control.md)。详细版本历史可查领域文档及 [V232 清理前 AGENTS 原文](https://github.com/kunlong-luo/merchivra/blob/96e6d97509930c88c85e31f013eb7bd483d90ce9/AGENTS.md)；历史 Proposed 不等于 Accepted。
