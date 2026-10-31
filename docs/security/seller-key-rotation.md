
# SEC-006 · V9 Seller Refresh Token 加密密钥轮换

**范围：本地 Vault 的 AES-256-GCM 密钥重包裹（rewrap），与 Amazon LWA Refresh Token 续期不同。** 不调用 Amazon、不更改卖家角色、不延长 Amazon Refresh Token 寿命。

## 已实现

- 兼容 V7 单密钥配置 `MERCHIVRA_SPAPI_OAUTH_ENCRYPTION_KEY_BASE64`（仅 version=1）；新增 `MERCHIVRA_SPAPI_OAUTH_ENCRYPTION_KEYRING`（例如 `1:<base64>,2:<base64>`）与 `MERCHIVRA_SPAPI_OAUTH_ACTIVE_KEY_VERSION`。新旧配置互斥；拒绝缺少活动版本、重复版本和非32字节密钥。
- OAuth 初次写入、V8 Sellers 核验、Worker Vault 及 V9 重加密使用同一个 `SellerTokenCipher`。旧密文按原 `key_version` 解密，新密文使用 active key，AAD 始终绑定 tenant/account/seller/keyVersion。
- 新的 `POST /api/v1/seller-accounts/{accountId}/oauth/rotate-key` 仅在 SP-API + OAuth 显式开启且 Demo 关闭时注册，要求 Session/Owner/CSRF。请求不接受原始密钥、Token、Seller ID、tenantId；响应只返回 `REWRAPPED/UNCHANGED` 与新的 `keyVersion`。
- 在事务内按租户、店铺、活跃 Owner 加 `FOR UPDATE OF g` 行锁；验证旧 GCM Tag，随机新 nonce 重加密，按旧 keyVersion 条件更新并插入 `KEY_REWRAPPED` 审计。错误回滚且不得恢复已撤销授权。
- 仅新增 V9 审计约束扩展迁移，V7/V8 文件保持不变。不会推断或自动批准 Finance/PII 角色。

## 安全操作顺序

1. 在受控 Secret Manager/KMS 中生成新随机 AES-256 密钥并确保旧密钥备份可用；不要放在代码、日志、Issue、公共CI或共享 .env。
2. 先全节点部署包含版本1和版本2的 keyring，**active version 保持 1**，确认旧数据可解密；删除 legacy 配置避免新旧配置冲突。
3. 所有节点具备双版本解密能力后，才统一切换 `ACTIVE_KEY_VERSION=2`。滚动升级期间未升级的节点无法解密新写入的版本2密文。
4. 各租户 Owner 通过 HTTPS Session + CSRF 对店铺调用 rotate-key。重复操作返回 `UNCHANGED`。当前没有自动全租户批量轮换任务。
5. 受限 DB 管理员只查询密钥版本计数，包含 pending 和 verifying 等非撤销行：`SELECT key_version,count(*) FROM spapi_seller_authorization WHERE status<>'REVOKED' GROUP BY key_version;`。确保全环境都完成迁移、灾备策略已确认后，才能考虑移除旧 key。
6. 检查无敏感内容的 `spapi_oauth_audit` 里 `KEY_REWRAPPED` 事件和操作者，不导出 Ciphertext/Token。遇到密文认证失败就停止并排查，不能强制覆盖。

## 尚未完成

**此功能只是应用层的手动 key rewrap，并非 AWS KMS Envelope Encryption、自动化密钥轮换、密钥吊销演练，也不更新 Amazon Refresh Token。** Amazon 公有应用授权的 Refresh Token 仍需独立的规定周期续期流程。正式生产前仍需多节点部署、安全 IAM、密钥备份/灾备、Amazon 实机验收、授权角色审核和同步 Worker 的撤销中断机制。

参考：[官方 SP-API Onboarding](https://developer-docs.amazon.com/sp-api/docs/onboarding-overview)。
