# Merchivra 功能对标与分阶段交付（2026-10-31）

本表参考公开的 [Sellercloud 产品功能](https://sellercloud.com/features/)、
[Linnworks 自动化](https://www.linnworks.com/features/ai-automation/)、
[Helium 10 库存管理](https://www.helium10.com/tools/operations/inventory-management/)。
属于产品能力对标而非复刻竞争对手实现。不能把“已写框架/API”当作真实生产接通。

| 业务能力 | 主流同类软件常见做法 | Merchivra 当前事实 | 优先顺序 |
|---|---|---|---|
| 多店铺/Marketplace | Sellercloud/Linnworks 统一渠道 | 本地多店铺、租户隔离、真实 OAuth V7–V9（仍需 Amazon 权限验收） | P0 完成真实授权/身份校验 |
| 订单与履约 | 自动下载、订单规则、物流/面单、退款 | Core 结构化表 + 离线 Mock；正式 Orders Worker 尚未上线 | P0 安全只读增量 Worker |
| Listing/SKU | 映射、异常质检、批量编辑 | Core Listing、映射事实及读取；V10 自动异常待办 | P1 映射审核与批量操作 |
| FBA 库存/补货 | 自动刷新、低库存阈值、补货预测 | 库存观测与未知/0 区分；V10 低库存与缺失数据任务 | P1 补货计算/安全建议 |
| 运营工作流规则 | Linnworks 规则自动分流/暂停；Sellercloud 定时任务 | V10 四种只读异常规则 + 待办 + 审计 + 可选定时扫描 | P0 扩展规则配置、通知、按店铺分配 |
| 财务、成本、利润 | 对账、退款/手续费、SKU利润 | 禁止把订单预计销售当做结算收入；无正式财务授权 | P0 先定口径后逐模块 |
| 采购与供应商 | 安全库存、采购单、供应商与在途 | 尚未实现 | P1 保守单据 + 审批 |
| 报表和提醒 | 趋势、异常、邮件/站内消息 | Mock经营总览 + V10 站内待办 | P1 看板、站内通知和日报 |
| 广告、关键词和调价 | 一些 Amazon 专用工具提供相关操作 | 尚未实现；广告和调价属于高风险写入 | P2 先观察/建议，再审批写入 |
| 多角色与细粒度权限 | 企业角色、团队工作流、记录审计 | 当前只有 OWNER 租户作用域 | P0 授权与正式运营前必须补齐 |

## V10 本轮实际交付

- Flyway V10 `ops_finding`、`ops_automation_run`、`ops_finding_audit`，每条记录绑定 tenant + marketplace。没有用户凭证、买家 PII 或财务账务存储。
- 检查来自本租户的：`UNMAPPED/CONFLICT` Listing、最新 FBA 数据不完整、最新可信观测的 `fulfillable <= threshold`、已知部分/全部退款。**只有 quality=OBSERVED 且非 NULL 的库存才参加低库存判断，UNKNOWN 绝不当做 0。**
- 同一异常重复巡检不会重复创建；人工完成后，在异常依然存在期间保持完成，条件消失自动标 CLEARED，之后重新出现才重新打开。
- 通过租户行锁串行化跨实例扫描，事务原子写入发现、清除和运行记录；OWNER Session+CSRF 可以触发扫描/流转。用户不得自带 tenantId。
- 新的运营自动化页面、4 类状态摘要、记录筛选、异常来源标记、人工认领/完成/重开。
- `MERCHIVRA_OPS_SCHEDULER_ENABLED=false` 默认关闭自动周期任务，部署者打开后每 15 分钟巡检一次；频率与低库存阈值可由环境变量调整。**不要求开启真实 SP-API/OAuth。**
- 不会自动采购、扣费、发货、发送客户消息、改动来源数据、调用 Amazon 或做广告/调价。

## 接下来优先实现

1. V11 正式 Orders 增量只读 Worker 与持久化 checkpoint / lease fencing / 429重试，必须先具备真实卖家 ID、角色核验、撤销阻断；继续默认关闭所有生产出站。
2. 运营规则配置持久化、阈值按站点/SKU 细化、站内通知/每日日报、人员分配与 SLA（须正式员工角色设计）。
3. Listing 映射冲突审核与多 SKU 操作草稿；只读 FBA 断货预测（含安全库存和缺失数据保护）。
4. 采购审批、退货/退款核对、P&L 口径审批、财务对账与可解释利润报表。
5. 生产 IAM、安全密钥/KMS、备份恢复、角色最小权限、真实 Amazon sandbox/live 认证及 β 验收。

V10 验收需以 GitHub Actions 的 Java/Vue 和 PostgreSQL Compose 全链路实际状态为准；测试场景只用虚构数据。正式 Amazon/通知/采购尚未接入。


## Work queue

- [#6 durable real Orders sync worker](https://github.com/kunlong-luo/merchivra/issues/6)
  and [#8 Orders domain integration](https://github.com/kunlong-luo/merchivra/issues/8)
- [#23 configurable operations rules + notifications](https://github.com/kunlong-luo/merchivra/issues/23)
- [#24 replenishment/purchase approvals](https://github.com/kunlong-luo/merchivra/issues/24)
- [#11 Amazon Finance and settlement reconciliation](https://github.com/kunlong-luo/merchivra/issues/11)
- [#13 production authorization, audit and disaster recovery](https://github.com/kunlong-luo/merchivra/issues/13)

V10 evidence: [CI](https://github.com/kunlong-luo/merchivra/actions/runs/41151803877),
[actual PG/Nginx/Java mock-chain](https://github.com/kunlong-luo/merchivra/actions/runs/41151803940),
[synthetic Chrome operations UI](https://github.com/kunlong-luo/merchivra/actions/runs/41151964910).


## V11B · Orders 角色证据与只读适配器（2026-10-31）

- Flyway **V12** 新增 `spapi_orders_role_evidence`。Seller v1 Marketplace 参与状态**不能证明**应用具有 Orders API 角色，因此不会自动创建角色证据。每条证据按 tenant/account 绑定、审核者、SHA-256 外部证据摘要、审核时间、有效期（最多 30 天）、显式撤销，并强制关联当前 Seller 授权的 `verified_at`；重新核验/吊销 Seller 后旧证据无效。
- `SpApiSellerGrantVault.loadOrdersApproved` 在原先租户/账号/Marketplace/连接状态/24h Seller 核验证据门禁上，再检查独立的 `Inventory and Order Tracking` 角色审核证据。无证据或过期绝不返回带角色的 Seller Scope。
- `SpApiRequest` 与官方 SDK 的 `SpApiOfficialModules.searchOrders/ordersDetail` 都新增按操作强制检查角色。当前仅允许精确的 `Inventory and Order Tracking`，比 Amazon 对 Orders API 公布的角色清单更保守。
- `ApprovedOrdersReadGateway` 实现只读 Orders v2026-01-01 的服务端适配器：从租户 Marketplace 解析 Seller Account，前置校验授权与角色，调用已有审查过的 `SpApiReadOperations.searchOrders`，按官方响应数据结构解析订单、强制校验 Marketplace 匹配，并在返回前重新检查 Seller 授权，防止网络读取期间撤销授权后继续使用结果。
- **仍不注册自动 Spring Bean、不暴露 HTTP API、不挂接到 V11A 的 MOCK-only 写入 Worker**。目前没有证据登记/审核 UI，也没有直接连接真实卖家的生产读操作；不能通过增加上述代码宣称已实现 Seller 角色核验或真实 Amazon 同步。
- 单元测试覆盖缺少 Seller/角色拒绝出站、无效时间窗口、市场错配、请求途中撤销授权、响应结构与无 PII 读取；PostgreSQL 18 Compose 检查 V1–V12 全部迁移且默认角色证据表为空。

Amazon 官方角色文档：https://developer-docs.amazon.com/sp-api/docs/orders-api 。
