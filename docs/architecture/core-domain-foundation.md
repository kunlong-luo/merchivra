# DATA-004 · M2 PostgreSQL 核心数据表（实施基线 v0.1）

> **2026-10-30 / 部分实现**：`V2__core_domain_foundation.sql` 将候选设计转换为第一批 Flyway 物理约束。它仅包含核心结构；**不表示 G1/G2 已正式签署，也不表示身份认证、真实卖家接入或资金模型已完成**。

## 表与作用域

| 组 | 已建表 | 约束重点 |
|---|---|---|
| 基础成员 | `tenant`、`app_user`、`membership`、`role_binding` | 身份引用、会员关系、同租户角色绑定（**无登录逻辑**） |
| 店铺 | `seller_account`、`seller_marketplace` | `(tenant_id,id)` 复合唯一键，同租户复合外键与来源 Seller 维度 |
| 商品 | `product_sku`、`marketplace_listing`、`listing_sku_mapping` | 内部 SKU 与 Seller SKU 分开，站点唯一性，当前映射部分唯一索引 |
| 订单 | `sales_order`、`order_item` | `(tenant_id,seller_marketplace_id,external_order_id)` 幂等作用域，原币 NUMERIC 与数量校验 |
| FBA | `fba_inventory_observation` | 来源观测键和站点作用域，`NULL` 允许 UNKNOWN，非负库存 |
| 同步 | `sync_stream`、`sync_run`、`sync_page_commit` | Lease epoch、来源接口/版本/作用域指纹、同租户运行与页去重 |

**Mock 映射已落地：** `DemoCoreImporter` 只在本地成功手动 Mock 同步时，将固定虚构租户的数据转换为 Seller、Marketplace、SKU/Listing、订单与 FBA 观测。来源账户/订单/库存键派生稳定 UUID，使用 `ON CONFLICT` 保持重复调用幂等。演示订单项数量 1 是测试简化，**不能用于 Amazon 真实订单项映射**。核心表计数只通过 `/api/v1/demo/core-status` 公开，仅对固定 Mock 租户有效。

所有业务表外键不只引用随机 UUID，还包含 `tenant_id`。**外键本身不能防止服务端查询越权**；加入真实身份和认证之前，没有任何非 `/demo` 的业务读取/写入 API 暴露。

## 本阶段与 G2 草案的差异

- 暂不加入 Finances / Settlement / FX / 退款金额事实表。Amazon Finances 来源唯一键和费用分类尚未实测。
- `listing_sku_mapping` 仅限制“当前 ACTIVE 且 `valid_to` 为 NULL”的映射唯一，**尚未通过时间区间排斥约束证明历史不重叠**。
- 库存 `source_observation_key` 是 ERP 来源适配器确保的**操作/维度/批次稳定键**，不能假设 Amazon 总会返回唯一库存事件 ID。
- `role_binding` 尚无全局与账户范围角色决策，`seller_account_id NULL` 的重复语义需 Auth ADR 定稿时迁移修正。
- `sync_stream` 是可落库的物理实体，真正 Worker CAS、租约续签、分页 Checkpoint **尚未实现**。
- `source_updated_at` 或业务时间未知可以为 NULL；不能因为字段缺失把它填为当前平台时间。
- Flyway V1 演示快照表与本次 V2 核心表独立，已发布的 V1 没有修改。

## 不可用场景

- **禁止向新表导入真实 Amazon 数据**：没有会话身份、授权 Seller Account scope、密钥管理及 PII 保护。
- **不能宣称数据库迁移在真实 PostgreSQL 已跑通**，除非有实际 Compose/PG 验证证据；当前轻量 CI 只有 Maven/TypeScript/Vitest/构建。
- V2 没有使用 RLS；今后应以服务层强制过滤 + 数据库外键及评审通过的 RLS 作为完整方案。

## 轻量验证优先级

1. SQL 约束设计静态检查：复合 FK 的引用 UNIQUE 是否完整、金额与库存 NULL/0 是否区分。
2. 下次可用 PostgreSQL 时应用 `V1`、`V2`，尝试插入同来源重复记录、跨租户引用、未知/零库存，并记录结果。
3. Auth 定稿后接入服务端租户 Context 和真正的数据 Repository，**不准直接开放数据库表为无鉴权 CRUD**。

相关：[物理设计候选](physical-data-design.md)、[租户权限](../security/tenant-enforcement.md)、[Mock 工作台](../demo.md)、[轻量 CI](../testing/ci-policy.md)。
