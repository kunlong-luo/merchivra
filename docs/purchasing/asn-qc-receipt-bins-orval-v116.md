# V116 — ASN、质检/退货、收货批次与待检库位的 Orval 统一

> 2026-11-09，Merchivra A1 / Issue #62。基线：V115 main `c188fd6`。本批次是**契约与前端 HTTP 客户端迁移**，不是重新实现业务流程或证明生产数据库集成测试通过。

## 本轮迁移范围

| 采购子域 | Orval 覆盖的现有真实 Controller 路由 | 重要限制 |
| --- | --- | --- |
| 供应商 ASN（9 个操作） | 按 PO 查 ASN、审计事件、创建通知、ship/cancel、ETA 变更、findings、scan、人工处理 state | `SHIPPED` 仅为供应商申报，不是承运商验真、实收到货或 FBA 货件。原服务器 PO 锁、ASN requestKey、预计到货变更审计和可接收数量上限不变。旧 ASN 列表/事件依旧使用固定条数；本轮只迁移合同，不宣称解除了上限。 |
| 采购质检与供应商退货（13 个操作） | 实收质检列表与 keyset、创建检验、供应商退货列表与 keyset/汇总、创建退货、dispatch/cancel、findings 与 keyset、scan/人工 state | `accepted + rejected = received`、一次性 QC、隔离账和退货不二次扣库保留在 Java。返回的整单总数不是当前页统计。 |
| V46 实际收货批次（9 个操作） | 采购单/仓库批次列表与 keyset、整单汇总、改批号、独立 Owner 放行、审计事件及 keyset | **质检员≠放行 Owner**；`INSPECTED` 仍不可用，必须显式 RELEASE。 |
| V47 待检库位/分件（9 个操作） | 仓库 bins 列表/游标与创建、实收 pieces 列表/游标、split/move、供应压力列表/游标 | 分件和待检库位移动不自动生成可售库存；`declaredAsnInTransitUnits` 是供应商申报，不能冒充实际在途或 FBA 可售。 |

`apps/web/openapi/purchasing-v114.json` 沿用 V114 的固定生成文件名（避免下游变更），版本更新至 **116.1.0**，累计 **58 个唯一 operationId**（包含 V110–V115 主采购操作）。新增 DTO 明确 UUID、整数、带符号余额、日期、nullable、枚举、`Page<T>` 游标和 piece page 的 `totalQuantity`。

`supplier-asns.ts`、`purchase-quality.ts`、`receipt-batches.ts`、`lot-locations.ts` 已调用 Orval 生成函数，保留现有 Zod 服务端响应约束和面向业务的导出函数签名，避免要求已有 React 工作台重写。所有 POST 使用共享 `merchivraFetch` 的 Session 同源凭据、CSRF 和 no-store；所有动态 URI 的路径 ID 在交给 Orval 字符串插值前都先用 UUID Zod 验证，无效输入 fail-closed，不产生网络请求。

前端生成流程复用 `apps/web/scripts/ensure-purchasing-generated.mjs` 的 OpenAPI SHA256 缓存，在 npm test/build/typecheck（含 Next preview）前生成，不手改忽略签入的 `generated/purchasing-v114.ts` 和模型文件。仍可用 `cd apps/web && npm run api:generate:purchasing` 主动更新。

## 风险和测试边界

- 本阶段**没有修改** Java ASN/QC/批次/库位的业务逻辑，也没有新增数据库迁移；底层事务锁、租户过滤、Owner 授权、QC 审核隔离、请求幂等的既有服务端测试继续作为快速 CI 的保护。
- 旧 Vitest 调整为从标准 `Headers` 对象验证 `X-CSRF-TOKEN`，而不是依赖旧手写 fetch 的对象字面量格式。现有 ASN/QC/receipt-batches/lot-locations 单测仍核对真实 HTTP 路由和请求体。
- 新增 `purchasing-subdomains-v116.test.ts`：9 种 keyset 分页端点、`limit=50` 与 cursor、全量 piece quantity、同源/no-store、ASN 发货申报≠收货、UUID 路径拦截、CSRF requestKey/expectedBinId、409 fail-closed、旧数组/坏游标合同拒绝。
- **真实 PostgreSQL 18 HTTP、并发对抗、权限、金钱与完整库存不变量回归仍由 A3 的 `full_suite` 集中执行**。未运行不能记为通过。
- 采购子域中 V48 已放行分段质检/库存、V49 批次生命周期、供应商采购规则、采购交期、索赔、批次证据/纠错、FBA 采购闭环仍需后续 Orval 合同扩展。ASN 的既有 `LIMIT 200` 和部分早期只读记录的固定条数仍待另案服务端分页；本次不把客户端迁移误写成消除这些截断。

## 上线依赖

[Issue #62](https://github.com/kunlong-luo/merchivra/issues/62) 继续开放以跟踪剩余子域覆盖与 A3，Next.js 正式运行/反向代理/Session+CSRF 由 [Issue #63](https://github.com/kunlong-luo/merchivra/issues/63) 单独处理；Amazon 真实生产授权和远程写入仍关闭，由用户最后在本地完成授权测试。GitHub Pages 仅为只读展示，不是 ERP 生产系统。
