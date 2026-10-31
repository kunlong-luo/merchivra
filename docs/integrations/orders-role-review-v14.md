# V14 · Orders 应用角色双人审核流程（默认关闭）

本功能不是从 Amazon 自动获取/验证应用角色；Amazon Seller Marketplace 参与状态和 OAuth 成功**均不能证明**应用已获得 Orders v2026-01-01 操作角色。为填补 V12 独立证据表的人工操作缺口，V14 提供有审计的双人审批。

## 启用条件与权限

默认 `MERCHIVRA_SPAPI_ORDERS_ROLE_REVIEW_ENABLED=false`，所有端点不注册。仅当已经具备真实 Seller OAuth、Marketplace verification、独立外部角色批准记录（如 Amazon 开发者控制台的应用角色审批记录）和受信任的多用户 OWNER 管理后，才考虑启用。

- 申请者：当前租户的有效 **OWNER**，提交 64 位小写 SHA-256 证据摘要、8–128 字符非敏感外部案件号。**不接受文件内容、令牌、买家 PII、完整授权 URL**。
- 审核者：必须是该租户的另一位有效 **OWNER**，且**另外**经受信任管理员预先授予 `SECURITY_REVIEWER` 角色；可以限制到指定 Seller Account。审核人必须人工核对外部应用角色已由 Amazon 批准、Seller grant、case reference 和原始证明材料的 SHA-256，**仅点击批准并不自动验证证明真伪**。
- 自己申请自己批准、缺失 SECURITY_REVIEWER、撤销/过期 Seller grant、grant 版本不同或尚未完成核验：不能生成角色证据。
- 审核通过后生成 V12 的 `EXTERNAL_APP_ROLE_REVIEW` 记录，有效期仅 7 天；过期、角色审核员的会员权限失效或 grant 重新核验后，`SpApiSellerGrantVault.loadOrdersApproved` 再次拒绝授权。
- 每次申请、批准、驳回、失效和撤销都写入单独审计表。Owner 可在应急情况下撤销，无需另一个审批人。

当前项目尚无经过生产验收的多用户账号开通/管理员权限分配 UI，因此**不会自动赋予任何账号 SECURITY_REVIEWER，也不会伪造已审批的角色数据**；管理员须通过受信任的权限配置流程预先准备两个独立用户，方能执行审核。

## HTTP 端点（Owner Session + CSRF）

启用后：

- `GET /api/v1/seller-accounts/{accountId}/orders-role-review`：返回本租户账号最近 50 条审核元数据，包含证据摘要、申请者和外部案件号；不返回任何 Seller refresh token。
- `POST /api/v1/seller-accounts/{accountId}/orders-role-review`：JSON `{"evidenceSha256":"<64 lowercase hex>","externalCaseRef":"APP-CASE-12345"}`，仅创建 `PENDING` 申请。
- `POST /api/v1/seller-accounts/orders-role-review/{reviewId}/approve`：**审核者**核对外部证据后批准。
- `POST /api/v1/seller-accounts/orders-role-review/{reviewId}/reject`：审核者驳回。
- `POST /api/v1/seller-accounts/{accountId}/orders-role-review/revoke`：Owner 紧急撤销最近批准的角色证据。

所有请求从服务器 Session 获取 tenant 和 actor；提交体不允许自带 tenantId/roleCode。POST 使用 Spring Security CSRF。外部未知或跨租户 ID 不暴露任何有效角色。

## 验收边界

Java 25 单测检查坏证据格式不写库、无 Seller grant 拒绝申请、申请者不能自批、无审核角色不能批准、独立审核者批准时写入 V12 证据和审计、拒绝不会生成角色证据。PostgreSQL 18 Compose 测试验证 Flyway V14、默认申请/审计/角色证据表均为空以及原 V11A Mock 分页重启链路。

当前**没有**真实 Amazon 审核材料，也没有实际角色批准证据，因此任何生产 LIVE Orders 请求仍默认禁止。不能把这种离线双人批准逻辑当作 Amazon 已核验或生产已经安全上线。

参考： https://developer-docs.amazon.com/sp-api/docs/orders-api


## 2026-10-31 · V11D / V14 已执行的验证

- [Java 25 Maven 单测 + Vue TypeScript/Vitest/Vite CI 通过](https://github.com/kunlong-luo/merchivra/actions/runs/41156896032)：包含官方 SDK 类型化合成订单模型、缺失 Orders 角色拒绝、Marketplaces 归属错误拒绝、履约缺失不推断、V14 双人审批权限与审计测试。
- [PostgreSQL 18 + Spring Boot + Nginx Compose 全链路通过](https://github.com/kunlong-luo/merchivra/actions/runs/41156896071)：实际执行 Flyway V1–V14，断言 V12 已批准角色为空、V14 申请/审核审计均为空，继续测试模拟 Session/CSRF、先提交页1→真实重启后端→恢复页2/水位/去重。
- **这些全部是离线/模拟数据验收**：没有使用真实 Seller refresh token，也没有任何真实 Amazon 开发者后台角色证据、两位生产审核员或 LIVE 网络同步。在运营角色管理员 UI 完整、外部角色核验以及实际 Seller 授权验收前，V11D/V14 不能称为生产就绪。
