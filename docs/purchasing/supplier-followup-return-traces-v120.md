# V120 — 供应商履约/催办 Orval 和 V52 退货追踪全库闭环

2026-11-09，A1 [Issue #62](https://github.com/kunlong-luo/merchivra/issues/62)。基线 V119 `c503424`。本阶段遵循 **Java 25 / Gradle / PostgreSQL 18 / React / Next.js / Orval** 和业务安全优先，不引入额外前后端 HTTP 框架。

## 已实现接口与页面

采购源规范 `apps/web/openapi/purchasing-v114.json` 版本为 **120.0.0**，由 V119 102 个增至 **112 个唯一 operationId**。

- **V73 履约本地证据评分（2 GET）**：继承原来已实现的服务器 `/supplier-performance/scorecards/page`。完整的订单 ASN 收货关联 + 近 90 天 QC 样本才参与评分；每类有效样本不足 3 条，`evidenceScore` 必须是 `null`、`scoringStatus=INSUFFICIENT_EVIDENCE`，不把缺失填零或宣称“承运商准时率”。旧 `/scorecards` 的 200 项限制只保留兼容路径，主 React 工作台继续分页。
- **V73 人工催办（2 GET + 2 POST）**：继续使用服务端 `/followups/page` 全量 keyset 与 `ACTIVE/ALL`、人工 `scan` 和 `followups/{findingId}/state`。发运前覆盖缺口和 ASN 未申报发货是本地人工跟进，**不会自动联系供应商**，已申报的 ETA 提醒继续由 V43 管理。
- **V52 已隔离报废/退货的供应商退货追踪（2 GET + 2 POST）**：旧 `/lot-supplier-returns/warehouses/{warehouseId}` 只返回前 200 条，保留兼容；新增 `GET /warehouses/{warehouseId}/page`，按 `(dispatch_reported_at,id) DESC` 稳定 keyset，每页 1–100，默认 50，游标明确绑定 `return-v120`、当前 Session 租户、仓库、时间与 UUID，跨仓库/租户/格式异常 HTTP 400。`POST` 发运只接受已有 `SUPPLIER_RETURN` 且 `EXECUTED_REPORTED` 的处置，使用请求幂等键和事务锁；`POST /{id}/receipt` 要求第二位 Owner、实点件数不得超过发运量，异常形成 `RECEIPT_MISMATCH`，不是二次库存入库/扣库。
- **操作入口**：`LotReconciliationPanel` 在已选仓库下增加 `SupplierReturnTraceSection`，复用 `CursorPageControls`、TanStack Query、现有处置记录、表单/确认对话框和 CSRF mutator；允许记录本地发运报告及独立人工回执。页面显式强调承运商/供应商均**没有外部核验**。来源选择仍受 V50 兼容处置列表的原有最大条数影响；**新退货历史**本身可翻完整库，完整处置候选目录仍为后续 P0。
- **统一客户端**：`supplier-performance.ts` 从手写 fetch 转向 Orval 生成方法；`lot-supplier-returns.ts` 是新前端 TypeScript facade，两个模块仍以 Zod 校验实际响应，所有变更由原有 `merchivraFetch` 设置同源 Cookie、CSRF 与 no-store，路径 UUID 在插值前 fail-closed，tenantId 由服务端 Session 决定。

## 保护和测试

- 不修改 V49 的源库位隔离、V50 处置审核状态机或 V52 接收双人复核/幂等规则，也不操作 Amazon SP-API。采购本地退货报表与 V44 QC 拒收退货是**两个独立来源**。
- Java `LotSupplierReturnTracePageV120Test` 模拟 225 条返回 50×4+25 的完整查询、最后第 201 条、`LIMIT 200` 不存在于新查询以及越权/错误游标在数据库访问前拒绝。先前 V52 事务/审计/互斥测试保留。
- Vitest `supplier-followup-returns-v120.test.ts` 验证：V73 样本不足值保持 NULL；服务端游标/过滤；225 条退货全历史；POST CSRF、幂等键、实际回执不匹配；坏 UUID/游标/409 阻断。旧 V73 CSRF 断言改成实际标准 Headers 语义。
- PR 自动快速 CI 使用 Java25/Gradle、Vitest/Orval/TypeScript/Vite/Next 和 Compose 静态检查；**真正 PG18 Flyway/HTTP、并发与大数据执行计划必须在 A3 里程碑集中跑**。未执行不等于已通过。

## 继续收尾

[Issue #62](https://github.com/kunlong-luo/merchivra/issues/62) 继续追踪 V50 盘点/仓间调拨/处置历史页面的真实全库分页、V51 独立异常复核/凭据目录与 V52 私人附件的安全 API 边界、FBA 采购关联及全合同覆盖。紧接着应进入 **A3 PG18 真 HTTP/安全/库存回归**和 [Issue #63](https://github.com/kunlong-luo/merchivra/issues/63) Next.js runtime 部署计划；不应无限追加新功能。实际 Amazon Seller OAuth/生产写入保持关闭，待用户本地最终授权与验收。
