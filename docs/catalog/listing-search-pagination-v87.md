# V87 · P0 Listing 全量服务端检索、游标分页与映射版本证据

**日期：2026-11-03**。落实 [V86 核心功能审计](../testing/core-coverage-audit-v86.md) 识别的 P0 Listing 首 50 条限制。本阶段复用原 V2 `marketplace_listing` + `listing_sku_mapping`、V18 人工 SKU 映射和 V16 Session Owner / 精确 Seller Account `OPERATOR_READ`，不新建另一份 Listing 表、不改变 Amazon 商品或 Seller 认证状态。真实 Amazon OAuth/SP-API 均保持关闭。

## API

`GET /api/v1/seller-marketplaces/{marketplaceId}/listings/page?limit=50&field=SELLER_SKU&search=SKU-&status=ALL&cursor=...`

响应示例：

```json
{
  "items": [{
    "id": "22222222-2222-4222-8222-222222222222",
    "sellerSku": "SKU-001",
    "asin": "B0EXAMPLE01",
    "fnsku": null,
    "mappingStatus": "ACTIVE",
    "internalSku": "LOCAL-SKU",
    "activeMappingVersion": 2,
    "latestMappingVersion": 2,
    "sourceUpdatedAt": "2026-11-03T00:00:00Z",
    "dataOrigin": "MOCK"
  }],
  "nextCursor": null,
  "hasMore": false,
  "pageSize": 50,
  "field": "SELLER_SKU",
  "searchPrefix": "sku-",
  "mappingStatus": "ALL"
}
```

- **权限**：继续调用 `TenantAccessService.currentReadTenant` 与原 `authorizedMarketplace`；Owner 或经过已批准 Seller Account 范围限制的 `OPERATOR_READ` 才能查询。匿名 401；跨租户/不存在 Marketplace 404。没有客户端传入的 tenantId/actor 或通用搜索 SQL。
- **字段**：`field=SELLER_SKU|ASIN`，默认 Seller SKU；`search` 是最多 80 个可打印字符、大小写不敏感的**字面量前缀**，% / _ / ! 都被严格转义。选择 ASIN 且前缀为空时，仅返回有 ASIN 值的 Listing，不把未知 ASIN 当成空串。非允许字段 400。
- **状态**：`status=ALL|UNMAPPED|SUGGESTED|VERIFIED|ACTIVE|CONFLICT|SUPERSEDED`，默认 ALL，过滤 `marketplace_listing.mapping_status`。冲突行保留在结果集；状态不是自动映射或重新授权凭据。非允许状态 400。
- **排序/分页**：`seller_sku ASC,id ASC` 双键稳定排序；每页 1–100 条，额外读一行判断 `hasMore`。使用相同排序条件做 PostgreSQL keyset 比较，不使用 OFFSET 或全量内存拉取。Base64url 游标保存 Marketplace、字段、前缀、状态、最后 Seller SKU 和 UUID；SKU 又单独编码，可支持在原始数据中含换行/非 ASCII 的特殊字符。游标只定位，**不是授权凭证或完整数据集快照**。换字段、换搜索、换状态或换 Marketplace 时旧游标 400，后续每一页重验 Session 权限。
- **映射证据**：`activeMappingVersion` 与 `internalSku` 只来自 `listing_sku_mapping.status='ACTIVE' AND valid_to IS NULL` 的当前映射；`latestMappingVersion` 则来自该 Listing 的所有已登记映射版本 `MAX(mapping_version)`。如冲突行有历史版本但无当前 ACTIVE 映射，`latestMappingVersion=3`、`activeMappingVersion=null`、`internalSku=null`，**不能把历史 SKU 当现行关系**。无映射版本返回 null，不制造零版本。原 `mappingStatus`、`sourceUpdatedAt`、`dataOrigin` 保留。
- **缓存与迁移**：`Cache-Control:no-store`。旧 `GET .../listings?limit=50` 保持不变，现有 API 客户端和 Mapping 保存动作不需要迁移；新 React 页面调用分页契约。Flyway **V79** 为原表新增低写复杂度的 `lower(seller_sku)` / `lower(asin)` PostgreSQL 18 B-tree 前缀表达式索引，复用原租户/站点限定，不新建搜索系统、Redis 或第三方依赖。

## React 用户流程

核心 Listing 页提供 Seller SKU / ASIN 选择、映射状态筛选、显式提交的服务器前缀搜索、上一页/下一页。更新搜索条件、切换 Marketplace 时重置游标；SKU 映射操作区域仅接收本页 Listing，筛选/分页变化时清空旧选择组件，避免对上一页未知行误操作。映射或解绑成功后使 `listings-v87` 的 React Query 缓存失效，让新的活跃/历史版本返回服务端事实。

原有内置 SKU / Listing 映射审核动作、历史审计与独立订单/FBA 数据表不受影响。Listing 的“当前页有 50 条”不代表全站总数；顺序页可能因同步更新而发生变动，不是已冻结报表。V64 原币来源贡献任务仍是独立财务快照入口。

## 隔离验收

`scripts/smoke_core_listings_pagination_v87.py` 只允许 localhost PostgreSQL18、`DB_NAME=merchivra`、Demo 模式及 SP-API/OAuth 双关闭。接在原 V86 数据后，在同一可丢弃的 Mock Marketplace 填充 64 条 Listing，测试：

- Seller SKU 首 50 条 + 后 14 条；ASIN 仅 61 条，正确穿越第 50 条；
- 当前/最新映射版本、CONFLICT 有历史但未生效映射、UNMAPPED 无映射；
- 精确状态筛选、null ASIN、大小写不敏感、% / _ / ! 字面量、Seller SKU 内换行导致的游标边界；
- 非法 limit/field/status/搜索/游标 400，匿名 401、跨 Marketplace 404，历史接口兼容，库存零写入；
- Flyway V79 两个索引存在，Java 25 JUnit + React 19 TypeScript/Zod/Vitest/Vite、Docker Compose 的 CI 结果以 GitHub Actions 为准。

**后续 V88**：补充 FBA 每 SKU 最新观测的全库服务端分页/搜索，必须保持 OBSERVED 0、UNKNOWN NULL、STALE、更新时间和 Mock/Live 来源质量边界。真实 Seller 和 Ads 角色联调仍按约定由 Owner 最后在本地执行。
