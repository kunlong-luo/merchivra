# V117 — V48/V49 Orval 与 ASN 通知/审计全库分页

2026-11-09，A1 Issue #62；基线 V116 main `b4a2ff3`。目标是**使用现有 Orval/React/Java 组件**，不是重写已验证业务服务。

## 实际实现

- `apps/web/openapi/purchasing-v114.json` 版本升至 **117.0.0**；累计 **71 个唯一 operationId**（V116 为 58）。新增 V48 分段质检与汇总、已放行库存位置、原库存不重复增加的上架、FIFO/FEFO 预览/实物拣货，V49 库位移位、跨仓调拨、过期隔离、非过账盘点观察、审计历史/来源链/过期提醒，以及 2 个 ASN 服务器全库分页 API。
- `apps/web/src/lib/released-lots.ts` 与 `lot-lifecycle.ts` 原业务 API 函数对外签名不变，改成 Orval v8 生成请求 + 既有 Zod 运行时解析。POST 在 `merchivraFetch` 统一 Session Cookie、CSRF、no-store 下执行；动态路径先校验 UUID。
- Java 服务不修改 V48/V49 审批、可用余额、批次余量、签名流水、幂等键、租户及库存事务。拣货 `expectedOnHand` 仍要求精确余额；`COUNT_OBSERVATION` 不过账、`EXPIRED_QUARANTINE` 与仓间转移仍是显式人类操作，不是自动写入 Amazon。

### ASN 不再只显示前 200 条

保留旧 `GET /supplier-asns/orders/{orderId}` 和旧 `events` 的前 200 条接口给旧消费者兼容；**新主页面不再调用它们**。

- 新增 `GET /api/v1/purchasing/supplier-asns/orders/{orderId}/page?limit=50&cursor=...`，和 `GET /orders/{orderId}/{asnId}/events/page`。两者由 Session tenant 控制，`(created_at,id) DESC` 稳定 keyset；`limit+1` 探测 `hasMore`，每页 1..100，默认 50。
- ASN 游标绑定版本、tenant、PO ID、时间与 UUID；审计事件游标额外绑定 ASN ID，旧租户、错采购单、错 ASN/坏 cursor 统一 400。审计事件读取前再检查 `tenant_id,purchase_order_id,id` 三者归属，不可跨单读取。
- `SupplierAsnPanel.tsx` 通过 TanStack Query 按 PO+cursor 服务器检索，复用 `CursorPageControls` 的上一页/下一页。订单切换时重置通知页和审计页；打开另一条 ASN 时重置审计 cursor，列表翻页取消已展开事件，防止旧详情误关联当前页。ASN 列表仍以第 201 项后的真实 `receivedQuantity` 显示供应商申报和本地实收，不能将 `SHIPPED` 当作承运商验证。
- `supplier-asns.ts` 新增 `fetchSupplierAsnsPage` / `fetchSupplierAsnEventsPage`；返回 Zod 校验 `items,nextCursor,hasMore,pageSize`，拒绝旧截断数组或逻辑不一致的 `hasMore/nextCursor`。

## 验证

- Java `SupplierAsnKeysetPageV117Test` 使用模拟 225 条 ASN 和 225 条不可改事件，验证两组 `50+50+50+50+25`、第 201 项、跨 PO/租户/ASN 的游标拒绝、无效游标/超限以及父 ASN 真实归属。
- Vitest `released-lots-asn-v117.test.ts` 对新 Orval 访问验证 225 条完整跨页、不可改事件链、CSRF + requestKey + 真实 expectedOnHand、显式盘点观察不产生活动过账 ID、409 拒绝及坏页面返回 fail-closed。
- 先进行 PR 快速 Gradle/React/Vitest/Vite/Next CI；**不把未经集中运行的 PostgreSQL 18 HTTP 测试宣称为通过**。A3 的完整 PG18 Flyway+权限+并发+仓库余额回归仍未执行；生产 Amazon OAuth/SP-API 仍关闭。

## 后续

[Issue #62](https://github.com/kunlong-luo/merchivra/issues/62) 继续追踪供应商采购计划、报价、财务/索赔、批次证据、历史固定数上限和其他关联子域的完整 Orval 及 A3 验收；[Issue #63](https://github.com/kunlong-luo/merchivra/issues/63) 跟进 Next.js 正式运行，不是 GitHub Pages 只读演示。
