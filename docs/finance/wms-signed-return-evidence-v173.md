# V173 · 外部仓储公钥登记、Ed25519 包裹签名与来源防重

## V173 精确主线 SHA 技术验收（2026-11-17）

- 功能 [PR #158](https://github.com/kunlong-luo/merchivra/pull/158) 合并代码 SHA `187a96f4c3782a48660f076b6f911f07b1150fb4`，快速 [CI #43969174921](https://github.com/kunlong-luo/merchivra/actions/runs/43969174921) SUCCESS。其初次 [完整 A3 #43969427227](https://github.com/kunlong-luo/merchivra/actions/runs/43969427227) **6/7：PG18 失败**；实际 Spring Boot/Flyway V98 已迁移、租户注册公钥与独立 Owner 审批已通过，失败源自合成脚本误让公钥**原登记人**撤销，系统正确返回 HTTP 403，而脚本错误期望 200。**这是测试角色假设错误，不能抹掉这次 FAIL。**
- 测试角色修正 [PR #159](https://github.com/kunlong-luo/merchivra/pull/159)：先明确断言原登记者撤销公钥返回 403，再使用独立 Owner 完成撤销，撤销后再次批准 409。仅更正 PG18 脚本与 A3 工作流唯一标记，**没有放宽签名或审批规则**。该 PR 快速 [CI #43969607749](https://github.com/kunlong-luo/merchivra/actions/runs/43969607749) SUCCESS。
- 最终业务+测试代码 SHA `14c88c4a40e53b8100f501bb1168c78e8377288a`，精确 SHA [完整 A3 #43969992583](https://github.com/kunlong-luo/merchivra/actions/runs/43969992583) **run_attempt=1、7/7 SUCCESS**：Java25/Gradle、PostgreSQL18/Flyway V98/真实 Spring HTTP 合成反例、Next.js16/React19/Vite、Chromium、Compose 配置和完整 Compose HTTP 皆成功。
- **证据范围**：JUnit 用即时生成的 Ed25519 密钥做真正签名验签/篡改检测；PG18 一次性合成仓库验证未验证外部签收时禁止提交、用户跨权限、来源唯一、密钥独立审批及永久撤销/不可变历史。**这些验证既没有使用实际 WMS 签发的公钥，也没有接通 Amazon FBA/FBM 实时认证 API，不能被解释为真实仓库收货或 COGS 核销认证。**

## 核心业务边界

V172 只有 `ATTESTED_LOCAL` 的**本地双人核查**。V173 为外部仓储回执添加独立的加密来源验证：接受合作 WMS 提供的 Ed25519 X.509 DER 公钥（Base64）、由第二个 Owner 对**租户内部登记**进行确认后，逐包裹校验对方持有的私钥所生成的 detached Ed25519 签名。**从未要求上传或保存私钥**。

**风险：公钥由租户 Owner 自主登记。** 即使 `signatureValid=true` 且来源经过独立 Owner 批准，也只能证明签名载荷与**登记公钥**匹配，**不能证明登记密钥确实属于外部仓储运营商**，更不能证明真实收到商品或商品可售。因此 `operatorIdentityVerified=false`、`physicalReceiptVerified=false`、`inventoryPosted=false`、`cogsReversalEligible=false` 恒成立。V162、V64 和 Next 利润中心不能将此来源认列为真实 COGS 回转。

## Flyway V98

- `finance_wms_signing_source_v98`：按 tenant + warehouse + source_code 唯一，冻结 Ed25519 X.509 SPKI 公钥，登记后状态 `PENDING` → **不同 Owner** `APPROVED` 或 `REVOKED`，后续也可由未参与登记的 Owner 从 `APPROVED` 撤销。撤销不可复活；换密钥必须注册新的来源代码版本。两人都只能核对租户登记，不能代表已验证第三方公司身份。
- `finance_wms_signed_return_v98`：每条记录以 `(tenant,source_id,external_receipt_id)` 唯一防重，且 `(tenant,intake_id)` 唯一使同一 V172 包裹只能有一份签名结果；通过复合 FK 强制 WMS 来源与 V172 包裹**为同一租户、同一本地仓库**。记录签名原文的 SHA-256、来源、公钥来源 ID、外部回执编号、观察时刻、件数、人工商品状态；不写任何采购收货/本地库存/损益。
- 三组 PG18 触发器禁止密钥替换及不可逆批准历史改写，禁止已存签名凭据和源审核日志被 UPDATE/DELETE；来源含 `TENANT_REGISTERED`，签名凭据状态仅 `SIGNATURE_VALID_UNTRUSTED`。

## 签名标准（版本 1）

签名算法 **Ed25519**，SHA-256 仅用于持久化载荷摘要，不用作 Ed25519 的额外预哈希。UTF-8 载荷按以下字段以单个 LF（`\\n`）连接，**最后一个字段后也要有 LF**：

```text
MERCHIVRA_WMS_RETURN_V1
{tenant UUID}
{marketplace UUID}
{Finances refund event UUID}
{V172 parcel intake UUID}
{local warehouse UUID}
{approved public key source UUID}
{external receipt ID}
{V172 original local receipt reference}
{V172 exact received quantity}
{V172 exact condition code}
{observedAt normalized UTC Instant ISO-8601}
```

全部 UUID 由服务端会话和已经绑定的 V172 数据确定，不接受自报 tenant；外部回执编号及本地包裹号仅允许安全字母数字与 `._:/-`，禁止注入换行。签名必须匹配当前通过 V63/V171 来源新鲜度复核的 `ATTESTED_LOCAL` 包裹、完全一致件数及人工处置、尚未被撤销的仓库签名来源。数字和字符串无法通过改变订单/商品/仓库/来源或签收时刻而重放；接收时使用 PG18 `REPEATABLE_READ`，锁公钥来源和同一个 V172 包裹，并重复校验与防重唯一索引。客户端展示标准载荷用于发送合作方签署，私钥保存在合作方。

## 安全 API 和 Next.js

- `GET /api/v1/finance/wms-signing-sources?warehouseId=<UUID>`：当前仓库最近 100 个密钥配置，不回传密钥私钥，返回 `operatorIdentityVerified=false`。
- `POST /api/v1/finance/wms-signing-sources`：正文 `{warehouseId,sourceCode,publicKeySpkiBase64,reason}`。仅当前 Session OWNER，可写但不能自审。
- `POST /api/v1/finance/wms-signing-sources/{sourceId}/approve|revoke`：正文 `{reason}`。不能自审，撤销永久有效。
- `GET /api/v1/finance/marketplaces/{marketplaceId}/events/{eventId}/return-intakes/{intakeId}/signed-wms`：精确同租户/Marketplace/退款/包裹验证后读取不可变签名结果。
- `POST .../signed-wms`：正文 `{sourceId,externalReceiptId,observedAt,quantity,condition,signatureBase64}`。只有当前 `ATTESTED_LOCAL` 包裹且来源公钥批准后才执行真实 Ed25519 核验；返回签名有效，始终返回 `physicalReceiptVerified=false` 和 `actualCogsReversal=null`。全部 POST 仍执行 Spring CSRF，所有 GET/POST 响应 no-store。
- Next.js16/Vite 共享 React19 组件 `WmsSignedEvidencePanel` 嵌入 V172 包裹工作台，支持受控公钥登记、独立审批/撤销、标准载荷预览与签名导入；Zod 强制验证真值标记，不允许通过 UI 误呈现真实仓库或 COGS 已核准。

## 合成验收与真实上线差距

JUnit 使用运行时生成的随机 Ed25519 密钥对进行**实际签名和验签**，检测单独修改仓库、退款事件、包裹、件数、处置、公钥来源与本地参考号都不能通过验证。PG18 HTTP 的一次性合成仓库只验证登记、来源唯一性、跨身份 Owner 拒绝、第二人审批、撤销无法复活、数据库触发器及未授权退款包裹拒绝入账；不能称为真正 WMS 回执的正向入库验收。

真正放开受信仓储还要求外部运营商身份出具/可信登记、密钥轮换和撤销分发、批量原始回执的来源追溯、真实货件物流签收和可售/不可售 QC、Seller/FBA 授权与对账、FBA/FBM 退货费用成本、历史 COGS 过账与 G1 会计批准。本期没有真实 Amazon OAuth、生产 API 写入、库存变动或冻结财务重算。**生产 #104 仍 NO-GO**。
