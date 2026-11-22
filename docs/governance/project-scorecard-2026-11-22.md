# Merchivra · 项目成熟度评分基线（2026-11-22）

**评估结论：68/100（专家定性评估，不是自动化测试分数或生产批准）**。评估基线为整理后的 `main` [`c6e1ff991ffdf8f74ba6fe9e9f91e0274475d644`](https://github.com/kunlong-luo/merchivra/commit/c6e1ff991ffdf8f74ba6fe9e9f91e0274475d644)，其 [快速 CI #45017099593](https://github.com/kunlong-luo/merchivra/actions/runs/45017099593) **SUCCESS**，完整 PG18 job 在该快速 CI 中 SKIPPED。最新**完整** A3 仅针对更早的 V193 [`6008832`](https://github.com/kunlong-luo/merchivra/commit/600883236a3669f51b6703229447d93499b271cd)：[A3 #45012495202 / attempt 2](https://github.com/kunlong-luo/merchivra/actions/runs/45012495202) SUCCESS。**V194/V195 在本评分基线没有对应完整 A3**。

## 评分模型

| 维度 | 满分 | 得分 | 主要证据和扣分理由 |
| --- | ---: | ---: | --- |
| 架构与技术选型 | 20 | **18** | Java25/Spring Boot4.1.2/Gradle/PG18、Next16/React19/TS6/pnpm12 统一；模块与 OpenAPI/Orval 清晰，目标部署还待认证 |
| 业务功能 | 20 | **16** | 本地店铺、订单、SKU、采购/FBA、仓储、异常与财务可用代码较多；真实 Seller/Ads 外部 API 尚未授权 |
| 全成本与利润真实性 | 25 | **10** | V64 贡献与 V91–V104/V162–V194 证据/预览基础已存在；外部费用真实性、退款 COGS、G1/DEC-06/07/08 未签署，`realizedNetProfit=null` |
| 安全与可信数据 | 15 | **11** | Session/CSRF、租户/Owner、双人审查、不可变 SQL、源证据阻断；生产 IAM、外部 WMS/银行/Ads 与 G1/G2/G3 待验 |
| 前端体验 | 10 | **8** | Next App Router 和业务工作台已实现，V195 明确区分会话故障；真实操作可用性、错误恢复及用户测试仍欠缺 |
| 测试、运营与发布 | 10 | **5** | 快 CI、Gradle/Vitest/Chromium/PG18/Compose 的历史基础存在；V194 后完整 A3、真实目标 TLS/KMS/PITR/外部告警与 #104 仍未放行 |
| **总计** | **100** | **68** | **成熟的内部工程原型，尚未达到可正式经营/生产批准的 Amazon ERP** |

**评分是按上表权重的人工判断**，不能把文件数、测试数或版本号等同于商业完成度。仓库约有 274 个生产 Java 文件、150 个 Java 测试文件、86 个前端测试文件、101 个 Flyway 迁移文件，证明已有工程积累，**不证明真实平台数据可靠**。V194 的本地 SHA-256 指纹不是第三方签名。

## 用什么事实提高分数

- **短期先验证，不堆 UI**：V194 的 PostgreSQL18/Flyway V104 真实安装、V103 不变性、旧证据/指纹错配、Owner HTTP 和 V188/V64 的 fail-closed，在含实现代码的精确 SHA 完整 A3 运行；本轮新增 SQL 专项仅覆盖数据库约束，不能单独代替完整 Owner 业务验收。
- **财务本质**：通过合法授权及独立证据，核验费用来源、归属、退款 COGS 和原币历史成本，并由 G1/DEC-06/07/08 批准口径。没完成之前利润项得分不能因更多瀑布卡片显著增加。
- **真实发布门禁**：生产 TLS、KMS、备份/PITR、外部告警、第三方 WMS、RPO/RTO 和负责人书面批准应产生可审计证据，而不是从合成 CI 绿灯推导。
- **产品体验**：本地 Spring/PG18 的 HTTP 500 根因仍未诊断，V195 仅修复用户可见错误分级和提示。应有真实环境日志和复现验收。

## 复评分与状态管理

每次重大阶段按同样六维权重更新**新一份评估记录**，显式说明基线 SHA、测试 URL、已通过/失败/跳过和新增证据。评分变化不自动更改 [当前事实](../current-status.md)、[P0 计划](../roadmap/current-plan.md) 或 [文档控制](document-control.md)；未签署外部政策仍是 Proposed。

**发布结论独立于分数**：项目仍为 pre-alpha，Amazon Seller/Ads 真实 OAuth 与写入关闭，`realizedNetProfit=null`；生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。即便评分升高也不能绕过 G1/G2/G3 与真实运营证据。
