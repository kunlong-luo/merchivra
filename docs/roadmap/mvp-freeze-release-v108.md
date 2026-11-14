# V108 — MVP 收尾、集中测试与上线准入（2026-11-08）

## 2026-11-14 / V163 手工成本来源入库和独立审核（**当前工程状态**）

> 已新增 [V163 成本凭证存证/审核规则](../finance/cost-source-review-v163.md)：PostgreSQL 18 Flyway **V91**，租户/Marketplace/原币约束、跨类别同来源键唯一防重、原始凭证 metadata 不可变触发器、一次性不同 Owner 双人审核及追加审计。后端 Owner Session/CSRF 新增成本凭证登记、列表、复核、审计 API。所有来源仍 **MANUAL_UNVERIFIED**，复核通过为 **REVIEWED_UNVERIFIED**，不是真实第三方 Ads/财务核验，**不自动接入 V162 扣减或 V64 利润报告**。

- **验证边界**：V163 改了真实 PG18 DDL、敏感资金与 HTTP 读写 API；PR 必须通过 Java 25/Gradle 财务来源/权限负例与文档 CI。合并后须对**精确 main SHA**重新执行完整 A3 (PG18/Flyway + Spring Session/CSRF + Next/Vite 合成 HTTPS/Chromium)，不能沿用 V160 的 7/7、也不能用 V162 的纯算法 CI 代替。实际第三方费用真实性/反重复凭证与 maker-checker 财务 G1 审批仍需另审。
- **剩余真正 P0**：持久化成本来源与当前 V33、Ads 离线报告、库存/物流票据的**跨来源防双计**；按 SKU/订单/期间的有金额守恒的分摊与未分摊池、版本化 V64 全成本报表、Next.js 成本覆盖与利润瀑布、真实来源签署。跟踪 [利润 P0 #12](https://github.com/kunlong-luo/merchivra/issues/12)。
- **生产与前端**：[G1/G2/G3 #104](https://github.com/kunlong-luo/merchivra/issues/104) 仍 OPEN/NO-GO；Next.js 16 是正式目标，Vite 目前仍是旧默认与安全回退；Amazon Seller/Ads 真实授权和远程读写继续关闭；V148 TLS 历史问题 [#115](https://github.com/kunlong-luo/merchivra/issues/115) 未证实根因。

---

## 2026-11-14 / V161 利润优先产品目标 + Next.js 单一前端（V161 方向）

> **正式产品方向**：Merchivra 首要价值是**完整成本归集 → 可追溯利润核算 → 订单/SKU/店铺/站点/期间经营报表**。采购、库存、FBA/FBM、广告、订单、Finances、Settlement 和回款是成本与收入来源，而不是优先级高于利润的孤立子系统。详见 [V161 利润优先产品/交付计划](../product/profit-first-next-primary-v161.md) 和 [仍待 G1 签署的财务口径](../product/finance-rulebook.md)。

- **当前真实代码边界**：已有 V33 单订单贡献、V61 SKU 预览、V62 多 SKU 受审分配、V64 冻结整期原币贡献及 CSV，**但未实现完整净利润**：广告、头程、仓储、FBM 配送、退款成本、汇率及其他期间费用并未全部可验证纳入。必须显式显示缺项/未知状态，禁止将“贡献估算”命名为“已实现净利润”。
- **现在的重点**：P0-A 成本科目/真实来源与金额防重复 → P0-B 多类费用分摊/退款历史归属 → P0-C 全成本利润引擎与覆盖率 → P0-D Next 利润中心多维报表与 CSV → P0-E G1 财务样本及全链路对账验收。真实 Amazon Seller/Ads API 依旧需维护者在本地最后授权；在此之前用合成和获审手工来源建设逻辑。
- **Next.js 正式唯一前端目标**：Next.js 16 + React 19 App Router 为主，Vite/Nginx 仅作为**尚未实际目标切流期间**的默认旧部署与短期回滚。不得再为 Vite 开辟长期第二套业务 UI；通过目标 HTTPS、会话/CSRF/租户权限、业务对照、实际受控切流和人工准入后再切换 Compose 默认及移除 Vite 代码。GitHub Pages 仅是独立静态展示，不是登录 ERP。Next 目标 [#63](https://github.com/kunlong-luo/merchivra/issues/63) 技术 Issue 虽关闭，不证明真实生产切流。
- **验收分级**：V160 已有精确 main SHA [a960f7cf93c3dfc8a0f8ab96b534fd0a7252e49a](https://github.com/kunlong-luo/merchivra/commit/a960f7cf93c3dfc8a0f8ab96b534fd0a7252e49a) 的[完整 A3 #43428867257](https://github.com/kunlong-luo/merchivra/actions/runs/43428867257) 首次 **7/7 SUCCESS**。它仅验证合成运行时；V161 属产品优先级与 UI 信息布局更新，需按风险运行前端快速 CI。任何新金融公式必须有金额守恒、多币种、跨期退款/费用防重负例和 G1 真实样本批准；**不能从 V160 A3 推断 V161 财务已验收**。
- **生产与历史故障**：[G1/G2/G3 #104](https://github.com/kunlong-luo/merchivra/issues/104) 继续 OPEN/NO-GO；[#115](https://github.com/kunlong-luo/merchivra/issues/115) V148 历史 HTTPS 根因未证实；真实 Seller/Ads OAuth 和远程读写保持关闭。

---

## 2026-11-13 / V160 自构建镜像与候选 SHA 绑定（历史 V160 技术记录）

> V160 是新的**运行时镜像构建配置变更**：Spring、Next 和 Vite 回退最终镜像均支持从受信构建流程注入 `MERCHIVRA_BUILD_REVISION` OCI label（默认空值，不假造源 commit）。[目标运行容器只读核对](../operations/v160-target-image-evidence.md) 要求独立审查过的三份不可变本地 image ID、人工确认的候选 SHA 和明确授权的 Docker context；只读取固定 metadata，决不读取 Env/日志、自动构建、重启或部署。镜像标签和本地 image ID 匹配**不是**签名验证，更不是发布批准。实际目标未经此 PR 访问。

- **风险对应验收**：因为 V160 修改了镜像与 Compose 构建行为，合并到 main 后必须触发**精确新 SHA** 的完整 A3，不能沿用此前 V157 或 V159 CI 代替。V159 最近主线快速 CI [#43427553987](https://github.com/kunlong-luo/merchivra/actions/runs/43427553987) 首次 SUCCESS，V157 最近运行时 A3 [#43422914368](https://github.com/kunlong-luo/merchivra/actions/runs/43422914368) 首次 7/7；二者都不证明 V160 的新镜像。
- **唯一生产准入**：[G1/G2/G3 #104](https://github.com/kunlong-luo/merchivra/issues/104) 仍 OPEN/NO-GO：缺真正签名或制品可信验证、真实目标 HTTPS/IAM/KMS/ClamAV、离站加密 PG18 PITR/RPO/RTO、外部 Alertmanager 人类 ACK 和组织授权签署。真实 Seller/Ads OAuth 和出站读写全部关闭。[#115](https://github.com/kunlong-luo/merchivra/issues/115) 历史 V148 间歇 HTTPS 故障根因未证实。
- 本里程碑不生成正式 release、没有生产批准权；只允许受权运维将**安全脱敏元数据**带入线下独立审查。报告始终 `productionApproved=false`、`deploymentPermitted=false`。

---

## 2026-11-13 / V159 目标 HTTPS 预检证据质量（历史 V159 记录）

> **已验收基线分开记录**：最近一次**完整 A3** 为 [V157 main 5ac412dab7e71a0703c4887614bfba17fbe2eb25](https://github.com/kunlong-luo/merchivra/commit/5ac412dab7e71a0703c4887614bfba17fbe2eb25) / [#43422914368](https://github.com/kunlong-luo/merchivra/actions/runs/43422914368) 首次 7/7 SUCCESS（仅隔离 PG18、Next/Vite、HTTPS 技术测试）。最近一次**主线快速 CI** 为 V158 [main c911c7c9659cb1d4031d896a8586d61b32b00ee7](https://github.com/kunlong-luo/merchivra/commit/c911c7c9659cb1d4031d896a8586d61b32b00ee7) / [#43424933763](https://github.com/kunlong-luo/merchivra/actions/runs/43424933763) 首次 SUCCESS。V159 的 PR/验收必须另行记录，不能沿用上一个 SHA 宣称本次通过。

- **V159 范围**：[V151–V159 目标 HTTPS 只读核查](../architecture/next-target-ingress-v151.md) 强制 Spring Ping/CSRF API 的 `Content-Type` 必须单一且为 `application/json`；要求受权运维提供 `--candidate-sha` 以供审计对照，但`operatorClaimedCandidateSha` **不是**目标实际运行镜像的独立验证。保持不向 CI 访问外网、匿名 GET/验证 TLS/禁止真实 Amazon、无自动发布和所有隐私脱敏。
- **生产依旧 NO-GO**：[G1/G2/G3 #104](https://github.com/kunlong-luo/merchivra/issues/104) 缺真实目标 TLS/IAM/KMS/ClamAV/PG18 离站 PITR/外部告警人类 ACK 和三组正式签署；[#63](https://github.com/kunlong-luo/merchivra/issues/63) 已由维护者按技术范围关闭并非正式生产上线，[#115](https://github.com/kunlong-luo/merchivra/issues/115) 历史 HTTPS 根因仍待可靠证据。
- **回归策略**：本阶段只修改目标只读审计探针、测试与说明，不修改运行时服务；快速 CI 中的专项负例和文档静态一致性必须通过。不为只读运维脚本重复完整 A3；如未来改真实部署/认证、DB、安全运行时，要重新做风险对应验证。

---

## 2026-11-13 / V158 目标生产证据分工（历史 V158 记录）

> **最近已完整验收的代码主线是 V157**：[main `5ac412dab7e71a0703c4887614bfba17fbe2eb25`](https://github.com/kunlong-luo/merchivra/commit/5ac412dab7e71a0703c4887614bfba17fbe2eb25)，[完整 A3 #43422914368](https://github.com/kunlong-luo/merchivra/actions/runs/43422914368) **首次执行 7/7 SUCCESS**（隔离 PG18/Flyway/HTTP、Next/Vite Chromium、真实合成 CA HTTPS Next→Vite→Next 回切、Java25/Gradle/Compose）。此证据仅属于该 SHA 的**隔离技术验收**。V158 为**只读证据清单和文档纠偏**，不能把 V157 的 A3 当成 V158 改动的新 SHA 验收，也无需仅为文档清单重复完整 A3。

- **A0/A1/A2/A3 技术范围**：采购/供应商/仓库分页 [#62](https://github.com/kunlong-luo/merchivra/issues/62) CLOSED；FBA 关联分页 [#108](https://github.com/kunlong-luo/merchivra/issues/108) 按合成技术范围 CLOSED。Next 技术跟踪 [#63](https://github.com/kunlong-luo/merchivra/issues/63) 在 2026-11-13 由维护者标记 CLOSED；**这是项目管理决定，不是实际外部环境已受控切流/真实 TLS/IAM/KMS 验收或上线批准**。
- **P0 A5 / [#104](https://github.com/kunlong-luo/merchivra/issues/104)** 保持 OPEN、生产 **NO-GO**：G1 产品/运营/财务真实政策和样本、G2 目标 TLS/可信代理/MFA/KMS/ClamAV/租户权限、G3 离站加密 PG18 物理备份/WAL/真实 PITR RPO/RTO/外部告警人工 ACK 与独立签署仍缺目标证据。Amazon Seller/Ads 真实授权与远程读写继续关闭。
- **V158 工程交付**：[只读生产证据责任清单](../operations/v158-production-evidence-worklist.md) 从 V143 **已脱敏**预检 JSON 生成结构缺项、责任人角色与真实证据需求；任何格式完整的自填声明也始终 **NO-GO/exit 2**。该工具不访问目标环境、不验证签名、不批准或部署。真实证据只在授权人员的受信系统内核验。
- **P1 / [#115](https://github.com/kunlong-luo/merchivra/issues/115)** 保持 OPEN：V148 HTTPS canary 首轮失败时未记录阶段，故无法事后断定根因；V149–V157 的日志和就绪规则加固、后续一次通过不是历史缺陷已被确证并根治。
- **当前顺序**：授权人员提供目标 G1/G2/G3 原件与签署 → 运维只读复核/弥补真实基础设施缺口 → 独立发布批准 → 再讨论实际部署与本地受控 Seller/Ads 授权（仍需单独批准）。**不能承诺生产日期**。

---


## 历史：2026-11-13 / V150 最终合成技术验收（已由 V157 证据覆盖）

> **精确已验收代码主线 SHA**：[`2e96bf421d1b8e0c2a2999ab63a236d80ea3a9d9`](https://github.com/kunlong-luo/merchivra/commit/2e96bf421d1b8e0c2a2999ab63a236d80ea3a9d9)。[V150 完整 A3 #43361557152](https://github.com/kunlong-luo/merchivra/actions/runs/43361557152) **第 1 次尝试 7/7 SUCCESS**：真实一次性 PostgreSQL 18/Flyway/HTTP、Java 25/Gradle、Next/Vite Chromium、Docker Compose 和临时本地 CA 验证的 HTTPS 切流。技术合成测试不能代表目标生产环境，也不能代替逐需求 G1/G2/G3 签字。

- [PR #118](https://github.com/kunlong-luo/merchivra/pull/118) 在可登录的 Next→Vite→Next 合成 HTTPS 测试前，新增独立匿名 CookieJar 的**真实安全会话就绪检查**：匿名 /auth/me 401、/auth/csrf 200 且有令牌、JSESSIONID 必须 Secure/HttpOnly/SameSite=Strict。只对启动期临时 502/503/504 或连接中断进行有上限重试；证书不受信、CSRF 异常、错误授权状态或不安全 Cookie 均立即 fail-closed。沿用 V149 脱敏失败阶段诊断。
- [#115](https://github.com/kunlong-luo/merchivra/issues/115) **仍开放**：V148 A3 首次 HTTPS AssertionError 具体根因无法从当时仅含异常类型的日志确认；V150 是就绪检查加固且本次首次运行成功，**不构成历史问题已定位/已根治的证明**。
- 采购/仓储 [#62](https://github.com/kunlong-luo/merchivra/issues/62) 和 FBA 来源分页 [#108](https://github.com/kunlong-luo/merchivra/issues/108) 维持合成技术验收 CLOSED。Next 目标环境受控切流 [#63](https://github.com/kunlong-luo/merchivra/issues/63)、实际生产 G1/G2/G3/PITR/KMS/Alertmanager [#104](https://github.com/kunlong-luo/merchivra/issues/104)、维护者本地真实 Seller/Ads 只读 OAuth 和数据核验均仍 BLOCKED；未向公网发布真实 ERP，未进行真实 Amazon 写入。
- [RTM-002](../requirements/implementation-acceptance-rtm-v148.md) 的 25 个 P0 代码/test 入口与人审缺项继续生效；本次 A3 只验证此 SHA 的**合成技术**，非每一条 FR 在真实店铺上完成产品/财务签署。

---

## 2026-11-13 / V149 主线回归（最新技术证据）

> **精确主线 SHA** [`57d55b6e54e07cac1473259763ce48e4d96e9816`](https://github.com/kunlong-luo/merchivra/commit/57d55b6e54e07cac1473259763ce48e4d96e9816)：[完整 V149 A3 #43358590421](https://github.com/kunlong-luo/merchivra/actions/runs/43358590421) **第 1 次运行 7/7 SUCCESS**（Java25/Gradle、Next/Vite/Chromium、隔离 PG18/Flyway/真实 HTTP、Compose、CA 验证的本地 HTTPS 代理与同会话 Next→Vite→Next 回滚）。[PR #116](https://github.com/kunlong-luo/merchivra/pull/116) 仅加入 HTTPS canary **固定阶段、无敏感内容**的失败诊断及脱敏单测，并未更改匿名拒绝/CSRF/Secure Cookie/真实 OAuth 安全门槛。

- #62 与 #108 已依据此前合成技术验收关闭；[RTM-002](../requirements/implementation-acceptance-rtm-v148.md) 提供 25 个 P0 的实际源码/测试入口与仍需人工认可的边界。
- [#115](https://github.com/kunlong-luo/merchivra/issues/115) **保持开放**：V148 先失败再成功的本地 HTTPS 断言根因未确定；V149 只增强脱敏定位并且本次首次全绿，不代表“零失败”或实际生产 SLA。
- [#63](https://github.com/kunlong-luo/merchivra/issues/63) 目标生产 Next 受控切流、维护者本地真实 Amazon Seller/Ads 授权与只读来源验收、[#104](https://github.com/kunlong-luo/merchivra/issues/104) 真实 TLS/IAM/KMS/离站 PG18 PITR/Alertmanager 外部值班 ACK/G1/G2/G3 仍**全部未放行**。不在公开 CI 启用真实凭据、生产 API 写入或实际公网 ERP。
- 后续任何业务、安全或部署代码变更都须重新绑定**新的 main SHA**做风险对应验收；单纯文档说明不得冒充该新 SHA 的全 A3 通过。

---

## 2026-11-13 / V148 技术验收结论（覆盖下方 V146/V147 历史当前状态）

> **当前精确主线 SHA**：[`a09fc4b2c8fa49b843e28642fb0fb39b7f114a5e`](https://github.com/kunlong-luo/merchivra/commit/a09fc4b2c8fa49b843e28642fb0fb39b7f114a5e)。[V148 完整 A3 #43356548084](https://github.com/kunlong-luo/merchivra/actions/runs/43356548084) 第 **2 次尝试 7/7 jobs SUCCESS**；包括 Java25/Gradle、Next/Vite/Chromium、隔离 PostgreSQL18/Flyway/HTTP/Compose、V146 本地 CA-verified HTTPS 下一致 Session 回滚。**首次尝试 V146 HTTPS canary 发生 fail-closed AssertionError（A3 曾失败），必须保留此不稳定性记录**，不得写成一次就成功。

- **P0 #62** 采购/供应商/仓储分页 V144 技术关闭；**P0 [#108](https://github.com/kunlong-luo/merchivra/issues/108)** V147 FBA PO/ASN 来源历史/候选 keyset（原 LIMIT 200/100）由 [PR #110](https://github.com/kunlong-luo/merchivra/pull/110) 实现，V148 [PR #111](https://github.com/kunlong-luo/merchivra/pull/111) 修复旧浏览器 fixture、[PR #113](https://github.com/kunlong-luo/merchivra/pull/113) 触发准确主线 A3；在二次完整 A3 7/7 后 **#108 已按合成技术范围关闭**。
- **当前 RTM**：[RTM-002 P0 代码/测试/真实来源/签署追踪](../requirements/implementation-acceptance-rtm-v148.md)。25 个 P0、3 个 P1、1 个 P2 需求均有当前代码/test 入口映射或明确欠缺；**代码/CI 存在不自动代表逐 AC 全验收**，真实 Seller/Ads/银行来源与产品/财务签署仍待用户/负责人独立执行。
- **当前真正阻塞**：[Next.js 目标生产切流 #63](https://github.com/kunlong-luo/merchivra/issues/63)、[G1/G2/G3 真实 IAM/HTTPS/KMS/PG18 PITR/告警及签署 #104](https://github.com/kunlong-luo/merchivra/issues/104)、用户本地真实 Amazon Seller/Ads **只读**授权联调，仍全部不因合成 A3 自动变为通过。下一阶段需先落实有权限的**目标环境验收条件**；不可自动启用生产 OAuth 或远程写。
- **维护原则**：任何后续业务/高风险改动都要在新 SHA 上重新验收。V148 的 CI 首次 TLS 断言失败应另行复查并对失败阶段添加无敏感信息诊断；重试成功不能作为生产可用性统计上的“零故障”。

---

## 2026-11-13 主线 V146 状态补丁（本文件现行优先级，V108 以下为历史执行记录）

> **核查基线**：`main` [`945ffb299f8dccfe321c6792960cb461d5c4bf30`](https://github.com/kunlong-luo/merchivra/commit/945ffb299f8dccfe321c6792960cb461d5c4bf30)（V146 / PR #107）；[相同 SHA 的快速 CI #43284799168](https://github.com/kunlong-luo/merchivra/actions/runs/43284799168) **success**、[完整 A3 #43284799417](https://github.com/kunlong-luo/merchivra/actions/runs/43284799417) **7/7 jobs success**。后者确实运行 Java 25/Gradle、React/Vite、Next/Chromium、隔离 PostgreSQL 18 + Flyway + HTTP/恢复及 Compose；**只证明该 SHA 的合成技术验收**，不等于真实 Amazon/Ads/银行、目标生产部署、正式业务/财务验收或 G1/G2/G3 签署。后续合并新代码必须用**新 SHA**重验。

### 现行执行状态（取代下文 V108–V145 的“当前”“待做”历史快照）

| 阶段 / 优先级 | 截至 V146 的真实状态 | 继续执行 / 关闭条件 |
| --- | --- | --- |
| A0 / CI 分层 | **已落地**：快速 PR CI + 显式完整里程碑检查 | 继续风险分层；生产风险变更需专项 PG18 / 安全检查，不因减少重复测试而跳过 |
| A1 / 采购供应商仓储分页 P0 [#62](https://github.com/kunlong-luo/merchivra/issues/62) | **已关闭（V144）**：采购、供应商、仓储目录/余额/待收货/流水及相关关键选择器均已实现 keyset；[V144 A3 #43221779773](https://github.com/kunlong-luo/merchivra/actions/runs/43221779773) 7/7 通过，包含超过旧 LIMIT 的真实隔离 PG18 HTTP | 不能再把 #62 原始 LIMIT 100/200/500 缺口当作仍未开发；旧数组端点仅供兼容，未来新增功能另立审计 |
| A1 / FBA 来源关联 P0 [#108](https://github.com/kunlong-luo/merchivra/issues/108) | **V147 PR [#110](https://github.com/kunlong-luo/merchivra/pull/110) 分支已实现、主线待验收**：新增 PO/ASN 来源历史与候选全库 keyset、17 个真实 Springdoc/Orval 操作、React 分页和 Flyway V90；旧 200/100 数组接口仅兼容。[分支 full_suite #43354930355](https://github.com/kunlong-luo/merchivra/actions/runs/43354930355) PG18 226/126 真实 HTTP + Java/Next/Compose **4/4 success** | 合并时确认移除临时工作流，以最终 main SHA 重跑完整 **A3 7/7** 才可按技术范围关闭 #108；真实 Seller/Ads、目标生产环境及 G1/G2/G3 独立保持未批准，详见 [V147 专项](../inventory/fba-purchase-pagination-v147.md) |
| A2 / Next 私有 ERP [#63](https://github.com/kunlong-luo/merchivra/issues/63) | **部分完成**：Node 24 standalone、真实 Spring Session/CSRF/PG18 Compose；V144 覆盖 11 条 Next 工作台与跨登录缓存边界；V146 完成隔离 CA 验证的本地 HTTPS、Secure Cookie、同 Origin Next → Vite → Next 保持 Session 的回退测试 | **仍开放**：真实目标域名/CA、可信代理、证书轮换、MFA/IAM、正式 ingress、真实环境回滚/截图审阅、切流授权。详见 [V146](../architecture/next-https-cutover-v146.md) |
| A3 / 合成技术验收 | **V146 当前 main 已完整 7/7 通过**，不等于“剩余 P0 全部完成”或产品/生产签字 | #108 等最后代码修订后锁定候选 commit，并再次执行完整 A3；建立每个 P0 FR 的代码、合同、模拟测试、真实来源、批准人和未完成事项的逐项 RTM，不能只沿用 V86 静态快照 |
| A4 / 真实 Seller、Ads 与外部数据 | **未开始生产授权验收**；已有官方 SDK/读取代码和 Mock/隔离测试 | 维护者**仅在本地**用自有账户和已获批准的角色逐项只读 OAuth/SP-API/Ads、429/撤销/游标/订单/财务数据对账；银行/外部物流需要单独界定来源、范围和批准；远程写入另行审批 |
| A5 / 生产发布 [#104](https://github.com/kunlong-luo/merchivra/issues/104) | **BLOCKED**：V142 仅隔离 PITR/内部 webhook；V143 只读发布预检无法自行批准；V146 HTTPS 也仅本地受控测试 | G1 产品/财务、G2 安全/架构、G3 运维/发布**真实独立签署**；目标环境 TLS/IAM/KMS、真实加密离站备份和 PITR/RPO/RTO、实际 Alertmanager 外部送达 ACK、ClamAV、容量/回滚及审计均须验证。参见 [V143 预检](../operations/v143-production-release-preflight.md) |

### 当前未兑现的交付物与责任边界

1. **代码 P0**：先关闭 #108 FBA PO/ASN 来源历史/候选的全库分页安全断点；V145 只迁移合同、没有修复数组上限；禁止把原先已关闭的 #62 重开或重复建设采购分页。
2. **正式验收文档 P0**：`docs/requirements/traceability.md` 目前仍是设计矩阵，缺少按 FR 列出的“实现代码 / 模拟 SHA / 真实授权 / 负责人签署 / 放行状态”新鲜证据。A3 功能候选冻结前需补正式 RTM；无证据填“待验证”，不可按版本号自动填“通过”。
3. **真实目标环境 P0**：#63 和 #104 的 HTTPS/IAM/密钥、PostgreSQL 18 真实生产恢复、外部告警、业务财务规则和人工作业签署必须由维护者/有权限负责人执行；CI 不得持有真实 Seller/Ads/银行密钥，GitHub Pages 只能是只读合成展示。
4. **非首发 P1**：真实银行/外部物流、完整净利润与正式会计回转、进一步自动采购/预测/通知，均须先经 G1 决定首发范围、来源与口径；不能因为某个模块有 UI/模拟流程就标为正式集成完成。老 Issue #2–#24 的 OPEN 状态也不等于代码未开发，后续应逐条按合并 PR/实际代码核对并关闭或重定范围。

**下一个客观顺序**：#108 代码/安全/专项 PG18 → 新 main SHA 全 A3 + RTM 状态记录 → #63 受控目标 Next 切流/回滚 → 用户本地 Seller/Ads 及外部来源只读试点 → #104 独立 G1/G2/G3 与目标基础设施签署 → 明确批准后 Beta/正式部署。没有经过这些验收就**不能承诺上线日期**。本段是**计划/状态纠偏**，不表示 V147 代码已经实现或有人代替用户批准真实生产授权。

---


> **唯一现行执行计划。** 覆盖 `docs/roadmap.md` 中 V92 及更早阶段的“当前/下一步”历史表述；旧版里程碑记录仍留存，不应作为现行版本信息。此计划明确由项目维护者批准：先补完 P0 功能，再集中运行完整回归；正式 Amazon Seller/Ads 授权最后在用户本地进行。此决策**不是**生产发布批准、G1/G2/G3 的业务签署，也不代表所有功能已完成。

## 基线与状态口径

> **V109 进度核对（2026-11-08）**：本计划在 V108 PR #64 已合并，A0 的 CI/文档策略已经实施；[首次完整合成回归 #42448160593](https://github.com/kunlong-luo/merchivra/actions/runs/42448160593) 已通过。当前进入 A1 的审计/实现排期，尚未把 [采购完整分页 #62](https://github.com/kunlong-luo/merchivra/issues/62) 或 [Next 正式切换 #63](https://github.com/kunlong-luo/merchivra/issues/63) 标记为完成。文档问题修订与后续缺口见 [V109 文档审计](../governance/docs-audit-v109.md)。此补充不改变 V108 当时 V107 代码基线的历史含义。

- 基线为 **V107 合并后的 main**：Java 25 / Spring Boot 4.1.x / Gradle Wrapper / PostgreSQL 18 / React 19 / Next.js 16.4.5 App Router（仍是待切换的正式部署候选）/ Vite+Nginx（仍作为当前正式打包方案）/ TanStack Query / Orval / Zod / Base UI / TanStack Table。
- 已有代码覆盖经营总览、Listing/SKU、Orders/FBA、采购/供应商/仓储、财务来源与估算报表、运营异常和本地用户权限；大部分是本地数据与 Mock 业务链上的功能，不等于真实 Amazon/Ads/银行验收。
- 必须区分五种证据：**代码实现** → **快速 CI** → **集中 PG18/Compose/Chromium Mock 回归** → **真实 Seller/Ads 联调** → **安全/业务正式发布批准**。不得用一个绿灯冒充后面几个阶段。
- GitHub Pages 只托管**合成数据、不可登录、不写真实业务数据**的展示页面，不是已上线的 ERP。
- 不引入 Maven、Vue、PostgreSQL 17；不开启真实 Amazon OAuth/SP-API 或远程写入。保持现有租户 RBAC、Owner 独立审批、CSRF、幂等、事务、来源区分、金额/币种、UNKNOWN != 0。

## 实施路线：先做完 P0，再集中完整验收

| 阶段 | 工作 | 能够进入下一阶段的客观标准 |
| --- | --- | --- |
| **V108 / A0**（现在） | 冻结 M0–M3 新功能范围、更新现行路线图与 Agent 策略、实施轻量 CI + 一键里程碑验证 | 文档/流程与代码一致；普通 PR 快速检查成功；完整验证有明确可执行入口 |
| **A1：剩余 P0 核心功能** | 只修业务断点：采购单/供应商/本地仓库流水等返回上限与全库筛选/游标，跨模块被限制在前 50/100 条的选择入口，核心审计/财务来源完整性；针对实际查询逐一检查，不盲目重写成熟模块 | 真正的服务端游标/搜索、稳定排序与租户权限，跨页与 100+ 样本可操作，未加载数据不被伪称为全量；每批保留关键安全单测 |
| **A2：Next.js 16 正式切换准备** | 对全部会话受保护的真实工作台实现路由/回退/刷新、Spring Session/CSRF/Next Proxy 与 Cookie、OAuth 回调敏感信息保护；切换正式 Web Docker/反向代理并保留明确回退路径 | 真实本地后端 + PG18 的登录/登出/角色隔离/写请求代理成功，能用 Next 独立部署；Vite 回退经验证，公开 Pages 不带业务凭证 |
| **A3：MVP 功能冻结 / 集中回归** | 冻结功能分支，审计 RTM 需求与 API 实际实现；运行一键 milestone workflow 的**全量** Java/Next/Vite/PG18+Flyway+合同漂移/恢复/安全/Compose+Chromium；修复回归并复测直到全绿 | 记录 main commit、工作流 URL、用例覆盖/失败与放行人；P0 阻塞清零；G1/G2/G3 未批准的事项仍在报告中明示 |
| **A4：本地真实接口与私人 Beta** | 维护者在自己环境中启用最小 Seller/Ads 授权，按账号/地区/角色/限流/429/撤销/断点/订单/费用逐项只读联调；敏感数据不进入 GitHub CI 或公开演示 | 授权与撤销、数据正确性、实单与财务/成本来源一致性通过；远程写入需要额外单独批准；本地 Beta 经明确准入 |
| **A5：正式生产发布** | TLS/Ingress、MFA/IAM、外部密钥/Vault、备份/PITR/恢复、容量/并发/限流、日志/告警、漏洞与数据隔离审查、RPO/RTO、正式业务财务口径和 G1/G2/G3 签署、回滚预案 | 全部生产阻塞项验证并有人批准，才允许公网正式业务上线；没有固定日期承诺 |

**范围限制**：新增预测 AI、复杂可视化、额外通知渠道、平台远程自动采购、跨币种伪净利润和与 P0 无关的微服务均延期至 Beta 之后。任何扩展须说明相对 P0 的必要性和上线收益。

## 新的测试策略（实际由工作流执行）

### 每次 PR / main 推送的快速检查

- Java 25 Gradle `test`、Wrapper/无 Maven 检查。
- Node 24 `npm ci`、React/Orval 契约漂移、TypeScript、Vitest、Vite build、Next `typecheck:next`。
- Docker Compose 配置/生产安全 overlay **静态检查**（不启动有状态容器）。
- 公共 Pages **仅 main 推送**发布，PR 不再额外构建重复演示；它仍是单独的合成数据网站。

### 仅里程碑、显式手动和高风险变更时做完整回归

- `.github/workflows/milestone-validation.yml` 提供**单次人工点击**集中执行；**该工作流首次合并到 main 时，仅因自身 YAML 文件发生变化自动启动一次 V108 合成数据基线验证，后续普通功能提交不会自动启动**：完整 CI（含 Next 生产 build/HTTP、PG18/Flyway 与一次性 Mock/恢复/HTTP 安全）、Docker Compose 真三服务+重启、Chromium Vite 与 Next 合成屏幕回归。
- `.github/workflows/ci.yml` 的 `workflow_dispatch(full_suite=true)` 允许单独完整 CI；完整浏览器/Compose 测试也可独立手动启动。
- 非里程碑不因每一次 TSX/CSS/测试脚本修改自动触发独立 PG18/浏览器/全容器构建。代码在开发期可能缺乏完整 Mock 端到端验证，**明确保留风险并且必须在 A3 统一补测**。
- 以下**不能延迟**到最终阶段：租户/店铺越权、Session/CSRF、危险采购/库存写入、幂等与并发、金额守恒/币种、退款双计、UNKNOWN/NULL 安全语义、授权撤销。相关业务改动必须立刻运行针对性单测/必要的 scoped 集成检查；若涉及跨进程或数据库不变量，维护者要手动触发全量 PG18 或验证专项用例，不能只靠前端绿色构建。
- 发布或 Beta 放行必须在目标 commit **集中工作流全绿**，不得用前一次 commit 的绿灯替代。

### 结果记录和复测原则

- 功能批次可以聚合多个小改动到同一个短命 PR，编译失败只修快速检查，不因非风险改动重复运行重型套件。
- 集中验收失败时优先重跑**失败的测试**与相关依赖；只有修复改变最终候选 SHA 后才重新集中验收该新 SHA。
- 测试使用隔离的 localhost/一次性 PostgreSQL 18，Amazon LIVE 默认禁用；浏览器和 Github Pages 仅合成数据。备份恢复脚本只准用于满足 disposable guard 的 CI 隔离数据库。
- 记录工作流运行时间、PR 执行次数及失败类型。不要只按“运行 2 分钟”评估消耗，也要关注多次重复工作流与排查时间。
- 文档：`docs/testing/ci-policy.md`、`AGENTS.md`、`Agent.md`、`docs/roadmap.md` 均引用此现行计划；里程碑入口不可因后续重构被删。

> **A1 源码对标补充（2026-11-08）**：已对 Wimoor、ERPNext、metasfresh 及 Amazon 官方 SDK/示例的实际源码完成 [BENCH-001 对照](../architecture/open-source-erp-benchmark-2026-11-08.md)。结论是保持现有业务状态机、Gradle/PG18/React/官方 SDK，不因竞品有更多界面而增加新 P0。**先做 Issue #62 的真实全库分页/历史入口，再做 #63 Next 正式切换**；供应商应付/采购付款、库存历史可视化等仅为需 G1 评审的 P1 候选。

> **A1 / V110 实施进展**：采购订单 `/orders/page` 的服务端状态/单号检索、租户/过滤器绑定 keyset 和 Next/React 采购表格翻页已经作为第一批代码变更提交；范围与 [专项单测、尚待执行的 PG18 HTTP 里程碑冒烟](../purchasing/purchase-order-pages-v110.md) 在此记录。**Issue #62 其他供应商、仓库库存/待收货、流水截断仍未修复，Next 正式部署 #63 仍未实施。** 本表记录最初断点，不因新增兼容接口而改写历史证据。

> **A1 / V111 实施进展（2026-11-08）**：供应商 `/suppliers/page` 已新增租户绑定的编号前缀与 ACTIVE/ARCHIVED 过滤、稳定编码+UUID 游标；采购草稿、供应商采购规则输入及目录已改走可搜索的服务端分页选择。实现与单元测试详见 [V111 专项](../purchasing/supplier-directory-pages-v111.md)，其 PG18 HTTP（含第 201 个供应商创建本地 DRAFT）留到显式 `full_suite` / 集中里程碑，未执行之前不记为通过。订单/采购规则只读供应商名称映射仍可能在当前页外退回 UUID。**Issue #62 继续开启**：仓库流水 100 条、库存与待收货 500 条上限未解决；Next 正式切换 #63 同样未完成。旧 V108 原始缺口表仍是历史基线，不应当作 V111 改动尚未开展的实时状态。

> **A1 / V112 实施进展（2026-11-08）**：本地仓库库存流水新增 `/warehouse-ledger/page`，支持按仓库+租户隔离的时间戳/UUID 倒序游标、服务器流水类型与 SKU 字面搜索，保留签名数量、收货/操作/质检/批次来源 ID 和业务原因。仓库工作台已替换“最近 100 条”局部视图，保留兼容旧接口。索引使用 Flyway V82。专项 Java/Vitest 及完整 PG18 HTTP 脚本详见 [V112](../purchasing/warehouse-ledger-pages-v112.md)；PG18 仅在 `full_suite` / A3 运行，尚未验证通过。**Issue #62 仍需解决库存余额/待收货的 500 条上限及分页契约迁移，Issue #63 Next 正式切换未完成。**

> **A1 / V113 实施进展（2026-11-08）**：仓库库存余额与已审批/部分收货明细新增全库游标接口 `/warehouse-stock/page`、`/pending-receipts/page`，工作台接入服务端按 SKU、采购单号及状态搜索；旧 500 条兼容接口保留。新增 `/warehouse-stock/item` 在人工库存操作前精确查询 tenant+warehouse+ACTIVE SKU，**未加载或请求失败不是零库存**，现有事务内 expected-on-hand 与 lot-position 校验不变。Flyway V83 索引及 [V113 专项单测/待运行 PG18 HTTP 冒烟](../purchasing/warehouse-stock-pending-pages-v113.md) 已纳入开发分支；普通 PR 快速 CI 与集中 PG18 证据独立记录。**Issue #62 中的仓库目录旧 200 条上限、OpenAPI/Orval 统一与 A3 PG18 全链验收仍然未完成，Issue #63 Next 正式切换未完成。**

> **A1 / V114 实施进展（2026-11-08）**：新增可搜索、租户/过滤绑定 `/warehouses/page` 和精确 `/warehouses/item`，采购收货、调拨、ASN/批次/异常等仓库下拉选择改为全库游标组件，不再被旧 `LIMIT 200` 阻断。另新增 [采购读取域 OpenAPI/Orval V114](../purchasing/warehouse-directory-orval-v114.md)：V110–V114 八项读取 API 采用 Orval v8 生成客户端 + Zod 响应验证，按构建/测试前置钩子生成而非手工签入；采购写入及附属子模块尚未全部迁移，不能视为整个采购域 Orval 工作完成。225 仓库专项 JUnit/Vitest 与一次性 PG18 HTTP（含第 201 仓人工库存 COUNT）已列入 `full_suite`，**未执行的 PG18 不记为通过**；Next 正式切换与真实 Amazon 授权仍未放行。

> **A1 / V115 实施进展（2026-11-08）**：完成 V114 采购 OpenAPI 的核心写入扩展：供应商/仓库/PO DRAFT、提交/审批/取消、真实收货、仓库库存操作及采购明细查询改由 Orval 生成客户端发送，原币金额字符串与 Session-CSRF/后端审批和库存事务锁保持不变。只读仓库标签新增最多 100 个 ID 的租户作用域批量解析，解决第 201 个仓库在 ASN、QC、收货与批次来源链中只显示 UUID；V48 FIFO/FEFO 与 V49 批次操作继续补齐精确 SKU 余额查询，UNKNOWN **不得**被当作零库存。详见 [V115 变更、专项测试和未执行项](../purchasing/core-writes-orval-warehouse-labels-v115.md)。独立采购附属模块尚未全部迁移，A3 PG18 验收仍需明确执行，Next 正式切换 #63 与真实 Seller 授权继续保持关闭。

> **A1 / V116 实施进展（2026-11-09）**：按现有 Java Controller 真实合同，供应商 ASN（9）、采购 QC/退货（13）、V46 收货批次（9）、V47 待检库位/分件（9）共 **40 条附属采购读写 API** 纳入同一 OpenAPI/Orval，采购源规范现累计 58 个唯一操作。已有前端业务函数签名、TanStack Query 与 Zod 校验不变，POST 统一使用 Session/CSRF 安全 transport；动态路径 UUID 校验避免生成 URL 直接插入非法字符串。详见 [V116 范围、测试与剩余缺口](../purchasing/asn-qc-receipt-bins-orval-v116.md)。底层审核、批次隔离、实际收货证据和 Spring 事务锁未改。**旧 ASN/事件等 LIMIT 200 不因 Orval 生成消失；V48/V49、供应商采购计划等附属域及集中 PG18 验收继续保留未完成，Next 正式部署 Issue #63 仍未放行。**

> **A1 / V117 实施进展（2026-11-09）**：V48 已放行实物批次/分段质检/上架/FIFO-FEFO 拣货、V49 库位调拨/过期隔离/盘点观察/审计来源链纳入已有 OpenAPI/Orval 请求生成链；现累计 **71 个采购唯一 operationId**。新增 ASN 通知与不可覆盖事件日志的 Session 租户/PO/ASN 游标绑定服务端全量分页，前端展示第 201 条后真实通知和事件，旧 200 条兼容 API 保留供旧消费者使用。专项 Java 225 通知/事件、CSRF 和 Vitest 测试随 [V117 说明](../purchasing/released-lots-asn-pages-v117.md) 落库。**PG18 全量权限/并发/事务验收仍由 A3 集中执行，不以 CI 快速测试代替**。采购计划/供应商报价、索赔/批次证据、Next 正式切换 #63 与真实 Amazon 授权继续未完成。

> **A1 / V118 实施进展（2026-11-09）**：复用现有 OpenAPI 3.1/Orval v8 生成方式，把 V42 供应商 MOQ/箱规/原币报价/采购计划（11）、V45 差异与供应商索赔（14）、V51 处置证明元数据（3）及 V72 本地交付概览读（1）共 **29 条已实现 API** 纳入生成请求合同，采购累计 **100 个唯一 operationId**；前端保留 Zod 运行校验、CSRF、Session、幂等键、UNKNOWN 防护和服务器审批规则。V52 真实附件仍走专门的 multipart MIME/大小/哈希和 CSRF 边界，不直接拿 JSON Orval mutator 代替。本次还在 V72 页面明确提示：`/delivery/overview` 旧 `LIMIT 300` 及其汇总仅为当前最多 300 行，**绝不是全租户总量**。修复全库汇总仍在 P0 待办；A3 PostgreSQL 18 真实验收、Next 正式切换与 Amazon 本地授权依旧未执行。详情见 [V118 变更](../purchasing/planning-disputes-proof-orval-v118.md)。

> **A1 / V119 实施进展（2026-11-09）**：采购 V72 `/delivery/overview` 旧 `LIMIT 300` 总量截断已通过新的全租户数据库 `GET /delivery/summary` 聚合及 `GET /delivery/lines/page`（订单更新时间+订单/明细 UUID 绑定游标，每页 50）在主 React 采购交付工作台中解决；旧 API 仅作兼容。新增 2 项 Orval 读合同，采购 OpenAPI 累计 **102 个唯一 operationId**。服务端汇总直接从**全部本地符合条件的采购行**计算，UI 不再把 300 条局部求和误作全租户总计；仍保留 ASN 申报≠承运商、QC≠FBA 可售及无历史事实不猜测的规则。[V119 实现、专项测试及 A3 待执行说明](../purchasing/delivery-full-store-v119.md)。真实 PG18 性能/权限/并发/全链验证与 Next 正式切换 #63 仍未放行，Amazon 真实授权继续关闭。

> **A1 / V120 实施进展（2026-11-09）**：复用 V73 已有的供应商本地履约 90 天证据分数和人工催办真实分页，把 6 条 GET/POST 接入 OpenAPI/Orval；V52 已执行供应商退货新增仓库+租户绑定的 `/lot-supplier-returns/warehouses/{warehouseId}/page` 全库 keyset，连同旧兼容读/人工发运/独立收货报告 4 条一起纳入生成合同（本阶段 +10，采购规范累计 **112 个唯一操作**）。现有 V50 处置工作台加入独立 Owner 报告发运和人工收货及可翻页历史；保留 VOID/UNKNOWN、隔离库存**不二次扣减**、发运/收货仅本地声明非第三方核验、样本不足不评分的安全语义。详见 [V120 说明](../purchasing/supplier-followup-return-traces-v120.md)。V50 处置候选的旧定额、V51/52 原件字节与源证据链、A3 PG18 真实安全回归及 #63 Next 正式运行继续未完成，Amazon 真实授权保持关闭。

> **A1 / V121 实施进展（2026-11-09）**：V50 盘点复核、跨仓目的仓 ACK、已隔离处置历史和未规划隔离来源均新增 Session tenant+warehouse+domain 绑定的全库 keyset（每页 50），React 工作台不再以旧 `LIMIT 200` 截断审批/来源候选。V51 盘盈/少收来源选择也使用 V50 服务器分页，原有 V51 纠正审批 `PENDING/ALL` 页保留。V50/V51 共 **18 条 GET/POST** 纳入 OpenAPI/Orval v8，采购规范累计 **130 个唯一 operationId**；Java/Vitest 模拟 225 条每类 5 页、跨租户/跨仓库/跨记录类型游标拒绝以及 CSRF/库存来源/409 校验。旧兼容接口保留但主工作台不依赖旧历史定额。详见 [V121 记录](../purchasing/lot-reconciliation-pages-v121.md)。**PG18 全量真实 Flyway/HTTP、并发+权限+库存、Next runtime #63、Amazon 本地生产授权仍未验收或开启。**

> **A2/A3 / V122（2026-11-09）**：为 Next.js 16.4.5 App Router 创建独立 **Node 24 standalone 多阶段/非 root 镜像** `apps/web/Dockerfile.next` 和可选择的私有 `deploy/compose.next.yaml`；默认仅暴露 `127.0.0.1:3001`，原 Vite/Nginx `web` 保持不变，可作为即时代理回切。编译时明确将 `/api` 代理指向 Compose 内部 `server:8080`，不通过浏览器暴露后端身份/秘密。普通 CI 新增有效 Compose 配置、Next Build、非 root/端口/禁 Seller/禁注入 Secrets 和 Vite 回退校验；V122 完整 Compose 里程碑将通过 Node 容器与 Spring/PG18 真登录、Session+CSRF 及 Cookie/权限校验。**本轮合并后 `milestone-validation.yml` 变更会自动触发一次主线精确 SHA 的全量 PG18、Chromium、Compose 回归，运行结果必须实查**，不能以绿色普通 PR 代替。详情与受控切流/回退见 [V122](../architecture/next-node-cutover-v122.md)。生产公网 TLS、真实 Seller/Ads 本地授权、外部秘密与 G1/G2/G3 签署仍需用户最终批准，故当前不声称正式上线。

> **A3 / V123 集中测试实测发现（2026-11-09）**：V122 main `08dce7a` 首轮 [A3 #42602507335](https://github.com/kunlong-luo/merchivra/actions/runs/42602507335) **失败**，必须保持发布阻塞。Compose 三服务 + **Next16 standalone Node ↔ Spring ↔ PG18 真实 Session/CSRF** 全链通过，但 Chromium 的 V72 合成 Mock 未适配 V119 的 `/delivery/summary` 与 `/lines/page`，PG18 `/pending-receipts/page` 的状态+搜索 SQL 意外拼接 `$2AND` 引发 HTTP 500；V119/V120 真 PG18 冒烟到达并通过。V123 修复这些确诊断点并加入 SQL 拼接单测，合并后通过更新的 milestone workflow **再次在新的 main SHA 运行完整 PG18/Chromium/Compose**。详见 [V123 失败证据与修复记录](../testing/a3-regression-v123.md)。未全绿之前任何快速 CI 或历史测试均不能替代 A3；真实 Amazon 授权与正式生产仍关闭。

## 第一批可执行 P0 核查清单（V108 历史基线；采购分页现已完成，参见顶部 V146 状态）

| 核查对象 | 当前有证据的缺口 | 下一步 |
| --- | --- | --- |
| 采购单 `GET /api/v1/purchasing/orders` | V107 直接显示 API 数组，无游标/真实全库筛选 | 查明后端实际 LIMIT/排序、索引、tenant scope，优先落地 server paging，兼容旧读取 |
| 供应商 `GET /api/v1/purchasing/suppliers` | V107 本地选择/目录消费数组 | 盘点是否隐式截断、状态过滤、全量 SKU 关联；必要时复用既有 keyset 与 Orval |
| 仓库流水 `GET /api/v1/purchasing/warehouse-ledger` | 界面明确“最近 100 条”，不可能宣称全历史 | 规划只读、稳定 UUID/时间游标与仓库+租户检查，并单独验证完整历史 |
| Next.js 正式部署 | V99–V107 App Router 可用但仍标注 preview；当前默认部署是 Vite/Nginx | 真实 backend 同源代理与会话/CSRF、路由/登录复测后改 Docker runtime，保留回退 |
| 财务/产品验收 | V64 报表与 V66 Ads 本地来源已实现，真实银行/Ads/Seller 未验收，G1/G2/G3 未签署 | 先按事实记录未验证项，最后由维护者本地接入真实来源并办理正式准入 |

**上线时间**：可把私人 Beta 作为 A4 的近期目标，但任何“2–4 周”只能是预算估计，不能在真实授权/安全/业务验收尚未做完时承诺日历上线日期。正式公网发布日期暂不设定。


> **A1 / V145（2026-11-12）**：FBA 本地 PO/ASN 来源关联的五条现有接口纳入真实 Springdoc/Orval，FBA 入仓合同累计 15 项操作。前端移除手写请求并保留 UUID、Zod、CSRF/no-store；见 [V75/V145 合同与测试范围](../inventory/fba-purchase-link-v75.md)。关联历史 200 条与候选 100 条硬上限仍存在，另由 #108 跟踪；#62 已按 V144 验收关闭。新提交完整 A3 需实查；#108 记录后续 FBA 分页缺口；#63 Next 切流与 #104 目标生产签署、真实 Amazon 本地授权仍未完成。
