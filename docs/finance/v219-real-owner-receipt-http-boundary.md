# V219 · 收货费用 Owner 实际 HTTP / 跨租户与站点权限验证

**2026-11-24 · 对既有 V216 GET 增加真实 Java25/Spring Session/PostgreSQL18 角色与租户 HTTP 验证；不新增财务 API、Flyway、业务表或外部授权。**

V218 已通过 Next16/Chromium 合成界面的 401/403/404/409 与旧值隔离，但**浏览器本地 mock 返回的 403 不等于真实 Spring Security 的非 Owner 身份验证**。V219 的优先级是填补真实进程安全证据缺口，避免将前端错误展示误写成后端授权已经验收。复用 TenantAccessService.currentTenant、ReadOnlyStaffGuardFilter、V216 50 计划/双 RR 快照及 V215 V112 原币分配独立核对，不另建第二套系统。

## 新增实际测试链路

在**真正运行的 Spring Boot + Flyway V112 + PostgreSQL18** 上执行：

- scripts/smoke_receipt_cost_real_http_v219.py：由完整 A3 的 .github/workflows/ci.yml PG18 作业执行。强制 MERCHIVRA_V219_DISPOSABLE_ONLY=true，校验 CI 固定 localhost 数据库地址/端口/名称/用户/密码、临时 Bootstrap 用户和密码、Amazon Seller/Ads 开关 OFF；必须是 PG18/V112 且**仅一个初始 Bootstrap 租户/账号**。否则在写入任何测试记录前停止。绝不允许用该脚本连接真实数据库。
- 在 CI **独立一次性数据库中提交**两套**合成** local seller_account/Marketplace、供应商、SKU、采购单/行/receipt 和一个非 Owner 用户；所有行具有真实 FK，不提交任何 V112/V91/V92 费用金额或外部凭证。容器销毁时数据消失。OPERATOR_READ 用户在租户 A 限定卖家账号，同时在租户 B 有 OWNER，用于验证**同一身份拥有某处 Owner 不代表任一租户都可 Owner 操作**。合成账号复用 CI 临时 Bootstrap 的单向密码 hash。
- 使用独立的真实 CookieJar/JSESSIONID 和 CSRF，在真实 /auth/login 分别登录；用 /auth/me 验证当前租户与 Owner 属性，用带 CSRF 的 /auth/active-tenant POST 测试切换（**不是**新增财务写入）。
- **Owner A**：本租户 Marketplace A1 + receipt A 返回 200、no-store 和 UNKNOWN≠0；另一同租户 Marketplace A2 + 同一 receipt A 返回 200 UNKNOWN，证明 V20 receipt 不绑定单一 Marketplace；附带 ?tenantId=B 仍按服务器会话返回 A；B Marketplace/receipt、A Marketplace+B receipt、虚构 receipt 均 404；切换 B 被真实 HTTP **403** 拒绝，A 当前租户不变。
- **已登录 OPERATOR_READ A / OWNER B 的同一用户**：选择 A 后 /auth/me.isOwner=false，所有费用 GET 均由真实后端 **403**；选择 B 后 isOwner=true，B Marketplace+B receipt 为 200 UNKNOWN，A Marketplace+receipt 为 **404**；切回 A 仍 403。禁止把其他租户的 Owner 权限混用到当前只读员工租户。
- 独立匿名 CookieJar 始终 **401**；Owner A logout 后自己的 GET 401、B 的另一 Session 仍 200；B logout 后 GET 401。测试过程仅操作一次性隔离 PostgreSQL 和 localhost，绝不出站到 Amazon/Ads/物流/银行。
- 所有 200 返回核对 marketplaceId/purchaseReceiptId、localPlanCount=0、NO_LOCAL_RECEIPT_COST_PROPOSAL_UNKNOWN_NOT_ZERO、actualBatchCogs=null、realizedNetProfit=null、所有外部已核证/过账标志 false，以及 Cache-Control: no-store。**合法权限和零本地计划不等于真实成本为零**。

原前端 apps/web/src/lib/receipt-cost-integrity-v217.test.ts 补 TanStack Query Session 清理过程中仍在进行的 V216 fetch AbortSignal 取消与缓存清除正反例。继续复用 V144 全业务缓存清理，不新增第二套请求/缓存层。

## 真实与模拟边界

- V219 新增的 401/403/404/200 来自**真实 Spring Controller/FilterChain + 两个登录 Session + PG18 的租户/角色/receipt**，不是 Chromium mock 伪造状态。
- 本轮没有验证 V112 **非空** 1–50 份原币费用计划的真实 HTTP 成功响应、50+ 上限 409、真实并发 ABA、各类外部单据真实性。这些仍由 V215/V216 已声明范围的 Java/PG18 TEMP 查询与前端 Zod 部分覆盖，不能把其测试范围扩大为实际成本已核验。
- 真正净利润仍需独立核对 Amazon Finances/Settlement/Ads、货代/海关/银行以及原批次物理凭证，并由 G1/DEC-06/07/08 决定资本化、汇率、跨期、退款回转。生产上线还必须独立签署。
- 测试不连生产、不打开真实 Seller/Ads OAuth、不新增财务写接口或费用提案，真实 COGS 和利润继续 null。

## CI 调度修复记录

首次 V219 提交后，GitHub Actions 在当前工作流上出现 **completed/failure 但 0 jobs**，表示业务/权限 SQL 根本未运行，不能标为测试失败也不能标为验收成功。为恢复可验证性，将 CI YAML 中 **7 条 Flyway 版本检查 + 9 条 PostgreSQL index 检查 + 1 条 constraint 检查** 归并为显式列表循环，仍对每个原有版本、索引和约束逐一判定 count=1，并保留全部其他验收任务。此变更只压缩工作流重复文本，不放宽或跳过检查。重跑新的精确 SHA Job 后才能判断 V219 实际 HTTP 成功与否。

## 验收依据与后续

必须记录**精确 V219 代码 SHA**、pnpm/Vitest/Next16 快 CI，以及包含 PostgreSQL18、真实 Spring Session 权限脚本、Chromium、Compose 的完整 A3 7/7；PG18 原始日志须出现 PASS V219 real Spring/PG18 HTTP。任何真实 SQL 或权限失败须修复，不允许用 V218 绿色冒充 V219 验收。

V220 优先推进实际 V112 **非空** 人工费用提案 Owner GET、跨来源碰撞和 50+ 上限的真实 HTTP/409 验收，进一步检查同会话突变和双 RR 快照，但人工提案始终不能转成真实 landed cost。先解决外部证据和 G1 政策，不堆孤立财务卡片。

**评分 70/100**，realizedNetProfit=null，真实 Amazon Seller/Ads OAuth OFF，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。
