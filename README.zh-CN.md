# Merchivra ERP（麦奇维拉）

**面向跨境电商卖家的开源 ERP，第一阶段专注 Amazon Seller。**

[English](README.md) · [MVP 需求](docs/requirements/mvp.md) · [技术架构](docs/architecture/overview.md) · [路线图](docs/roadmap.md) · [参与贡献](CONTRIBUTING.md)

> **当前状态：Pre-alpha / 登录后的核心业务只读工作台 + Mock 演示。** 已有服务端 Session、首个管理员初始化、**本地未连接店铺/站点配置和操作审计**，以及按当前登录租户过滤的 Marketplace、Listing、订单、FBA 只读查询；新租户通常没有真实店铺数据。原来的经营总览与模拟同步仍只使用虚构数据。完整角色权限、真实 Amazon 接入与财务功能尚未完成，禁止生产使用。

## 产品愿景

帮助卖家在自有基础设施统一管理店铺、商品、订单、FBA 库存与利润。坚持私有部署、数据可追溯、模块化设计、开放的平台适配器，以及可重复的自动化测试。

## 技术栈

- 后端：**Java 25 LTS / Spring Boot 4.1.2 / Spring Security 7 / Jackson 3 / Maven**
- 前端：**Node.js 24 LTS / React 19.3 / Vite 8.3.5 / TypeScript 6.0.3 / Vitest 5**（npm lockfile 已提交）
- 数据库：PostgreSQL；缓存预留 Redis
- 架构：模块化单体，后续根据业务量拆分 Worker
- 部署：Docker Compose；CI：GitHub Actions（`npm ci` + Maven 快速单测，不运行 E2E）

升级细节与尚未运行的容器验证见 [2026 技术栈升级记录](docs/architecture/stack-upgrade-2026-10-30.md)。

## 快速启动（仅本地模拟演示）

```bash
cp .env.example .env
# 修改 .env 中的 MERCHIVRA_DB_PASSWORD 为强密码
# 再填写 MERCHIVRA_BOOTSTRAP_USERNAME 和 MERCHIVRA_BOOTSTRAP_PASSWORD（自设至少 16 位）
docker compose --env-file .env -f deploy/compose.yaml up --build
```

- 前端：http://localhost:3000
- API：http://localhost:8080/api/v1/ping
- Mock 业务概览：http://localhost:8080/api/v1/demo/overview （需显式启用 Mock；Compose 已在本地开启）
- 健康检查：http://localhost:8080/actuator/health

**已实现首个管理员 Session 登录、最小租户隔离和本地配置审计；[PostgreSQL V1–V4 一次性冒烟](https://github.com/kunlong-luo/merchivra/actions/runs/41059134533) 已通过，但尚未验收整套 Docker Compose、完整角色授权及生产安全。端口仅监听 127.0.0.1，不得直接部署到公网。**

更多命令见 [开发指南](docs/development.md)、[受保护核心只读 API](docs/api/core-read-api.md)、[本地店铺配置](docs/api/local-seller-setup.md)、[Mock 演示](docs/demo.md)、[本地登录指南](docs/security/local-auth.md) 以及 [Agent 策略](Agent.md)。

## 开发路线

1. M0：需求、架构、工程骨架、Docker、CI。
2. M1：账户与 SP-API 授权、凭证安全、Mock 适配器、同步任务机制。
3. M2：Listing、订单与 FBA 库存数据同步。
4. M3：商品历史成本、财务流水、预估利润与对账。
5. M4：权限隔离、安全审计、错误恢复、Beta 试用。

详情见 [MVP 需求文档](docs/requirements/mvp.md) 和 [路线图](docs/roadmap.md)。

## 开源协议

[Apache License 2.0](LICENSE)。欢迎提 Issue 和 PR。请勿提交 Amazon 密钥、客户个人信息或卖家真实报表。

## 纯虚构数据全链路模拟

已新增登录后“核心业务数据”页的手动 Sandbox 回放：为本地未连接的 US/DE 站点模拟分批 Orders、Listing 和 FBA 来源，并在结构化表明确写入 `data_origin=MOCK` 后通过受保护的业务 API 查询。财务始终 `NOT_AUTHORIZED`；这**不是**真实 Amazon 账号授权或 Finances 入账。操作与实际联调脚本参见 [TEST-004](docs/testing/mock-full-chain.md)。

**实际验证：** [Compose 全链路成功运行（2026-10-30）](https://github.com/kunlong-luo/merchivra/actions/runs/41061679196) 已实际构建并运行 Compose 的 Nginx、Spring Boot、PostgreSQL，模拟 US/DE 的订单/Listing/FBA 同步，检查幂等、审计与服务器重启后数据仍在。**但仍不是**真实 Amazon 授权、Finances 对账或生产上线验证。

开发期新增 **V6 模拟订单逐页提交**：可在核心工作台查看已完成页数、手动推进一页、通过 PostgreSQL 持久化检查点与幂等键验证重启后恢复。它不是真实后台同步 Worker、Amazon 分页或已授权来源。详见 [TEST-004](docs/testing/mock-full-chain.md)。

## Amazon 官方 SDK 接入代码（默认关闭）

后端已引入 Amazon **官方 Java SP-API SDK 1.13.0**，包含多个模块的生成 API 类型；新增仅供受授权服务端调用的 LWA/区域 Client、Orders/Listing/FBA/Finances/Reports/Feeds 等读取封装，以及测试中使用的纯虚构 Token 和响应结构。**已有默认关闭的 OAuth/加密 Vault 代码，但尚无真实 Amazon 网络验收、完整角色授权或生产 Worker，默认不开启出站**。模块与差距详见 [INT-004](docs/integrations/sp-api-code-integration.md)。

**Seller OAuth 下一层代码：** 已增加 V7、Amazon 公有应用的两阶段授权回调、一次性 State、租户绑定 AES-256-GCM 凭证 Vault、本地撤销和审计。授权回调后仅 `PENDING_VERIFICATION`，没有接入真实账户或完成生产级核验；默认禁用。见 [SEC-004](docs/security/seller-oauth.md)。

**V8/V9 进一步实现（仍默认关闭）：** V8 增加 Amazon 官方 Sellers v1 Marketplace 参与状态核验与 Worker Vault 24 小时站点证据门禁；V9 增加版本化 AES-256-GCM 密钥环、Owner+CSRF 按店铺原子重加密和审计。**未做真实 Amazon Seller 账号验证、角色证明、KMS Envelope Encryption 或真实订单/库存 Worker。**本地加密密钥轮换不等于 Amazon Refresh Token 续期。见 [SEC-005](docs/security/seller-verification.md) / [SEC-006](docs/security/seller-key-rotation.md)。


## V10 运营自动化工作台

- [竞品能力矩阵与路线图](docs/product/competitor-gap-and-automation.md)：对照 Sellercloud、Linnworks 和 Helium 10 的订单自动化、库存风险、采购与财务模块，标记已完成、待集成和高风险写入能力。
- [OPS-001](docs/operations/ops-automation.md)：提供只读事实上的异常识别、持久待办、认领完成、审计与显式开启的定期巡检；默认无需调用真实 Amazon。建议先在本地模拟导入数据，然后通过“运营自动化”工作台手动执行巡检。
- **没有**实现物流发货、广告自动调价、正式补货单/采购审批、财务对账或正式 Amazon Orders Worker；功能对标不是宣称生产连接已全部完成。

## PostgreSQL 18 数据卷升级提示（2026-10-31）

Compose 使用 `postgres:18-alpine`，新卷 `pgdata18:/var/lib/postgresql`。此前 17 的 `pgdata` **不自动升级、不自动删除**。已有数据须先按 [PostgreSQL 17→18 迁移指南](docs/deployment/postgresql-18-upgrade.md) 备份和恢复；不能将 17 的数据文件直接挂载到 18 或执行 `down -v` 处理生产数据。


## PostgreSQL 18 / V11B 验证（2026-10-31）

[PG18 + V1–V12 Compose](https://github.com/kunlong-luo/merchivra/actions/runs/41154500844) 与 [Java/Vue CI](https://github.com/kunlong-luo/merchivra/actions/runs/41154501014) 已通过。V11B 已加入 Orders 角色审核证据结构和仅服务端的只读网关，**并未开放真实 Amazon Orders 同步**；原 PostgreSQL 17 数据卷需要另外迁移。详见 [V11B 文档](docs/integrations/orders-worker-v11.md)。


## V11C LIVE Orders 只读 Worker 安全闸（2026-10-31）

- V11C `OrdersSyncConfiguration` 显式选择 `MOCK`（默认）或 `LIVE`（需要独立批准 `MERCHIVRA_ORDERS_SYNC_LIVE_APPROVED=true`）；LIVE 要求正式 OAuth、真实 Seller Verification、V12 Orders 角色证据、生产 SP-API 只读，Demo/Sandbox/远端写入必须关闭。默认不触发任何生产出站。
- `OrdersSyncService` 现在把任务 mode 与记录 `data_origin` 绑定，禁止既有 MOCK 任务继承 LIVE 任务游标；提交 LIVE 订单/页日志/安全水位时重新核验并事务锁住账户、授权、角色、参与证据，授权撤销后事务回滚且转暂停。
- Flyway V13 放开 ops_finding 只读建议记录的 LIVE 来源分类，Web 必须区分 Amazon API 已同步/模拟/未核验；LIVE 绝不代表已记账或 Amazon 写入批准。
- 所有真实角色审核、真实 LWA、网络 429/Token TTL 与生产部署仍需独立实测。项目 CI 必须保留 Mock-only 全链路及默认角色表为空的断言。详情 `docs/integrations/orders-worker-v11.md`。


## V15 多管理员团队与 SECURITY_REVIEWER 授权（2026-10-31）

- 新增 `local_owner_invitation` + `local_team_audit`（Flyway V15）。默认 `MERCHIVRA_TEAM_ENABLED=false`；开启后当前 OWNER 生成 256-bit 24h 单次邀请令牌（SQL 只存 SHA256），受邀用户在匿名但 CSRF 防护的 `/api/v1/auth/invitations/accept` 自设 16+ 位 BCrypt 密码，获得同租户 OWNER 权限。只适用于**另一位全权限管理员**，不是普通员工 RBAC。
- `LocalTeamService` 通过数据库事务、租户行锁和当前 OWNER Session 控制邀请、撤销、审核员角色授权。OWNER 不能给自己授予 `SECURITY_REVIEWER`，移除成员立即失去租户访问且不能移除最后一个 OWNER。所有变更写 `local_team_audit`，原始 token 不入库、不记日志、不在列表查询返回。
- 独立的有效 OWNER+SECURITY_REVIEWER 才可执行 V14 Orders 应用角色审核。V12 Vault 与 V14 审核增加 Reviewer OWNER 角色要求。团队页面在前端单独展示，未启用时清晰提示；激活入口位于登录页。尚无员工细粒度权限、OIDC/MFA/邮件、正式 Amazon LIVE 验收。
- [V15 说明与安全边界](docs/security/team-v15.md)。CI 包括 PostgreSQL 18 HTTP 双管理员邀请激活、身份/CSRF、审核员授予/撤销、成员撤销旧会话立刻失效，未经实际运行不得宣称通过。


## V15 实际验收证据（2026-10-31）

- [Java 25 / Vue TypeScript / Vitest / Vite CI 41159636629](https://github.com/kunlong-luo/merchivra/actions/runs/41159636629) **成功**。
- [PostgreSQL 18 + Java25 + Nginx Compose 41159526524](https://github.com/kunlong-luo/merchivra/actions/runs/41159526524) **成功**，执行 Flyway V1–V15，继续验证此前业务 Mock、订单双页 checkpoint、后端真实容器重启；包含 V15 一次性邀请、匿名/CSRF 拒绝、令牌重放失败、另一位 OWNER 独立账号登录、自授权 reviewer 拒绝、授予/撤销 reviewer、撤销 OWNER 后已登录旧 Session 拒绝访问、仍保留有效 OWNER。日志有 `PASS V15: PG18 2-Owner invitation ... membership immediate revocation`。
- [Chrome 360px responsive UI 41159636546](https://github.com/kunlong-luo/merchivra/actions/runs/41159636546) **成功**，合成双管理员数据验证「团队与权限」成员表、Reviewer 标记、邀请入口和移动端布局，并保留此前 Mock/LIVE 订单面板的响应式检查。
- 上述测试**不代表**生产安全审计、邮件邀请、MFA、SSO、员工/财务分权或 Amazon 正式角色审批；团队管理需显式开启，LIVE SP-API 仍默认关闭。

## V16 店铺级只读运营员工（2026-10-31）

- Flyway V16 为 V15 邀请增加 `invite_role` 与租户约束的 `seller_account_id`；旧邀请默认 OWNER 不变。`OPERATOR_READ` 必须绑定 Seller Account，禁止全租户该角色、非法或跨租户账号、未审核权限自动升级。Owner 邀请的员工激活后仅获得指定账号的店铺级只读角色，而不是 OWNER。
- 原有敏感控制器的 `TenantAccessService.currentTenant` 继续**只允许 OWNER**；新增 `currentReadTenant/readableTenants` 仅用于身份/核心只读业务端点。核心 Marketplace 列表、Listing、Orders、FBA 每个请求重新联查数据库当前 membership、用户状态、role_binding 与 Seller Account；未知及跨账号 ID 均返回同样 404。所有授权记录以服务器 Session userId 为准。
- `ReadOnlyStaffGuardFilter` 在业务控制器之外额外阻止员工读取旧 Demo 非授权接口、执行 Mock 同步、运营规则、Amazon OAuth、账户配置或任何其他非白名单 API。唯一放行额外工作区 API 是 GET `/api/v1/seller-marketplaces/**`，具体订单/库存查询仍二次校验角色；旧敏感管理员 API 未因员工登录而开放。
- 前端角色信息来自 `/api/v1/auth/me.isOwner`；员工只看「核心业务数据」，不显示团队、运营自动化、经营演示、Seller Setup 和同步按钮。隐藏 UI 不是安全边界。团队页面可以选择邀请 OWNER 或 `OPERATOR_READ`，后者要求选定 Seller Account。
- 这只是 **V16 第一层员工权限（店铺级只读运营）**。尚不支持采购写、财务阅览、普通运营修改、单独 Marketplace 级别授权、OIDC/MFA 和批量授权矩阵。真实 Amazon LIVE 出站依旧默认关闭。
- CI 将使用 PostgreSQL 18：员工一次性邀请→登录→只看到一个 Seller Account 所属 Marketplace→读取其订单/Listing/FBA→其他 Seller Account 所属站点 404→团队/运营/账户等管理员 API 403→Owner 撤销后旧 Session 无法读取。此处描述验收目标，必须以成功 workflow 为准。


## V16 已完成的实际验证（2026-10-31）

- [Java 25 Maven + Vue / TypeScript / Vitest / Vite CI 41162402846](https://github.com/kunlong-luo/merchivra/actions/runs/41162402846) **成功**，包含路径白名单、当前租户 Owner 校验及跨租户管理员不能借权操作的负例。
- [PostgreSQL 18 + Nginx + Spring Boot Compose 41162402904](https://github.com/kunlong-luo/merchivra/actions/runs/41162402904) **成功**：Flyway V1–V16，V15 邀请和 V16 员工跨店铺授权、账号激活、Session/CSRF、访问者越权 403/404、管理员撤销后权限立即失效；真实重启 Spring Boot 后 V11A 模拟订单页 2 恢复且无重复写入。日志：`PASS V16: staff login, scoped Marketplace + orders/listings/FBA, foreign 404, no OWNER routes, immediate revoke`。
- [Chromium 360px 员工界面验收 41162279336](https://github.com/kunlong-luo/merchivra/actions/runs/41162279336) **成功**；假设员工具有受限角色，验证仅有核心数据入口，团队/模拟导入/同步面板不展示；页面截图存放于 workflow artifacts。
- 本轮还有显式安全收紧：服务端仅放行精确已审核的 Marketplace GET 读路径，OWNER 权限必须属于**当前 Session 所选租户**，而不是其他租户中的 OWNER 权限。生产真实 Amazon API 出站默认关闭。
- **已完成的是初级 OPERATOR_READ 角色，不是全量员工 RBAC**。运营写入、采购、财务、SSO/MFA、外部生产安全审核均未完成，不能将模拟测试结果称作生产权限认证。

## 前端技术栈迁移：React-only（2026-10-31）

项目维护者已确定弃用 Vue 系列。当前前端使用 **React 19.3 + React DOM + TypeScript 6 + Vite 8 + TanStack React Query + Zod**，所有 UI 入口和业务组件迁移到 TSX，不再保留 Vue 运行依赖与 SFC；Java25/Spring Boot4.1.2/PostgreSQL18/Maven 保持不变。详情：[React 迁移决策](docs/architecture/react-migration-2026-10-31.md)。历史文档中提到 Vue 的旧 CI 结果仅代表迁移前，不是当前技术栈。


## V19 开发新增（尚未集中验收）

- 私有 Seller 应用 Seller Central 自授权的 Refresh Token **服务端一次性加密导入**：不从浏览器提交秘密，导入后仍须验证 Seller/Marketplace；详见 [INT-005](docs/integrations/private-seller-import.md)。
- 内部 SKU 新增/改名/归档/恢复、Listing 人工绑定/解绑及历史审计，React 业务界面；详见 [CAT-001](docs/catalog/sku-management.md)。
- `deploy/compose.production.yaml` 仅为独立非 Demo 部署覆盖层的**代码配置**，不自带公网 TLS、备份、Secrets Manager 或生产验收。
- 根据维护者新指示，这批功能只提交代码，暂不运行测试或真实 Amazon 连接；**不应据此宣称已经正式可用**。


## V19/V20 商品成本与采购代码进展（尚未验收）

新增历史成本版本（原币、有效区间）、供应商、采购草稿、跨 Owner 审批、分批收货和相应 React 页面。**不意味着实现了 Amazon 财务对账、真实入仓或完整采购系统。**
见 [成本历史](docs/finance/sku-cost-history.md) 与 [采购单](docs/purchasing/local-purchase-workflow.md)。


## V21 财务来源事件开发（代码已提交，未验收）

新增 `财务与对账` React 入口：按来源/原币/类别展示来源事实，允许 Owner 人工录入**未核验**事件和关联同站点订单，保存分类与匹配决策的审计。**不是**真实 Amazon Finances 同步、平台已结算或可信利润计算。
详见 [FIN-005](docs/finance/source-events-v21.md)。


## V22 库存补货建议代码（未验收）

新增 FBA 页内按 Seller SKU 配置的补货阈值、目标库存、提前期以及计算建议。**数据质量不可信时返回「不可计算」而不是把未知库存当作 0；不会自动采购或修改 Amazon。**详见 [INV-004](docs/inventory/replenishment-policy-v22.md)。


## V23 Orders 商品明细同步代码（待真实 Amazon 验收）

接入官方 Orders v2026 `searchOrders` 的非 PII 商品项、数量及可选 ITEM 小计字段；增量 Worker 在每页事务中持久化当前明细并保留历史，React Core 订单支持展开查看、明确区分订单项未知与已观测。无真实订单同步验收，不等于财务结算或净利润。见 [ORD-005](docs/integrations/orders-items-v23.md)。


### V24 真实 Listing / FBA 只读同步代码

Core 工作台新增官方 SDK Listing/FBA 页面分页同步，包含授权/角色复核、持久化 checkpoint、租约、重试和来源完整度。默认关闭，非真实卖家联调完成；不是自动上架、库存写回或自动下单。见 [V24 说明](docs/integrations/catalog-fba-live-sync-v24.md)。


管理员建立 Listing/FBA 扫描后，可选开启 `MERCHIVRA_CATALOG_SYNC_SCHEDULER_ENABLED=true` 自动逐页读取与重试，默认关闭。该调度器不会自动创建新扫描或执行 Amazon 写入。


### V25/V26 Finances 只读业务链路

增加 Finances 独立两人安全审批、`listTransactions` 官方 SDK 交易只读 Worker、稳定去重/原币未分类入库、原子分页 checkpoint、重试及可选调度，工作台能查看状态并逐页推进。所有真实功能**默认关闭**，不宣称真实 Amazon 已联调或费用/利润/结算已完整分类。详见 [FIN-006](docs/finance/finances-live-v26.md)。


V27 增加 Finances 原始 breakdown 父子层级保存与查阅，防止把已含子项的父级金额重复计入收入或费用。原始类型仍属于待核对来源，并非已分类会计数据。


### V28 Finances 只读人工分类及完整度核对

财务页面可对官方来源的末级明细类型审批本地分类规则，记录审计、检查分类覆盖率并核对原始有符号总额。未核对一致、混币种或来源不完整时**不显示分类汇总**；更不会把该结果当作已结算利润。见 [FIN-007](docs/finance/finance-classification-v28.md)。


### V29 · 订单历史成本覆盖率

Orders 读取增加真实创建时间，订单项带来源标记；财务页面可查看订单当地业务日的内部 SKU 历史成本匹配情况。仅在所有 LIVE 订单项齐全、映射有效、金额币种一致时显示暂定 COGS；不是利润或结算。详见 [V29](docs/finance/order-cost-readiness-v29.md)。


### V30 · 本地结算参考与财务来源对账

财务工作台可创建人工结算参考单，显式选择同站点同原币的 SP-API 来源交易，检查有符号金额差异并保留分配审计。**金额匹配仍然不是银行到账验证**，不要求现在进行真实 Seller 授权。见 [V30](docs/finance/settlement-reconciliation-v30.md)。


### V31 · 结算来源编号辅助核对

官方 Finances typed 读取层保存部分可核对的结算业务标识，结算工作台对人工账单编号相同的来源交易标记“精确匹配建议”。这不是自动到账确认，也不会调用 Amazon 写接口；本地真实授权留到功能完成后。详见 [V31](docs/finance/settlement-source-references-v31.md)。


### V32 · 财务异常自动核对待办

财务工作台新增只读的自动异常队列：对近期来源明细、订单人工匹配、结算差额及历史 SKU 成本覆盖进行分级检查，提示下一步操作。按需调用本地 API，不主动请求 Amazon。详见 [V32](docs/finance/finance-exception-queue-v32.md)。


### V33 · 订单已审核财务来源的经营贡献预览

在财务工作台选择订单后，可查看已审核关联 Finances 来源的分类收入、平台费用、FBA 费用与有效期 SKU 商品成本构成的**有条件经营贡献余额**。退款、调整、混币种、来源遗漏或历史成本未核验时拒绝显示误导金额。此数字不等于最终净利润，真实 Amazon 验收仍在未来由使用者本地完成。详见 [V33](docs/finance/order-contribution-v33.md)。


### V34 · 退款商品处置审核

财务工作台新增退款类来源事件的逐订单项人工审核：数量、处置、证据编号与撤销历史；阻断跨多个退款事件超数量和已审核来源变更订单。人工“已入库”只能产生历史成本敏感度参考值，不自动回冲 COGS 或库存、不虚构最终利润。见 [V34](docs/finance/refund-item-review-v34.md)。
