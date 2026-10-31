# V15 多管理员邀请与安全审核员角色

阶段：仅 **OWNER** 多管理员访问与 `SECURITY_REVIEWER` 授权。**不是**普通运营员工的完整最小权限 RBAC。首次部署不会自动邀请任何人，不会自动授予审核员权限。

## 启用

```dotenv
MERCHIVRA_TEAM_ENABLED=true
```

默认值为 `false`，因此该功能的 API 不会注册。打开开关后，现有 OWNER 可在「团队与权限」页面生成一次性管理员邀请。邀请 token 256-bit 随机生成，服务器只储存 SHA-256；令牌仅在创建响应中展示一次，24 小时后过期。管理员通过受信任渠道交给受邀者，受邀者在登录页选择「使用邀请令牌激活」后自行设置长度 16–128 的密码。服务端对该操作强制 CSRF，令牌在事务中单次领取，不能重复使用。

加入者获得本租户 ACTIVE Membership 和 `OWNER` 全租户角色，没有任何 `SECURITY_REVIEWER` 权限。现阶段**不要邀请只需查看订单的普通员工**：OWNER 有广泛业务权限；必须等每个读写 API 都实现 per-account/per-role 授权，才能开放 OPERATOR/FINANCE 等角色。

## 独立审核员授权和撤销

已登录的一个 OWNER 可以授予**另一位 ACTIVE OWNER** 全租户 `SECURITY_REVIEWER`，不能给自己授予。被授予角色后，另一用户才能以独立身份审核 V14 Orders 应用角色申请，V14 仍校验申请/审核用户不同，且 V12 Vault 每次检查审核人的有效成员和权限。两方均须独立登录并凭服务器 Session 操作。

可以撤销审核员权限、取消尚未被接受的邀请，以及撤销另一位 OWNER 的本租户成员访问。当前不允许撤销自己的访问权限，以防意外锁定管理员；成员撤销前校验仍至少有一个 ACTIVE OWNER。成员撤销后 `TenantAccessService.currentTenant` 会在后续每次业务 API 请求检查 membership，旧 Session 无法继续访问该租户。已经关闭或撤销的邀请不会重新激活，角色审计持续保留。

V15 包含 `local_team_audit`，记录 INVITED、INVITE_ACCEPTED、INVITE_REVOKED、REVIEWER_GRANTED、REVIEWER_REVOKED、OWNER_REVOKED；不记录原始邀请令牌或明文密码。邀请令牌仅一次显示，复制后应通过可信渠道发送并清除剪贴板。

### 服务端 API

- `GET /api/v1/team/members`：当前租户 OWNER 成员及审核员状态。
- `GET /api/v1/team/invitations`：最近 50 条邀请元数据，**不返回 token**。
- `POST /api/v1/team/invitations`：`{"loginName":"example-owner"}`，返回一次性 token。
- `POST /api/v1/auth/invitations/accept`：免登录但强制 Session CSRF，`{"token":"...","password":"..."}`，成功 204；绝无公开自注册。
- `POST /api/v1/team/invitations/{uuid}/revoke`：取消待接受的邀请。
- `POST /api/v1/team/members/{uuid}/reviewer`：`{"enabled":true|false}`，授予或撤销另一位管理员的 SECURITY_REVIEWER。
- `POST /api/v1/team/members/{uuid}/revoke`：撤销另一位管理员的租户访问。

所有团队管理 API 从已认证的 server-side Session 取得 actor 和 tenant；前端不能提供可信 tenantId 或 roleCode。接受邀请是唯一的免登录路由，不要求、也不能使用目标用户的现有凭证；Spring CSRF 仍不可跳过。

## 验收与限制

测试应以真实 PostgreSQL 18 / Java 25 / Nginx Compose 执行：Owner 登录 → 创建邀请 → 第二个会话激活/不可重放 → 第二个独立 Owner 登录 → 不能自授审核员 → Owner 授予并撤销另一位审核员 → 撤销成员后旧 Session 被拒绝，最后一个 OWNER 不可被删除 → 匿名/CSRF 拒绝。Java/Vue CI 另测输入、Schema、角色边界，浏览器验收独立进行。

未实现：邀请邮件发送、企业 SSO/MFA、审计导出、邀请滥用限速、普通员工权限矩阵、生产凭证恢复、安全审核员对外部 Amazon 角色证据的自动真实性核验。正式 Amazon SP-API LIVE 仍默认关闭，且独立要求审核证据和运营验收。
