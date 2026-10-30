# SEC-004 · Amazon Seller OAuth 授权和加密凭证（V7）

**状态：代码已实现、默认关闭、未连接真实 Amazon。** 当前属于开发期公有 SP-API 应用授权实现切片，非经过授权/生产验收的完整凭证管理系统。

参考 Amazon 官方 [Website Authorization Workflow](https://developer-docs.amazon.com/sp-api/docs/website-authorization-workflow) 与 [Authorize Applications](https://developer-docs.amazon.com/sp-api/docs/authorizing-selling-partner-api-applications)。公有应用由 Seller OAuth 2.0 授权，**私有应用应使用 Seller Central 自授权流程**；两者不要混用。

## 设计与已实现 API

启用条件为服务端环境同时 `MERCHIVRA_SPAPI_ENABLED=true`、`MERCHIVRA_SPAPI_OAUTH_ENABLED=true`，且必须关闭 Demo，配置已注册的 HTTPS Redirect URI、Amazon App ID、LWA 凭证及 AES-256 Key（32 字节 Base64）。默认所有开关关闭，现有本地 Mock 模式仍可使用且没有 Amazon Token。

| 方法 | 路径 | 作用 | 授权条件 |
|---|---|---|---|
| POST | `/api/v1/seller-accounts/{accountId}/oauth/start` | 创建 10 分钟有效期的 OAuth State，返回 Seller Central consent URL，设置单独的 HttpOnly SameSite=Lax 交接 Cookie | 已登录 ACTIVE OWNER + CSRF；必须是当前租户 LOCAL/UNCONFIGURED 占位店铺 |
| GET | `/api/v1/spapi/oauth/login` | Amazon 第一阶段返回的 `amazon_callback_uri`、`amazon_state`、`selling_partner_id`，验证 Cookie/State 和 Amazon Callback 来源，将卖家身份绑定到授权请求，再重定向给 Amazon | 无业务 Session，但需要短期交接 Cookie、未过期单次 State 和严格 HTTPS Origin/路径白名单 |
| GET | `/api/v1/spapi/oauth/callback` | 最终验证 `state`、`selling_partner_id` 和交接 Cookie，原子消耗 State，然后用 `spapi_oauth_code` 向 LWA 换 Token 并加密保存 | 无业务 Session；必须通过上阶段且一次性状态未使用 |
| GET | `/api/v1/seller-accounts/{accountId}/oauth/status` | 显示 `NOT_CONNECTED/PENDING_VERIFICATION/VERIFIED/REVOKED` 等状态，不返回 Token | 当前登录 Owner/租户 |
| GET | `/api/v1/seller-accounts/{accountId}/oauth/audit` | 最近 30 条 OAuth 生命周期审计，不返回 Code/Token | 当前登录 Owner/租户 |
| POST | `/api/v1/seller-accounts/{accountId}/oauth/revoke` | **本地**撤销授权，清除 ciphertext/nonce，阻止此后获取凭证 | 当前登录 Owner + CSRF；不是调用 Amazon 远程撤销 |

### 状态与数据安全

- Flyway `V7__spapi_oauth_encrypted_credentials.sql` 包括 `spapi_oauth_attempt`（只存 SHA-256 State 摘要和租户/用户/店铺绑定）、`spapi_seller_authorization`（AES-256-GCM 的 ciphertext/随机 nonce/key_version）、`spapi_oauth_audit`（动作与时间、不包含秘密）。
- `SellerOauthService` 两阶段交接：Amazon 第一阶段请求必须经白名单；最终回调要求 Amazon Seller ID 与前次交接相同。State 在外部 LWA 请求**之前**就原子标记消耗；Token 获取失败需重新发起授权，防止重复 Code 消费。
- 加密的 AAD 绑定 `tenantId+sellerAccountId+sellerId+keyVersion`；读取错误的租户、店铺、卖家或修改密文都必须失败。
- 回调交换**成功也只保存 `PENDING_VERIFICATION`**。原本 `LOCAL-` 内部卖家标识不替换为未经查验的真实 Seller ID，不会自动设置 `CONNECTED`。当前尚无验证状态提升的生产操作。
- `SpApiSellerGrantVault.loadVerified` 为**后台内部接口**，只有独立认证为 `VERIFIED` 且账号 `CONNECTED`、活动租户且 Marketplace 匹配才解密；没有向浏览器提供任何 Token API。Finance role 尚未核实，默认空权限。
- OAuth URL 禁止通过本地 Nginx 记录访问日志，并设置 `Referrer-Policy: no-referrer` 和 `Cache-Control: no-store`。正式部署时也必须对负载均衡器、Ingress、WAF、APM、Trace 等请求路径/查询字符串脱敏。
- 本地撤销只删除 Merchivra 内保存的密钥并阻断后续新调用；不等于在 Amazon Seller Central 远程撤销已颁发的授权，也不能自动取消已经运行中的所有后台任务。

## 未来环境配置

项目内 `.env.example` 列出变量名（无真实值），关键变量如下：

```dotenv
MERCHIVRA_SPAPI_ENABLED=false
MERCHIVRA_SPAPI_OAUTH_ENABLED=false
MERCHIVRA_SPAPI_SANDBOX=true
MERCHIVRA_SPAPI_ALLOW_WRITES=false
# MERCHIVRA_SPAPI_APP_ID=amzn1.sellerapps.app....
# MERCHIVRA_SPAPI_OAUTH_REDIRECT_URI=https://your-domain.example/api/v1/spapi/oauth/callback
# MERCHIVRA_SPAPI_OAUTH_AMAZON_ORIGINS=https://amazon.com
# MERCHIVRA_SPAPI_OAUTH_ENCRYPTION_KEY_BASE64=<32 random bytes encoded as Base64>
# MERCHIVRA_LWA_CLIENT_ID=<from secret manager>
# MERCHIVRA_LWA_CLIENT_SECRET=<from secret manager>
```

示意字段以尖括号标明**占位**，不要直接作为可使用的秘密。生产密钥应由 Secret Manager/KMS 注入，仅后端访问并独立限制、备份和轮换；当前固定 `key_version=1`，**尚无在线轮换程序**。同样不可将真实 Refresh Token、Code、App Secret 写入 Git、日志、Issue、公共 CI、纯虚构 Mock Fixtures。

注意本地 Compose 默认 `MERCHIVRA_DEMO_ENABLED=true`，代码明确禁止同时打开真实 OAuth，且默认 HTTP 访问无法满足 HTTPS Redirect/Secure Cookie。真正授权前必须配置已注册的公有 App、专用 HTTPS 域名、Demo 关闭、生产级会话/网络/密钥安全与经批准的卖家角色。

## 未完成及后续验收

- 未完成独立读取真实 Seller 的身份/Marketplace/角色并将 `PENDING_VERIFICATION` 提升为 `VERIFIED`，没有接入真正的订单/Listing/FBA Worker；官方 SDK 工厂与仓库解密访问尚未通过生产权限判定。
- 尚无自动 Refresh Token 轮换（Amazon 授权周期须按官方规定处理）、KMS envelope encryption/key rotation、角色最小化、账户级授权撤销即时中断、细粒度 ACL、并发压力、浏览器 OAuth 真实回跳验收。生产必须完成这些审查。
- 离线单测覆盖凭证加密/防篡改、LWA Code 交换及响应泄漏、伪造 State/Callback Origin 的拦截；独立 Compose 工作流核对 V7 Flyway 迁移及**OAuth 默认关闭**，不是 Amazon 网络验收。参见 [INT-004](../integrations/sp-api-code-integration.md)。

**任何真实 Seller 接入仍需 G1/G2 授权和数据安全决策正式通过；当前不应在公共互联网直接启用此代码。**
