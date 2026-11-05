# V92 延伸：本地采购与批次操作入口的完整翻页

基线：V92 本地 FBA 货件历史已具有 `/shipments/page`，本次不重复实现。此 PR 针对仍影响真实操作的列表局部加载，全部属于本地数据库；不运行 Amazon 授权、SP-API、出站下单或真实货件写入。

## 已发现的业务风险与修正

| 原列表 | 原上限 | 本次只读分页 | React 操作入口 |
| --- | ---: | --- | --- |
| 供应商 SKU 采购规则 | 500 | `GET /api/v1/purchasing/planning/supplier-rules/page?limit=50&cursor=...` | 可以继续翻页编辑/移除旧规则；编辑仍需手动在全库 SKU 选择器选中相同 UUID |
| 仓库补货采购建议 | 500 | `GET /api/v1/purchasing/planning/warehouses/{id}/recommendations/page` | 能找到第 501+ 项，仍由服务端核验库存、MOQ、规则时间戳与幂等键 |
| 已创建本地采购计划 | 100 | `GET /api/v1/purchasing/planning/warehouses/{id}/plans/page` | 旧 PROPOSED 仍能人工转换/取消；不再依据仅有的本页计划误判“没有重复计划” |
| 本地仓库库位 | 200 | `GET /api/v1/purchasing/lot-locations/warehouses/{id}/bins/page` | QC_HOLD 目标库位可以跨页选取 |
| 单笔收货的批次分段 | 200 | `GET /api/v1/purchasing/lot-locations/receipts/{id}/pieces/page` | 旧分段可以跨页拆分/移动，数据库 `SUM(quantity)` 提供完整批次数量，不能用当页件数作合计 |
| 采购单的收货批次 | 300 | `GET /api/v1/purchasing/receipt-batches/orders/{id}/page` | 第 301+ 个收货批次可进入分段与库位操作 |

分页每页默认 50、合法 1–100，查询都从当前 Session 获取租户，单独按仓库、订单或收货批次过滤，服务端拒绝不匹配的游标，响应设置 `Cache-Control: no-store`。Keyset 顺序：规则按 `(supplier_id,sku_id)`；建议按 `(internal_sku,supplier_id)`；计划按 `(created_at,id)` 倒序；库位按 `bin_code`；分段按 `piece_code`；收货批次按 `(created_at,receipt_id)` 倒序。旧版有限条数 API 保留向后兼容，分页不改变业务状态机、采购授权、审计和库存账本语义。

页游标仅为翻页指针，**不是审计冻结快照或授权令牌**；每次请求仍校验 Session 和作用域。并发增删可能让前后页非同一时刻快照；审批/拆分/移库操作只能使用最新服务端校验。前端在切换页/单据/仓库时清除旧选择，提交前继续由领域层验证待检、币种、SKU、幂等键、审计和库存边界。分段完整数量聚合仅表示本地分段计数，不可推断 FBA 可售库存。

## PostgreSQL 18 完整 Mock 验收

`scripts/smoke_paged_local_purchasing_v92.py` 接在原 V92 端到端测试之后，使用一次性 localhost PG18，显式要求 `MERCHIVRA_DEMO_ENABLED=true` 且 Seller OAuth / SP-API / Amazon 写入关闭。它从现有 Session/CSRF 登录，数据库只插入虚构商品、规则、仓库、采购单/收货台账、批次与库位：

- 512 条新供应商规则（超出旧 500）、512 条该仓库的补货建议；全页遍历验证无遗漏和跨仓游标拒绝。
- 125 个虚构 PROPOSED 计划（超出旧 100）、215 个 QC_HOLD 库位（超出旧 200）。
- 320 个本地收货批次（超出旧 300）；某一批次 225 个待检分段（超出旧 200），整体 226 件。选择末页分段执行人工 Mock 拆分后仍为 226 件。
- 验证翻页限值/无效游标/匿名权限，旧接口兼容；真正执行分页与分段操作后采购单数和本地库存流水条数不增加。

运行记录应分别标注「代码提交」「Java/React CI」「PG18 HTTP Mock 通过」「真实 Amazon 授权实测」，不得混称。测试脚本只供可销毁 CI 数据库，**不可运行于生产或真实 Seller 数据库**。

## 尚未覆盖

`ReceiptLotLocationService.pressure` 每仓最多 300 个 SKU，只是局部供应压力浏览，UI 已明确说明，不得当作总账或全仓完整库存。旧 `forWarehouse` 收货批次 300 条、审计事件 200 条等历史只读入口，仍需后续单独审计。FBA SP-API 生产授权、真实 Amazon/财务数据与写入继续关闭。
