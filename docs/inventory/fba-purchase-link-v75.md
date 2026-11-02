# FBA / Purchase V75 · 明确货件身份与供应商 ASN 本地证据关联

2026-11-02，产品 V75 / Flyway V73。基于 V20 本地采购收货、V43 供应商申报 ASN、V65 本地 FBA 货件意向与入仓人工/Mock 报告、V72 采购交付看板和 V74 风险优先级。**这是人工核对两个系统中记录 ID 的证据关系，不是 Amazon FBA 官方货件关联、库存核销、承运商跟踪、自动入库或确认真实送达。**

## 为什么没有做自动按 SKU 匹配

同一内部 SKU 可能属于多店铺/多个 Seller SKU，日期或数量接近并不证明相同批次。只有当前 marketplace 下 ACTIVE Listing → ACTIVE SKU 映射、active 内部 SKU、已批准（APPROVED/RECEIVING/RECEIVED）PO 行，才提供候选；需由 Owner 显式选中**特定本地 FBA 计划行 + 特定采购行**，并提交长度 10–200 的证据编号/人工引用。可选 ASN 必须状态 SHIPPED 且拥有**同一采购行的外键**。没有 ASN 仍可以明确标注只有采购行关联，但不会推定供应商发货。

## 人工双人复核与不可变身份

- POST /api/v1/fba/marketplaces/{marketplaceId}/shipments/{shipmentId}/purchase-links 提议关联，requestKey(UUID) 幂等；只能在本地货件 DRAFT 期间提交。
- GET /purchase-links，GET /purchase-links/lines/{inboundLineId}/candidates 当前 Owner Session / ACTIVE tenant 下查询证据及候选。
- POST /purchase-links/{linkId}/confirm 或 /revoke 带 10–200 字符复核原因。提出人不能确认自己提出的链接。不同 Owner 才能把状态 PROPOSED 改 CONFIRMED；待审核的提议可撤销，并保留历史，重新提交需要新请求密钥。第二 Owner 复核只是**人工身份证明**。
- 仅 PROPOSED 可以确认/撤销；CONFIRMED 不可重写、发运后所有关联不可更改。同一货件行至多一个有效链接，历史 REVOKED 行继续可查。修改以 FOR UPDATE 锁住货件父行并重新锁定读当前证明，避免并发第二 Owner 重复变更与重复审计。原 V65 dispatch 在任何 PROPOSED 关联尚未审核时返回 409；没有关联的原 V65 货件流程保持兼容。
- 原 V65 9 个操作与 Orval 生成为既有固定契约：scripts/normalize-fba-openapi.mjs 排除新 purchase-links 路由和新 DTO，保证老客户端生成稳定。V75 前端复用同一个 merchivraFetch 安全传输（Session、CSRF、no-store、站内相对路径）并以 Zod 校验扩展响应，不引入另一套鉴权或直接修改 generated 文件。

## 数量证据与不可“自动对账”

- 单个本地 FBA 行的计划件数不得超过对应批准 PO 行的可用**总订购容量**，所有 PROPOSED/CONFIRMED 链接按真实 inbound_line.planned_quantity 累加，按同 PO 行上锁原子串行化。REVOKED 不占容量。
- 如果指定 ASN，所有有效链接对同 ASN 的累计计划件数不能超过该 SHIPPED ASN 的申报 quantity。可将同一 PO/ASN 显式分配到不同 FBA 计划行，但不能重复占用已核对的计划件数。
- 只读列表分别展示 FBA 人工报告入仓、FBA 计划件数、PO 订购与本地实收、供应商 ASN 申报及实际关联的**本地 ASN 实收**。**这些是来源各自的全量事实，不代表一个货件的逐件实物核销，也不能跨多个链接合计本地实收。** 不扣 PO 未收、不加 FBA 可售，不操作付款或 FBA Shipment API。
- 货件 evidence_origin 仍限 MOCK/MANUAL_UNVERIFIED。CONFIRMED 是人工确认关系状态，不可读作 AmazonVerified=true；原 V65 amazonVerified/fbaStockCredited 一律 false。

## 测试与受控操作

scripts/smoke_fba_purchase_links_v75.py 使用一次性 127.0.0.1 PostgreSQL18、Mock 开、SP-API/OAuth 关，走真实 HTTP：单店铺 Listing/SKU 映射、PO 双 Owner 审批、SHIPPED ASN、FBA local DRAFT、候选过滤、提议/请求重放、原提议人确认失败、第二 Owner 确认、未确认拦截 dispatch、发运后不可改变、分拆数量上限、撤销与再申请、错误货件范围拒绝、本地 ASN 收货与 FBA 本地报告分离、追加式审计及无 FBA 库存写入。JUnit/Vitest 还验证输入证据长度、鉴权安全传输、NULL ASN 和禁止假冒官方确认；Chromium 360px 验证本地审核待办与来源标签。

## 未实现

尚无可靠可验证的 Amazon FBA Shipment ID 与本地货件 ID 官方对照，也无承运商 POD/实际 FBA Receipts 读取，无法声明“实际在途”或“已由 Amazon 签收”。未来必须独立建立 Seller 授权、真实只读 Shipment API、事件源签名/版本/时间链与补偿机制，并取得用户本地权限确认。不得直接把本阶段的本地证明加入 Amazon 可售或自动采购净需求。
