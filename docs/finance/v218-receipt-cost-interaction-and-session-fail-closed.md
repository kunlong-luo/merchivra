# V218 · 采购/财务收货单费用证据交互及旧值阻断

**2026-11-24 / Next.js 16 + React 19、无新 Flyway、无外部 API/OAuth/过账。**
沿用 [V216 收货费用原币/来源审计](v216-receipt-local-cost-integrity-read-gate.md) 和
[V217 采购/财务只读 UI](v217-receipt-cost-evidence-workbench.md)，
本阶段验证「用户必须明确选择来源」「旧结果不重新出现」「错误一律 fail closed」，
而不是设计第二套费用或成本凭证系统。

## 新发现与修复

V217 用 TanStack Query 存储 tenant + Marketplace + receipt 的已成功审计；
虽然界面折叠后不显示结果，但同一来源重新展开、手动重算期间，
同一 cache key 可能短暂展示上一轮成功候选原币金额。
这对**读时可能失效的财务证据**不够严格。

V218 把每次用户主动展开、提交或重新核对，变为
\`inspectionRevision\` 新版本 query key（同时保留 tenant/Marketplace/receipt），
这样再次核验期间绝不复用旧成本成功结果。
\`q.isPending || q.isFetching\` 时只显示等待文字，不显示旧金额。
HTTP 401/403/404/409 及一般失败只显示错误，不显示部分数值；
更换 tenant/Marketplace/receipt 清除选中状态与展开状态。
\`getReceiptCostIntegrityV217\` 透传 \`AbortSignal\` 至原生 fetch，
配合 [V144 会话 QueryClient 全业务缓存清理](../governance/engineering-standards.md)，
防止取消请求后继续显示前一会话凭证结果。
认证与授予权限仍只由服务端 \`TenantAccessService\` 判断；前端操作不代替权限验证。

## Chromium 真实界面场景（隔离合成）

扩展已有 \`scripts/smoke_browser_layout.mjs\` 的 **真实 Next App Router / Chromium 360px**
场景，复用已有 CDP/Node24、受控 Next 本地 HTTP 代理、Base UI
和 V106/V180 原有断言，不引入 Playwright/新依赖或平行 mock runner：

1. 采购页选中一张真实列表形式的**合成** V107 采购单，读取
   \`/api/v1/purchasing/receipt-batches/orders/{id}/page\` 返回的合成
   V46 \`receiptId\`；选择“核对本收货单的费用来源”、待明确按需展开后
   才出现首个 V216 费用审计 GET。测试截图不包含真实用户、供应商或仓库数据。
2. 财务页的完整 UUID 先输入无效文本，确保不请求；再输入准确 receipt UUID，
   **未提交时不请求**，用户点击后出现 \`USD 40.000000\` 的
   「本地候选原币金额」及真实 COGS/净利润未核证提醒。
3. 同一数据成功后依次模拟 **409、403、404、401**：每次点击重新核对都需要
   一次新 GET；显示具体错误，上一轮 \`USD 40.000000\` 必须从 DOM 完全消失，
   不能借由缓存或局部分页继续渲染。错误恢复成功必须重新向后端读取。
4. 同一用户切换 Base UI Marketplace：本地原收货 UUID 选择被清空，
   **不主动请求**，前一个站点原币金额不得出现；
   再明确提交，在另一站点得到 0 条本地来源时显示
   「成本证据未知，不代表真实费用为零」。跨范围绝不合并金额。
5. 所有 V218 来源审计请求必须为 **GET**；保留现有真实 Chromium
   浏览器布局/Finance 分页/账户来源/React/Next/Vite 回退检查及合成截图证据。

**覆盖范围说明**：Chromium 里 401/403/404/409 是**合成 localhost API**
按请求返回的受控 HTTP 状态，测的是按钮、React Query、Zod 和错误 UI。
它**不是**通过真实登录多角色模拟来证明 Spring Security 的 403。
真实 PG18 A3 仍独立执行 V216 Owner 匿名 GET 401 及租户/站点
SQL 隔离；未来应加真实 HTTP Owner/非 Owner/跨租户负例，不得将合成 UI
状态当作后端多角色权限验证。

## 验收、阻断及后续

本轮以精确代码提交、pnpm/Vitest、Next16 TypeScript/build、
真实 Chromium Next 路由截图与模拟状态断言、PG18/Compose A3 的
真实 GitHub job 日志为准。任何测试失败必须修复再回填，
**不允许借用 V217 A3 或把 PG18 SKIPPED 当作通过**。

V218 不新增会计写入、成本分配审核或真实平台数据核证；
没有真实承运、关税/汇兑/采购发票、Amazon/Ads、银行和原退货物理仓库来源，
\`actualBatchCogs=null\`、\`realizedNetProfit=null\`，不允许实际 COGS 或利润过账。
G1/DEC-06/07/08 尚未批准。下一阶段 V219 应转向**后端独立真实 HTTP
Owner 与跨租户/跨 Marketplace 负例**和原批次证据约束，
而非更多展示卡。

项目评分 **70/100**；真实 Seller/Ads OAuth OFF；
生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**。
