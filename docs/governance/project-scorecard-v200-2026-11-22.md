# Merchivra · V200 阶段独立复评分（2026-11-22）

**本次定性评分：70/100；较原 [68/100 基线](project-scorecard-2026-11-22.md) +2 分。不是财务或生产批准，也不是 GitHub CI 通过百分比。** 评估代码基线为 [`271552911b55a451e66662cdf3aab94c2db21341`](https://github.com/kunlong-luo/merchivra/commit/271552911b55a451e66662cdf3aab94c2db21341)：[快速 CI #45023721155](https://github.com/kunlong-luo/merchivra/actions/runs/45023721155) SUCCESS、[精确 SHA 完整 A3 #45023721170](https://github.com/kunlong-luo/merchivra/actions/runs/45023721170) **7/7 核心 SUCCESS**，包括真实 PG18/Flyway、Spring HTTP/Compose、Java、Next、Chromium。完全使用隔离/合成数据，**没有 Amazon/银行生产源验证**。

## 六维统一量表

| 维度 | 上次 | 本次 | 满分 | 复评分依据 |
| --- | ---: | ---: | ---: | --- |
| 架构与技术选型 | 18 | **18** | 20 | Java25 / Spring Boot4.1.2 / Gradle / PG18，React19 / Next16 / pnpm，OpenAPI；外部目标部署/授权未签署，不抬分 |
| 业务功能完整性 | 16 | **16** | 20 | 订单、SKU、采购/FBA、仓储、异常、财务预览等内部模块持续具备；Seller/Ads 真实 API 和真实运营尚未验收 |
| 全成本与利润真实性 | 10 | **10** | 25 | V197 退款 COGS 阻断、V198/V199 结算归属完整性、V200 历史成本重叠拦截**提高本地证据质量**，但没有独立 Amazon/Ads/运费/银行费用、实物退货成本回转和 G1 财务口径；`realizedNetProfit=null` |
| 安全与可信数据 | 11 | **12** | 15 | **+1**：V197 原退款来源时点一致性、V198 重复结算归属、V199 缺失声明净额、V200 两类历史成本歧义均采取 fail-closed 且有负例验证；生产 IAM/外部 WMS 身份、银行仍待核 |
| 前端与交互体验 | 8 | **8** | 10 | Next 是正式前端，错误与财务风险状态可见；真实用户场景、移动端及本地 HTTP 500 根因仍待验收 |
| 测试、运营与发布 | 5 | **6** | 10 | **+1**：V194/V105、V197、V198、V199、V200 精确 SHA 的 PG18/HTTP/Chromium/Compose 完整合成 A3 均有证据，特别是 V200 在真实 PG18 上跑原 Java SQL 的版本冲突负例；但真实目标 TLS/KMS/PITR/RPO/RTO/外部告警 ACK 与发布签署未解决 |
| **合计** | **68** | **70** | **100** | **可复核的内部工程原型；仍非可以计算真实净利润并上线的 ERP** |

这里的 2 分**不是因为“测试变绿”自动加分**，而是安全/数据质量项增加了可明确追踪的错误拒绝路径，测试/运行项有跨运行时真实 PG18 执行负例而不只是单测。财务真实性仍为 **10/25**，保留最大单项短板；先前 68 分基线不回写。

## 风险与实际下一步

1. **P0-A 费用外部真实性**：Amazon Finances/Settlement、Ads、头程、关税、仓储、银行支出的实际可核验文件/交易行、唯一原始凭据与独立复核。目前本地 `LIVE` 与本地 SHA-256 不等于第三方验证。
2. **P0-B 真实历史采购批次成本 / 退款 COGS**：须把订单 SKU 与可证明的入库批次、单位成本版本、库存流转、跨事件退款数量守恒及真正可信退货实收连接；V200 只排除了多个有效候选之间的任意选择，未完成批次成本会计。
3. **P0-C 利润报表**：G1/DEC-06/07/08 批准的成本口径、税费、汇率、退款回转规则、期间划分及币种不重复后，才能核实净利润。目前只允许冻结贡献预览与阻断说明。
4. **P0-D/E 生产签署**：真实运营验证、第三方 WMS 操作身份、目标环境安全/恢复/告警及 G1/G2/G3 人工签字。GitHub [#104](https://github.com/kunlong-luo/merchivra/issues/104) 仍开放且明确 BLOCKED，[全成本利润 #12](https://github.com/kunlong-luo/merchivra/issues/12)、[G1 决策 #15](https://github.com/kunlong-luo/merchivra/issues/15) 仍开放。

**发布独立判断：pre-alpha / NO-GO。** 真实 Seller/Ads OAuth、生产写入不打开；`realizedNetProfit=null`。不能用 70 分、合成 A3 或“缺失=0”绕过外部业务事实及正式签署。
