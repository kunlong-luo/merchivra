# V220 · 已审核本地人工费用计划的真实 HTTP 读时守恒和 51 项全量阻断

**2026-11-24 · 真实 Java25/Spring Session/PostgreSQL18 + Flyway V91/V92/V112/V165，复用 V216 Owner-only GET；不新增财务 API、生产权限、会计写入、Flyway 或新的费率算法。**

V219 已使用真实 Spring/PG18 多租户/混合角色 Session 验证 401/403/404/200，但其合法 Owner 返回的是**零份** V112 收货费用计划：这能证明权限，却不能证明真实后端对**非空已审核人工提案**的原币金额、独立审核、来源碰撞、同一来源不可重复归属、读时失效及全量上限的 HTTP 行为。V220 补的是这部分遗漏，不复制一套 V91/V92/V112 财务来源。

## 范围及程序

新增 scripts/smoke_receipt_cost_actual_http_v220.py，与此前通过的 scripts/smoke_receipt_cost_real_http_v219.py 顺序执行。强制 GITHUB_ACTIONS、本仓库 main、GITHUB_RUN_ID、固定的**一次性** localhost PG18 CI 数据库、临时用户密码、Seller/Ads OAuth OFF、PG18 Flyway V112、V219 已创建的合成 A Owner/A 员工-B Owner、采购单、收货单与 Marketplace 实体，拒绝重复使用 V220 预留 ID。所有记录都是**隔离测试合成元数据**，不会进入真实仓库或外部财务平台。

使用**真实 V91/V112 业务表与已有数据库触发器**，不是用 TEMP 表绕过触发器：

1. 在租户 A 的真实 V20 收货单上，创建第一笔来源 USD 100.000000，分别向两个精确原采购收货单身份分配 USD 40.000000 和 USD 50.000000。来源必须由另一位人员完成 V91 REVIEWED_UNVERIFIED 元数据审核，V112 提案经独立 Owner 审批为 APPROVED_UNVERIFIED，并保存数据库写入的 V112 审核事件及 V91 审核事件。
2. 创建第二份 USD 30.000000 的独立人工来源，只给当前 receipt 分配 USD 10.000000；在**相同 Marketplace** 额外保存相同**用户声称的**文档摘要，形成 V165 潜在来源碰撞；在**其他 Marketplace** 保存与第一笔相同摘要，只为证明隔离规则。相同摘要不等于银行/发票已核实的重复扣费。
3. 真实已登录 Owner 对原 V216 GET 应收到 **200+no-store、localPlanCount=2**。第一份候选收货额 40、全收货额 90、globalReceiptCount=2、原币 USD、来源重叠信号 0；第二份候选收货额 10、来源重叠信号非零，明确标记潜在碰撞 HOLD。所有实际 landed cost、实际 COGS、真实净利润及自动过账标志继续 false/null。不同同租户 Marketplace 即使存在相似文档，也不能继承其他 Marketplace 的这些计划；跨租户 receipt/Marketplace 404。
4. **V92 原属权冲突**：尝试用第一个 V112 活跃提案的同一 V91 source 创建 V92 PERIOD 费用分配，必须由现有 PG18 V112 双向排斥触发器直接报错；随后真实 GET 仍返回原来完整且未验证的计划，不产生双重费用。
5. **原币证据读时失效**：临时把现有 V20 采购单币种 USD 改为 EUR，再真实 HTTP 读取必须标为 LOCAL_RECEIPT_EVIDENCE_STALE_OR_UNAUDITED、包含 RECEIPT_LOCAL_SOURCE_AMOUNT_CURRENCY_OR_V92_DRIFT；不允许修改 V112 固定来源快照，也不允许凭过去 APPROVED_UNVERIFIED 认为成本有效。将本地测试采购单币种恢复 USD 后必须重新读得原先的*本地守恒未证实*状态，仍没有实际成本。
6. 在同一个 receipt 上创建共 **50 份**合法来源、计划和不可变分摊明细。真实 GET 必须精确地返回完整 50 份唯一 plan/source，无游标或部分截断、no-store、全部财务核证 false/null；第 **51 份**一经创建，重复 GET 必须是 **HTTP 409**，响应中绝不能夹带前 50 份旧计划/金额或假实际 COGS。
7. 检查在数量上限已阻断时，同租户的其它 Marketplace 仍是 UNKNOWN 非零；已登录 A 非 Owner 对该费用接口仍是 403、B Owner 跨租户请求仍是 404、匿名为 401。所有业务费用读取仍只走 GET，测试仅使用 localhost 真实登录与一次性数据库种子。

## 验收与禁止扩大结论

- 将 PG18 V219、V220 测试顺序固定在全套财务/备份/PITR 专项之后；快 CI 对新增 Python 脚本做语法门禁，沿用 Java/Gradle、Node24/pnpm、React19/Next16 与已通过的 V218 Chromium。
- 只有实际 GitHub Actions **精确 SHA** 的 PostgreSQL18 Job 打出 PASS V220 real Spring/PG18 V112 nonempty Owner HTTP，以及完整 A3 七项全部 SUCCESS，才能标记 V220 验收。此前 V219 7/7 不能替代本次非空计划测试。
- V220 所有 V91 文档摘要、来源号、供应商/仓库/收货、审核人与金额都是**合成当地记录**，没有 Amazon Seller/Ads、Finances/Settlement 报告原件、第三方仓库签名、货代/关税、银行凭证、汇率/税务/资本化政策 G1/DEC-06/07/08。即使本地原币守恒、账户权限和重复归属拒绝全部通过，也只表明内部数据契约正确，**不是已发生的 COGS、退款 COGS 回转或可确认净利润**。
- 该合成顺序不会验证实时并发触发的双 repeatable-read 快照 ABA 漂移 409；并发/快照稳定性应由下一阶段 V221 继续独立验证，不能用 51 项人口上限的 409 冒充 ABA。

**项目评分 70/100**，realizedNetProfit=null，Amazon Seller/Ads 真实 OAuth 关闭，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。
