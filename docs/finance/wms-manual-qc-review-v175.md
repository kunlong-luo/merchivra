# V175-A · WMS 手工 QC 差异复核（非真实实收认证）

状态：**V175-A 已合并且合成技术 A3 通过（2026-11-17）**。本期仅实现 V175 第一批可审计人工 QC 复核基础，并**没有完成**独立真实运营商身份认证或物理仓库收货核验。

## V175-A 合成技术验收归档（2026-11-17）

PR [#163](https://github.com/kunlong-luo/merchivra/pull/163) 合并 SHA `c05a990579380a694e17cc84e28e8284ad5ba51f`；最新分支快速 [CI #44011618464](https://github.com/kunlong-luo/merchivra/actions/runs/44011618464) **4/4 SUCCESS**（包括 PostgreSQL 18 V100）；合并后精确 SHA [完整 A3 #44012031121](https://github.com/kunlong-luo/merchivra/actions/runs/44012031121) **run_attempt=1，7/7 SUCCESS**（Flyway、Java25、Next16/Vite/Chromium、真实 PG18 与 Compose HTTP）。

- 首轮增加专项 PG18 后的 [CI #44011300873](https://github.com/kunlong-luo/merchivra/actions/runs/44011300873) **FAILED**：函数源码文本空格断言失配，Flyway V100 与原有 V174 及财务 PG18 合成检查已通过；修正仅测试文本匹配后，再以实际 PG18 临时影子测试执行三人权限、拒绝重复和 append-only 触发器，最终 #44011618464 SUCCESS。不能称整个开发过程首次无失败。
- **已验证边界**：PG18 实际应用 V100，复合 FK/唯一与不可变触发器、Owner/匿名/错误请求的负例 HTTP，触发器函数在 disposable 影子表中执行第三人独立通过、同人操作和重复决定拒绝；V162/V64/V39 不产生过账。
- **尚未验收**：跨三名真实独立 Owner 的完整正向 HTTP 实物收货流程（当前仅数据库触发器行为和负例 HTTP）；可信外部 WMS 主体身份、真实签收、可售 QC/库存、历史 COGS 原币回转及净利润确认。生产 #104 仍 NO-GO，Seller/Ads OAuth OFF。

## 目的与范围

V174 已存储不可变 Ed25519 V2 签名外部报告与本地 V172 包裹的数量/处置差异，但两者都不能证明实物收货。V175-A 添加第三组独立的**人工 QC 观察**（件数、处置、受约束的原始证据编号、说明）和由另一名 Owner 作出的一次性审查结论。ACKNOWLEDGED 表示 **已审阅记录**，不是“收货认证”；REJECTED 表示审查被拒。任何结论都保留 V174 和 V172 原始数据，不覆盖、不做余额调整。

- Flyway **V100**：对 V174 的租户/Marketplace/退款事件/包裹/仓库/观察 ID 添加复合唯一引用范围；`finance_wms_qc_case_v100` 每条已签 V174 观察至多一份不可变 QC 材料；`finance_wms_qc_decision_v100` 每个 QC 案件至多一个不可变结论。拒绝后不可重用当前案件/签名来“洗白”差异，需新合规来源/后续独立版本。
- 服务用 PostgreSQL `REPEATABLE_READ`；先锁对应签名来源，再锁 V172 本地包裹，重检原始证据仍新鲜、来源公钥 `APPROVED`。撤销或过期的观察不能新增材料，也不能 ACK；可以继续对历史材料执行 REJECT 并存证。
- **三人角色分离**：签名观察导入人 ≠ QC 材料登记人 ≠ 最终决策人。Java 业务校验和 PG18 行级触发器均检查；数据库唯一约束阻止并发重复登记和多次决策。路由从 Spring Session 的当前 Owner 获取租户与用户，写操作继续走既有 CSRF。
- Next.js 16 / React 19 与 Vite 回退共用的 `WmsQcReviewPanel` 展示本地数量、签名外部数量、人工 QC 数量及独立决策。只显示当前冻结原因；不会声称签名公钥为真实 WMS 身份。

## 安全语义

任何数据行都强制 `operator_identity_verified=false`、`physical_receipt_verified=false`、`inventory_posted=false`、`cogs_reversed=false`。API 也返回 `cogsReversalEligible=false`、`actualCogsReversal=null`。签名、手工 QC、三名 Owner 的决策均**不是**第三方仓库已交付证明。

动态冻结顺序：本地 V63/V171/V172 过期 → 公钥已撤销 → QC 被驳回 → 待独立复核 → QC/签名数量或处置差异 → 本地/签名数据不一致 → **运营商身份始终未证实**。所有情况持续禁止 V39 库存与历史 COGS 回转、V162/V64 已验证净利润入账。

## API

以下四条均在 `/api/v1/finance/marketplaces/{marketplaceId}/events/{eventId}/return-intakes/{intakeId}/wms-qc-review` 下：

- `GET /` — 当前包裹唯一 QC 案件（可能无案件），Owner 会话隔离与 no-store。
- `POST /` — 创建不可变 QC 材料，要求 `{qcQuantity,qcCondition,evidenceReference,explanation}`。
- `POST /{caseId}/acknowledge` — 独立审阅并确认**看到** QC 材料，说明至少 10 字；依然冻结。
- `POST /{caseId}/reject` — 独立驳回，不可重审，保留原因。

只有 V174 新版签名观察支持 QC 案件；V173 老签名不能隐式升级为 V174，历史外部观察仍保留。单纯 409 不是证据丢失，已提交并验签的 V174 观察保持可审计。

## 验收要求（未执行不可打勾）

1. 快速 PR CI：Java 25 Gradle + React 19 Vite/Next.js 16 TypeScript + Compose 静态检查；静态测试覆盖来源范围、两张不可变表、三人隔离与财务只读。
2. **专项真实 PG18**：Flyway V100 与 V99 全迁移验证；三个不同活跃 Owner 完整会话/CSRF；被撤销来源、过期 V171、跨租户/跨事件、同观察并发、同案件重复决策、直接 SQL 绕过与三人角色冲突均应拒绝。现有合成 CI 尚不足以证明真实仓储身份。
3. 合并后**精确新的 main SHA**运行集中 A3 全部 7 job；只有全部成功才能标记 V175-A 合成技术验收。暂不承诺通过。

**V175 尚未完成**：真正独立可信 WMS 经营主体身份锚定、可核查的第三方签收/货件/批次与可售 QC，受控库存调整、原币历史 COGS 核销、V162/V64 全成本和真实净利润。没有真实 Amazon Seller/Ads OAuth，生产 #104 与 G1/G2/G3 一律 NO-GO。
