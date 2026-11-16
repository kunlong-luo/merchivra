# V172 · 本地仓消费者退货包裹核对与来源唯一性（不可直接冲回 COGS）

## 为什么需要

V171 的 V96 手工退货核查单只表达「商品应当/据称退回」及独立 Owner 对声明的复核。它尚不对应可追溯的**仓库、每个到仓包裹、该包裹的数量、签收参考号**。V39/V44 是采购收货，不能用作消费者退货的事实或将 V171 `RESTOCKED_CLAIMED` 变成销售库存。

V172 新增**独立的本地消费者退货包裹候选流水**，按退货申报关联订单商品与本地仓库，分包裹控制总件数和来源唯一性，同时保留第二人核查和审核事件。此流水是尚未经过 WMS 外部来源认证的**本地双人记录**，仍不是自动可售入库、正式库存凭证或真实 COGS 回转。

## PostgreSQL 18 / Flyway V97

- `finance_customer_return_intake_v97`：不可混用的 `tenant_id, marketplace, Finances event, LIVE order, V34 item, V96 claim, local warehouse` 关联；复合 FK 强制 V96 claim 实属**同一退款、订单及商品**。独立 `receipt_reference` 8–160 位白名单；`(tenant,warehouse,receipt_reference)` 唯一（**驳回的包裹号也永久保留**，防止来源引用重用）。
- 数量限定 1–1,000,000；每次创建在同一 PG18 `REPEATABLE_READ` 事务按 **LIVE 订单 → LIVE Finances 事件 → V96 复核核查单** 锁行序列化。当前 `PENDING + ATTESTED_LOCAL` 的全部包裹数量**跨所有 V96 版次**累计，不得超过当前审核的 V171 申报件数；单个包裹可被驳回，但数据和审计仍永久可追踪。不是把本页 50 条当全库。
- `PENDING` 只能一次进入 `ATTESTED_LOCAL` 或 `REJECTED`。审核人不得是该包裹申请者、V96 核查单编制人/决定人或该项 V34/V35 来源编制人。数据库触发器禁止改写原始收件数量、仓库/参考号、历史证据指纹、任何已决策数据或删除任何原始记录及审计。
- 强制 `origin='LOCAL_TWO_PERSON_UNVERIFIED'`、`external_wms_verified=false`、`inventory_posted=false`、`cogs_posted=false`。退货商品人工「可售」声明不自动修改可售库存、批次、仓库明细、V64 冻结利润或 V162 真实全成本。
- 源指纹哈希基于 V171 核查单、退款及订单引用、商品、当时已审数量、人工状态、V63 审核版本、源证据引用及审核人。读取每次动态核对 V171/V63；来源过期显示 `STALE`，阻止后续确认。支持有限范围分页 `(requested_at DESC,id DESC)` 每次 50 条；**全部来源**跨页聚合、按退款商品计算待核对/已本地核对/剩余件数。原币利润不可由这两个件数衍生。

## Next.js / API

Owner-only 路由，后端 Session 派生租户，写请求 Spring CSRF，返回 `Cache-Control: no-store`：

- `GET /api/v1/finance/marketplaces/{marketplaceId}/events/{eventId}/return-intakes[?beforeAt=ISO-8601&beforeId=UUID]`：全来源统计、50 条明细 keyset 与缺项/证据新鲜度。`externalWarehouseReconciled=false`、`realizedNetProfitVerified=false`。
- `POST .../return-intakes`：`{claimId,warehouseId,receiptReference,quantity,condition,note}`。不存在或跨租户/跨事件的核查单 404，不是 `REVIEWED_MANUAL` 或证据过期 409，缺少输入 400；同一仓库包裹参考号重复 409。
- `POST .../return-intakes/{id}/attest|reject`：一次独立 Owner 核对，旧证据/数量变更 409，不满足独立性 403。状态 `ATTESTED_LOCAL` **仍不代表正式实物认证**。
- `GET .../return-intakes/{id}/audit`：追加式审计读取。
- React19 `LocalReturnIntakePanel` 内嵌于 V171 退款核查页面，**直接复用 `WarehousePicker`（原项目已经具备全租户服务器分页与精确仓库验证）**，展示审核的原件数、待核对数量、本地双人核对数量、剩余数量及参考号/审计。Next.js16 与 Vite 回退复用；Zod 前端严格校验所有已核验/利润旗标为 false。

## 验证与未来真实集成

新增 JUnit：输入范围、证据过期、Owner/CSRF 权限映射、PG18 FK/唯一性/触发器及无库存/COGS 过账静态合同；扩展独立 PG18 + Spring HTTP 验证 401/400/404、不完整 V171 不能建入仓包裹、来源未认证标记 false、数据库约束存在及未更改利润账本。

**本阶段不等于外部 WMS/FBA/FBM 真实实收。** 后续真正的「可信」认证还需要：真实 WMS 或 Amazon 授权、源事件不可篡改摘要/签名、包裹和批次层面的可售/不可售 QC、物理收货与退款/原始成本的独立对账、历史汇率/费用去重、G1 会计规则及冻结报表重算。没有这条外部来源链之前，绝不把本地人工件数或 V171 申报件数作为真正 COGS 转回。

真实 Seller/Ads OAuth OFF、远程写入 OFF，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) NO-GO。
