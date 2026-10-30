# PLAN-001 · 文档先行与分阶段交付路线

> **状态更新（2026-10-30）：PR #1 工程骨架与 PR #14 文档已合入 main；在 main 按 M0→M4 逐步开发。** G1/G2 业务与架构决策仍待签署，未确认部分不应作为真实 Amazon 生产契约。

| 阶段 | 目标 | 交付物 | 通过条件 |
|---|---|---|---|
| D0（现在） | 文档基线 | PRD、FR/NFR、业务图、架构图、ERD、同步、集成、安全、UI、测试、运维 | G1/G2 问题有结论 |
| D1 | 文档评审与定稿 | 业务决策、真实站点与利润口径、ADR 接受记录 | 所有 P0 决策无关键 TBD |
| M0 | 工程骨架验证 | Docker、CI、最小 Web/API、鉴权设计 | 可运行 Mock、无公网上线风险 |
| M1 | 账号 + 同步底座 | Seller 授权、Mock、任务与限流 | 安全授权、可重放 |
| M2 | 商品/订单/库存 | 业务 CRUD、数据同步 | 样本业务验证 |
| M3 | 财务/成本/利润 | 费用、成本与估算报表 | 可对账、可追踪 |
| M4 | Beta | 安全、运维、发布 | P0 验收通过、运行手册完成 |

## 第二轮评审状态（2026-10-30）

目前完成候选设计的交叉修订，但 G1、G2、G3 均**尚未通过**。下一步不是立刻启动 M0/M1，而是处理 [REVIEW-002](governance/review-report-2026-10-30.md) 的 Blocker/High 项，邀请运营与财务确认 [DEC-001](governance/decision-register.md) 后再签署相关 ADR。

## 第三轮 G1 产品设计补全（仍需批准）

[产品业务评审包](product/g1-review-package.md) 已定义 US/NA 试点候选、FBA/FBM 范围、SKU 映射和财务口径；[虚构案例](product/business-scenarios.md) 包括销售/部分退款/FBM 缺费/财务重复/库存未知等。下一步由负责人完成 [G1 决策](governance/decision-register.md) 和 [G1 业务验收](requirements/g1-business-acceptance.md) 的评审记录，然后才能正式启动 G2 物理数据/接口的细化与冻结。

## 第四轮 G2 约束级架构补全（候选）

[DATA-003 物理模型](architecture/physical-data-design.md)、[ARCH-003 一致性](architecture/consistency-boundaries.md)、[INT-003 来源契约](integrations/amazon-data-contract.md)、[API-002 OpenAPI](api/README.md) 及 [G2 评审报告](governance/g2-review-2026-10-30.md) 已成为可审阅的下一阶段设计稿。**G1 未签署，G2 亦未通过**；不得立即实施 Flyway/业务 API。后续先明确 DEC-04/05/06/07/09/11/13，再批准物理唯一约束和正式 Auth/API 方案。

## 开发阶段 CI 决策（已确认）

按项目负责人要求，**M0～M2 轻量 CI**：后端快速单测，前端类型检查、少量 Vitest 与构建。**不执行 Playwright E2E、跨浏览器矩阵、覆盖率或完整容器测试门禁**。M3 在主要业务流程能联通后逐步补关键 E2E，M4 Beta 上线前再按实际安全/业务风险加强自动检查。详见 [TEST-003](testing/ci-policy.md)。

这是开发效率决策，不表示取消真实授权、租户隔离和财务关键规则的必要验证，也不意味着已获准生产部署。

## 建议工作优先级

- 当前第一优先：回答 [Q01–Q08](requirements/decision-questions.md)，进行产品与利润口径评审。
- 第二优先：确认 [ER 图](architecture/data-model.md)、[同步设计](architecture/sync-engine.md)、[安全模型](security/security-design.md)。
- 第三优先：确定 Amazon 开发者权限/站点与真实 API 版本支持矩阵。
- 只有上述通过后，再从已建立的 GitHub Issues 认领开发任务。

## 不建议立即交付的内容

真实业务代码、生产部署脚本、数据库最终 DDL、最终高保真 UI，以及任何声称实际接入成功的演示。这些都依赖需求和授权决策。

## M2 核心库结构进度（2026-10-30）

- [x] Flyway V2 创建租户、Seller Account、Marketplace、内部 SKU、Listing 映射、订单/订单项、FBA 观测和 Sync Stream 等核心表，并在跨实体关联上约束 `tenant_id`。
- [x] 固定虚构账户可通过本地 Mock 成功同步写入结构化核心表，在 Web 同步页展示数据计数；导入使用稳定来源键及幂等插入。
- [ ] 真正的授权 Session、租户/账号权限作用域、真实 Seller 数据适配与 Finances 仍未实现；无真实业务 API。
- [ ] V1/V2 在实际 PostgreSQL 的迁移执行及跨租户失败用例尚未验收，CI 仍仅为快速编译、类型检查和少量单测。

## 本地模拟调用链补齐（2026-10-30）

- [x] 按 INT-002/INT-003/SYNC-002 **语义**添加 MockSpApiClient / 固定 US/DE 分页 Orders、Listing、FBA 场景；没有真实 HTTP Amazon 调用。
- [x] 本地 Owner 当前租户的 LOCAL/UNCONFIGURED Marketplace 可以显式触发 Sandbox 模拟导入，保留 MOCK 来源、事务、审计和幂等记录；前端通过核心业务 API 读取。
- [x] 添加 [TEST-004](testing/mock-full-chain.md) 与手动 Compose 校验脚本，不增加 M0～M2 日常强制 E2E。
- [ ] 真实 SP-API OAuth/角色、官方 DTO 的映射与分页 cursor/Worker 的租约 fencing、Finances 对账/成本利润、生产权限/备份仍未实现；不能把本地模拟运行称为已批准业务交付。

## 2026-10-30 · 实际 Compose 端到端模拟联调

- [x] [Compose 全链路成功运行（2026-10-30）](https://github.com/kunlong-luo/merchivra/actions/runs/41061679196)：真实 Nginx + Spring Boot + PG 构建并执行两店铺 Mock source → 订单分页、Listing、FBA → 持久化 → 登录用户 SQL 查询 → 运行/审计 → 重启后复核 → 退出失效。
- [x] 修复根目录 Compose `.env` 读取和 US/DE 共享内部 SKU 外键冲突；原始币种与 FBA NULL/0 均保留。
- [ ] 真实 SP-API 接入、页级持久检查点/worker fencing、Finances/结算/成本利润、正式租户安全、备份及生产部署仍未完成，不能把 Mock 测试结果当成正式业务验收。
