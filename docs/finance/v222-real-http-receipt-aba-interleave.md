# V222 · 真实 Spring HTTP 双 RR 收货证据 ABA 交错与隔离

**2026-11-24 · 真实 Spring Boot 4 / Java 25 / PostgreSQL 18 / Flyway V113。V216 API 与返回字段不变，新增仅隔离 CI 可用的测试同步点。**

## V222 已验收 · 精确执行依据

[V222 精确验收代码 `4070d9f`](https://github.com/kunlong-luo/merchivra/commit/4070d9f5a499ef0cc97537a00083fdd08309b0bb) · [快速 CI #45318993934 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45318993934) · [完整 A3 #45318994059（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45318994059)。PG18 Job #138192661605 原始日志打印 `PASS V222 REAL Spring Owner HTTP ABA`：真正已登录 Owner 的 V216 GET 第一个 RR 已提交、在 ci-v222 专用 advisory xact-lock 上阻塞，另一个数据库连接分两次提交 PO USD→EUR→USD，第二 RR V113 epoch 不同，真实 HTTP409、错误体无旧 plans/actualBatchCogs/realizedNetProfit；新稳定 GET 200/no-store/UNKNOWN，其他 Marketplace 不混入。V219/V220/V221 真实 PG18 回归同轮 PASS。全部仅限 CI 合成数据，正式环境不注册同步 Bean，实际外部 COGS 和净利润未核证。

## 为什么需要 V222

V221 的 Java 单元测试已验证两份内容相同、提交版本不同的快照会 HTTP 409；PG18 两连接测试已真实执行来源 INSERT/DELETE 以及采购单 USD→EUR→USD 后的新旧 RR 快照。但两项证明仍是**不同执行上下文**，没有在真正**已登录 Owner 的 HTTP 请求**内部，确定性暂停在两个 RR 事务之间并提交 ABA 变动。V222 专门填补这个验证边界，不新建财务写接口，不让用户凭测试金额计算利润。

## 实现方式

- `ReceiptCostIntegrityServiceV216.preview` 保留原两次 RR、来源全量检查、V165 身份碰撞和 V113 tenant epoch 比较。在两个 `snapshot` 调用之间增加**可选测试同步组件**；组件未注册（默认/本地/生产）时不执行任何额外查询或等待。
- `ReceiptCostIntegrityCiBoundaryV222` 需**同时**满足 Spring `ci-v222` Profile 和显式属性 `merchivra.ci.v222.read-boundary=true` 才能注册。在 `@PostConstruct` 还必须检查 GitHub Actions、`kunlong-luo/merchivra` 的 `main`、专用 `MERCHIVRA_V222_DISPOSABLE_ONLY=true`、固定 loopback PostgreSQL18 CI 用户/库/密码、Seller/Ads 功能开关 OFF、一次性 Bootstrap 用户。环境条件不符必须**直接启动失败**，绝不能把测试同步功能暴露到任何正常环境。
- 即使隔离 Profile 被显式启用，组件也只针对固定 UUID `00000000-0000-4000-8000-0000000056b9`（22201）的**虚构收货单**操作。它没有新增 HTTP Controller/路由、没有财务写权限、没有解锁外部费用。
- 在第一份 RR 已提交、第二份 RR 未开始的位置，测试组件通过 `TransactionTemplate` 中的 `pg_advisory_xact_lock(222,2)` 等待；锁是**事务级**，不会泄漏到 JDBC 连接池。外部一次性 PG18 的独立持锁会话主动释放 advisory lock，正常业务查询从不触发锁等待。

## 真实验收路径

`scripts/smoke_receipt_http_aba_v222.py` 仅由完整 A3 的 PG18 Job，在 V219（真实权限）、V220（非空来源）、V221（数据库 committed epoch）全部测试之后执行。脚本再次强校验主分支 GitHub Actions、一次性 PostgreSQL/Bootstrap 和关闭真实 Amazon 授权。

1. 新建仅在 CI 中存在的收货单，仍复用 V219 的真实采购/收货 FK。原采购币种为 USD、当前 V112 本地费用提案数为 0（UNKNOWN≠0）；不创建真实外部发票/货代/银行来源。
2. 使用真正的 `/auth/csrf` 和 `/auth/login` 获取独立 Owner Session/JSESSIONID，再通过 `/auth/me` 验证当前有效 Owner tenant。
3. 由一个单独 PG18 `psql` 会话先持有 advisory lock `(222,2)`；在 Python daemon thread 中真实发起 Owner 的 V216 GET；检查 `pg_locks` 对该锁出现 **granted=false**，证明 Spring HTTP 工作线程已完成第一份 RR 并停在明确的中间边界，且请求尚未响应。
4. 另一个真实 PG18 会话将同一采购单币种 USD→EUR→USD，使用**两次不同事务提交**；最终币种与初始一致但 V113 tenant revision 必须至少增长两次。已阻塞的 HTTP GET 不得提前结束。
5. 释放同一数据库会话持有的锁，第一次真正 HTTP GET 必须返回 **409**，错误体不能夹带旧 `plans`、`actualBatchCogs` 或 `realizedNetProfit`。此 409 明确来自读时 ABA 版本比较，**不是** V220 的 51 个来源上限 409。
6. 同一真实 Owner Session 发起**新请求**，在稳定版本下必须返回 `200+Cache-Control:no-store`，零计划 `UNKNOWN_NOT_ZERO`、实际 COGS/净利润仍为 null、过账 false；另一个同租户 Marketplace 的查询不触发测试同步点，也不混入成本。

## 安全、性能和不可扩大结论

这次同步点专门用于隔离 CI，不能在生产、开发者本机、自动化生产任务或公有 URL 使用。未来如果出现误把 `ci-v222` Profile 带到生产的部署配置，启动检查必须失败，而不是静默启用。测试只读真实 Controller/Session，数据变更仅用一次性 PostgreSQL 凭据注入合成采购币种。

V113 租户级提交版本偏保守：同一个租户其他 Marketplace 的写入也可能造成当前 V216 读 409。V222 证明 ABA 安全性，但**不证明**高并发情况下的吞吐、锁竞争、用户体验和 409 频率。后续 V223 应增加 bounded 写入压力和版本范围设计检查；不得为压低 409 牺牲财务来源变更的可检测性。

真实 Seller/Ads/Finances、货代/海关/银行、原始批次物理证据、G1/DEC-06/07/08 仍未核证；`actualBatchCogs=null`、`realizedNetProfit=null`、真实 Amazon OAuth OFF、生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。项目评分维持 **70/100**。

## 验收标准

**上述标准已满足：** [V222 精确验收代码 `4070d9f`](https://github.com/kunlong-luo/merchivra/commit/4070d9f5a499ef0cc97537a00083fdd08309b0bb) · [快速 CI #45318993934 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45318993934) · [完整 A3 #45318994059（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45318994059)，PG18 实际日志含 `PASS V222 REAL Spring Owner HTTP ABA`。PG18 原始日志必须明确打印 `PASS V222 REAL Spring Owner HTTP ABA`，而且测试若无法在限定时间内到达等待锁、无法提交两次真实 PO 修改、未返回真正 409 或错误体含旧金额，必须 FAIL；Java Mock 或 V221 的 DB 两连接 PASS 不能替代。
