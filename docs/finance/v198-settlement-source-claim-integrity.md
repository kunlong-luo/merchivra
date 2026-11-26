# Merchivra V198 · 结算编号唯一归属与来源完整性冲突拦截

**状态：2026-11-22 · V198 代码及隔离合成 A3 已完成。** 这次只改已有 V58 报告 / V31 Finances 引用的只读比较判定、Next.js 展示与合成回归。不发起 Amazon/Ads 调用、不增会计账本、不得解除外部验真或生产门禁。验收：[精确代码 `11864a7`](https://github.com/kunlong-luo/merchivra/commit/11864a739451f0e91c0bd9639b2c4bfe441b518e) 的 [完整 A3 #45021678686（run_attempt=1，7/7 SUCCESS）](https://github.com/kunlong-luo/merchivra/actions/runs/45021678686)，及 [同 SHA 快 CI #45021678580](https://github.com/kunlong-luo/merchivra/actions/runs/45021678580) SUCCESS。最初 `f5bc560` 的 [快 CI #45021496206](https://github.com/kunlong-luo/merchivra/actions/runs/45021496206) 和 [A3 #45021496225](https://github.com/kunlong-luo/merchivra/actions/runs/45021496225) 曾因旧 Java 构造测试与旧 Vitest 响应夹具缺五项计数而失败；修复的是测试数据，不是删除冲突守护。旧失败结果保留作为回归证据。

## 为什么要修复，不重复造一个财务流程

V58 的 `spapi_settlement_report` / `spapi_settlement_report_line` 已保存原始报告编号、文档 SHA-256、声明净额和明细数。V31 的 `finance_transaction_reference` 保存 Finances 的 `SETTLEMENT_ID` 文本引用；已有对账比较只按租户/Marketplace/结算 ID/币种加总。V56 已提供独立 Owner 双人审核，且最终 approve 时会防止同一结算 ID 的多报告归属，**本轮不复制这层审批**。

实测代码审计可见以下尚未覆盖的假阳性入口：

1. V31 的主键仅阻止同一事件+同一 SETTLEMENT_ID 值重复写入；**一个 Finances 事件可以持有不同的 SETTLEMENT_ID**。旧比较把它算入匹配报告，可能同时支持另一份报告。
2. 相同 `settlement_reference` 在同站点多份报告中出现时，旧只读比较不显示这一冲突；即使人工 approve 后端已有拦截，前端仍可错误显示金额一致。
3. V54 报告导入时冻结的 `line_count` 与当前明细行数可能分离；特别是缺失一行零金额明细，**SUM 仍可能完全相同**，旧比较没有行数守护。
4. 若旧表或后续数据库数据出现不同原币的报告明细行，单纯相加会产生无意义的混币金额，必须在报告端单独拦截。

## 复用现有服务实现

在 `SettlementReportComparisonService.COMPARISON_SQL` **同一 SQL 语句**按租户、Marketplace、当前报告进行完整范围检查，新增五个非负证据字段：

| 返回字段 | 证明的只是本地事实 |
| --- | --- |
| `expectedReportLineCount` | 导入报告时存储的预期行数 |
| `actualReportLineCount` | PostgreSQL 实际报告行数 |
| `foreignCurrencyReportLineCount` | 报告内不属于报告原币的明细行数 |
| `multiplyReferencedFinancesEventCount` | 属于当前 Settlement ID、但同一事件还拥有其他 SETTLEMENT_ID 的 Finances 交易数 |
| `otherReportSameSettlementCount` | 同租户/Marketplace 使用同一结算编号的**其他**导入报告数量 |

冲突优先于金额相等，新增：

- `REPORT_LINE_COUNT_MISMATCH`：预期/实存数量不同；`REPORT_LINE_CURRENCY_CONFLICT`：报告内部混币；
- `FINANCES_EVENT_MULTI_SETTLEMENT_CLAIM`：同一 Finances 事件属于多个结算参考；`SETTLEMENT_REFERENCE_SHARED_REPORTS`：同编号多报告竞争。

只有无上述矛盾、原报表声明/明细及 Finances 来源金额均数值一致时，才继续返回**原有** `SOURCE_AMOUNTS_AGREE_UNVERIFIED`。仍不是 `VERIFIED` 或银行确认。后端 `reconciled=false`、`bankVerified=false` 恒不变。现有 V56 审核会在比较状态变化后将先前证据标为 `STALE`，且 approve 仅允许 `SOURCE_AMOUNTS_AGREE_UNVERIFIED`。禁止为了规避冲突修改历史 Flyway 或回填多结算归属。

Next.js 原有结算报告工作台显示数值和中文原因；`settlement-comparison.ts` 与 `settlement-source-review.ts` 的 Zod 联动同步新状态和字段，拒绝把缺少冲突计数的响应默认解释为“0 个冲突”。没有第二套前端组件、认证或 API。

## 测试及限制

- Java `SettlementReportComparisonServiceTest` 包含原有原币/缺来源/错额检查及新增报告行数异常、内部混币、交易多结算引用、多报告相同 ID、无冲突金额一致的负例，检查 SQL 的租户/Marketplace 关联条件。
- pnpm/Vitest `settlement-comparison.test.ts`、`settlement-source-review.test.ts` 分别保护只读请求、冲突字段必填、状态枚举和“不能伪造银行已确认/对账完成”。
- **真实 PG18 SQL**：`MERCHIVRA_V198_DISPOSABLE_ONLY=true python3 scripts/smoke_settlement_source_identity_v198.py`，从 Java 原文件提取 **COMPARISON_SQL 原文**，在同一 PostgreSQL 18 会话的 TEMP 表和最终 ROLLBACK 中检测同交易两个结算 ID、同 ID 两份报告、跨租户/站点隔离、缺零金额行与内部混币。既不导入真实 Amazon 文件，也不更改公共业务表。
- **仍未完成的商业链路**：有合法授权后的 Amazon Settlement/Finances 交叉证据独立验真、到账银行真实核证、已批准的退款 COGS/税费/外币会计处理和可过账利润。历史多归属**只生成诊断/拦截，不自动裁决或删除**。`realizedNetProfit=null`，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) 继续 **NO-GO**。

现行开发规范以 [AGENTS.md](../../AGENTS.md)、[ENG-001](../governance/engineering-standards.md) 为准；评分仍按 [68/100 基线](../governance/project-scorecard-2026-11-22.md) 审视外部真值，不因仅增加合成测试而自动提高。


## V271 · 订单原始参考分组守恒（待验收）

V271：在 V198 同 SQL 快照比较中对当前 tenant/marketplace/report 的原币报告行及精确 settlement ID LIVE Finances 交易按原始 external_order_id 分组，NULL 单独分组、不得猜测归属。总额一致但任一分组缺一侧（含零金额）或原币净额不同，返回 ORDER_REFERENCE_AMOUNT_DIFFERENCE，旧 V56 只允许 SOURCE_AMOUNTS_AGREE_UNVERIFIED 的批准规则继续阻断；保留原状态优先级。V56 指纹升级 V271（旧审核需重审，不回填），包含报告实际明细、声明净额、Finances 原订单参考/类别/source_type/UTC epoch；状态读取 RR，未宣称全域并发或 ABA 证明。Next 原工作台及 Zod 接受新阻断状态；扩展 V198 真 PG18 TEMP/ROLLBACK 原 SQL、Java/Vitest 反例，待 A3。属于订单净额分组一致性门禁，不等同逐行匹配或证明全部费用、非订单费用、实际到账、FX/COGS/会计签署；真实 E1–E5 未完成，realizedNetProfit=null、Seller/Ads OAuth OFF、#104 NO-GO。


V271 [代码 6a17618dfbd2](https://github.com/kunlong-luo/merchivra/commit/6a17618dfbd2156c9b0b8f36b076e9ddbe423ad9) [快 CI #45611812174 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45611812174) [完整 A3 #45611813480 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45611813480)，[只读 Pages #45611811764 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45611811764)。

## V272 · 末级明细与正负方向守恒（待验收）

V272：现有 V198/V271 比较单 SQL 快照对每笔精确 Settlement ID、同 tenant/marketplace/currency LIVE Finances 事件核验 1–2000 个末级明细、非 NULL 金额/原币一致、末级净额等于事件净额；父节点不计入金额。缺口 FINANCES_LEAF_COVERAGE_INCOMPLETE，覆盖后按原始订单参考（NULL 单独）+金额正负方向核对 Reports 明细与 Finances leaf 合计，差异 ORDER_DIRECTION_AMOUNT_DIFFERENCE。零额无正负方向，仍由 V271 分组覆盖约束。保留旧阻断优先级、V56 审批仅允许 SOURCE_AMOUNTS_AGREE_UNVERIFIED。V272 指纹含当前全部拆分节点的 path/parent/type/amount/currency/is_leaf，旧指纹失效，未回填；前端 Zod 和提示同步；Java/Vitest 及 V198 真 PG18 TEMP 回归待 A3。此为金额方向守恒，不把正数一律认定商品收入、负数一律认定佣金，不凭名称猜测跨来源费用分类；同方向类别抵消、逐行身份、item/SKU 归属、实际原件与会计批准仍未解决。缺历史拆分的审核会阻断，须合法重新获取完整来源。独立 Redis smoke V270 失败具体原因未核实，不改测试或宣称修复。realizedNetProfit=null、OAuth OFF、#104 NO-GO。


V272 [代码 75abfb7b8993](https://github.com/kunlong-luo/merchivra/commit/75abfb7b8993d2c47042504c82d15d8499df02cf) [快 CI #45615522059 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45615522059)，[只读 Pages #45615522250 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45615522250)，[完整 A3 #45615522448](https://github.com/kunlong-luo/merchivra/actions/runs/45615522448) 核对时仍运行中。

## V273 · 显式跨来源费用分类预检（待验收）

V273：新 POST category-comparison-preview（Owner Session/CSRF/no-store，只读 RR），复用 V272 全源比较及现有 V28 ACTIVE Finances leaf source_type 分类，Reports 显式输入完整 transaction_type/amount_type/amount_description 三元组映射（最多200，不预设 Amazon 对应关系）。按原始 order_ref（NULL 单独）+V28 category+金额方向（含零）独立比较，任一缺映射、UNCLASSIFIED、未使用映射或超限（30000行）不给类别总额；完全映射后报告冲突分组数和原币分类双方合计，不暴露订单编号。相同方向类别抵消可识别，但 V28 现有五类仍粗粒度，同一类内部佣金/其他子费用不能区分。映射仅为本次请求假设，不持久化、不写入 V28、没有独立批准/版本审计或接入 V56 审批门禁；mappingApproved/bankVerified/ledgerPosted=false，realizedNetProfit=null。未新增迁移、前端 UI 或第三方依赖。JUnit 合成金额反例及既有 V198 真 PG18 TEMP SQL 扩展验证站点/租户、当前 ACTIVE、末级和未映射，待 A3；真实 HTTP 授权和真实财务样本未验收。V270 Redis smoke 失败仍未修复。

### 本地 Owner API 合同

`POST /api/v1/finance/marketplaces/{marketplaceId}/reports/{reportId}/category-comparison-preview`，使用现有 Session Cookie/CSRF 与 JSON 请求数组。不接受 tenantId。下面仅为合成类型演示，真实映射须根据合法原件和财务政策复核，不可视为 Amazon 官方分类：

```json
[{"reportType":{"transactionType":"Order","amountType":"ItemFees","amountDescription":"Commission"},"category":"AMAZON_FEE"}]
```

实际请求需覆盖该报告全部原始类型组合，Finances 末级分类由当前 V28 ACTIVE 规则读取。`CATEGORY_MAPPING_INCOMPLETE` 不返回局部总额；`ORDER_CATEGORY_AMOUNT_DIFFERENCE` 表示按订单/类别/方向至少一个分组差异，汇总两侧金额仍可能抵消。`LOCAL_CATEGORY_AMOUNTS_AGREE_UNVERIFIED` 只说明给定映射下本地金额相符。无授权、无真实外部证据、无会计签署时禁止把结果用作正式利润。尚无持久化映射与 UI，后续基于真实原件设计独立审核、版本及失效契约，而非自动批准。


V273 [代码 9955a7b93751](https://github.com/kunlong-luo/merchivra/commit/9955a7b93751e34d8589bed31deb1e2cfd4cc6e9) [快 CI #45616101485 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45616101485)，[A3 #45616101623](https://github.com/kunlong-luo/merchivra/actions/runs/45616101623) 核对时尚未开始；[V272 A3 #45615522448](https://github.com/kunlong-luo/merchivra/actions/runs/45615522448) 核对时运行中。

## V274 · 不可变 Reports 分类映射草稿（待验收）

V274：Flyway V124 报告范围不可变分类映射草稿（tenant/report/content SHA-256 主键，报告与创建者复合外键、内容哈希 DB CHECK、复用不可变 UPDATE/DELETE trigger）；复用 V273 验证器，三元组 NULL 显式编码、按内容排序，提交顺序不改变身份，修改生成新 SHA，相同内容 ON CONFLICT DO NOTHING 幂等保存。读取强制当前 ACTIVE tenant、marketplace/report，重新校验 SHA、规范编码及类型映射；保存不隐含当前证据一致。保存的映射预检在 RR 重读 V272/V273 和当前 V28 规则，旧草稿不缓存财务结果。新增三个 Owner Session/CSRF（写）/no-store API；无自动映射、独立批准、签署、细分类别扩展、V56 门禁或 UI，mappingApproved/bankVerified/ledgerPosted=false，realizedNetProfit=null。JUnit 覆盖规范化及范围 SQL，扩展既有 V198 PG18 TEMP 测试执行 V124 DDL 的临时表副本及不可变 trigger；实际 Flyway 与新 HTTP 行为仍待 A3/后续端到端验证。本轮暂不修改现有 A3 workflow 定义，避免取消运行中的 V272/V273 验收，V274 自身完整 A3 需后续明确触发。Redis smoke V270 启动失败尚未修复。真实 E1–E5 未完成，Seller/Ads OAuth OFF，#104 NO-GO。

### API

基准路径 `/api/v1/finance/marketplaces/{marketplaceId}/reports/{reportId}`。

- `POST /category-mapping-drafts`：请求体复用 V273 显式映射数组；返回 `mappingSha256` 和规范排序后的 `mappings`，状态 `LOCAL_IMMUTABLE_MAPPING_DRAFT`。重复内容返回同一身份；不是更新接口。草稿保存允许尚未完全覆盖的有效类型映射，不声称分类一致。
- `GET /category-mapping-drafts/{mappingSha256}`：读取当前报告范围指定草稿；不同报告/站点不可读取。调用者需保留返回 SHA；无草稿列表或“当前已批准版本”概念。
- `GET /category-mapping-drafts/{mappingSha256}/preview`：重算当前来源及分类，返回 SHA 和 V273 `preview`，不会使用历史缓存。变更 V28 或原始来源可能改变结果；旧草稿本身不变。后续独立审批必须绑定证据版本与完整映射，不得仅凭 SHA 认定有效。


V272 [完整 A3 #45615522448 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45615522448)。V273 [完整 A3 #45616101623 FAILURE 6/7](https://github.com/kunlong-luo/merchivra/actions/runs/45616101623)，PG18 job #139213034617 的综合脚本步骤失败，日志接口不可读取；静态核查发现 V273/V274 新增 DO $ BEGIN / END $; 非法分隔符，已改为具名 dollar quote，不宣称为唯一失败根因。V274 [代码 dc203b85c4f2](https://github.com/kunlong-luo/merchivra/commit/dc203b85c4f216069c28ca4f8f66af004ae26598) [快 CI #45616598969 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45616598969)，未触发完整 A3。

## V275 · 回归修复与分类证据指纹（待验收）

V275：修复 V273/V274 PG18 TEMP SQL DO 分隔符，V124 回归改为直接提取真实 Java SAVE_MAPPING_SQL/READ_MAPPING_SQL，验证内容 SHA、重复提交仅一条、错误 Marketplace 无结果、无变化 UPDATE 与 DELETE 必须触发 V106 不可变拒绝，而非把任意异常当成功。quick CI 增加该 Python 文件语法检查（不能替代 PG18 执行）。V274 保存映射预检仅在 LOCAL_CATEGORY_AMOUNTS_AGREE_UNVERIFIED 时新增 evidenceSha256，绑定 tenant/marketplace/report UUID、mapping SHA、V56/V272 源证据指纹与当前实际使用 V28 ACTIVE 规则 ID/type/category/revision/state 指纹；全在 RR 事务，同内容稳定、正常规则修订变更使证据失效，非相关规则不参与。缺覆盖/冲突不返回证据摘要。新增 Java digest 反例及真 PG18 规则修订与范围测试，触发完整 A3 待验收。不持久化证据、不建立独立审批、不改变 V56 门禁、UI、源事实或利润；不是签章、授权令牌或全域 ABA 防护。真实 HTTP、Redis smoke V270 失败、外部 E1–E5 与 G1/DEC 仍待完成。mappingApproved=false、realizedNetProfit=null、OAuth OFF、#104 NO-GO。

`GET /category-mapping-drafts/{mappingSha256}/preview` 新增可空 `evidenceSha256`。只有当前预检完全一致时生成本地证据身份，供后续独立审核绑定；规则或原始来源变化后必须重新预检比较，不得将旧摘要作为现行批准。


V275 [代码 4a8af88ffb2e](https://github.com/kunlong-luo/merchivra/commit/4a8af88ffb2e11d69795a32f16a7c3e475ab46e6) [快 CI #45618142971 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45618142971)，[完整 A3 #45618143096](https://github.com/kunlong-luo/merchivra/actions/runs/45618143096) 核对时 PG18 与 HTTP job 尚未结束。

## V276 · 内部独立分类审批记录（待验收）

V276：Flyway V125 新增内部不可变分类申请/批准两表，精确绑定 tenant/report/mapping SHA/evidence SHA，外键绑定草稿、申请及 membership，申请/批准分别唯一且 UPDATE/DELETE 拒绝。SettlementCategoryReviewV276 在 RR 内调用 V275 当前预检，只有一致且 evidence SHA 完全匹配才接受；复用现有 active 全租户 Owner 校验，审批人与申请人、原映射创建人均不同；读取及重放重查双方资格、审批时间与 OWNER_REVOKED 审计。相同 actor/reason 可幂等复用，冲突拒绝；并发 RR 序列化失败未做自动重试。原证据撤销后同 SHA 不可重新申请（后续需设计重开与授权代际）；非全域 ABA/撤销线性化保证。仅内部服务，无 controller/UI、无 V56 门禁或正式激活；mappingActivated/bankVerified/ledgerPosted=false、realizedNetProfit=null。新增 JUnit 过期、独立性、缺请求与 SQL 契约，扩展 V198 真 PG18 TEMP 精确 V125 DDL、申请/批准 SQL、重复、self approval、站点隔离、不可变触发器；实际迁移与服务端到端未验收。为保留 V275 正在进行的完整验收，本轮不修改 A3 workflow；待 V275 结果明确后另行触发 V276 A3。真实 E1–E5、G1/DEC、Redis smoke 启动问题仍阻塞；Seller/Ads OAuth OFF、#104 NO-GO。

内部方法 `request`、`approve`、`current` 绑定显式 mapping/evidence SHA；未开放 HTTP，保存记录不是激活，当前证据改变则拒绝。审批者不同于原草稿创建者及申请者；审批记录不可变。后续先完成 PG18/真实 HTTP 授权回归及审批重开契约，再评估前端操作入口。


V275 [完整 A3 #45618143096 FAILURE 6/7](https://github.com/kunlong-luo/merchivra/actions/runs/45618143096)，[PG18 job #139218878361](https://github.com/kunlong-luo/merchivra/actions/runs/45618143096/job/139218878361) 完整日志已核实：V198/V271/V273/V274/V275 SQL marker 均通过，V250 Java 并发测试第 612/713 行 DuplicateKeyException，DB 记录 finance_refund_compensation_p_tenant_id_proposal_sha256_req_key 唯一冲突，V123 INSERT 仅指定 PK 仲裁目标。HTTP、Java 常规、Next、Chromium、Compose、基线其余6项成功。V276 [代码 9ffb348a325e](https://github.com/kunlong-luo/merchivra/commit/9ffb348a325e98638956ceef315f36c79641c514) [快 CI #45618870958](https://github.com/kunlong-luo/merchivra/actions/runs/45618870958) 核对时运行中，未独立触发 A3。

## V277 · 分类请求唯一键竞争预防（待验收）

V277：修复 V123 请求双唯一约束仲裁漏洞，INSERT 使用无目标 ON CONFLICT DO NOTHING，使 PK 和为审批外键保留的 UNIQUE 均参与冲突仲裁；无操作后仍必须精确复核现行证据、canonical payload、actor/reason 及授权，非唯一键异常不吞掉，保留 V268 40001 整 RR 事务最多3次总尝试。V125 分类申请存在相同结构，预防性调整为全唯一仲裁，审批只有单主键维持原式；不新增事务重试或开放接口。V250 真 PG18 两连接表锁回归保留相同请求/批准与不同批准理由竞争，另增加不同请求理由同键竞争（一个成功、一个409、唯一记录）；强制 V277 JUnit marker，触发新 A3 待验收。无迁移、依赖、前端、补偿激活或 V56 分类门禁变化；分类审批服务的完整并发及 HTTP 验收仍待完成。真实 E1–E5、G1/DEC、Redis 独立 smoke 失败仍待解决；realizedNetProfit=null、OAuth OFF、#104 NO-GO。


V276 [快 CI #45618870958 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45618870958)；V277 [代码 73485ed88c03](https://github.com/kunlong-luo/merchivra/commit/73485ed88c03249905d94b2c41e6383e96c5da46) [快 CI #45619345494 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45619345494) [完整 A3 #45619345828 SUCCESS 7/7](https://github.com/kunlong-luo/merchivra/actions/runs/45619345828)，PG18 job #139223062078 成功，含 V277 强制并发 JUnit marker 及 V125 TEMP SQL；历史 V273/V275 失败仍保留。

## V278 · 本地分类审核 HTTP（待验收）

V278：开放 V276 本地分类审核 HTTP（Owner Session/CSRF、no-store、tenant/actor 服务端解析），明确选择 report/mapping/evidence SHA；GET 当前有效审核、POST request/approve，复用 V125 记录及当前证据/角色/撤销门禁，不新增审批状态或 DB 迁移。CannotSerializeTransactionException 映射409要求重新加载，而非自动重试；其他错误不伪装成成功。新增 V250 实际 Spring HTTP/PG18 合成报告、真实 DB 交易/拆分/规则夹具及两个独立 Owner session，覆盖 draft/preview/request/approve/current、匿名、缺 CSRF、无申请、旧指纹、同人、跨站点、规则 revision 变化及不可激活字段，并强制 V278 JUnit marker，待新 A3。尚无专门 staff/跨租户 HTTP 新用例（复用既有权限边界）、前端、审核重开、V56 门禁、实际激活或真实 Amazon/银行/会计验证；mappingActivated/bankVerified/ledgerPosted=false、realizedNetProfit=null。Redis 独立 smoke 失败仍待修复；真实 Seller/Ads OAuth OFF，#104 NO-GO。

基准路径 `/api/v1/finance/marketplaces/{marketplaceId}/reports/{reportId}/category-mapping-drafts/{mappingSha256}/reviews/{evidenceSha256}`。先保存草稿并调用其 `/preview` 取得非空当前证据摘要。

- `POST /request`：`{"reason":"10–500字符的非敏感审核理由"}`，返回 LOCAL_CATEGORY_REVIEW_REQUESTED。
- `POST /approve`：同请求结构，须由不同于申请人及草稿创建人的当前 Owner 执行；返回 LOCAL_CATEGORY_REVIEW_RECORDED_NOT_ACTIVATED。
- `GET` 基准路径：仅返回当前有效审核记录；无申请、旧证据或已审计撤销导致 409，不可直接当作已批准。404 为不存在/不在所选范围；403 为权限或独立性拒绝。所有写请求携带既有 Session CSRF。409 重新预检，不能编辑旧记录；同证据撤销后的重开机制未实现。

批准仅为本地证据记录，不会使 V56 自动批准、不产生会计凭证或实际利润。当前尚无前端操作入口。


V278 [代码 a36377ae452c](https://github.com/kunlong-luo/merchivra/commit/a36377ae452ccd928d5ebe7b671b1f9d91953353) [快 CI #45741837204 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45741837204)；[完整 A3 #45741837209](https://github.com/kunlong-luo/merchivra/actions/runs/45741837209) 核对时仍运行，基线、Java、Next、Chromium、Compose 成功，真实 PG18/HTTP 未结束。最新完整验收仍 V277。

## V279 · 费用分类操作工作台（待验收）

V279：在既有结算报告工作台中新增费用分类操作区，复用 React/Next 工作台及现有 Zod、CSRF、Session API。可从可见前100行提取不同原始三元组（不猜分类）、手工补充/删除类型、最多200项、明确 NULL 语义；保存 V274 不可变版本、按 SHA 加载（第二 Owner 可加载同一草稿）、重新核算 V275、查看未映射数/未分类数/差异分组与原币双方金额，调用 V278 本地申请、批准、当前读取。编辑/切换报告或证据错误清除旧结果；409 明示无当前有效审核或失效，不假称尚未提交；需重新核算。Zod 强制 false/null 与响应身份，禁止自审批响应、错误摘要等；新增 Vitest 反例。全量类型自动发现、草稿列表、当前操作者主动前端独立性识别、审核重开、新工作台 Chromium 手动验收尚未实现；服务端仍执行全部权限/覆盖校验。仅使用合成测试，不是财务批准或激活。为避免抢占 V278 完整 A3 本轮不修改 A3 workflow；V279 快 CI 待核，独立完整 A3 需后续触发。真实 Seller/Ads OAuth OFF，realizedNetProfit=null，#104 NO-GO。

操作入口：财务 → Amazon 结算报告 → 选择报告 → 费用分类核对与审核。可见原始类型仅取前100行，必要时维护者在受控原件核对剩余类型并手工添加；最终后端拒绝缺映射。两位 Owner 通过同一报告/映射 SHA 重新核算，不传入 tenantId/actor，不将审核记录当作会计签署。无草稿列表，需保留该版本 SHA。

V278 [代码 a36377ae452c](https://github.com/kunlong-luo/merchivra/commit/a36377ae452ccd928d5ebe7b671b1f9d91953353) [快 CI #45741837204 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45741837204) [完整 A3 #45741837209 SUCCESS 7/7](https://github.com/kunlong-luo/merchivra/actions/runs/45741837209)，实际 PG18 job #139648487996 日志确认 PASS V278 actual category review HTTP JUnit marker verified (not skipped)。V279 [代码 f1994297ab75](https://github.com/kunlong-luo/merchivra/commit/f1994297ab754597b1a3b2b47358336605f392c2) [快 CI #45742309207 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45742309207) [只读 Pages #45742309241 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45742309241)，未单独 A3 或专项分类 UI Chromium 验收。

## V280 · 完整报告类型发现（待验收）

V280：新增 Owner no-store GET `/api/v1/finance/marketplaces/{marketplaceId}/reports/{reportId}/category-types`，在一个 RR 事务检查报告声明行数、实际行数（1–10000）及原币，然后按精确原始类型三元组全量 GROUP BY，读取最多201组以检测超出200项映射契约；每组 rowCount、NULL 保留，无猜测分类或金额。非全量、混币、超限、不可映射类型均返回 HOLD 状态及空数组，跨 Marketplace 404。前端不再从前100行初始化映射，使用全量查询及 Zod 覆盖/身份/重复校验，刷新重新挂载工作台并清除旧证据；原明细表保持100行。新增 Java/Vitest 反例；V250 实际 HTTP 夹具扩为101行、最后一行佣金、两类完整映射及复用 V278 双人审核，强制 V280 JUnit marker；触发完整 A3 待核。无迁移、依赖、真实 API 授权、财务激活、审批重开或正式入账；独立 Redis 启动失败尚未排除。

V280 补正：类型初始化使用泛型保留原明细测试夹具额外字段；完整类型查询不在窗口聚焦/网络重连时自动刷新，避免清空未保存的编辑。用户明确刷新会清空编辑与旧证据；以新精确 SHA 的 r2 完整 A3 为准，原运行不作为该 SHA 的验收证据。


V281 [代码 cb9bb88eea15](https://github.com/kunlong-luo/merchivra/commit/cb9bb88eea150384fa87a121c60e2a11d4bf42cf) [快 CI #45743424984 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45743424984)，[独立 Redis smoke #45743425032](https://github.com/kunlong-luo/merchivra/actions/runs/45743425032) [job #139653656144 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45743425032/job/139653656144) 实际日志 redis_version:8.10.1、GCRA PASS、真实登录/CSRF、重启后同一 userId/tenantId、断 Redis 后旧 cookie 不授权均通过；无生产凭据。V280 r2 [完整 A3 #45742981332](https://github.com/kunlong-luo/merchivra/actions/runs/45742981332) 核查时5项通过，PG18及完整 HTTP 尚运行。

V282：复用 `scripts/smoke_browser_layout.mjs` 现有 Chromium/CDP 与 localhost 合成 API，新增费用分类工作台专项流程：报告101行而可见100行、全量两个原始类型（第101行佣金）、无自动分类、精确映射及 CSRF 保存、原币预检与当前证据、申请写入、模拟自审批403、清除旧证据、重算新证据与跨 Marketplace 清除面板；保存两个360px截图。断言针对真实 DOM 操作及合成请求，不能替代 V278/V280 真实 HTTP 或真实 Amazon 财务验真。未新增前端框架/依赖、迁移、业务激活或正式会计功能。V282 专项浏览器尚待独立运行；不改 A3 工作流，保留 V280 r2 完整验收。


V280 r2 [完整 A3 #45742981332 7/7](https://github.com/kunlong-luo/merchivra/actions/runs/45742981332)，七个 job 均 SUCCESS；[PG18 job #139654014988](https://github.com/kunlong-luo/merchivra/actions/runs/45742981332/job/139654014988) 日志确认 `PASS V280 actual full report type discovery JUnit marker verified (not skipped)`。V281 独立 Redis 通过证据保持。V282 [快 CI #45743906965](https://github.com/kunlong-luo/merchivra/actions/runs/45743906965) 的 [Compose job #139655319948](https://github.com/kunlong-luo/merchivra/actions/runs/45743906965/job/139655319948) 失败于 V108 cadence AssertionError: browser；[Chromium #45743907063 job #139655232452](https://github.com/kunlong-luo/merchivra/actions/runs/45743907063/job/139655232452) Vite 阶段通过，Next 阶段在 `V282 full type discovery` 超时，未证明后续保存/审核等动作成功；原因尚未确证。

V283：修正 V108 快速 CI cadence guard，要求 Chromium 的 main push 路径精确等于四个测试契约文件，禁止无过滤 push、常规前端文件自动全量触发及 PR；Compose 和 PG18 原集中测试门禁保留。修正 V282 Chromium 断言：为完整类型覆盖加入明确语义标签，要求选中精确 report、101行/2组覆盖文字（仅归一空白）、两个分类控件且实际 `/category-types` GET；失败记录仅合成请求路径/DOM 摘要及截图，仍抛出超时。动作等待真实可操作（包括 fieldset disabled），自审403必须出现实际 alert 而非静态说明，新证据必须重新 GET 审核。未删原独立性或金额断言，未改变服务端财务门禁。针对浏览器超时尚未证实根因/修复效果，本轮自动触发快 CI 与独立 Chromium 待核，不重跑已通过的 V280 精确 SHA 全 A3。无数据库迁移、依赖或真实 API 调用。
