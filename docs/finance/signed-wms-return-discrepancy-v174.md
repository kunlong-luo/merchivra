# V174 · 外部退货签名观察与跨密钥回执对账隔离

## 目的与信任边界

V173 只能保存**完全等于 V172 人工核对数量、处置**的 Ed25519 签名证据。真实仓库回执出现件数差、可售判断差时，直接返回 409 会抹掉需要处理的源头差异。V174 通过单独的 **签名 V2 观察协议**保留“外部独立声称的数量/处置”与原 V172 本地数值，并保留不可修改的对账分类；不修改 V63/V171/V172/V173 原始记录。

**两个重要限制**：

1. 公钥仍仅由租户 Owner 登记并经不同 Owner 批准；这不是独立验证 WMS 运营商身份，签名有效也不是官方 Amazon FBA 实物入库证明。
2. 即使对账一致，数据库硬约束 `operator_identity_verified=false`、`physical_receipt_verified=false`、`inventory_posted=false`、`cogs_reversed=false`。状态 `MATCHED_UNVERIFIED` 永不等于真实净利润或允许 V162/V64 成本重算。

## 数据库 V99 / 全局回执去重

- `finance_wms_receipt_identity_v99` 是**跨 V173 与 V174**的全局回执预留表：主键 `(tenant_id,warehouse_id,external_receipt_id)`，另有 `UNIQUE(tenant_id,intake_id)` 和证据版本+ID 唯一；migration 自动登记现有 V173 签名来源。**轮换/新增另一版公钥不能复用相同外部仓库回执编号**，同一 V172 包裹也不能分别占用 V173 和 V174 证据。
- PG18 `AFTER INSERT` 触发器同时在 V173 / V174 签名记录入库时自动登记全局 ID；并发由数据库唯一约束负责最终仲裁，应用在预检和并发冲突时返回 409。全局身份本身也不可 UPDATE/DELETE。
- `finance_wms_return_observation_v99`：保存 V2 签名、SHA-256 摘要、同一 V172 包裹的本地快照、外部回执自报数量/处置、签署时间和分类。复合 FK 强制 `tenant + marketplace + refund event + intake + warehouse` 一致，另一个复合 FK 绑定相同 tenant/warehouse 的 V173 signing source。
- 分类由后端计算并经 PG CHECK 验证：`MATCHED_UNVERIFIED` / `QUANTITY_MISMATCH` / `CONDITION_MISMATCH` / `BOTH_MISMATCH`，必须与实际两套数量、处置完全一致。原始观察值及状态均不可修改。
- 动态状态分别揭示 `PENDING_EXTERNAL_EVIDENCE`、`STALE_LOCAL_HOLD`、`REVOKED_SOURCE_HOLD`、`MISMATCH_QUARANTINE_HOLD` 和 `IDENTITY_UNVERIFIED_HOLD`；已撤销签名来源的历史证据仍保留，但不可当作当前有效来源。

## 固定 Ed25519 V2 签名协议

UTF-8 固定单 LF 分隔，每个字段一行，末尾亦保留一个 LF。不要用 JSON 序列化或以 V173 V1 的签名代替：

```text
MERCHIVRA_WMS_OBSERVATION_V2
{session tenant UUID}
{marketplace UUID}
{current finance refund event UUID}
{V172 intake UUID}
{local warehouse UUID}
{approved V173 source UUID}
{external receipt ID}
{original V172 local receipt reference}
{original V172 local quantity}
{original V172 local claimed disposition}
{external signed observed quantity}
{external signed observed disposition}
{observedAt normalized UTC ISO-8601}
```

租户、Marketplace、退款事件、原始本地收货信息完全由服务端身份和已审查来源推导；只有外部回执 ID、观测值、日期、签名由调用者输入。使用 Java 25 Ed25519 实际验签，金额/数量不做浮点运算。受控来源必须 `APPROVED`、仓库一致、V172 `ATTESTED_LOCAL` 且 V171/V63 来源仍新鲜。事务为 PG18 `REPEATABLE_READ`，锁公钥和已登记本地包裹后重新校验原始来源和全局回执身份。

## API / React 工作台

- `GET /api/v1/finance/marketplaces/{marketplaceId}/events/{eventId}/return-intakes/{intakeId}/wms-reconciliation`：返回本地件数/处置、当前 V174（若无则读取原 V173）来源件数、差额、当前公钥撤销状态以及永久真实实收/库存/COGS 禁入标志。Tenant Session Owner、no-store。
- `POST` 同路径，输入 `{sourceId,externalReceiptId,observedAt,externalQuantity,externalCondition,signatureBase64}`。仅保存真正通过 V2 Ed25519 验证的**签名观察**，哪怕数量或处置有差异，也按分类隔离，而非丢弃真实差异。已占用的同仓外部回执/同一个 V172 包裹 HTTP 409，且所有写入执行 Session/CSRF。
- Next.js16/React19 与 Vite 共用 `WmsReturnReconciliationPanel`；挂载在 V173 页面，展示一致/差异/公钥撤销的动态冻结原因，编辑外部报告数量和处置，展示固定签名载荷并导入 detached Ed25519 签名。Zod 字面量永不允许真实实收/COGS 已认证。

## 验收与后续真实认证

- JUnit：生成真实 Ed25519 密钥对，验证 V2 完整签名，逐一修改 tenant、Marketplace、退款、仓库、原包裹、外部回执、两组数量/处置后签名均失效；四种差异分类与源码禁入。
- PG18 合成 HTTP：Owner 认证、无法导入无 V172 包裹的签名、V99 复合约束/全局回执索引/不可变触发器实际安装；不声称真实外部仓库端到端回执已验收。
- **未来 P0**：真实仓储运营商身份（外部预先可信公钥、合同/回调或认证来源）、物理签收订单/货件对账、可售 QC 与库存流水、审计批准的原始币种成本 / 历史 COGS 回转、V162/V64 完整费用和真实净利润；Amazon Seller/Ads OAuth 仍按用户约定保持关闭，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) NO-GO。
