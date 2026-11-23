# Merchivra V208 · 外部文件声称签发方的 Ed25519 绑定与读时复算

**2026-11-23 · Flyway V111 · [V208 精确代码 `6678558`](https://github.com/kunlong-luo/merchivra/commit/66785586f1856fc5647c48bbf4a975152a16fce7) · [同 SHA 快速 CI #45150207475 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45150207475) · [完整 A3 #45150207574（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45150207574)。仅合成工程测试成功，第三方真实身份信任锚仍未建立。**

## 问题与信任边界

截至 V207，V203 的本地批次来源守恒、V204 的**租户自行登记 WMS 公钥**签名、V205 的**租户声称**的外部运营商和承运交接文件编号、V206 的原件 BYTEA+SHA-256 内容独立重算、V207 的原件法律保全与元数据访问审计均已完成合成工程测试。但文件内容一致、内部 Owner 双人复核、公钥能够验证 Ed25519，**都不等于**文件由声称的真实第三方机构签发、真实仓库曾交货或 Amazon 已履约。

V208 只增加一个有意义的密码学证据层：不同文件类别各自绑定**声称签发方的公钥**，用真实 Java 25 Ed25519 验证对**固定版本、完整上下文**的脱离式签名，并在每次读取中重算公钥和历史原始 SHA 的关联。因为公钥可以由租户 Owner 自行登记，本阶段没有外部 CA/证书链、机构签发名录、OCSP/CRL、API 身份验证或者目标环境授权，**任何“签名验证成功”仍只表示租户登记的那把公钥确实签过相应内容**。

## V111 PostgreSQL 18 契约

1. `finance_custody_issuer_key_v111`：分别登记 `OPERATOR_REGISTRY` 或 `CARRIER_HANDOFF` 声称的签发方引用、DER X.509 Ed25519 SPKI Base64、PG18 从实际 SPKI 自动生成的公钥 SHA-256 指纹，时间及 Owner 审计。一个公钥不得跨两类签发方重用。状态仅 `REGISTERED_UNVERIFIED`、`REVIEWED_UNVERIFIED`、`REVOKED`；只有另一名 Owner 内部复核后才能导入签名，审核不变成外部认证。原始公钥、所声称的机构引用、登记人不可改，撤销**不可逆**，历史关联不能刷新成外部已核实。
2. `finance_custody_issuer_signature_v111`：针对 V205 档案的 V206 某类实际原件，最多保存一条声明签名；`(tenant,dossier,kind)`、`(tenant,original_file_id)` 去重。数据库从 V108/V109 **自己捕获** Marketplace、订单、V204 签名出库 ID、外部文件编号、原件 SHA、声称签发方引用，而不信任客户端传入的伪快照。仅允许两份原件齐备且 V203–V206 当前完整时导入；签发时间在十年内且不晚于当前时间十分钟。导入 Owner 与公钥登记人、档案提交人、原件上传人独立。
3. `finance_custody_issuer_signature_integrity_v111`：在每次查询时加入最新的 V109 源内容 SHA、V108 身份和 V111 公钥状态；状态为 `ISSUER_KEY_REVOKED_OR_UNREVIEWED`、`ORIGINALS_OR_UPSTREAM_STALE`、`ISSUER_SIGNATURE_IDENTITY_DRIFT` 或 `TENANT_KEY_SOURCE_CURRENT_CRYPTO_RECOMPUTE_REQUIRED`。**最后一个状态只表示数据库信息匹配，绝不表示 Ed25519 数学验证已执行**。Java 还要根据完整规范化 UTF-8 原文独立重算 SHA 与 Ed25519，才可以显示 `SIGNATURE_VALID_FOR_TENANT_KEY_ONLY_EXTERNAL_UNVERIFIED`。
4. V111 关联签名及证据状态不可修改/删除；所有 `external_issuer_identity_verified`、`independent_trust_anchor_verified`、`physical_handoff_verified`、`amazon_fulfillment_verified`、`actual_batch_cogs_verified`、`cogs_posted` 一律数据库 `CHECK(FALSE)` 或视图永假；没有任何真实财务过账。

## 精确 canonical UTF-8 V1 原文

按固定顺序，逐个以 LF 换行，结尾也有 LF：

```text
MERCHIVRA_CUSTODY_CLAIMED_ISSUER_V1
tenant_uuid
marketplace_uuid
order_uuid
dossier_uuid
v204_signed_dispatch_uuid
v206_original_file_uuid
OPERATOR_REGISTRY|CARRIER_HANDOFF
lowercase_original_file_sha256
claimed_issuer_key_uuid
v205_external_document_reference
claimed_issuer_reference
claimed_issued_at_utc_ISO_INSTANT
```

没有 JSON map 任意序列化、可更改字段顺序、空格截断或未界定的文本。签名为 UTF-8 原文字节的 Ed25519 detached 64 bytes Base64；签发时间 UTC instant 规范化。Java 使用已有 `WmsSignedReturnService.verify` 统一 Ed25519 解码和公钥校验，使用现有 SHA 函数，不新增第二套密码算法。

## Session/CSRF、Owner 与 API

- `GET/POST /api/v1/finance/custody-issuer-keys`：租户范围的声称签发方公钥目录/登记；GET 只返回引用、SHA-256 指纹和 `UNVERIFIED` 状态，不公开私有原件或签名私钥。
- `POST /api/v1/finance/custody-issuer-keys/{issuerId}/review`：另一名 Owner 记录留痕的内部 `REVIEWED_UNVERIFIED`，不等于 CA 签署。 `POST /api/v1/finance/custody-issuer-keys/{issuerId}/revoke`：永久撤销并使历史签名 fail-closed。
- `POST /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/custody-dossiers/{dossierId}/originals/{kind}/claimed-issuer-signatures`：只接受 `issuerKeyId`、`claimedIssuedAt`、`signatureBase64`；先复用 V206/V205/V204/V203 完整重算，再锁定真实原件与公钥并验证确切签名。数据库再独立锁定/捕获身份。签名不能替代真实第三方签名机构认证。
- `GET /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/custody-issuer-integrity`：Owner、`no-store`，重新计算 Ed25519、上游 SHA 以及撤销状态，分别列出完整性、缺少的两类签名和固定真实核证/COGS 阻断。

不调用任何真实 Seller/Ads、银行、WMS、企业登记机构、CA 或承运商 API，不提供原件下载、对象删除、法律保全解除、库存/COGS 写入、生产权限切换。

## 验收及下一步

- Java `CustodyIssuerAttestationV208Test` 运行时生成真实 Ed25519 密钥并签署 V1 原文；逐项修改文件种类、SHA-256、签发公钥、原件 UUID 和档案 UUID 应全部不能复核成功；拒绝超界时间/换行注入/伪 VERIFY 标签，并验证 Owner/no-store 与无证据时实际利润 NULL。
- PostgreSQL18 `MERCHIVRA_V208_DISPOSABLE_ONLY=true python3 scripts/smoke_custody_claimed_issuer_signatures_v208.py`：先检查 Flyway V111 已安装，在 TEMP 阴影表调用真正 V111 数据库触发器和从迁移文件提取的原始 V109/V111 视图 SQL，最后 ROLLBACK；覆盖内部公钥审核不得自审、分离导入人、跨租户/错种类、公钥不能篡改与复用、签名快照 DB 覆盖、重复签名、撤销实时失效、上游失效、财务硬假。SQL 不生成真实可信签名，只有 Java 测试实际执行密码算法。本轮 [V208 精确代码 `6678558`](https://github.com/kunlong-luo/merchivra/commit/66785586f1856fc5647c48bbf4a975152a16fce7) 的 [同 SHA 快速 CI #45150207475 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45150207475) 和 [完整 A3 #45150207574（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45150207574) 已通过，A3 7 个 Job 全部 SUCCESS。PG18 Job 实际含 `PASS V208 PG18 V111 exact source capture/key guards/view`；SQL 证明 V111 原始触发器/视图的来源约束与撤销失效，Java 用真实运行时 Ed25519 完成签名重算。不得用 V207 的绿灯代替本轮验收；也不得据此声称外部身份。
- **下一阶段 V209/B2** 首先需要维护者本地明确授权的**权威签发方/机构注册来源**、可信证书或公钥指纹的外部认证、证书有效期/吊销机制和独立托管证据；否则不能从 `SIGNATURE_VALID_FOR_TENANT_KEY_ONLY_EXTERNAL_UNVERIFIED` 升级到真实核证。随后 Amazon 授权需另行合规批准与只读交叉核验；G1/DEC 财务政策和退款 COGS 仍未签署，不能自动核算真实利润。

正式项目评分 **70/100**，`realizedNetProfit=null`，真实 Amazon Seller/Ads OAuth **OFF**，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。
