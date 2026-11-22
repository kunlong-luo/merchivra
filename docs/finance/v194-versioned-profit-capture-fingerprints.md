# Merchivra V194 · 版本化订单/SKU 冻结捕获指纹与独立重算

**状态（2026-11-22）**：V194 实现提交 [`61c8678`](https://github.com/kunlong-luo/merchivra/commit/61c8678288697d20476c44478fafae8bd7739349)，Next 展示 fail-closed 修补 [`9d4ebad`](https://github.com/kunlong-luo/merchivra/commit/9d4ebadb7abe45369ea55a7bb6c33b09e920d985)；后者 [快速 CI #45014853040](https://github.com/kunlong-luo/merchivra/actions/runs/45014853040) **SUCCESS**，但仍**待独立完整 PG18/Flyway/Owner HTTP/A3**；本说明不是外部数据、生产发布或财务 G1 批准。

## 既有基础与实际缺口

- V62 / 产品 V64：按固定订单集合分批生成局部贡献快照；`extract_sha256` 是当时在 Java 内用拼接字符串生成的本地摘要。历史记录没有版本标识，不能从保存后的 SKU 子行可靠、无歧义地复算，更不是 Amazon 签章。
- V192：订单/SKU 逐笔原币五项守恒，防止不同订单明细金额差异互相抵消。
- V193 / Flyway V103：数据库触发器保护冻结后的订单、SKU、成功任务与审计事件不可改写。**迁移之前的历史真实性不能追认。**

## V194 的实现边界

Flyway **V104** 对 `finance_profit_report_order` 和 `finance_profit_report_sku` 各增加可空 `capture_version_v194` 与 `capture_sha256_v194`：新订单批次在同一个 `REPEATABLE_READ` 事务计算并写入版本 1 指纹，SKU 成员指纹先生成、排序后写入订单根指纹。所有指纹采用 SHA-256、带版本和用途域标识的 UTF-8 **长度前缀编码**；空值单独编码，金额统一 NUMERIC(20,6) 精度，不可无声四舍五入。订单根指纹绑定租户、Marketplace、批次、订单、状态、准备度、币种、复核事件数、五项金额、历史摘要值和排序后的每个 SKU 指纹；SKU 指纹绑定租户、批次、订单、内部 SKU、件数和五项金额。新版本只是**本地冻结数据一致性证明**。

独立读时校验 `ProfitCaptureVerifierV194` 使用冻结后的 V62 订单/SKU 行重算，不重新读取当前 Amazon、Ads 或银行数据、不根据历史时间戳补造证据、不修改旧数据。按整批检查（最多 5,000 订单，至多 100,000 SKU 子行的显式保护）：

| 状态或风险 | 必须采取的行为 |
| --- | --- |
| `capture_version_v194 = null`、指纹缺失的旧 V62 行 | 明确统计 `historicalLegacyOrders`，不把旧 `extract_sha256` 升级为 V1 |
| 新行的 SKU 指纹不符、成员缺失/增多、金额不守恒、订单根指纹不符、跨租户或批次 | 统计 `fingerprintMismatchOrders`，禁止金额汇总或瀑布继续 |
| 有 `PENDING`、`BLOCKED`、未完成/空批次、订单计数冲突 | 非完整利润基线；未知不等于零 |
| 已完成、非空、全部订单 ELIGIBLE 且 V1 重算一致 | 仅允许显示**局部已复核来源贡献算术**；绝不能自动标为净利润 |

新的 Owner Session 只读 GET `/api/v1/finance/marketplaces/{marketplaceId}/capture-verification/{jobId}` 显示完整批次的版本化/历史/异常/等待计数和阻断码；这个路由**不侵入**既有 V64 九操作 OpenAPI/Orval 契约。旧 V64 `extractSha256` 字段不改名，新增的 V1 指纹仅存后端冻结表，不伪称旧摘要的含义。非完整局部证据时，V64 期间金额汇总设为 **null**、SKU 汇总为空（订单原始冻结记录仍可用于取证）；已完成报表的 CSV 拒绝将未核证捕获导出为计算结果。V188 成本瀑布在读取原币金额前调用同一独立校验，发现缺证直接 HTTP 409，V192 金额守恒检查仍保持。

## 测试与限制

`ProfitCaptureFingerprintV194Test` 覆盖指纹输入顺序、非英文和特殊 SKU、固定六位金额、不可舍入、跨租户/批次、旧数据不可升级、SKU 缺失/篡改、未完成与 BLOCKED 场景，另验证 V104 及服务门禁代码。不应以静态或 Mockito 单测代替 PostgreSQL 18 Flyway + 合成 Owner HTTP 的专项回归；后续完整 A3 必须记录精确 main SHA、run attempt、结果和失败修复情况。

**严格保持**：V1 证明本地冻结行可重算，**不证明**原始 Seller Finances / Settlement、Ads、物流、银行真实性或手续费齐全。退款 COGS 真实回转、G1/DEC-06/07/08 会计政策、FX 和完整 V162 净利润仍未完成；所有 `realizedNetProfit=null`，Seller/Ads OAuth 与生产写入关闭，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。默认 Web Next.js 16，前端包管理仅 pnpm；V194 不添加展示卡、授权或独立会计引擎。
