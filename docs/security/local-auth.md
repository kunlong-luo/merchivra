# SEC-003 · 本地账号 / Session 登录与租户隔离（首批实施）

**实施状态：M2/M3 开发阶段的最小安全基线，仍为 Pre-alpha。** 技术方案采用 Spring Security 3.5 管理的 Spring Security 6 生态、服务端 Session + CSRF、BCrypt 密码哈希。该实现尚未通过 PostgreSQL 集成测试、渗透测试或生产安全签审，**不得与已完成生产认证混同**。

## 首次本地启动

1. 在仓库根目录复制 `.env.example` 为 `.env`，将数据库密码改成强密码。
2. 设置 `MERCHIVRA_BOOTSTRAP_USERNAME`（3–64 位，允许字母数字和 `._-`）和 `MERCHIVRA_BOOTSTRAP_PASSWORD`（**至少 16 个字符、必须自己生成**）；可修改 `MERCHIVRA_BOOTSTRAP_TENANT_NAME`。
3. 在本机执行 `docker compose -f deploy/compose.yaml up --build`，打开 `http://localhost:3000` 输入刚设定的账号密码。
4. 登录后仅可浏览固定虚构 Mock 数据；另有 `GET /api/v1/seller-accounts` 返回当前真实登录租户下的已建店铺（首次新租户通常为空）。
5. 首次成功初始化后建议**从运行环境删除 Bootstrap Password**，重启不会覆盖或重置已有密码。

**没有内置默认管理员密码。** 两个初始化变量皆空时应用可以启动但**不能首次登录**；只填写一部分或设置过短密码且尚无任何本地用户，会拒绝初始化，不会创建弱凭证。不要把真实密码提交到 Git 或在 Issue 中贴出。

## 安全实现与权限

| 组件 | 已实现 | 注意 |
|---|---|---|
| Flyway V3 | `local_credential(user_id,login_name,password_hash)` 和全租户角色绑定部分唯一索引 | 与 `app_user` FK 关联，口令只储存 BCrypt hash |
| 初始 Owner | 在 PostgreSQL 事务和 advisory lock 下，仅**当前还没有本地凭证时**创建 1 个租户/Owner | 不是远程开放的匿名初始化 API；不支持重新设置管理员密码 |
| Session | Spring Security 登录成功迁移会话 ID、`JSESSIONID` HttpOnly、SameSite=Strict | 本地 HTTP 为 `Secure=false`；**公网/HTTPS 必须启用 Secure 和可信 TLS 代理** |
| CSRF | `GET /api/v1/auth/csrf` 返回存在服务端 Session 的 Token；写操作通过 `X-CSRF-TOKEN` 请求头提交 | Token 不保存在 localStorage；登录、模拟同步、切换租户、退出均校验 |
| 租户作用域 | 权限从服务端认证 UUID + ACTIVE Membership + OWNER role 查询得出；活动租户在 Session 内 | 客户端不可用 `tenantId` 直接读取其他租户；角色变更后下次访问会重新校验 |
| 业务 API | `GET /api/v1/seller-accounts` 与按当前租户查询 Marketplace/Listing/订单/FBA；本地配置 POST 经 CSRF | 新增的是只创建 `UNCONFIGURED` 店铺/站点的开发期安全配置，不接受真实 Amazon 凭证 |
| Mock 工作台 | `/api/v1/demo/**` 除需显式开关外还要求登录 | 继续只处理固定虚构数据，不代表真实 Amazon 授权 |

## 当前认证端点

| 方法 | 路径 | 请求 | 结果 |
|---|---|---|---|
| GET | `/api/v1/auth/csrf` | 无须登录 | `{token,headerName:"X-CSRF-TOKEN"}`；建立/复用 Session |
| POST | `/api/v1/auth/login` | form `username` + `password`，含 CSRF header | 200 JSON；凭据错误 401 |
| GET | `/api/v1/auth/me` | 已登录 Cookie | `{userId, activeTenantId, availableTenantIds}` |
| POST | `/api/v1/auth/active-tenant` | `{"tenantId":"..."}` + CSRF | 仅当有 ACTIVE OWNER 权限允许切换，否则 403 |
| POST | `/api/v1/auth/logout` | CSRF header | 204，撤销浏览器 Session |
| GET | `/api/v1/seller-accounts` | 已登录 Cookie | 服务端根据 Session/Owner 作用域过滤店铺 |
| GET | `/api/v1/ping` | 无 | 仍开放用于本地存活检查 |

前端由 `apps/web/src/Root.vue` 先验证 Session 再挂载 `App.vue`，登出时清理客户端 Mock 查询缓存。**前端隐藏界面不是安全边界**；后端全部业务端点必须单独校验。

## 当前不提供的能力

- 不支持注册、多管理员、邀请成员、密码重设/MFA/OIDC，也没有邮箱找回；忘记密码需后续受审核的维护恢复工具，**不能使用默认密码绕过**。
- 没有 Seller 真实 OAuth / SP-API 凭证加密或权限角色，真实 Amazon 数据**仍禁止接入**。
- 当前只实现 OWNER 全租户读与**本地店铺初始化写入**；细粒度员工/财务/店铺角色没有实现。仅 [API-004](../api/local-seller-setup.md) 具有局部配置审计，缺少全站审计、统一 API 速率限制、账户锁定/暴力破解防护、密钥管理、RLS、分布式 Session 存储、多实例一致性与正式安全扫描。
- 浏览器 Cookie 走本地 HTTP 仅是开发便利；若要 HTTPS 部署必须调整 `MERCHIVRA_SESSION_COOKIE_SECURE=true`，配置可信反向代理，并完成实际检查（当前仅绑定本地 IP）。
- 2026-10-30 的 [一次性 PostgreSQL 17 冒烟验证](https://github.com/kunlong-luo/merchivra/actions/runs/41059134533) 已验证**临时 PostgreSQL 17 上 V1–V4 的真实启动与最小登录/CSRF/本地店铺配置链路**，但没有完成浏览器 E2E、Docker Compose 正式部署、并发与生产安全验收；不代表服务器端实机安全完整。

## 后续验收

临时 PostgreSQL V1–V4 单次冒烟已通过；下一步完成 Docker Compose 联调、并发/撤销权限负例和全套生产安全审查、匿名 API 401、CSRF 缺失 403、登录错误密码 401、会话过期/注销、跨租户账号访问 403/不返回数据、权限撤销后 403 的负例。项目要求前期轻量 CI，详细运行态/浏览器测试留待 M3/M4 质量阶段，不将 E2E 添加到当前 CI。

相关：[Tenant enforcement](tenant-enforcement.md)、[ADR-0005](../architecture/adrs/0005-api-authentication-tradeoffs.md)、[实施物理表](../architecture/core-domain-foundation.md)、[候选 OpenAPI](../api/README.md)。
