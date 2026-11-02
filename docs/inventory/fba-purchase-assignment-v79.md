# V79 · FBA/采购异常批量分派、Owner 负载及跨模块导航

V79 以 V77 人工异常处理单和 V78 统一 Ops 待办为基础；**不新增分派/通知系统**，不使用真实 Amazon 授权，也不自动确认来源事实。

## 业务功能

1. **批量分派**：`POST /api/v1/fba/marketplaces/{marketplaceId}/purchase-reconciliation/workbench/bulk-assign`。Owner Session + CSRF。请求包含 `requestKey`（UUID）、`assigneeId`（当前租户活跃 Owner）、1–50 条 `{lineId,expectedVersion,expectedFingerprint}` 和 10–300 字理由。成功返回 `requestKey/assigneeId/assignedCount`。每行复用 V77 `ASSIGN` 状态机，仍为 OPEN/ACKNOWLEDGED，不会自动转为 ACKNOWLEDGED/RESOLVED。遇到非法负责人、来源变化、已完成案件、版本冲突或历史窗口外行时整批回滚，不写部分审计。
2. **幂等与历史**：Flyway V76 扩展既有 `fba_purchase_exception_batch` 幂等账本，重命名 `acknowledged_count` 为通用 `affected_count`，旧记录默认 `action_type=ACKNOWLEDGE`；新增 `ASSIGN` 和可选负责人 FK。沿用全站点唯一的 `(tenant,marketplace,request_key)`，跨动作复用同一个键会冲突。已有 V77 的批量确认 API/返回 JSON 字段不变；逐行历史仍由 `fba_purchase_exception_audit` 保管。
3. **Owner 工作量**：`GET /api/v1/fba/marketplaces/{marketplaceId}/purchase-reconciliation/workbench/workload` 返回已登记处理单（不限 V76 本页 100 张货件/500 行）的总数、尚未完成且未分派数、已不再属于活跃 Owner 的未完成案件数，以及最多 100 个有效 Owner 的 OPEN/ACKNOWLEDGED/超期数量。它不是员工绩效或 Amazon SLA；未知/未登记案件不当成 0。
4. **跨模块入口**：V78 统一 Ops 待办的证据变化、超期规则提供“查看 FBA 原处理单”。应用切换到「核心业务数据→FBA 库存→本地 FBA 货件与差异对账」，在站点列表确认目标 Marketplace 可访问后，定位对应本地异常和原货件。所有真实数据仍从服务端 Session 推导租户；浏览器路径不可代替权限校验。目标不在最近 100 张货件/500 行的读窗口时明确提示，不伪造找到了原记录。

## 安全和验收

- 无 Amazon Seller OAuth、SP-API 真实出站或库存/入仓/采购自动写操作；无额外 MQ、任务系统、前端路由依赖。
- 继续 PostgreSQL 18、Flyway、Java 25、Spring Boot 4、Gradle、React 19、TanStack Query、Zod。
- `scripts/smoke_fba_purchase_assignment_v79.py` 在一次性 PostgreSQL 18 + 虚构本地 Marketplace 数据上验证 Owner、两行原子分派、重复键重试、跨动作冲突、无效负责人、过期版本整批回滚、负载统计、来源不变及库存零写入。Java/React/Compose 验证依 CI 真实结论报告。
