# V226 · 有限批量暂存 V91 手工原币费用：真实 Owner/PG18 安全合同

**2026-11-24 · 技术前提与门禁。** [V225B 真 PG18 量化结果](v225-epoch-bulk-write-long-transaction-and-source-aba.md) 表明，同事务 20 条本地 V91 来源用集合 SQL 的 INSERT+DELETE 是 2 个语句级 tenant epoch tick，对照逐行 SQL 是 40 个；但此结果只量化来源语句形状，并**不自动授权将财务来源审批或会计过账合并**。项目未启用真实 Amazon Seller/Ads OAuth，外部费用、库存实际收货和银行来源没有独立认证，`realizedNetProfit=null`，生产 #104 仍 NO-GO。

## V226 真实验收结果 · 2026-11-24

**代码精确 SHA**：[`81ea17187dd693da3c12344aa5e6a6da49c7cf7e`](https://github.com/kunlong-luo/merchivra/commit/81ea17187dd693da3c12344aa5e6a6da49c7cf7e)。[同 SHA 快速 CI #45326774290 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45326774290)；[完整 A3 #45326774310（run_attempt=2，**7/7 SUCCESS**）](https://github.com/kunlong-luo/merchivra/actions/runs/45326774310)：精确 Spring/PG18 Job **#138218184069** 的原始日志打印 `PASS V222`/`PASS V223`/`PASS V224`/`PASS V225` 和全部 `V226_*`，`PASS V226`；Java25/Gradle、Next16/React19/pnpm、Chromium、Compose/static、mock-chain 均最终 PASS。**该技术结果不是生产容量许可或真实外部费用证明。**

| 真实 Owner/CSRF/PG18 原始日志 | 实际结论 |
| --- | --- |
| `V226_REAL_HTTP_BATCH_NEW` | 20 新 V91 来源 = 20 个唯一 UUID + 20 条 STAGED 审计行，完整响应且 no-store，租户 epoch **+2** |
| `V226_REAL_HTTP_REPLAY` | 同样 20 条重放，原 UUID 不变，**epoch +0 / 新审计 +0**；单来源旧接口重放一致；5 旧+3 新仅增加 3 个来源及 3 个独立审计，**epoch +2** |
| `V226_REAL_HTTP_ROLLBACK` | 旧键不同原币金额返回真实 **HTTP409** 且前面的合法新候选没有部分写入；4 组非法输入（坏 claimed SHA、重复键、21 条、空集合）均 **HTTP400**，失败请求 **epoch +0** |
| `V226_REAL_HTTP_AUTH` | 匿名真实 **401**、没有 CSRF **403**、跨租户 B Marketplace **404**；A 的非 Owner 只读人员不能写入；失败请求没有 V91 版本影响 |
| `PASS V226` | 真 V216 收货完整性 Owner GET 仍 **`actualBatchCogs=null`、`realizedNetProfit=null`、`postingAllowed=false`、`completeExternalCostCoverage=false`**；没有真实外部发票/费用确认，Seller/Ads OAuth OFF |

**首轮 A3 失败记录必须保留**：[同 SHA A3 #45326774310 · run_attempt=1](https://github.com/kunlong-luo/merchivra/actions/runs/45326774310) 的 PostgreSQL18/真实 HTTP **PASS**，7 项中有 6 项成功；独立 mock-chain Job #138218183813 在冷 Docker bootJar 三次有界重试中均遭 Maven Central **HTTP429 Too Many Requests**（例如 `spring-boot-buildpack-platform:4.1.2` 的 POM），所以完整 A3 **FAIL**。没有跳过 Docker、改变业务 SQL、改写已发布 Flyway 或使用旧 V225 A3 冒充通过。在 run_attempt=2 **只重试失败 Job #138219684488，最终 SUCCESS**，完整 A3 同 SHA **7/7 SUCCESS**。限流是外部依赖可用性问题，不应归因于源计费/库存逻辑已经无误或生产服务 SLO 已达标。

**阶段结论**：安全受控的 V91 原币费用**批量暂存** API 可用，单条来源身份、审计行、幂等回放、事务原子及 Owner/CSRF/租户范围已经由真实 PG18 验证；整批只有新的 `STAGED/MANUAL_UNVERIFIED`。未实现批量审核或来源/报告真实有效性认证，故本项目评分继续 **70/100**、`realizedNetProfit=null`、生产 #104 NO-GO。下一阶段 V227 应继续覆盖不同批次大小、并发站点和重放压力，以及 Orval 客户端/前端导入体验；其前提是保留本轮原币/证据链。

## 代码实现与保持的契约

复用既有 `CostSourceReviewService.create` 和 `CostSourceReviewController`，仅新增：

- `POST /api/v1/finance/marketplaces/{marketplaceId}/cost-sources/batch`（OpenAPI operationId `stageManualCostSourceBatch`）；请求体为 1–20 个原有 `Draft`，返回与单条创建同型的 **有序 `Source[]`**，响应 `Cache-Control: no-store`；**只允许真实 Session 当前活跃 Owner，必须通过 CSRF**。不接受客户端指定 tenant，Marketplace 仍以服务端 session tenant 查找。单条 `POST /cost-sources` 保持原契约。
- **批前验证**：actor 不存在则 HTTP401；空、超过 20、null/非法金额/原币/日期/claimed SHA/类别/重复的 `(sourceKind,sourceKey)` 则 HTTP400；这一步不得触数据库；请求中任一原币与当前 Marketplace 不符则 409。不允许把 0 或 claimed digest 伪装真实费用。
- **共享来源键幂等**：在当前 `seller_marketplace` 的同一 `FOR UPDATE` 行锁下，按 `(tenant,marketplace,kind,key)` 搜索。旧来源如果金额、币种、claimed SHA、日期、类别全部匹配，就返回同一 UUID；如果任一不一致，整批 409 且不插入任何新来源。已 REVIEWED_UNVERIFIED/REJECTED 的相同事实也仍是原 UUID 回放，**不自动升级审核/更改原始数据/新增审计**。批内重复键本身被拒绝，避免同一次请求产生双重归属。
- **集合写入但审计不合并**：新来源在**同一 SQL** `INSERT finance_cost_source_v91 VALUES (...) ...` 中写入，每条仍有自己的 UUID、sourceKey、原币金额、claimed digest 和 createdBy；本批所有新来源的 `STAGED` 审计在**另一条 SQL** 中写入，每条审计有不同 ID 且精确关联对应源，仍保留原 1:1 身份。保留 V91 不可变事实、一次审核、V92/V112 归属和 V165 潜在冲突保护。事务原子：若来源/审计/来源约束有任何失败，Spring `@Transactional` 回滚所有新记录。所有重放全旧记录时 **无 INSERT，无 tenant epoch 增长**；如有新记录（无论 1–20），预计一次 V91 INSERT+一次 V91 audit INSERT = **2 tick**（受 V113 同租户其他并发写入影响，CI 顺序场景才可严格校验）。
- **禁止事项**：不加入批量 reviewer 决定、不中途创建 V92 成本归属、不推断 Amazon/Ads/银行真实性、不更新库存或 COGS、不生成净利润；不改已经发布的 V113/V114/V115 Flyway，不删除 46 个触发器，不为获得“0次409”细分未经证明的 Marketplace epoch。

**并发/性能权衡**：批量操作复用原单条服务的 Marketplace `FOR UPDATE` 行锁，包含逐条查 key 和回放结果读取，仍可能增加同 Marketplace 的锁持有时间。最多 20 条是保守上限而非生产容量结论。相比 Java `JdbcTemplate.batchUpdate` 逐行执行 SQL，本实现通过一条真正的 SQL `VALUES` 写入多行，才可使 PostgreSQL statement trigger 一次更新一组。即便同租户两个 Marketplace 执行不同批次，真正的新来源仍会经 V113 tenant epoch 行锁串行，从而保留双 RR 的真实 ABA 可见性。V226 不声称生产自然 409、锁等待或 P95/P99 SLO 已批准。

## CI 真实 Spring Owner/PG18 正反例

新增 `scripts/smoke_cost_source_batch_real_http_v226.py`，必须在 V219–V225 fixture 已建立后、只在**仓库 main GitHub Actions/disposable localhost PG18/ci-v222/OAuth OFF** 的所有严格环境约束下运行；不需要新增公开调试接口。此测试通过已登录 Owner 的 JSESSIONID + X-CSRF-TOKEN 实际 `POST /batch`，直接核对真 PG18 原始来源/审计/epoch，并通过真实 V216 Owner GET 确保成本仍 UNKNOWN/null：

1. 20 条互异有效 `MANUAL_UNVERIFIED` V91 Draft：按请求顺序完整返回 20 个不同 source UUID；所有 `STAGED`、reviewer null；真实 PG18 20 条新来源、20 条不同 audit ID（每个源 1 STAGED），**新增 2 个 tenant epoch tick**，`Cache-Control: no-store`。
2. 原 20 条完全重复请求和对其中一条调用原单条 `POST /cost-sources`：原 UUID 不变，审计总数和 epoch **增长 0**。旧来源同键不同金额不得覆盖原行。
3. 5 条旧记录重放 + 3 条新增记录：原 5 UUID 不变，新 3 UUID 不重复，原有 20 audit 不变、新 3 audit 独立存在，整个请求仍 **+2 tick**。任一旧来源真实字段不一致即整个请求 409、预先指定的合法新行也不得部分留下。
4. 非法金额/claim SHA、重复 source key、21 条超限、空集合，全部 HTTP400，**无数据库变更、无 tenant epoch tick**；不能用“有些项合法”把批次回退成部分成功。
5. 匿名 HTTP401，缺 CSRF HTTP403，非 Owner 的 A READ 无权写入（403/404，不能成功），A Owner 无权向 B tenant Marketplace 写入（HTTP404）。所有未授权请求不得改变任何来源或 epoch。
6. 已创建 23 条 STAGED 合成费用后，真实 V216 Owner 收货完整性 GET 仍返回 `actualBatchCogs=null`、`realizedNetProfit=null`、`postingAllowed=false`、`completeExternalCostCoverage=false`；批量暂存绝不证明实际 landed cost。

保留原有 V225 真 PG18 15 表/长事务/真实来源 ABA 与 V221–V224 的 A3 正反例。**验收标准已由上述精确代码 SHA 满足**：快 CI Java25/Gradle、Next16/React19/pnpm 和 Compose，以及完整 A3 **run_attempt=2、7/7 SUCCESS**；独立 PostgreSQL 18 Job 必须打印 `V226_REAL_HTTP_BATCH_NEW`、`V226_REAL_HTTP_REPLAY`、`V226_REAL_HTTP_ROLLBACK`、`V226_REAL_HTTP_AUTH` 和 `PASS V226`，还要打印 `PASS V225` 及前序 ABA。若运行报错，必须保留失败 SHA 和日志、修改后重新跑完整 A3，不能让 V225 的旧 7/7 冒充本轮成功。

## 后续 V227 候选

对真实来源变更（非这轮合成暂存）增加批量大小 1/5/20、全新与高 replay 比例、跨 Marketplace 和多 tenant、多长事务分布的 PG18 WAL、锁等待、数据库 CPU/IO 与真 Owner HTTP P95/P99 对照，并补实际 Controller OpenAPI/Orval 与前端导入体验，前提是本轮安全/审计契约已稳定。高密度 V165 交叉来源或真实物流/结算原件未证实前，不得放松凭据/归属或绕过独立财务复核。

项目评分保持 **70/100**；`realizedNetProfit=null`，真实 Seller/Ads OAuth OFF，G1/DEC 口径待批准，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。
