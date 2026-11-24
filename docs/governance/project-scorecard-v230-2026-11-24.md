# Merchivra · V230 独立产品成熟度复评分（2026-11-24）

**现行统一评分 75/100（V229 [74/100](project-scorecard-v229-2026-11-24.md) → +1）**。严格保留之前 [V228 73/100](project-scorecard-v228-2026-11-24.md)、[V200 70/100](project-scorecard-v200-2026-11-22.md) 及全部 A3 失败记录，不回写历史；原六维满分和评价口径未改变。**75 是内部产品/工程成熟度定性分数，不是生产准备度、真实利润可信度、工程完成率或实际 Amazon 授权的百分比**。

## 六维不变，V230 仅业务工作流完整性 +1

| 维度 | V229 | V230 | 满分 | 可独立复核的新增证据 / 不得加分的缺口 |
| --- | ---: | ---: | ---: | --- |
| 架构与技术选型 | 18 | **18** | 20 | Java25 / Spring Boot4.1.2 / Gradle / PostgreSQL18、Next16 / React19 / pnpm / Orval 已稳定；无真实生产目标部署和批准证据，不加分 |
| **业务功能完整性** | 16 | **17** | 20 | **+1**：经营人员在 V228 Owner 手工费用来源暂存/重放结果上可选择单个真实 source UUID，查看 **8 种成本类别**的合法第三方证明要求；V230 在单一 RR 事务中连接 V229 原始审计与 V182/V165/V94/V102 本地结算、冲突风险，明确先解决缺/重复审计、Maker/Checker、碰撞，再提供独立签发方、原文件字节、原币、货件/退货 COGS、支付与财务签署的分类型未核证材料清单；用户可以据此开展真实证据准备工作，而不再只是创建孤立手工来源。这是用户可操作功能，不是单独卡片或又一层内部批准。真实 Amazon/Ads/银行仍没有验收，不得算真同步/真过账完成 |
| **全成本与利润真实性** | 10 | **10** | 25 | **0 分变化**：仅凭 V91 手工声明的 SHA、V229 完整本地审计或 V182 同金额结算行不能证明外部签发方、真实文件字节、支付金额、重复扣款排除、合法退货/批次 COGS 或已批准的 DEC-06/07/08；`realizedNetProfit=null`、所有外部验证布尔值 false；没有真实来源绝不加分 |
| 安全与可信数据 | 14 | **14** | 15 | V230 继续按 Session tenant/Marketplace/source no-store 严格隔离，Zod 拒绝不可信 VERIFIED/非 null 利润，但这复用 V229 已计分安全原则；真实生产 IAM/KMS/外部主体认证仍未闭环，不重复加分 |
| 前端与交互体验 | 9 | **9** | 10 | Next 工作台新增选中来源查看缺口和错误阻断；V230 Chromium/Orval/Vitest 完整自动化通过，但真实使用者体验、移动端原型和用户本地 HTTP500 根因未独立验证，不重复加分 |
| 测试、运营与发布 | 7 | **7** | 10 | 首轮 A3 找到 Next 路由 remount 测试 race，修复后实际 7/7，记录失败历史；没有真实目标 TLS/PITR/KMS/外部告警/容量 SLO、生产审批，不能把合成 CI 当成生产验收，不加分 |
| **总分** | **74** | **75** | **100** | **仅业务可操作证据准备流程 +1；真实利润真实性 10/25 不变** |

拆解后五个非真实利润维度为 **65/75（86.7%）**，真正全成本/利润只有 **10/25（40%）**；75 分**不表示**利润核算功能可以在真实店铺、银行或仓库环境直接用于会计/经营决策。

## 精确 GitHub A3 / Spring/Orval/Chromium 与历史失败

- [V230B 接受代码 SHA `a8e5eba353d501de9761685cdd0d05de1d340982`](https://github.com/kunlong-luo/merchivra/commit/a8e5eba353d501de9761685cdd0d05de1d340982) 的 [完整 A3 #45368066154（run_attempt=1，**7/7 SUCCESS**）](https://github.com/kunlong-luo/merchivra/actions/runs/45368066154)，真实 PostgreSQL18 / Spring Job **#138355672453** 输出 `V230_SOURCE_PROOF_DOSSIER`、`PASS V230`，还复验实际 Springdoc 对应已有 Orval 的 `ExternalCostProofDossierV230` / `ExternalCostProofRequirementV230` DTO。真实 Owner HTTP 验证 ADS、头程、FBA仓储和退货四类材料清单，原 V91 初始 audit 1/0/2，V165 相同手工摘要的**候选**冲突 hold、匿名401/错误 Marketplace404、GET 零次 V113 tenant epoch 写入，所有第三方签发者/原始收费/COGS/净利润证据始终 false/null。其他四类费用类别由 Java 单元测试完成分类回归，不冒称这四类也都经历过真实 PG18 HTTP。
- [首次代码 SHA `6baef5a3230dd799815b4dbf230ce74de55d82a3`](https://github.com/kunlong-luo/merchivra/commit/6baef5a3230dd799815b4dbf230ce74de55d82a3) 的 [A3 #45367537947](https://github.com/kunlong-luo/merchivra/actions/runs/45367537947) **仅 6/7**，非数据库业务回归失败原因是 Chromium/Next App Router 切换时通用表格 DOM 被重挂载，脚本在无节点时读取列数抛 `Uncaught`。真实 PG18 Job #138353131033 当时已打印 `PASS V230`；修复后的脚本严格限定当前 Core 面板，受控重试稳定 DOM 并保留订单/库存身份及列表列数断言；不能把原始 6/7 冒充成功。
- 本轮 **未新增 Flyway、真实 Amazon/Ads OAuth 开关、真正外部原始数据接入、费用过账、退款 COGS 数字或已实现净利润**。同 SHA A3 只能证明隔离合成技术安全和正确阻断，不能代替真正的原始数据取得、独立签发方/账户核查或会计签署。
- 项目 [#12 全成本/利润](https://github.com/kunlong-luo/merchivra/issues/12)、[#15 G1/DEC 会计决策](https://github.com/kunlong-luo/merchivra/issues/15)、[#104 真实生产 G1/G2/G3](https://github.com/kunlong-luo/merchivra/issues/104) 未取得独立完整放行材料，即使代码/Issue 流程有变化，也不能因此推测净利润可用。

## 下一阶段 V231：怎样显著提升评分

真正高权重的是全成本/净利润真实性的 25 分。V231 应从 V230 已规范化的第三方证据要求出发，先为**合法取得的** Seller/Ads/银行/货代/WMS 真实原始文件/接口响应实现受限且可撤销的内容 SHA 重算、签发主体/授权身份核证、原始金额/原币和行级交易键、不可重复归属及独立审核、失效重算，不得从任何操作者输入直接宣称 VERIFIED。之后才有可能将已核实采购入库/真实出库与退货 COGS 和 DEC-06/07/08 结算口径纳入真正可审计的 SKU/订单/整期利润。

目前仍 **pre-alpha、生产 #104 NO-GO、Seller/Ads 真 OAuth OFF、`realizedNetProfit=null`**。以后加分也必须基于明确的新代码 SHA、真 PG18/HTTP/浏览器负例、**真正合法第三方凭证与人工签署**；禁止只为分数把未知/人工费用填零或改成已认证。
