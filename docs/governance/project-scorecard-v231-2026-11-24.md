# Merchivra · V231 独立成熟度重新评分（2026-11-24）

**最新统一评分：76/100（V230 [75/100](project-scorecard-v230-2026-11-24.md) → +1）。** 原六维满分不改，也不追溯修改旧 [V229 74](project-scorecard-v229-2026-11-24.md)、[V228 73](project-scorecard-v228-2026-11-24.md) 和 [V200 70](project-scorecard-v200-2026-11-22.md)；评分是内部产品/工程**定性成熟度**，**不是**生产可用率、真实利润可信度或实际 Amazon ERP 上线完成率。

## 按原评分模型复评

| 维度 | V230 | V231 | 满分 | 证据、为何加分或不加分 |
| --- | ---: | ---: | ---: | --- |
| 架构与技术 | 18 | **18** | 20 | Java25/Spring Boot4.1.2/Gradle/PostgreSQL18，React19/Next16.4.5/Node24/pnpm12.20.0 保持；V231 未独立证明目标生产环境，不加分 |
| **业务功能完整性** | 17 | **18** | 20 | **+1**：V230 原币手工来源只列“原始文件字节仍未核验”；V231 现在可由操作者在同一来源 UUID 下选择合法持有的原文件，在**本地浏览器**按**真实字节**重算 SHA-256，对比 V91 用户原有 `documentSha256` 声明，明确显示 **MATCH 仅本地自述一致 / MISMATCH 阻断**，更换来源重置；1 B–8 MiB、安全 Crypto/格式/变化长度处理、没有文件上传。已通过精确 SHA 的 5 项新增 Vitest，完整前端 94 文件/461 项，Next/Chromium 合成回归成功。解决“原手工摘要甚至未对照文件内容”的一个真实可用操作缺口；并未新增可信外部签发人或真实利润，所以**仅此项 +1** |
| **全成本/真实净利润真实性** | 10 | **10** | 25 | 仍无独立 Amazon Seller/Ads/银行/货代/报关/WMS 第三方原始签发身份证明，缺真实物流/退款 COGS 与 DEC-06/07/08、G1 正式签署；WebCrypto `MATCH` 是**同一用户自述 SHA 的局部内容一致性**，不是源系统认证、支付核证或允许入账。所有外部 truth flags 仍 false，`realizedNetProfit=null`，绝不能加分 |
| 安全与可信数据 | 14 | **14** | 15 | 本地字节不上传、明示敏感边界与 fail-closed；既有安全分已包括来源不可变、Session、跨租户/审计阻断，未验收生产 IAM/KMS/第三方主体，不重复加分 |
| 前端与交互体验 | 9 | **9** | 10 | 在 Next 财务证据清单新增本地文件选择/重算，不显示文件名也不保留 File state；缺真实最终用户手动/可访问性与移动端专项验收，不独立追加一分 |
| 测试、运营与发布 | 7 | **7** | 10 | V231 强化了目录/文档守卫，完整 A3/源码/CI 都成功；但真实目标 TLS、PITR/RPO/RTO、KMS、外部告警接收、人工用户体验/生产上线签署缺失，仍不加分 |
| **合计** | **75** | **76** | **100** | **唯一新增分数归于可操作的文件字节校验，非文档数量，也非财务真实性** |

非“全成本/真实净利润”五项合计 **66/75（88%）**，利润真实性仍 **10/25（40%）**。这个拆分**不能称为生产就绪度**；产品在真正合法账单与会计审计未完成前仍 pre-alpha / **NO-GO**。

## 精确代码、独立正反测试与仓库裁剪事实

- V231 **真实代码与仓库目录清理的精确提交 SHA**：[**`9ff02147de17996d28ec95875a4eaa6445e1c197`**](https://github.com/kunlong-luo/merchivra/commit/9ff02147de17996d28ec95875a4eaa6445e1c197)，对应 [完整 A3 **#45373462870，run_attempt=1，7/7 SUCCESS**](https://github.com/kunlong-luo/merchivra/actions/runs/45373462870) 和 [精确同 SHA 快速 CI **#45373462897 SUCCESS**](https://github.com/kunlong-luo/merchivra/actions/runs/45373462897)。
- Java25/Gradle、Next16/pnpm/Orval、Chromium 布局、Compose/spark-db、mock-chain 与真实 PostgreSQL18 7 个 A3 目标均成功；前端 `local-document-digest-v231.test.ts` **5项 PASS**，总计 **94 个测试文件、461 项测试全部 PASS**。测试包含标准 `abc` SHA、不同原始字节、错误/全零摘要、空文件、超过 8MiB 在读取前拒绝、读长度与文件 `size` 不一致、清除临时字节缓冲和零 `fetch` 调用；**它们全是合成数据，不代表第三方真实发票已取得**。
- 真 PG18/Spring Job **#138373426010** 复核 V229 本地 V91 审计 1/0/2、V230 类别化证据清单和跨来源冲突、租户401/404及原币财务阻断，真实日志包含 `PASS V229` 和 `PASS V230`。V231 没有新的数据库表、上传 API 或实际原始外部签发数据；不能宣称 PG18 单独验证了用户实际文件来源。
- 同一 A3 的 Compose Job **#138373425952** 执行 `python3 scripts/check_docs_consistency_v109.py`，输出 **`PASS DOC-196 documentation: 26 entry docs; 504 relative local links; current runtime/release boundaries`**。额外手动校验了 10 份权威入口的 214 个本地链接无缺失。
- 已直接从 `main` 删除冗余大小写 `Agent.md` 兼容别名（[删除提交](https://github.com/kunlong-luo/merchivra/commit/ae3dbca6cc81874dd4f1a9ee8ae7ffcc711d5017)）和停留在“等待 PR/PG18”的旧 V120 重复短稿（[删除提交](https://github.com/kunlong-luo/merchivra/commit/829dc5f31390530e757ff2f1307e88284ad24cb0)）；仍保留唯一 `AGENTS.md` 和内容完整的 [V120 退货追踪文档](../purchasing/supplier-followup-return-traces-v120.md)。原始文件历史未抹除，doc guard 会拒绝重新创建这两个已淘汰路径。
- 旧仓库按 Git Tree 盘点 1,432 个 tracked 文件；未发现跟踪 node_modules/.next/dist/build、npm/yarn lock、pom.xml 或临时日志。**没有发现可直接整目录删除而不破坏业务/回退/证据的目录**；真实代码目录、`deploy/`、`ops/`、`.github/`、`scripts/` 和 `docs/` 历史证据保留。根中英文 README 清楚说明每个目录及子领域作用；大幅精简了 `docs/README.md`、`docs/current-status.md`、`docs/roadmap/current-plan.md` 的版本流水账，旧详细来源仍可从精确 Vxxx 文档、旧标记 ARCHIVE 的路线与 Git 历史查阅。
- **不能把 A3 全绿等同于真实业务可上线。** 生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) 仍 NO-GO，Seller/Ads OAuth OFF，`realizedNetProfit=null`。

## 下一阶段怎样真正显著提高分数

[V232 现行 P0](../roadmap/current-plan.md) 应围绕合法第三方签发来源、独立授权/撤销、原始字节身份、费用行原币与订单/批次唯一归属、真实入库/退货 COGS 与 DEC-06/07/08/G1 共同签署，在**取得真实外部资料**后独立验证。不要再因为整理 README 或增加同类只读缺口卡片自动加分；在外部证据和会计决策完成之前，**真实利润 10/25 不得调整**。

本次文档-only 后续提交的快速 CI 是另外的精确 SHA；不能拿上述代码 A3 给尚未验证的新提交“继承”测试结果。


## V279 复评补充（2026-11-26，不改 V231 历史评分）

V277 完整 A3 通过，V278 快 CI 通过、完整 A3 待核，V279 实际工作台待验收；维持六维 76/100，真实利润 10/25。工程证据不能替代真实 E1–E5/G1/G2/G3；生产 NO-GO。当前发布分层与具体阻塞见 [V143 生产准入](../operations/v143-production-release-preflight.md)。
