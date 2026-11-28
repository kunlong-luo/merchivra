# INT-005 · 私有 Seller 自授权导入与真实只读联调（V19 / V291）

**CURRENT · 2026-11-28。维护者确认使用自己的 Amazon 私有 Seller 应用；方案准备完成，真实网络/凭证及角色尚未验证。** 当前不授权出站、不修改默认配置，V291 BLOCKED、#104 NO-GO。旧 V19 说明及旧 Compose 命令见 [精确历史快照](https://github.com/kunlong-luo/merchivra/blob/7dd29280438be1d53d3788b400e5cfc4b523ec0c/docs/integrations/private-seller-import.md)，旧命令不得照抄。现行 Spring 环境变量无 MERCHIVRA_ 前缀（Compose 宿主输入另有映射）。

## 1. 已有实现和真实阻塞

- 私有应用通过 Amazon Solution Provider Portal / Developer Central 自授权得到 LWA Refresh Token，而非公有网站 OAuth，不需要公网 Login/Redirect URI。官方 [开发者入门](https://developer-docs.amazon.com/sp-api/docs/onboarding-overview)、[私有开发者注册](https://developer-docs.amazon.com/sp-api/docs/register-as-a-private-developer)、[LWA 凭证](https://developer-docs.amazon.com/sp-api/docs/viewing-your-application-information-and-credentials)、[区域端点](https://developer-docs.amazon.com/sp-api/docs/sp-api-endpoints)（本轮核对）。
- `PrivateSellerImportConfiguration` / `PrivateSellerGrantService` 一次性服务端导入，AES-256-GCM 加密，按 tenant/account/Seller 绑定，初始 `PENDING_VERIFICATION` / `AUTHORIZATION_PENDING`，成功退出；没有浏览器 Token 输入或读取 API。只允许新 LOCAL/UNCONFIGURED 或已撤销的同 Seller/Region；需至少一个本地 Marketplace。不要 SQL 伪造已核验状态。
- `SpApiConfiguration` 要求真实 endpoint 必须启用 Redis 配额，否则启动拒绝；Redis 失败时限流失败封闭。以前共享 Redis PONG 验收不证明实际配额。先用隔离实例/受审查 ACL 与专用配置，允许实际 Lua/TIME/TTL 操作；不得 FLUSHDB 或修改 spark-iot 的共享 Redis；Session Redis 可继续 OFF，burst 为空。
- `SellerVerificationController` 共用 `POST /api/v1/seller-accounts/{accountId}/oauth/verify`，即使 public OAuth OFF 也可在 private+verification enabled 时注册。Sellers v1 `getMarketplaceParticipations` 验证配置站点参与状态，不独立返回 Seller ID，不证明 Orders/Finance 角色；全部通过后 VERIFIED/CONNECTED，24h 内有效。
- **实际代码限制：** `LocalSellerSetupService.MARKETPLACES` 与 `apps/web/src/lib/setup.ts` 目前只支持 US(NA)、DE(EU)、JP(FE)。若真实站点不是三者之一，先报告站点/区域，添加正确官方 Marketplace ID、币种、时区和正反测试；不能假选美国或直接插库。Amazon 支持更多站点不等于项目已经支持。
- `SpApiSellerGrantVault` 的 Orders/Listing/FBA 门禁要求 `Inventory and Order Tracking` 的真实外部审批证据与独立 Reviewer；Finances 要求 `Finance and Accounting` 独立审核，不能复用前者。单独一个 Owner 无法自审。实际调用前须明确项目本地受控出站审批及 Amazon 应用角色范围；无真实资料不得开启。Ads 不属于此 SP-API 私有授权，另行联调。

## 2. 第一轮：只做准备（可立即执行，真实网络仍 OFF）

1. 在 Amazon 官方开发者后台确认应用类型 Private Seller、站点、已批准角色；准备 Seller ID（不是本地 UUID）、LWA Client ID、Client Secret、Self-Authorization Refresh Token。仅在你自己的受控环境保管，不在聊天、Git、截图、IDEA 共享运行配置、shell 历史或公共 CI 中提供。官方 [入门](https://developer-docs.amazon.com/sp-api/docs/onboarding-overview) 说明私有应用在开发者控制台自授权；不需要公有 Appstore 发布或网站回调。
2. 新建独立 PG18 测试库/低权限角色，不复用已有合成数据或公开测试密码；用私有强密码初始化新 OWNER，成功后将 BOOTSTRAP_USERNAME 与 BOOTSTRAP_PASSWORD 都显式置空（已有账号不会因改环境而改密码）。私有配置位于仓库外 `~/.config/merchivra/application.yml`（local profile 自动导入），目录700、文件600；使用秘密管理器/IDEA 私有环境注入 DB/LWA/Vault 等，不保存 Refresh Token 到常驻文件。Vault Key 使用随机32字节 Base64（`openssl rand -base64 32`，安全保存输出而非转发），固定备份，不每次启动重新生成；单一 v1 key 使用 `SPAPI_OAUTH_ENCRYPTION_KEY_BASE64`，keyring 与该值二选一。
3. 本机保持 `DEMO_ENABLED=false`、`SPAPI_ENABLED=false`、所有真实同步/授权 OFF，启动 IDEA local profile（8081）和 `pnpm dev:local`（3001）。登录“核心业务数据 → 本地店铺配置与测试工具”，创建一个真实站点对应的 LOCAL 占位店铺并添加 Marketplace。仅在已登录自己的浏览器本地查看同源 `/api/v1/auth/me` 返回 activeTenantId、`/api/v1/seller-accounts` 返回所选 id；记录对应 UUID，不要发到聊天。确认实际区域与授权主体一致。
4. 确认独立 Redis 可用、最小权限、私有凭据、TLS/网络边界、Amazon DPP/AUP 及 G1/G2 安全负责人批准受控只读试验。无批准继续 BLOCKED，可先在官方后台导出 E1 文件，不开启真实 API。

## 3. 获明确受控只读批准后：首轮仅站点核验

以下是**条件式本机配置**，不是当前启用指令。只在独立本地非 Demo 环境以私有环境变量提供；保留原仓库默认 OFF：

```dotenv
DEMO_ENABLED=false
SPAPI_ENABLED=true
SPAPI_SANDBOX=false
SPAPI_ALLOW_WRITES=false
SPAPI_PRIVATE_ENABLED=true
SPAPI_OAUTH_ENABLED=false
SPAPI_VERIFICATION_ENABLED=true
SPAPI_RATE_LIMIT_ENABLED=true
SPAPI_APPROVED_BURSTS=
REDIS_HEALTH_ENABLED=true
REDIS_SESSIONS_ENABLED=false
SPAPI_PRIVATE_IMPORT_ENABLED=false
SPAPI_ORDERS_ROLE_REVIEW_ENABLED=false
SPAPI_FINANCES_ROLE_REVIEW_ENABLED=false
ORDERS_SYNC_ENABLED=false
ORDERS_SYNC_LIVE_APPROVED=false
CATALOG_SYNC_ENABLED=false
FINANCES_SYNC_ENABLED=false
SPAPI_REPORTS_ENABLED=false
ORDERS_SYNC_SCHEDULER_ENABLED=false
CATALOG_SYNC_SCHEDULER_ENABLED=false
FINANCES_SYNC_SCHEDULER_ENABLED=false
SPAPI_REPORTS_SCHEDULER_ENABLED=false
```

另以私有环境设置 DB_URL/DB_USER/DB_PASSWORD、REDIS_HOST/REDIS_PORT/REDIS_DATABASE/REDIS_PASSWORD、LWA_CLIENT_ID/LWA_CLIENT_SECRET、SPAPI_OAUTH_ENCRYPTION_KEY_BASE64（或 keyring），均不在示例填真实值。私有应用不需要 SPAPI_APP_ID、SPAPI_OAUTH_REDIRECT_URI 公有回调配置；实际获批应用角色、正确站点、网络与配额仍必须核验。

**一次性导入**：停常驻后端及所有 Worker，保持上述只读配置及验证 OFF 可选（导入自身无远程交换），独立本机 Bash 环境继承上述私有变量。以下 UUID 与 Seller ID 也只在本机输入；使用隐藏输入避免命令历史和终端回显。不要在共享 shell、debug/trace 或录屏中运行。

```bash
cd /home/spark/Projects/github/merchivra
(
  set +x
  read -r -s -p 'Local tenant UUID: ' SPAPI_PRIVATE_IMPORT_TENANT_ID; echo
  read -r -s -p 'Local seller account UUID: ' SPAPI_PRIVATE_IMPORT_ACCOUNT_ID; echo
  read -r -s -p 'Amazon Seller ID: ' SPAPI_PRIVATE_IMPORT_SELLER_ID; echo
  read -r -s -p 'LWA refresh token: ' SPAPI_PRIVATE_IMPORT_REFRESH_TOKEN; echo
  export SPAPI_PRIVATE_IMPORT_TENANT_ID SPAPI_PRIVATE_IMPORT_ACCOUNT_ID
  export SPAPI_PRIVATE_IMPORT_SELLER_ID SPAPI_PRIVATE_IMPORT_REFRESH_TOKEN
  export SPAPI_PRIVATE_IMPORT_ENABLED=true
  export SPAPI_VERIFICATION_ENABLED=false
  ./apps/server/gradlew -p apps/server --no-daemon --console=plain bootRun --args='--spring.profiles.active=local --server.address=127.0.0.1 --server.port=0'
)
```

使用独立子 shell，导入值不留在父 shell；确认正常进程退出、没有错误、无 token 日志。环境变量仍可能被同权限进程读取，真实环境须受控；不要使用 --debug/--scan，不上传完整日志。此宿主机命令是按当前代码设计的操作方案，尚未真实执行。导入失败保持关闭，核对目标、UUID、已有 grant、Key、区域，不删除现存数据或绕过权限。常驻重启时确保 IMPORT=false、4项 import 输入均不存在、verification=true 仅在批准环境。前端“卖家授权”选店，期望 PRIVATE_SELF/PENDING_VERIFICATION，点击“手动验证 Marketplace”一次；通过为 VERIFIED/CONNECTED。失败只回传脱敏 HTTP/错误枚举，不反复点击。

## 4. 分步扩大读权限（首轮通过且分别获批后）

| 阶段 | 当前代码入口/开关 | 条件与验证 |
| --- | --- | --- |
| R1 | Seller verification | 仅上述首轮；配置站点全部活跃，独立核对 Seller ID |
| R2 | TEAM_ENABLED、SPAPI_ORDERS_ROLE_REVIEW_ENABLED | 两位真实独立 OWNER，其中审核者有 SECURITY_REVIEWER；[团队配置](../security/team-v15.md)、[V14](orders-role-review-v14.md)，使用真实批准材料，不能自审或造摘要 |
| R3 | CATALOG_SYNC_ENABLED | [V24](catalog-fba-live-sync-v24.md) LISTINGS/FBA start/step 单页，同一站点，核对来源 LIVE、SKU/数量及重复处理；不修改 Amazon 商品/库存 |
| R4 | ORDERS_SYNC_ENABLED、ORDERS_SYNC_MODE=LIVE、ORDERS_SYNC_LIVE_APPROVED | 另行明确部署批准，已核验站点及 Orders 角色；[V11](orders-worker-v11.md)，初始窗口、单页、重放、水位；不请求 BUYER/RECIPIENT，不确认发货 |
| R5 | SPAPI_FINANCES_ROLE_REVIEW_ENABLED、FINANCES_SYNC_ENABLED | 独立 Finance and Accounting 角色审核；listTransactions 只读，按原币/行键核对，无真实付款/利润确认 |
| R6 | SPAPI_REPORTS_ENABLED 暂保持 false | 先使用 Seller Central 官方下载已有结算报告，按 [E1/V270](../roadmap/current-plan.md) 私有核对。Reports createReport 是远程创建任务，不混入首次只读许可；后续单独检查既有报告读取、下载和操作门禁 |

全部 scheduler 保持 OFF，一次只放一个阶段。只读指 Amazon 不修改订单、价格、库存、Feeds、退款、付款等；本地 PostgreSQL 会写入授权/审计/同步数据。每轮校验独立角色、授权新鲜度、站点一致、原币、分页及幂等、429/5xx 正确退避。404 核查功能开关及 scope，401 核查本地 Session 或 Amazon 授权（勿混淆），403 核查角色/CSRF，409 核查状态与证据，429 等待重试，不调大配额。停止时关闭所有业务同步、verification/egress 开关；如需撤销先用私有授权 revoke 清除本地密文，再在 Amazon 官方后台独立撤权；本地撤销不是远程撤权，不保证已开始请求被瞬间取消。已失效角色/站点不得继续使用。

## 5. 脱敏回执（只提交准备状态）

- 私有应用已注册：YES/NO/UNKNOWN；目标站点：国家或站点代号；LWA三项已在本机准备：YES/NO；Seller ID 已备：YES/NO。
- 独立数据库/OWNER 安全配置：PASS/FAIL/UNKNOWN；独立 Redis 限流预检：PASS/FAIL/UNKNOWN；真实受控只读批准：PASS/FAIL/UNKNOWN；实际应用角色：Inventory and Order Tracking / Finance and Accounting 分别 PASS/FAIL/UNKNOWN。
- 若 R1 已在获批环境执行，仅记录本机 SHA、PRIVATE_SELF、导入/站点核验 PASS/FAIL/NOT_RUN、固定错误代码、核验站点数量，禁止回传 Seller ID、UUID、LWA 值、真实响应体、买家信息。
- 没有批准或材料则 NOT_RUN。E1–E5 仍独立，不把成功连接等同于真实财务签署。

精确基线 [`7dd29280438be1d53d3788b400e5cfc4b523ec0c`](https://github.com/kunlong-luo/merchivra/commit/7dd29280438be1d53d3788b400e5cfc4b523ec0c) [快 CI #46005938792](https://github.com/kunlong-luo/merchivra/actions/runs/46005938792) SUCCESS：Next #140528987913、Java #140528988203、Compose #140528988268；PG18 #140528990185、JaCoCo、完整 Next HTTP 按快 CI 范围跳过。完整 A3 仍对应 efe94b5841b05dfa14de6fea0167a0bb3badebd2，7/7。 本轮核查与方案编写不调用 Amazon，不导入 Token，不运行实际商家数据。新文档 SHA CI 另核。
