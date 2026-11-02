# V83 · 批量完成预检、逐行诊断与批次账本可追溯

在 V82 已审核的单事务 RESOLVE 流程前新增**只读预检**。本阶段使用原 V77–V82 所有处理单、CAS、状态机、租户权限和幂等批次账本，不新增表、数据库迁移、权限角色、依赖、异步任务或 Amazon 写入。

## 接口

`POST /api/v1/fba/marketplaces/{marketplaceId}/purchase-reconciliation/workbench/bulk-resolve/preflight`

请求为 `{ "lines": [{"lineId":"uuid","expectedVersion":2,"expectedFingerprint":"64位小写SHA-256"},...] }`，1–50 条。使用 POST 仅为承载选择行及指纹，**它不产生数据库写入**，但继续执行 Session Owner 租户认证及 CSRF 验证。没有资格的操作者不得将租户 ID 从客户端传入绕过服务端权限；不存在的 Marketplace 返回 404。跨站点或不在 V76 最多 100 张货件/500 行范围内的行，只返回统一 `OUT_OF_SCOPE`，不泄露该行实际属于哪一个租户/站点。

服务器对每个选择行返回 `lineId`、站点内可见货件标识/SKU（不可见为 NULL）、当前已登记处理单状态、指派人、实际版本、当前来源指纹、`eligible` 和有解释的 `reasonCode`。固定原因：
- `READY`：已经 ACKNOWLEDGED、有负责人、当前版本及来源指纹一致，且 V65 差异或 V75 独立身份审核阻断不存在。
- `OUT_OF_SCOPE`：行不在当前租户/站点/业务读取边界；只返回用户原来提供的行 ID。
- `CASE_NOT_REGISTERED`：没有持久化人工处理单。
- `EVIDENCE_CHANGED`：原来源指纹与最新本地证据不一致，或客户端携带的指纹过期。
- `VERSION_CONFLICT`：人工处理单的乐观版本和用户选择时不一致。
- `NOT_ACKNOWLEDGED`：尚未通过人工确认，或已是本地 RESOLVED。
- `NO_ASSIGNEE`：缺少已分配负责人。
- `VARIANCE_REVIEW_REQUIRED`：V65 本地入仓超报仍未独立复核。
- `IDENTITY_REVIEW_REQUIRED`：V75 采购/FBA 身份关系尚需第二 Owner 独立核实。

`GET /api/v1/fba/marketplaces/{marketplaceId}/purchase-reconciliation/workbench/batches`

读取当前 Session Owner 可访问站点中既有 `fba_purchase_exception_batch` 最近 50 条记录，包括原始请求幂等键、动作类型（ACKNOWLEDGE / ASSIGN / RESOLVE）、实际操作者、成功行数与提交时间，按创建时间和 ID 倒序。**账本不保存按批次关联到行级审计的外键**，因此 V83 不臆造批次与具体行的关联。确切行操作仍须从 V77 `cases/{lineId}/history` 查看。

## UI 和安全保证

工作台使用现有 `merchivraFetch`、Zod、TanStack Query 和 Session CSRF：
1. 选择 1–50 行后，点击“预检所选处理单（不写入）”；显示逐行的中文阻断原因。即使来源状态在 UI 看似完成，也以服务器的证据及 CAS 结果为准。
2. 只有对应**同一站点、同一选择顺序、同一乐观版本/指纹**的本次预检全部通过后，才启用显式人工确认选框及本地 RESOLVE 按钮；预检失败或更换选择时不得复用旧预检。
3. V82 提交端依然**独立、原子地重新调用 V77 RESOLVE 全部安全条件**。预检只是无锁瞬时观察，不是永久批准、资源锁、授权凭证或对 409 冲突的豁免。提交时任何版本或来源变动仍拒绝并整批回滚，提示刷新。

此前已有的 V77 人工审计、V79 分配、V82 配置审计不变。无新的 Operator 写入权限，也不改变既有 Owner 门槛。所有 Amazon Seller OAuth/SP-API、PO/ASN、FBA 库存和外部通知均保持关闭。

## 回归与验证

- `scripts/smoke_fba_exception_preflight_v83.py` 接在 V76–V82 隔离的 PostgreSQL 18 HTTP 假数据后，验证未登录 401、跨站点 404、越界脱敏、重复/错误输入 400、明确原因/可完成数、批次账本、预检的审计/处理单/批次零写入，以及**预检成功后来源证据变化、真实 RESOLVE 仍必须 409 拒绝**，并保证零 FBA 库存变更。
- `apps/web/src/lib/fba-exception-preflight-v83.test.ts` 验证类型边界、枚举原因、CSRF POST 与只读账本 GET。
- Java 25/Gradle、React 19/TS6/Vitest/Vite8、PostgreSQL 18/Flyway 和 Docker Compose 以 CI 实际结果为准。
