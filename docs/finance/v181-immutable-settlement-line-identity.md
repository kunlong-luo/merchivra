# V181 — 结算报告行复合身份的不可变证据快照

状态：**V181 业务代码已合并，真实 PostgreSQL 18/Flyway 与精确 main SHA A3 首次 7/7 成功（2026-11-20）**。本版是可审计证据加强，**不是真实平台/银行收费认证，也不放行真实利润**。

## V181B 补充指纹验收（2026-11-21）

**V181B 结算指纹补强已验收（2026-11-21）**：业务 [PR #178](https://github.com/kunlong-luo/merchivra/pull/178) 已合并，精确业务 main SHA `202f5fdf73ccea8e0a44a0bbc725672ae6170432`；合并前 [CI #44859006834](https://github.com/kunlong-luo/merchivra/actions/runs/44859006834) **SUCCESS**（Java25/Gradle、React19/Next16/Vite、Compose）；该 SHA 上 [完整 A3 #44859153160](https://github.com/kunlong-luo/merchivra/actions/runs/44859153160) **run_attempt=1，7/7 核心任务 SUCCESS**（PG18/Flyway、Java、React/Next、Chromium、Compose HTTP/安全与恢复；独立 Next 设计专项按策略跳过）。V102 不可变快照可规范化按租户/站点/费用来源复算本地 SHA-256，`fingerprintOrigin=DERIVED_ON_READ_FROM_FROZEN_V102`，**不是创建时保存的指纹或 Amazon 签章**。旧 V94 缺证返回 null；V94 独立复核、V95 共用行 hold 均不变；Seller/Ads OAuth OFF、#104 NO-GO、真实净利润未知。

完整规范见本文后部的 V181B 指纹规范。此补充不修改已验收的 V102 Flyway、现有财务入账门禁和外部身份认证边界。

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

后续归属裁决见 [V182 专项证据](v182-settlement-attribution-evidence.md)；现行任务只看 [P0 计划](../roadmap/current-plan.md)。

## V181B 指纹规范（原独立 V1 文档归并）

### 范围与信任边界

- **不新增** Flyway 表、结算行关联、审批、财务过账，也不重构 V94/V95 的独立复核或共用行 hold。仅对已冻结的 V102 元数据按固定规则提供本地 SHA-256 复算结果。
- 在原 `GET .../settlement-line-links/{linkId}/composite-identity` 响应中附带 `fingerprintVersion`、`fingerprintSha256`、`fingerprintOrigin`。原身份字段以及全部 `externalChargeVerified=false`、`duplicateDebitVerified=false`、`bankVerified=false`、`netProfitEligible=false` 不改变。
- 冻结的 V102 行返回 `MERCHIVRA_SETTLEMENT_LINE_IDENTITY_V1`、64 位小写 SHA-256 和 `DERIVED_ON_READ_FROM_FROZEN_V102`。**这是从已冻结快照在读取时派生的指纹，绝非“创建时已保存的指纹”或独立外部签章**。对在新逻辑推出前已经保存的 V102 行，同样可以复算，不伪装成曾保存该摘要。
- V94 历史行若未在创建时捕获 V102 快照，则仍返回 `CAPTURE_NOT_AVAILABLE`，上述三个新增字段均为 `null`。禁止从今天的原报告元数据对这些旧关联“补算”成原始创建时证据。
- 此摘要既不能独立验证文档 SHA-256 与外部 Amazon 原始文档内容一致，也不能证明来源 ID 真实、重复扣费、银行到账、费用可入账、退款/COGS 或 V162/V64 最终净利润。

### 规范 V1 输入（固定字段顺序）

使用 SHA-256（JDK 内建，**不是 HMAC / 电子签名**）。逐字段按 UTF-8 编码：先写 4 字节**有符号大端长度**，然后写字段字节；`null` 用 `FF FF FF FF`，空字符串用 `00 00 00 00`，两者不相同。最终摘要输出 64 字符小写十六进制。

字段顺序（都作字符串；由 API 鉴权与 V102 行数据取得）：

1. `MERCHIVRA_SETTLEMENT_LINE_IDENTITY_V1`（域分隔 / 版本）
2. 租户 UUID（小写标准字符串）
3. Seller Marketplace UUID（小写标准字符串）
4. 人工费用 source UUID（小写标准字符串）
5. V94 关联 link UUID
6. V102 结算报告 UUID
7. 结算行行号十进制文本
8. 原 Amazon 报告 ID
9. 原文档 ID
10. 文档 SHA-256
11. 交易类型
12. 金额类型
13. 金额描述
14. 原始订单引用
15. 有符号行金额，采用数据库 `NUMERIC(20,6)` 读取的 plain text，例如 `-12.340000`
16. 原币
17. 当时关联人 UUID
18. V102 捕获时间：从 `captured_at` 转换为 UTC `Instant.toString()`

不得折叠大小写、修剪业务字符串、把空值填零，也不得按 UI 本地时区修改时间。V1 不纳入审核状态、利润状态、相似度、当前银行状态等可变化的结果。升级字段序列必须用新版本号，旧版本按旧规范继续复算。

### 测试与部署门禁

新增纯 Java 离线单元测试：**固定黄金摘要**、重复计算、租户/站点/来源隔离、空值与空字符串不同、金额及文档摘要敏感、旧记录拒绝生成、所有财务认证标志仍为 false。原 V181 Controller/Service/SQL 语义不变。通过 PR CI 的 Java25/Gradle、React19/Next16/Vite、Compose；真实 PostgreSQL 18 / 完整 A3 只能在对应新业务 SHA 的验收完成后宣称。

保持真实 Amazon Seller/Ads 授权 OFF；生产 #104 **NO-GO**。独立外部身份和行级冲突裁决仍须遵循现行 P0，不能因为这里出现 SHA-256 而放行。

[归并前 V181B 完整原文](https://github.com/kunlong-luo/merchivra/blob/2c2265036df33f0afc7269ff392eea8a5a5059e4/docs/finance/v181-settlement-identity-fingerprint-v1.md)；上方两次精确 SHA/A3 验收互不替代。
