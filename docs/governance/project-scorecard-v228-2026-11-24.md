# Merchivra · V228 六维重新评分（2026-11-24）

**本轮独立复评分：73/100（旧 [V200 正式 70/100](project-scorecard-v200-2026-11-22.md) → +3）；仍是 pre-alpha、G1/G2/G3 未放行、生产 #104 NO-GO。** 这是产品与技术合并的成熟度**定性评分**，不是 GitHub CI 通过率、项目完成比例、真实利润可信度或运营/生产授权。保留 V200 的原始分数和当时证据，不追溯篡改。

## 保持原六维满分，不因版本号自动加分

| 评估维度 | V200 | V228 复评 | 满分 | 本轮具体新增证据与剩余缺口 |
| --- | ---: | ---: | ---: | --- |
| 架构与技术选型 | 18 | **18** | 20 | Java25/Spring Boot4.1.2/Gradle/PG18 与 Next16/React19/TypeScript/pnpm/Orval 基线不变；未证明正式生产目标环境部署 |
| 业务功能完整性 | 16 | **16** | 20 | V203–V227 本地订单→批次/退款/费用源完整关联、V226 有界 Owner 批量暂存已存在，**但真实 Seller/Ads 只读授权与真实运营未验收**，不把模拟模块数量当功能全部完成 |
| 全成本与利润真实性 | 10 | **10** | 25 | 原始合法 Amazon Finances/Settlement、Ads、银行/物流/关税与第三方 WMS 实收仍未独立证实，退款批次 COGS 与 G1/DEC-06/07/08 口径尚未批准；`realizedNetProfit=null`，**不得补成0或合成净利润** |
| 安全与可信数据 | 12 | **13** | 15 | **+1**：V221–V227 真 PG18 V113 46 个触发器、已提交 ABA→HTTP409，V114/V115 INSERT-safe 无变化 UPDATE，V91 不变事实/审核隔离，V226/V227 多来源/租户/Marketplace 唯一归属、批量重放不重复审计、Owner/CSRF/回滚等跨进程正反例；真实生产 IAM、外部仓储/银行主体与 KMS 待签 |
| 前端与交互体验 | 8 | **9** | 10 | **+1**：V228 已将 V226 原币费用独立于一般财务事件，用 Orval + Zod 接入正式 Next 财务页；1–20 行六列 TSV 整批预览、原币/摘要/日期/金额严格验证，409 失败不冒充部分成功、同 ID 重放不冒充新建，Marketplace 切换重置。相关 V228 五项新 Vitest 及 452 项前端测试通过；**真实最终用户流程、移动端及用户本地 HTTP500 根因仍未验证** |
| 测试、运营与发布 | 6 | **7** | 10 | **+1**：V221–V227 多阶段精确 SHA 的真实 PG18/Owner HTTP/Chromium/Compose 7/7，包含事务 ABA、失败历史和前向修复；V228 新代码已有 92 个测试文件/452 项测试的 Next TS/Orval/Gradle/浏览器布局通过。真实外部目标 TLS/KMS/PITR/RPO/RTO/告警 ACK、容量 SLO、发布审批缺失，**不能用合成 A3 代替** |
| **合计** | **70** | **73** | **100** | **工程可信度/体验确有增量，但真正的“全成本 → 可审计净利润”还未完成** |

**拆开看更直观**：除“真实利润”外的五项合计是 **63/75（84%）**，而全成本/真实利润只有 **10/25（40%）**，合成综合 **73/100**。这解释了为什么工程阶段增加很多、全部测试都变绿，综合分仍不可能跳到 90：最重要的业务真实性占 25 分，仍缺 15 分。这里的 84% 是同一评分表的**非真实利润子项占比**，不叫生产就绪度、真实可售完成度，也不对应可直接上市。

## 精确代码与测试、未完成验收

- V227 真实 PG18 Owner 并发/重放：精确代码 [`782c2121c2ce`](https://github.com/kunlong-luo/merchivra/commit/782c2121c2cee8091e9ae1ff5e976d59e4ad346a)、[快速 CI #45328943995 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45328943995)、[完整 A3 #45328944026（7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45328944026)：2 Owner Session ×2 Marketplace 的 16 个 batch/80 个唯一来源/80 条 STAGED 审计，租户 epoch **+32 严格守恒**，真实 HTTP 等待 `FOR UPDATE` 及释放恢复、无财务过账；不代表生产自然 409 比率。
- V228 Orval/Next 财务 TSV：精确首轮代码 SHA [`ccec47d9b0b8`](https://github.com/kunlong-luo/merchivra/commit/ccec47d9b0b8603d7a8a3f51ece3608f3cae3065) 的 [完整 A3 #45359294822](https://github.com/kunlong-luo/merchivra/actions/runs/45359294822) 是 **6/7**，其中 Next/Vitest **92 files/452 tests**、Java/Gradle/Compose/Chromium 与旧 V221–V227 真 PG18 业务安全全部 PASS；新 V228 自身 Springdoc 校验脚本将实际 200 schema 的 `*/*` 媒体错误地限定为 `application/json`，报 `KeyError`，**所以不能将首次 A3 当 V228 的完整验收**。仅修复该校验器的媒体 key 处理并保留必需来源响应 schema 检查，修复代码 SHA [`ecd02b5bd5fc`](https://github.com/kunlong-luo/merchivra/commit/ecd02b5bd5fc0b33b15ee2a1c91f4303ec220c15) 对应的 [第二轮 A3 #45360086678](https://github.com/kunlong-luo/merchivra/actions/runs/45360086678) **再次 6/7**：已接受实际 `*/*`，但严格比对来源 schema 缺少正确属性；因此不能冒称通过。新增 Java `Draft`/`Source` 唯一 `@Schema` 名称与诊断 Java 测试、仍保留 Springdoc 源字段校验，[V228C 真实 PG18 A3 #45360965067](https://github.com/kunlong-luo/merchivra/actions/runs/45360965067) 待结论；**73分仅反映先前可证实的工程与前端进步，不等于 V228 完整技术验收或生产发布**。前端体验的 +1 是已完成 TS/Orval/测试及入口功能改善，不等于本项已经获得全量 A3。
- GitHub [#12 全成本利润](https://github.com/kunlong-luo/merchivra/issues/12)、[#15 会计与业务决策](https://github.com/kunlong-luo/merchivra/issues/15)、[#104 真实生产 G1/G2/G3](https://github.com/kunlong-luo/merchivra/issues/104) 在本次复核时均为 **OPEN**，不是从模拟数据猜测已关闭。

## 为什么不是更高分、下一步怎样真正涨分

1. **先解决占25分的真实利润证据，而不是继续新增展示卡片**：维护者本地依法完成实际 Amazon Seller/Ads 的只读授权（或合法持有、可独立确认来源的正式账单），让每笔订单收入/退款、每项费用原币与结算报告行、物流/关税/仓储/汇兑及 WMS 实收获得源系统身份、原始文件摘要、完整候选匹配、独立复核与失效/冲突记录；严禁将手工 SHA 声明自动升级为真正 VERIFIED。
2. **完成退款 COGS、最终 landed cost 与签署会计政策**：采购批次的货物成本、运费关税、税费汇率、原始出库及退货物流必须形成跨订单/批次/期间守恒和确权证据，DEC-06/07/08 和 G1 对利润期间与正负费用口径正式审批后，才考虑从局部贡献预览升级为真正实际净利润；不允许虚构未取得的外部数据。
3. **提升目标运营/安全验收**：真实 TLS、KMS、PITR/RPO/RTO、外部告警接收证明、第三方 WMS 身份与权限及实际维护者接受测试由独立人工执行。完成正式目标部署不自动代替真利润真实性。

**评分模型保持原权重，下一次增加分数须指向确切的新证据、代码 SHA、真实负例和独立签署结果。** 暂保持生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**，真实 Amazon Seller/Ads OAuth OFF，`realizedNetProfit=null`。
