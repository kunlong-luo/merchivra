# Merchivra V205 · 外部运营主体与承运交接证据档案（内部审计，未认证外部真值）

**2026-11-23 / Flyway V108 / [V205 精确代码 `99ef9b9`](https://github.com/kunlong-luo/merchivra/commit/99ef9b988de101f3413e0821817071bcce85c370) · [同 SHA 快速 CI #45132910751 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45132910751) · [完整 A3 #45132910387（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45132910387)。全部测试都是隔离合成工程验证，不代表真实第三方运营主体身份。**

## 为什么做、为什么不直接标记 VERIFIED

V203/V106 是 **本地订单项→本地 pick→采购收货批次** 的数量守恒声明；V204/V107 证明一把**租户自己登记**的 Ed25519 密钥曾签署与当前本地记录一致的出库观察，绝不证明 Amazon 已履约或 WMS 的外部法人/设备真实身份。现有 V98 的来源审批和 V101 的密钥持有权证明不构成第三方独立认证，且不能因此获得实际 COGS/利润。

V205 新增的，是把下一阶段需要向外部可信渠道取得的**运营商注册证据与承运交接凭据**先变成可查询、不可篡改、可分离复核的独立**证据档案**。本阶段 **没有**真实外部登记机构/承运商/签收方 API 对接，也没有接收到经核证的原始文件，用户输入的文件 SHA-256 是**声称摘要**，不是已独立重算的文件摘要。即使内部审查意见认为引用格式一致，也只准返回 `INTERNAL_REVIEW_ONLY_EXTERNAL_UNVERIFIED`。

## V108 数据契约

- `finance_dispatch_custody_dossier_v108` 以同租户 `signed_dispatch_id` 唯一关联 V107，冻结 `claim_id`、仓库、签名源、出库编号、签名规范化摘要和数量；**冻结值由数据库触发器覆盖客户端输入**，不可自行伪造。严禁历史回填、根据 SKU 猜 FIFO。
- 保存两个来源**彼此独立**的引用：`operator_registry_ref` + `operator_document_sha256`，`carrier_handoff_ref` + `carrier_document_sha256`，以及声称的交接时间。仅接受安全编号和严格小写 SHA-256；运营商与承运商不允许引用同一个摘要作为两份不同来源的原件。
- 整个租户范围内，相同登记源/运营商编号具有不止一个**声称原件摘要**、或相同交接编号具有不止一个**声称承运文件摘要**时，`finance_dispatch_custody_integrity_v108` 对所有相关档案返回 `EXTERNAL_REFERENCE_DIGEST_CONFLICT`。同一个承运交接文件覆盖多个订单/商品批次时，摘要相同可共享编号，不强制一票一行。
- `finance_dispatch_custody_review_v108` 是不可变的一次性独立内部文件一致性复核，合法决策仅为 `DOCS_CONSISTENT_UNVERIFIED`、`NEEDS_EVIDENCE` 和 `REJECTED`。复核人**不能是档案提交人、签名出库导入人、WMS 公钥登记人**，即使这些人都是 Owner。数据库触发器捕获两份文件的原始声称摘要快照；存在上游失效/文件冲突时不允许记录一致性通过。
- 所有档案/复核都 `append-only`，禁止 `UPDATE/DELETE`；两张表的 `operator_identity_verified`、`physical_handoff_verified`、`platform_fulfillment_verified`、`actual_batch_cogs_verified`、`cogs_posted`（有该字段处）均为 `CHECK(FALSE)`。不能以本地复核冒充 Amazon 订单项、第三方运营资质、实物交付或 G1 签署。

## 权限和调用顺序

复用 Session、CSRF、Owner、租户/Marketplace/订单范围与 Spring JDBC，不引入第二套鉴权或签名系统：

1. 先用 V203 `/sale-lot-integrity` 核对本地归属，再用 V204 `/signed-dispatch` 独立重算签名。两者出现 stale 或冲突，档案写入和“文件一致性”复核均阻断。
2. `POST /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/signed-dispatch/{signedDispatchId}/custody-dossiers`：输入登记编号、登记文档 SHA-256、承运交接编号、承运文档 SHA-256、声称交接时间；服务和数据库对签名观察、数量、源状态、时间和不可变身份双重核对。**不接受**客户自行指定可信状态或快照。
3. `POST /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/custody-dossiers/{dossierId}/review`：另一名合格 Owner 提交一次内部核对决策与 10–500 字符原因，带审计身份；重复审查和自审批拒绝。
4. `GET /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/custody-readiness`：`no-store`，先调用 V204 重新验证 Ed25519/历史 SKU/密钥撤销，再查询 V108 原始 PostgreSQL 视图。状态可能为 `SIGNED_DISPATCH_RECOMPUTATION_FAILED_OR_STALE`、`SIGNED_DISPATCH_STALE`、`CUSTODY_SNAPSHOT_STALE`、`EXTERNAL_REFERENCE_DIGEST_CONFLICT`、`EXTERNAL_EVIDENCE_UNREVIEWED`、`INTERNAL_REVIEW_ONLY_EXTERNAL_UNVERIFIED` 或 `INTERNAL_REVIEW_REJECTED_OR_INCOMPLETE`，返回缺档案数量和明确阻断原因。无论哪种状态，实际 COGS/履约/利润均不可放行。

## 合成验证范围

- `DispatchCustodyDossierV205Test`：非法外部编号/摘要、企图越级到 VERIFIED、签名撤销读时失效、Owner/no-store 路由及无档案时 COGS 硬阻断。
- `MERCHIVRA_V205_DISPOSABLE_ONLY=true python3 scripts/smoke_dispatch_custody_dossier_v205.py`：先检查 PostgreSQL18 已应用 Flyway V108，再在 `TEMP` 阴影表内调用**安装的原始 V108 PL/pgSQL 触发器**和从 V108 迁移文件中抽出的完整 `CREATE VIEW` SQL；`ROLLBACK` 后不遗留合成审计记录。负例涵盖源撤销、外键身份、恶意输入的快照覆盖、四种不同独立人员角色、双 SHA 冲突、复核前的失效重算、重复声明、不可变与 `CHECK(FALSE)`。
- 真正 Ed25519 签名由 V204 Java Test 运行时随机生成 Ed25519 密钥进行正反例；SQL-only 夹具不能、也不应伪称有可信的第三方机构文件。
- V205 [V205 精确代码 `99ef9b9`](https://github.com/kunlong-luo/merchivra/commit/99ef9b988de101f3413e0821817071bcce85c370) 的 [同 SHA 快速 CI #45132910751 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45132910751) 与 [完整 A3 #45132910387（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45132910387) 已成功，7 个完整 A3 jobs 全部 SUCCESS；PG18 Job 日志实际包含 `PASS V205 PG18 V108 original capture/review guards and source view`。此前 V204 的结果不能代替本次精确 V108 验收。

## 下一阶段 V206 的不可缺先决条件

需要与真实运营商、承运商及 Amazon 合规源建立**可信且授权的取证渠道**，例如来源机构查询回执、签收原件、公开注册机构核验时间及对方主体/设备独立身份验证，同时建立受限原件存储与 SHA-256 **实物原件重算**。只在完成维护者本地授权、第三方真值交叉核验、G1/DEC 财务政策和 G2/G3 生产安全审批后，才能另行设计实物库存/COGS 过账；不能通过修改 V108 的内部 review 决策直接升级。

正式评分 **70/100**，`realizedNetProfit=null`，真实 Amazon Seller/Ads OAuth **OFF**，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。
