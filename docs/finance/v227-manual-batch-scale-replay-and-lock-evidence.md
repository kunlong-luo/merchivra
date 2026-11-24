# V227 · 手工原币来源批次规模、重放、跨 Marketplace 竞争与证据边界

**2026-11-24；仅 disposable main GitHub Actions 的真实 PostgreSQL 18 / Spring Owner Session，不是生产容量证明。** 本阶段优先做既有 [V226 安全批量暂存 API](v226-bounded-owner-manual-source-batch.md) 的规模与并发回归，并保留 [V225 来源版本与 ABA](v225-epoch-bulk-write-long-transaction-and-source-aba.md) 完整规则。没有新增费用源真实性证明、批量审核、落地成本或财务过账，也不启用 Seller/Ads 真实授权。

## 检查的现有实现与缺口

`CostSourceReviewController.stageManualCostSourceBatch` 已有 `@Operation(operationId="stageManualCostSourceBatch")`，请求为 1–20 条 `CostSourceReviewService.Draft`，响应顺序与输入对应，`Cache-Control: no-store`、Session/CSRF、Owner 授权均复用单条来源。Spring 单事务先逐条校验所有字段，再锁当前 `seller_marketplace` 行，查找完全相同 key/immutable facts 的回放，**仅对新来源**发出 1 条多 VALUES INSERT 以及 1 条每源独立审核事件 INSERT。旧来源重放不写数据库，但新请求不能给旧凭据换金额/sha。这个链路已经在 V226 真 PG18 A3 [run_attempt=2，7/7](https://github.com/kunlong-luo/merchivra/actions/runs/45326774310) 中按 20 条全新、20 条全部重放、5 旧+3 新、冲突回滚与权限案例验证。

**V227 仍需补证**：1、5、20 三种大小的同一真实 API 延迟/数据库 WAL 观察；0%、50%、100% 重放占比的源/审计/版本守恒；两个不同 Marketplace 的真实 Owner Session 并发下**同租户共享** V113 epoch 不能发生失计；Marketplace `FOR UPDATE` 真实 HTTP 被阻塞后应在事务释放后恢复，不借绕过行锁提升吞吐。只有测量、没有授权生产自然负载分布和目标 SLO，不能声称 V113 性能问题已经消失。

## 新增可重复实验

脚本：`scripts/smoke_cost_source_batch_scale_v227.py`，在 `.github/workflows/ci.yml` 的真 PG18 链路中**接在 V226 之后**运行，且快速 CI 运行 `py_compile`。它首先逐项核对不可删除的门禁：本仓库 `main`、GitHub Actions、固定 localhost 的一次性 PG18 凭据/登录用户、`ci-v222` 合成数据、Flyway V115、V113 的 46 触发器、Seller/Ads OAuth 明确 OFF，以及 `MERCHIVRA_V227_DISPOSABLE_ONLY=true`。绝不在任何用户本地真实账本运行。

| 阶段/输出前缀 | 真 SQL / HTTP 正反例 | 预期严格断言 |
| --- | --- | --- |
| `V227_SIZE_REPLAY` | 每种 1、5、20 条各重复 4 组全新 + 完全重放；分别计算 HTTP 调用包含 CSRF 的耗时中位数、P95 和两次 PostgreSQL WAL LSN 差 | 所有新来源 UUID 唯一、每源独立 1 STAGED 审计、整批+2 tenant epoch；完整重放同 UUID/audit、epoch+0；不预设耗时谁快 |
| `V227_REPLAY_RATIO` | 固定长度 20 条，0/10/20 已存在的事实，即 0%、50%、100% 原样重放 | 完整顺序、20 个不同 UUID、20 个独立 STAGED audit；新来源至少 1 条则+2，100% replay 0；不重复归属 |
| `V227_CONCURRENT_MARKETPLACES` | 两个真正登录的 Owner Session 各在两个不同 Marketplace 并发发出 8 个 5 条新事实 batch，共 16 个 HTTP 写入事务、80 条 V91 来源 | 16×2=**全局 epoch 精确 +32**，80 个唯一 source UUID/80 个唯一 STAGED audit，V216 GET 依旧 UNKNOWN / `actualBatchCogs=null` / `realizedNetProfit=null`，报告请求 P95/P99 |
| `V227_LOCK_WAIT` | 外部 PG18 连接通过真正 `SELECT seller_marketplace ... FOR UPDATE` 持有一条 Marketplace 行；随后 Owner 发起真实 POST /batch，检查 `pg_stat_activity.wait_event_type=Lock`，持有方 COMMIT 再恢复 | 不出现未经锁许可的部分写入，释放后 HTTP200、来源与审核各 1 行、epoch+2 |
| `PASS V227` | V221–V226 原有真实 PG18/Owner HTTP 同轮再次通过 | 没有移除 ABA409、CSRF、租户隔离、币种原样、审核独立性 |

**注意测量边界**：统计的客户端耗时含申请 CSRF token 与本地 HTTP 请求。PostgreSQL `pg_current_wal_lsn` 是**集群全局 WAL 指针**，两个采样点之间可能有别的业务与 CI 进程写入；只可称“区间全局 WAL 字节观察”，绝不是每批本身的归因值。每组只有 4 次执行，V227 的并发样本是人工同步起跑与小批次序列，P95/P99 和 409 都不能称生产样本或已批准 SLO。若实际 409 出现，必须核对是否因 V216 tenant epoch ABA 才 fail-closed，不能将 409 当必须下降为零的目标。

## 验收与后续优先级

需要**精确代码 SHA** 的快速 CI（Java25/Gradle/Next16/React19/pnpm/Compose + 语法检查）与完整 A3 **7/7**，且真实 PG18 Job 打印全部 V227 结构化行和 `PASS V227`。失败不能用上一版 V226 A3 代替；保留原始失败与修复的提交/运行/重新验收历史。最终实际耗时、WAL、并发 P95/P99 以真 CI 日志补写，未跑前不能当通过。

**后续候选 V228**：在实际体量已证实的前提下检查是否需要 OpenAPI/Orval 自动生成客户机与可恢复的手工来源批量导入 UI；需要明确 Owner/CSRF、20 条上限、无局部成功、冲突回放及来源未核证标识。不能基于这轮数据开启真实 Amazon/Ads、取消独立审核或发出“已盈利”报表；实际利润来源要等待原始外部结算/物流/税单/WMS 与 G1/DEC 会计政策签署。

项目评分 **70/100**、`realizedNetProfit=null`，真实 Amazon Seller/Ads OAuth 关闭，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。
