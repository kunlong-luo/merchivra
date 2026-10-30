# SEC-005 · Sellers API v1 Marketplace 验证（V8）

**状态：代码/离线测试切片；真实 Amazon 未调用，默认关闭；不是生产级 IAM/角色证明。**

## 范围与证据

V7 公有应用 OAuth 的两阶段回调提供 Amazon `selling_partner_id`（严格绑定一次性 State/Cookie）。
V8 使用官方 Java SDK `SellersApi.getMarketplaceParticipations()`，以 V7 加密的 Refresh Token
读取该授权可访问的 Marketplace 列表、`isParticipating` 与 `hasSuspendedListings`。
**Sellers v1 响应不返回可独立交叉核对的 Seller ID，也不返回 Finance/Orders 等角色授权列表**：
绝不能把 Marketplace 接口成功解释成所有 SP-API 角色已通过验证。

- 若任一已配置站点缺失、`isParticipating != true`、暂停 Listing 或返回重复/畸形条目，验证失败；部分成功不予连接。
- 真实验证只能在 `MERCHIVRA_SPAPI_ENABLED=true`、`MERCHIVRA_SPAPI_OAUTH_ENABLED=true`、
  `MERCHIVRA_SPAPI_VERIFICATION_ENABLED=true`、`MERCHIVRA_SPAPI_SANDBOX=false`、
  `MERCHIVRA_DEMO_ENABLED=false` 时启动。默认全关闭。Sandbox 不能当生产授权证据。
- Owner 的 Session/CSRF 保护 `POST /api/v1/seller-accounts/{accountId}/oauth/verify`。
  `accountId` 仅用于租户内查找；不接受客户端传入 Seller ID、token、tenant ID、区域或 Host。
- 新的 `VERIFYING` + 一次性 `verification_attempt_id` 进行并发 fencing；
  5 分钟超时可以抢占，1 分钟最短重试间隔。远程请求在事务外进行，迟到请求只有
  持有当前 claim 才能将授权置为 `VERIFIED`。撤销优先，绝不复活旧授权。
- 失败仅持久化固定枚举原因，清除站点证据，将连接退回 `AUTHORIZATION_PENDING`，
  不回显 Amazon 响应体、秘密/异常文本，失败只允许人工重试。
- 通过后授权 `VERIFIED`、店铺 `CONNECTED`、每个已配置站点证据写入同一事务。
  `SpApiSellerGrantVault.loadVerified` 只允许具有 24 小时内核验证据的站点，
  且仍要求活动租户、同区、已连接授权。届满后自动拒绝后续凭证读取，
  需手动再次验证；这不是已经实现的定期后台复核。
- 目前 **approvedRoles 永远为空**。Finances / RDT 等敏感能力仍默认禁止；应基于
  官方应用/Developer 角色批准记录、对应 operation 的最小权限测试、单独审批和审计
  引入明确的 `role evidence` 后才能放行，禁止凭借 Sellers v1 推断角色。

## 限制与上线门槛

1. 没有真实公有应用授权、Amazon 网络、LWA Refresh、IP/网络审查及生产 IAM 验收。
2. 没有独立 Seller 身份核验端点；Seller ID 来源于 OAuth 回调，Marketplace 仅证明 Token
   的站点参与范围。运维必须确认授权/应用范围与业务主体匹配，不得把这当成 KYC。
3. 没有正式订单/Listing/FBA Worker、持久分布式任务取消或自动 token 生命周期轮换。
   24 小时过期是本地准入防护，不代表 Amazon 授权本身只有 24 小时。
4. 当前 AES-GCM 密钥仍固定版本 1，部署前需经 KMS/Secret Manager 生命周期设计；
   禁止将真实 Token、Secret 或 Seller 身份提交到仓库、CI 与日志。
5. 正式验收需要通过对接方账户、受审查的 OAuth 端点、真实市场数据、角色负例、
   429/5xx/撤销并发、数据库 crash recovery 与 Worker 取消测试。

官方参考：[Sellers API v1](https://developer-docs.amazon.com/sp-api/docs/sellers-api-v1-reference)、
[官方 Java SDK](https://github.com/amzn/selling-partner-api-sdk)、
[SP-API Roles](https://developer-docs.amazon.com/sp-api/docs/roles-in-the-selling-partner-api)。
