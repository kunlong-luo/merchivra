# V115 — 采购主流程写入 OpenAPI/Orval、仓库历史标签与批次库存 UNKNOWN

2026-11-08。衔接 [V114 仓库选择器与采购只读契约](./warehouse-directory-orval-v114.md)；本批改动仅涉及**本地采购/库存**，没有 Amazon LIVE 授权、同步或远程写入。

## 1. 已纳入可生成合同的核心写入接口

`apps/web/openapi/purchasing-v114.json` 版本提升为 `115.1.0`；文件名不更改，以便重用 V114 既有 Orval 生成入口。已有 8 个分页/精确读取接口继续不变，本批增加 10 个核心能力操作：

| HTTP 合同 | 业务作用 / 安全边界 |
| --- | --- |
| `POST /api/v1/purchasing/suppliers` | 新增租户供应商；编号去重、ACTIVE 校验由服务器执行 |
| `POST /api/v1/purchasing/warehouses` | 新增本地仓库及 QC-HOLD 默认库位 |
| `GET /api/v1/purchasing/orders/{id}` | 单据与全部明细的精确读取 |
| `POST /api/v1/purchasing/orders` | 新建 DRAFT；原币单价保持 **decimal string**，不使用浮点金额 |
| `POST /api/v1/purchasing/orders/{id}/submit` | 提交审批，不允许 GET 改状态 |
| `POST /api/v1/purchasing/orders/{id}/approve` | 独立 Owner/状态审批规则仍在后端验证 |
| `POST /api/v1/purchasing/orders/{id}/cancel` | 原有状态/历史约束保留 |
| `POST /api/v1/purchasing/orders/{id}/receive` | 对指定仓库的真实收货入待检，并可带人工 ASN ID；不是 FBA 可售 |
| `POST /api/v1/purchasing/warehouse-operations` | COUNT/OUTBOUND/TRANSFER，保留 requestKey、签名库存、expectedOnHand、来源和仓库事务锁 |
| `GET /api/v1/purchasing/warehouses/resolve?ids=...` | 最多 100 个仓库 ID 的**批量只读标签**；Session tenant 限制、返回当前租户内找到的 ACTIVE/ARCHIVED 仓库，缺失不伪造 |

这些操作只扩展既有服务端合同，不修改原 Java 状态机，不移除 Spring Security Session/CSRF/Owner RBAC，不改变事务锁、库存负数/已上架约束或真实收货证据。原 Orval 生成器 `apps/web/orval.purchasing.config.ts`、共享 `merchivraFetch`（同源 Cookie、CSRF、no-store）与 Zod runtime parse 继续复用；生成物按 OpenAPI hash 在 `npm run test/typecheck/build`、Next 类型检查前可复现生成，不手改产物。

**范围说明：** 此次覆盖采购主流程写入，但供应商 ASN、采购规则与报价、QC/退货、lot 证据、库位动作等独立控制器的完整 OpenAPI/Orval 迁移**尚未完成**，不能宣称整个采购域接口全部转换。

## 2. 历史仓库标签的真值

V114 旧 `GET /warehouses` 兼容 API 返回最多 200 个仓库，导致 ASN、V46 批次、QC、V49 来源链的只读表格对第 201 个仓库显示 UUID。本次复用现有 `Warehouse` DTO，新增 `resolveWarehouses(tenant,ids)`：

- 批量上限 100；全部为 UUID JDBC 占位参数，where `tenant_id=?`；拒绝空、超过上限、非法 UUID；未知/外国租户 ID **不被返回**。
- 前端 `useWarehouseLabelMap` 缺失时以已加载的兼容仓库资料为第一优先，其次一组 TanStack Query 缓存的批量 ID 查询，不因同一页数十条记录触发数十次 HTTP。
- `SupplierAsnPanel`、`ReceiptBatchPanel`、`PurchaseQualityPanel` 和 `LotLifecyclePanel` 在当前页分别查询历史仓库真实名称。服务器未返回的仓库仍显示原始 ID，不猜名称或自动修改仓库字段。
- 支持归档仓库历史读取标签，但归档仓库仍不允许被 ACTIVE-only 的收货/出库选择器用于写入。

## 3. 补上的批次库存安全边界

V113 已在人工库存操作中修复“不在旧 `LIMIT 500` 的 SKU 就当作 0”。V115 审核发现 V48 FIFO/FEFO 拣货、V49 批次调拨/过期隔离仍通过旧 `fetchWarehouseStock` 数组读 `??0`。

- V48 改用成熟的 `InternalSkuPicker` **全租户分页 SKU 选择器**，配合 `fetchWarehouseStockItem(warehouseId, skuId)` 精确观测。读取失败/未知库存禁止写入；审计 payload 使用真实服务端余额。
- V49 批次来源仓库/SKU 改用同一精确余额查询，只有在已知余额内可进行需要减少库存的调拨或过期隔离；缺失不允许隐式转换为 0。
- 已有 Java 后端库存事务、批次 FIFO/FEFO 与幂等约束不改。**前端禁止只是额外屏障，不是最终库存安全证明。** PG18 跨事务并发回归仍需 A3 统一实跑。

## 4. 验收及未执行项

- `WarehouseDirectoryPageTest` 新增批量解析的 100 限制、去重、租户 where 占位参数和不存在 ID 测试。
- `purchasing-writes-v115.test.ts` 检查 Orval API 的 POST+CSRF、原币单价字符串、审批路由、收货 ASN、库存 expectedOnHand 与 requestKey、409 fail-closed、单次仓库标签批量请求。
- V40/V46/V48/V49 既有 Java/Vitest 安全用例继续纳入普通快速 CI；没有更改其服务器锁与写入流程。
- `scripts/smoke_warehouse_directory_v114.py` 在 V114 的一次性 PG18（225 仓、第 201 个仓的本地人工 COUNT）基础上增加 V115 批量标签测试。仍然只在 `full_suite` / 受保护的 **localhost disposable PG18** 执行；没有实际运行的 PG18 测试不能记为通过。

## 剩余事项

- [Issue #62](https://github.com/kunlong-luo/merchivra/issues/62) 仍需全采购附属模块 OpenAPI/Orval、完整 PG18 大样本/安全验收；V110–V115 不能代替 G1/G2/G3 业务签署。
- [Issue #63](https://github.com/kunlong-luo/merchivra/issues/63) Next.js 真实 Docker runtime、Session/CSRF 代理联调与正式切换未完成。
- [PR #71](https://github.com/kunlong-luo/merchivra/pull/71) 为早于 V114 已合并 PR #72 的重叠方案，**不应直接合并**；需按当前 main 差异审查或标记被取代。
