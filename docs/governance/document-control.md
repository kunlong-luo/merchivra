# GOV-001 · 当前文档控制与决策管理

**状态：CURRENT · 2026-11-22 / DOC-196。** 文档分类、优先级、审阅与证据格式在此统一。历史 [V109 文档审计](docs-audit-v109.md) 和 [V191 收敛记录](docs-audit-v191.md) 仅为当时事实；本轮核查见 [DOC-196](docs-audit-2026-11-22.md)。

## 唯一入口与优先级

| 类别 | 权威入口 | 用途 | 何时更新 |
| --- | --- | --- | --- |
| **Current facts / 当前事实** | [current-status.md](../current-status.md) | 实际代码、已通过验证、真实未知和 NO-GO；事实须引用精确 SHA/运行 | 每次实现/验证改变 |
| **Current plan / 唯一计划** | [current-plan.md](../roadmap/current-plan.md) | 未来 P0 顺序、阻塞原因、下一验收 | 任务完成/优先级改变 |
| **Execution policy / 工程规范** | [AGENTS.md](../../AGENTS.md) · [ENG-001](engineering-standards.md) | 技术栈、工作流、安全与测试规则 | 维护者改变规范或升级技术 |
| **Operating guides / 操作指引** | [开发](../development.md) · [CI](../testing/ci-policy.md) · [部署](../deployment/local-spark-db.md) · [API](../api/README.md) | 可执行命令/状态码、配置、运行验证 | 代码或配置接口改变 |
| **Proposed / 待批准** | [决策登记](decision-register.md) · G1/G2/ADR | 候选产品、财务和安全政策 | 正式审查/签署 |
| **Evidence / 历史证据** | 各领域 Vxxx、历史 A3/PR/SHA、[文档审计](docs-audit-v191.md) | 证明**那次**实现和验证，不作当前指令 | 仅追加勘误、记录被替代 |

出现冲突：先核实实现/真实配置和用户已批准的现行决策；当前事实纠正客观内容，现行规范修订可执行要求，开放决策在 G1/G2/ADR 登记；不得让历史记录覆盖现行指令，也不得用成功的 CI 冒充正式签署。

## 仓库文件删留判定与目录治理

**当前仓库目录与正式用途以 [根中文 README](../../README.zh-CN.md) / [英文 README](../../README.md) 为准**，完整删留审计见 [V231 清理报告](../finance/v231-local-byte-claim-check-and-repository-pruning.md)。删除前须核对 Git Tree、现行入口、代码/CI 脚本/业务域文档引用与财务/数据库证据的不可替代性；优先移除已证实的重复待验收旧草稿和不再承担功能的别名。删除时保留 Git SHA/历史可追溯，不靠重建同名占位符“掩盖”旧链接。**禁止**为了减少文件数删除 Flyway、Spring/Next 业务实现、受控 Vite 回退、Compose/监控、审计证据、历史失败 A3 或未签署 G1/DEC 资料。

V231 删除两份确认冗余文件：旧 `Agent.md` 兼容别名和 V120 旧 PR 待验收短稿；独立正式 V120 文档、可追溯的旧 Git 原文、全部目录功能类别均保留。旧的 [DOC-196](docs-audit-2026-11-22.md) 要求暂留兼容别名属于**历史时点**，已经在其顶部追加更新说明，不改变原历史。当前唯一 Agent 规则为根 [AGENTS.md](../../AGENTS.md)，唯一计划为 [current-plan.md](../roadmap/current-plan.md)，只追加符合原始证据的精确 SHA CI 状态。

V235 起 README 仅承担产品入口、目录用途、启动和开发导航；文档中心仅承担分类导航，版本验收集中 current-status/专项证据，不得跨入口重复完整流水账。文档守卫要求最新评分链接，不强制在 README 继续引用过时评分；保留历史证据的原链接或 Git 精确 SHA。

目录合并记录以根 README 的迁移映射为准：V236 运维、V238 UI/UX、V239 架构图纸/产品流程/ADR 模板归入已有领域。原文件可在迁移前 Git 精确 SHA 追溯；评审编号、原图、需求关联和可复用模板保留，不将历史草案当作现行 P0。自动守卫统一检查全部已退役目录、根与 docs 内 Markdown 相对链接，以及每个实际 docs 子目录在中英文 README 和文档中心是否有明确分类。根 ops/ 为实际工具，不在退役范围。

V240 起同一功能的补充规范归入原领域契约，不另建只重复验收结果的 Vxxx 文件；CI 策略只维护执行规则，历史流水账使用精确 Git 快照和领域证据。已归并文件的原文 SHA 与迁移映射必须可追溯，守卫禁止重建旧文件。原阶段事实不回写为当前技术栈。

## 状态词（文档和 CI 不得混用）

- **DRAFT**：未完成提案；**PROPOSED**：可评审方案，尚未批准；**ACCEPTED**：明确记录批准人、日期、范围与依据的决定。
- **IMPLEMENTED**：实际代码与对应的回归测试路径存在；不是外部真实性或上线证明。
- **VERIFIED (synthetic)**：精确 SHA、GitHub Actions run、run_attempt、完成/失败/跳过的 job 范围都已记录；只能证明隔离合成场景。
- **EXTERNAL VERIFIED**：独立、授权取得的第三方来源和批准证据；不能因内部签名或人工复核自动获得。
- **SUPERSEDED / ARCHIVE**：保留旧文本用于审计，开头醒目标记替代的当前入口，不允许再作为执行计划。
- **NO-GO**：生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) 仍禁止；G1/G2/G3 没有真实签署或外部核验不能写成 GO。

## 必须记录的验证证据

每个新版本记录应说明：需求/变更边界、变更过的真实代码文件/迁移、精确 `main` SHA、CI/PG18/HTTP/Chromium/Compose 的各项验证 URL 与 run_attempt、**失败和跳过项**、测试是否合成、原币与租户边界、残余风险、外部未证事项。不允许只写“测试已通过”而没有精确目标；新提交不能继承旧 SHA 的验证。

## 直接 main 的文档维护流程

1. 核对 [文档索引](../README.md)、真实 `main` HEAD、相关实现文件和现有版本资料；先定位旧信息，不复制第二个“当前计划”。
2. 编辑受影响的权威入口和版本证据文档，保持链接可追踪；遵守 [工程规范](engineering-standards.md)。日常直接提交 `main`，**不再以 PR 为必须步骤**，但并发更新必须校验 HEAD，不能 force push。
3. 对纯文档先跑 `python3 scripts/check_docs_consistency_v109.py`、`python3 scripts/check_ci_cadence_v108.py`；提交后检查该 SHA 的快速 CI。变更影响资金/权限/DB 的代码，按 [分层 CI](../testing/ci-policy.md) 另加对应 A3。
4. 涉及未签署经营范围、税费、成本/利润、角色或真实接入时，保留 **PROPOSED** 并记录待审批人；不得擅自将其标记 **ACCEPTED**。

## 责任与正式签署

产品/财务 Owner 负责 G1 和 DEC-06/07/08，Tech lead 负责架构/ADR，Security lead 负责 IAM/凭据/发布风险，Maintainer 负责代码/文档索引与 CI；同一人可有多个角色，**不意味着自己提交代码就自动签署财务/生产决定**。决策在 [DEC 登记](decision-register.md) 记录所选方案、反例/替代、审批人、日期、影响文件与关闭证据。

本轮只定工程和文档规则，不完成真实 Seller/Ads OAuth、银行证明、退款 COGS 回转、G1/G2/G3 或生产 GO。
