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

## V246 · 采购收货成本按明确数量区间分摊（候选算术，待验收）

复用 `CostAllocationEngine.partitionReceiptAmount`，不另建费用账本。调用者显式提供同币种、六位小数以内的原始收货成本金额、正整数数量分母与稳定的半开区间 [from,to)，区间仅是计算坐标，不是货物序列号、实际出库或返仓证明。对每份同一原始成本的分摊，定义 F(k)=floor(amount*k/basis,6)，区间金额=F(to)-F(from)，按区间排序、拒绝重复 claim ID/重叠/越界（最多1000行），保留缺口数量与未分配金额。连续区间相加精确回到原金额；退款若未来有真实原出库区间，只能以同一原金额/分母/原范围内的子区间重算，不得重新平均或跨批次推断。零金额必须由调用者明确提供，不代替未知；计算得到零份额不等于可创建 V112 零金额行。

这是待 G1/DEC 确定数量分母、拒收报废、FX、费用资本化与返仓规则的候选算法，不是已批准会计规则。尚无服务调用、API、持久化区间身份或 UI，单次调用无法防跨调用重复。不得将输出写入 V112/实际 COGS 或利润；后续真实样本和批准前不得自动生成单位区间。Java 回归覆盖 100/3 尾差、部分缺口、退款子区间、微额、最大分母、非法区间及500组固定随机种子独立 BigInteger 重算。
