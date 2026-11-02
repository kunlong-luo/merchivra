# V77 · 采购 / FBA 异常处理工作台（本地证据，不触达 Amazon）

**业务里程碑 V77 / Flyway V74 · PostgreSQL 18、Java 25 / Spring Boot 4 / Gradle、React 19 / TanStack Query / Zod 4。**
沿用 V75 的明确 PO/ASN 身份关联、V76 的事实级只读分类；不增加另一套数量对账算法。真实 Amazon Seller 授权、官方读取和写入仍关闭。

## 边界和数据语义

- 数据来源仅 V63 本地货件、V20 本地采购、V43 供应商 ASN、V73 V75 身份链接与 V76 风险分类。PO 实收、ASN 实收、本地 FBA 报告来自不同阶段，**绝不相减得出“平台丢件”**。
- RESOLVED 是 **ERP 操作人员已记录处理结果**，不表示 V65 报告已更改、Amazon FBA 入仓已确认、FBA 可售库存已增加、供应商已经赔偿或任何财务凭证已形成。
- 初次查看工作台只读，不自动插入异常 Case；工作台仅覆盖最近 100 张本地货件，最多 500 行，`truncated` 和 `shipmentScope` 显示读取范围，不能当成全量未结数量。
- 每条处理单唯一绑定租户、Marketplace、原本地货件行；所有来源关联使用真实 ID，没有 SKU 猜测与跨店铺写入。

## 后端接口

均在 `/api/v1/fba/marketplaces/{marketplaceId}/purchase-reconciliation/workbench` 下，使用服务器 Owner Session 确定租户和操作人；POST 经 Spring Security CSRF，GET 全部 no-store。

| 方法 | 路由 | 语义 |
|---|---|---|
| GET | `/` | 100 货件 / 500 行的风险及处理状态、可用 Owner、期限提示、当前策略 |
| POST | `/cases/{lineId}/actions` | ASSIGN / ACKNOWLEDGE / SET_DUE / RESOLVE / REOPEN |
| POST | `/bulk-acknowledge` | 1–50 行的事务式批量确认，稳定 requestKey 重试，旧版本整批回滚 |
| GET | `/cases/{lineId}/history` | 单行不可变处理历史，最多 100 条 |
| POST | `/policy` | 启用开关、提前提醒小时数、初次处理默认期限，乐观版本锁 |
| GET | `/policy/history` | 不可变策略变更审计，最多 100 条 |

### 并发与安全

1. 操作输入必须提供 `expectedVersion` 与 `expectedFingerprint`（来源指纹）。指纹覆盖货件状态、计划量、本地报告、V65 复核状态、V75 有效链接、PO/ASN 当前状态及 V76 风险分类；**不会把跨环节的不同数量当成损失**。新的来源变化后，该 Case 呈现 RECHECK，旧指纹被 HTTP 409 拒绝。
2. 写操作对原本地货件行所在货件加锁，Case 使用行锁和版本 CAS；批量按行 ID 排序加锁。批量请求键全局限定在 tenant + Marketplace，存完整载荷摘要与操作人，相同重试返回历史计数、冲突载荷返回 409；任何一行失败则整个事务撤销（含审计）。
3. 负责人必须属于同租户、仍然 ACTIVE 的全局 OWNER；不能分配给已撤销成员、外租户或只读操作员。ACKNOWLEDGE 无负责人时默认由当前 Owner 接手。每个动作强制 10–300 字理由；保留旧/新状态、负责人、期限、来源指纹和操作者。
4. RESOLVE 必须先 ACKNOWLEDGE 且有负责人。对于 LOCAL_OVER_REPORT，需先通过原 V65 独立差异接受；对于 PENDING_IDENTITY_REVIEW，必须先经 V75 第二 Owner 的源身份审查。无任何动作能跳过原流程或修改原证据。
5. 可配置的提醒仅用于 **工作台页面内判定**：提前 1–168 小时、首次处理期限 1–720 小时，可关闭；逾期标 OVERDUE、临期 DUE_SOON、来源变化 RECHECK。**不发送邮件/推送、不运行后台扫描、不调用 Amazon，也不自称实时通知**；需打开/刷新工作台查看。长期自动通知应以后续经过授权的事件流设计实施。

## 前端与工具复用

V77 React 组件直接装配在原 FbaInboundPanel 的 V76 证据清单之后。共享 TanStack Query 前缀 `['fba-inbound',tenantId,marketplaceId]`，复用原 `merchivraFetch`（同源 Session、CSRF、错误处理）与 Zod 严格解析，不另起 HTTP 客户端、队列、Redis 或审计框架。历史按选中项延迟加载。提醒策略支持版本号与审计历史，批量动作只确认风险已知悉，并不改变 V65/V75 事实。

## 验收

- `apps/server/src/test/java/org/merchivra/erp/fba/FbaPurchaseExceptionServiceTest.java`：指纹稳定性、来源变化以及跨环节数量不推断货损。
- `apps/web/src/lib/fba-purchase-exceptions.test.ts`：Zod 边界、Session 同源 GET、CSRF POST、批量幂等键、提醒配置。
- `scripts/smoke_fba_purchase_exceptions_v77.py`：必须接在 `smoke_fba_purchase_reconciliation_v76.py` 后运行于隔离 PG18 / 本地 Spring HTTP 实例；验证无授权 401、外店铺 404、已启用 Owner 分配、CAS 409、批量确认与幂等重放、失败整批回滚、期限临近提示、策略审计、ASN 来源更改触发 RECHECK、FBA 库存观察表零写入。
- CI `pg18-migrations` 阶段执行真实 Flyway V74，并运行该脚本；Java 25 Gradle、React 19 TypeScript/Vitest 分别校验。

## 下一阶段方向

V78 可评估统一消息收件箱（复用现有 Operations 提醒概念）、根据来源更新时间自动建议重新核查、对已完成工单建立 SLA 统计，但需先隔离准确定义“提醒送达”和“单据异常已解决”。真实 Amazon 官方证据仍留待用户本地授权和逐项验证。
