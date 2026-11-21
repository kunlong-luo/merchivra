# V184：财务费用证据列表批量读取与保守原币利润门禁

状态：**业务 PR 已合并，精确 main SHA 完整 A3 7/7 通过**。

V184 批量费用证据查询验收（2026-11-21）：业务 [PR #186](https://github.com/kunlong-luo/merchivra/pull/186) 合并 SHA `c4a5033319e68c1c59b186928bebf93125d5fa7c`；合并前第二次 [CI #44863451425](https://github.com/kunlong-luo/merchivra/actions/runs/44863451425) **SUCCESS**（Java25/Gradle、React/Next16/Vite、Compose；首次 CI 旧 V183 静态测试预期失败后已修复）；精确业务 main SHA [完整 A3 #44863540279](https://github.com/kunlong-luo/merchivra/actions/runs/44863540279) **run_attempt=1，7/7 核心任务 SUCCESS**（PG18/Flyway 合成 HTTP、Java、React/Next16、Chromium、Compose 全 HTTP/安全/恢复；独立设计专项按策略 SKIPPED）。V184 将当页最多 50 条 V165/V182 逐条证据扫描从约 100 次降为两次批量范围 SQL；V183 全期间统计、租户隔离、V94 审计/V95 冲突/V102 缺证、原币金额和不可过账门禁均保留。**没有外部 Amazon/银行真实性认证，不允许 V162/V64 真实净利润或生产放行；Seller/Ads OAuth OFF、生产 #104 NO-GO。**


## 已核查问题

V183 全期间阻断清单是独立、非分页的聚合，但列表中每条费用来源依次调用 V182 `assess()`；每次又对 V165 八类交叉来源信号和 V94/V102 审计数据各查一次数据库。满页 50 个来源导致 **100 次数据库证据查询**，其间还有固定的类别金额、全期间阻断和列表基础 SQL。大量来源会增加端到端延迟，不能只靠增加页面或者将结果改成未经认证的利润卡片。

## V184 实际方案

- 保留 V183 同一个 `REPEATABLE_READ`、Owner Session、租户和 Marketplace 过滤、最多 366 天日期范围、每页 50 条/51 条游标探测。
- 使用 `CostIdentityReconciliationService.previewBatch()`：一次有界 `IN` 查询仍完整执行原 V165 的八类重叠信号、V95 共用行计数；强制仅 1–50 个非空唯一来源 ID，返回条数、来源集合必须精确一致，否则 404/冲突。
- 使用 `CostAttributionEvidenceService.assessBatch()`：一次按照来源 `GROUP BY` 的 V94 关联、独立审核+不可变审计、V102 创建时冻结、V95 共用行汇总。无关联记录使用 `count(l.id)` 而非 `count(*)`，防止 LEFT JOIN 的空行误当成一个链接；禁止把历史缺快照填补成已证明费用。
- 在 V183 `CostProfitReadinessService.report` 只调用一次 `assessBatch`，50 个来源的证据数据库查询从 100 次降为**2 次**；空页不进行证据批查。全期间 `evidenceBarriers` 继续独立、完整且不依赖游标。
- 保留单个来源 `assess()` API 和 V182 完整判定 `classify()`；不改变原币金额、冲突优先、外部真实性标志、批准/过账逻辑，不引入缓存或异步一致性问题。

## 验收

Java 25 单元测试与静态不变量：批大小、重复/空/不属于租户的来源拒绝、无关联 count 防虚增、原 V94 不可变审计匹配和所有无财务写入限制。现有 V165/V182/V183 合成 PG18 HTTP fixture 验证无链接、冻结单行+审核、共用行冲突、52 条来源跨页、空月份以及原币类别汇总，不得将 `SUPPORTING_LOCAL_METADATA_UNVERIFIED` 升级为认证扣费。

CI 全绿后合并业务 PR，须按**精确业务 main SHA**执行完整 A3（PG18/Flyway、Java25/Gradle、React19/Next16、Chromium、Compose HTTP）。此前 V183 A3 成绩不能直接移用。

**真实 Seller/Ads OAuth OFF；生产 #104 NO-GO。** 即便全部本地费用获独立 Owner 审核，仍无外部来源认证、银行核销和 G1 财务政策，不可计算已核实净利润。
