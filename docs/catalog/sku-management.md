# CAT-001 · 内部 SKU 主数据与 Listing 人工映射（V19）

**状态：已加入代码，验收与真实 Amazon 同步留待后续集中验证。**

- `GET /api/v1/catalog/skus?limit=50&prefix=...&after=...`：租户 Owner 查询内部 SKU，基于 `internal_sku` 的稳定键集游标。
- `POST /api/v1/catalog/skus`：创建内部 SKU 和名称，租户唯一；重复返回 409，不能静默覆盖。
- `PATCH /api/v1/catalog/skus/{id}`：修改名称 / `ACTIVE`、`ARCHIVED`。尚有活动 Listing 绑定时不能归档。
- `POST /api/v1/catalog/listings/{id}/map`：把真实业务表中的 Listing 显式绑定已有活跃内部 SKU；事务锁住 Listing 行，旧映射版本闭合、新版本追加，无重复绑定的幂等副作用。
- `DELETE /api/v1/catalog/listings/{id}/map`：关闭当前活跃映射而非删除历史，回复 204。
- Flyway V18 增加 `product_sku.updated_at` 和 `catalog_change_audit`，记录创建、改名、归档、恢复、映射与解绑的操作者和时间，不存储敏感客户数据。
- React Core Listing 页面中，Owner 可以创建、改名、归档/恢复内部 SKU，并选择当前页 Listing 建立/解除映射。员工 `OPERATOR_READ` 仅阅读现有映射。
- **这不是 Amazon 远程 Listing 修改功能**，不写入 SP-API；也不是 Listing 真实自动同步，更不是完整供应链/商品变体/采购成本功能。没有在本次开发任务中运行测试。
