# Merchivra V215 · 原采购收货单级人工费用归属契约

**2026-11-24 · Flyway V112 / 本地人工提案、无公开写入 API、无真实财务过账。** V215 只把 V91 原币人工费用提案绑定本地采购收货 ID，并显式阻止 V92 的重复归属，未核证任何真实 landed cost。精确 SHA/PG18/A3 结果将在运行后单独确认。

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
