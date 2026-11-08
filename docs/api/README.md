# API-002 · G2 OpenAPI 设计草案与真实后端契约（2026-11-08）

**本页的 [候选 OpenAPI JSON](openapi.json) 只用于早期 G2 设计评审**（10 条路径、11 个 HTTP operation、19 个 Schema），不是现行所有后端接口的实时目录。其中历史 Bearer/JWT 方案未部署为当前业务认证，不应据此实现新客户端。

## 当前已实现的 API 与代码生成

- Spring Boot 4.1.2 MVC 已有 Session/CSRF、租户与 Marketplace 范围的实际业务接口，涉及 Seller/店铺、SKU/Listing、订单、FBA、采购/供应商/仓储、财务、运营异常和员工权限。代码从 `apps/server/src/main/java/org/merchivra/erp/` 的 Controller 与服务实现核查；现有 [核心 API 历史说明](core-read-api.md) 是 V3 的设计/实现记录，不是完整现行列表。
- **真实 Controller/OpenAPI/Orval 契约已经部分落地**：`apps/web/openapi/finance-profits.json`、`fba-inbound.json`、`ads-reports.json`、`operations-v67.json`；与 `apps/web/orval*.config.ts`、`apps/web/src/generated/` 配套。详见 [Springdoc / Orval 渐进接入方案](../architecture/mature-tool-first-api-client-2026-11-01.md)。这些 JSON 只覆盖对应模块，不能误称“所有 REST API 都已迁移至 Orval”。
- 实际运行时为 **Spring Security Session + HttpOnly Cookie + CSRF**，不是历史候选 JSON 的 JWT `bearerAuth`；所有请求的服务端权限和原币/来源数据校验仍不可仅依靠 TypeScript 类型。
- **真实 Amazon Seller/Ads 生产出站与写入依旧默认关闭**；`/api/v1/demo/*` 是单独的合成 Mock 命名空间，见 [Mock 演示指南](../demo.md)。已有 OAuth/Worker 代码不代表真实 Amazon 授权完成。
- 功能冻结前应按 [V108 MVP 计划](../roadmap/mvp-freeze-release-v108.md) 与 [P0 采购容量审计](../testing/p0-purchasing-read-cap-audit-v108.md) 检查每个实际 Controller/Orval 约束；G1/G2/G3 仍需审批。

## 契约设计原则

- 所有业务路径基于 `/api/v1`；只读操作不暴露 Amazon SP-API token、买家 PII 或未验证的租户 ID。
- **已实现的最小本地认证**采用 Spring Security Session + HttpOnly Cookie + CSRF，见 [SEC-003](../security/local-auth.md)。JSON 文件中的 bearerAuth 仍是**未同步到实际实现的设计候选**，不能用于推断现有端点使用 JWT；正式 G2/G3 冻结时必须更正。
- 金额 `{amount: "40.00", currency: "USD"}`，string Decimal 保持精度；未知/未分配不可默认为零。
- Orders 的履约/退款/财务状态并列；FBA 观测允许 null 数量（UNKNOWN）；利润预计与平台入账分别报告。
- Sync Run 是可重试运行，内部持久 Sync Stream 和 Amazon paginationToken 不暴露给 Web。
- 每个 operation 当前只有设计级错误列表（401/403/404/409 等），真实角色、字段限制、异常全集、认证方案仍须 G2 评审。

## G2 → G3 的检查

- [ ] DEC-09 登录/Session/Auth 确定后调整 securityScheme、CSRF 与会话响应。
- [ ] 完整的 OpenAPI linter 与 Schema 验证加入 CI；当前仅做引用、operationId、响应基础自检。
- [ ] 端点权限矩阵和所有异常响应与 [API-001](../architecture/api-contracts.md) 一致。
- [ ] 确认实体 UUID、业务键/商店范围与 [DATA-003](../architecture/physical-data-design.md) 一致。
- [ ] 使用全虚构 Mock fixtures 校验设计请求/响应。
- [ ] 根据所获 Amazon API 角色调整不可用/延迟数据状态。
- [ ] 通过 [G2-AC](../requirements/g2-architecture-acceptance.md) 并留签署记录。

参考：[OpenAPI 3.1.1 官方规范](https://spec.openapis.org/oas/v3.1.1.html)。
