# FBA / Purchase V76 · 本地 FBA 货件、采购及供应商 ASN 差异核对工作台

**2026-11-02 · V76。无新 Flyway 迁移：沿用 V20 采购、V43 ASN、V63 FBA 本地货件/差异接受、V73 明确关联证明与索引。** 这是严格只读的人工异常分类，**没有接入 Amazon 真实 FBA 货件事件、可售库存、承运商轨迹、财务或自动补货下单**。

## 1. 只读接口与访问范围

GET /api/v1/fba/marketplaces/{marketplaceId}/purchase-reconciliation

通过已认证 Owner Session 的 tenantId 与 Marketplace ID 查询 ACTIVE 租户；未登录/异店铺拒绝。只读 PostgreSQL 18 真实业务表，按最新 100 个本地 FBA 货件读取每个 Seller SKU 计划行，返回最多 500 行并以 truncated 标识超限。totals 只统计**已读取的范围**；shipmentScope 显式标识读取了多少本地货件，不能误称全店、全历史。响应 amazonVerified=false / fbaStockCredited=false；没有单独写入或定时扫描功能，也不复刻第二套采购、收货或风险规则数据库。

每行只用明确 V73 fba_inbound_purchase_link **未撤销**的唯一身份关联：能展示采购单编号/当前状态/订购量/本地实收、可选 SHIPPED ASN 当前状态/申报量/本地 ASN 实收。不存在身份关联时对应字段为 NULL，**不得用 SKU/数量/日期猜对应采购或 ASN**；已有采购、已登记本地仓库收货均不等同 Amazon FBA 货件发运或签收。

## 2. 风险优先级与人工操作建议

严格使用三个优先级：HIGH（本地相同货件行：报告入仓件数超过计划，尚无已接受差异）、WARNING（尚待人工核查）、INFO（仅参考）。从高到低判定单一主代码：

- LOCAL_OVER_REPORT：同一 FBA 本地计划行的报告量超过计划量，尚未被 V65 人工差异接受（HIGH）；建议走 V65 本地差异复核。
- PENDING_IDENTITY_REVIEW：V75 已提出、尚未独立 Owner 确认的关联（WARNING）；原 V65 dispatch 已被 V75 拦截。
- LINK_SOURCE_CHANGED：原已确认身份关联的本地 PO 已不在 APPROVED/RECEIVING/RECEIVED，或关联 ASN 当前不再为 SHIPPED（WARNING）。仅提示来源状态变化，不自动撤销或改变历史审计。
- LOCAL_VARIANCE_PENDING：V65 本地货件进入 REVIEWING 后，同一行计划与报告量不同且尚未经人工接受（WARNING）。
- LOCAL_REPORT_WITHOUT_IDENTITY：已有人为报告本地入仓，但没有有效采购身份关联（WARNING），不证明丢货或无法入仓。
- DISPATCHED_WITHOUT_LINK：本地货件不是 DRAFT，仍无有效采购关联（WARNING）；V65 原有未建立 PO 关联的货件允许存在，V76 只提示补证据。
- UNLINKED_DRAFT：新的本地草稿尚未建立采购关联（INFO），关联为可选而非系统猜测。
- LOCAL_VARIANCE_ACCEPTED：同一行 V65 差异已接受且存在有效来源链接（INFO），保留原独立复核结果。
- CONFIRMED_PO_ONLY：V75 双人已确认采购行，暂无明确 ASN 关联（INFO）；不是供应商已发货。
- IDENTITY_CONFIRMED：已人工双人确认采购/ASN 身份（INFO），仍不能视为 Amazon 官方签收。

**绝不直接相减比较不同阶段的 PO 收货、ASN 收货和 FBA 本地报告量来声称货损/丢件**。这三组数据基于不同实物处理环节；即使关联到同一 PO 行，其原值也可能在不同 FBA 货件行重复显示，不能简单跨货件加总。商品、数量、单价或承运商事件无法从本阶段凭空推断。

## 3. React 工作台

FbaPurchaseReconciliationPanel 集成原 FbaInboundPanel：按 HIGH/WARNING/INFO、需要人工核查、全部记录切换；显示货件、Seller SKU、来源 MOCK/手工未核验、计划与本地报告、PO/ASN 本地原始证据、身份审核状态、风险解释与下一人工动作。点击“查看对应货件”沿用原 V65 货件明细与 V75 关联面板，**没有新建平行收货或跨模块自动修复按钮**。已登记货件、采购及关联发生更新后，复用父查询前缀失效刷新，不增加 Redis/消息队列和定时任务。网络和未知值失败时均不显示伪造的零或伪造的已核验状态。

## 4. 验收

- JUnit: 同一行报告超计划为 HIGH、已人工接受差异不再假警报、二次 Owner 待审、已确认 ASN 状态变化、无关联 DRAFT/发运/本地报告等分类，以及**不比较跨来源数量**。
- Vitest + Zod: GET-only Session/marketplace URL、客户端无 tenantId/无 CSRF 误写、不合规枚举/负件数拒绝、UNKNOWN 源保留 NULL，最多 500 行。
- scripts/smoke_fba_purchase_reconciliation_v76.py: 一次性 localhost PostgreSQL 18、SP-API/OAuth 关闭、虚构 5 张本地货件，真实 HTTP 双 Owner 采购审批及 V75 关联，分别验证 CONFIRMED / PENDING / UNLINKED / 无身份的本地入仓 / 本地超报告，ASN 状态变动提示，以及 V65 独立人工差异接受后清理 HIGH；验证采购实际本地收货不会自动写入 FBA 库存。
- Chromium 360px: 真实 React 组件展示第二 Owner 待核查、PO 来源和明确非 Amazon 官方签收提示。既有 V65 Orval 合同维持原样。
- 本阶段不新增数据库表和迁移，复用成熟的 Spring JDBC 查询、React Query、Zod 4 与既有 API mutator。没有声称完成真实 Amazon Seller API 联调。

后续若要真正可信的 Amazon FBA 货件/入仓对账，需要 Seller 本地授权后基于官方 API 可追踪 ID、确认事件与来源版本建立只读采集，并明确人工纠错的会计/库存边界；不能将本地手工确认提升成平台签收事实。
