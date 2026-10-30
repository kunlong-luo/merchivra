# ADR-0005 · ERP API 身份认证方案选择（未决）

- **状态：Proposed alternatives — DEC-09 未批准**
- 日期：2026-10-30
- 相关文档：[API-002](../../api/README.md)、[SEC-002](../../security/tenant-enforcement.md)、[IAM-001](../tenant-bootstrap.md)

## 候选方案比较

| 方案 | 优点 | 风险 / 注意 | 倾向 |
|---|---|---|---|
| 服务端 Session + HttpOnly/Secure/SameSite Cookie | 自部署 Web 同源容易管理、可集中撤销 | 需 CSRF 防御、Session 生命周期与存储 | **推荐评审** |
| 短效 JWT Bearer + Refresh Rotation | 移动端/API 客户端方便，标准 OIDC 易扩展 | 令牌撤销、XSS 存储风险、刷新复杂 | 仅 API 客户端明确要求时 |
| OIDC 外部身份提供方 | 可整合 SSO/MFA | 自托管部署依赖、用户管理复杂 | 后续可选 |
| 默认管理员口令 | 安装步骤简单 | 不可接受的共享凭证风险 | 排除 |

## 本轮结论

- 没有选定正式认证机制。可机读 OpenAPI 中的 `bearerAuth` **只是占位候选**，不是已生效产品承诺。
- 无论选什么方式，`tenantId` 必须来自可信认证主体/任务上下文，授权还要校验角色和 sellerAccount 范围；不能仅依靠前端隐藏。
- 首次引导必须有仅一次有效的随机凭证、禁止默认口令及重复初始化。
- 选择 Cookie 后应更新 OpenAPI securityScheme、CSRF 机制及 Mock 契约，不能长期保留与真实实现矛盾的 Bearer 描述。

## 通过条件
产品确认是否支持非浏览器 API 客户端、运维对身份提供方的偏好、安全评审完成 CSRF/MFA/Session 设计；对应 G2-AC-12/14/15/16 能被设计和测试。

**状态仍待签署**。
