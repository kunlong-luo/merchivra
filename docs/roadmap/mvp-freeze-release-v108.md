# V108 — MVP 收尾、集中测试与上线准入（2026-11-08）

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

## 第一批可执行 P0 核查清单

| 核查对象 | 当前有证据的缺口 | 下一步 |
| --- | --- | --- |
| 采购单 `GET /api/v1/purchasing/orders` | V107 直接显示 API 数组，无游标/真实全库筛选 | 查明后端实际 LIMIT/排序、索引、tenant scope，优先落地 server paging，兼容旧读取 |
| 供应商 `GET /api/v1/purchasing/suppliers` | V107 本地选择/目录消费数组 | 盘点是否隐式截断、状态过滤、全量 SKU 关联；必要时复用既有 keyset 与 Orval |
| 仓库流水 `GET /api/v1/purchasing/warehouse-ledger` | 界面明确“最近 100 条”，不可能宣称全历史 | 规划只读、稳定 UUID/时间游标与仓库+租户检查，并单独验证完整历史 |
| Next.js 正式部署 | V99–V107 App Router 可用但仍标注 preview；当前默认部署是 Vite/Nginx | 真实 backend 同源代理与会话/CSRF、路由/登录复测后改 Docker runtime，保留回退 |
| 财务/产品验收 | V64 报表与 V66 Ads 本地来源已实现，真实银行/Ads/Seller 未验收，G1/G2/G3 未签署 | 先按事实记录未验证项，最后由维护者本地接入真实来源并办理正式准入 |

**上线时间**：可把私人 Beta 作为 A4 的近期目标，但任何“2–4 周”只能是预算估计，不能在真实授权/安全/业务验收尚未做完时承诺日历上线日期。正式公网发布日期暂不设定。
