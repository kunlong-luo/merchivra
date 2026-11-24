# V230 · 原币费用的第三方证明缺口清单、同快照组合检查与 Owner 工作台

**2026-11-24 · V230B 已按精确代码 SHA 的真实 Spring Owner / PostgreSQL18 / Next.js / Chromium 完整 A3 7/7 SUCCESS 验收；首轮因 Next DOM 重挂载竞态仅 6/7 的失败历史保留。** 本轮目标是从 **V229 完整的本地费用来源审计**走向“哪些材料才可证明真实第三方费用”的可操作准备度，而非新建财务真实性状态表、开通 Seller/Ads API 或将本地 Owner 复核升级为已认证真实利润。

## 实际 PG18 数据与首次 A3 失败（保留历史）

- [首轮代码 SHA `6baef5a3230dd799815b4dbf230ce74de55d82a3`](https://github.com/kunlong-luo/merchivra/commit/6baef5a3230dd799815b4dbf230ce74de55d82a3) 的 [完整 A3 #45367537947](https://github.com/kunlong-luo/merchivra/actions/runs/45367537947) **6/7**；Java/Gradle、Next/pnpm/Orval、Compose、mock-chain 与真实 PG18 均通过，**browser-layout FAIL**。PG18 Job **#138353131033** 原始日志同时打印 `PASS V230 live Springdoc namespaced Orval read dossier ...`、`V230_SOURCE_PROOF_DOSSIER`、`PASS V230 real PG18 Owner ...`。 
- 真 PG18 源类别：`ADS_SPEND`、`INBOUND_FREIGHT`、`FBA_STORAGE`、`RETURN_HANDLING` 各自匹配不同必需外部凭证代码。真实 Owner POST/GET 检测 V91 正常1/缺失0/重复2条初始审计；两个不同来源采用相同用户声明 SHA 时 **V165 potential overlap 保持 hold（仅本地候选，不是已确认重复扣款）**；匿名401、错误 Marketplace404；只读 GET V113 epoch +0。所有外部签发主体、原始文件重算、收费、COGS、会计批准与利润始终 false/null。
- 初轮 Next 浏览器失败是 `scripts/smoke_browser_layout.mjs` 在 Next App Router 路由转换期间，刚查到订单行就通过**未按面板限定**的 `.core-data-scroll table` 读取列，DOM 短暂被卸载导致 `Uncaught`。已在代码中改成目标 `#core-panel-orders` / `#core-panel-inventory` 表格定位，并在异步重挂载时受控重试 90×140ms；**必须仍满足选择的订单/货位、真实测试行、来源标识、非空表头/数据和列数相等**。首轮失败不能覆盖或伪写成成功。
- [**V230B 接受代码 SHA `a8e5eba353d501de9761685cdd0d05de1d340982`**](https://github.com/kunlong-luo/merchivra/commit/a8e5eba353d501de9761685cdd0d05de1d340982) 的 [完整 A3 #45368066154（run_attempt=1，**7/7 SUCCESS**）](https://github.com/kunlong-luo/merchivra/actions/runs/45368066154) 已完成。真实 PG18/Spring Job **#138355672453** 原始日志打印 `PASS V230 live Springdoc...`、`V230_SOURCE_PROOF_DOSSIER` 与 `PASS V230 real PG18 Owner...`；V229/V228/V227/V226 等原始正反例同轮通过，Gradle、Next/pnpm/Orval、Chromium（修复后严格通过）、Compose、mock-chain 均 SUCCESS。**正式独立复评分为 [V230 75/100](../governance/project-scorecard-v230-2026-11-24.md)，只因真正可操作的业务来源证明缺口流程 +1，真实利润仍10/25。**

## 原有能力与缺口

V91/V92 的手工费用来源不可变、审核事件追加；V229 [真实 PG18 7/7](v229-source-review-audit-integrity-and-external-proof-hold.md) 能独立发现初始暂存审计丢失、重复和 reviewer/状态不符。V165/V182/V94/V95/V102 是另一条独立链：识别跨来源相似身份、已冻结的报告行及其复核。**单独读 V229 的 `localAuditComplete=true` 不证明外部费用；单独读 V182 的 `SUPPORTING_LOCAL_METADATA_UNVERIFIED` 也不证明 V91 最初审计完整，更不能断言真实结算付款或不存在其他收费。** 先前 V228 财务工作台只让用户看原币暂存成功、重放和待审核状态，缺少一份按照广告/货代/退货等实际类别排列的合法外部凭证要求。

## 实现契约（不可放宽）

- `CostExternalProofDossierServiceV230` 在同一个 **read-only / REPEATABLE_READ PostgreSQL 18 事务**先调用现有 `V229.inspect`（缺来源、不活跃租户、越界 Marketplace 404），再读取同范围的**费用类别与原币**，再调用现有 `V182.assess` 读取 V165/V94/V102 的身份/结算/审核分类。三者必须属于同一 source UUID、Marketplace 与事务快照，否则返回冲突/404；不复刻 V182 SQL、不新建来源归属写入器或新的真实性审批表。
- 新 GET：`/api/v1/finance/marketplaces/{marketplaceId}/cost-sources/{sourceId}/external-proof-dossier`，operationId 为 `getManualCostExternalProofDossierV230`。复用 Session owner/tenant 和服务器授权权限、`Cache-Control: no-store`；不接受浏览器 tenant，也不返回 SHA 原文、用户发票全文、银行账户、审计备注、Seller/Ads 令牌或其他租户财务身份。
- 本地状态独立分组：`LOCAL_AUDIT_INCONSISTENT_HOLD` 优先于其他“看起来有正向的本地结算关联”；`LOCAL_SOURCE_REJECTED`、`LOCAL_METADATA_REVIEW_PENDING`、`LOCAL_CROSS_SOURCE_CONFLICT_HOLD` 分别标注；只有本地 V91 已独立审核、完整且无当前可识别碰撞时，才可称 `LOCAL_REVIEW_COMPLETE_EXTERNAL_UNVERIFIED`。**即使如此仍永远不是 VERIFIED**。
- 分类别材料：广告需实际 Ads 报告/收费行；FBA 仓储需真实平台费用行；头程/FBM 物流需要货代/承运商发票、运单、实收；退货处理需要实际 QC/收件和原出库 COGS 冲回；进口关税需报关/税单；支付和汇兑需银行/支付服务商费用及汇率决策；其他经营费用需合同与独立履约/发票。共通要求是独立获取的**原文件字节与重算 SHA-256**、合法签发主体、原币金额与订单/SKU/批次/期间守恒、重复收费排除、独立会计签署。**不能要求所有物流或关税费用都必须出现在 Amazon 结算行**；不同成本类别的合法凭证不同。
- 每一条 `requiredExternalProof[].independentlyVerified` 固定 `false`，同时 `originalBytesVerified=false`、`issuerIdentityVerified=false`、`originalChargeVerified=false`、`duplicateDebitExcluded=false`、`settlementOrPaymentReconciled=false`、`actualLandedOrRefundCogsVerified=false`、`accountingPolicyApproved=false`、`eligibleForRealizedProfit=false`、`realizedNetProfit=null`。**这是合法原始证据待办清单，不是创建新外部证明记录、接入平台授权或财务过账能力**。
- 前端增加新的审阅 OpenAPI/Orval GET 合同到已有 `apps/web/openapi/manual-cost-v228.json`，继续使用同一 Orval v8 `react-query/fetch` + `merchivraFetch` Session/no-store，不新增手写第二套 HTTP。独立 Zod 响应校验**拒绝任何外部已核验布尔值、非 null 利润、缺失类别要求、相互矛盾的本地审核状态、跨 Marketplace/币种/来源 UUID 或没有 no-store 的响应**。
- `ManualCostBatchPanelV228` 的每条成功暂存/重放结果旁增加“查看缺口”；点击后 `ManualCostProofDossierPanelV230` 查询并列出按来源类别的材料、当前本地审计/结算关联状态和所有未满足的外部要求。切换 Marketplace 时沿用原有 key 重置；读取出错时显示阻断，不假装“无风险”，没有文件上传、未核验转已核验按钮、财务写入/COGS 确认入口。

## 自动验收、失败与评分边界

- Java：`CostExternalProofDossierV230Test` 必须覆盖八种类别、已审核完整但财务仍不可认定、审计缺失优先于正向 V94 本地关联、冲突/拒绝/暂存待审核、混合来源/站点拒绝、Owner Session/no-store 以及不含任何财务写入 SQL。
- TypeScript：`manual-cost-proof-v230.test.ts` 必须覆盖相同 UUID/原币、安全响应、伪造的外部 `true`、非法利润字符串、伪造别的站点身份、缺材料、相互矛盾的审核状态及不支持缓存的读取。类型客户端应由 pnpm prebuild/pretest 经真实静态合同 SHA 重生成，不能手改 `src/generated`。
- 真实 PG18：`smoke_external_cost_proof_dossier_v230.py` 仅可在仓库 GitHub Actions `main` / `ci-v222` / disposable PG18 / 明确 SP-API/OAuth OFF 运行。它必须通过真实 Owner Cookie/CSRF POST 暂存四类来源，再由 GET 独立确认不同类别材料、V91 正常/缺失/重复审计对整份清单的优先级、同摘要不同源的 V165 本地碰撞提示、匿名401/跨 Marketplace404、只读 GET 0 个 V113 版本写入。**禁止在真实运营数据库插入“缺审计/重复审计”负例**。
- 真实 Springdoc：现有 `scripts/check_manual_cost_openapi_v228.py` 扩展校验 V230 GET 路径、operationId、`@Schema(name)` 唯一身份以及响应中类别/原币/外部证明数组/全部禁止的真实费用和利润字段，不能以“只匹配 200”替代真实 DTO 字段完整性。
- 精确代码 SHA 的 7/7 完整 A3、Java/Next/Chromium/Compose/mock-chain/真 PG18 和新 Vitest 全部成功，才能标记 V230 已验收。**首轮 A3 #45367537947 为6/7，浏览器竞态失败已留档；V230B 精确 SHA #45368066154 现为完整7/7 SUCCESS**。

## 下一阶段 V231 与评分

V230 解决了**财务来源可操作核证准备度**，但因为用户尚未授权真实 Seller/Ads、未提供独立签发的原始发票/银行/报关资料、外部 WMS 实收未证实、退款实际 COGS 与 DEC-06/07/08 口径未批准，真实利润仍 `null`，生产 #104 **NO-GO**。已根据完整7/7形成 [V230 新版 **75/100** 独立评分](../governance/project-scorecard-v230-2026-11-24.md)（旧 [V229 74/100](../governance/project-scorecard-v229-2026-11-24.md) 保留历史）：只按原六维**业务工作流完整性 +1（16→17）**，真实利润 **10/25 不变**、安全14/15和测试7/10不重复加分。

V231 优先沿用这一类别化清单，针对维护者在本地取得的**合法、可复核、真实第三方原始文件字节与签发方**定义独立验真、保密存储、撤销和失效机制；禁止操作者单方面上传自己的声明 hash 就自动 `VERIFIED`。同时推动真实订单/退款/货件的 landed COGS 守恒和 G1/DEC 签署。未经真实授权仍保持 Amazon Seller/Ads OAuth OFF。
