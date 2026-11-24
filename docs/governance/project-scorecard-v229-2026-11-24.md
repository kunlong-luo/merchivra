# Merchivra · V229 独立复评分（2026-11-24）

**新统一成熟度评分：74/100（V228 [73/100](project-scorecard-v228-2026-11-24.md) +1）。** 原始 [V200 70/100](project-scorecard-v200-2026-11-22.md) 和 V228 73/100 均保留为各自阶段真实历史，不回写。**这是带证据的定性综合评价，不是 GitHub 测试通过率、真实业务已完成百分比或生产上线批准。** 本轮调整的唯一来源是 *安全与可信数据 13→14*；真实财务来源/利润项 10/25 **不变**。

## 同一六维权重与逐项可复核评分

| 维度 | V228 | V229 | 满分 | 加分或保留的实证条件 |
| --- | ---: | ---: | ---: | --- |
| 架构与技术选型 | 18 | **18** | 20 | Spring Boot4.1.2/Java25/Gradle、PG18、Next16/React19/pnpm 与 Orval 工作流稳定；尚无真实生产目标签署 |
| 业务功能完整性 | 16 | **16** | 20 | 订单、采购/FBA、仓储、退款/财务等内部业务与 V226/228 手工费用批次工作台存在；实际 Amazon Seller/Ads 授权和端到端真实运营尚未验收；本轮只读预检不当作真实费用收入功能已完成 |
| 全成本与利润真实性 | 10 | **10** | 25 | 费用是 `MANUAL_UNVERIFIED`；没有真实第三方签发方/原始文件内容与资金流核证、退款实收/批次 COGS 与 G1/DEC-06/07/08 会计口径；`realizedNetProfit=null` |
| 安全与可信数据 | 13 | **14** | 15 | **+1**：在已经由 V91/V92 不可变来源与 append-only 审计、V182 结算行独立复核之外，V229 新增同一 PG18 快照的**审计链完整性反证**，严格区分来源初次 STAGED audit 的 1 条正常/0 条缺失/2 条重复、独立 reviewer/状态不匹配，精确租户/Marketplace/Owner HTTP401/404，不允许仅凭来源状态、用户声明 SHA 或 append-only 性质放行；真 PG18 Job #138338765615 逐项证实。剩余生产 IAM/KMS/真实外部主体/独立验证仍是生产 GO 硬阻断。 |
| 前端与交互体验 | 9 | **9** | 10 | V228 安全 TSV 暂存/Orval/Next 财务页存在；V229 只读 API 尚未成为经真实用户研究验证的完整 UI，故本轮不加分 |
| 测试、运营与发布 | 7 | **7** | 10 | 同 SHA Java/Next/Chromium/Compose/PG18 A3 全过，但真实目标 TLS/PITR/RPO/RTO/外部告警/KMS、用户本地 HTTP500 根因与正式发布签署仍未验收，不重复计分 |
| **合计** | **73** | **74** | **100** | **安全审计完整性被真实负例补强，不是假定外部真实来源已经可记账** |

现在非真实利润五项为 **64/75（约 85.3%）**，财务真实性只有 **10/25（40%）**。该子分只是对工程/产品成熟度的拆分分析，**不得**写作生产就绪度、业务功能完成率或真正 Amazon ERP 可上市概率。

## 精确代码、CI 真实来源与保留的限制

- 新 [V229 原始来源审计完整性合同](../finance/v229-source-review-audit-integrity-and-external-proof-hold.md) 的**精确代码 SHA** 为 [`3c857c788b6eeac5db8fa770caff14b7a253ab0a`](https://github.com/kunlong-luo/merchivra/commit/3c857c788b6eeac5db8fa770caff14b7a253ab0a)，[完整 A3 #45363362444（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45363362444) 严格对应这一代码 SHA；真实 PG18 Job **#138338765615** 打印 `V229_REAL_OWNER_AUDIT` 和 `PASS V229`：正常 STAGED=1、本地无初始 audit=0、非法重复 audit=2、匿名401、错误 Marketplace404、GET 0 个 V113 epoch tick、所有外部 issuer/charge/COGS/profit 强制 false/null。真实 V222/V225/V226/V227/V228 的既有 Owner/PG18 回归同轮重跑 PASS。
- 同一代码提交的独立快速 CI #45363362446 因后续快速文档提交的并发策略**被取消（CANCELLED），不能说 SUCCESS**。完整 A3 已经包含 Java/Next/浏览器/Compose/PG18 所有七个目标；文档末尾的 `main` 新提交仍需自己的快速 CI 验证，不能倒置不同 SHA 的证据。
- 负例仅在不可复用的 GitHub Actions disposable PG18 构造，严格固定 OAuth OFF/本地安全凭证。不加载真实银行、Amazon/Ads、物流/WMS 文件，也不增加会计确认、批量 reviewer 审核或真实生产授权。
- GitHub [#12 全成本利润](https://github.com/kunlong-luo/merchivra/issues/12)、[#15 G1/DEC 财务口径](https://github.com/kunlong-luo/merchivra/issues/15)、[#104 真实生产环境签署](https://github.com/kunlong-luo/merchivra/issues/104) 都仍未取得必要的真实证据；即便这些 Issue 的描述或状态发生变化，也不能从合成 A3 推断真实费用可以计入利润。

## 怎样实质提高下一阶段综合评分

V229 这 1 分属于**缺失/重复内部审计可检测性**。下一轮 [V230](../roadmap/current-plan.md) 应优先完成“声明的 SHA-256/本地 maker-checker”与“可独立核证的**真正第三方原始文件和签发者身份**”之间的强证据合同；再推动实际 Amazon Finances/Settlement/Ads、货代/税费、银行/WMS 逐项合法核证，关联真实订单/批次/退款 COGS、独立财务口径签署。在取得这类真实证据前，**不应继续提高 10/25 的利润真实性分数**，更不能把本地 `localAuditComplete=true` 作为已核验真实净利润的开关。

生产仍为 **pre-alpha / NO-GO**；`realizedNetProfit=null`，真实 Amazon Seller/Ads OAuth 关闭，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) 继续阻断。
