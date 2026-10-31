# PLAN-001 · 文档先行与分阶段交付路线

> **状态更新（2026-10-30）：PR #1 工程骨架与 PR #14 文档已合入 main；在 main 按 M0→M4 逐步开发。** G1/G2 业务与架构决策仍待签署，未确认部分不应作为真实 Amazon 生产契约。

| 阶段 | 目标 | 交付物 | 通过条件 |
|---|---|---|---|
| D0（现在） | 文档基线 | PRD、FR/NFR、业务图、架构图、ERD、同步、集成、安全、UI、测试、运维 | G1/G2 问题有结论 |
| D1 | 文档评审与定稿 | 业务决策、真实站点与利润口径、ADR 接受记录 | 所有 P0 决策无关键 TBD |
| M0 | 工程骨架验证 | Docker、CI、最小 Web/API、鉴权设计 | 可运行 Mock、无公网上线风险 |
| M1 | 账号 + 同步底座 | Seller 授权、Mock、任务与限流 | 安全授权、可重放 |
| M2 | 商品/订单/库存 | 业务 CRUD、数据同步 | 样本业务验证 |
| M3 | 财务/成本/利润 | 费用、成本与估算报表 | 可对账、可追踪 |
| M4 | Beta | 安全、运维、发布 | P0 验收通过、运行手册完成 |

## 第二轮评审状态（2026-10-30）

目前完成候选设计的交叉修订，但 G1、G2、G3 均**尚未通过**。下一步不是立刻启动 M0/M1，而是处理 [REVIEW-002](governance/review-report-2026-10-30.md) 的 Blocker/High 项，邀请运营与财务确认 [DEC-001](governance/decision-register.md) 后再签署相关 ADR。

## 第三轮 G1 产品设计补全（仍需批准）

[产品业务评审包](product/g1-review-package.md) 已定义 US/NA 试点候选、FBA/FBM 范围、SKU 映射和财务口径；[虚构案例](product/business-scenarios.md) 包括销售/部分退款/FBM 缺费/财务重复/库存未知等。下一步由负责人完成 [G1 决策](governance/decision-register.md) 和 [G1 业务验收](requirements/g1-business-acceptance.md) 的评审记录，然后才能正式启动 G2 物理数据/接口的细化与冻结。

## 第四轮 G2 约束级架构补全（候选）

[DATA-003 物理模型](architecture/physical-data-design.md)、[ARCH-003 一致性](architecture/consistency-boundaries.md)、[INT-003 来源契约](integrations/amazon-data-contract.md)、[API-002 OpenAPI](api/README.md) 及 [G2 评审报告](governance/g2-review-2026-10-30.md) 已成为可审阅的下一阶段设计稿。**G1 未签署，G2 亦未通过**；不得立即实施 Flyway/业务 API。后续先明确 DEC-04/05/06/07/09/11/13，再批准物理唯一约束和正式 Auth/API 方案。

## 开发阶段 CI 决策（已确认）

按项目负责人要求，**M0～M2 轻量 CI**：后端快速单测，前端类型检查、少量 Vitest 与构建。**不执行 Playwright E2E、跨浏览器矩阵、覆盖率或完整容器测试门禁**。M3 在主要业务流程能联通后逐步补关键 E2E，M4 Beta 上线前再按实际安全/业务风险加强自动检查。详见 [TEST-003](testing/ci-policy.md)。

这是开发效率决策，不表示取消真实授权、租户隔离和财务关键规则的必要验证，也不意味着已获准生产部署。

## 建议工作优先级

- 当前第一优先：回答 [Q01–Q08](requirements/decision-questions.md)，进行产品与利润口径评审。
- 第二优先：确认 [ER 图](architecture/data-model.md)、[同步设计](architecture/sync-engine.md)、[安全模型](security/security-design.md)。
- 第三优先：确定 Amazon 开发者权限/站点与真实 API 版本支持矩阵。
- 只有上述通过后，再从已建立的 GitHub Issues 认领开发任务。

## 不建议立即交付的内容

真实业务代码、生产部署脚本、数据库最终 DDL、最终高保真 UI，以及任何声称实际接入成功的演示。这些都依赖需求和授权决策。

## M2 核心库结构进度（2026-10-30）

- [x] Flyway V2 创建租户、Seller Account、Marketplace、内部 SKU、Listing 映射、订单/订单项、FBA 观测和 Sync Stream 等核心表，并在跨实体关联上约束 `tenant_id`。
- [x] 固定虚构账户可通过本地 Mock 成功同步写入结构化核心表，在 Web 同步页展示数据计数；导入使用稳定来源键及幂等插入。
- [ ] 真正的授权 Session、租户/账号权限作用域、真实 Seller 数据适配与 Finances 仍未实现；无真实业务 API。
- [ ] V1/V2 在实际 PostgreSQL 的迁移执行及跨租户失败用例尚未验收，CI 仍仅为快速编译、类型检查和少量单测。

## 本地模拟调用链补齐（2026-10-30）

- [x] 按 INT-002/INT-003/SYNC-002 **语义**添加 MockSpApiClient / 固定 US/DE 分页 Orders、Listing、FBA 场景；没有真实 HTTP Amazon 调用。
- [x] 本地 Owner 当前租户的 LOCAL/UNCONFIGURED Marketplace 可以显式触发 Sandbox 模拟导入，保留 MOCK 来源、事务、审计和幂等记录；前端通过核心业务 API 读取。
- [x] 添加 [TEST-004](testing/mock-full-chain.md) 与手动 Compose 校验脚本，不增加 M0～M2 日常强制 E2E。
- [ ] 真实 SP-API OAuth/角色、官方 DTO 的映射与分页 cursor/Worker 的租约 fencing、Finances 对账/成本利润、生产权限/备份仍未实现；不能把本地模拟运行称为已批准业务交付。

## 2026-10-30 · 实际 Compose 端到端模拟联调

- [x] [Compose 全链路成功运行（2026-10-30）](https://github.com/kunlong-luo/merchivra/actions/runs/41061679196)：真实 Nginx + Spring Boot + PG 构建并执行两店铺 Mock source → 订单分页、Listing、FBA → 持久化 → 登录用户 SQL 查询 → 运行/审计 → 重启后复核 → 退出失效。
- [x] 修复根目录 Compose `.env` 读取和 US/DE 共享内部 SKU 外键冲突；原始币种与 FBA NULL/0 均保留。
- [ ] 真实 SP-API 接入、页级持久检查点/worker fencing、Finances/结算/成本利润、正式租户安全、备份及生产部署仍未完成，不能把 Mock 测试结果当成正式业务验收。

## 逐页持久模拟（V6）

- [x] V6 只增加开发用 `sandbox_page_stream` 和 `sandbox_page_commit`，支持一个 LOCAL/UNCONFIGURED Marketplace 的手动下一页提交，并对同页/同键/同租户设唯一约束。
- [x] 后端用 PostgreSQL 事务行锁与递增 `lease_epoch`，每次只落一个 Mock Orders 页；前端可查看提交进度并手动继续。
- [x] 独立 Compose 脚本新增第 1 页提交后重启、再继续第 2 页及同键重放的验证场景；最终通过结论需参考对应工作流实际运行记录。
- [ ] 尚未实现真实后台 Worker、流租约过期、真实 API 分页 token 过期恢复、持久查询窗口与正式高水位、来源授权或真实对账。

- [x] [V6 逐页断点恢复 Compose 验证成功](https://github.com/kunlong-luo/merchivra/actions/runs/41065562926)：实际 PG 17 + Nginx/Java Compose 验证先提交第 1 页、重启后续第 2 页、重复旧幂等键返回原页，页级事务代数与审计正确；**不是正式后台 Worker**。

## Amazon 官方 SP-API SDK 代码先行（2026-10-30）

- [x] Maven 引入 Amazon 官方 `software.amazon.spapi:spapi-sdk:1.13.0`；提供可构造 SDK 已发布生成类型的内部工厂与常用领域类型化入口。
- [x] 配置 LWA 令牌交换/内存缓存、NA/EU/FE 固定域名、默认关闭网络出站/远程写入；Orders v2026、Listing、FBA、Catalog、Finances、Reports、Feeds 等有安全读取调用封装与离线响应测试。
- [ ] 这仍然**不是所有 SP-API endpoint 的 ERP 业务实现**：授权回调、凭证 Vault、来源实际认证、真实动态限额/Worker/财务对账、通知/Feeds/Reports 文件流程、非卖家/专用业务 API 均需逐模块补全。详见 [INT-004](integrations/sp-api-code-integration.md)。

## V7 公有应用 Seller OAuth 初版（2026-10-31）

- [x] V7 一次性 State hash 与 10 分钟 TTL、Amazon Login URI + Redirect URI、LWA Code 交换、AES-256-GCM 租户级凭证、审计与本地 revoke API；Owner Session+CSRF 保护用户发起/撤销。
- [x] 增加严格的 Amazon Callback Origin 检查、前后两个阶段 Seller ID 绑定，私密 Code 查询日志脱敏，`PENDING_VERIFICATION` 不当作已连接。
- [x] `SpApiSellerGrantVault` 仅在 `VERIFIED/CONNECTED`、活动 Tenant、Marketplace 相符时让内部 Worker 解密；当前没有将状态自动提升的真实账户校验。
- [ ] 实际公有/私有 Seller 授权资格、TLS 域名与密钥管理、API 角色与卖家一致性校验、Token/Key 轮换、账户级任务终止和真实 Amazon Worker/财务仍需开发与验收。见 [SEC-004](security/seller-oauth.md)。

- [x] [V7 真实 Compose/PG 验证](https://github.com/kunlong-luo/merchivra/actions/runs/41145773306)：Flyway V1–V7 与旧 Mock 链全部通过，未开放 OAuth 或插入任何真实 Seller 授权。不是 Amazon 生产授权验收。

## V17 · Seller 身份匹配与 React 连接中心（2026-10-31）

- 修复 Seller v1 核验通过后 `seller_account.seller_id` 仍为 `LOCAL-` 的断点；仅在全部 Marketplace 证据验证与事务 claim 成功时绑定 Amazon OAuth Seller ID。
- 在内部凭证 Vault 增加账号真实 Seller ID 与授权身份匹配校验，保持跨租户和失效证据拒绝。
- React Owner 导航新增授权状态/启动/核验/本地撤销、OAuth 审计和独立 Orders 角色审核（全程 CSRF/同源、默认关闭）；待真实 Amazon/角色/负载/密钥验收。

## V18 · P0 以 Seller 授权闭环优先（2026-10-31）

- [x] 代码：本地店铺 → 公有网站 OAuth → 一次性 State → 加密 Refresh Token → Marketplace 核验 → 状态/脱敏审计 → 本地撤销 → **同 Seller 重新授权**。V18 禁止更换 Seller 重用同一记录；撤销时同步清除旧站点及 Orders 角色证据。
- [x] React Owner 面板：初次授权/已撤销重新授权/手动验证/审计/角色证据审批；默认无 Amazon 出站，绝不把 UI 当成生产授权成功证明。
- [ ] 使用已获批准的 Amazon 生产应用与卖家开展真实网站 OAuth 浏览器回跳、Sellers v1 验证、LWA 密钥与网络、撤销期间 Worker fencing 和安全验收。
- [ ] 私有应用自授权是独立的后续路径，需另行设计仅管理员可用、服务端安全导入的 Token 入库方式。
- [ ] 后续才是 Orders LIVE 增量、Listing、FBA、Finances；未经授权和来源验证，不开启新的自动读取和财务核算。

受控验收依据：[FR-SHOP-01/02](requirements/functional.md)、[SEC-004 OAuth](security/seller-oauth.md)、[SEC-005 验证](security/seller-verification.md)、[ARCH-003 一致性](architecture/consistency-boundaries.md)。


## V19 · 先完成代码，再集中验收（2026-10-31）

维护者要求先实现尽量完整的产品功能，不再把每一轮完整 CI/E2E/真实 Amazon 验收当作开发前置。继续保持 Secret 安全、租户隔离和危险操作禁用；未测试的代码不能标记为「可上线」。

- [x] **代码已加入，未验收**：独立于公有 OAuth 的私有应用 Seller Central 自授权服务器一次性加密导入、相同 Seller 重导入、本地撤销、脱敏状态/审计；V17 记录 authorization_method。部署 Production Compose overlay 与服务端环境配置已新增。
- [x] **代码已加入，未验收**：内部 SKU 主数据创建/改名/归档/恢复、Listing 活跃映射的事务化版本历史/解绑、操作审计，以及 React Owner 操作页；V18 迁移。
- [ ] 待开发：真实 Listing/FBA 只读 Worker 全链、订单行、Finances 交易及对账、历史成本与利润、采购/补货审批、通知与运营规则管理、报表/导出、仓储/发货、广告等。
- [ ] 集中验证：所有 V17/V18 迁移、Java 编译、React 类型与构建、PostgreSQL 18 多租户 HTTP/CSRF、虚构私有导入、Amazon 真实 Seller 授权、生产权限与备份。

[INT-005 私有应用导入](integrations/private-seller-import.md) · [CAT-001 商品/SKU](catalog/sku-management.md)


## V19/V20 补充 · 成本及采购流程（2026-10-31）

- [x] **代码已编写，未验收**：V19 SKU 原币历史成本半开区间表、OWNER 追加价格版本的 API、React 表格与输入。旧周期闭合但不物理删除，单行独立金额不称真实净利润。
- [x] **代码已编写，未验收**：V20 供应商、采购单草稿/提交、另一名 Owner 批准/取消、追加式分批收货与数量上限、状态与审计；React “采购与供应商”页面。**不更新 Amazon/FBA，不视为财务已入账**。
- [ ] Amazon Orders/Listing/FBA 正式同步、Finance/结算/税费与报表、采购库存仓库流水、自动补货建议/审批/通知、成熟企业 RBAC、多币汇兑、出口合规和生产验收仍待开发。
- [ ] 所有本轮新增迁移、代码及页面均未按维护者要求执行测试，后续需集中修复编译、类型、Flyway 和数据一致性问题。

[FIN-004](finance/sku-cost-history.md) · [PO-001](purchasing/local-purchase-workflow.md)


## V21 · 财务来源事实与人工核对代码切片（2026-10-31）

- [x] **代码已加入，未通过集中验收**：独立于 Orders 的 `finance_source_event`、原币/来源分类/稳定唯一键，可靠来源事件不能被重复同键改写；手工录入只标 `MANUAL_UNVERIFIED`。
- [x] **代码已加入，未通过集中验收**：Owner 查看站点财务来源事件及同币分类汇总、审核关联同站点订单、保留审查历史；React 财务与对账工作台及服务端键集分页。
- [ ] 未完成：正式 Finances 角色审核及 SP-API API Worker、结算/回款、可信订单收入/退款汇总、汇兑、订单成本/已知费用的解释性利润、测试与审计。

[FIN-005](finance/source-events-v21.md)


## V22 · 站点级补货策略与保守建议（2026-10-31）

- [x] **开发代码已编写，待统一验收**：按 Seller SKU 配置补货点、目标数量、采购提前期，操作审计；使用最近 48 小时可信 FBA 可售观测提出补货数量，未知/过期/部分观测不返回数量。
- [x] **React 页面及 API 代码已编写**：Core → FBA 库存可设置、查看、编辑和删除补货策略。
- [ ] 真正销量预测、采购建议审批、采购自动生成、Amazon 货件/物流在途、跨仓库存统一汇总、真实 FBA 数据接入仍未实现。

[INV-004](inventory/replenishment-policy-v22.md)


## V23 · Orders v2026-01-01 真实订单项链路（2026-10-31）

- [x] **代码已加入，尚需集中验收**：官方 Orders v2026 `searchOrders` 直接提取商品项 ID、Seller SKU、数量和 `type=ITEM` 的原币商品小计；仅请求 PROCEEDS，不取 BUYER/RECIPIENT；无金额保留 NULL。
- [x] **代码已加入**：V23 增加是否观测订单项与当前行历史标记，订单与当前项在现有租户/Marketplace/授权 Worker 的单页事务中提交，分页 checkpoint 不脱节；老版本/空缺来源不覆盖可信明细。
- [x] **代码已加入**：受 Session/账号作用域保护的订单项明细 API 与 React Core 订单展开视图，区别「未取得」与「已观测为空」。
- [ ] 真实 Seller Orders 2026 联调、实际 Seller 网络限流/边界、PostgreSQL/Compose V23 回归以及订单履约/退款/财务对账尚需集中验证或开发。

[ORD-005](integrations/orders-items-v23.md)


## V24 · 官方 Listing / FBA 只读页同步（2026-10-31）

- [x] **代码已加入，待真实 Amazon 验证**：`searchListingsItems` typed 获取 Seller SKU/ASIN/FNSKU/更新时间；`getInventorySummaries` 获取 FBA 可售/预留/更新时间，缺失保留未知。
- [x] **代码已加入**：独立 Listing/FBA 任务的代次/页游标/租约 fencing/重试与原子写入；V12/V14 已核角色和 Marketplace Vault 每页前后复核，原生 SDK 非 PII 读取。
- [x] **页面代码已加入**：Core Listing 与 FBA 添加 Owner 手动触发只读来源扫描入口，source=LIVE 与 MOCK 分离。
- [ ] 未完成：真实 Seller 授权/角色演练、生产调用与长时间分页容灾、可选 scheduler、Listings 信息扩展、FBA 货件/仓库退货、实际利润。

[集成实现契约](integrations/catalog-fba-live-sync-v24.md)


### V24B · 可选自动分页（2026-10-31）

- [x] **代码已加入、待完整验收**：Owner 手动创建的 Listing/FBA 正式只读扫描可由默认关闭的 Scheduler 逐页执行和重试，保留数据库 Lease/Epoch 与来源权限逐页复核；不会自动对 Seller 发起新任务。


## V25/V26 · Finances 角色审批及平台交易只读（2026-10-31）

- [x] **已编写并提交** V25 独立 `Finance and Accounting` 双人角色审核、历史/撤销与加密 Seller Vault 财务 Scope；此前 Orders 审核不授予财务权限。
- [x] **代码已编写，待验收** V26 SDK `listTransactions`、原币有符号金额、稳定 SHA-256 来源键、`UNCLASSIFIED` 隔离；固定 14 天/48 小时回溯窗口，持久化页游标、租约、重试和事务内来源事件/检查点。
- [x] **代码已编写** React Seller 财务角色审批页面及财务工作台同步入口，另有默认关闭的可选自动分页调度。
- [ ] 真实 Seller Finances 正式数据验收、历史补录、未分类交易 breakdown、退款/费用/结算识别、利润报表与税务口径仍未实现。

[FIN-006](finance/finances-live-v26.md)


### V27 · Finances 原始交易明细层级（2026-10-31）

- [x] 代码新增：官方 Finances Transaction 的 breakdown 嵌套路径、类型、原币金额和父子关系持久化，重复来源不可静默改变明细事实。
- [x] React 财务工作台展示来源事件的 breakdown 树形路径，无论顶层还是末级都不自动加总或推断净利润。
- [ ] 费用业务字典、退款/税金归属、可审计结算关联、成本和广告汇总的利润模型及真实 Amazon 联调尚待实现。


## V28 · Finances 规则分类与质量核对（2026-10-31）

- [x] **已编写待 CI/集中验收**：Marketplace 级精确 sourceType 人工分类、创建/修改/停用审计和历史。
- [x] **已编写待 CI/集中验收**：只对末级 breakdown 做原币 signed 汇总，缺失、混币种、未分类、来源总额不平等均不返回临时类别合计。
- [x] **已编写**：财务工作台逐事件质量状态、规则审批、修订审计和临时分类预览。
- [ ] 平台费用归属、订单成本/退款和结算款核实、真实 Amazon/PG18 联调与正式利润模型仍待开发。

[FIN-007](finance/finance-classification-v28.md)


## V29 · Orders 创建时点和历史单位成本核对（2026-10-31）

- [x] **代码待 CI/本地验收**：保留 v2026 Orders createdTime，LIVE/MOCK 订单项来源明确标记，写入当前项时校验来源隔离与集合完整性。
- [x] **代码待 CI/本地验收**：历史 SKU 映射 + 市场当地订单日期 + 有效期单位成本的 COGS 只读覆盖率与失败原因；缺来源、数量、币种时不返回误导性成本。
- [x] **代码待 CI/本地验收**：React 财务工作台按订单展示成本历史覆盖和缺项。
- [ ] 本地真实 Seller 数据验证、采购批次真实成本追踪、退款/税费/结算对账和净利润模型仍需后续开发。

[订单历史成本完整度说明](finance/order-cost-readiness-v29.md)


## V30 · 结算参考单与 LIVE 财务来源对账（2026-10-31）

- [x] **代码已加入，待 PG18 全链路验收**：人工参考结算单/有符号金额/原币/期间、LIVE Finances 交易唯一关联、人工解除与审计。
- [x] **代码已加入**：同站点同币种候选、未分配计数、期间外异常、来源交易 signed net 核平与参考差异；永不自动认为银行已到账。
- [x] **代码已加入**：React 结算单创建、来源交易选择、差异预览及分配审计；不依赖真实卖家授权。
- [ ] 真正 Settlement/Disbursement ID 及 Amazon 报告数据导入、银行流水、退款/税费最终归类、正式利润报表仍待实现。

[结算参考及差异核对](finance/settlement-reconciliation-v30.md)


## V31 · Finances 结算文档来源引用（2026-10-31）

- [x] **代码已加入待 CI**：Finances v2024 交易 `SETTLEMENT_ID/DISBURSEMENT_ID/FINANCIAL_EVENT_GROUP_ID` 原始业务标识独立存储，来源与页提交原子幂等。
- [x] **代码已加入**：V30 同站点同原币参考账单按来源 ID 精确匹配优先提示，运营人工确认才分配，不自动声称结算/银行已到账。
- [x] **代码已加入**：Owner 的来源 ID 只读查询、synthetic 官方 SDK 引用测试。
- [ ] 真实 Amazon 报告/银行入账/Settlement 明细验真及来源覆盖率仍待实现。

[结算来源引用](finance/settlement-source-references-v31.md)


## V32 · 本地财务异常自动扫描（2026-10-31）

- [x] **已编写待 CI**：按来源交易、结算参考单及 LIVE 订单历史成本自动生成有界的异常待办项，不写入会计凭证。
- [x] **已编写待 CI**：末级 breakdown / 原币 / 未分类 / 原订单关联 / 结算差异 / 过期或未知历史成本的分级检查。
- [x] **已编写待 CI**：React 异常工作台按级别筛选、60 秒自动刷新本地 API，明确标注仅扫描部分近期数据。
- [ ] 审核处置工作流、永久增量扫描、结算及银行验真和正式贡献利润模型尚待开发。

[FIN-010 财务异常自动检查](finance/finance-exception-queue-v32.md)


## V33 · 已审核财务来源的订单经营贡献预览（2026-10-31）

- [x] **代码已编写待 CI**：受信 LIVE 来源交易人工订单关联 + V28 分类核平 + V29 历史单位成本形成有界原币贡献额预览。
- [x] **代码已编写**：退款、调整、未审订单关联、混币种和成本不齐时直接不输出金额；负费用按原始正负号相加。
- [x] **代码已编写**：订单财务页面展示来源分类/成本/贡献和质量原因，任何核平都不宣称已结算利润。
- [ ] 真实退货成本回转、广告费用、仓储和采购物流、税费、正式银行到账与利润冻结报表仍待开发。

[FIN-011 订单贡献额预览](finance/order-contribution-v33.md)


## V34 · 退款商品处置与成本回转证据链（2026-10-31）

- [x] **已编写，待 CI**：LIVE Finances 来源退款的人工订单项审核，跨多个退款交易数量不超过订单原订购数量。
- [x] **已编写，待 CI**：人工“已重新入库”/未入库/未知处置 + 审核证据参考号、追加审计、撤销，订单关联变更前必须先撤销商品审核。
- [x] **已编写，待 CI**：按历史 SKU 单位成本计算仅供核验的退款成本敏感度，不写入库存台账、不变更 V33 贡献额。
- [ ] 官方退货入仓、物流/退货损耗、真实采购批次成本回转、退款金额和商品分摊/正式利润仍待继续。

[财务退款商品审核说明](finance/refund-item-review-v34.md)


## V35 · 退款商品金额分配与签名差额（2026-10-31）

- [x] **代码已写入待 CI**：V28 `PRODUCT_REFUND` 受信来源金额按 V34 已核验数量的商品项进行有符号人工分配，保留来源币种/金额/差额。
- [x] **代码已写入待 CI**：超额、币种不一致、退款签名方向冲突、未审核商品审核状态拒绝保存，只有同店同源可编辑。
- [x] **代码已写入待 CI**：独立追加审计、前后端分配和撤销；V34 项撤销前必须先撤销 V35 金额分配。
- [ ] 实际入库/退货成本回转、正式 Amazon 报表与真实退款 API 验证仍待开发，V35 不产生库存或记账数据。

[退款金额分配说明](finance/refund-amount-allocation-v35.md)


## V36 · 广告费用参考来源与内部 SKU 分摊（2026-10-31）

- [x] **代码已加入待 CI**：人工未核验广告费用参考来源、稳定来源 key 幂等、广告活动/日期/原币支出事实，不伪造 Ads API。
- [x] **代码已加入待 CI**：Owner 审核 SKU 分摊基点，累计不超过 100%、显示未分摊费用、变更/撤销审计。
- [x] **代码已加入待 CI**：财务页面广告费用表与归属比例录入，纯 Java 分摊计算及边界测试。
- [ ] Ads API 独立 OAuth/报告、广告费用与 Finances 去重、SKU 广告归因及最终毛利/净利仍待开发。

[广告费用 SKU 分摊](finance/ad-spend-sku-attribution-v36.md)

## V37 · 补货建议转换本地采购草稿（2026-10-31）

- [x] 代码提交：Owner 人工从 FBA 补货建议生成本地采购 `DRAFT`，创建前验证最新观测/策略版本/ACTIVE 内部 SKU 映射、采购价和供应商；同租户 UUID 请求幂等与 V37 不可变来源快照，React 操作与来源历史。
- [x] 增加后端补货决策及拒绝测试，前端请求契约测试；**实际测试结论以 GitHub CI 为准**。
- [ ] 待完整验收：PostgreSQL 18 Flyway V37、并发幂等/隔离、跨租户 HTTP/CSRF、浏览器实际操作；后续预测算法、MOQ、仓库履约、官方 Shipment API 均未实现。
- **不自动采购**：只创建本地草稿，沿用 PO-001 提交和不同 Owner 审批，不使用 Amazon 写入 API。

## V39 · 本地采购收货、仓库入库流水及待收货（2026-10-31）

- [x] 代码提交：V39 本地仓库与追加式入库流水；采购收货明确指定仓库，仓库/收货/内部 SKU 复合租户约束与来源收货唯一键，收货+库存移动同事务。
- [x] React 采购页仓库创建、收货选择、已审批待收货、仓库累计采购入库和近期收货流水；后端/前端离线防护用例已加入。
- [ ] 真实 PostgreSQL 18 新迁移与并发/CSRF/浏览器联调的验收，以实际 CI/Compose 运行结果为准。
- [ ] 仍缺真实物流在途/ASN、出库扣减/仓间调拨/盘点、供应商 MOQ、仓储异常提醒，不能将本地累计入库视为 Amazon FBA 可售。
- 文档：[PO-002 本地仓库收货](purchasing/local-warehouse-receipt-v39.md)。

## V40 · 本地仓库出库、调拨与盘点（2026-10-31）

- [x] 编码 V40 迁移：新增可追溯操作实体，扩展 V39 采购收货流水以保存人工出库、调拨双向流水和实盘差异；同租户请求键、库存来源唯一约束与非零数量、数量方向检查。
- [x] 新增 Owner + Session/CSRF 库存操作 API、余额快照复核、仓库行锁、库存不足拒绝；React 采购仓库台账增加人工操作与历史明细；JUnit/Vitest 离线用例。
- [ ] PostgreSQL 18 V40 真实迁移、跨进程并发/反向调拨死锁验证、生产级仓储操作审计和完整 HTTP/浏览器业务验收仍需以实际测试记录为准。
- [ ] 未实现真实货件跟踪、仓库低库存异常告警、实物在途、质检预留、成本结算或 Amazon 写入。
- 参考：[PO-003 本地库存操作契约](purchasing/local-stock-operations-v40.md)。

## V41 · 本地仓储告警与保守采购参考（2026-10-31）

- [x] V41 迁移：仓库/SKU 补货阈值与目标库存、策略变更审计、持久异常队列、状态审计、人工/计划巡检记录。
- [x] 代码：从 V39/V40 本地流水形成库存建议，不把**无流水**变成 0；仅在有台账证据且触发本地低库存阈值时提供建议。全仓已审批待收货单列而不扣减或称作已发货在途。
- [x] 低库存、异常负台账、7 日内较大盘点差异识别；Owner 认领/标记处理/清除，React 仓库管理面板；可选本地计划巡检默认关闭。
- [x] 新增 Java 规则/隔离检查与 React 契约测试；实际 CI 结果须单独确认。
- [ ] PostgreSQL 18 V41 迁移、并行多实例巡检与跨租户 HTTP/CSRF、完整真实流程验收尚待完成。
- [ ] 仍缺 ASN / 供应商交期与 MOQ / 真实发货在途 / Amazon FBA 入库对账与用户通知渠道。
- 详见 [PO-004 本地仓储告警](purchasing/local-stock-alerts-v41.md)。
