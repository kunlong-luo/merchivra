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


## V15 实际验收证据（2026-10-31）

- [Java 25 / Vue TypeScript / Vitest / Vite CI 41159636629](https://github.com/kunlong-luo/merchivra/actions/runs/41159636629) **成功**。
- [PostgreSQL 18 + Java25 + Nginx Compose 41159526524](https://github.com/kunlong-luo/merchivra/actions/runs/41159526524) **成功**，执行 Flyway V1–V15，继续验证此前业务 Mock、订单双页 checkpoint、后端真实容器重启；包含 V15 一次性邀请、匿名/CSRF 拒绝、令牌重放失败、另一位 OWNER 独立账号登录、自授权 reviewer 拒绝、授予/撤销 reviewer、撤销 OWNER 后已登录旧 Session 拒绝访问、仍保留有效 OWNER。日志有 `PASS V15: PG18 2-Owner invitation ... membership immediate revocation`。
- [Chrome 360px responsive UI 41159636546](https://github.com/kunlong-luo/merchivra/actions/runs/41159636546) **成功**，合成双管理员数据验证「团队与权限」成员表、Reviewer 标记、邀请入口和移动端布局，并保留此前 Mock/LIVE 订单面板的响应式检查。
- 上述测试**不代表**生产安全审计、邮件邀请、MFA、SSO、员工/财务分权或 Amazon 正式角色审批；团队管理需显式开启，LIVE SP-API 仍默认关闭。

## V16 店铺级只读运营员工（2026-10-31）

- Flyway V16 为 V15 邀请增加 `invite_role` 与租户约束的 `seller_account_id`；旧邀请默认 OWNER 不变。`OPERATOR_READ` 必须绑定 Seller Account，禁止全租户该角色、非法或跨租户账号、未审核权限自动升级。Owner 邀请的员工激活后仅获得指定账号的店铺级只读角色，而不是 OWNER。
- 原有敏感控制器的 `TenantAccessService.currentTenant` 继续**只允许 OWNER**；新增 `currentReadTenant/readableTenants` 仅用于身份/核心只读业务端点。核心 Marketplace 列表、Listing、Orders、FBA 每个请求重新联查数据库当前 membership、用户状态、role_binding 与 Seller Account；未知及跨账号 ID 均返回同样 404。所有授权记录以服务器 Session userId 为准。
- `ReadOnlyStaffGuardFilter` 在业务控制器之外额外阻止员工读取旧 Demo 非授权接口、执行 Mock 同步、运营规则、Amazon OAuth、账户配置或任何其他非白名单 API。唯一放行额外工作区 API 是 GET `/api/v1/seller-marketplaces/**`，具体订单/库存查询仍二次校验角色；旧敏感管理员 API 未因员工登录而开放。
- 前端角色信息来自 `/api/v1/auth/me.isOwner`；员工只看「核心业务数据」，不显示团队、运营自动化、经营演示、Seller Setup 和同步按钮。隐藏 UI 不是安全边界。团队页面可以选择邀请 OWNER 或 `OPERATOR_READ`，后者要求选定 Seller Account。
- 这只是 **V16 第一层员工权限（店铺级只读运营）**。尚不支持采购写、财务阅览、普通运营修改、单独 Marketplace 级别授权、OIDC/MFA 和批量授权矩阵。真实 Amazon LIVE 出站依旧默认关闭。
- CI 将使用 PostgreSQL 18：员工一次性邀请→登录→只看到一个 Seller Account 所属 Marketplace→读取其订单/Listing/FBA→其他 Seller Account 所属站点 404→团队/运营/账户等管理员 API 403→Owner 撤销后旧 Session 无法读取。此处描述验收目标，必须以成功 workflow 为准。


## V16 已完成的实际验证（2026-10-31）

- [Java 25 Maven + Vue / TypeScript / Vitest / Vite CI 41162402846](https://github.com/kunlong-luo/merchivra/actions/runs/41162402846) **成功**，包含路径白名单、当前租户 Owner 校验及跨租户管理员不能借权操作的负例。
- [PostgreSQL 18 + Nginx + Spring Boot Compose 41162402904](https://github.com/kunlong-luo/merchivra/actions/runs/41162402904) **成功**：Flyway V1–V16，V15 邀请和 V16 员工跨店铺授权、账号激活、Session/CSRF、访问者越权 403/404、管理员撤销后权限立即失效；真实重启 Spring Boot 后 V11A 模拟订单页 2 恢复且无重复写入。日志：`PASS V16: staff login, scoped Marketplace + orders/listings/FBA, foreign 404, no OWNER routes, immediate revoke`。
- [Chromium 360px 员工界面验收 41162279336](https://github.com/kunlong-luo/merchivra/actions/runs/41162279336) **成功**；假设员工具有受限角色，验证仅有核心数据入口，团队/模拟导入/同步面板不展示；页面截图存放于 workflow artifacts。
- 本轮还有显式安全收紧：服务端仅放行精确已审核的 Marketplace GET 读路径，OWNER 权限必须属于**当前 Session 所选租户**，而不是其他租户中的 OWNER 权限。生产真实 Amazon API 出站默认关闭。
- **已完成的是初级 OPERATOR_READ 角色，不是全量员工 RBAC**。运营写入、采购、财务、SSO/MFA、外部生产安全审核均未完成，不能将模拟测试结果称作生产权限认证。
