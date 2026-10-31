# V16 · 店铺级员工只读权限（最小安全闭环）

状态：2026-10-31 已实现代码，采用 PostgreSQL 18、Java 25、Vue/TypeScript。**不是完整的员工权限矩阵**。

## 已实现角色及范围

| 角色 | 授权范围 | 允许的 API | 禁止的 API |
|---|---|---|---|
| `OWNER` | 当前租户下全部 Seller Account | 原有核心业务、团队、运营及受控管理能力 | 仍不得绕过真实 Amazon SP-API 审批 |
| `OPERATOR_READ` | 单一 `seller_account_id` 下全部 Marketplace | `GET /api/v1/seller-marketplaces`，以及对应 `/{marketplaceId}/listings`、`/orders`、`/inventory/fba` | 其他非身份验证 API，包括团队、账号配置、模拟同步、运营规则变更、OAuth、Orders LIVE Worker、审批和财务 |
| `SECURITY_REVIEWER` | 附加在现有有效 OWNER 上 | V14 的独立双人角色审批（须与申请人不同） | 不可自授；不是普通员工只读角色 |

V16 **仅允许店铺级只读运营人员**。尚无运营写入、采购审批、财务访问、账号级别多角色组合管理，也无单 Marketplace 细粒度权限。

## 创建与撤销

在「团队与权限」选择 `OPERATOR_READ` 以及 Seller Account，再创建邀请。此邀请延续 V15：256-bit 随机令牌，服务器仅持有 SHA-256，24 小时有效，受邀者通过 CSRF 保护的激活端点设置独立 16–128 位密码，激活时写入对应的租户级 ACTIVE Membership 和**仅账号级** role_binding。旧 V15 邀请默认为 OWNER，不改变历史令牌语义。

Flyway V16 对邀请和角色表施加数据库 CHECK：全局 `OPERATOR_READ` 不合法，邀请中的 `OWNER` 必须不携带店铺，`OPERATOR_READ` 必须携带归属于当前租户的店铺；不能仅靠前端传参扩权。Owner 可撤销员工 membership，后续请求重新核验，在旧 Session 内立即失去授权；仍禁止移除自己及最后一个有效 OWNER。

## 两层独立服务端授权

1. 业务授权：`LocalUserDetailsService` 允许有实际账号作用域的只读员工登录；`TenantAccessService.currentReadTenant` 仅用于 `/auth/me` 和核心业务**只读** API，`currentTenant` 依旧维持 OWNER-only。核心数据 API 在 Marketplace 列表及每条具体数据查询前，通过 tenant、用户 ID、ACTIVE membership、app_user 状态和 role_binding/seller_account 的联合 SQL 条件判定；不存在或跨账号 Marketplace 统一给 404。服务器不信任浏览器传入的 tenantId。
2. 默认拒绝：`ReadOnlyStaffGuardFilter` 仅放行经过明确审阅的四种只读路径（列表 + Listing/Orders/FBA）。其他非身份验证 API，包括历史 Demo 工作流和运营/团队/同步操作，必须具有**当前选中租户的**有效 OWNER 身份，即使在其他租户拥有 OWNER 也不能越权。Spring Security Session/CSRF 校验继续有效。

前端 `/api/v1/auth/me.isOwner` 仅控制菜单和按钮可见性；普通员工只看到「核心业务数据」，原有 Seller Setup、Demo 导入、同步面板不显示。**前端隐藏不构成权限边界**。

## 验收和生产限制

PG18 Compose 执行 V1–V16、既有 V11A 模拟分页重启/去重。V16 实际 HTTP 冒烟创建员工并验证：邀请→新密码→独立登录→仅授权 Seller Account 的 Marketplace；该账号的 Orders、Listing、FBA 返回 200，其他账号返回 404；团队/运营/账户创建 403；Owner 撤销成员后旧会话 403。Java 单测验证精确路径白名单、跨租户 Owner 不可借用管理员权限、认证/角色边界。Chromium 合成测试验证 360px 员工界面只显示核心业务、无团队和同步按钮。**所有证据均是合成数据，不是生产安全验收**。

未实现：岗位级可写操作、采购和财务隔离、全局审计导出、密码重置/MFA/SSO、邀请投递/反滥用机制、多店铺赋权 UX、生产规模/攻击测试。没有真实 Seller OAuth 和 Amazon 审核证据，LIVE SP-API 默认关闭。

参阅 [V15 团队管理](team-v15.md)、[Orders 角色双人审核](../integrations/orders-role-review-v14.md)。
