# V183：全期间财务真实性阻断清单与 V182 逐来源归属证据联动

状态：**业务已合并，精确 main SHA 完整 A3 7/7 SUCCESS**。没有引入真实 Amazon/银行/WMS 外部凭证认证，不是可以入账的利润报表。

## 实际 CI、两次失败与最终验收归档

**V183 全期间费用真实性阻断与利润中心证据联动：技术 A3 7/7 完成（2026-11-21）**。业务 [PR #182](https://github.com/kunlong-luo/merchivra/pull/182) 合并至 `main` SHA `9b55c1c306598d14717340ed9d8c7addb1a67571`，合并前 [快速 CI #44861901027](https://github.com/kunlong-luo/merchivra/actions/runs/44861901027) **SUCCESS**。其首次 [完整 A3 #44862039686](https://github.com/kunlong-luo/merchivra/actions/runs/44862039686) **FAILED**：Chromium 157 已宣布 loopback DevTools 端口，但 Linux runner 未写 `DevToolsActivePort`，浏览器尚未进入 UI 断言；其他 6/7 核心任务（含真实 PG18/Flyway 与合成 HTTP）SUCCESS。[修复 PR #183](https://github.com/kunlong-luo/merchivra/pull/183) 合并 `190879f6ff9d1a97c556ef7d18be8e5fcedb89df`，保留完整浏览器检查，增加端口发现回退及 Node 安全单测；其 [A3 #44862416930](https://github.com/kunlong-luo/merchivra/actions/runs/44862416930) **仍 FAILED**，这次已通过 Chrome/Vite 浏览器断言，但 Next 工作区旧 V169 合成响应缺少新增的 V183 `evidenceBarriers`/逐来源字段，前端 Zod 正确拒绝，费用来源按钮不可显示。随后 [修复 PR #184](https://github.com/kunlong-luo/merchivra/pull/184) 将合成财务响应升级到真实 V183 严格 schema，并新增整期未认证费用及本地冲突的 Next 浏览器断言，合并 `main` 精确 SHA **`cfed3a9e0a91f7bacdb7a77d4375fb37c9ab943e`**。该精确 SHA 的 [**最终完整 A3 #44862773635**](https://github.com/kunlong-luo/merchivra/actions/runs/44862773635) **run_attempt=1、7/7 核心任务 SUCCESS**：Java25/Gradle、PG18/Flyway、真实 Spring HTTP、Next16/React19、Chromium Vite/Next 全视口、Compose 配置及 Compose HTTP/Session/CSRF/恢复。独立设计专项按原有 CI 策略 SKIPPED，不是失败。

**事实边界不可上调**：V183 的 `evidenceBarriers` 是整期间、非 50 行分页的本地证据缺口计数，V182 的 `SUPPORTING_LOCAL_METADATA_UNVERIFIED` 不是外部 Amazon 费用真伪认证。无关联不证明零成本；共用行不证明双重扣费；`eligibleForV162OrV64Profit=false`，`realizedNetProfit=null`，不自动解锁费用入账。没有真实 Amazon Seller/Ads/银行授权或外部真实性签署；**Seller/Ads OAuth OFF、生产 #104 NO-GO**。

## 为什么要做 V183

V162 的 `FullCostCoverageEngine` 只有纯数学/人工提供的预览输入，还不能直接读取可核实的全成本来源。V169 的月度成本准备度已有原币人工金额与 V95 共用行检测，但来源列表每页 50 条；在过去的实现里页面无法展示**整个期间**未审核、未关联、历史缺 V102 或同一结算行被不同人工费用认领的统计。V182 可以给单笔费用保守判定，但不能代替整月所有费用的风险盘点。

V183 在**已有** V169 API 和 Next.js 16 费用工作台上增加全期间阻断统计，并复用 V182 逐来源判定，不增加另一套审核表、费用入账、净利润数字、会计政策或对外授权。

## 现有 API 的向后兼容新增字段

`GET /api/v1/finance/marketplaces/{marketplaceId}/cost-profit-readiness?periodStart=YYYY-MM-DD&periodEndExclusive=YYYY-MM-DD[&beforeDate=YYYY-MM-DD&beforeId=UUID]`

新增顶层 `evidenceBarriers`，其所有计数从**同一 PostgreSQL REPEATABLE READ 快照中的完整日期区间**读取，与当前页码、`LIMIT 51`、游标无关：

- `sourceCount`：此业务日区间全部 V91 人工费用来源数量；也包括不在当前来源页上的记录。
- `metadataReviewPendingSourceCount`：尚未处于 `REVIEWED_UNVERIFIED` 的人工来源数。
- `noSettlementAssociationSourceCount`：无 V94 结算行关联的来源数。**仅代表本地关联不足**，非 Amazon 仓储发票、物流供应商发票不要求天然存在 Amazon settlement 行，不得因此定性为错误扣费。
- `missingFrozenCaptureSourceCount`：至少一条关联缺创建时 V102 快照的来源数。旧 V94 链接绝不能从当前可变元数据补做历史真值。
- `sharedSettlementLineSourceCount`：至少一条结算行被别的费用来源共用的来源数；不等于真的双重扣费。
- `pendingLineReviewSourceCount`：存在 V94 `OPEN` 独立复核待办的来源数。
- `missingLineAuditSourceCount`：至少一条已核查 V94 决定缺少与最终状态/审核人相符的审计事件的来源数。
- `multipleLineClaimsSourceCount`：一条人工费用关联多条结算行，仍缺完整分摊归属依据。
- `externallyUnverifiedSourceCount`：本阶段等于全部人工来源数，因为 V91/V92/V94/V102 并未提供独立费用真伪认证，**不因为 V182 本地支持而减少**。
- `blockerCodes`：即使 `sourceCount=0`，外部八类费用覆盖、银行结算与真实利润核算缺口仍保留。不能将空库解释为 0 费用。
- `eligibleForV162OrV64Profit=false`：始终不自动打通 V162/V64 真实净利润。

新增逐来源 `localAttributionStatus`、`localAttributionReasonCodes`、`frozenIdentityCount`、`missingCaptureCount`、`independentReviewAuditComplete`。它们由现有 V182 Service 实时计算（继续使用 V165 8 类身份重叠信号及 V94 审核、V95 共享、V102 冻结快照），不在前端重新推断真实性。旧的 `localIdentityStatus`、`approvalHeld` 也直接复用 V182 当前快照。逐来源返回仅最多 50 条/页，但全局 `evidenceBarriers` 不分页。

### 数据与性能约束

- 一个受租户、Marketplace 和业务日期绑定的 `CROSS JOIN LATERAL` SQL 聚合完整 V91 范围；关联的 V94/V102 各行先聚成计数再按费用来源计数，避免多条关联重复放大来源。
- 维持现有期间至多 366 天、Owner Session、`Cache-Control: no-store` 和 `REPEATABLE_READ` 一致性；不存在/非活动 Marketplace 返回 404。
- 读取发生在 PostgreSQL，不新增 Flyway 表、回填、伪造平台认证、伪造审计、外部网络请求或额外页面卡片。各币种人工申报金额仍在 V169 `buckets` 以类别/原币展示，不将 USD 和 EUR 合并。
- V182 每页的 50 条证据检查可能会增加数据库查询；下一步如果来源量大，应在保持相同判定语义的前提下做批处理与查询计划分析，不能用减少信号或跳过后几页换性能。

## Next.js 工作台

延续现有成本准备度面板的原币分组表，直接在顶部展示**全期间非分页**的来源数、各类未完成证据数量和 `V162/V64` 真实利润阻断说明。单笔来源显示“本地归属冲突”“本地证据支持但未认证”或“尚未证明”，审核抽屉显示 V102 冻结/缺证、V94 独立复核审计状态与原因代码。零来源明确为**未知，不是零成本**。

## 验收及安全边界

- Java25：检查整期 SQL 不引用 `beforeDate/beforeId/LIMIT`，租户/站点/范围隔离、V102/审计/共享行关联和只读、不生成真实利润；现有 V182 分级测试仍需全部通过。
- 真实 PG18 + Spring HTTP 合成 fixture：本期来源计数与 SQL 全区间核验；无凭证仍无利润；已审／未审核来源、真实库共用行、各行 V182 API 与 V169 对齐；追加 52 个来源后两页相同的全期间阻断统计；空月份保留全成本未知。
- 前端 React 19、Next.js 16 正式目标、Vite 暂时回退、Zod 新字段 fail-closed、Chromium/Compose A3。

**V183 不是可认证的最终成本计算阶段。** V162 `REVIEWED_COST` 不得直接接入 V91 `REVIEWED_UNVERIFIED`、V92 `APPROVED_UNVERIFIED` 或 V182 `SUPPORTING_LOCAL_METADATA_UNVERIFIED`。真实来源覆盖、G1 会计口径/汇率/税费政策、真实 Ads/Finances/银行授权与核销、退款/退货成本回转、SKU/订单/期间正确分摊仍缺外部授权及完整证据。保留 **Amazon Seller/Ads OAuth OFF；生产 #104 NO-GO**。
