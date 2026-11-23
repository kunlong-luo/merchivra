# Merchivra · 当前执行计划（2026-11-23 / V205 外部交接声明与独立复核）

**状态：现行 P0 任务顺序，不是自动上线授权。** 以 [当前事实基线](../current-status.md)、[ENG-001 实施规范](../governance/engineering-standards.md)、[V194/V105 最终代码 SHA `9cdb9268020bdc9ae0c743e09d5d5775c377ce69`](https://github.com/kunlong-luo/merchivra/commit/9cdb9268020bdc9ae0c743e09d5d5775c377ce69) 的 [完整 A3 #45018945885（run_attempt=1，7/7 核心 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45018945885)；同一 SHA [快速 CI #45018945926 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45018945926) 为依据；早期 [V108 MVP 计划](mvp-freeze-release-v108.md) 与 [PLAN-001 长路线图](../roadmap.md) 保留为历史迭代记录。

## 北极星与边界

Merchivra 最重要的核心能力是**汇集订单/SKU/店铺/期间所有有证据的真实收入、费用、货物成本和退款回转，核对重复归属并形成可追溯的利润报表**。不能为了“有利润数字”绕开不可信来源、币种和会计政策。正式本地 UI 已切 Next.js 16，pnpm 是前端唯一包管理器；这两个工程完成项**不能**代替业务真值。

## 执行优先级（按证据闭环，而非展示卡数量）

| 优先级 | 要完成什么 | 现有可复用基础 | 完成与不可完成的判据 |
| --- | --- | --- | --- |
| P0-A | **第三方来源真实性/账单独立核验**：定义 Amazon Finances/Settlement、Ads、物流/头程/关税、银行证据的合法取得和身份复核流程 | V91–V95、V102/V181、V182–V184、本地身份冲突与不可变审计 | 每笔来源能追溯原始系统/报告/行/摘要、币种与独立批准；手工/合成绝不自动升级为 VERIFIED |
| P0-B | **全成本、退款 COGS 和唯一归属**：明确 SKU/订单/期间及跨月金额守恒、退款负费用/成本回转、真实重复扣款裁决 | V162、V164、V170–V188 的只读引擎、跨来源 hold、原币预览 | 有明确 G1/DEC-06/07/08 财务口径，整期全量而非仅当前页，缺来源为 UNKNOWN、重复不叠加 |
| P0-C | **可审计利润核算与报表**：让 V64 已知范围贡献与经过独立认证的费用接入同一可重算版本化证据链 | V64 冻结快照、V180 版本差、V183/V186/V187/V188 敏感度瀑布、Next 财务中心 | 仅在成本完整/已签署情况下给出“真实净利润”；此前 `realizedNetProfit=null`，展示阻断原因而非填写零 |
| P0-D | **可信 WMS 及库存/COGS**：把 B1 合成密钥持有权扩为独立 B2 外部运营主体与物理签收、获准 B3 库存/成本过账 | V173–V175 QC/回执和 V101 密钥持有权技术基础 | 不能用内部双/三 Owner、签名 JSON、上传公钥冒充真实外部身份或实收；G1 签署前不能改变 COGS |
| P0-E | **安全与真实目标环境**：TLS/代理头、KMS/密钥、AV、数据库 PITR/RPO/RTO、外部告警 ACK、权限、容量及人工审批 | 受控 Next/PG18/Chromium/Compose A3、[目标预检](../architecture/next-target-ingress-v151.md)、[生产阻断](../testing/release-readiness-v68.md) | [#104](https://github.com/kunlong-luo/merchivra/issues/104) 的 G1/G2/G3 分别签署，目标环境证据独立审核；任何合成 CI 绿灯不等于发布 |
| 最后 | **维护者本地真实 Seller/Ads 只读授权与 API 验收** | Mock Gateway、连接与同步代码 | 用户完成实际授权、合法角色/区域、分页/限流/撤销、凭证/银行真实性和隐私审查之后才可调整访问开关；写入另行审批 |

工作原则：先复用既有表、服务、Orval 客户端和差异审计；不新建重复“真实性系统”，不修改历史 Flyway；后端审计优先于增加展示卡片。

## V205 · 已实现待当前 SHA 验收：外部运营主体/承运交接审计档案

[契约、外部身份信任边界与来源冲突规则](../finance/v205-external-dispatch-custody-dossier.md)：新 V108 不再自造另一套 WMS 密钥、批准和真实库存账本；在 V204 当前签名出库观察上，登记外部运营者/承运原件的声称引用与 SHA-256，数据库自行冻结关联签名、仓库、出库编号和数量。运营商编号与承运交接单号全租户范围检测**相同外部引用的不同声称文件摘要**，独立 Owner 文件核对不得由档案提交人、WMS 签名导入人或公钥登记人执行；来源撤销/批次漂移后旧审查自动失效。Java/PG18 拒绝从内部审查状态升级外部身份、实物交接、Amazon 履约或实际成本。用户未提供真实外部原件/可信核验渠道，**不能标记 B2 VERIFIED；本阶段验收目标是可审计、fail-closed 的取证准备链**。

下一阶段 **V206/P0-D/P0-A** 需要在真实维护者本地授权之后建立可信第三方登记机构与承运方核验渠道、来源原件留存/摘要重算、受控 Amazon 订单履约交叉核验和退款/运费证据链，另由 G1 审签入账成本/原币政策。生产 #104 继续 NO-GO、实际利润继续 NULL、评分 70/100。

## 已交付 · V204 签名出库观察与可重算失效链（[V204 精确代码 `f89485f`](https://github.com/kunlong-luo/merchivra/commit/f89485fe0b85cb63f7e12614f254c4ba57ed9be9) · [同 SHA 快速 CI #45122956620 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45122956620) · [完整 A3 #45122956610（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45122956610)）

[设计、规范化 Ed25519 字节契约、读时失效与安全边界](../finance/v204-signed-sale-dispatch-identity-gate.md)：不造第二套 WMS 公钥治理，沿用 V98/V101 独立密钥持有权证明、V203 的全批次归属校验和 V200 历史订单 SKU 门禁。V107 不可变签名出库观察在写时要求登记源已批准、密钥持有权已证明、V106 本地归属有效，按精确数量/仓库/订单/批次保存规范化签名与指纹；读时对来源撤销、归属失效、历史 SKU 漂移、原始签名字节与 SHA-256 独立重算。没有 Amazon 外部订单项履约 API 的真实授权与外部 WMS 法人/设备身份认证，仍**只得到 SIGNATURE_ONLY_UNVERIFIED，不得确认实际 COGS**。本轮精确代码提交的 Java Ed25519 真值负例、PG18 V107 真触发器和原 SQL、HTTP、Chromium/Compose 已完成 7/7 合成 A3；真实外部 WMS/Amazon 与 G1 审批仍独立阻断。

下阶段 V205/P0-D 应建立独立真实外部仓库运营主体/设备身份和具合法来源的发货/交付证明流程，另在维护者本地同意且权限、审计、合规完成后才安排 Seller/Ads 只读联调；真实账务、退款 COGS 和 G1 会计签署仍需单独批准。生产 #104 **NO-GO**，评分 **70/100**，已实现逻辑不代表真实利润完成。

## 已交付 · V203 销售出库对采购批次的不可变关联声明（[精确代码 `3d94550`](https://github.com/kunlong-luo/merchivra/commit/3d94550b9a2ef5a53c15db358b390ed88b60478b) · [同 SHA 快 CI #45116800143 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45116800143) · [完整 A3 #45116800085（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45116800085)）

[契约、数量守恒与审计边界](../finance/v203-sale-to-lot-link-integrity-contract.md)：优先复用 V48 pick line、V40 OUTBOUND、V46 QC 与 V20 receipt/PO 身份，新增 Flyway V106 的原子捕获和独立 PostgreSQL 重算视图；同一订单项/pick line/收货批次全历史数量守恒，独立追加撤销不释放旧数量，更新源数据需显示 STALE。Owner/no-store 只读 API 复核 V200 历史 SKU 映射。该代码精确 SHA 已通过快 CI 及 PG18/HTTP/Chromium/Compose 完整 A3 7/7；所有验证为合成隔离环境，不是实物发货与 G1 核证。本地声明和摘要不构成 Amazon 或外部 WMS 真实证明；不得生成实际 COGS/GL、退款冲回或已核实利润。

下一阶段优先 **P0-D/B**：实际外部 WMS 主体独立身份与出库履约证明、平台订单项↔外部运输/交付的来源认证、退款实收与 COGS 转回、头程/运费/关税归属/原币会计政策审批。需要正式 G1/G2/G3 审签；生产 #104 保持 NO-GO，实际净利润继续 NULL，评分 **70/100**。

## 已交付 · V202 本地收货与销售批次成本证据核对（[精确代码 `4d07d87`](https://github.com/kunlong-luo/merchivra/commit/4d07d87c92366b661ad9c5400b759ac73d98362f) · [快速 CI #45105511319 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45105511319) · [完整 A3 #45105511271（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45105511271)）

[技术边界](../finance/v202-order-local-batch-cost-provenance.md)：现有 V20 PO 单价/V46 QC 批次/V39 库存流水仅能证明**本地采购和操作**，不能匹配 Amazon 实际订单的出库批次。复用 V200 有效期订单 SKU 准备度，汇总同租户且收货在订单时间之前的候选本地记录，独立检测收货↔PO SKU/仓库/数量、QC↔receipt、QC_RELEASE↔accepted units 以及多原币/单价冲突；结果永久不允许 `saleLotAttributed=true` 或 `realizedBatchCogs` 非空。Next 复用订单成本面板展示，不堆盈利卡片。之后需要真实销售→批次签证、完整库存流转守恒和 G1/外部 WMS 证明后才能计算实际批次 COGS。本轮完整 A3 已 7/7 成功，但测试全程仅隔离合成，仍不能证明 Amazon 实际销售批次或 G1 认可的批次成本。此阶段不启用 Seller/Ads/生产写入。


## 已交付 · V201 跨退款事件数量守恒读时门禁（[精确代码 `c47873c`](https://github.com/kunlong-luo/merchivra/commit/c47873cd1ad3e9963f57c9d69dc19b2ba5a17c90) · [快速 CI #45024903306 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45024903306) · [完整 A3 #45024903265（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45024903265)）

沿用 [V34/V35/V63](../finance/refund-recovery-v63.md) 原审核/金额分摊/成本敏感度路径。在原 LIVE 订单有效数量变化后，所有退款事件的历史审核数量合计可能超额；已有 V34 写时校验并不足以保证过去的 V63 审核仍有效。[V201](../finance/v201-refund-cross-event-quantity-read-gate.md) 增加按整单与当前有效项重新读取的数量守恒，冲突时禁止展示 COGS 情景值与继续财务复核，保留完整人工审计。精确代码的完整合成 A3 已 7/7 SUCCESS，含真实 PG18 原 Java SQL 的数量冲突负例；不生成真实退款凭据或过账。下一阶段仍需核证采购批次、真实退货收货与第三方来源，生产不放行。


## 已交付 · V200 历史 SKU 成本歧义 fail-closed（[精确 V200 代码 `2715529`](https://github.com/kunlong-luo/merchivra/commit/271552911b55a451e66662cdf3aab94c2db21341) · [快速 CI #45023721155 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45023721155) · [完整 A3 #45023721170（7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45023721170)）

在已有 V19 生效期 SKU 成本与订单时点历史 SKU 映射上，修复仅按版本排序取首条而未识别冲突的风险：同一订单日期存在**多条有效 SKU 映射**或**多条历史成本版本**时，订单成本必须是 `INCOMPLETE`、`provisionalCost=null`；不能选任意成本。参见 [V200](../finance/v200-historical-sku-cost-ambiguity.md)。不改变已有历史迁移、订单/退款成本规则、实物库存和已冻结利润。精确 SHA A3 已在原 Java SQL 的 PG18 TEMP/ROLLBACK 场景验证多候选和缺成本，整体 7/7 SUCCESS。仍未核实采购批次实物成本/真实 Amazon/银行数据；下阶段 P0 应先补历史批次与退款数量唯一归属的独立证据，再考虑任何 COGS 冲回。


## 已交付 · V199（[精确 `30fd25c` 提交](https://github.com/kunlong-luo/merchivra/commit/30fd25c9205c10234fced28626d69e0df2b81889) 的 [完整 A3 #45022799341（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45022799341) 与 [快速 CI #45022799357](https://github.com/kunlong-luo/merchivra/actions/runs/45022799357) SUCCESS）

V198 完整 A3 已通过，但其 V54 报告 `declared_net` 原本允许 NULL；[V199](../finance/v199-settlement-declared-net-fail-closed.md) 禁止将**无声明净额**而其他两项金额相等的结算证据判为 `SOURCE_AMOUNTS_AGREE_UNVERIFIED`。复用现有审核与前端，不生成零值、过账或新审批。本次完整 A3 通过 Java/Next/Chromium、真实 PG18/Flyway、HTTP/Compose 合成回归；仍未验证真实 Amazon/银行来源或批准财务过账。

## 已交付 · V198（[精确代码 `11864a7`](https://github.com/kunlong-luo/merchivra/commit/11864a739451f0e91c0bd9639b2c4bfe441b518e) 的 [完整 A3 #45021678686（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45021678686)，及 [同 SHA 快 CI #45021678580](https://github.com/kunlong-luo/merchivra/actions/runs/45021678580) SUCCESS）

继续 P0-A 原始费用来源/结算归属校验：复用 V58 报告、V31 Finances `SETTLEMENT_ID` 和 V56 结算双人审核，新增 [V198 多重引用、重复报告、报告行数与混币完整性阻断](../finance/v198-settlement-source-claim-integrity.md)。新状态在源比较产生，而不是等到最终人工批准才发现；旧审批依现有证据状态变更而标 STALE。PG18 脚本使用 TEMP 和 ROLLBACK 执行实际 Java 比较 SQL。仍不尝试凭金额相等自动归属、生成真实净利润或放开生产。最终精确提交 `11864a7` 的完整 A3 已通过真实 PG18 的原 Java SQL 对照、Next/Vite Chromium、Java、React、HTTP/Compose 7/7；此前 `f5bc560` 因旧测试夹具不兼容失败，现已修复，记录见 [V198](../finance/v198-settlement-source-claim-integrity.md)。这些测试不代表外部真实性或生产批准。

## 已交付 · V197（[V197 完整 A3 #45020338812（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45020338812)）

V194/V105 7/7 A3 后进入**退款 COGS 与真实来源验证准备**。已沿用 V34/V35/V63/V170 的人工复核、历史成本敏感度和原币来源，新增 [V197 后端只读阻断与跨事务漂移检查](../finance/v197-refund-cogs-source-consistency-barriers.md)。先核对相同退款事件、订单、币种与有符号金额，变更后隐藏敏感度并显示 STALE；再列出缺审、旧证据、处置未定及外部/G1/实收/会计硬阻断。没有独立实收/第三方来源认证或财务规则批准前依然 `realizedNetProfit=null`，不做 GL 或库存过账。

下一步应优先让真实 Seller/Ads/供应链来源经过授权后的**独立来源证据**与合规 G1 决策进入已有账单关联机制，而不是复制另一套审批或增加盈利展示卡片。本阶段已在 [精确代码 `db9d88b`](https://github.com/kunlong-luo/merchivra/commit/db9d88be81ec326194242f75596661cb950cbea7) 的 [同 SHA 快 CI #45020338752](https://github.com/kunlong-luo/merchivra/actions/runs/45020338752) 及 [V197 完整 A3 #45020338812（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45020338812) 验收：Gradle/React/Next、PG18/HTTP、Chromium、Compose 的合成范围通过。真实 Seller/Ads/银行和外部实收仍为独立未知，不因此关闭 G1 或生产 #104。

## 最近已交付工程基线

- **V194/V105（完整 PG18/A3 7/7 已通过）**：版本化 V1 冻结订单/SKU 证据捕获和独立重算，显式隔离历史 V62 摘要与新指纹；金额汇总、CSV、V188 瀑布发现旧证据或错误时 fail closed。仍不能解锁真实净利润。[设计与测试边界](../finance/v194-versioned-profit-capture-fingerprints.md)。

- **V195（快 CI 已通过）**：本地 Session 5xx/代理失败不再误导为“无法验证身份”；区分 401、403、连接错误和无效响应，有限自动重试与展开排错。**用户本机后端 HTTP 500 根因未证实**，不能把 UI 错误态优化当后端修复。见 [本地启动排错](../development.md#本地启动时显示服务暂时未就绪)。
- **V188B**：只读原币期间成本瀑布，修复固定 V64 OpenAPI 九路由边界；仍不是已核验利润。参见 [V188 交付说明](../finance/v188-period-profit-cost-waterfall-preview.md)。
- **V189**：移除不可达 PR-only CI 分支判断，并按用户要求维护 **main 直接开发**。
- **V190**：`pnpm` 以外包管理迁移前，默认本地 Next.js 16 Web 和受控 Vite fallback 已以 7/7 A3 验证。
- **V193**：复用 V62 表、V64 批处理和 V192 只读守恒，Flyway V103 将已采集订单、SKU、已完成任务及审计历史锁为**数据库级不可变**，阻止跨批次晚插 SKU；[说明](../finance/v193-frozen-profit-snapshot-database-guards.md)。本地旧 SHA-256 不升格外部验真，真实净利润仍不可解锁。
- **V192**：基于现有 V62/V188 在后端只读 SQL 增加**逐订单—SKU 五项原币金额守恒**，不让相互抵消的明细异常进入期间敏感度；对应 [V188/V192 说明](../finance/v188-period-profit-cost-waterfall-preview.md)。仍不计算已核验净利润。
- **V191**：统一 Node 24 / **pnpm 12.20.0**、冻结锁文件、Docker 与 GitHub Actions，并在精确 [main SHA](https://github.com/kunlong-luo/merchivra/commit/8dab97147010fdc440ee81abcfa67cd1d1d57261) 完成 [完整 A3 7/7](https://github.com/kunlong-luo/merchivra/actions/runs/44882890834)。

## 近期必须补齐的验证与文档

1. **V194 / V104**：**已完成**：在 [A3 #45018945885](https://github.com/kunlong-luo/merchivra/actions/runs/45018945885) 对 `9cdb926` 真实 PG18/Flyway V105、匿名/Owner HTTP、历史旧证据和半指纹双向阻断及 V103 不变性完成隔离合成验收。仍需后续专项覆盖“完整可用订单/SKU 捕获”的真实全链路正例；测试绿灯不能证明真实费用来源。
2. **本地 HTTP 500**：取得用户本机 Spring/数据库日志与两条 curl 的可复现结果，定位真正故障；界面 V195 已友好化，但不是根因修复。
3. **文档治理**：每轮只维护 [单一当前状态](../current-status.md)、本计划、[ENG-001](../governance/engineering-standards.md) 与实际改变的运行指南；历史 Vxx 不再作为并列实时计划。

## 测试纪律与 NO-GO

每次 `main` 改动运行快速 CI；身份、租户、金额、库存、审核/迁移改变必须跑相应高价值负例，跨边界重大变更按实际风险运行完整 PG18/HTTP/Session/CSRF/Chromium/Compose A3。测试需要**记录执行的精确提交**，不能把上次 green 当本次 green。只读合成 GitHub Pages 不是真正 ERP 部署。真实 Seller/Ads OAuth、SP-API 出站/写入保持关闭，生产 #104 NO-GO。

跟踪：[全成本利润 #12](https://github.com/kunlong-luo/merchivra/issues/12) · [财务对账 #11](https://github.com/kunlong-luo/merchivra/issues/11) · [生产 G1/G2/G3 #104](https://github.com/kunlong-luo/merchivra/issues/104) · [需求决策 #15](https://github.com/kunlong-luo/merchivra/issues/15)。历史计划中已完成或关闭的技术 Issue 不要重复当成新 P0。
