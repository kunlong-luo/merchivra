# Merchivra V207 · 私有外部凭证原件的法律保全、保留期限与访问审计

**2026-11-23 / Flyway V110 / 精确代码 SHA 的 CI/A3 待本轮确认。**

V203 本地销售到批次声明、V204 租户密钥的 Ed25519 观察、V205 声称外部运营商/承运文档摘要、V206 私有原件 `BYTEA` SHA-256 重算均已实现，但**没有**可信外部登记机关/承运商/签发人认证，也没有真实 Amazon 授权。V207 解决原件的治理准备度，而不是把内部操作员的两次认可冒充第三方主体真实性。

## 为什么复用 V138 而不是另起完整文档库

项目已有 V52/V137 私有 PDF/PNG/JPEG 原件存储、ClamAV fail-closed、V129 不可变访问审计、V138 内部法律保全/保留期限审查模型。V207 复用既有约束风格与 `finance_wms_append_only_v98()`，不复制文件字节、不改变 V109 的 MIME/容量/哈希规则，也不引入外部存储、额外认证系统或独立会计账本。

目前没有真实法务授权与目标环境签署。因此 V207 **默认法律保全始终有效**，内部任何留存政策复核均**不能解除 hold，不能删除或改变原件**。不创建后台自动删除、手工清理、归档迁移、公开下载或任何 COGS 写入能力。

## V110 不可变治理契约

- `finance_custody_original_retention_proposal_v110`：文件唯一、提案 Owner 与原件上传人必须不同。留存期限严格晚于当前时间 1 天且不超过 100 年。记录理由、UTC 提案时间，不可修改；`legal_hold=TRUE`，`deletion_permitted=FALSE`。
- `finance_custody_original_retention_review_v110`：同一提案最多一次复核，复核人不能是提案者或原件上传人，决策仅 `REVIEWED_NO_PURGE` / `REJECTED`。复核为正时必须复查 V109 **两份原件齐备、SHA 完整、V205 内部未核证来源状态当前仍未失效**。正面复核是**留存建议受内部复核**，不能证明外部签发方真实，不能解锁法律保全或过账。
- `finance_custody_original_metadata_access_v110`：任何 Owner 对单个文件 SHA、大小、原件类型、期限状态的成功读取均在事务内写入一条访问事件。摘要与字节数由 **PG18 触发器从 V109 已保存的原件记录捕获**，无用户可控覆盖，永远 `original_bytes_returned=FALSE`。审计不保存原始二进制、文件名或外部访问 token。
- `finance_custody_original_governance_v110`：只读查询 V109 原件和 V110 提案/审查的最新（唯一）来源状态，输出 `DEFAULT_LEGAL_HOLD_NO_RETENTION`、`RETENTION_PENDING_HOLD_REMAINS`、`RETENTION_REVIEWED_HOLD_REMAINS`、`RETENTION_REJECTED_HOLD_REMAINS` 或 `EVIDENCE_STALE_OR_UNVERIFIED`。V203–V206 任何旧来源撤销/跨摘要冲突均导致读时 fail-closed；`legalHold=true`、`deletionPermitted=false`、`actualBatchCogsVerified=false`。
- 原件表 V109 的 append-only/容量限制依旧不变。V110 所有提案、复核、访问事件均使用既有不可变触发器，禁止 SQL UPDATE/DELETE。**数据库表面上的不可变不替代目标环境的真正 WORM、对象锁、PITR、加密或特权审计，G3 仍未达标。**

## Owner API / CSRF / no-store

- `GET /api/v1/finance/custody-originals/capacity`：全租户仅元数据汇总，显示已存原件总数/总量（上限 256 MiB）、仍受 hold 的数量、已复核期限文件、过期或失效的原件数量。不会授权清理。
- `GET /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/custody-dossiers/{dossierId}/originals/{kind}/governance`：单个原件的只读隐私元数据；在同一事务内持久记录一次访问审计，日志失败即不放行响应。原始 BYTEA 不允许对外返回。
- `POST /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/custody-dossiers/{dossierId}/originals/{kind}/retention-proposals`：`retentionUntil` UTC ISO-8601 与 10–500 字符书面理由，数据库二次校验与原件上传人分离。
- `POST /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/custody-dossiers/{dossierId}/originals/{kind}/retention-proposals/{proposalId}/review`：另一 Owner `decision` / `reason`，数据库复核人分离、旧签名撤销与重复审签都拒绝。保留期内部复核始终无权删除。

以上接口全部从 Session principal 获取真实 Owner/租户，从路由验证 Marketplace、订单和档案，沿用 Spring Security CSRF、no-store。返回对象不含文件本体、客户/银行凭据或真实平台 API 结果。

## 真实 PG18/Java 合成回归

- `DispatchCustodyOriginalGovernanceV207Test`：拦截 release/delete/VERIFIED 伪决策、过期/超长留存期限、不合法理由、缺失文件防披露，检验 Controller Owner 范围、无原件字节、no-store。
- `MERCHIVRA_V207_DISPOSABLE_ONLY=true python3 scripts/smoke_custody_original_governance_v207.py`：在安装 Flyway V110 的**真实 PG18** 上，用 V109/V110 迁移源 SQL 视图、真正部署的 V110 PL/pgSQL 触发器、TEMP 表和 ROLLBACK，检测默认 hold、提案人/复核人与上传人独立、旧来源撤销、审计 SHA/字节长度不可伪造、同文件重复复核、跨租户引用、不可变以及外部身份/实际 COGS 永不升级。不会写生产表、不会调用真实 Seller/Ads 或外部 WMS。
- 最终以本轮**精确代码 SHA** 快速 CI 和完整 A3（PG18、Java25、Next16、Chromium、HTTP、Compose）为准；不能用 V206 的绿灯冒充 V207 的验收。

## 下一阶段 V208 的必须条件

真实的 B2 运营商主体/承运交接认证需要用户在本地合法授权可核验的外部机构通道、机构签发的不可篡改回执及身份证书链、文件发放/撤销时间；要审查托管环境加密和法律保留制度。没有这些证据、真实 Seller/Ads 只读 OAuth、G1/DEC 财务会计签署，不能把内部治理完成解读为实际发货、已确认批次 COGS 或净利润。

**正式项目评分 70/100，`realizedNetProfit=null`，真实 Amazon Seller/Ads 授权关闭，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) NO-GO。**
