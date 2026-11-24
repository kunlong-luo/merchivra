# V221 · 已提交证据版本与 V216 双 RR 收货费用读时 ABA 防护

**2026-11-24 · Flyway V113 + Spring V216 双快照读取增强 / 真实 PG18 事务交错回归。无新 HTTP 财务写接口、无真实 Amazon 授权、无实际 COGS 或利润解锁。**

## 已发现的具体缺口

原 V216 的一次 Owner GET 在两个独立 PostgreSQL `REPEATABLE READ` 事务中分别读取 V112 收货费用和 V165 身份碰撞结果，然后比较两份 Java `Snapshot`。这可以拦截两次读到**不同结果**的变化，但无法观察两次读取之间**先 A→B、随后 B→A，且两次提交后业务行恢复原值**的变化。此时来源金额、批次人数、币种和 V165 状态的 Java `equals` 可能相同，旧实现就没有可靠理由触发 409。

V221 既保留 V216 对**结果本身**的独立比较，又给每一个证据读写范围增加**事务提交有序的单调版本**。新的版本只代表可见的本地数据更改，并不是外部发票真实性、财务审核、会计过账、实际批次 COGS 或 Amazon 授权证明。

## V113：按租户版本与触发器

- 新表 `finance_receipt_read_epoch_v113(tenant_id PRIMARY KEY,revision,updated_at)`；没有历史回填。既有租户无版本行时只读为 **0**，**0 不等于验证通过**。
- `finance_receipt_read_epoch_touch_v113` 使用 `INSERT ... ON CONFLICT DO UPDATE SET revision=revision+1`，更新在**业务源同一 PostgreSQL 事务内**。相同 tenant 的 revision 行锁使**提交顺序**可见，避免非事务性 PostgreSQL sequence 的“先发号后提交”误判。事务回滚也会回滚版本；提交的 A→B→A 必然有较高版本，无法回退到旧版本。
- 用 PostgreSQL 18 的**语句级 transition tables**（而不是每行一次）监听 15 张 V216 真正参与取证的表的 INSERT、UPDATE、DELETE：V91 来源及审核事件、V92 活跃归属、V112 计划/收货明细/审批审计、V20 采购单/收货单、seller_marketplace，以及 V165 的 6 个交叉来源表：finance_ad_spend_reference、ads_report_import、finance_source_event、spapi_settlement_report、finance_settlement_statement、finance_cost_settlement_line_link_v94。每次语句的每个受影响 tenant 递增一次；UPDATE 同时统计 OLD 与 NEW 的 tenant identity，不能通过移动租户绕过版本。另有 tenant.status 变更触发器，共 45+1 个触发器。
- 仅有真实成功的数据库源操作才能推动版本；没有设置来源事实为 Verified 的开关，且**未引入第二套采购/财务分摊系统**。全部业务现有 V91/V92/V112 的原触发器、不可重复归属与独立审核条件保留。

## V216 服务侧的具体修改

- 原 `scope` 存活判断从外部独立 SELECT 搬入**每一个** RR 事务，与同一个事务中的 V112 行、V165 flags 和 V113 revision 共享 PG 可见性视图。中途失去 tenant/Marketplace/receipt 范围必须按现有隐私边界拒绝。
- 每个 `Snapshot` 追加只用于服务器比较的 `committedEvidenceRevision`，不会公开给浏览器让旧版本伪造“已核证”。在读取来源及碰撞之后仍**在同一 RR 事务内**读 V113 单调版本。两次 RR 事务读取后，**版本不同先返回 HTTP 409**；版本相同仍继续比较来源计划与 V165 信号全量。如果审计/来源不一致，照 V216 既有条件拒绝。
- 版本不解决真实外部真伪：V216 成功 200 仍可能是 `LOCAL_RECEIPT_ALLOCATION_INTACT_UNVERIFIED`，`actualBatchCogs=null`、`realizedNetProfit=null`、`postingAllowed=false`。50 条完整 200 / 51 条 409 的既有 V220 规则保持不变。

## 验收证据的严格分层

1. **Java25/Gradle JUnit：** 独立测试 V216 两次 RR 使用相同 Source 和相同 V165 flags，第一版本 18、第二版本 20（模拟已提交 A→B→A），应 HTTP 409；空来源也要检测；版本不变仍只返回未核证的内部候选；没有历史 V113 行时 revision 0 仍 UNKNOWN；scope 从有效变为无效返回 404，两个 RR 事务均经真实 TransactionTemplate 构建的调用检验。该单元测试不宣称自身已做真实 PG 并发。
2. **真实 PG18 A3：** 在 V219/V220 一次性真实 Spring+PG18 关联后运行 `scripts/smoke_receipt_read_epoch_v221.py`。强制本仓库 main GitHub Actions、GITHUB_RUN_ID、固定 loopback disposable PG18 凭证、OAuth OFF。确认 V113 迁移及 46 个触发器生效，先证明业务更新在事务回滚后 revision 与原币不变、tenant B 的写入不会推动 tenant A 的 revision、V91 同 Marketplace 声称文档 INSERT→DELETE 后来源仍不存在而 revision 已增长。
3. **两条真实数据库连接的确定性交错：** 在 PG18 使用 advisory lock 同步两个连接；读者在**第一个 RR 事务中**先读 tenant epoch，随后等待；写者完成 PO 原币 USD→EUR→USD 两笔**独立提交**；读者在相同 RR 中读出的仍是第一个版本，在**第二个 RR 事务中**必须读到更高的版本。这证实 V113 对实际 PG18 MVCC 和 ABA 的能力，而不是用测试 mock 冒充真实事务。最终 Owner 真实 GET 对 V220 的另一个非空收货单继续返回本地候选及 `no-store`，没有外部 COGS/净利润。
4. **区分验证范围：** 两连接 DB 交错不等于把真实 HTTP 请求强制停在 V216 两次 RR 的边界；后者的确定性 409 逻辑由 Java 服务单元测试验证。**不能**称本轮已做真实 in-flight HTTP 暂停与交错，也不能把 V220 的第 51 项 409 当作 V221 ABA 的 409。此项可留到 V222 对隔离可控的端到端读边界再测。

## 权衡、残余风险和上线门禁

- 当前采用**租户级保守版本**：同租户其他 Marketplace 的合法来源写入也可触发正在读取的 V216 请求返回 409。它不会让错误数据成功，却可能在写入繁忙的大租户造成额外 409/行锁争用；上线前应按实际租户流量进行性能和公平性测量，再评估更细的 Marketplace/receipt 粒度，**不能为了降低 409 直接删除该保护**。
- 此机制依赖数据库业务写入通过受监控的 INSERT/UPDATE/DELETE 触发器执行，无法对超级管理员禁用触发器、TRUNCATE、手工更改迁移或业务规则外的表变更做真实性保证。V165 增减信号源表时必须同步扩展 V113 触发器覆盖；这种依赖纳入 ENG-001 工程规范。RR 第二次取样之后才发生的新提交属于新请求应重新读取的时间窗口，不能声称永久锁定了结果。
- V113 为**版本工具**不是会计政策审批。Amazon Seller/Ads 实际 OAuth 关闭，真实平台原始对账、真实采购批次证据、关税/货代/银行与 G1/DEC-06/07/08 尚待核实。项目评分 **70/100**，`realizedNetProfit=null`，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。

## 首次完整 A3 故障与修复记录

- [首轮 V221 触发提交 `106c398`](https://github.com/kunlong-luo/merchivra/commit/106c398431dd858cd6f97334b6f36910fc879ff9) 的 [完整 A3 #45316142338](https://github.com/kunlong-luo/merchivra/actions/runs/45316142338) **6/7 SUCCESS、PG18 FAIL**。Java25/Gradle/Next16/Chromium/Compose 通过，Flyway V113 已实际安装，真实 V219 和 V220 PG18 HTTP 日志也再次分别 PASS。**V221 的双连接测试在等待读者会话退出时发生 15 秒超时**；没有打印 PASS V221，因此不可称已验收。
- 原因是早期脚本让锁持有者连接执行服务器端 `pg_sleep(25)`，之后只终止 `psql` 客户端。客户端 SIGTERM 不保证 PostgreSQL 后端马上觉察连接关闭，导致 (221,2) advisory lock 可能仍保持至服务端 sleep 结束。该问题是**测试同步机制缺陷**，不是证明源版本设计通过或失败。
- 修复使用**持续打开 stdin 的真实 `psql` 持锁会话**，不用 `pg_sleep`；两笔 PO 更改提交后主动由同一个持锁会话执行 `pg_advisory_unlock(221,2)` 并退出，再收取读者旧 RR/新 RR 的实际结果。保持原有两连接、rollback、跨租户版本、V165 来源 ABA 和真实 Owner GET 全部断言；必须按新精确 SHA 重跑 A3。

## 第二轮真 PG18 成功、Compose 依赖服务限流（分开判定）

- 修复测试会话释放后，[第二轮 A3 #45316961822](https://github.com/kunlong-luo/merchivra/actions/runs/45316961822)，精确 SHA [`97e559c`](https://github.com/kunlong-luo/merchivra/commit/97e559c521b13b01ae343fde02746cfe1f415443) 的 **PG18 Job #138186054911 SUCCESS**，原始日志明确输出 `PASS V221 PG18 committed tenant epoch`；同时 V219、V220 真 Spring/PG18 HTTP 重新 PASS。V221 真 PG18 核验 46 个触发器、回滚稳定、跨租户版本不混入、V91 INSERT/DELETE 来源回到原值但版本单调、两连接旧 RR/新 RR 在 PO USD→EUR→USD 后版本不同，正常 Owner GET 仍无实际 COGS/利润。
- **同一 A3 尚非完整 7/7**：独立 Compose mock-chain Job #138186054740 在冷 Docker Gradle `bootJar` 解析 `spring-core:7.0.10` 时，Maven Central 返回 **HTTP 429 Too Many Requests**，Gradle 无法下载依赖。该项不是 Flyway V113 或 Java 编译回归，且不允许将 PG18 SUCCESS 冒充全套 CI 成功。
- `apps/server/Dockerfile` 的依赖构建增加**仅针对原始 Gradle 日志中明确的 HTTP 429/502/503/504** 的最多三次、每次短暂递增间隔的重试；普通编译/测试失败立即退出，不跳过 `bootJar` 或自动切到其他技术栈。重试日志仍可追溯。该修复在**新的精确 SHA** 的完整 Compose 和 A3 成功前不算已验收。

## 验收判定

只有该**精确 V221 代码 SHA** 的快 CI Java25+Gradle、Node24+pnpm/Next16 和真实 PG18/Chromium/Compose 完整 A3 **7/7 SUCCESS**，且 PG18 原始日志包含 `PASS V221 PG18 committed tenant epoch`，才将 V221 标为通过；任何未运行、0 jobs、SQL 错误、并发等待超时都必须按未验收如实记录。
