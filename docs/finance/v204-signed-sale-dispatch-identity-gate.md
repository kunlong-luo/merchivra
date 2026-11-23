# Merchivra V204 · 独立可重算的本地 WMS 签名出库观察，不代表平台履约

**2026-11-23；代码与合成测试待本轮精确 SHA 验收。** 本阶段是在 V203 的不可变批次归属声明上增加 **Ed25519 签名观察**，不引入真实 Seller/Ads OAuth、不导入平台真实性、不中断既有 V98/V101 登记与密钥轮换，不执行 COGS/库存/GL 过账。

## 复用与证明层级

1. **本地声明（V106）**：订单项、pick line、receipt、QC、数量、采购行单价/原币、V200 有效 SKU 映射；当前 V203 的 `LOCAL_LINK_INTACT_UNVERIFIED` 只是声明内部一致。
2. **登记密钥签名（V98/V101）**：复用 `finance_wms_signing_source_v98` 的租户级 Ed25519 公钥与独立验证的 `finance_wms_key_proof_v101`；即使登记源 `APPROVED` 且密钥持有权验证成功，**仍然不等于外部 WMS 真实法人/设备身份和实物出库**。登记源随时可撤销。
3. **V107 签名观察**：`finance_sale_lot_signed_dispatch_v107` 原样追加签名、规范化 SHA-256、租户/站点/订单/声明/源、出库编号、数量、时间。唯一 claim 占用，唯一源+签名防重放；所有业务验证状态由数据库 `CHECK(FALSE)` 保持为未核实，没有历史回填。
4. **平台履约证据**：真实 Amazon FBA/FBM 单项履约、交付和退款事件目前 **未接入**，绝不使用 `sales_order.fulfillment_status_raw`、人工上传摘要或 V203 的本地 OUTBOUND 伪造已验证平台发货。

## Ed25519 签名契约（V1）

WMS 或隔离合成测试必须对以下精确 UTF-8 字节进行 **detached Ed25519** 签名，不能签任意 JSON 后声称有效：

```text
MERCHIVRA_WMS_SALE_DISPATCH_V1
<tenant UUID>
<marketplace UUID>
<sales order UUID>
<local V106 claim UUID>
<order item UUID>
<local pick-line UUID>
<purchase receipt UUID>
<warehouse UUID>
<enrolled WMS source UUID>
<external dispatch reference>
<source order-item identifier>
<V106 claimed external payload SHA-256 (lowercase)>
<assigned integer quantity>
<UTC ISO-8601 observed timestamp>
```

每行用 `\n` 分隔，末尾必须有 `\n`。字段来自服务器 V106 不可变快照（日期只由提交观察给出）；出库编号与 item 标识需符合严格安全字符约束，避免换行边界混淆。观察时间不得为未来 10 分钟以上或早于 366 天。**签名不能因旧密钥持有权证明就被视为可信第三方身份**；密钥签名若被篡改必须在读取时再次验证。

## 写时保护和读时失效

- Owner + Session/CSRF，`POST /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/sale-lot-claims/{claimId}/signed-dispatch` 仅接受 `sourceId`、`quantity`、`observedAt`、`signatureBase64`。用户无法发送可信度状态或采购成本；接口不会调用真实 Seller/Ads/WMS 端点，也不会改库存/利润。
- Java 重用 V98 `WmsSignedReturnService.verify` 完整 Ed25519 校验，V203 当前原订单/历史 SKU/本地批次复核、源审批及 V101 密钥持有权记录，数据库 V107 触发器再独立校验 V106 最新内部状态、租户/仓库/数量/dispatch 精确绑定。冲突不覆盖证据。
- 数据库追加不可变；只有同源、同声明、同 payload、同数量的一份观察可入库，防止重复引用与身份交叉。**撤销公钥、撤销 V106 归属或变更订单/QC/PO/出库批次均需读时失效**。数据库只读视图 `finance_sale_lot_dispatch_integrity_v107` 自动重判。
- Owner + `no-store` 的 GET `/api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/signed-dispatch` 再由 Java **独立重算规范化 SHA-256 与 Ed25519 签名**，同时与 V203 最新历史 SKU 状态比较。返回 `TENANT_KEY_SIGNATURE_ONLY_UNVERIFIED` 或 `LOCAL_CLAIM_STALE`、`SIGNING_SOURCE_REVOKED`、`KEY_POSSESSION_EVIDENCE_MISSING`、`SIGNED_DISPATCH_IDENTITY_CONFLICT`、`SIGNED_PAYLOAD_RECOMPUTATION_FAILED`、`SALE_LOT_OR_HISTORICAL_SKU_STALE`。即使全部内部匹配，也不能返回实际 COGS。
- `platformFulfillmentVerified=false`、`actualBatchCogsVerified=false`、`realizedBatchCogs=null` 恒保持；外部身份、实际实物出库和会计批准是独立的 **P0 不可自动解除阻断**。

## 测试与下一阶段

- Java：`SaleLotSignedDispatchV204Test` 用运行时生成的 Ed25519 测试密钥签名并验证数量、claim、source、dispatch 四类篡改失效；响应始终不能确认真实 COGS；Owner、no-store、API 作用域均受保护。
- PG18：`MERCHIVRA_V204_DISPOSABLE_ONLY=true python3 scripts/smoke_signed_sale_dispatch_v204.py` 在隔离数据库上检查 **已应用 Flyway V107 的真实触发器和源 SQL VIEW**，TEMP + ROLLBACK，覆盖没有 V101 密钥证明、重复 claim、源撤销、声明失效、数量更改、跨租户引用、不可变与伪造 TRUE 拒绝。纯 SQL 测试不证明 Ed25519 真伪；Java 单元测试负责算法层。
- 精确代码 SHA 的快速 CI / 完整 A3 成功后才能在此写入测试验收结论。所有 CI 使用隔离合成来源，不能证明真实第三方身份或 Amazon 原件。

下一阶段继续 **V205/P0-D 与 P0-B**：取得有授权且独立审计的外部 WMS 主体身份/设备登记、原始出库与交付见证；在维护者本地真实 Seller/Ads 只读授权、G1/G2/G3 合规审签之后才能开展真实平台订单项履约交叉核证，另需按批准的原币会计政策处理物流/关税及退款 COGS 回转。届时仍不得仅凭签名自动开启 COGS。

**正式项目评分 70/100、`realizedNetProfit=null`、Seller/Ads OAuth OFF、生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) NO-GO 均不变。**
