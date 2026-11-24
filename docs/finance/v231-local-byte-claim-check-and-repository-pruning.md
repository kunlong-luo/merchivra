# V231 · 浏览器原始文件字节 SHA-256 对照与仓库文档清理

**2026-11-24 / V231 精确代码 SHA `9ff02147de17` 的 Java/Next/Vitest/Chromium/PG18 完整 A3 7/7 SUCCESS 已验收；本地 SHA 比对始终不代表第三方原始费用真实性。** 上一轮 [V230B A3 7/7](https://github.com/kunlong-luo/merchivra/actions/runs/45368066154) 只覆盖已验收的外部材料**缺口分类**；不能继承上轮结果声称本轮浏览器字节核对或仓库清理已经通过。

## 直接解决的实际问题

V91 存有 `source_document_sha256`，但它此前是**操作者输入的声明**，并没有校验操作者手上文件的原始字节是否恰好对应该摘要。V230 对八类费用仅列出独立真实第三方原文件、签发者和付款/COGS 的缺口，但不允许上传原件或改变真实费用状态。本轮增加**隐私优先、完全离线的浏览器 SHA 比对**，仅作为手工声明的本地内部一致性工具，**并没有引入任何可自动解除外部真实性阻断的证据**。

## 实现与威胁模型

- `apps/web/src/lib/local-document-digest-v231.ts` 调用浏览器原生 `crypto.subtle.digest('SHA-256', rawBytes)` 对用户自愿选取的原始 `File/Blob` 字节重算并与 V228/V226 服务端返回的 V91 原始声明 `documentSha256` 比较。只接受小写、非零64位 SHA；文件长度要求 **1 字节–8 MiB**，并在 `arrayBuffer()` 返回后核对实际字节数一致。超限/空文件/无 Web Crypto/长度变化全部 fail-closed、不会返回成功。计算完尽力清零读取缓冲，但 JavaScript 引擎可能保留内部拷贝，不能宣称内存中绝对消失。
- 结果分为 `LOCAL_BYTES_MATCH_USER_CLAIM_ONLY` 和 `LOCAL_BYTES_MISMATCH_HOLD`；**两种状态均** `independentlyIssued=false`、`externalChargeVerified=false`、`actualCogsVerified=false`、`realizedNetProfit=null`。MATCH 只说明用户选择的此刻文件字节与用户过去声明的摘要相符，**不**证明 Amazon、Ads、银行、货代/WMS 签发、真实结算或本地用户有权使用该文件。
- `apps/web/src/LocalDocumentCheckPanelV231.tsx` 由 V228 批量费用结果中已选 source UUID 启动；只读当前 source 的原始声明摘要，不修改后端/数据库，不写 cookies、localStorage、IndexedDB，不传文件名/内容，不调用 `fetch`，不依赖远程哈希 API。换来源将丢弃旧比对结果；竞态中的旧异步结果通过 request ref 丢弃。用户选择的文件不会存入 React state。
- `ManualCostBatchPanelV228` 在 V230 仍展示外部材料清单和 false/null 真实性旁新增本地比对；相符不改变 V230 清单，不提供“认证/入账”按钮。**V91 自述 SHA 固定不变，文件改变必须重新选择、重新比对。**
- 只读操作没有后端新 API，没有 Flyway，不更改原币金额、Session/CSRF、Owner 独立 reviewer、V182/V229、COGS、V64 或 Seller/Ads OAuth；无法处理 8MiB 以上文件时提示限制，不假装散列已算。

## 精确代码与可复核验收

- V231 代码及文档清理接受 SHA [`9ff02147de17996d28ec95875a4eaa6445e1c197`](https://github.com/kunlong-luo/merchivra/commit/9ff02147de17996d28ec95875a4eaa6445e1c197)：[完整 A3 #45373462870，run_attempt=1，**7/7 SUCCESS**](https://github.com/kunlong-luo/merchivra/actions/runs/45373462870)，同 SHA [快速 CI #45373462897 **SUCCESS**](https://github.com/kunlong-luo/merchivra/actions/runs/45373462897)。
- Next/pnpm/Vitest：**94 个测试文件、461 项通过**，其中 V231 新 5 项涵盖标准 SHA256 原始字节匹配/不匹配、无效摘要/空文件/超限、读长度不一致、缓冲清除和无 `fetch`；合成数据不是第三方来源证明。Java/Gradle、Chromium、Compose、mock-chain 与真 PG18 全部成功。
- 真 Spring Boot / PostgreSQL18 Job #138373426010 再次通过 V229 V91 1/0/2 审计负例和 V230 费用类别清单/候选碰撞/401/404/零次写版本。V231 **没有**对新文件原件进行真 PG18 上传或外部签发者鉴权，故不能从本轮 PG18 PASS 推断真实平台账单已证实。
- 该 A3 Compose Job #138373425952 执行 `python3 scripts/check_docs_consistency_v109.py`，实际输出 **`PASS DOC-196 documentation: 26 entry docs; 504 relative local links; current runtime/release boundaries`**，证明已删别名/旧 V120 不再是必需文档，现行目录/README 路径仍可导航。另对十份权威核心文档的 **214** 条本地链接进行独立查询，无缺失。
- 后续仅修改文档的 `main` 提交属于新的 SHA，只可报告其自身快速 CI；上面精确代码的 7/7 不自动为不同 SHA 背书。

## 测试验收矩阵

`apps/web/src/lib/local-document-digest-v231.test.ts` 用 synthetic `abc` 的标准 SHA-256 `ba7816bf...` 测试正向字节一致（仍 false/null）、`abd` 字节不同、错误长度/大小写/全零摘要、空文件、超过 8MiB **读取前拒绝**、实际读长度不等、读取后字节缓冲尽力清零和对全局 `fetch` 零调用。真实浏览器动态来源切换继续依赖既有 Next/Chromium 流程与独立 Vitest；**没有合法外部原件可用于真正第三方来源验收**。若这些测试/全 A3 失败，必须保持评分不增加并记录具体修复后的精确 SHA。

## 仓库审计与实际删除

盘点旧 `main` Git Tree 共有 **1,432 个 tracked 文件**：`apps/` 999、`docs/` 286、`scripts/` 122，其余为 CI/部署/运维/根规范。**未发现**被跟踪的 node_modules、dist/build、.next、运行日志、`pom.xml`、npm/yarn 旧锁文件或其他明显生成垃圾。保留 `apps/server/`、`apps/web/`、`deploy/`、`ops/`、`scripts/`、`.github/`；全部有可执行或审计用途，**不删除实际工作目录**。

**确认并已从 `main` 删除的两份冗余文件：**

1. `Agent.md`（旧大小写兼容跳转）：自 DOC-196 开始已不是独立规范，根 [AGENTS.md](../../AGENTS.md) 是唯一现行可执行规则。删除避免第二份文件后续偏离；旧内容可通过 [删除提交](https://github.com/kunlong-luo/merchivra/commit/ae3dbca6cc81874dd4f1a9ee8ae7ffcc711d5017) 和 Git 历史恢复，**不应再创建同名空占位符**。
2. `docs/purchasing/supplier-followup-return-trace-v120.md`：旧英文简稿仍标注 PR #79/PG18 **待运行**，与内容和接口更完整的 [正式 V120 供应商退货证据](../purchasing/supplier-followup-return-traces-v120.md) 功能重复且时态容易误导。删除记录 [`829dc5f`](https://github.com/kunlong-luo/merchivra/commit/829dc5f31390530e757ff2f1307e88284ad24cb0)，保留 Git 历史；已检查本领域其他文档没有引用旧单数文件名，文档 guard 将阻止其被重新加回。

**有明确用途所以保留而非误删的历史**：`docs/roadmap.md`、`docs/roadmap/mvp-freeze-release-v108.md` 等显式历史路线/失败 A3 汇总，独立的 Vxxx 财务/仓储证据、旧评分、Flyway 迁移、Vite rollback + Pages synthetic 入口、部署备份/监控与单文件目录的 ADR 模板、图纸、业务流程。旧路线图不能再作为现行任务；所有历史文件都有 Git 内容追溯价值。**本次没有发现无用的完整目录需要删除**，不为减少数字破坏仍使用的部署/测试/文档链接。

## 文档清理与持续守卫

- [根英文 README](../../README.md)、[根中文 README](../../README.zh-CN.md) 已重写：每个顶层代码/部署/运维/自动化/文档目录的用途，以及全部 `docs/*` 功能分类、Vite回退边界、唯一 `AGENTS.md`、本地启动和真实净利润 NO-GO。
- [docs/README.md](../README.md) 收敛为**唯一文档导航**，分清现行状态/计划/规范与历史证据；[current-status.md](../current-status.md)、[current-plan.md](../roadmap/current-plan.md) 大幅减少各版流水账重复记录，保留近期精确 SHA/失败链接，通过域内 Vxxx 保留历史。版本证据不删除、不篡改。
- `scripts/check_docs_consistency_v109.py` 原本强制保留 `Agent.md`，现改为**强制不存在**、阻止过时 V120 重复草稿回归，并检查双语根 README 明列重要运行/文档目录及 V230 真实评分证据、全部已有入口文档链接。不使用 npm 或 Maven，不另造检测脚手架。运行本脚本、`scripts/check_ci_cadence_v108.py`、Java/pnpm CI，最后必须在同一代码 SHA 上通过完整 A3。
- 部分历史档案的文字（例如 DOC-196 当时为兼容保留 `Agent.md`）应在顶部追加 V231 勘误说明**历史时间点**，而不是回写已提交的历史事实；当前行为以本文件与 AGENTS/README 为准。

## 下一步与评分

**V231 新功能已由精确代码 SHA 的完整 7/7 和同 SHA 快速 CI 验收，可以按保留原六维后的实际可操作字节比对能力复评**。**现行独立成熟度评分为 [V231 76/100](../governance/project-scorecard-v231-2026-11-24.md)**，相对历史 [V230 75/100](../governance/project-scorecard-v230-2026-11-24.md) 仅在业务功能完整性增加1分（17→18），未因文档数量/内部模拟重复加分。**真实全成本与净利润真实性必须保持 10/25**，因为独立第三方签发者/银行付款/退货实收与 G1/DEC 并未发生真实核证。

V232 重点应转向合法取得的真实原始文件、签发主体与交易行认证、版本与撤销、重复归属、退款已实收的批次 COGS 和会计签署。需要维护者本地真实 Amazon/Ads 授权时不得擅自启用；`realizedNetProfit=null`、生产 #104 **NO-GO**。
