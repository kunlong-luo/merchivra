# API-002 · 可机读 OpenAPI 3.1.1 候选草案

**状态 Proposed，不代表已经实现。** [查看 OpenAPI JSON](openapi.json)（10 条路径、11 个 HTTP operation、19 个 Schema）。它用于架构审查和未来契约测试，不是部署在服务器的接口。

## 契约设计原则

- 所有业务路径基于 `/api/v1`；只读操作不暴露 Amazon SP-API token、买家 PII 或未验证的租户 ID。
- 服务器认证决定活动租户和获准访问的 Seller Account 范围。JSON 中的 bearerAuth 是 **DEC-09 的候选方案**，不是已决定使用 JWT；可由 HttpOnly Session/OIDC 替代。
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
