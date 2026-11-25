# Merchivra V215 · 原采购收货单级人工费用归属契约

**2026-11-24 · Flyway V112 / 本地人工提案、无公开写入 API、无真实财务过账。** V215 只把 V91 原币人工费用提案绑定本地采购收货 ID，并显式阻止 V92 的重复归属，未核证任何真实 landed cost。[精确代码 `709fbd0`](https://github.com/kunlong-luo/merchivra/commit/709fbd0bd702af2694d9f351b6e0cbb367b7a6c1) · [同 SHA 快速 CI #45295653983 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45295653983) · [同 SHA 完整 A3 #45295654148（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45295654148)。PG18 日志通过 V112 真实已安装触发器的 TEMP/ROLLBACK 合成回归，不等于外部真实成本核证。

## 已有来源与证据等级

V20 的 erp_purchase_receipt.id 是本地采购收货身份；V106/V213 的领取数量守恒不是 Amazon/WMS 实际出库证明。V91 保存来源声明、原币、金额、人工文件摘要和内部非同人审核，但没有收货单 FK；V92 将同一费用来源唯一地分至 SKU、订单或期间；V186 防止期间统计重复。V215 不新增第二套费用账本，不修改历史迁移或已冻结利润。

## Flyway V112 新增数据库级契约

1. finance_receipt_cost_plan_v112：同租户同 V91 source 全历史仅允许一项计划，包括 REJECTED 历史。仅允许已内部 REVIEWED_UNVERIFIED 且类别为 INBOUND_FREIGHT、IMPORT_DUTY、PAYMENT_AND_FX 的人工源；数据库锁定并自行捕获不可变源金额和币种。
2. finance_receipt_cost_line_v112：复合 FK (tenant_id,receipt_id) 指向 V20 原收货行，不根据 SKU/日期推断；采购单原币与来源原币必须一致。金额使用 NUMERIC(20,6) 正数，每 plan 最多 100 张收货单，单收货仅一行；对同一源行锁下累计 SUM(amount) 不超过原始金额，未分配尾额不虚构为已资本化成本。
3. V92 与 V112 双向互斥：V112 INSERT 先锁 V91 再查 V92 活跃 PENDING_REVIEW/APPROVED_UNVERIFIED；V92 的新 AFTER INSERT/UPDATE 触发器反向拦截活跃 V112。复用 V92 已有锁序，防止同一 V91 费用同时占有订单/SKU/期间和原收货两个可计费视图。
4. 仅允许 PENDING_REVIEW 一次转为 APPROVED_UNVERIFIED 或 REJECTED；复核者不能是源创建者或计划提议者。审批时重新读取全量原币金额/行数、源快照及 V92 状态。计划与金额行不可 UPDATE/DELETE；审计由 PostgreSQL 触发器生成并禁止修改/删除。
5. finance_receipt_cost_integrity_v112 只报告本地守恒、剩余原币和 V92 归属冲突；原 PO 币种漂移也会在读时检查。external_cost_verified=false、actual_landed_cost_posted=false、realized_batch_cogs=NULL 永久固定，内部审批并非外部账单签证或 G1 会计政策许可。

## 已知风险、验收和下一步

仅是内部数据结构和 SQL 写入时守卫，当前**没有** Owner/Session/CSRF 的应用写 API，也未接 Next.js。实际运营不应手工调用生产库 V112 DML。不同 V91 source 可能代表同一外部发票，仍需复用 V165/V166/V182 的冲突核查；同币种限制不能解决 FX 与各税费资本化边界。人工源经复核也不等于真实银行/海关/承运核验。

合成测试脚本 scripts/smoke_receipt_cost_allocation_v215.py 仅在 MERCHIVRA_V215_DISPOSABLE_ONLY=true、PG18/Flyway V112 环境下用 TEMP 影子表+真实已安装函数，覆盖来源快照、双向 V92 冲突、超额/跨币种、空审批、不可变和自动审计，最后 ROLLBACK。CI 完整 PG18 任务中加入脚本；测试是否真实成功必须另核对精确 SHA 与日志，不能借旧 V213 green。

V216 优先实现独立只读源漂移/跨来源双计诊断以及 V112 回归；写入与双人审批应用入口待 G1/DEC-06/07/08 原币资本化、退款和期间政策确认及受控权限回归之后再决定。真实 Seller/Ads/银行授权 OFF；realizedNetProfit=null；评分 70/100；生产 #104 NO-GO。

## 初次 A3 失败记录

V215 第一次完整 A3 #45295153716 在精确 `7515edd` 的 PostgreSQL 18 专项 FAILED：新 TEMP V91 fixture 填写 REVIEWED_UNVERIFIED，却缺 required reviewed_by/reviewed_at/review_reason，原 V91 CHECK 正确拦截；不是已经证明 V112 的触发器失败。已修正测试夹具而**未放宽** V91/V112 的任何真实约束；下一次同 SHA A3 成功后才可验收。

## V246 · 采购收货成本按明确数量区间分摊（候选算术，快 CI 已通过）

复用 `CostAllocationEngine.partitionReceiptAmount`，不另建费用账本。调用者显式提供同币种、六位小数以内的原始收货成本金额、正整数数量分母与稳定的半开区间 [from,to)，区间仅是计算坐标，不是货物序列号、实际出库或返仓证明。对每份同一原始成本的分摊，定义 F(k)=floor(amount*k/basis,6)，区间金额=F(to)-F(from)，按区间排序、拒绝重复 claim ID/重叠/越界（最多1000行），保留缺口数量与未分配金额。连续区间相加精确回到原金额；退款若未来有真实原出库区间，只能以同一原金额/分母/原范围内的子区间重算，不得重新平均或跨批次推断。零金额必须由调用者明确提供，不代替未知；计算得到零份额不等于可创建 V112 零金额行。

这是待 G1/DEC 确定数量分母、拒收报废、FX、费用资本化与返仓规则的候选算法，不是已批准会计规则。尚无服务调用、API、持久化区间身份或 UI，单次调用无法防跨调用重复。不得将输出写入 V112/实际 COGS 或利润；后续真实样本和批准前不得自动生成单位区间。Java 回归覆盖 100/3 尾差、部分缺口、退款子区间、微额、最大分母、非法区间及500组固定随机种子独立 BigInteger 重算。

V246 精确 [SHA 8ab8a93a53b3](https://github.com/kunlong-luo/merchivra/commit/8ab8a93a53b348a7a329e0292b992007bd426efb) [CI #45560785767](https://github.com/kunlong-luo/merchivra/actions/runs/45560785767) SUCCESS，PG18 SKIPPED。V247 的本地显式坐标契约见 [V106/V117](v203-sale-to-lot-link-integrity-contract.md)；不改变 V112 来源金额、审批或实际利润门禁。

V247 的完整 A3 PG18 失败，V248 以追加 V118 修正区间触发器别名并补真实双连接回归；参见 [V106/V117/V118](v203-sale-to-lot-link-integrity-contract.md)。V112 金额与 V246 分摊的业务集成留待区间验收及 E1/E3/E5 证据，不以测试代替成本认证。

## V249 · PO 原价、头程、关税与销售区间组合（内部候选，快 CI 已通过）

`ReceiptCostCompositionServiceV249` 在独立 REQUIRES_NEW/read-only RR 快照复用 V216 单次 source/碰撞读取、V106 全历史 claim（含撤销）、V117 区间及 V246 算术。单次快照不是 V216 独立双读实时校验，不为外部源签证。跨站点 claim/费用、缺失/撤销/失效区间、收货与验收数量不同、不同 PO 单价/原币、缺少头程或关税、V216 审核/冲突 hold、PAYMENT_AND_FX 均阻断整体候选金额；没有合法零费用证明时不把缺失记零。每个原始来源只分摊 V112 当前收货归属金额，不使用全来源金额；PO 参考价乘同一数量分母。每来源分别按 V246 分摊后加总，保留每来源及每 claim 候选值、未分配金额。未冻结外部费用组合版本、未提供 API/UI/持久化/退款过账；实际成本及净利润恒 null。Java 回归涵盖逐来源尾差、缺费用、碰撞、跨站点、缺区间、拒收和重叠；数据库服务路径尚待真实 PG18 集成回归。

## V250 · 实际 PG18 服务集成回归（工作流未启动）

V249 [SHA 9a41dc9b7613](https://github.com/kunlong-luo/merchivra/commit/9a41dc9b76139583ac77007720a21ed624eaab21) [快 CI #45563638010](https://github.com/kunlong-luo/merchivra/actions/runs/45563638010) SUCCESS，PG18 SKIPPED。V250 新增隔离 CI `ReceiptCostCompositionPg18V250Test`，严格核对 main、数据库/授权 OFF 和 Flyway 106/112/117/118 后，通过真实 public 业务表、外键、V106/V117/V112 触发器建立合成采购/QC/出库/订单/费用；真实 JdbcTemplate 和事务管理器调用 V249/V216/V165。验证原采购 30、收货归属头程 10/原源20、关税5/原源10；区间 [0,1)、[2,3) 候选总额45、销售30、余15，逐 claim 14.999999/15.000001，缺区间/缺关税/采购价漂移/跨租户/撤销/跨站点费用阻断。使用追加式撤销，不清理历史合成事实，随 CI 数据库销毁。复用现有 JUnit XML 守卫，要求此方法实际执行并输出 PASS，不允许跳过。完整 A3 尚待确认；不新增 API/UI、无实际外部成本证明、无批准会计规则。

V250 精确 [SHA 201957f5da48](https://github.com/kunlong-luo/merchivra/commit/201957f5da4836a3b64067a6486f11d933c69ad8) 快 [CI #45564608797](https://github.com/kunlong-luo/merchivra/actions/runs/45564608797) / [A3 #45564610271](https://github.com/kunlong-luo/merchivra/actions/runs/45564610271) 均 FAILURE 且 jobs=[]；未执行 JUnit。未获取工作流拒绝注释，不能断言根因或测试失败。V251 将 约2.1万字符 PG18 run scalar 原样移至 [run_pg18_full_ci.sh](../../scripts/run_pg18_full_ci.sh)，增加隔离环境检查与 EXIT 清理，不删旧测试或放宽验收；待新 SHA 验收后再决定只读 API。

## V252 · Owner 只读批次候选成本 API（完整 A3 已通过）

`GET /api/v1/finance/marketplaces/{marketplaceId}/purchase-receipts/{receiptId}/cost-composition-candidate`（operationId `getPurchaseReceiptCostCandidateV252`）复用 V249/V216，不新增费用账本或写接口。Session 当前可读租户必须具有 OWNER，不能从 Staff 工作区自动切到另一 Owner 租户；服务重新核对 receipt/Marketplace，404 跨租户。no-store，原币六位字符串/未知 null；返回最多51条逐来源摘要（不重复返回每个来源的全区间明细）和最多1000条 claim 候选值。completeExternalCostCoverage=false、actualBatchCogsVerified=false、postingAllowed=false、actualBatchCogs/realizedNetProfit=null。V219 真 HTTP 复用无 claim 夹具，验证 HOLD、匿名401/Staff403/跨租户404、伪造 query tenant 无效、退出失效；正金额仅服务回归/Controller 单测，真实非空 HTTP、Orval 和 Next UI 留待验收后。V251 的完整 A3 在本轮开始仍运行；V252 额外修复外置 shell 遗留 GitHub 表达式，将 job service ID 经 env 传递，新增静态禁止未展开表达式。未确认 V250/V251 真 PG18 服务通过，不开放生产。

V252 精确 [SHA 65128ba2eb93](https://github.com/kunlong-luo/merchivra/commit/65128ba2eb938574d600ee281b83301603b16fd2) [CI #45566116953](https://github.com/kunlong-luo/merchivra/actions/runs/45566116953) SUCCESS / [A3 #45566116963](https://github.com/kunlong-luo/merchivra/actions/runs/45566116963) 7/7 SUCCESS，PG18 job #139041361728 原日志含 PASS V252 actual Owner HTTP candidate 与 PASS V250 actual PG18 composition JUnit marker verified (not skipped)。V251 PG18 日志确认 bad substitution 已修复。

## V253 · 既有收货面板的 Orval 成本候选读取（待验收）

扩展原 `apps/web/openapi/manual-cost-v228.json` 与既有 Orval 生成流程（生成文件不手改、不提交），V217 收货单面板内按精确租户/Marketplace/receipt 及检查 revision 挂载 V253，独立按需 GET、取消请求、无旧值展示。Zod + BigInt 微单位核对来源唯一、原币、数量和来源/整批/销售金额守恒，HOLD null 不显示部分金额，禁止 verified/posting/net profit。复用现有样式与 Session transport；采购与财务共享 V217 的位置均可访问。不增加新路由或并行 HTTP 客户端。扩展既有 Springdoc 检查及 Vitest 运输/守恒负例，待 CI 生成及 A3；真实非空 HTTP 和 Chromium 新面板专属流程未完成，仍无实际外部证据、退款过账或正式成本版本。

## V254 · 非空真实 Owner HTTP 与 Next Chromium 轨迹（待验收）

V253 [快 CI #45568099720](https://github.com/kunlong-luo/merchivra/actions/runs/45568099720) SUCCESS，完整 [A3 #45568099866](https://github.com/kunlong-luo/merchivra/actions/runs/45568099866) 本轮初查 5/7 成功、2 项运行中。V254 扩展原 V250 一次性夹具：复制仅 CI bootstrap hash 建立独立合成 Owner，真实 loopback HTTP Cookie/CSRF 登录，验证缺关税 HOLD、45/30/15 原币候选与 claim 尾差、价格漂移/恢复、跨站点 HOLD、匿名/退出 401、no-store 及禁止核证字段；原服务回归保留且 JUnit 必须含 V254 marker。扩展既有 Next Chromium 360px 合成 fixture：显式 GET、金额及认领明细、409 清空旧值、重试、切站点不自动读、HOLD 不显示旧金额，保存截图。浏览器是合成 HTTP fixture，不是同一真实 PG18 浏览器会话；两者均待新 SHA A3，不新增 API、依赖、会计成本版本或退款回转。下一步以已确认原始成本区间与独立退款物理证据为基础设计回转。

V255 从 V249 同一 RR 观察准备原认领成本候选快照（不是历史过账），复用逐来源 V246 算术计算显式退款子区间；[退款原批次契约](v212-refund-original-sale-lot-quantity-envelope.md) 维护版本、摘要、守恒与缺失持久化/外部物理证明限制。不新增 HTTP/UI，实际回转、利润保持 null。

V256/Flyway V119 为 V255 同快照候选增加一次性不可变存储与退款区间预留，规则和限制见 [退款原批次契约](v212-refund-original-sale-lot-quantity-envelope.md)。不修改 V112/V117 或财务过账，不开放写接口。

V257 在 V249 同 RR 内重新读取 V119 预留并核对当前 V255 成本指纹及退款审核，失效 HOLD/null；真实双连接和跨认领测试见 [V212 退款契约](v212-refund-original-sale-lot-quantity-envelope.md)。
