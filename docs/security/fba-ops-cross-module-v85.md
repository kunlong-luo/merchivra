# V85 · V84 Owner 异常权限在 Operations / FBA 证据入口的一致性

## 范围与定义

在 V84 中，V77 原始采购/FBA 异常工作台已实现站点级 VIEW、ASSIGN、REVIEW、RESOLVE、CONFIGURE、MANAGE 权限，但同一异常会由 V78 投射为 Ops `FBA_PO_EVIDENCE_CHANGED` 和 `FBA_PO_EXCEPTION_OVERDUE` 两类运营待办。V85 保持跨模块操作一致：不允许通过统一待办间接领取、关闭或修改被限制的本地异常，也不将这一局部授权误扩大到无关库存、退款、广告、订单或 FBA 原始货件操作。

**无新角色、无新权限表、无迁移、无消息队列或单独审计系统。** 仍沿用 V84 的现有 Owner/Marketplace 授权和原有 `ops_finding_audit`、`ops_finding_attention_audit`、V77 单行处理审计。原 `OPERATOR_READ` 身份不授予任何写能力。

## 服务器端必需能力

- FBA 采购差异/证据 `GET /api/v1/fba/marketplaces/{id}/purchase-reconciliation` 与 V77 原人工异常工作台：**VIEW**。这不是全部入仓或采购 API 的通用授权模型；本轮仅覆盖相同异常证据。
- 统一 Ops 待办列表 `GET /api/v1/operations/findings`：SQL 在 `LIMIT 100` **之前**排除显式 `can_view=false` 站点的两类 FBA_PO 异常，防止跨站点异常详情泄露或让被隐藏行占满前 100 名额。其他类别 Ops 发现保持原 Owner 权限。
- `GET /api/v1/operations/summary`：从**全部**已持久化 Ops 发现按同一 ACL 聚合，不能依据最多 100 行界面列表臆造全租户计数，隐藏异常不得通过汇总数量泄露。
- `GET /api/v1/operations/marketplaces/{id}/rules` 及 `rules/audit`：显式禁用 VIEW 的站点隐藏 FBA_PO 两类规则及其审计，但不隐藏其他七类或六类与其无关的运营规则（当前 Ops 总共八类）。
- `POST /api/v1/operations/marketplaces/{id}/rules/{ruleCode}`：仅当规则是两种 FBA_PO 人工异常时额外要求 **CONFIGURE**；其他 Ops 规则保留原有 Owner 检查。
- `GET /api/v1/operations/findings/{findingId}/attention-audit`：两类 FBA_PO 需要 VIEW，否则 403；外租户/不存在的 finding 返回 404。
- `POST /api/v1/operations/findings/{id}/claim`、`release`：两类 FBA_PO 需要 **ASSIGN**。
- `POST /api/v1/operations/findings/{id}/snooze`、`unsnooze`，及将其状态置为 OPEN/ACKNOWLEDGED：需要 **REVIEW**。
- `POST /api/v1/operations/findings/{id}/state` 将其设为 DONE：需要 **RESOLVE**。Ops DONE 仅是待办已处理状态，**从不代表 V77 本地处理单已 RESOLVED，更不表示 Amazon 完成入仓**。
- `POST /api/v1/operations/scan` 是全租户的事务性写入和扫描，因此任一站点当前调用者显式禁用 VIEW 或 CONFIGURE 时，**整个手动巡检拒绝 403**，不会触发部分 Ops 写入。计划任务由原系统服务按部署配置运行，不是用户手动巡检或权限委托。
- 所有非 VIEW 的原始工作台和 Ops 操作还必须具备 VIEW 这个父门槛：避免被撤销查看权限但仍保留 REVIEW 标志的 Owner 用猜到的来源 ID 进行盲操作。

控制器继续通过 `TenantAccessService.currentTenant`、活跃 Owner membership、Session 与 Spring Security CSRF 识别操作者，绝不信任请求的 tenantId/actorId。权限不作为自动复核、采购结算、货件收货、Amazon OAuth/SP-API 或异步操作的凭证。

## UI / 测试

React Operations 工作台从既有 V84 `getMyExceptionGrants` 获取涉及 FBA_PO 的站点级能力。对尚未获取有效权限的站点先禁用敏感操作；统一待办认领、暂缓、完成、重新打开及 FBA_PO 规则暂停/启用均按能力限制。后端每次 HTTP 操作再次验证权限。Ops Summary 加强错误显示，网络或权限故障不能被当作“0 条异常”。

`scripts/smoke_fba_ops_capability_v85.py` 接 V76–V84 已存在的全模拟链路，使用两位真实独立 Owner Session：
1. 第二 Owner 初始保留 VIEW/REVIEW，但无 ASSIGN/RESOLVE/CONFIGURE；证明可读 FBA_PO，不能通过 Ops 领取、DONE、改 FBA_PO 规则或启动整租户手动扫描；同一 Owner 仍可修改无关 LOW_STOCK Ops 规则。
2. 管理员进一步撤销 VIEW，但故意保留 REVIEW；证明 FBA 证据和异常看板返回 403，指定 ID 的原异常操作/ Ops 暂缓和变更/审计读取也返回 403，未授权对象不进入待办列表或统计汇总，FBA_PO 规则审计不会泄露，低库存规则正常可见。
3. 验证全部阻断操作不会新增 Ops 扫描记录或 FBA 库存入仓记录。全程 `MERCHIVRA_SPAPI_ENABLED=false` 和 `MERCHIVRA_SPAPI_OAUTH_ENABLED=false`。

Java 25/Gradle、React19/TS6/Vitest/Vite、真实 PostgreSQL18/Flyway、Docker Compose 以 CI 结果为准。
