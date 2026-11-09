# V121 — V50/V51 批次对账与异常纠正全库分页、Orval

2026-11-09，A1 Issue #62，PR #80。复用现有 Java 25 / Gradle / PG18 / Spring Boot 4.1 / React 19 / Next 16 / Orval 8 / TanStack Query；不修改库存过账状态机。

## 实施内容

- **V50 四类来源历史**：`GET /lot-reconciliation/warehouses/{warehouseId}/counts/page`（盘点复核）、`transfers/page`（已过账的跨仓调拨目的仓收货记录）、`dispositions/page`（过期隔离处置审批/执行）、`unplanned-quarantines/page`（尚未规划的过期隔离流水）。均按各自 `(created_at,id) DESC` 做服务端 keyset，`limit+1` 判断下一页，每页 1–100，默认 50。游标绑定 Session tenant、warehouse、记录类型、时间与 UUID，错误参数、跨租户/跨仓库/跨类型游标返回 400。旧 `LIMIT 200` GET 保留供兼容消费者，但**主 V50 页面不再用它们**。
- **V51 已实现的异常纠正分页**：复用 `GET /lot-exceptions/warehouses/{warehouseId}/page` 的 `PENDING/ALL` 服务器分页。新的 V51 UI 允许对盘盈复核来源和目的仓少收来源逐页选择，避免 UI 只能选前 200 条；仍由后端证明 sourceId 唯一性和全部审批/库存快照条件。原已放行批次位置目录另有历史限制，来源收货无法定位时客户端继续阻止申请，绝不猜测或造库存。
- **生成客户端**：采购规范 `apps/web/openapi/purchasing-v114.json` 版本 **121.0.0**、累计 **130 个唯一 operationId**（V120 112），新增 18 个 V50/V51 原有/新 GET/POST 契约。`lot-reconciliation.ts` 与 `lot-exceptions.ts` 的业务 Facade 已换为 Orval 生成方法，保留 Zod 对真实响应检查、动态 UUID 预检、Session Cookie / CSRF / no-store 和精确 `expectedOnHand`。
- **React 工作台**：`LotReconciliationPanel` 为盘点、调拨、处置历史和未规划隔离来源均复用 `CursorPageControls`，每页 50，仓库/租户切换重置游标；目的仓/隔离来源翻页清除原选择。原 V52 供应商退货确认组件仍由 V50 已审批处置列表提供候选，较早处置可经新的分页找到。
- **操作边界**：盘盈未经真实已质检放行的库存来源不能加库存；已签名跨仓过账的目的仓 ACK 与已隔离品处置不二次过账；盘亏/纠正仅由独立 Owner 经服务器全量幂等和库存快照核对后操作。人工处置报告、供应商退货发运与 Amazon/FBA 真实物流不可混为一谈。所有真实 Amazon 生产授权、SP-API 写入仍关闭。

## 回归策略

- Java `LotReconciliationKeysetV121Test` 模拟每类 V50 225 条，5 页完整检索含第 201 项、tenant/warehouse/type/时间/UUID/limit 越权和无效游标在数据库访问前拒绝，不修改库存。
- Vitest `lot-reconciliation-orval-v121.test.ts` 模拟 225 条四类分页，V51 过滤和游标、无样本和 409 fail-closed、Session/CSRF 幂等和 UUID 路径预检；现有 V50/V51 单测继续运行。
- PR 快速 CI：Java25/Gradle、Orval 生成一致性、Vitest/TS/Vite/Next 和 Compose 静态门禁。**真实 PostgreSQL18 Flyway、HTTP、双 Owner/RBAC/CSRF/并发/库存正确性及大租户索引验证未在本次快速 CI 中执行**；应在 A3 `full_suite` 里程碑集中执行，不可用模拟测试冒充生产可用性。

## 后续上线门槛

[Issue #62](https://github.com/kunlong-luo/merchivra/issues/62) 后续仅收尾实物库存/原始收货位置目录遗留上限、私有附件审计及 FBA 采购证据关联，尽快推进 A3 全链 PG18；[Issue #63](https://github.com/kunlong-luo/merchivra/issues/63) 完成 Next.js 正式 Node Runtime、Session 同源反代、HTTPS/Compose/回滚和浏览器真实 API 联调。GitHub Pages 是只读合成演示，不是正式业务系统。
