# DOC-109 · Merchivra 文档查漏补缺与治理审计（2026-11-08）

> **状态：V109 入口文档审计 + 修订说明；不是产品上线验收报告。** 对照 GitHub `main` 的 Java/Gradle/Node、Controller、Next/Vite、GitHub Actions、PG18/Compose 和最近 V108 的 P0 计划进行。早期设计文档、G1/G2/G3 待签署项必须留存；不能因为文档被纠错就声称业务签署或真实接口联调完成。仓库中已有百余份历史版本文件，本次优先修复会误导开发、测试和部署的**高影响入口**，不把每一份旧版里程碑记录机械地重写成“今天”。

## 本轮审计结论及实际修订

| 优先级 | 文档 | 确认的问题 | 本轮处理 |
| --- | --- | --- | --- |
| **P0** | `docs/README.md` | 2026-10-30 版本还宣称“只完成设计、不存在已完成业务系统”，与 V107/V108 主线不符；没有权威当前导航 | **已整体改写**为当前入口、证据分级、实施/历史/待审批边界及文档索引 |
| **P0** | `docs/development.md` | PostgreSQL 17 旧 CI 被称作最近验证；Core 仍被描述为只读前 50 行；Redis 指令缺少 Compose profile；重型 CI 触发策略过时 | **已修复**，保留 2026-10-30 PG17 **历史标签**，当前技术/CI/游标和真实 P0 缺口另行标明 |
| **P0** | `SECURITY.md` | 把已实现的 Seller Vault、受控员工权限和安全审计笼统写为完全缺失，同时缺少生产阻塞清单 | **已重写**：区分已实现的隔离 CI 与生产未通过的 MFA/KMS/TLS、真实 OAuth、IAM/备份、安全和业务批准 |
| **P0** | `docs/api/README.md` | 把早期 bearerAuth/G2 设计 JSON 的“未实现”用于泛指现有 API；缺少实际 Springdoc + Orval 生成合同入口 | **已分离** G2 设计候选与当前 Session/CSRF、真实 Controller 的模块化 OpenAPI 及生成客户端 |
| **P0** | `docs/requirements/traceability.md` | 顶部仍写“尚未批准或实现”，混淆旧 FR 设计映射与已落地的代码 | **已明确**历史设计矩阵不能代替 V108 代码/模拟/真实验收/生产批准逐项结果 |
| **P1** | `docs/testing/release-readiness-v68.md` | 把 V68 PG18/HTTP/恢复测试称为“每次 CI 自动执行”，与 V108 快速 CI/集中里程碑规则冲突；出现多处显示错误的转义反引号 | **已更新**现行触发策略和基线证据，修复 Markdown 原样显示问题，保留历史安全门槛 |
| **P1** | `docs/deployment/local-spark-db.md` | Redis8 仅被说成“未来缓存”；Next 预览与正式 Vite/Nginx 部署的区别不够醒目 | **已更新**默认部署、Redis8 已实现但 opt-in 的范围、生产 overlay 的公网限制 |
| **P1** | `README.md`、`README.zh-CN.md` | 行内含字面值 `\n`、近期验证链接指向更早的 V61，缺少文档总索引 | **已修复**显示和导航，并改为明确引用 V108 对应 SHA 的集中 Mock 验证 |
| **P1** | `DESIGN.md` | 迭代 2 的 Vue 测试要求、首 50 行过滤被写成现在仍需遵循的规则 | **已标历史**；当前 React/Next/A+B 与实际 Chromium 的证明单独标注 |
| **P1** | `docs/architecture/overview.md`、`docs/roadmap.md` | 早期有 Vue/Maven/PG17；V92“最新阶段”盖过 V108 | **V108 已修复主入口**；V109 保持其当前技术约束并建立交叉链接 |
| **P1** | `docs/testing/ci-policy.md`、`AGENTS.md`、`Agent.md` | 多份早期规则与新流程共存，容易误读旧规则为现行 | **V108 已加入覆盖说明**，本轮补充文档维护的回归约束 |
| **P1** | `CONTRIBUTING.md` | 缺少 Next TS、Orval 和当前 CI 入口，可能引导贡献者把历史设计 JSON 当 API 合同 | **本轮补充**快速贡献流程与风险分层 |
| **P1** | 文档链接和导航 | 核心入口相对路径依赖手工维护，容易因迁移文件破链 | **本轮加入**无需额外依赖的本地链接/现行策略校验，并纳入快速 CI |

> **补充研究（2026-11-08）**：已按源码确认 [开源 Amazon ERP / ERPNext / metasfresh / 官方 SDK 对标矩阵](../architecture/open-source-erp-benchmark-2026-11-08.md)。该矩阵明确了 V20 历史“未做调拨/退货”已过时，并坚持当前 #62/#63 P0 顺序，不把对标候选直接当新上线功能或合法复制代码的许可。

## 本次修订不表示完成的事项

1. [P0 #62：供应商、采购、仓库完整服务端查询](https://github.com/kunlong-luo/merchivra/issues/62) 仍未实现。V107 的本地采购/流水过滤**不代表数据库全量筛选**。后端实际有 `orders LIMIT 100`、`suppliers LIMIT 200`、`ledger LIMIT 100`、`stock/pendingReceipts LIMIT 500`；详见 [代码证据](../testing/p0-purchasing-read-cap-audit-v108.md)。
2. [P0 #63：Next.js 16 生产切换](https://github.com/kunlong-luo/merchivra/issues/63) 仍需真实 Session/CSRF/OAuth 回调、Node 运行镜像、回滚和 HTTPS 代理检查。静态 GitHub Pages 不能充当私有 ERP。
3. G1/G2/G3 的真实财务、税费/汇率、来源认定、生产 IAM/密钥/备份/容灾/并发、安全签审尚无正式批准。审阅 [业务条款](../requirements/g1-business-acceptance.md)、[架构条款](../requirements/g2-architecture-acceptance.md) 和 [发布阻塞项](../testing/release-readiness-v68.md) 时应记录责任人和原始验证证据。
4. **历史阶段文件不是坏文件**。例如 V86 核查中说 Listing/FBA 未分页，是写作当时的真实问题，后来 V87/V88 已处理；保留历史版本，但“当前状态”须引用 [V108 计划](../roadmap/mvp-freeze-release-v108.md)。其余各版分散的架构/UX/集成提案，后续按实际触达的模块逐个复核，不做无证据的大规模全量重写。

## 文档分类与维护规则

- **CURRENT**：`README*`、`docs/README.md`、`docs/development.md`、`docs/roadmap/mvp-freeze-release-v108.md`、`docs/testing/ci-policy.md`、`SECURITY.md` 及基于当前代码的部署说明。维护者应在每次改变运行时/路由/会话/接口/安全边界时同步检查。
- **IMPLEMENTED WITH EVIDENCE**：单个 Vxx 实施记录、实际 Controller/OpenAPI/Orval、一次性 CI/PG18/Chromium 结果。证据必须绑定具体提交 SHA、测试范围与数据来源；不得从通过的 Mock CI 推断真实 Amazon 联调。
- **DESIGN / UNAPPROVED**：G1/G2/G3、FR/ADR、设计式 API/ERD；设计可仍然有效但未自动被批准。任何“未实现”的历史句子不能覆盖当前代码状态。
- **ARCHIVED SNAPSHOT**：V1–V107 阶段路线和 2026-10-30/31 的早期试验；除链接/严重安全错误外，不隐式重写成当前结果。

变更后运行 `python3 scripts/check_docs_consistency_v109.py`（只读本地 repo，不联网、不解析/输出凭证）。快速 CI 会阻止关键入口的相对链接损坏或再次出现旧技术栈的错误当前宣称。**检查通过不等于所有 200+ 篇历史文件已人工审计**。

## 需要后续补齐的高价值文档（待办，不误称已完成）

| 顺序 | 文档缺口 | 完成标准 |
| --- | --- | --- |
| 1 / A1 | P0 #62 新服务器分页契约、索引、游标和前端选择器 | 实现时同步 Controller/Orval/权限约定、100+ 数据定向测试，排除旧 LIMIT |
| 2 / A2 | Next 16 正式容器、同源代理、认证/写入/附件、回滚操作手册 | 对照真实测试通过的代码、启动命令和证书/访问控制，不能只有编译成功 |
| 3 / A3 | **一份正式 RTM 现状/验收矩阵**，取代 V86 历史快照 | 每个 P0 FR 附代码路径、可复现测试 SHA、未验证事项、G1/G2/G3 签署和责任人 |
| 4 / A4 | 真实 Seller/Ads 只读本地授权试点记录 | 权限/地区/429/撤销/限流、财务原始金额、隐私防护及假数据隔离的脱敏证据 |
| 5 / A5 | 安全/生产运行手册及 G1/G2/G3 业务批准记录 | 有实际 HTTPS/KMS、RPO/RTO/PITR 证据、容量/恢复/回滚报告与责任人签署 |

最后原则：**先修误导性的入口文档，再沿着正在实现的 P0 模块补证据，不靠增加文档数量或 Vxx 编号伪造“接近上线”。**
