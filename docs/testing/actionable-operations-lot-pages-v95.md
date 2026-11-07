# V95：运营异常与库位纠正独立审批的完整操作分页

## 为什么优先处理

对 V94 后业务操作入口的固定行数做审计时，优先发现：

- **V10/V77/V85 运营待办**：实际运营工作台仍通过 `OperationsAutomationService.findings()` 读取最多 100 行；第 101 条及之后无法在页面领取、暂停、恢复、标记已处理或返回对应 FBA 本地处理单。V85 针对采购/FBA 异常的 VIEW 拒绝必须先于分页生效。
- **V51 来源明确的仓库异常纠正审批**：`LotExceptionResolutionService.list()` 最多 200 行，超出上限的待审核申请无法由第二 Owner 在界面中审核。已解决的历史列表挤占最近 200 行时会进一步影响可见性。
- 财务审核历史、供应商索赔/差异等还存在独立旧上限：本阶段不声称全仓库所有列表已分页，也不改动它们的来源证据或业务状态。

## 完成的操作链

复用现有 `LocalKeysetPage`（将既有静态方法暴露为跨模块公共方法）、Spring JDBC、TanStack React Query、Zod 和 `CursorPageControls`，没有新加缓存队列、ORM 或分页库。

**运营异常：**
- `GET /api/v1/operations/findings/page?filter=ACTIVE|ACTIONABLE|ALL&limit=50&cursor=...`。
- 服务端延续旧版状态优先级 `OPEN → ACKNOWLEDGED → DONE → CLEARED`；同一优先级按 `changed_at DESC,id DESC` 排序并游标翻页。
- 游标绑定 **租户、当前 Owner、筛选、优先级与排序键**，错误范围和无效游标返回 400，长度 1–100，默认 50。
- FBA 本地异常的 V85 显式 `NOT can_view` 站点拒绝在 SQL 内执行，**先过滤权限再选出页面**。相关操作仍由原有服务端 FBA 细粒度授权控制，不能通过分页扩大角色范围。
- `ACTIONABLE` 排除仍被暂缓的行；`ACTIVE` 包含暂缓。巡检/状态变更后回到第一页，并完整刷新现有 Query 缓存。领取/提醒操作仍复用 V77 处理控件、审计和独立鉴权。

**V51 仓库来源异常纠正：**
- `GET /api/v1/purchasing/lot-exceptions/warehouses/{warehouseId}/page?filter=PENDING|ALL&limit=50&cursor=...`。
- 默认 `PENDING`，只看需要另一 Owner 审核的 `PENDING_REVIEW` 申请，减少历史记录遮挡；`ALL` 则遍历所有已拒绝、已应用和待处理的记录。
- 游标绑定 **租户、仓库、状态筛选、`created_at DESC,id DESC`**。切换仓库、筛选或提交/审批成功后重置翻页。
- 新建申请仍由 V51 事务全库检查来源的单例约束和证据，列表筛选的当前页不能充当全量去重证明；第二 Owner 审批前依旧重查目标库位、来源、仓库已签名库存快照，提议人不能自批。
- 独立接口均 `Cache-Control: no-store`、当前 Session 租户 + Owner/CSRF 原有门禁，默认禁用 Amazon OAuth/SP-API 生产环境/外部写入。旧 `/findings` 和 `/warehouses/{id}` 的固定上限数组接口仅为兼容保留；业务界面已改为分页。

两组接口均属**实时查询而非冻结快照**；在审核/状态操作后数据可能重排，操作必须继续由后端重新核对实际状态。

## 隔离 PostgreSQL 18 Mock 验收

CI 在 V92/V93/V94 完整数据链之后执行 `scripts/smoke_ops_lot_pagination_v95.py`：

1. 登录本地 Owner。增加 165 条运营 Mock 异常（OPEN/ACKNOWLEDGED/DONE），其中 10 条暂缓：遍历 ACTIVE/ALL/ACTIONABLE，核对 100 条旧上限、筛选差异与游标跨页稳定。
2. 选择不在前 100 条的真实合成发现 ID，调用受 CSRF 保护的状态写入端点，确认 `DONE`、追加一条 `ops_finding_audit` 且从 ACTIVE 消失、在 ALL 保留。
3. 使用 V92 现有租户、仓库、收货来源创建一条**只在 disposable PG18 存在的**本地模拟库位元数据，然后增加 225 条独立的 V49 COUNT_OBSERVATION、V50 已复核证据及 V51 待审批申请（所有新观测不产生库存流水）；逐页核对后 25 条可见，当前 Owner 对第 225 条自批被拒绝 409、状态保留。
4. 无登录 401，范围、游标、筛选与 limit 非法 400；记录签名库存流水/采购单数不变。所有测试只在 localhost PG18 且 `MERCHIVRA_SPAPI_ENABLED=false`、`MERCHIVRA_SPAPI_OAUTH_ENABLED=false`、`MERCHIVRA_SPAPI_ALLOW_WRITES=false` 下允许执行。

**限制：**完整第二 Owner *成功批准并真实应用盘盈/少收* 的业务操作仍复用既有 V51 测试与服务端事务；V95 新增 Mock 专项只验证列表第 225 项可达及提议者自批被拒绝，不能将拒绝验证冒充正向双人审批。后续阶段可继续补全 FBA 权限拒绝页级集成测试、跨模块供应商索赔历史和财务审计分页。
