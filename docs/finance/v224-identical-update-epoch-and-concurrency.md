# V224 · V114 相同数据 UPDATE 的 epoch 减噪与原有 ABA 防护

**2026-11-24 · Flyway V114、真 PG18/Owner HTTP 双读、同租户跨站点竞争与混合读写诊断。此文记录工程技术验证，不构成真实 Amazon/银行/物流核验或生产上线许可。**

## 首轮真实 A3 失败与 V115 前向修复（不得删除失败历史）

首次 V224 代码精确 SHA [`d9832ff80f20bbf911f8c3c37cc086c9885c5a38`](https://github.com/kunlong-luo/merchivra/commit/d9832ff80f20bbf911f8c3c37cc086c9885c5a38) 在 [完整 A3 #45322692498](https://github.com/kunlong-luo/merchivra/actions/runs/45322692498) 的真实 PG18 Job **FAILED**：Spring 启动、Flyway 安装 V114 后，正常 Marketplace INSERT 会执行 V113/V114 触发函数；V114 在单个 PL/pgSQL `IF TG_OP='UPDATE' AND ... old_rows` 中引用 UPDATE-only transition relations。**PostgreSQL 不保证该复合布尔表达式会短路**，INSERT 运行时出现 `ERROR: relation "old_rows" does not exist`，导致 `POST /api/v1/seller-accounts/{id}/marketplaces` 真正返回 **HTTP 500**。这不是单纯脚本断言错误，也不能借用上一版本 A3 的 green。

新 **Flyway V115**（`V115__limit_epoch_transition_multiset_to_update.sql`）以**外层独立 `IF TG_OP='UPDATE' THEN`** 将 OLD/NEW 多重集检查严格限定为 UPDATE 触发器；INSERT 和 DELETE 仍沿 V113 完整推进租户 epoch。坚持**不改已经落盘/提交的 V113 与 V114 历史迁移及校验和**。V224 测试增加真实 Seller Marketplace 临时 INSERT/DELETE 各 +1 tick 的显式负例；继续覆盖无变化 UPDATE 为 0、真正变更/ABA 为 +1/+2、rollback 为 0、真实 Owner HTTP 200/409 和跨站点锁竞争。**只有新精确 SHA 的完整 A3 通过，才可认为 V115 修复验收完成**。

## 为什么需要前向迁移

V223 [精确代码与量化结果](v223-tenant-epoch-contention-and-cross-marketplace-409.md) 的 A3 中，8 writers 在同租户双 Marketplace 的 V91 来源 INSERT→DELETE 下为 433.43 TPS / P95 65.00ms；跨双租户则为 636.54 TPS / P95 28.82ms，确认 V113 `finance_receipt_read_epoch_v113` **租户级行锁仍可能是热点**。已完成的独立实验证明同租户异 Marketplace 写入会给 V216 费用核查带来额外的**强制交错** HTTP409，但该比例不是正常生产流量 409 率。

源码检查又发现 V113 对命中行的所有 UPDATE 均推进 tenant epoch；即使 `SET currency_code=currency_code` 或 `SET timezone=timezone` 等**行内容没变**，也争抢同一版本记录并可能误触发 409。第一步应抑制这种确实没有新增事实的更新，而不是贸然把 V113 拆成 Marketplace 版本：同一采购 PO/receipt 可服务同租户不同 Marketplace；tenant.status 和 V165 冲突来源又是独立的跨站点边界，错误细分会重新引入 ABA 漏检。

## V114 的精确变化及未改变的范围

新增 **`apps/server/src/main/resources/db/migration/V114__skip_identical_purchase_scope_epoch_updates.sql`**，并通过 **V115** 纠正首次真实 PG18 A3 暴露的 INSERT transition-table 错误。禁止覆盖已发布的 V113：它前向 `CREATE OR REPLACE FUNCTION finance_receipt_read_epoch_statement_v113()`，复用原 **45 个 transition-table 语句触发器 + 1 个 tenant.status 触发器**，不改现有财务证明状态、来源权属或双 RR API。

仅当 `TG_OP='UPDATE'` 且表为 `erp_purchase_order` 或 `seller_marketplace` 时，先对 `old_rows` / `new_rows` 的**完整可见行 JSONB 多重集合**做两个方向的 `EXCEPT ALL` 对比。必须两个方向都没有任何差异才跳过版本更新；只比较主键、行数、SKU、金额和 hash 的近似方案都不允许。保留正常 SQL UPDATE 对原业务表的行为，不额外重写业务行或阻断审计。对于这两表，任意真实列值变化均继续调用 **V113 原函数**，事务内锁住 tenant revision；租户身份变动仍同时推进 OLD/NEW tenant，租户 status trigger 仍仅按状态真变化推进版本。

其余 **13 张**直接费用及 V165 输入表的 UPDATE 仍按 V113 保守规则推进版本，所有 INSERT、DELETE 不变；测试不声称 V114 解决 V223 已测出的 **V91 真正 INSERT/DELETE 高并发热点**。这是一项安全优先、范围受控的无效版本减少，不是 409/吞吐的总体优化完成。特别注意全行 JSONB 比较本身有 CPU 成本，尚未用有代表性的大量真实行、复杂列、高写入业务压测证明能全面提速。

## V224 独立的隔离 A3 正反例

新增 `scripts/smoke_receipt_epoch_noop_v224.py`，在 V219–V223 fixture 完成后运行，要求本仓库 main GitHub Actions、固定 localhost disposable PostgreSQL 18 用户/凭证、已安装的 Flyway V114+V115、全部 46 个触发器、`ci-v222` 专用 profile、Bootstrap Owner 以及 Seller/Ads OAuth 全关闭，缺一就拒绝。没有任何生产调试 HTTP 写接口或重复的财务来源系统。

- **INSERT/DELETE 防回归**：在 `seller_marketplace` 真正插入并删除无 FK 引用的临时测试站点，各推进一个 epoch，不得引用不存在的 UPDATE-only `old_rows` 或报 HTTP500。
- **真正无变化**：采购 PO `SET currency_code=currency_code`、单/双 Marketplace `SET timezone=timezone`、0 行命中的 UPDATE 均不增长 epoch，且必须保留原币和范围；被 V91 审核/不变性约束拒绝的 UPDATE 不能当作有效无变化优化。
- **两次真正提交的 ABA**：PO USD→EUR→USD，两个事务各增长 1 个版本；回滚 EUR 更改版本不能增长。该场景区别于结果字符串最终相等、也区别于 51 条计划上限造成的 409。
- **长事务热点仍存在**：一个真实 PG18 会话修改 PO 但不提交；另一个更新**不同 Marketplace 行**的真实会话必须可在 `pg_stat_activity.wait_event_type='Lock'` 观察到等待；前者提交后两写入可正常完成，版本各增长一次，再恢复原值。两方没有共享业务行，测试评估的是共享租户版本依赖。Lock 观测不可自动推导生产 P95。
- **真实 Owner in-flight HTTP**：复用 V222 仅 CI 的双 RR 中间锁定，对同租户**另一个 Marketplace**无变化 UPDATE 强制交错 3 次，期望 3/3 HTTP200+UNKNOWN/NULL；对真实 A2 timezone A→B→A 两次已提交修改强制交错 3 次，期望 3/3 HTTP409，响应无旧 plans、actualBatchCogs、realizedNetProfit。其余 V221–V223 原有 PG18、真 HTTP 安全测试仍在本次 A3 重跑。
- **非强制混合试验**：V216 Owner 读取在无写入时采 20 次 HTTP；在同租户另一个 Marketplace 有两个线程分别进行 45 笔实际 V91 INSERT/DELETE 事务期间采 100 次真实 GET，记录客户端 P95 和观察到的 409 数。**样本与业务分布都是合成的**；不对观测到的 409 数设置正向最低阈值，避免把调度偶然性作为安全断言。绝不能解释成生产自然 409 比率、数据库规模容量或 SLO。

## 验收和后续限制

须由该**精确代码 SHA** 的快 CI `py_compile`、Gradle/Next/Compose 与完整 A3 真 PG18 Job 输出 `V224_PG18_NOOP`、`V224_FORCED_OWNER_HTTP`、`V224_MIXED_OWNER_HTTP`、`PASS V224`，及 V221/V222/V223 原有回归全部通过才能宣告 V224 的技术验收通过；不能使用旧 V223 7/7 替代。每次文档追记需保留原始 SHA、运行 ID 和具体日志，不将样本合成改说成生产容量。

下一步拟继续比较 **保守 13 张来源表** 是否存在可安全优化的高频无变化变更、实测 UPDATE 完整行对比自身的 CPU/堆内存负担，以及跨站点共享 PO/receipt、tenant.status、V165 的完整依赖图。任何后续按 Marketplace/receipt 的版本分层都必须有提交/回滚、跨租户、跨站点、真实费用源 ABA 和真 Owner HTTP409 的独立负例，不能损失安全性来换低 409。

项目仍为 **70/100**；`realizedNetProfit=null`、实际 COGS/退款回转未核证，真实 Amazon Seller/Ads OAuth OFF，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) 继续 **NO-GO**。
