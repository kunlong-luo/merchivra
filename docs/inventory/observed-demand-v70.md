# INV-070 · V70 订单行销量观测与补货参考（非自动下单）

**产品阶段 V70，Flyway 迁移 V67；2026-11-02。** 这是 V22 补货策略、V37/V38 人工采购草稿以及 V42 供应商采购规则的**只读补充**，并非新采购单系统，也不是能独立认证的预测服务。Seller OAuth、Ads OAuth、外部自动下单和远程写入保持关闭。

## 用途与安全限制

- 当前 Marketplace 配置了补货策略后，Owner 可以在原 FBA 库存补货页面查看每个 Seller SKU 的 **7 天 / 28 天观察到的下单件数**、28 天平均下单件数，以及基于人工配置的 leadTimeDays 计算的**示意件数**。窗口固定为 \`[当前时刻 - 28 天 - 30 分钟, 当前时刻 - 30 分钟)\`，所有时间按 UTC 时刻比较。
- **仅允许可解释的完整来源**：须 ACTIVE Listing、唯一 ACTIVE 内部 SKU 映射、ACTIVE 内部 SKU；\`orders_sync_job\` 必须存在、state=\`SUCCEEDED\`，窗口覆盖完整 28 天、最新安全水位覆盖截止时间，且当前 generation 至少有一笔已经提交的页日志。对应窗口内每个 Order 必须 \`items_observation=OBSERVED\`，所有当前订单行的数量/Seller SKU/来源必须完整；订单来源不能与 job mode 不一致。**只要有任一条件缺失，所有观察量返回 NULL**，不能假设无销量。
- Mock 与 LIVE **必须区分**：Mock 样本最多返回 \`MOCK_SAMPLE\`，LIVE 即使经校验也只返回 \`OBSERVED_ONLY\`，不是认证的需求预测。真正的 Seller/Orders LIVE 接口及同步窗口覆盖需要用户在最终本地授权后另行验证。没有记录的完整窗口返回 \`NO_OBSERVED_ORDERS\`、观测数 0，但**没有速度外推**。缺失窗口返回 \`INCOMPLETE_COVERAGE\` 且观测数 NULL；两种状态不能混称。
- 只统计 \`order_item.is_current=true\` 的原始下单数量、Seller SKU 一致的 OrderItems；不把 \`NULL\` 当 0、不叠加历史订单行、不跨 Marketplace/租户、不按币种相加金额。**取消/退款/退货、售后状态、缺货造成的需求丢失、季节性、多 SKU 替代、广告效应都没有被回算**，因此不能叫净销量、真实需求或预计发货日。币种信息不用于件数指标。
- 日均保留 3 位小数（实际数值总量 ÷ 28）；提前期件数为 \`ceil(28 天下单件数 × 人工 leadTimeDays ÷ 28)\`，大于 1,000,000 件时不提供该数字。该指标是**供运营判断的历史比例示例**，不作为 \`reorderPoint\`、\`targetStock\` 或采购单数量的默认值，也不直接触发审批、FBA 在途抵扣、采购费用或 Amazon 接口。

## 当前新增接口和 UI

\`GET /api/v1/replenishment/marketplaces/{marketplaceId}/demand-signals\`

- Owner Session / 租户范围保护，不接受浏览器传入 \`tenantId\`。未知或越权 Marketplace 返回 404，未认证 401。
- 每个已配置策略的 Seller SKU 输出 \`status\`、\`sourceMode\`、\`windowStart\` / \`windowEnd\`、\`leadTimeDays\`、\`observedOrderCount\`、\`incompleteOrderCount\`、\`observedUnits7d\`、\`observedUnits28d\`、\`observedDailyUnits28d\` 和 \`illustrativeLeadTimeUnits\`。\`status\` 之一：\`UNMAPPED\`、\`INCOMPLETE_COVERAGE\`、\`INCOMPLETE_ITEMS\`、\`NO_OBSERVED_ORDERS\`、\`MOCK_SAMPLE\`、\`OBSERVED_ONLY\`。
- 只读、每店最多 500 个策略 SKU，复用 Spring JDBC 和既有 \`orders_sync_page_commit\` 防护；通过 Flyway V67 为时间窗口与当前订单项增加查询索引。
- React \`ReplenishmentWorkspace\` 现有工作台添加只读来源质量表；Zod 校验返回值，继续使用已有 React Query 缓存。原 V37 创建采购 DRAFT 的 API、CSRF、幂等键、观测锁、独立审批与人工供应商/币种/单价仍维持原样，没有额外的自动采购操作。

## 隔离环境验收

\`scripts/smoke_replenishment_v70.py\` 仅接受 localhost / PostgreSQL 18 隔离 Mock 数据库、Amazon outbound OFF 的 CI；通过本地会话创建虚构 Seller/Marketplace，插入虚构订单和页日志，检查：

- 401 未登录、404 未所属 Marketplace；
- 未映射 SKU 与未完整同步窗口两种 NULL；
- 完整 MOCK/已提交订单页与 28 件/28 天示例得 1.000 件/天，14 天示意 14 件；
- 有订单但订单行 \`NOT_OBSERVED\` → 不能计算；同一来源窗口混入 LIVE → 不能计算；
- 完整但无订单 → 明确的观测 0、无预测；PAUSED job → NULL；不能创建任何 PO 记录。
- Spring Boot 单测覆盖库存/来源负例及舍入/上限，现有 V37/V38 审批与幂等链保持独立。

**尚未完成**：可信真实销量需求预测、季节性置信区间、取消/退款净销量模型、供应商承诺交期与 FBA Arrival API 可信映射、自动优化 MOQ/箱规采购数量，以及真实 Seller/Ads/银行生产联调。后续优先复用 V42 Supplier SKU Rule（MOQ/Pack）、V37 Draft、V20 Purchasing，而非重新定义采购单。G1/G2/G3 业务签审仍需人工完成。
