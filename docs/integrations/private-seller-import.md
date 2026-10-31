# INT-005 · 私有 Seller 应用服务端自授权导入（V19，开发实现）

**状态：代码已提交设计切片；未做真实 Amazon / 数据库 / 安全验收。**

Amazon 私有 Seller 应用仅用于本组织，Seller Central 自授权取得 LWA Refresh Token；不需要公有应用网站 OAuth 的 login/callback。公有应用仍使用 SEC-004 的网站 OAuth，**两条路径不可冒充或混合**。官方参考：
[SP-API Registration Overview](https://developer-docs.amazon.com/sp-api/docs/sp-api-registration-overview)。

## 路径

1. 管理员先经受保护的本地 Owner API 创建 `seller_account`，绑定明确区域的 Marketplace。
2. 在 Amazon Seller Central 的应用管理界面，使用已有注册/获批的**私有** SP-API 应用完成自授权，并在个人安全环境取到真实 Seller ID 和 Refresh Token。
3. 在独立维护任务中设定 `MERCHIVRA_SPAPI_ENABLED=true`、`MERCHIVRA_SPAPI_PRIVATE_ENABLED=true`、`MERCHIVRA_SPAPI_OAUTH_ENABLED=false`、`MERCHIVRA_DEMO_ENABLED=false`，提供 LWA Client ID/Secret、AES-256 Vault Key 或 Keyring、PostgreSQL 连接。
4. 使用**仅服务端、一次性执行**的导入模式 `MERCHIVRA_SPAPI_PRIVATE_IMPORT_ENABLED=true`。从宿主 Secret Manager/短生命周期环境注入 `_TENANT_ID`、`_ACCOUNT_ID`、`_SELLER_ID`、`_REFRESH_TOKEN`；不在 shell 命令行粘贴令牌，也不加入任何持久 Compose 配置。示例（各敏感变量已从安全环境注入）：

   ```bash
   # 先启动独立 HTTPS 后端/数据库环境，且停止正在使用旧授权的任务。
   # 不启用 --service-ports；这个 one-shot 进程不需要对外暴露 HTTP。
   docker compose --env-file .env -f deploy/compose.yaml -f deploy/compose.production.yaml \
     run --rm --no-deps \
     -e MERCHIVRA_SPAPI_PRIVATE_IMPORT_ENABLED=true \
     -e MERCHIVRA_SPAPI_PRIVATE_IMPORT_TENANT_ID \
     -e MERCHIVRA_SPAPI_PRIVATE_IMPORT_ACCOUNT_ID \
     -e MERCHIVRA_SPAPI_PRIVATE_IMPORT_SELLER_ID \
     -e MERCHIVRA_SPAPI_PRIVATE_IMPORT_REFRESH_TOKEN server
   ```

5. 导入程序验证 Seller 记录、目标站点、租户、Seller/Region 一致性，锁定数据库，在同一事务内加密保存 Refresh Token 并记录 `PRIVATE_IMPORTED`（actor 为 NULL，明确服务端维护操作）；进程成功后自行退出，不作为带敏感环境变量的常驻 HTTP 服务。
6. **初始状态始终为** `PENDING_VERIFICATION` / `AUTHORIZATION_PENDING`；需显式开启正式 Sellers v1 站点验证，验证后才能 `CONNECTED`。Orders 仍需独立审批，并且暂不自动授予 Finance/PII 角色。
7. Owner 可通过 `GET /api/v1/seller-accounts/{id}/private-authorization/status` 和 `GET .../audit` 查看脱敏状态，`POST .../revoke` 撤销本地密文并失效站点/角色证据；撤销后导入仅允许**同一 Seller ID 和 Region**，更换 Seller 应新建店铺。

**安全边界：**该命令**不能**自动撤销 Amazon Seller Central 的授权；即使本地清除密文，Amazon 侧仍需手动撤权。不要在生产日志/APM/CLI 历史/Docker Compose 配置中显示任何凭证。生产部署需要外部 HTTPS 反向代理、独立 Secrets 与密钥管理、审计、备份和权限审批；提供的生产 Compose overlay 只是占位配置，不构成安全生产认证。

**交付边界：**该功能目前为**可调用的代码路径**，用户要求先实现功能再集中验证；没有确认任何真实 Seller token 或生产请求。
