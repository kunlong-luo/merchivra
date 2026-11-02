# V81 · 30 天异常审计趋势、人工处理活动与复发识别

V81 是 V80 跨模块运营看板的只读扩展，继续复用 V77 的 `fba_purchase_exception_case` 与追加式 `fba_purchase_exception_audit`，不新增队列、表、迁移、前端图表依赖或 Amazon 写操作。

## API / 范围

新增 `GET /api/v1/exception-insights/marketplaces/{marketplaceId}/history`。租户从已有服务端 Owner Session 推导；每次读取首先重用 V78 `metrics` 的 marketplace/ACTIVE tenant 校验，外国/不存在站点返回 404，未登录返回 401。读接口返回 `Cache-Control: no-store`，不接受浏览器传入 `tenantId`。

固定查询从当前 UTC 日期往前 29 天（**30 个 UTC 自然日，含未完成的今天**），使用半开时间区间 `[UTC-29d 00:00, next UTC 00:00)`。不按本地浏览器时区猜测时间。日序列按 UTC 日期零填充，**只有本地审计记录真的为零时才输出 0**；数据库或接口故障必须显示错误。

字段涵盖：
- **每日趋势：** 本地注册处理单数（`case.created_at`）以及 `RESOLVE`、`REOPEN`、`ASSIGN`、`ACKNOWLEDGE` 和人工审计中来源指纹变化次数。RESOLVE 是动作计数，不是去重结案数；同一处理单可多次重新打开/完成。
- **30 天窗口摘要：** 新建处理单、全部审计动作、RESOLVE、REOPEN、发生过 REOPEN 的不同处理单、记录到审计的指纹更新次数。
- **操作人活动：** 按审计中实际 `actor_user_id` 归属操作动作，不把当前分派负责人误认为实际执行人。平均首次完成时长只计当前窗口内每个处理单的第一次 RESOLVE，计算 `first RESOLVE - case.created_at`（小时，保留 1 位小数）；无首次 RESOLVE 样本返回 NULL。**这是本地历史操作统计，不代表员工能力、平台 SLA、仓储交付或解决质量。** 显示最多 100 位操作人，保留 `actorsTruncated`。
- **复发来源：** 30 天窗口有审计 `REOPEN` 或人工动作写入的 `old_fingerprint != new_fingerprint` 的处理单，展示当前本地状态、累计（本窗口）重开次数、证据更新次数和上次重开时间。按重开次数、指纹变化次数、来源行稳定排序，最多 20 条，保留 `recurrencesTruncated`。未登记的来源变化**不会被伪造成历史复发**，应看 V80 的实时 P0 来源变化提示；旧 RESOLVED 的历史审计变化不应被删除。

## 前端

`OpsExceptionHistoryPanel` 嵌入既有 V80 `OpsExceptionInsightsPanel`，复用当前 Session 可访问 Marketplace 选择、TanStack Query/Zod、V79 `onOpenFbaException` 原单导航。轻量双轴**非预测**柱形图同时显示新登记、RESOLVE、REOPEN 的 30 天变化；随时可以展开结构化数据表核对。零数据、不可计算、超出限制、接口失败各自呈现不同状态。

## 隐私与自动化边界

本轮任何 GET 都不会触发 V78 Ops 规则扫描、自动分派、批量确认、PO/ASN 改写、入仓记账、FBA 库存更新、外部通知或真实 Amazon OAuth/SP-API。API 仅依据已登记处理单和追加审计计数；不能用平均首次完成时长比较员工整体绩效，也不能把人工 RESOLVE 视为 Amazon 完结。

## 验收

- `scripts/smoke_exception_history_v81.py` 在完全隔离的 PostgreSQL 18 假数据上，沿用 V76–V80 回归产生的真实审计，检查 UTC 30 日零填充、新单/完成/重开/证据变更计数、一致的逐日汇总、实际操作人首次完成耗时、已重开处理单来源跳转 ID、401/404 权限阻断、GET 重放零写入和 FBA 库存零变化。
- `apps/web/src/lib/exception-history-v81.test.ts` 测试 30 天 Zod 限制、只读同源 GET、错误不变零和 Amazon 假核验数据拦截。
- Java 25/Gradle、React 19/TS6/Vite8/Vitest、PostgreSQL18 与 Docker Compose 整体检查以真实 CI 结果为准。
