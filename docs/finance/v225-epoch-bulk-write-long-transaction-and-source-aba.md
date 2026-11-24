# V225 · V113/V115 批量来源写入、长事务锁与真实来源 ABA 测试

**2026-11-24；实验仅使用本仓库 main 的 GitHub Actions disposable PostgreSQL18 + 真实 Spring Owner Session。此阶段不新增生产 Flyway，不修改 V113/V114/V115 的账务/业务触发函数或财务 API。**

## V225B 最终验收 · 精确 SHA 与真实 PostgreSQL 18 证据

**2026-11-24 技术验收已完成**：[代码提交 `e360e74be8177d472434723b3516fd6340e918c0`](https://github.com/kunlong-luo/merchivra/commit/e360e74be8177d472434723b3516fd6340e918c0) · [同 SHA 快速 CI #45325472769 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45325472769) · [同 SHA 完整 A3 #45325472831（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45325472831)。真 PostgreSQL 18 / Flyway **Job #138213939404** 原始日志再次打印 `PASS V221`、`PASS V222 REAL Spring Owner HTTP ABA`、`PASS V223`、`PASS V224`，并完整打印下面的 `V225_*` 与 `PASS V225`。Java25/Gradle、Next.js16/React19/pnpm、Chromium、Compose 和 mock-chain 同轮全部成功。

| V225B 真 PG18 项目 | 输出/测量值 | 允许的解释 |
| --- | --- | --- |
| `V225_TRIGGER_COVERAGE` | 15 张表，每表 3 个 transition trigger，共 45 + tenant.status 1；V115 已安装 | **逐表**覆盖经真实系统目录核对，没有改动 V113/V114/V115 |
| `V225_BULK_V91`：4 个事务试验、每次 20 条 V91 | **集合 SQL** INSERT 20 + DELETE 20：**2 tick**，耗时中位 **33.51ms** / P95 **33.56ms**，原始 33.56、33.48、33.12、33.53ms | 同样事实、同样最终空集，一条 INSERT + 一条 DELETE statement；小样本包含 psql 启动、不是生产 TPS |
| 同批对照 | **逐行 SQL** 20× INSERT + 20× DELETE：**40 tick**，耗时中位 **37.45ms** / P95 **38.07ms**，原始 36.96、37.71、38.07、37.20ms | 相比逐行语句，集合 SQL 在这组场景减少 **38/40=95%** statement-level version updates，**非**已证明生产吞吐加速 95% |
| `V225_V91_GUARD` | staged 来源 `review_state=review_state` 真数据库拒绝、失败 tick=0；正常 INSERT/DELETE tick=2 | 不允许以“无字段变化”绕开来源审核状态机 |
| `V225_LONG_TRANSACTION` | A1 V91 长事务对 A2 不同来源行造成真实 `Lock`；B 租户仍能独立提交，A 未提交版本对另一会话不可见；最终版本守恒 | 锁热点存在，不能仅凭批量 SQL 宣称消失 |
| `V225_FORCED_V91_HTTP` | 无写入 2/2 **200**，异租户 V91 INSERT→DELETE 2/2 **200**，同租户异 Marketplace 两次已提交来源 ABA **3/3 HTTP409** | 真 Owner Session + V222 CI-only 双 RR 条件化交错，响应无旧 plans/COGS/净利润 |
| `V225_UNFORCED_MIXED_HTTP` | 2 workers ×16 笔 = **32 个已提交批量事务**、共 **64 次租户版本更新**（全局严格守恒）；640 条来源行变动；80 GET 中 **78×200、2×409**；客户端 **P95 5.96ms / P99 6.45ms** | **合成有限到达过程**，观察值 2/80 = 2.5% 不能作为生产自然 409 比率 / SLA |

第一轮 [A3 #45324675583 6/7](https://github.com/kunlong-luo/merchivra/actions/runs/45324675583) 与本轮 7/7 的**耗时不同**（首轮集合 SQL 中位 63.19ms，逐行 SQL 中位 78.22ms），进一步证明 runner 启动/资源和短样本影响很大；不能对真实业务吞吐做基于 4 次试验的加速承诺。首轮失败是合成线程错误地把共享租户全局 epoch 当成私有计数，修复改为**窗口总量严格 +64**，并非放松全局正确性要求。保留首次失败日志与已证实的来源 ABA 409，不冒充首轮 7/7。

**阶段结论**：V225 已提供可重复的真实 PG18 技术诊断，并未交付覆盖全部费用来源的生产性能 SLO、未改变业务入账和批量审核 API、未减少既有来源审计、未验证真实平台/银行费用。后续 V226 应以这组数据为基础评估有独立审计/回滚/冲突约束的服务级批量写入与容量测试，不应单纯为省 epoch tick 合并独立的会计事件。保持 **70/100**、`realizedNetProfit=null`、真实 Seller/Ads OAuth OFF、生产 #104 **NO-GO**。

## 范围与前提

[V223](v223-tenant-epoch-contention-and-cross-marketplace-409.md) 已在小规模合成 CI 中证明跨 Marketplace 同租户存在 epoch 行锁竞争；[V224](v224-identical-update-epoch-and-concurrency.md) 通过 Flyway V114/V115 仅减少 `erp_purchase_order` / `seller_marketplace` **真正完全相同完整行集**的 UPDATE epoch tick，代码已按 [精确 SHA `74a2d04b9` / A3 7/7](https://github.com/kunlong-luo/merchivra/actions/runs/45323010754) 验收。真实 V91 来源、审核及 V165 外部来源的写入热点**仍存在**；不能以 2 张表的减噪暗示所有 15 表可安全跳过 UPDATE。

**本阶段重点是先证实写入形状的成本与正确性，再评估生产改造**，而非用没有经过读依赖图和审计约束验证的批量无变化 UPDATE 优化直接修改金融证据。源系统回执真实性、退款 COGS、费用唯一归属、G1/DEC 签署继续独立阻断生产与真实利润。

## V113 精确覆盖的 15 表依赖矩阵

| 表 | 双 RR/V165 依赖原因 | V225 变更决策 |
| --- | --- | --- |
| `finance_cost_source_v91` | V216 原币费用、审核状态及 V165 claimed document SHA | **不自动跳过**：V91 原始金额/凭据不可变，已有 BEFORE UPDATE 只允许单次真实审核状态迁移，`review_state=review_state` 必须 DB 拒绝 |
| `finance_cost_source_audit_v91` | V216 审核来源真实性链 | V92 附加 append-only 审计约束，不把一般 UPDATE 当合法幂等 |
| `finance_cost_allocation_batch_v92` | 当前 V216 外部费用重复分配计数与审核 | 状态与行约束，需先审查具体 UPDATE/审核事务，不能按通用表格自动减噪 |
| `finance_receipt_cost_plan_v112` | V216 主计划、审核、来源一致性 | 可能跨 receipt，同一批次多次操作，保持 V113 |
| `finance_receipt_cost_line_v112` | V216 当前/其他收货原币分配守恒 | 共享源归属；保留 V113 |
| `finance_receipt_cost_audit_v112` | V216 审核/复核审计证明 | 必须保留审计时间线；不可删除版本保护 |
| `erp_purchase_order` | V216 所有收货单共用 PO 币种 | **已 V114/V115 按完整 OLD/NEW 行集相等处理**；真正更新仍会 tick |
| `erp_purchase_receipt` | V216 receipt 身份与货物/PO 关系 | 跨 Marketplace 的共享范围风险，保持 V113 |
| `seller_marketplace` | V216 Scope 与 V165 source 范围 | **已 V114/V115 对严格无变化 UPDATE 减噪** |
| `finance_ad_spend_reference` | V165 人工 Ads claimed key | 与 V91 交叉碰撞，保持 V113 |
| `ads_report_import` | V165 离线 Ads 报告参考号/claimed SHA | 报告审核及来源范围，保持 V113 |
| `finance_source_event` | V165 SPAPI Finances 本地 event key | 数据来源与 tenant/Marketplace 维度，保持 V113 |
| `spapi_settlement_report` | V165 Settlement ID / 报告文档 ID / SHA | 跨报告/结算来源可能相同，保持 V113 |
| `finance_settlement_statement` | V165 手工结算单 claimed reference | 外部结算身份未核验，保持 V113 |
| `finance_cost_settlement_line_link_v94` | V165 外部报告行连接/共用归属 | 报告行碰撞和已共享金额，保持 V113 |

额外：`tenant.status` 使用独立 `WHEN OLD.status IS DISTINCT FROM NEW.status` 行级触发器，任何状态真变更都必须推进版本；本轮不删除、也不改动既有 **45 + 1 = 46** 个触发器。上述映射依据 V216 两次 RR、V165 实际 SQL、V91 和 V92 审计/审核约束；不声称全面覆盖其他业务异步写入路径、长事务用户分布或真实平台负载。

## 实施的可独立复核 A3 实验

新增 `scripts/smoke_receipt_epoch_bulk_v225.py`，仅在本仓库 main GitHub Actions、固定 `ci-v222` 仅用于 disposable 的 Owner 双 RR 同步 Bean、固定 localhost PG18 账号与 V219–V224 合成 fixture、真实 Seller/Ads OAuth 禁用、显式 `MERCHIVRA_V225_DISPOSABLE_ONLY=true` 等全部门禁满足后运行：

1. **触发器全覆盖**：逐表查询系统目录核对 15 张表分别存在 V113 INSERT/UPDATE/DELETE transition-table statement trigger，并核验 V115 成功迁移及触发器总数 46；不能只靠 46 这一全局数字掩盖漏表。
2. **同样 20 条来源记录，两种 SQL 形状**：单个事务执行一条 20-values INSERT + 一条 20-rows DELETE → 2 次已提交 epoch tick；另一个同样数量的独立来源使用 20 条单行 INSERT + 20 条单行 DELETE，同事务内合计 40 次 tick。各重复 4 次，记录耗时中位数、P95、原始样本，断言所有 INSERT→DELETE 事实完全归零和 epoch 守恒。**比较包含 psql 启动与共享 runner 抖动**，不是生产每秒事务数，也不能仅凭这一性能样本机械重写业务审核系统。
3. **V91 不可变审核反例**：在一条新 staged 记录上执行 `review_state=review_state` 必须由现有 BEFORE UPDATE 拒绝；失败 SQL 不能产生 tick；成功的 INSERT/DELETE 各产生一次。证明“看似无值变化”的 SQL 不一定是合法操作，更不能一律装上 BEFORE UPDATE RETURN NULL。
4. **真实长事务**：A1 中 V91 INSERT 开启事务但不提交，A2 同租户**另一来源 ID**写入在 `pg_stat_activity` 表示 `Lock`；B 租户独立写入可在 A 尚未提交时完成，且 A 的 committed epoch 对其他事务不可见；A 解锁后两个真实提交各 +1、最终 DELETE 清理依然守恒。
5. **真 Owner 双 RR 来源 ABA**：不用 V222 的 PO update，而是在读取 A1 receipt 期间对 A2 独立 V91 原币来源分别 INSERT 和 DELETE、两笔事务已提交，最终行集恢复原样却须真 HTTP409；异租户同样操作不能造成 A1 409，无变化对照为 HTTP200。复用 V222 的正式 API Cookie/CSRF 与只对固定虚构 receipt 启用的 CI 同步点。409 响应不能泄漏旧 plans/actualBatchCogs/realizedNetProfit，200 仍 UNKNOWN/null。
6. **非强制混合样本**：2 并发 writers × 各 16 笔事务 × 每笔批量写入/删除 10 个来源，80 次 Owner GET，记录实测 HTTP200/409、客户端 P95/P99，所有 32 笔事务均合法提交，64 个版本 tick。**这是合成负载，没有代表性到达过程、真实商品规模、第三方真实延迟、租户长事务占比或服务器饱和条件，不能称自然生产 409 率或批准 SLO。**

## 首轮 A3 运行与测试脚本修复（历史不删除）

精确代码 [`badde09dd35637c0bd04e815e3cd9e0ab71cd385`](https://github.com/kunlong-luo/merchivra/commit/badde09dd35637c0bd04e815e3cd9e0ab71cd385) 对应 [快速 CI #45324675472 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45324675472)、[完整 A3 #45324675583 **6/7 SUCCESS，PG18 FAIL**](https://github.com/kunlong-luo/merchivra/actions/runs/45324675583)。真实 PG18 Job #138211339703 的**前五组**打印如下结构化证据，不能因第六组脚本出错就冒充全部通过：

| 首轮真实 disposable PG18 输出 | 精确观测 | 解释 |
| --- | --- | --- |
| `V225_TRIGGER_COVERAGE` | 15 张、45 个 statement trigger + 1 个 tenant.status、V115 已应用 | 逐表三种 DML 都存在 |
| `V225_BULK_V91`（4 次，每次 20 条） | 集合 SQL：2 ticks / 中位 **63.19ms** / P95 **68.82ms**，原始 61.74、64.65、68.82、58.92ms | SQL batch 比较包含 psql 进程启动，不是生产 TPS |
| 同组逐行 SQL | 40 ticks / 中位 **78.22ms** / P95 **81.08ms**，原始 81.08、73.17、76.93、79.50ms | 相同 20 条来源、相同事务边界，40 个独立 SQL 语句 |
| `V225_V91_GUARD` | staged 的无变化审核 UPDATE 被数据库拒绝；失败 0 ticks、提交 INSERT/DELETE 2 ticks | 不能把不合法 UPDATE 当作省版本的优化目标 |
| `V225_LONG_TRANSACTION` | 同租户异 Marketplace 不同费用行有真实 Lock 等待；异租户在 A 未提交时可独立提交，A 待提交版本其他会话不可见 | 真 V113 tenant 锁热点与租户隔离同时成立 |
| `V225_FORCED_V91_HTTP` | 无写入 2/2 HTTP200，异租户来源 2/2 HTTP200，同租户异 Marketplace V91 INSERT→DELETE 3/3 真 HTTP409 | 实际来源 ABA 仍拦截，409 无旧财务金额或 plans |

**首轮失败原因是测试并发记账的错误假设**：2 个 writer 同时写同一个 tenant 的 10 行批量事务时，脚本 `write_sources` 在每个线程分别从“本线程开始前/结束后”的全局 `finance_receipt_read_epoch_v113` 读取版本，并错误要求差值**恰好 2**。合法的另一 writer 可能在同一窗口也提交 +2，实际差值可大于 2；PG18 日志报 `AssertionError: ('tenant epoch conservation', True, 10, 2)`。这**没有**证明版本守恒失效，且 V225 前五组已独立成功。修复后的每线程只断言 **≥2**，在两个 writer 全部完成后统一核对 **32 个事务 ×2 tick = 全局正好 64**；此精确并发窗口没有其他有意写入。修复仅涉及 Python 测试脚本，**没有**修改 Flyway、业务规则或生产 API。必须重新跑新代码 SHA 的完整 A3 才算本阶段完成。

## 版本与验收纪律

脚本及对应 CI 接入在提交 `main` 后，必须由此**精确代码 SHA** 的快 CI `py_compile`/Gradle/Next/Compose 和触发真正 PG18 的完整 A3（同时 V219–V224 老测试）分别成功；PG18 Job 的结构化输出须包含 `V225_TRIGGER_COVERAGE`、`V225_BULK_V91`、`V225_V91_GUARD`、`V225_LONG_TRANSACTION`、`V225_FORCED_V91_HTTP`、`V225_UNFORCED_MIXED_HTTP`、`PASS V225`。未验收不得宣称成功；失败按精确 SHA 追踪，必要修改须在新提交重新 A3。真实验收结果、完整运行号及数值已在文首 V225B 章节登记；未曾将首轮 6/7 当作验收。

**后续 V226 候选**：分析批量语句是否可通过已存在的业务服务接口安全使用，而不是通过降低审计精度来减少行锁；补多租户/多 Marketplace 高写入、真实 Owner GET P95/P99、POSTGRES WAL/锁采样、稀疏/高密度 V165 交叉来源的代表性压测。若考虑细粒度 epoch，先画出跨 PO/receipt/tenant.status/V165 的读写依赖及真实 ABA 正反例，不能为了规避 409 而漏掉一次真实财务变更。

项目仍 **70/100**；`realizedNetProfit=null`，本地 V91 来源不等于外部费用原件，真实 Amazon Seller/Ads OAuth OFF，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) 继续 **NO-GO**。
