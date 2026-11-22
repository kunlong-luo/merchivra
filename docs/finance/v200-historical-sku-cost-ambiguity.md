# Merchivra V200 · 历史 SKU 映射与有效期成本版本歧义阻断

**状态：2026-11-22 实现并通过完整合成 A3。** [精确 V200 代码 `2715529`](https://github.com/kunlong-luo/merchivra/commit/271552911b55a451e66662cdf3aab94c2db21341) · [快速 CI #45023721155 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45023721155) · [完整 A3 #45023721170（7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45023721170)。

## 审计发现的真实风险

`OrderCostReadinessService.preview` 在原订单业务时点使用 `listing_sku_mapping` 和业务日期使用 `sku_cost_version`。此前这两条关联都是按最新版本/生效日期排序再 `LIMIT 1`，**未判定是否同时有其他匹配候选**：

- V19 的 `sku_cost_version` 有「同起始日期唯一」和「至多一条开放生效区间」，但并无所有历史闭区间的排他区间约束；一条尚未到期的历史闭区间与另一条新版本可能重叠。数据库允许「同时生效的两个价格」，原查询可能假装选中了正确的价格。
- 若历史 `listing_sku_mapping` 由于回补/异常状态产生同一订单时间多条有效 SKU 映射，单纯取 `mapping_version DESC LIMIT 1` 也会隐藏实际 SKU 归属歧义，即使金额刚好一致。

两种情况都会污染人工退款 V34/V35/V63 所依赖的历史单位成本，以及只读订单/SKU 贡献预览；必须 **UNKNOWN ≠ 0、歧义 ≠ 可用**。

## V200 复用原逻辑

1. 不新建成本表、不重新实现历史成本计算、不修改已发布 V19 Flyway。原订单/Marketplace/租户过滤和订单当地日期保持不变。
2. 原 PostgreSQL `LATERAL` 查询使用 `COUNT(*) OVER()` 对 **WHERE 生效期范围内所有匹配候选**计数，然后才 `ORDER BY … LIMIT 1`。缺失为 0、恰好 1 为可用、多个为冲突。不能把相同金额的两个版本当唯一版本。
3. `CASE` 只在 SKU 映射与成本版本均恰好各 1 条时返回选中单位成本和原币；其他情况 SQL 不泄露任意历史成本。Java 原 `OrderCostEngine` 汇总器新增两种显式项级阻断：`MAPPING_AMBIGUOUS_AS_OF_DATE`、`UNIT_COST_VERSION_OVERLAP_AS_OF_DATE`，以及 `COST_EVIDENCE_COUNT_UNKNOWN`；保持其旧构造器兼容以避免破坏调用方。订单汇总必须 `INCOMPLETE`、`provisionalCost=null`，不能把缺失/冲突当零。
4. Next 既有「订单历史成本完整度」页面将冲突标为中文；不增加卡片，不把成本准备度当实际采购批次/退货入库/GL/银行事实。

## 有效的验证与未解决事项

- JUnit `OrderCostEngineTest` 新增多映射、重叠版本、未知候选计数的负例；即使另一行成本可用，整单暫定成本依然为 null。
- PostgreSQL 18 合成 `MERCHIVRA_V200_DISPOSABLE_ONLY=true python3 scripts/smoke_order_cost_ambiguity_v200.py` **提取并执行原 Java SQL**，使用临时表/事务 ROLLBACK，分别测试一对一、闭区间重叠（即使单位成本相同）、不同 SKU 历史映射重叠、完全缺成本，不修改任何真实业务记录。
- 本次精确代码 SHA 的快速 CI 与完整 A3 已核验 SUCCESS（7/7）。包括真实 PostgreSQL18 上原 Java SQL 的 TEMP/ROLLBACK 测试、Java/Next、Chromium、HTTP/Compose，未启动 Amazon/Ads 真实 OAuth。

**没有解决**：采购入库批次/货位与实际售出库存的 FIFO/加权成本过账联系、外部 WMS/FBA 物理退货确认、Amazon/Ads/银行费用外部真实性、G1/DEC-06/07/08 签署、完整净利润。V200 仅消除一个**本地历史成本选择错误**；`realizedNetProfit=null`、真实 Amazon Seller/Ads OAuth OFF、生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。评分必须单独按六维事实复核，不因新测试自动上调。
