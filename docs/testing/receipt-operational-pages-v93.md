# V93 — 采购收货批次操作与仓库压力完整分页（隔离 Mock）

## 实际遗漏及修复

V92 已使收货分段页面可按订单检索超过 300 条收货批次，但 V46 真实**批次审批与放行工作台** `ReceiptBatchPanel` 仍读取旧 `LIMIT 300`，使第 301 条以后的待放行记录无法编辑或审批。该工作台还把截断列表中的待检件数显示为整单数量，造成错误的业务合计。质检页面同样限制 300 条实际收货、会导致第 301 条以后的收货不可检验；审计页面仅显示前 200 条事件，V47 压力视图仅显示前 300 个 SKU。

V93 **复用 V92 的 `LocalKeysetPage`** 和成熟 `JdbcTemplate`、`React` / `TanStack Query` / `Zod`，在后端提供：

- `GET /api/v1/purchasing/receipt-batches/orders/{orderId}/page?limit=50&cursor=…`（V92 已有，现在供批次放行工作台使用）
- `GET /api/v1/purchasing/quality/orders/{orderId}/receipts/page?limit=50&cursor=…`：质检工作台可遍历**全部实际收货**，不再因旧 300 条上限漏掉后续实收及质检按钮。
- `GET /api/v1/purchasing/receipt-batches/orders/{orderId}/summary`：数据库按**全部批次**汇总待检实收件数、待放行合格件数和对应批次数；不将页面部分记录冒充整单合计。
- `GET /api/v1/purchasing/receipt-batches/warehouses/{warehouseId}/page?limit=50&cursor=…`：可遍历整个仓库的收货批次。
- `GET /api/v1/purchasing/receipt-batches/{receiptId}/events/page?limit=50&cursor=…`：稳定 `(created_at DESC,id DESC)` 分页浏览不可覆盖事件。
- `GET /api/v1/purchasing/lot-locations/warehouses/{warehouseId}/pressure/page?limit=50&cursor=…`：稳定 `(internal_sku,product_sku_id)` 分页浏览所有**有本地收货批次**的 SKU，非全仓所有 SKU 的库存总账。

以上查询严格从登录会话解析租户；cursor 绑定订单、收货批次或仓库；页长 1–100 校验；响应 `Cache-Control: no-store`；按 `limit+1` 查询，返回 `items/nextCursor/hasMore/pageSize`。原有数组型有限列表 API 暂留用于兼容，不再供 V46/V47 当前操作面板使用。分页为实时游标浏览，**不是不可变业务快照**，不能用于审计数据冻结或库存对账。

## 前端操作闭环

- `PurchaseQualityPanel` 的实际收货质检使用独立收货页；改页时清除编辑和退货选择，且不再用被截断的批次数组判断 V46/V44 质检消息。即使第 301 条以后收货也能依法记录一次性质检，合格品须另由不同 Owner 放行。
- `ReceiptBatchPanel` 的批次改标、独立 Owner 放行和审计入口全部基于当前页，页间切换时取消已选择的收货批次和展开的审计，避免误操作旧记录；整单统计从独立汇总 API 读取。
- 审计历史使用自己的游标栈，支持上一页/下一页，并在切换批次时重置。
- `ReceiptLotLocationPanel` 的压力表不再声明“最多 300 个 SKU”，支持翻页浏览；待检、已检待放行和申报 ASN 在途与可用库存始终区分。

## PostgreSQL 18 / Mock 验收

CI 在现有 V92 disposable localhost PG18 Fixture 后运行 `scripts/smoke_receipt_operational_pages_v93.py`：

1. 本地模拟增加 315 个 SKU 的真实收货批次，并验证订单和仓库各 **635 条收货批次、13 页**，第 301 条后仍可访问。
2. 检查整单待检 **860 件 / 635 批次**，不是 50 条当前页的数量。
3. 压力分页覆盖 **316 SKU、7 页**，校验按 SKU 不重不漏、held/ASN 和 usable 分离。
4. 质检工作台共 **760 条真实收货、16 页**；在第 301 条以后的 V46 收货上执行幂等的一次性质检，并验证待检批次转为 `INSPECTED`、整单统计更新而库存流水保持不变。
5. 同一批次追加 **225 条**不可覆盖模拟历史事件，验证审计超过旧 200 条上限并保持稳定排序。
6. 验证跨订单、跨仓库、跨批次游标和无效 limit 被拒绝，未登录不允许访问；GET 全链路不新增库存流水或采购单。

整个环境强制 `DB_HOST=127.0.0.1`、`MERCHIVRA_SPAPI_ENABLED=false`、`MERCHIVRA_SPAPI_OAUTH_ENABLED=false`、`MERCHIVRA_SPAPI_ALLOW_WRITES=false`，不调用真实 Amazon SP-API、授权或写入。

## 边界与后续

所有模拟数量只用于本地测试。待检实收不等于实际可售库存，已检合格数在独立 Owner 放行前不可用；供应商 ASN 在途只是声明值。SKU 压力视图只涵盖已出现本地收货批次的 SKU，并不是全量补货建议或库存总账。后续可逐模块检查类似旧固定行数、审计游标冻结需求以及将其他只读列表迁至新分页约定。
