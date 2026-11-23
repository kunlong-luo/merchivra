# Merchivra V206 · 外部凭据原件内容 SHA-256 可重算契约

**2026-11-23 · Flyway V109 · 当前代码及隔离合成验收待本阶段精确提交。** 本阶段继续 P0-D/A 的原件真实性准备链，但严格区分「上传文件内容与租户声称摘要一致」和「第三方真的签发、物流真的交接、Amazon 订单实际履约」。

## 现有基础和复用策略

V203/V106 是本地 sale→pick→采购收货批次不可变关联；V204/V107 使用租户登记公钥验证出库签名，但没有核实真实外部 WMS；V205/V108 存档声称的运营商登记编号、登记文件 SHA-256、承运交接编号及交接文件 SHA-256，由另一名 Owner 内部复核一致性。V205 中的摘要来自提交者，不是从原件独立重算。

已有 [V52/V137 私有原件实现](../../apps/server/src/main/java/org/merchivra/erp/purchasing/LotProofBinaryService.java) 和 [ClamAV fail-closed 扫描器](../../apps/server/src/main/java/org/merchivra/erp/purchasing/LotProofAvScanner.java)，不新造 MIME 检测器、病毒扫描逻辑或散列算法。V206 将 `mediaType` 与 `digest` 改为公共静态工具，沿用现行 **2 MiB / PDF、PNG、JPEG 限制**和**仅显示为 Demo 时才允许的扫描豁免**。实际部署中扫描器不可用、超时或不是 CLEAN 必须拒绝，不可把未扫描文件冒充已核验。

## 数据契约 · Flyway V109

- 新表 `finance_dispatch_custody_original_v109` 对 `tenant_id,dossier_id,document_kind` 建立唯一约束；文件类型仅 `OPERATOR_REGISTRY` 和 `CARRIER_HANDOFF`，每档案最多两份原件。文件二进制 `BYTEA` 私有存储，不支持公网 URL 或下载接口。不可 UPDATE/DELETE；只返回文件标识、媒体类型、SHA-256、长度、上传 Owner。
- 文件的 `sha256` 是 **PostgreSQL 18 `GENERATED ALWAYS AS (encode(sha256(file_bytes),'hex')) STORED`**，长度也由实际 `octet_length(file_bytes)` 自动生成；用户无法提交“原件摘要”字段覆盖。写时触发器再次计算 `sha256(NEW.file_bytes)`，与原 V205 的运营商/承运商声称摘要一一比对；拒绝错类型、错摘要、跨租户、重复角色上传及异常/撤销档案。
- 数据库 `CHECK` 再对 PDF/PNG/JPEG 的魔数字节做独立限制（不是只依赖 Java）；文件至少 5 字节、至多 2 MiB。按租户行锁串行控制 V206 原件总量最多 **256 MiB**，避免两笔并发上传越过阈值；无自动删除、变更与异步转正功能。更长期的法务留存/容量策略仍需 G2/G3 审批。
- `finance_dispatch_custody_original_integrity_v109` 每次 SELECT **直接对实际 `file_bytes` 再次 SHA-256**，分别与保存时的生成 SHA 和 V205 声称摘要对比。返回 `ORIGINAL_CONTENT_MISSING`、`ORIGINAL_CONTENT_DIGEST_STALE`、`DOSSIER_OR_SIGNED_SOURCE_STALE`、`ORIGINALS_MATCH_BUT_INTERNAL_REVIEW_MISSING` 或 `ORIGINALS_MATCH_CLAIMS_EXTERNAL_UNVERIFIED`。
- 当 V205 内部复核声称 `DOCS_CONSISTENT_UNVERIFIED`，原件缺一份、V204 签名密钥撤销、V203 本地关联/历史 SKU 漂移、承运编号摘要冲突等仍在读时阻断；**旧审批不覆盖新的文件证据**。
- 表、视图的 `operator_identity_verified`、`physical_handoff_verified`、`platform_fulfillment_verified`、`actual_batch_cogs_verified`、`cogs_posted` 均不可从数据库伪造成真；`realizedBatchCogs=null`，`realizedNetProfit=null`。

## 受限 API 与安全

Session/CSRF、Owner、租户+Marketplace+订单范围和 V205/V204 的已有限制保持原样：

- `POST /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/custody-dossiers/{dossierId}/originals/{kind}`：`multipart/form-data`、`file` 唯一输入，`kind` 是 `OPERATOR_REGISTRY` 或 `CARRIER_HANDOFF`；先用成熟扫描器校验，再检查 V205 原始档案的实时完整性、文件 SHA，再由数据库独立触发器校验。返回 `201 Created`、`no-store` 元数据，不返回原始字节。原件不允许通过重复上传覆盖。
- `GET /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/custody-original-integrity`：先复用 V205→V204→V203 的多层复核，再读 V109 独立计算结果，汇报每档案 0/1/2 份原件、缺失数量、失效状态和**不可解除的真实来源/履约/COGS 阻断**。只读，`no-store`。
- **没有原件下载、二进制回显或外网自动上传**；不打印原始字节、用户凭据或文件名。PG18 原件储存须纳入数据库加密、容量/PITR/访问控制与隐私治理，否则 G3 生产继续 NO-GO。

## 验收范围

- Java `DispatchCustodyOriginalV206Test`：复用既有文件魔数/摘要、输入非法 kind、上游撤销优先、缺少文件不认证外部、扫描失败时不查询或写数据库、Owner/no-store 控制器和 2 MiB 文件大小限制。
- `MERCHIVRA_V206_DISPOSABLE_ONLY=true python3 scripts/smoke_custody_original_content_v206.py`：隔离 PostgreSQL18 安装 Flyway V109 后，使用**真实安装的触发器函数 + V109 源 SQL 视图**和 TEMP 表，最后 ROLLBACK；覆盖实际字节生成 SHA、读取重算、两份缺一份、错内容散列、错媒体魔数、上游撤销/摘要漂移、跨租户、重复文档类型、不可变和不可伪造外部核验。
- 运行当前精确 SHA 的 Java/Next/Chromium/Compose/真实 PG18/HTTP A3；只有 GitHub 结果成功才能把「工程验收通过」写入本文件。即使 7/7 绿灯，也是**合成技术证据**，不是可信文件来源和现实物流证明。

## 下一阶段 V207 前置条件

实际 B2 需要合法可信的外部运营商、企业注册机构、承运商/签收方核验通道，提供**有认证边界的查询回执**、真实文件签发方和签署时间核验，且要将被校验的原件/来源记录纳入隐私与保留治理。实际 Amazon FBA/FBM 履约证据仍须由维护者在本地明确授权并经相应安全审签后独立引入；G1/DEC-06/07/08 原币成本分摊、退货 COGS 回转与完整净利润仍未完成，绝不因为文件 SHA 相同而自动放行。

项目正式评分 **70/100**；真实 Seller/Ads OAuth **OFF**；`realizedNetProfit=null`；生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。
