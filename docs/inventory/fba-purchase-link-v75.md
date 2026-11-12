# FBA / Purchase V75 · 明确货件身份与供应商 ASN 本地证据关联

2026-11-02，产品 V75 / Flyway V73。基于 V20 本地采购收货、V43 供应商申报 ASN、V65 本地 FBA 货件意向与入仓人工/Mock 报告、V72 采购交付看板和 V74 风险优先级。**这是人工核对两个系统中记录 ID 的证据关系，不是 Amazon FBA 官方货件关联、库存核销、承运商跟踪、自动入库或确认真实送达。**

## 为什么没有做自动按 SKU 匹配

同一内部 SKU 可能属于多店铺/多个 Seller SKU，日期或数量接近并不证明相同批次。只有当前 marketplace 下 ACTIVE Listing → ACTIVE SKU 映射、active 内部 SKU、已批准（APPROVED/RECEIVING/RECEIVED）PO 行，才提供候选；需由 Owner 显式选中**特定本地 FBA 计划行 + 特定采购行**，并提交长度 10–200 的证据编号/人工引用。可选 ASN 必须状态 SHIPPED 且拥有**同一采购行的外键**。没有 ASN 仍可以明确标注只有采购行关联，但不会推定供应商发货。

## 人工双人复核与不可变身份

- POST /api/v1/fba/marketplaces/{marketplaceId}/shipments/{shipmentId}/purchase-links 提议关联，requestKey(UUID) 幂等；只能在本地货件 DRAFT 期间提交。
- GET /purchase-links，GET /purchase-links/lines/{inboundLineId}/candidates 当前 Owner Session / ACTIVE tenant 下查询证据及候选。
- POST /purchase-links/{linkId}/confirm 或 /revoke 带 10–200 字符复核原因。提出人不能确认自己提出的链接。不同 Owner 才能把状态 PROPOSED 改 CONFIRMED；待审核的提议可撤销，并保留历史，重新提交需要新请求密钥。第二 Owner 复核只是**人工身份证明**。
- 仅 PROPOSED 可以确认/撤销；CONFIRMED 不可重写、发运后所有关联不可更改。同一货件行至多一个有效链接，历史 REVOKED 行继续可查。修改以 FOR UPDATE 锁住货件父行并重新锁定读当前证明，避免并发第二 Owner 重复变更与重复审计。原 V65 dispatch 在任何 PROPOSED 关联尚未审核时返回 409；没有关联的原 V65 货件流程保持兼容。
- **V75 历史实现**曾刻意在 Orval 中排除 purchase-links。**V145（2026-11-12）已将以下五条现有 Controller 接入真实 Springdoc/Orval 生成链**，复用 `merchivraFetch` 的 Session、CSRF、no-store 和 Zod；不再手写关联请求路径/JSON transport。

## 数量证据与不可“自动对账”

- 单个本地 FBA 行的计划件数不得超过对应批准 PO 行的可用**总订购容量**，所有 PROPOSED/CONFIRMED 链接按真实 inbound_line.planned_quantity 累加，按同 PO 行上锁原子串行化。REVOKED 不占容量。
- 如果指定 ASN，所有有效链接对同 ASN 的累计计划件数不能超过该 SHIPPED ASN 的申报 quantity。可将同一 PO/ASN 显式分配到不同 FBA 计划行，但不能重复占用已核对的计划件数。
- 只读列表分别展示 FBA 人工报告入仓、FBA 计划件数、PO 订购与本地实收、供应商 ASN 申报及实际关联的**本地 ASN 实收**。**这些是来源各自的全量事实，不代表一个货件的逐件实物核销，也不能跨多个链接合计本地实收。** 不扣 PO 未收、不加 FBA 可售，不操作付款或 FBA Shipment API。
- 货件 evidence_origin 仍限 MOCK/MANUAL_UNVERIFIED。CONFIRMED 是人工确认关系状态，不可读作 AmazonVerified=true；原 V65 amazonVerified/fbaStockCredited 一律 false。

## 测试与受控操作

scripts/smoke_fba_purchase_links_v75.py 使用一次性 127.0.0.1 PostgreSQL18、Mock 开、SP-API/OAuth 关，走真实 HTTP：单店铺 Listing/SKU 映射、PO 双 Owner 审批、SHIPPED ASN、FBA local DRAFT、候选过滤、提议/请求重放、原提议人确认失败、第二 Owner 确认、未确认拦截 dispatch、发运后不可改变、分拆数量上限、撤销与再申请、错误货件范围拒绝、本地 ASN 收货与 FBA 本地报告分离、追加式审计及无 FBA 库存写入。JUnit/Vitest 还验证输入证据长度、鉴权安全传输、NULL ASN 和禁止假冒官方确认；Chromium 360px 验证本地审核待办与来源标签。

## 未实现

尚无可靠可验证的 Amazon FBA Shipment ID 与本地货件 ID 官方对照，也无承运商 POD/实际 FBA Receipts 读取，无法声明“实际在途”或“已由 Amazon 签收”。未来必须独立建立 Seller 授权、真实只读 Shipment API、事件源签名/版本/时间链与补偿机制，并取得用户本地权限确认。不得直接把本阶段的本地证明加入 Amazon 可售或自动采购净需求。

## V145 · 来源关联 API 合同收尾（2026-11-12）

| 现有操作 | 稳定 operationId |
| --- | --- |
| GET purchase-links | `listFbaPurchaseLinks` |
| GET purchase-links/lines/{lineId}/candidates | `listFbaPurchaseCandidates` |
| POST purchase-links | `proposeFbaPurchaseLink` |
| POST purchase-links/{linkId}/confirm | `confirmFbaPurchaseLink` |
| POST purchase-links/{linkId}/revoke | `revokeFbaPurchaseLink` |

`apps/web/openapi/fba-inbound.json` 由真实 Spring MVC `/v3/api-docs/fba-inbound` 导出，经 `scripts/normalize-fba-openapi.mjs` 筛选后共 **15 项操作**（原 10 + 关联 5）。SDK 的旧 `swagger-annotations` 与 Springdoc 的 `swagger-annotations-jakarta` 含重复类，遇到字段 `@Schema` 时会出现 `NoSuchMethodError: Schema.schemaResolution()`；Gradle 仅排除 SDK 传递依赖中的旧注解包，继续使用 Springdoc 提供的 Jakarta 注解，SDK 版本不变。归一化器核对唯一 operationId 和 DTO 引用；生成产物由 `npm run api:generate` 更新，`npm run api:check` 检查漂移。可选 ASN、待审核确认人/时间在源 DTO 明确保留 OpenAPI 3.1 的 `null`，避免生成类型把未知值压成缺失或零。动态 Marketplace、shipment、line 和 link 路径均先经 UUID 校验，再交由生成函数；不接受浏览器 tenantId。服务端 `currentTenant`、独立 Owner、事务锁、幂等键、来源容量和 CSRF 仍由原有实现负责。

`FbaPurchaseLinkOpenApiV145Test` 在一次性随机环回端口启动真实 Springdoc，关闭 Flyway 并模拟首次管理员 bootstrap，核对五项操作、UUID 路径、隐藏认证参数及请求/响应形状；**不是 PG18 集成测试**。输出 `apps/server/build/fba-openapi-v145.json` 可供本地归一化。Vitest 覆盖生成请求的 CSRF、撤销、401/403/409 单次写入、非法路径零请求、来源字段与 NULL 校验。完整 PG18 的现有 V75 真 HTTP 冒烟和合同逐字节检查仍在 `full_suite` 执行，结果须绑定新提交核实。

**仍未解决的容量断点**：`links()` 的 `LIMIT 200`、`candidates()` 的 `LIMIT 100` 继续存在；生成客户端不等于全库分页。超限旧来源仍可能无法在页面选择，Issue #62 继续开放，下一批应补稳定全排序的服务器游标及页面接入。其他 FBA 对账/异常接口也不能因此称为全部已接入 Orval。Next 正式切流 #63、目标环境人工签署 #104 与 Amazon 本地授权均独立保留。

本轮已基于 V144 main `299d7215abda2f0daa08ee42327c4c7d1febbd10` 重放，保留会话缓存隔离和 11 条 Next 路由回归。本地验证：Java 25 Gradle **567 项全部通过**（受限容器以显式 Mockito agent 运行），Vitest **81 文件 / 354 项通过**，React/Next 类型检查及 Vite build 通过；真实 Springdoc 导出归一化通过，移除操作/删除引用 DTO 两个负例均拒绝。以上不包含本地 PG18/Compose/Chromium；最终候选在 GitHub 的同 SHA 完整 A3 结果应另行核实。合并本次 milestone 标记变更会触发一次完整合成回归，不授予生产发布许可。
