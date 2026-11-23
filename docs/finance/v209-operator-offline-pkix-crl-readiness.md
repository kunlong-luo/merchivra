# Merchivra V209 · 部署侧离线 X.509/PKIX + 签名 CRL 信任准备度

**2026-11-23 · 无新 Flyway 迁移 · [V209 精确代码 `0a9dc09`](https://github.com/kunlong-luo/merchivra/commit/0a9dc0903a92b64d5891c03011f5b3ef85fbc560) · [同 SHA 快速 CI #45155665615 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45155665615) · [完整 A3 #45155665675（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45155665675) · [同 SHA Redis Session 集成 #45155665716 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45155665716)。只有合成工程验收，没有独立外部法人认证。**

## 目的与业务硬边界

V203–V208 已做到本地销售→批次归属、租户注册 WMS 出库签名、**租户声称**的第三方运营商/承运原件引用、私有原件字节 SHA-256 重算、原件法律保全、租户自登记**声称签发人公钥** Ed25519 重算及撤销。它们还不能确定真实的第三方签发机构、物理交接或 Amazon 履约。

V209 **不新建第二套真实来源注册、任何带“verified”标记的 DB 表或额外内部审批**。唯一新增的是一个显式关闭的、部署运营方本地只读 **X.509 证书链 + 签名 CRL 技术准备度检查**。证书、根信任锚、CRL 和允许的签发方 DNS 由部署运营方在应用配置外部维护，不能由 ERP 租户用户通过 API 上传、登记、复核或变更。V208 原 Ed25519 证据仍必须在每次读取时实际重算；不能仅查数据库中有签名记录就显示完成。

**关键：技术验证不是法律认证**。运营方在本机放一个自签 root 或配置一个 DNS 白名单，不能证明该 root 属于声称的真实登记机构或承运公司。即使 PKIX、原件 Ed25519、公钥指纹、SAN、有效期限、CRL 全通过，V209 仍只返回 `OFFLINE_PKIX_AND_CRL_MATCH_TENANT_KEY_EXTERNAL_IDENTITY_UNVERIFIED`，所有 `independentlyVerifiedIssuerIdentity=false`、`physicalHandoffVerified=false`、`amazonFulfillmentVerified=false`、`actualBatchCogsVerified=false`、`realizedBatchCogs=null` 不可自动放开。

## 配置（默认全关闭，无真实第三方请求）

Spring `application.yml` 中新增以下**纯部署侧**配置，以环境变量指定；生产默认空值/禁用：

| 环境变量 | 含义 / 失败关闭行为 |
| --- | --- |
| `MERCHIVRA_EXTERNAL_ISSUER_OFFLINE_TRUST_ENABLED` | 默认 `false`，即使有租户登记公钥也返回 `OPERATOR_OFFLINE_TRUST_DISABLED` |
| `MERCHIVRA_EXTERNAL_ISSUER_TRUST_ROOT_CERT_FILE` | 独立部署方验证过来源的单份 X.509 CA 根证书 PEM 绝对路径；不允许相对路径、符号链接、普通租户上传 |
| `MERCHIVRA_EXTERNAL_ISSUER_TRUST_CHAIN_FILE` | 已获得授权的供应方证书与中间 CA 证书 PEM 包绝对路径；必须有与 V208 SPKI SHA-256 完全相同的 Ed25519 签名 leaf |
| `MERCHIVRA_EXTERNAL_ISSUER_TRUST_CRL_FILE` | 离线证书撤销清单 PEM 绝对路径，必须有 CA 的有效签名，覆盖 leaf/中间 CA，不接受缺失、过期或已吊销 |
| `MERCHIVRA_EXTERNAL_ISSUER_OPERATOR_DNS_ALLOWLIST` | 运营商登记类允许的**精确 DNS SAN** 名，逗号分隔，无通配符；默认空 |
| `MERCHIVRA_EXTERNAL_ISSUER_CARRIER_DNS_ALLOWLIST` | 承运交接类允许的**精确 DNS SAN** 名，逗号分隔，无通配符；默认空 |

路径对应部署运维的只读本地文件，无网络 URL 或外部自动抓取；每份文件限制 **64 KiB**，拒绝相对路径、符号链接、组/其他人可写文件，解析错误/丢失则失败关闭。校验时每次重新加载文件，CRL 撤销更新或运营商删除材料不能继续使用已缓存的绿色状态。运行环境 POSIX 文件权限、父目录 ACL、密钥/证书分发与轮换仍需 G3 独立审查。

**离线校验逻辑**：必须为有效期内、数字签名用途的 Ed25519 leaf，不允许 leaf 是 CA；leaf 的 SubjectAltName DNS **完全匹配**部署角色白名单中被 V208 声称的签发人 DNS（CN 不能替代）；其 SPKI 的原始 DER SHA-256 精确匹配数据库 V111 原公钥指纹。证书链只从配置的本地 PEM 构建、使用 JDK PKIX `CertPathValidator` 校验到本地单一根证书（没有自动 AIA/CRLDP/OCSP 出站）。根证书必须自签、CA/keyCertSign/crlSign 允许且在有效期。逐级对 leaf 和所有中间 CA 检查**正确 CA 签名的 X509 CRL**；`thisUpdate` 不得在未来，签发时间距当前最多约 7 天，`nextUpdate` 必须在未来且窗口不能超过 7 天；任一有效 CRL 显示撤销即失败。以 UTC 时间检查，不依赖本地机器展示时区。

因此无法保证“部署已经验证外部证书根来源”时，最多只能得到 `chainValidForOperatorPinnedRoot=true` / `offlineRevocationCurrent=true` 的**技术布尔值**；一律附带外部身份未经认证阻断，不能作为 B2 通过条件。

## 只读接口与审计

`GET /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/custody-external-trust-readiness`

复用原 Session/Owner/租户/Marketplace 访问范围与 no-store。首先调用 V208 Preview，独立重算所有 Ed25519 原文和 V203–V207 源；仅对 V208 判为 `SIGNATURE_VALID_FOR_TENANT_KEY_ONLY_EXTERNAL_UNVERIFIED` 的证明调用 V209 PKIX/CRL；校验结果仅留在当次请求，不写新的权威数据库状态。响应对每份原件报告 `v208EvidenceState`、`operatorOfflineTrustState`、`pinnedCertificateChainValid`、`signedOfflineRevocationCurrent`，同时始终携带真实来源身份、实物交接、Amazon FBA/FBM、G1 实际 COGS/净利润阻断。**无任何证书上传/签发信任锚管理、证书链外发、删除原件、解锁实际入账的 API。**

## 测试和验收

`OfflineIssuerCertificateTrustV209Test` 使用 Java 25 JDK PKIX + 测试目录公开的**合成 Ed25519 根 CA/leaf/签名 CRL**（只存公开证书与 CRL，未存任何私钥）测试正例和以下负例：

- 禁用开关、文件丢失、根证书错误、根/leaf/CRL 伪造、证书 SHA-256 不匹配、角色白名单不包含 DNS、租户自登记声称身份不在部署白名单。
- 当前 CRL 在同一服务运行中被运营方替换为真实签名的撤销清单后，必须立刻返回吊销阻断；旧 CRL 到期必须阻断，不能缓存先前的绿色结果。
- 没有 V208 Ed25519 或旧来源已经失效时，不得调用离线证书核验，不能显示任何外部身份已核实；返回总是 `realizedBatchCogs=null`。
- 公开的合成证书是测试信任材料，**禁止复制到生产配置**。测试时使用注入的固定时钟，避免静态 CRL 测试凭据过期造成漂移；真实运行始终用 UTC 当前时钟。
- 本轮 [V209 精确代码 `0a9dc09`](https://github.com/kunlong-luo/merchivra/commit/0a9dc0903a92b64d5891c03011f5b3ef85fbc560) 的 [同 SHA 快速 CI #45155665615 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45155665615)、[完整 A3 #45155665675（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45155665675)（A3 7/7）和 [同 SHA Redis Session 集成 #45155665716 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45155665716) 均已 SUCCESS，Java25/Next16/Chromium/Compose/隔离 PG18、HTTP 回归通过；匿名新 GET 401 纳入 CI。V209 不增加迁移或 PostgreSQL 写状态，不能把“V209 新 Flyway 验收”写入记录。

## 尚未完成的真实 B2/P0-A 与后续 V210

1. 需**真正独立渠道验证根证书、CA 身份与法定运营主体映射**，获得授权提供方的 CA/运营商名录、证书序列、签发合法性、吊销证据和可审计取得链。运营团队设置一个任意自签 root 不满足这项；本轮没有外部证据，B2 仍未完成。
2. 仅在维护者授权并签署 G1/G2/G3 后进行真实 Amazon Seller/Ads 只读联调和实际物流对账，退款 COGS 回转与跨币种/费用归属仍未通过。
3. 项目长期北极星是**所有真实成本、退款回转及可审计利润**，不应无限增加与实际利润无关的本地签名档案。下一阶段优先重新核查 P0-B 的缺证清单与退款 COGS 准入条件。

**正式项目评分 70/100，`realizedNetProfit=null`，真实 Amazon Seller/Ads OAuth OFF，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) NO-GO。**
