# V119 — 本地采购交付全库真值汇总与完整 keyset 分页

2026-11-09。A1 / [Issue #62](https://github.com/kunlong-luo/merchivra/issues/62)。原 V72 交付概览只读取 `LIMIT 300`，并将这 300 条求和；V118 虽标记了局部范围，但仍不能满足全租户真值要求。本轮修复的是**数据完整性**，不是增加第二套业务状态机。

## 服务端事实和安全约束

`PurchaseDeliveryService` 把 V72 实际采购行查询抽取为统一 `DELIVERY_SQL`，供三项能力复用：

- **全库汇总**：新增 `GET /api/v1/purchasing/delivery/summary`。PostgreSQL 对当前 Session 租户内所有状态为 `APPROVED/RECEIVING/RECEIVED` 的采购行做 `COUNT/SUM/CASE` 数据库聚合；不包含 `LIMIT 300`，也不靠 React 当前页求和。返回 `asOfDateUtc` 与 `Totals`，保留原来的 `DISCREPANCY_REVIEW` 优先级、QC 未知、手工 ASN 申报和 ETA 逾期判定。
- **完整明细**：新增 `GET /api/v1/purchasing/delivery/lines/page?limit=50&cursor=...`，每页 1–100 条，默认为 50，按 `(o.updated_at,o.id,l.id) DESC` 进行稳定 keyset 查询，`limit+1` 判定是否下一页。游标绑定 `delivery-v119` 版本、Session 租户、订单更新时间及订单/明细 UUID；跨租户、过期格式与非法游标 400，不读取其他租户。业务行保留 ASN/QC/差异的原本语义，不伪造承运商证明或 FBA 入仓。
- **兼容接口**：旧 `GET /api/v1/purchasing/delivery/overview` 保持最多 300 行和基于所返回行求和的行为，供既有集成兼容；新的正式采购交付工作台不再使用它。不能把旧响应 `Totals` 当作全库数据。

所有 GET 均由 `TenantAccessService` 获取身份/租户，新增端点响应 `Cache-Control: no-store`。未新增数据库表或修改 QC/ASN/库存事务锁、人工审批、金额精度、Amazon/OAuth/SP-API 写权限。Java 用一份业务事实查询避免明细和汇总关联条件漂移；聚合 CASE 与 Java 分类优先级对齐。

## 前端

`apps/web/openapi/purchasing-v114.json` 版本升至 **119.0.0**，累计 **102 个唯一 operationId**，新增全库汇总与行 keyset 两个读操作。仍使用原有 Orval 生成器、`merchivraFetch`、React Query、Zod；数据格式不完整、非法页游标、HTTP 403/500 均 fail-closed。

`PurchaseDeliveryPanel` 读取独立的全库汇总和服务端逐页采购行：UI 显示全租户 `lineCount` 与各指标，表格每页最多 50 行；复用 `CursorPageControls`（上一页/下一页），切换租户或刷新时重置页游标，并刷新两组 Query 缓存。页面注明全库汇总和当前页**不是同一数据库事务快照**，如果正在收货/审核，需刷新再核对。

## 专项验证与剩余门槛

- Java `PurchaseDeliveryFullScopeV119Test` 验证模拟 325 条连续 7 页（50×6+25）包含第 301 条，tenant+订单时间/UUID 游标绑定与畸形游标拒绝，SQL 不含 300 条窗口且数据库聚合覆盖所有行的选择条件。
- Vitest `purchase-delivery-v119.test.ts` 验证 325 条完整分页、全库 325 条摘要与 50 条展示分离、同源 Session/no-store、分页 Zod 一致性、401/403/500 fail-closed。
- 保留旧 `PurchaseDeliveryServiceTest` 和 `purchase-delivery.test.ts` 的原始业务不变量和兼容行为。
- **尚未执行 PostgreSQL 18 实际 HTTP/Flyway/并发及浏览器真数据端到端**：Java Mockito 与 Vitest 不能取代 A3 `full_suite`。全库聚合 SQL 在大租户下的索引/执行计划与读取性能也待 A3 检查；不得凭代码编译通过就宣称能支撑生产规模。
- [Issue #63](https://github.com/kunlong-luo/merchivra/issues/63) 的 Next.js 生产 runtime、Session 同源反代、HTTPS、发布与回滚仍单独推进。真实 Amazon Seller 授权及写入全部保持关闭，由用户在本地最终联调。
