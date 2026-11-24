# Merchivra · 唯一现行 P0 执行计划

**CURRENT · 2026-11-24 · V230B 与 V231 均已按各自精确代码 SHA 的完整 A3 7/7 验收；下一阶段 V232 聚焦真正合法独立外部原始费用。**

> **北极星**：对 Amazon Seller ERP 的每笔订单、退款、SKU、采购批次及每个原币费用来源建立可以由独立第三方验证的记录，覆盖全成本/真实库存 COGS，最后在经批准的 G1/DEC 会计政策下生成可审计期间净利润。不能为了显示一个数字填补 UNKNOWN 为0。项目仍为 pre-alpha、`realizedNetProfit=null`，真实 Seller/Ads OAuth OFF，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。

**现行技术与开发约束**：`main` 直接提交，Java25/Spring Boot4.1.2/**Gradle**/PostgreSQL 18、React19/**Next.js 16.4.5 默认**、Node24/**pnpm 12.20.0 唯一 JS 包管理器**；Vite 只作明确回退。规范见 [AGENTS.md](../../AGENTS.md) 与 [ENG-001](../governance/engineering-standards.md)。

## 近期已验收的工程基线

| 版本 | 已能证实的技术能力 | 什么仍不代表真实费用 |
| --- | --- | --- |
| V194/V195 历史 | V194 订单/SKU 金融指纹、冻结证据独立重算；V195 本地 Session 500 更友好的错误状态，历史 [V195 快 CI #45016097852](https://github.com/kunlong-luo/merchivra/actions/runs/45016097852) | 本地500真实根因、Amazon/银行没有因前端修复而获验证 |
| V203–V225 | 采购出库/批次身份、退货和实际成本守恒的技术阻断、租户 ABA/epoch 和不可变源审计及真实 PostgreSQL18 并发负例 | 合成 WMS/银行/平台账号不等于第三方实际签发、G1/DEC 也没有被代码签署 |
| V226–V228C | 原币 Owner 1–20 条 V91 手工费用批量事务、Source 单次 STAGED 审计和原 UUID 重放；Next/Orval/Zod 按原币显示未核验来源 | 本地来源/声明 SHA/人工 review 不等于任何实际扣款 |
| V229 | [PG18 真 Owner 审计 1/0/2 及 401/404](../finance/v229-source-review-audit-integrity-and-external-proof-hold.md)，[A3 #45363362444（7/7）](https://github.com/kunlong-luo/merchivra/actions/runs/45363362444) | append-only 与 Maker/Checker 仅证明本地审核链 |
| V230B | [八类原币费用第三方证明缺口 + Next 逐来源查看](../finance/v230-external-source-proof-dossier-and-owner-workflow.md)，[精确 SHA `a8e5eba353d5`](https://github.com/kunlong-luo/merchivra/commit/a8e5eba353d501de9761685cdd0d05de1d340982) 的 [完整 A3 #45368066154（7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45368066154) | 只标出第三方材料缺失并 hold，未新增 VERIFIED 发票、实收和净利润 |

V230 首轮 [#45367537947](https://github.com/kunlong-luo/merchivra/actions/runs/45367537947) 是 **6/7**：PG18 已通过、Chromium/Next 路由重挂载竞态失败；修复后精确 SHA 7/7 才获技术验收，失败历史保留。当前独立评分 [76/100（V231）](../governance/project-scorecard-v231-2026-11-24.md)；V230 75、V229 74、V228 73 与 V200 70 均为对应**历史**评分，不能随意修改。**真实利润真实性仍10/25**，绝不因 UI 或 CI 自然加分。

## V231 已验收 · 本地文件字节对照 + 文档/目录收敛

**已实现并按精确代码 SHA 验收**：[代码 `9ff02147de17`](https://github.com/kunlong-luo/merchivra/commit/9ff02147de17996d28ec95875a4eaa6445e1c197) 的 [完整 A3 #45373462870（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45373462870) 及同 SHA [快速 CI #45373462897（SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45373462897)。全套 Next/Java/Chromium/Compose/真 PG18 通过，94个 Vitest 文件/461项测试；DOC-196 26入口文档/504相对链接 PASS。

1. **原始字节核对的最小合法能力**：对 V226/V228 手工来源返回的不可变 UUID 与用户声明的 `documentSha256`，允许操作者**自愿选择本地持有文件**。用 Web Crypto SHA-256 在浏览器本地对文件**原始字节**重算，文件大小 1 B–8 MiB，空文件、超限、无加密 API、声明摘要格式错误、不完整字节读均不成功；文件内容/文件名不上传、不保存。匹配仅是 `LOCAL_BYTES_MATCH_USER_CLAIM_ONLY`，不匹配是 `LOCAL_BYTES_MISMATCH_HOLD`，两种状态都**不是独立第三方认证**、不会修改外部核验标志、费用归属、COGS 或 `realizedNetProfit`。
2. **失败安全与测试**：Vitest 需覆盖已知 SHA-256 正向、不同字节负例、64 位格式/全零摘要、空文件、8 MiB 越限、读长度不一致以及从未调用网络 `fetch`；浏览器更换来源必须清掉历史匹配结果，不得用一个来源摘要套另一个 Marketplace。已存在的 V230 Zod/Orval 跨租户/原币/false-profit 不可被本轮修改。
3. **文档与仓库整理**：清除确认重复的混合大小写 `Agent.md` 兼容别名和 V120 旧“PR/CI 待验收”重复短稿；保留唯一 `AGENTS.md`、正式 V120 证据、Flyway 迁移、所有 A3 原始失败/复验、历史评分、真实部署/回退脚本。重写根中英文 README、文档索引与当前状态/计划，列出**每个顶层及文档域目录用途**，不能仅因单文件目录而删除有效资料。自动化文档一致性守卫应拒绝旧冗余文件重新出现，检查入口文件的本地链接/现行评分与包管理约束。
4. **验收**：`main` 代码 SHA `9ff02147de17` 的 [完整 A3 #45373462870（7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45373462870) 已覆盖 pnpm/Next/Vitest 94文件461项、Gradle、Chromium、Compose/mock-chain、真实 PG18 及前序 V229/V230；[快 CI #45373462897](https://github.com/kunlong-luo/merchivra/actions/runs/45373462897) 也成功，DOC-196 检查504条链接并防止旧别名/草稿回归。仅在此基础上 [复评 76/100](../governance/project-scorecard-v231-2026-11-24.md)（业务完整性+1），**真实利润真实性10/25 不变**。

详情和保留/删除依据集中见 [V231 专项记录](../finance/v231-local-byte-claim-check-and-repository-pruning.md) 和 [README 目录用途](../../README.zh-CN.md)。

## 下一阶段 V232：真正提升真实利润可信度的 P0

| 顺序 | 任务（可用现有能力必须复用） | 真正完成的独立证据与阻断门槛 |
| --- | --- | --- |
| P0-A | **取得合法的外部签发方与费用原件**：Seller Finances/Settlement、Ads、银行支付、头程/报关/WMS 的原文件和 API 可信响应；重新计算文件 SHA，绑定签发主体/权限有效期/撤销与原始报告行 | 不能用 `MANUAL_UNVERIFIED` 的文件和摘要由同一 Owner 单方面认证；没有供应商独立签名/授权与行级核验渠道就维持缺口和 NO-GO |
| P0-B | **按原币/订单/SKU/批次/整期守恒计算所有成本与退款 COGS**：采购成本、运费关税、FBA/FBM 费、广告、支付 FX、退货实收和成本冲回 | V91/V94/V102/V165/V182/V229/V230 重用，不能对已归属结算行重复计算；原始来源不完整时不得报已实现净利润 |
| P0-C | **真正经审的利润中心与报表**：V64/162/183/188 已有按原币贡献/缺口预览，之后需接入通过 G1/DEC 批准的实际成本和可追溯版本 | 期间政策、正负费用、完整 SKU/退款证据、银行/结算行/仓储实收和 G1/DEC-06/07/08 双重审查均完整；`realizedNetProfit` 才可不是 NULL |
| P0-D | **独立外仓/运营主体与生产环境**：B2 外部 WMS 真运营商、实物收货/QC 与 B3 库存财务过账；目标 TLS、KMS、PITR、恢复、告警接收和 G1/G2/G3 签署 | 本地签名持有权和三 Owner 互审不是外部仓库物理实收；生产 [#104 NO-GO](https://github.com/kunlong-luo/merchivra/issues/104) 仍是硬闸门 |

本轮用户没有提供真实 Seller/Ads、银行/承运商原始材料，且明确最后在本地授权。**因此 V231/V232 不得假装已取得数据，不得启动真实生产 OAuth 或写入财务账本**。真正显著提高真实利润分的唯一可行路线是由合法独立第三方凭证与会计签署组成的可审计链。

## 文档清理规则

- 现行入口只保留 [当前状态](../current-status.md)、本计划、[工程规范](../governance/engineering-standards.md)、[AGENTS](../../AGENTS.md)、[文档控制](../governance/document-control.md)及具体运行指引。旧历史计划 [`docs/roadmap.md`](../roadmap.md)、[V108](mvp-freeze-release-v108.md)标记 ARCHIVE，**不再重用其“下一步”**。
- 核对 [docs/README.md](../README.md) 和根 [README 中文](../../README.zh-CN.md)的每个目录用途。真实财务、Flyway、历史 A3 证据、脚本和回退资产保留；真正重复草稿删除，Git 历史可恢复。
- 每次代码和文档变更提交 `main`，记录**精确代码 SHA**、完整 A3 的成功/失败/跳过、同一 SHA 快速 CI；不复制“上一轮 7/7”冒充本轮。历史 V194/V195 技术事实保留在域文档与 Git，不需要堆积在本计划顶部几百行。
