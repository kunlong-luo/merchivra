# V147 · FBA 本地 PO/ASN 来源历史与候选完整游标分页（开发中）

> **状态：V147 PR [#110](https://github.com/kunlong-luo/merchivra/pull/110)，分支完整隔离 PG18/HTTP + Java/React/Next/Compose 验证已成功；合并后的精确 main-SHA A3 和生产批准仍需独立确认。** 本文不构成正式生产发布、真实 Amazon 关联、财务核销或 G1/G2/G3 的批准。当前 [#108](https://github.com/kunlong-luo/merchivra/issues/108) 仍开放；V144 关闭的采购/供应商/仓库分页 [#62](https://github.com/kunlong-luo/merchivra/issues/62) 不重复建设。

## 为什么要改

V145 正式接入 15 项 Springdoc/Orval FBA 读写接口，但已存在的 `GET /purchase-links` 仍 `LIMIT 200`，`GET /lines/{lineId}/candidates` 仍 `LIMIT 100`。旧 UI 只会从单次响应读到最多这些数量，历史已撤销证据和大租户批准采购/ASN 候选可能被遗漏。**旧数组接口仍保留兼容，但不能作为全量来源或容量判定依据。**

## 新增只读接口

所有路径均以 `/api/v1/fba/marketplaces/{marketplaceId}/shipments/{shipmentId}/purchase-links` 为前缀：

| 路径 | 实际来源 | 查询与安全 |
| --- | --- | --- |
| `GET /page` | 全部本地已建立的关联历史，包括 REVOKED | `limit=1..100`（默认 50）、`state=ALL/PROPOSED/CONFIRMED/REVOKED`、可选 `inboundLineId`、`cursor` |
| `GET /lines/{lineId}/candidates/page` | 当前 Seller SKU 有 ACTIVE 映射的已批准 PO 行与可选 SHIPPED ASN | `limit=1..100`（默认 50）、可选 `purchaseLineId`、`cursor`，不推断 ASN 身份 |

响应均为 `{items,nextCursor,hasMore,pageSize}`；下一页仅由 `limit+1` 标识，空尾页 `nextCursor=null`。错误页大小/筛选/游标应 HTTP 400；其他租户或货件应在服务端严格按当前认证身份重查，绝不接受浏览器传入的 `tenantId`。查询为**实时本地观察**，不是跨页原子快照；并发修改 PO 更新时间可能使用户需要刷新/重查，不宣称会计审计快照一致性。

链接以 `created_at DESC, id DESC` 稳定翻页。候选以 `po.updated_at DESC,po.id DESC,pl.id DESC,COALESCE(asn.id,00000000-0000-0000-0000-000000000000) DESC` 排序，缺失 ASN 的来源明确在该采购行末尾；`EXISTS` 查询避免重复 ACTIVE Listing JOIN 将同一真实来源重复展示。游标包含当前认证 tenant、marketplace、shipment、inbound line（如果适用）、state 或购买行过滤器以及完整排序位置。跨范围/格式损坏即拒绝，仍由 SQL 在查询时执行租户和来源条件；游标不承载授权或业务批准。

React 入口对关联历史、候选采购行及所选 PO 行的 ASN 单独分页，上一页/下一页依赖完整游标。切换货件或行时重置选择和当前页；Zod 校验唯一有效响应形式：页大小、项数、枚举状态、nullable ASN、`hasMore` 和 `nextCursor` 一致。会话隔离继续使用原有 TanStack Query Session 缓存清除。采用 Springdoc → 归一化 OpenAPI → Orval v8 → Zod，不新增第二套 HTTP transport，全部走现有 no-store + CSRF / Session。

## 不更改的业务边界

原 V75 的 `propose/confirm/revoke` 及独立 Owner 双人复核、原本的事务锁、幂等请求键、预计数量不超 PO/ASN、审计不可改、货件发运禁止修改均不受影响。**本地 CONFIRMED 不是 Amazon Verified**；不自动从采购实收扣除、不增加 FBA available、不提交 Amazon 远程写入、不自动采购。

新增 Flyway V90 只增加三个索引，不覆盖历史迁移：租户+货件完整历史、已批准 PO 最新排序、已发运 ASN 采购行查找。与现有 PostgreSQL 18 迁移、租户约束及兼容旧数组接口并存。

## 需留证据的验收

- Java 25 / Gradle：非法 limit、状态、损坏游标、跨 tenant/marketplace/shipment/line/筛选游标 400；Springdoc 确认两条新操作的 UUID 路径及请求参数。
- React / Vitest：模拟 225 条关联历史与 125 条候选完整跨页；错误 `hasMore` / `nextCursor`、无效 UUID、页面容量溢出被拒绝。
- 完整 A3 `full_suite`：仅在 `127.0.0.1` 与一次性 PostgreSQL 18/Flyway 上合成数据，真 SQL 注入 225 个 REVOKED 历史 + 1 个原 CONFIRMED，HTTP 应为五页 226；同一显式映射 Seller SKU 注入 125 个额外批准 PO 行，联同原 SHIPPED ASN 形成 126 候选、三页；校验错筛选/货件/行游标、无效路径、ASN NULL 及无库存写。不可将此测试用于生产数据。
- 版本合并后再次对**精确主线 SHA**执行完整 A3；生产目标 TLS/IAM/PITR/Alertmanager、真实 Seller/Ads 由 #63/#104 和维护者本地验收独立处理。

**分支实测证据（2026-11-13）**：[完整 full_suite #43354930355](https://github.com/kunlong-luo/merchivra/actions/runs/43354930355) 的 4 项工作均成功，包含真实 PostgreSQL 18/Flyway V90、226 条来源历史（5 页）、126 条候选（3 页）、非法筛选/跨货件游标 HTTP 400、明确的可空 ASN、无 FBA 库存写入，以及 Java 25 / React/Vite/Next / Compose。V142 独立 PITR 曾在容器初始化暂态 SELECT 时出现关闭竞态，已要求数据库真实 post-init 就绪后再继续，最终同次 full_suite 通过。此完整验证仅属于 synthetic disposable 工作流，**不等于生产 PostgreSQL PITR、真实 Amazon 或外部 Alertmanager 验收**。

**合并与关闭条件**：先清理临时 CI workflow、确认常规 PR CI 正常，再合并至 main 并以该**精确 main commit**重新运行完整 A3。成功前保留 #108 的技术验收 OPEN，不自动放行 Next 生产切流 #63、G1/G2/G3 #104 或用户本地 Seller/Ads OAuth。
