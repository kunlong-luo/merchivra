# Merchivra · 当前文档中心

> **CURRENT / 2026-11-23 · DOC-196。** 从这里进入现行规范；不要因为旧 Vxxx 文件包含“当前/最新”就按它启动工作。实际工程为 Java25/Spring Boot4.1.2/Gradle/PG18、React19/**Next.js 16.4.5 默认**、Node24/**pnpm 12.20.0 唯一包管理**；项目 pre-alpha，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。

## 先看哪份文档

| 你的问题 | **唯一现行入口** |
| --- | --- |
| 这套系统到底已经有什么、什么仍未知？ | [当前事实与验证范围](current-status.md) |
| 现在项目成熟度是多少、为什么这样评分？ | [最新 V200 复评分：70/100](governance/project-scorecard-v200-2026-11-22.md) · [原 68/100 基线](governance/project-scorecard-2026-11-22.md) |
| 接下来先做哪些业务和证据？ | [唯一 P0 开发计划](roadmap/current-plan.md) |
| 必须遵守哪些开发/安全规范？ | [AGENTS.md](../AGENTS.md) · [ENG-001 工程实施规范](governance/engineering-standards.md) |
| 文档怎么更新、什么时候才叫 Accepted/Verified？ | [GOV-001 文档控制](governance/document-control.md) · [DOC-196 查漏补缺](governance/docs-audit-2026-11-22.md) |
| 如何在本地启动和处理会话 500？ | [开发及本地排错](development.md) · [Compose/数据安全部署](deployment/local-spark-db.md) |
| 应跑哪些测试、A3 需要什么证据？ | [CI/PG18/Chromium/A3 规则](testing/ci-policy.md) |
| 登录、权限、安全和生产阻塞？ | [安全政策](../SECURITY.md) · [上线门禁](testing/release-readiness-v68.md) |
| 如何贡献、UI 如何设计？ | [贡献规则](../CONTRIBUTING.md) · [设计约束](../DESIGN.md) |

## 业务及架构参考

| 主题 | 现行/版本参考 | 必须保留的边界 |
| --- | --- | --- |
| 技术架构与 API | [整体架构](architecture/overview.md) · [真实 Springdoc/Orval 与早期设计区分](api/README.md) | 不从历史 G2 草案生成线上客户端；Next 默认，Vite 只回退 |
| 经营利润的核心目标 | [财务规则（G1 待批准）](product/finance-rulebook.md) · [全成本覆盖 V162](finance/full-cost-coverage-v162.md) | 已知范围贡献、预览≠经审净利润 |
| 费用来源与结算证据 | [V198 多 Settlement ID / 同编号报告冲突](finance/v198-settlement-source-claim-integrity.md) · [结算身份 V181](finance/v181-immutable-settlement-line-identity.md) · [归属 V182](finance/v182-settlement-attribution-evidence.md) · [整期阻断 V183](finance/v183-full-period-profit-evidence-gates.md) | 人工审核和本地 SHA 不认证第三方费用 |
| 成本防重与利润展示 | [跨月 V185](finance/v185-original-currency-period-cost-preview.md) · [唯一归属 V186](finance/v186-single-cost-source-ownership-bridge.md) · [跨源风险 V187](finance/v187-full-period-cross-source-risk.md) · [瀑布 V188](finance/v188-period-profit-cost-waterfall-preview.md) | `APPROVED_UNVERIFIED` 不入账 |
| **冻结订单/SKU 利润证据** | [数据库不变性 V193](finance/v193-frozen-profit-snapshot-database-guards.md) · [独立重算 V194](finance/v194-versioned-profit-capture-fingerprints.md) | 历史旧摘要不能冒充 V1；V194 仍待自己的完整 PG18 A3 |
| 退货/WMS | [退款成本就绪 V170](finance/refund-cost-readiness-v170.md) · [V197 退款 COGS 阻断与来源一致性](finance/v197-refund-cogs-source-consistency-barriers.md) · [外部主体信任边界](finance/wms-operator-trust-contract-v175-b.md) | B2/B3 外部身份、物理实收、COGS 回转尚不完整 |
| 采购、库存及运营 | [采购主流程](purchasing/local-purchase-workflow.md) · [FBA 采购关联](inventory/fba-purchase-link-v75.md) | 本地模拟/审批不等于 Amazon FBA 已签收 |
| 集成与安全 | [SP-API 接入](integrations/amazon-sp-api.md) · [同源 Session/CSRF](security/next-session-csrf-v101.md) | Seller/Ads 真实授权关闭、生产 NO-GO |

## 文档分类与冲突裁决

- **CURRENT：** 本页、当前事实、唯一计划、AGENTS/ENG、GOV、开发/部署/CI/安全指引；修改运行时或业务边界时同步这些入口。
- **PROPOSED：** 财务规则、G1/G2/ADR、正式 OpenAPI 候选；未签署前不得写成 Accepted。
- **IMPLEMENTATION/EVIDENCE：** Vxxx 功能记录只对其注明的代码、日期、精确 SHA 和合成测试范围有效；旧 A3 不能让新代码自动“绿灯”。
- **ARCHIVE：** [V108 MVP](roadmap/mvp-freeze-release-v108.md)、[PLAN-001 历史路线](roadmap.md)、[V148 RTM](requirements/implementation-acceptance-rtm-v148.md)、[V109 审计](governance/docs-audit-v109.md)、[V191 审计](governance/docs-audit-v191.md)；保留历史真相但不再直接驱动任务。

文档修改**直接在 `main`**，完成后至少运行 `python3 scripts/check_docs_consistency_v109.py`、`python3 scripts/check_ci_cadence_v108.py`，核对新 SHA 的快 CI。完整范围与审阅步骤在 [GOV-001](governance/document-control.md)。

**事实提示：** V195 改善了本地 Session 500 的页面与重试，但未找到用户本机 Spring 故障根因；V194/V105 及 V197–V200 的各自精确 SHA 完整合成 A3 均已通过；不能外推至真实 Amazon/银行或本机登录服务的根因。**`realizedNetProfit=null`，公开 GitHub Pages 只是全虚构静态演示。**

最新财务后端审计：[V200 历史 SKU 映射/成本版本冲突阻断](finance/v200-historical-sku-cost-ambiguity.md)（[精确 V200 代码 `2715529`](https://github.com/kunlong-luo/merchivra/commit/271552911b55a451e66662cdf3aab94c2db21341) · [快速 CI #45023721155 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45023721155) · [完整 A3 #45023721170（7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45023721170)；仍非真实财务核证）。

当前退款财务可信度补强：[V201 跨事件审核数量读时守恒](finance/v201-refund-cross-event-quantity-read-gate.md)（本轮精确代码 7/7 合成 A3 通过）；沿用 [V200 70/100 复评分](governance/project-scorecard-v200-2026-11-22.md)，真实 G1 与生产授权仍 NO-GO。

V202 新增 [本地采购批次与订单历史 SKU 成本来源核对](finance/v202-order-local-batch-cost-provenance.md)（[精确代码 `4d07d87`](https://github.com/kunlong-luo/merchivra/commit/4d07d87c92366b661ad9c5400b759ac73d98362f) · [快速 CI #45105511319 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45105511319) · [完整 A3 #45105511271（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45105511271)）。只读核对 V20/V46/V39 本地链路，**没有 Amazon 销售→批次关联**，因此实际批次 COGS 不确认；评分仍 70/100，生产 NO-GO。

V203 继续建立 [本地销售 pick line ↔ 采购收货批次声明的可重算与失效契约](finance/v203-sale-to-lot-link-integrity-contract.md)：Flyway V106 捕获不可变来源，订单项/出库行/收货验收数量守恒，撤销不重复分配；Owner 只读接口仍将任何本地一致性声明标为外部未核证，不确认实际 COGS。已在 [精确代码 `3d94550`](https://github.com/kunlong-luo/merchivra/commit/3d94550b9a2ef5a53c15db358b390ed88b60478b) 的 [同 SHA 快 CI #45116800143 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45116800143) 与 [完整 A3 #45116800085（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45116800085) 完成隔离合成技术验收；正式评分 **70/100**、真实 Amazon/Ads OFF、生产 #104 NO-GO 不变。

V204 [签名销售出库观察与独立复算](finance/v204-signed-sale-dispatch-identity-gate.md) 新增 Flyway V107 和复用 V98/V101 的 Ed25519/密钥持有权技术证据。因当前仅能核验**租户自登记密钥**，不可能推出第三方仓库真实性、Amazon 已履约或实际批次 COGS；`realizedNetProfit=null`、70/100、生产 #104 NO-GO 均未改变。V204 [V204 精确代码 `f89485f`](https://github.com/kunlong-luo/merchivra/commit/f89485fe0b85cb63f7e12614f254c4ba57ed9be9) 的 [同 SHA 快速 CI #45122956620 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45122956620) 与 [完整 A3 #45122956610（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45122956610) 已核对成功（仅合成工程验证）。

V205 [外部运营主体/承运交接证据档案及独立 Owner 复核](finance/v205-external-dispatch-custody-dossier.md)：Flyway V108 按 V204 的当前签名出库保存两类外部文件声称摘要、身份快照、审计审查记录，并检测同来源编号的摘要冲突与历史失效。**只作内部一致性审计**，无法凭 Owner 签署或用户提供的散列证明 WMS 外部主体、真实物流/交付、Amazon 履约或批次成本；V205 [V205 精确代码 `99ef9b9`](https://github.com/kunlong-luo/merchivra/commit/99ef9b988de101f3413e0821817071bcce85c370) 的 [同 SHA 快速 CI #45132910751 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45132910751)、[完整 A3 #45132910387（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45132910387) 已通过（仅隔离合成工程测试）。真实 Seller/Ads OAuth 仍 OFF，`realizedNetProfit=null`、正式评分 70/100、生产 #104 NO-GO。

V206 [私有运营商/承运交接原件与 PG18 内容哈希独立复算](finance/v206-private-custody-original-content-integrity.md) 复用既有 PDF/PNG/JPEG/ClamAV 安全验证与 2 MiB 限制，并在 V109 冻结原件字节、自动生成摘要；读时缺原件/内容漂移/上游签名撤销仍阻断。**仅验证原件字节与租户声称摘要的匹配**，不验证实际外部发件人、运营主体或 Amazon 履约，实际 COGS/利润继续 NULL，生产 #104 NO-GO。V206 [V206 精确代码 `c85feba`](https://github.com/kunlong-luo/merchivra/commit/c85feba8cf77919e89a1626d9ddc0981f13deb74) 的 [同 SHA 快速 CI #45138354698 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45138354698) 和 [完整 A3 #45138354709（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45138354709) 已核对成功（仅隔离合成工程验证）。

V207 [私有原件法律保全、独立留存复核与不可变元数据访问审计](finance/v207-custody-original-retention-governance.md)：Flyway V110 默认 `legalHold=true`、内部审查无删除权限，跨租户读取或上游证据撤销不放行，审计 SHA/大小由数据库捕获。本阶段 [V207 精确代码 `8eb1b86`](https://github.com/kunlong-luo/merchivra/commit/8eb1b86a26caf8f73149e9dab6215e65c9a2574d) 的 [同 SHA 快速 CI #45140330888 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45140330888) 和 [完整 A3 #45140331187（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45140331187) 已成功；不能当成第三方文件签发方或 Amazon 真实履约确认。正式评分 70/100、`realizedNetProfit=null`、Seller/Ads OFF、生产 #104 NO-GO。

V208 [外部声称签发方 Ed25519 对 V206 原件及 V205 出库档案的不可变签名绑定](finance/v208-claimed-issuer-ed25519-signature-integrity.md)：Flyway V111 区分运营商和承运类的 tenant-enrolled 公钥、另一 Owner 的 `REVIEWED_UNVERIFIED`、撤销与签名读时独立重算，严格绑定原件 SHA/订单/出库 ID；**不能把租户自登记密钥称为独立第三方签发身份验证**。V208 [V208 精确代码 `6678558`](https://github.com/kunlong-luo/merchivra/commit/66785586f1856fc5647c48bbf4a975152a16fce7) 的 [同 SHA 快速 CI #45150207475 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45150207475) 与 [完整 A3 #45150207574（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45150207574) 已确认通过（仅隔离合成工程验证）；真实 Seller/Ads OAuth OFF，`realizedNetProfit=null`，评分 70/100、生产 #104 NO-GO。

V209 [部署侧离线 X.509/PKIX 及签名 CRL 外部证书准入准备度](finance/v209-operator-offline-pkix-crl-readiness.md)：完全默认关闭，仅运维受限路径/运营商与承运方独立 DNS 白名单；Java 25 本地证书链、SPKI 指纹与 CRL 实时重算，**技术证书链通过不代表真实机构身份或 Amazon 履约已认证**。无新 Flyway，本轮 [V209 精确代码 `0a9dc09`](https://github.com/kunlong-luo/merchivra/commit/0a9dc0903a92b64d5891c03011f5b3ef85fbc560) 的 [同 SHA 快速 CI #45155665615 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45155665615)、[完整 A3 #45155665675（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45155665675)、[同 SHA Redis Session 集成 #45155665716 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45155665716) 已核验成功；项目真实 OAuth OFF、利润 NULL、生产 #104 NO-GO。

V210 [订单级完整多次部分退款数量守恒、来源快照核对与 COGS 硬阻断](finance/v210-whole-order-refund-cogs-conservation.md)：复用 V34/V35/V63/V197/V201，不分页凑数，整单超量/孤儿/原币错误时手工成本敏感度整体为 NULL；不新增 Flyway/库存过账，实际成本和 `realizedNetProfit=null` 未获核证，生产 #104 NO-GO。本轮 [V210 精确代码 `dc7e1b3`](https://github.com/kunlong-luo/merchivra/commit/dc7e1b336bc5d1ac762c2172db657b5961a8bbdd) 的 [同 SHA 快速 CI #45159869191 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45159869191) 和 [完整 A3 #45159869175（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45159869175) 均已 SUCCESS，PG18 日志核实实际运行了 V210 原始 SQL 合成脚本。

V211 [V64/V188 利润和 V210 全订单退款的跨期归属、冻结报表重叠诊断](finance/v211-period-refund-profit-inclusion-gate.md)：Next.js 利润中心新增按需展开的只读 V211 区域，三种日期轴分别显示、原币全期来源不乘法放大、跨期退款与非本期冻结订单明确阻断；重叠报表只是快照，不等于重复记账。所有真实 COGS 与 `realizedNetProfit=null`、Seller/Ads OFF、70/100、生产 #104 NO-GO 不变。[V211 代码 `fec6371`](https://github.com/kunlong-luo/merchivra/commit/fec6371386e5f1a550be6b12856d627ad94b515f) 的 [同 SHA 快速 CI #45165293616 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45165293616)、[完整 A3 #45165293551（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45165293551) 已通过，PG18 源码 SQL 合成复核及匿名 401 均在同 SHA 验收中。

V212 [退款订单行 × 原销售批次认领 × 本地返仓数量守恒](finance/v212-refund-original-sale-lot-quantity-envelope.md)：复用 V210/V203/V106/V171/V172，不改现有账本或新增迁移。在 Next.js V211 退款诊断中选择订单按需核对数量、撤销/漂移与原采购参考单价，严格说明**无逐件原批次映射、无实际批次 COGS 及净利润**。真实 OAuth OFF、正式 70/100、生产 #104 NO-GO；[V212 精确代码 `921c2e2`](https://github.com/kunlong-luo/merchivra/commit/921c2e250ad4554349fb290e777c650b279fc259) 的 [同 SHA 快速 CI #45170646181 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45170646181)、[完整 A3 #45170646128（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45170646128) 已 SUCCESS；PG18 V212 source-exact SQL 和新路由匿名 401 均有日志证据。

V213 [原采购收货跨订单全局容量与真实落地成本缺证](finance/v213-global-receipt-capacity-and-landed-cost-hold.md)：V212 退款订单下按需展开，检查跨全部本地订单的原采购收货领取量、历史撤销仍占原容量、QC/采购参考单价与原币变化；不推断实际 Amazon 发货或退货原批次，COGS/净利润 NULL。项目评分 70/100、生产 #104 NO-GO；[V213 代码 `e560b63`](https://github.com/kunlong-luo/merchivra/commit/e560b63c9c56987811c98b4a793e72bcc69e4fde) · [同 SHA 快速 CI #45177212591 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45177212591) · [完整 A3 #45177212593（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45177212593) 已完成；PG18 原始 SQL TEMP 回归及匿名 Owner GET 401 有同 SHA 日志。

V214 [原币人工费用对采购收货单的缺失身份与落地成本阻断](finance/v214-manual-cost-receipt-provenance-hold.md)：V91/V92 目前只有人工费用元数据与 SKU/ORDER/PERIOD 分摊，没有直接 receipt 身份；新增受 Owner 保护的只读后端候选元数据覆盖诊断，无实际采购批次金额或财务过账。V214 精确代码 `9de829f` 的 [快 CI #45193406175 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45193406175)，PG18 专项 SKIPPED、完整 A3 未执行；70/100，真实 OAuth OFF，`realizedNetProfit=null`、生产 #104 NO-GO。
