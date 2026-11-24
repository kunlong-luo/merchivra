# V223 · V113 租户级提交版本的并发争用和跨 Marketplace 409 测量

**2026-11-24 · 性能评估及可复现测试工具；此文不代表性能结果已验收、生产负载已满足、实际 COGS 或净利润已核实。**

## V223 精确提交最终合成验收（已完成，非生产容量证明）

- **精确代码 SHA**：[`9cae5abb78aff944454351a8d9b0dde9df6dc87c`](https://github.com/kunlong-luo/merchivra/commit/9cae5abb78aff944454351a8d9b0dde9df6dc87c)。[同 SHA 快速 CI #45320774625 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45320774625)；[同 SHA 完整 A3 #45320774622（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45320774622)。真实 PostgreSQL 18 Job **#138198529460 SUCCESS**，原始日志明确包含 `V223_PG18_CONTENTION`、`V223_FORCED_HTTP409` 和 `PASS V223 PG18 V113 real-trigger contention and forced cross-Marketplace Owner HTTP409`，与 V219–V222 真 Spring/PG18 来源/ABA 回归同轮通过；Chromium、Next16、Java25、Compose 各任务成功。
- 4 组写入对照都完成提交版本守恒：单 worker 40 个事务 / 80 ticks；其余每组 8 worker × 30 = 240 个事务 / 480 ticks。全部 760 个事务来自真实 V91 INSERT/DELETE 语句触发器，来源行在事务结束时不存在。

| A3 真实合成场景 | 事务 TPS | P50 / P95 / P99（ms） | Lock 等待样本（命中/总数，最大等锁会话） |
| --- | ---: | ---: | --- |
| 1 worker、A1 | 402.44 | 2.07 / 2.65 / 17.03 | 0/1，0 |
| 8 workers、全 A1 | 452.98 | 6.33 / 66.56 / 114.45 | 4/4，7 |
| 8 workers、同租户 A1/A2 | 433.43 | 6.11 / 65.00 / 125.61 | 4/4，7 |
| 8 workers、tenant A/B | 636.54 | 8.53 / 28.82 / 75.69 | 3/3，5 |

**本次观察**：相同 8 workers，相比同租户双站点 433.43 TPS，跨两个租户 636.54 TPS（约 +46.9%）；P95 从 65.00ms 降至 28.82ms（约 -55.7%）。同租户双站点与同租户单站点都出现 p95≈65ms 和最多 7 个 Lock waiter，支持**租户版本热点可实测**，但不能排除同一 CI 主机资源、测试调度、索引/FK 等共因，不能把全部差异归结成一个版本行。80ms 采样点仅 1–4 个，不是持续锁等待率/直方图，也不能外推生产 TPS。
- **真实 Spring Owner 条件化双 RR 409**：无写入 **0/4**；异租户 V91 写入 **0/4**；同租户其他 Marketplace V91 INSERT→DELETE **4/4**；目标 Marketplace 自身写入 **4/4**。409 的响应不含 plans/COGS/net profit，正常 200 仍为 UNKNOWN/NULL。**该 100% 是人工强制命中 RR1 与 RR2 之间写入的条件比例，不是自然生产额外 409 率**。
- **决策**：V223 完成技术诊断及 A3 证据；暂时**不修改**已验证的 V113 提交有序防 ABA、V216 fail-closed 或生产门禁。下一步 V224 应复核无变化 UPDATE/批量写入导致的多余 tick，以及 PO/receipt/tenant.status/V165 跨站点依赖，再用代表性混合并发负载设定正常 HTTP409 和 P95/P99 SLO。没有真实平台/会计证据时 `realizedNetProfit=null`，评分仍 70/100，#104 NO-GO。

## 问题与设计审计

V221 / Flyway V113 在 15 个 V216 / V165 来源输入表上使用 **45 个语句级 transition-table 触发器**，另有一个 `tenant.status` 变更触发器。一次 SQL 语句对每个受影响 tenant 调用 `finance_receipt_read_epoch_touch_v113`，事务内对 `finance_receipt_read_epoch_v113(tenant_id PRIMARY KEY)` 执行 `INSERT .. ON CONFLICT DO UPDATE revision=revision+1`。这是**事务提交有序**的本地版本，而非数据库 sequence/外部凭证。V216 的两个 RR 事务分别读同一快照中的来源、冲突、范围和 epoch；值变更或版本变更均 fail-closed HTTP409。

- **性能热点**：相同 tenant 的所有来源写入更新同一 epoch 行，行级排他更新锁持有到事务结束。多 Marketplace 并发写可互相等待；同一事务中多次不同语句会多次 touch。版本递增本身不去重写业务表，也不允许绕过 V91/V92/V112 不变性、原币与独立审核。
- **额外 409**：同租户 Marketplace B 的合法且与目标收货无关的来源变更，仍可能使 Marketplace A 的 V216 Owner GET 两次 RR 之间的 epoch 不同而返回 409；这是保守误拒绝，不是来源真实性证明。另一个**租户**的版本变化不影响该请求。
- **不能直接拆成 Marketplace 版本**：`erp_purchase_order` 和 `erp_purchase_receipt`、租户状态等作用范围并非一对一 Marketplace（V219 一张本地 receipt 可以在同租户不同 Marketplace 范围内只读）。V165 新增来源时也要全量覆盖。错误地把共有来源归到一个站点会重新造成 V221 已修复的 A→B→A 漏检。
- **不能修改 Flyway V113 历史迁移**或用 PostgreSQL sequence 的非事务发号代替当前提交有序标记。优化必须是新迁移/可审计独立验证，且确保无旧版本漏读。

## V223 新增的可执行测量

`scripts/smoke_receipt_epoch_contention_v223.py` 使用现有完整 A3 中 V219–V222 的 **真实、隔离 PostgreSQL18、真实 Spring Owner Session 和 V222 CI-only RR 边界**。放在 `ci.yml` 的 V222 之后；正式环境不会注册 V222 组件，也没有新增 HTTP 写接口。该脚本使用固定本仓库 main/GitHub Actions run、固定 localhost disposable DB 用户/口令、OAuth OFF、Flyway V113 与 46 个触发器、测试租户/Marketplace/收货 ID 等**全部门禁**，缺一即拒绝。测试用 V91 尚未复核的虚构费用来源进行 INSERT 后 DELETE，**在同一事务提交并确保来源行最终不存在**，因此实测的是已部署的原语句触发器而不是仅直接调用触发函数或仅看 Java Mock。每次提交应递增 epoch 恰好 2 次，按 tenant 验证数量守恒。

| 场景 | 数据规模 | 需要记录 |
| --- | --- | --- |
| 单写入者、单站点 | 1 worker × 40 个事务 | 低并发起点 TPS/延迟 |
| 同租户单站点 | 8 workers × 30 个事务 | 热点行竞争对比 |
| 同租户双站点 | 8 workers × 30 个事务，A1/A2 各 4 | 同 tenant、不同 Marketplace 是否仍竞争 |
| 两租户隔离 | 8 workers × 30 个事务，tenant A/B 各 4 | 版本隔离下并发可扩展性的相对对照 |

输出每场景实测 `transactions_per_second`、`p50_ms`/`p95_ms`/`p99_ms`/`max_ms`、提交次数和 V113 tick 守恒；`pg_stat_activity.wait_event_type='Lock'` 的 80ms **采样**另外记录采样数、观测到锁等待的样本数/峰值会话数。采样只是观测到的 **Lock 等待代理指标**，不保证捕捉短锁、不能据此计算 PostgreSQL 完整锁等待时长。为减少客户端重复启动差异，每个 worker 使用一个持续连接的真实 psql 会话，独立事务提交和无共享 V91 业务行；单条请求延迟含 psql/网络往返开销，不能当 Spring API 的真实生产写入 P95。测试总计 760 个合成事务；不能仅凭 CI 的 TPS 推断具体大租户硬件容量。

## 真 Owner HTTP 条件化 409

复用 V222 的 **仅 CI 开启**的 PostgreSQL advisory xact 锁同步点，在**第一个 RR 已提交、第二个 RR 尚未开始**的同一真实 HTTP GET 之中对四种条件各做四轮测试：

1. **不写入**：预期 0/4 HTTP409，200 仍为 UNKNOWN、实际 COGS/净利润 NULL。
2. **其他 tenant B 修改自己的 Marketplace**：预期 0/4 HTTP409，不应改变 tenant A 的 epoch。
3. **同 tenant A 的其他 Marketplace A2 进行 V91 INSERT→DELETE**：预期 4/4 HTTP409，即使 A1 收货数据未变，这是量化的**额外** 409 来源。
4. **同 tenant A 的目标 Marketplace A1 进行 V91 INSERT→DELETE**：预期 4/4 HTTP409，保留 V221/V222 ABA 安全屏障。

每次检查源版本增长、真实 Owner HTTP 状态码和 409 响应不能泄漏 plans、actualBatchCogs 或 realizedNetProfit。这些 0/4、4/4 是**强制交错时的条件比例**，绝不能表述为运营负载的自然 409 率。真实环境的自然 409 基准还需要有代表性的多店铺流量、真实事务时间分布、GET 调用分布及无敏感数据的延迟直方图，尚未具备。

## 验收纪律与优化门槛

- 新代码是否执行成功，以当前**精确 main SHA** 的 GitHub 快 CI `py_compile`、完整 A3 实际 PG18 Job 原始输出 `V223_PG18_CONTENTION`、`V223_FORCED_HTTP409`、`PASS V223` 为准；运行未结束、脚本失败、A3 任一相关 job 失败均不得称 V223 性能已验收。上一阶段 V221/V222 的 green **不能继承为 V223 测量结果**。
- 如果同 tenant 高并发 TPS/p95 明显恶化或 Lock 等待占比高，先补生产代表性流量的**受控匿名合成回放**，独立确认瓶颈在 epoch 行锁而非 V91 索引、客户端或其他 FK 锁；再考虑将不依赖收货范围的来源细分版本。先证明共享 PO/receipt/tenant-status、跨站点 V165 来源均被检测，再补 DML→RR→HTTP 的 ABA 负例，才能替换 tenant epoch。不得降低安全性来追求性能分数。
- 生产上线需要明确批准的吞吐、P95/P99、409 SLO、回滚窗口和独立数据库容量证据。V223 A3 的四组固定配置是**诊断基线**，不自动提供生产 SLO，也不自动调优或放行。正常流量 409 比例与损失重试成本目前**未知**。

项目评分保持 **70/100**；`realizedNetProfit=null`、实际 `actualBatchCogs=null`，真实 Amazon Seller/Ads OAuth 关闭，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) 维持 **NO-GO**。
