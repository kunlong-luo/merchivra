# V181 — 结算报告行复合身份的不可变证据快照

状态：**V181 业务代码已合并，真实 PostgreSQL 18/Flyway 与精确 main SHA A3 首次 7/7 成功（2026-11-20）**。本版是可审计证据加强，**不是真实平台/银行收费认证，也不放行真实利润**。

## V181 精确 SHA 技术验收

业务 [PR #176](https://github.com/kunlong-luo/merchivra/pull/176) 已合并，精确 main SHA `51ced3db16e08ab70963ae22989f8ae7dc0efc98`；合并前 [CI #44729550558](https://github.com/kunlong-luo/merchivra/actions/runs/44729550558) **run_attempt=1 SUCCESS**（Java25/Gradle、React19/Vite/Next16、Compose、Chromium Next/Vite 新/旧行身份查询；PG18 在快 PR CI 按策略跳过）；合并后精确 SHA [完整 A3 #44729829959](https://github.com/kunlong-luo/merchivra/actions/runs/44729829959) **run_attempt=1、7 项核心任务 SUCCESS**（PG18/Flyway V102、Java、安全与恢复、React/Next16、Chromium、Compose HTTP；额外 PR 专属 Chromium job 在 main 按策略跳过）。[主线 CI #44729830152](https://github.com/kunlong-luo/merchivra/actions/runs/44729830152) SUCCESS；[GitHub Pages #44729829967](https://github.com/kunlong-luo/merchivra/actions/runs/44729829967) SUCCESS（只读虚构数据）。

验收精确覆盖 V102 Flyway 创建成功、Java 单元静态不可变性与租户控制路径、Next16/Vite 合成浏览器里新关联拥有 V102 来源身份、旧关联明确无历史创建时快照，及既有 Spring/PG18/HTTP 安全回归。**并未**用外部真实 Amazon、独立财务账单、银行或 WMS 证实元数据真实性，也未证明真实重复扣费或净利润。严格保持生产 #104 NO-GO、Seller/Ads OAuth OFF。

## 为什么必须做

此前 V94 已有“费用来源 → 结算报告 ID + 具体行号”的本地关联、独立第三人复核、单次不可逆的未核验结论及审计日志；V95 还有“一行被不同来源共用”的真实引用冲突提示与本地分摊拦截。因此绝不复制新一套账本/审批。但 V94 原始链接只冻结金额、币种与相似性，不固定导入 Amazon 报告原始文档标识、报告 SHA256、行级交易/金额类型与外部订单引用。在长期审计时缺少可再现的来源复合身份。

## V181 实际实现

1. PostgreSQL 18 Flyway `V102__settlement_line_composite_identity.sql` 创建 `finance_cost_settlement_line_identity_v102`，使用租户 + V94 link UUID 作不可变主键，并通过组合 FK 同时绑定原费用来源、站点、结算报告及真实行号。数据库触发器再按实际导入的报告行 `FOR SHARE` 锁定并逐字段比对来源报告 ID/文档 ID/SHA-256、行类型/描述、订单引用、有符号金额、原币、录入人。插入后 UPDATE/DELETE 一律禁止；首次冻结后相应报告元数据也不能改写（V95 已禁止已关联行变更）。
2. V94 `attach` 在同一个 Spring `@Transactional` 事务中，依次插入已有 V94 关联、新 V102 复合快照和原 V94 append-only 审计。缺任意一项、报告来源非 `IMPORTED/LIVE`、跨租户/站点或原字段变化都失败回滚，不能留下“只有链接没有证据”的**新**关联。
3. `GET /api/v1/finance/marketplaces/{marketplaceId}/cost-sources/{sourceId}/settlement-line-links/{linkId}/composite-identity` 使用已有 Spring Session + Owner 财务路由控制、服务器决定租户、`Cache-Control: no-store`，**只读**返回原始元数据；认证/重复扣费/银行确认/利润可用四个标志均为 `false`。
4. 既有 V94 关联不会为了过关而补造一个事后快照。它们返回 `CAPTURE_NOT_AVAILABLE`，具体原始文档字段为 `null`。V102 从此仅对新创建关联冻结事实；旧记录保留原 V94 审核/冲突历史。
5. Next.js 16/Vite 共用的 V162 全成本抽屉增加“结算报告行身份”查询、冻结身份字段与旧链接缺证提醒。前端通过带 CSRF Session cookie 的同源只读 API、Zod 契约、TanStack Query 和已有 Base UI 证据抽屉渲染，不新增 UI 依赖或真实 Amazon 写入。

## 风险、上线和测试

- **不等于** Amazon 提供独立签章：文档 SHA 来自已导入报告记录；即使报告声称 LIVE，也必须独立验证 API 授权与账单的真实归属。复合键、金额/币种相同不是“确定重复扣费”，不同也不必然互斥；独立复核只形成当地元数据结论。V95 冲突 hold 不能被这里绕开。
- Java 单元测试检查控制器的租户来自会话、404 范围外关联、禁止写与 V102 组合 FK/触发器/旧链接语义。PR CI 启用真实 Chrome 的 Next16/Vite 模拟账单新/旧身份下钻。合并后必须对业务精确 SHA 执行完整 Java25、PG18/Flyway、Compose HTTP、Chromium A3。
- Amazon Seller/Ads 真实授权仍保持 OFF，生产 #104 NO-GO。尚缺独立外部 WMS 实收、真实 Ads/Finances 账单身份对账、银行核实、退款原币 COGS 回转与完整成本来源覆盖。V162/V64 最终真实净利润继续不可计算。

下一阶段 V182：结合真实平台报告身份链对各费用来源进行行级“支持证据/冲突证据/未证明”归属裁决，要求独立复核和审计，**不**将本地复合标识当独立外部验真，更不可自动清除账务阻断。
