# V96：供应商采购差异、索赔审批、可追溯来源与审计分页

## 操作缺口

V95 完成运营异常及来源明确的库存纠正审批后，V45 供应商争议工作台还有三个直接业务漏洞与一个派生误判：

1. `SupplierDisputeService.variances()` 按采购单 `LIMIT 200`，第 201 条之后的人工差异无法从界面执行另一位 Owner 复核、关闭或查看审计。
2. `SupplierDisputeService.claims()` 按采购单 `LIMIT 200`，后续草稿不能提交，已提交单不能交给另一位 Owner 审批，已批单不能填写内部关闭说明。
3. `SupplierDisputeService.events()` 单案 `LIMIT 100`，不能阅读早期的不可覆盖审计记录。
4. 原来创建索赔来源使用 `variances` 的前 200 条、`claims` 的前 200 条和旧质检收货列表。前端按这些不完整数据排除已有索赔，既遗漏有效来源，也可能错误展示已经被旧记录占用的来源；**V45 数据库永久唯一索引**早已禁止同一来源产生第二份索赔，即使历史索赔已取消，前端也必须符合这一规则。

## 复用与实施

无需新建数据库表、队列、缓存或者额外分页库。复用 Java 25 / Spring Boot 4 / Gradle、PostgreSQL 18、现有 `LocalKeysetPage`、Spring JDBC、React 19 / TypeScript / Zod / TanStack Query / `CursorPageControls`。保留旧 `LIMIT 200/100` 数组 API 兼容其他消费方，但人类操作面板不再读取这些旧接口。

- `GET /api/v1/purchasing/disputes/orders/{orderId}/variances/page?filter=ACTIONABLE|ALL&limit=50&cursor=...`。ACTIONABLE 筛选 OPEN/REVIEWED；ALL 包含 CLOSED。按照 `created_at DESC,id DESC` 稳定游标。
- `GET /api/v1/purchasing/disputes/orders/{orderId}/claims/page?filter=ACTIONABLE|ALL&limit=50&cursor=...`。ACTIONABLE 筛选 DRAFT/SUBMITTED/APPROVED；ALL 包含 CANCELLED/CLOSED。
- `GET /api/v1/purchasing/disputes/orders/{orderId}/claim-sources/page?limit=50&cursor=...`。SQL 全量合并**已复核采购差异**与**质检确认不合格来源**；对所有状态的供应商索赔执行 `NOT EXISTS`，因此不依赖当前待办页、不将历史已取消的来源再次列为可索赔。依 `(created_at DESC,source_id DESC)` 游标分页，包含最大可申报件数。
- `GET /api/v1/purchasing/disputes/variances/{id}/events/page` 和 `/claims/{id}/events/page`。加载前必须验证该源属于当前 Session 租户；历史按 `(created_at DESC,id DESC)` 游标分页，无法跨另一案件沿用游标。
- 页长 1–100，默认 50。游标在服务端绑定租户、采购单、状态筛选或具体案件类型及 ID；无效游标/筛选/页长返回 400，所有分页读取均 `Cache-Control: no-store`。
- 客户端切换采购单、筛选或审计目标时清空对应页栈；操作成功后回第一页。选择某页的可索赔来源后翻页，会清空所选来源，避免错把旧页面对象当成当前来源。当前页的记录既不是冻结总账，也不能用于检查全表唯一性。
- `report()` 原有 V45 `UNIQUE NULLS NOT DISTINCT` 规则前增加全库来源冲突校验以得到正确的 HTTP 409；`createClaim()` 在 V45 单来源唯一索引之外增加全库重复申请显式冲突校验。实际审批的双人规则、来源接收数量快照、幂等键与不可覆盖审计仍由既有事务负责。

**边界：**本地审核状态 `APPROVED` 不等于供应商接受、物流签收或退款到账；所有真实 Amazon OAuth/SP-API 生产接入及远端写入保持关闭。现有 V45 供应商争议 API 采用 Zod 的同源安全客户端；不会为了分页重写现有领域服务或添加新技术栈。

## PostgreSQL 18 完整 Mock

`scripts/smoke_supplier_case_pages_v96.py` 接在 V95 之后，利用 V92/V93 本地采购单行，运行于一次性 PG18 容器和真实 Session/CSRF HTTP 服务：

1. 生成 535 条**独立采购单行**的本地数量差异（240 OPEN、295 REVIEWED），验证旧 API 只有 200，分页可遍历。第 201 条之后同一 Owner 复核被拒绝；已复核案件关闭成功并留下 `VARIANCE_CLOSED` 审计；已存在的源再次报告得到 409。
2. 针对 235 条已复核差异生成 235 条本地索赔（215 DRAFT、10 SUBMITTED、10 CANCELLED），验证超过旧 200 条的草稿真实内部提交成功、同一 Owner 审批被拒绝、原始操作产生审计。
3. 借助全表来源查询，验证其他 59+ 条已复核但尚未被索赔的差异仍能找到、已存在索赔和已关闭差异被排除，并在后续页的实际来源创建草稿及幂等重放；对已有索赔的来源再次创建返回 409。
4. 单案额外生成 135 条带「V96 synthetic」说明的不可覆盖审计元数据，验证旧接口最多 100、分页可完整读取 136 条（包括真实内部提交），同一源历史无重复。
5. 验证匿名访问 401、错误订单/筛选/游标/页长拒绝，最终校验**库存流水和采购单数量不变**。不会自动对外发货、联系供应商、向 Amazon 写入或变更财务总账。

历史静态测试行是**一次性 Mock 压力数据**，不是由真实仓库或供应商出具的证明，也不是可直接用于财务对账的实时生产记录。后续阶段可再处理供应商绩效供应商清单 200 条，以及财务跨模块人工审核历史的独立分页。
