# V66 Ads 独立授权准备与离线日报证据（Flyway V64）

**状态：本地 MOCK / MANUAL_UNVERIFIED 导入与双 Owner 复核已编写；真实 Ads 与 Seller 授权继续关闭。**

Ads OAuth 与 SP-API Seller OAuth 完全独立；V66 的 `AdsConsentProtocol` 是独立 LwA client/region/PKCE URL 构造的纯函数，不暴露授权 URL endpoint、不处理回调、不存储任何 Ads Token。状态接口始终返回 `DISABLED_PENDING_LOCAL_OWNER_AUTHORIZATION`。后续真实接入仍须独立 token Vault、一次性 State/PKCE、Ads profile API、授权撤销与异步 report worker，待全部核心功能完成后由 Owner 在本地联调。

通过 `/api/v1/ads/marketplaces/{marketplaceId}` 提供实际 Controller 的 8 个 OperationId：
`getAdsAuthorizationCapability`、
`listAdsProfiles`、
`registerAdsProfile`、
`listAdsReports`、
`importAdsReport`、
`getAdsReport`、
`reviewAdsReport`、
`listReviewedAdsCosts`。

Profile 只绑定一个 Tenant+Marketplace；origin 仅 MOCK / MANUAL_UNVERIFIED，status 永远 NOT_AUTHORIZED。
每日 Campaign 报告上限 200 行、最长连续 31 天，限定 Marketplace 原币、日期与 Profile 来源；行级固定金额和不可变 SHA-256 证据。相同报告号+相同内容允许幂等重放，变更内容冲突；跨报告相同 Profile+日期+Campaign 的批准行由 PostgreSQL 唯一约束禁止重复计费。仅非导入者的另一名 Owner 可 `APPROVE` 或 `REJECT`，结论不可再修改。审核通过状态为 `REVIEWED_UNVERIFIED`，不是 Ads API 验证。

**财务隔离：** `adsApiVerified=false`，`profitPosted=false`；不写 V36 人工广告费用、订单贡献、Finances、结算或银行，不自动进行 SKU 归因。只有独立的广告日报参考事实，不计算净利润。

合同从 loopback-only `/v3/api-docs/ads-reports` 导出，规范化后生成并提交 `apps/web/openapi/ads-reports.json`，以 Orval 8 生成 `src/generated/ads-reports.ts` 和 `ads-models`；CI 比对真实运行合同与已提交契约。所有接口采用 Owner Session + CSRF + no-store，真实 Amazon Auth / API 网络完全不需要。

官方 Ads 授权参考：https://advertising.amazon.com/API/docs/en-us/guides/account-management/authorization/authorization-grants

未完成：真实 Ads OAuth / Profile 授权验证 / v3 report API 同步和跨 Finances 去重、生产安全压测；不能把开发完成当作 Amazon 正式认证。
