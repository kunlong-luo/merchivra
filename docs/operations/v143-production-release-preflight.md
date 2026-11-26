# V143 — 生产部署前安全核查与 G1/G2/G3 证据门禁（不可自动放行）

状态：**仅实现可执行的只读预检。实际生产部署未批准、未尝试。**

技术基线：V142 [PR #102](https://github.com/kunlong-luo/merchivra/pull/102) 于主线提交 `25a1eebb1b21083f6e0b84394bd97f525d731221` 合并；[完整 A3 #43198515512](https://github.com/kunlong-luo/merchivra/actions/runs/43198515512) 7/7 通过。该测试确实验证了隔离 PostgreSQL 18 两容器 WAL 归档/指定时间恢复，以及内部 Alertmanager -> 虚构 webhook 送达，**绝没有**证明目标生产环境已有异地加密备份、外部值班接收方或正式上线权限。

## 已实施：可运行、始终不可自动放行的生产预检

- `scripts/check_production_gate_v143.py` 使用 **Python 标准库**。只从 stdin 读取由 `docker compose ... config --format json` 生成的实际叠加配置（可能含密码；程序不会输出原值），可选读取运维在私有空间保存的 G1–G3 **证据元数据** JSON。固定检查项目以代码标识输出；没有 Docker/网络、数据库调用、修改 GitHub 或启动服务的能力，不能启用 Amazon 或清除证据原件。
- 必须采用 PostgreSQL 18、数据库/Redis/clamd 端口不对外暴露、Spring 和 Next/Vite 回退端口仅绑定 `127.0.0.1`、Next 存在健康检查、Demo 关闭、Session Secure Cookie 开启、所有 Seller/OAuth/远程写和订单同步保持关闭。检查内部 clamd DNS 不得为空或明显为公网示例/回环地址；**不会因 DNS 字符串符合格式就判定真实 clamd 可用**。检查数据库密码已配置且非已知示例/CI 占位符，但不会泄露原密码，也不会声称它由真正的 KMS 注入。
- G1/G2/G3 控制项分别要求来自**目标环境的独立运维声明元数据**（代码为 `operator_attested_target`），包含对应项的 `status=verified`、证据文件 SHA-256 摘要、带时区实测时间和复核者标识。G3 记录不得超过 7 天，G1/G2 不得超过 30 天（只是 **候选新鲜度窗口**，不是经过批准的合规保留期）。G1/G2/G3 的主审标识必须互不相同，单项复核者不能是该组主审。交叉环境、伪造 SHA、未来/过期证明、CI 合成数据证明、未复核或自批准都拒绝。
- **关键防伪边界：这是自声明元数据的结构/时效验证，不是数字签名验证，更不是对证据原件的真实性、证书链、归档存储或人员身份进行独立验证。** 脚本输出字段 `externalApprovalCryptographicallyVerified=false`、`productionApproved=false`、`deploymentPermitted=false`、`automaticReleasePossible=false` **永远不会变成 true**。当资料齐备且预检未发现问题时，只能得到 `AWAITING_OUT_OF_BAND_G1_G2_G3_APPROVAL` 并以退出码 **3** 提示人工线下复核，绝不是“允许部署”。默认/缺项输出 `BLOCKED`、退出码 **2**。结构错误也阻止运行，不输出密钥。

V143 不创建新的 Amazon 凭证、外部账号、Webhook URL、DB 用户或部署基础设施；不能通过硬编码 CI/示例凭据使生产预检通过。

V158 新增 [脱敏证据缺项与责任人清单](v158-production-evidence-worklist.md)：它**只读取本程序已经脱敏的输出**，用于分派目标环境证据，不接受原始 Compose 配置，也不会判断真实签署有效或授权部署。

## G1 / G2 / G3 负责人应提供的实际证据

| 门禁 | 需要线下复核的事实 | 必须由谁批准 |
|---|---|---|
| **G1 · 业务** | 目标卖家/站点、FBA/FBM、SKU 规则与首批 P0 范围已确定；运营已检查进销存/采购/FBA 异常闭环；财务独立核准原币/成本/退款/费用口径及历史复算，关闭相应 DEC-01～08 | 产品负责人、运营负责人、财务负责人，各自提交文档、验收记录与真实但脱敏的样本 |
| **G2 · 安全架构** | 实际 HTTPS/TLS、trusted proxy/Session Cookie、MFA/IAM 与租户隔离、专用 KMS/密钥轮换、最小化 DB 权限；私网 clamd 真实 EICAR 签名与故障 fail-closed；Next 登录/CSRF/刷新代理和 Vite 回滚实测 | 安全负责人、技术/架构负责人、基础设施负责人 |
| **G3 · 运行/发布** | PostgreSQL 18 独立加密离站物理备份；实际 WAL archive / PITR 从目标备份到隔离实例，验证数据和恢复时间；经认可的 RPO/RTO 数值；实际磁盘/WAL/备份/归档地告警与接收方值班确认；可执行回滚与发布变更批准 | DBA、值班负责人、发布负责人，业务/安全联合最终复核 |

模板 [`ops/release/v143-production-evidence-template.json`](../../ops/release/v143-production-evidence-template.json) **所有项初始均为 `pending`**。它不能作为生产证明，也不能把包含真实签名或实名人员/密钥的文件提交到 Git、GitHub Issues、公共 CI 或公开演示站。实际审核资料与证据文件在受信安全系统中保存；模板只说明需要的字段。上传的摘要是文件指纹，不是已验证的外部电子签章。

两项历史技术跟踪 Issues：[#62](https://github.com/kunlong-luo/merchivra/issues/62)（采购/供应商/仓库分页）和 [#63](https://github.com/kunlong-luo/merchivra/issues/63)（Next.js 运行切换）都已由维护者 **CLOSED**；**这只是技术跟踪状态，不代表真实外部 Next 受控切流、目标 IAM/TLS/KMS 或 G1/G2/G3 获得独立签署**。正式生产阻塞仍由 [#104](https://github.com/kunlong-luo/merchivra/issues/104) 追踪。治理文档 [G1 静态审查](../governance/g1-review-2026-10-30.md)、[G2 静态审查](../governance/g2-review-2026-10-30.md)、[未决决策](../governance/decision-register.md)均明确仍待负责人签署。

## 运维在自己的受信环境执行（**不启动生产 Compose**）

1. 先人工核对目标数据库、HTTPS 反向代理、证书、IAM、真实私网 ClamAV、PG18 备份归档与 Alertmanager，以及 Git 仓库的发布候选 SHA。运维从秘密管理器把必要的配置注入 **本机临时环境**；不要使用仓库中的 `.env.example` 当作生产配置，不能把真实 `.env` 放入 Git。
2. 在 **受信运维主机**外部将待审模板复制到私有目录（例如 `/secure-ops/v143-evidence.json`），填写每项真实目标环境证据的哈希、日期、复核人。仍需在线下复核其身份和签名，不接受只填 `verified` 的所谓审计完成状态。
3. 在目录与环境确认正确之后，仅解析配置并运行预检：

```bash
set -o pipefail
# .env 和外部证据文件必须来自运维秘管；只传递给本地私有进程。
# 真正环境里的 SECRET 可能包含在 docker compose config 的 JSON 中：
# 绝不向终端、聊天、日志或 CI 输出中间 JSON。
docker compose --env-file /secure-ops/merchivra.env \
  -f deploy/compose.yaml -f deploy/compose.production.yaml \
  -f deploy/compose.next.yaml --profile optional-cache \
  config --format json |
  python3 scripts/check_production_gate_v143.py --compose-stdin \
    --candidate-sha "$(git rev-parse HEAD)" \
    --evidence-json /secure-ops/v143-evidence.json
```

命令 **只读**；`config` 不会启动容器，脚本不会调用数据库或外部告警。若输入可疑，退出 2；若只是结构上完整，退出 3，需要业务/安全/运维线下批准、真实 Target 验证与单独的变更审批，**不得把 3 当作 CI 部署成功**。任何 release automation 必须独立验证经批准的 G1/G2/G3 签章和组织变更流程，不能将此脚本的任何输出视为令牌或 deploy permission。

## CI 中的可复现负例

普通 PR/Main CI：Python 标准库单测包括 Demo/Cookie 不安全、公开数据库端口、Seller/Orders 开启、空白 clamd、明文示例数据库密码、Next 进程注入 Secret、遗漏 G1/2/3、过期/未来/错误 SHA 的证明、虚构 V142 CI 资料与自签/同一人复核；完整**自填资料即使都为 verified，也不得允许自动发布**。

Compose CI 会解析现有的实际生产覆盖层+Next App Router+可选 Redis，使用 `.env.example` 和没有任何真实外部审批的场景运行 V143 预检，要求 HTTP 之外的静态控制**明确以 2 拒绝**。仅上传**不含 secret、仅有检查代码与状态**的 `v143-release-preflight-blocked` 7 天保留 JSON 作为失败关闭的证据，不能上传合成批准书。

由于 V143 不更改 ERP/Flyway 业务逻辑，继承 V142 真实 PostgreSQL 18 时间点恢复、内网 Alertmanager 送达、V141 磁盘/WAL、V140 原件治理分页、Java 25、Next/React Chromium 与 Compose 全套 A3。新里程碑应在**合并后的同一主线 SHA**验证全部 7 个作业。绿色 A3 只证明代码与**拒绝上线安全门禁**有效；不意味着通过任何业务/生产签署。真实 Amazon Seller/Ads OAuth、外部写入以及原件删除仍保持关闭。


### 2026-11-26 · V279 上线复评（仍 NO-GO）

沿用 V231 六维评分 18/20 架构、18/20 业务、10/25 真实利润、14/15 安全、9/10 前端、7/10 测试运维 = **76/100**；V279 前端及 V278 专门 HTTP 尚未完整验收，不提升评分。评分非上线许可，即便90+也须独立完成 #104 G1/G2/G3。V277 完整 A3 7/7 是隔离合成工程证据，不是目标部署证明。

| 发布层级 | 当前结论 | 条件 |
| --- | --- | --- |
| 受控本地试用 / 隔离合成测试 | 可 | 最新变更仍需 CI；OAuth OFF、无真实业务写入、私有环境 |
| 私有预生产环境验证 | 有条件可测试 | 不连接真实 Amazon，不使用未审核真实数据；目标 TLS/Session/权限、备份/恢复和告警演练；不称正式上线 |
| 真实店铺生产运营 | NO-GO | E1–E5、DEC/G1 财务签署、G2 真实安全、G3 真实目标恢复/告警和独立变更批准；单独批准真实 OAuth |

[Issue #104](https://github.com/kunlong-luo/merchivra/issues/104) 核对仍 OPEN/BLOCKED。优先执行：1) 真实单笔合法报告/Finances/银行回单、采购头程关税、退款 COGS 与完整 SKU/订单/期间净利润复算及 G1；2) 目标 TLS/Session/KMS/ClamAV/租户隔离及 G2；3) 实际 PG18 加密离站备份、PITR、RPO/RTO 与值班 ACK/G3。Redis 独立 smoke 启动问题未确认修复。目标证据与签署不公开、不伪造；未签署不部署生产或启用真实授权。


## V280 复评（2026-11-26）

沿用原六维定性成熟度：架构18/20、**业务19/20（+1）**、真实全成本/净利润10/25、安全14/15、前端9/10、测试运维7/10，合计 **77/100**。唯一加分基于 V278 精确 SHA 的完整 A3 7/7 及 PG18 JUnit 非跳过 marker，证明两位独立 Owner 可以经真实 Spring HTTP 完成分类保存、预检、申请、批准、规则修订后拒绝旧证据的本地业务闭环；V279 快 CI 和 Pages 已通过，但不额外加前端分，V280 待验收不计分。真实 E1–E5、DEC/G1 会计签署、G2/G3 目标证明仍缺，#104 OPEN/BLOCKED；`realizedNetProfit=null`、OAuth OFF。受控本地/隔离测试可进行，私有预生产仅有条件验证，正式生产 NO-GO；评分不是上线许可。


V280 [r2 SHA 07fccfb4be98](https://github.com/kunlong-luo/merchivra/commit/07fccfb4be98cdaee4d46f592c804dd8c5826d75) [快 CI #45742981287 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45742981287) [只读 Pages #45742981345 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45742981345)，[完整 A3 #45742981332](https://github.com/kunlong-luo/merchivra/actions/runs/45742981332) 核对时 pending。原 V280 快 CI cancelled（被新版取代），原 A3 运行中；不能继承新版验收。V270 [Redis #45588988904](https://github.com/kunlong-luo/merchivra/actions/runs/45588988904) [job #139117867603](https://github.com/kunlong-luo/merchivra/actions/runs/45588988904/job/139117867603) 完整日志已核实：初始 checkout 网络错误后恢复，server Dockerfile 的 Gradle bootJar 三次均因 Maven Central HTTP 429（Boot buildpack-platform/loader-tools、commons-compress、spring-core 等 classpath）失败。容器启动及 Redis quota/login/restart/outage 未执行；不能推断 Redis Session 业务缺陷或已通过。

V281：只修改现有 Redis 独立 smoke workflow/script，不改变生产应用、Compose 权限、依赖版本或镜像 Dockerfile。拆分当前 SHA server/web 构建和 no-build 启动，server Dockerfile 原三次有限重试不变，外层仅对官方 artifact GET/HEAD 429/502/503/504 再冷却90秒、最多额外一次整体构建；其他失败不吞掉、不复用旧镜像。用 Compose --wait 与 PG18/Redis/Next 健康链，核对 server/web 镜像 revision 标签与当前 GitHub SHA（标签不是供应链签名），明确 Seller/OAuth/写入 OFF。Redis namespace scan 完整消费，重启核对 StartedAt 已变化、健康就绪及同一 cookie 的 userId/activeTenantId；私有临时 cookie/身份文件，始终清理。断 Redis 后检查容器已停止、匿名 ping 可访问，旧认证 cookie 必须实际返回 401/403/500/502/503/504，网络断开不计为通过；增强故障诊断，不输出配置/凭据。Redis 8/noeviction、原 GCRA 测试保留。独立 smoke 新 SHA 待验收；外部仓库持续限流仍可能失败。为保留 V280 r2 A3 不修改 milestone workflow；V281 独立 smoke 不能替代生产 TLS/KMS/恢复/财务签署。


## V281 复评（2026-11-26）

维持 V280 **77/100**：架构18/20、业务19/20、真实全成本及净利润10/25、安全14/15、前端9/10、测试运维7/10。V280 r2 快 CI/Pages 成功但完整 A3 尚未核实；V281 定位历史 Redis 构建429并强化验收，但未取得独立运行通过证据，不加分。生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) 仍 OPEN/BLOCKED。受控本地及隔离合成测试可进行；私有预生产只用于有条件验证，不能真实生产运营。真实 E1–E5 与 DEC/G1、G2/G3 目标独立签署未完成，`realizedNetProfit=null`、Seller/Ads OAuth OFF；评分不是上线批准。


V281 [代码 cb9bb88eea15](https://github.com/kunlong-luo/merchivra/commit/cb9bb88eea150384fa87a121c60e2a11d4bf42cf) [快 CI #45743424984 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45743424984)，[独立 Redis smoke #45743425032](https://github.com/kunlong-luo/merchivra/actions/runs/45743425032) [job #139653656144 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45743425032/job/139653656144) 实际日志 redis_version:8.10.1、GCRA PASS、真实登录/CSRF、重启后同一 userId/tenantId、断 Redis 后旧 cookie 不授权均通过；无生产凭据。V280 r2 [完整 A3 #45742981332](https://github.com/kunlong-luo/merchivra/actions/runs/45742981332) 核查时5项通过，PG18及完整 HTTP 尚运行。

V282：复用 `scripts/smoke_browser_layout.mjs` 现有 Chromium/CDP 与 localhost 合成 API，新增费用分类工作台专项流程：报告101行而可见100行、全量两个原始类型（第101行佣金）、无自动分类、精确映射及 CSRF 保存、原币预检与当前证据、申请写入、模拟自审批403、清除旧证据、重算新证据与跨 Marketplace 清除面板；保存两个360px截图。断言针对真实 DOM 操作及合成请求，不能替代 V278/V280 真实 HTTP 或真实 Amazon 财务验真。未新增前端框架/依赖、迁移、业务激活或正式会计功能。V282 专项浏览器尚待独立运行；不改 A3 工作流，保留 V280 r2 完整验收。


## V282 复评（2026-11-26）

原六维 **78/100**：架构18/20、业务19/20、真实全成本及净利润10/25、安全14/15、前端9/10、**测试运维8/10（+1）**。唯一新增分值基于 V281 精确 SHA 已完成且日志可核的独立 Redis 8 运行验收，消除长期未运行的 Session/配额专项证据缺口；不是生产环境证明。V280 r2 A3 与 V282 浏览器尚待核，不加分。真实 E1–E5、DEC/G1、G2/G3 目标环境签署仍缺；#104 OPEN/BLOCKED、realizedNetProfit=null、Seller/Ads OAuth OFF。可受控本地/隔离测试，有条件私有预生产验证，不批准商业生产。


V280 r2 [完整 A3 #45742981332 7/7](https://github.com/kunlong-luo/merchivra/actions/runs/45742981332)，七个 job 均 SUCCESS；[PG18 job #139654014988](https://github.com/kunlong-luo/merchivra/actions/runs/45742981332/job/139654014988) 日志确认 `PASS V280 actual full report type discovery JUnit marker verified (not skipped)`。V281 独立 Redis 通过证据保持。V282 [快 CI #45743906965](https://github.com/kunlong-luo/merchivra/actions/runs/45743906965) 的 [Compose job #139655319948](https://github.com/kunlong-luo/merchivra/actions/runs/45743906965/job/139655319948) 失败于 V108 cadence AssertionError: browser；[Chromium #45743907063 job #139655232452](https://github.com/kunlong-luo/merchivra/actions/runs/45743907063/job/139655232452) Vite 阶段通过，Next 阶段在 `V282 full type discovery` 超时，未证明后续保存/审核等动作成功；原因尚未确证。

V283：修正 V108 快速 CI cadence guard，要求 Chromium 的 main push 路径精确等于四个测试契约文件，禁止无过滤 push、常规前端文件自动全量触发及 PR；Compose 和 PG18 原集中测试门禁保留。修正 V282 Chromium 断言：为完整类型覆盖加入明确语义标签，要求选中精确 report、101行/2组覆盖文字（仅归一空白）、两个分类控件且实际 `/category-types` GET；失败记录仅合成请求路径/DOM 摘要及截图，仍抛出超时。动作等待真实可操作（包括 fieldset disabled），自审403必须出现实际 alert 而非静态说明，新证据必须重新 GET 审核。未删原独立性或金额断言，未改变服务端财务门禁。针对浏览器超时尚未证实根因/修复效果，本轮自动触发快 CI 与独立 Chromium 待核，不重跑已通过的 V280 精确 SHA 全 A3。无数据库迁移、依赖或真实 API 调用。


## V283 复评（2026-11-26）

维持 **78/100**：架构18/20、业务19/20、真实全成本及净利润10/25、安全14/15、前端9/10、测试运维8/10。V280 r2 完整 A3 与101行真实 HTTP 合成回归通过，确认此前技术闭环但业务评分已19/20；V282 发现新浏览器回归失败，V283 尚未通过，不增加前端或运维分。不以工程合成数据证明实际利润。#104 仍 OPEN/BLOCKED，真实 E1–E5、DEC/G1、目标 G2/G3 未验收；realizedNetProfit=null、Seller/Ads OAuth OFF。受控本地/隔离合成测试可以；私有预生产仅有条件验证；正式商业生产 NO-GO。

### V283 私有验收交接顺序（不是授权指令）

1. 固定已通过的精确源码 SHA、镜像 ID、Flyway 版本和受控目标；先完成 V283 浏览器与快速 CI，记录失败/修复/复测。只用隔离合成数据，不把 Pages 当目标服务。
2. 业务/财务负责人准备一个真实结算周期的脱敏订单及 SKU、Reports 原始文件、Finances 拆分、Ads 费用、采购与头程/关税/仓储、退款实收和银行回单，在受控本地保存原件及独立签发证明。遵循 `docs/security/seller-oauth.md` 和其后继安全契约；OAuth 需另行批准，勿直接改变默认 OFF。不得上传 Token、客户资料、银行账号、原始财务文件至公开 Git/CI/Issue。
3. 先用既有 V270 本地原件核对（见财务来源契约），随后独立验证 E1–E5 归属及金额守恒、退款 COGS、订单/SKU/期间净利润，确认 G1/DEC-06/07/08；缺失不是零。不得把分类一致或本地审批视为外部真伪/银行到账认证。
4. 目标环境另做 G2 TLS/Session/CSRF/角色/KMS/AV、G3 真实 PG18 异地加密备份/PITR/告警 ACK，记录签署人及实际结果；现有 V281 Redis 仅隔离运行证据，不替代目标 G2/G3。
5. 执行现有只读 V143 预检并由独立发布负责人签署，任何未满足项保留 NO-GO；无签署不开放真实 OAuth 或生产部署。


V283 [SHA 60a74d5c48e3](https://github.com/kunlong-luo/merchivra/commit/60a74d5c48e30375145efa3a2cf47613a7afc11c) [快 CI #45744211055 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45744211055) [只读 Pages #45744211134 SUCCESS](https://github.com/kunlong-luo/merchivra/actions/runs/45744211134)；[独立 Chromium #45744211076 FAILURE](https://github.com/kunlong-luo/merchivra/actions/runs/45744211076)，[job #139656222559](https://github.com/kunlong-luo/merchivra/actions/runs/45744211076/job/139656222559) Next 阶段日志显示 selected 为空、分类报告子请求为空、报告列表和人工参考仍在加载。说明失败发生在分类服务请求之前；可能为 App Router 初次挂载/重挂载时序，尚未证明唯一根因或生产 UI 无缺陷。V280 r2 A3 7/7、V281 独立 Redis 成功保持。

V284：将原 V282 分类专项旅程移动至既有财务页面明细分页和证据检查成功之后、切换 Marketplace 之前；要求 `/finance` 路由、精确合成报告选项、完整101行/2组覆盖及分类控件共同满足。只在原生 select 被重置时重复相同只读选择，写入按钮不重复操作；等待 fieldset 启用后才输入审核理由；实际报告列表及 `/category-types` GET、保存及申请的精确请求体、审批拒绝 alert、新证据 GET、跨站点清除等断言全部保留。补充根列表请求、路径及选项诊断，使用不与旧 SKU 夹具重复的报告 UUID。未增加超时时间、未跳过失败、未修改业务/财务服务、未开放真实授权。当前仅提交修复，实际浏览器验证仍待执行；如果再次失败按新日志定位应用或测试缺陷，不宣称已完成浏览器验收。


## V284 复评（2026-11-26）

维持 **78/100**：架构18/20、业务19/20、真实全成本及净利润10/25、安全14/15、前端9/10、测试运维8/10。V283 快 CI 失败已修复并通过、Pages 成功，但专门 Next 分类旅程仍失败，V284 修复未验收，不提高评分。当前主线不应称发布候选已通过。#104 OPEN/BLOCKED；真实 E1–E5、DEC/G1、G2/G3 目标证据及签署未完成，realizedNetProfit=null、Seller/Ads OAuth OFF。可以使用已验收基线在受控本地/隔离合成环境试用；私有预生产仅有条件验证；正式生产 NO-GO。下一个可验证目标是独立 Chromium 全绿及精确发布 SHA 完整验收，随后真实财务与目标基础设施；评分不替代上线批准。
