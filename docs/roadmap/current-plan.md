# Merchivra · 当前执行计划（2026-11-22 / DOC-196）

**状态：现行 P0 任务顺序，不是自动上线授权。** 以 [当前事实基线](../current-status.md)、[ENG-001 实施规范](../governance/engineering-standards.md)、V194 之后的 [最新快 CI #45016097852](https://github.com/kunlong-luo/merchivra/actions/runs/45016097852) 和**仅对应 V193 SHA**的 [PG18 完整 A3 #45012495202](https://github.com/kunlong-luo/merchivra/actions/runs/45012495202) 为依据；早期 [V108 MVP 计划](mvp-freeze-release-v108.md) 与 [PLAN-001 长路线图](../roadmap.md) 保留为历史迭代记录。

## 北极星与边界

Merchivra 最重要的核心能力是**汇集订单/SKU/店铺/期间所有有证据的真实收入、费用、货物成本和退款回转，核对重复归属并形成可追溯的利润报表**。不能为了“有利润数字”绕开不可信来源、币种和会计政策。正式本地 UI 已切 Next.js 16，pnpm 是前端唯一包管理器；这两个工程完成项**不能**代替业务真值。

## 执行优先级（按证据闭环，而非展示卡数量）

| 优先级 | 要完成什么 | 现有可复用基础 | 完成与不可完成的判据 |
| --- | --- | --- | --- |
| P0-A | **第三方来源真实性/账单独立核验**：定义 Amazon Finances/Settlement、Ads、物流/头程/关税、银行证据的合法取得和身份复核流程 | V91–V95、V102/V181、V182–V184、本地身份冲突与不可变审计 | 每笔来源能追溯原始系统/报告/行/摘要、币种与独立批准；手工/合成绝不自动升级为 VERIFIED |
| P0-B | **全成本、退款 COGS 和唯一归属**：明确 SKU/订单/期间及跨月金额守恒、退款负费用/成本回转、真实重复扣款裁决 | V162、V164、V170–V188 的只读引擎、跨来源 hold、原币预览 | 有明确 G1/DEC-06/07/08 财务口径，整期全量而非仅当前页，缺来源为 UNKNOWN、重复不叠加 |
| P0-C | **可审计利润核算与报表**：让 V64 已知范围贡献与经过独立认证的费用接入同一可重算版本化证据链 | V64 冻结快照、V180 版本差、V183/V186/V187/V188 敏感度瀑布、Next 财务中心 | 仅在成本完整/已签署情况下给出“真实净利润”；此前 `realizedNetProfit=null`，展示阻断原因而非填写零 |
| P0-D | **可信 WMS 及库存/COGS**：把 B1 合成密钥持有权扩为独立 B2 外部运营主体与物理签收、获准 B3 库存/成本过账 | V173–V175 QC/回执和 V101 密钥持有权技术基础 | 不能用内部双/三 Owner、签名 JSON、上传公钥冒充真实外部身份或实收；G1 签署前不能改变 COGS |
| P0-E | **安全与真实目标环境**：TLS/代理头、KMS/密钥、AV、数据库 PITR/RPO/RTO、外部告警 ACK、权限、容量及人工审批 | 受控 Next/PG18/Chromium/Compose A3、[目标预检](../architecture/next-target-ingress-v151.md)、[生产阻断](../testing/release-readiness-v68.md) | [#104](https://github.com/kunlong-luo/merchivra/issues/104) 的 G1/G2/G3 分别签署，目标环境证据独立审核；任何合成 CI 绿灯不等于发布 |
| 最后 | **维护者本地真实 Seller/Ads 只读授权与 API 验收** | Mock Gateway、连接与同步代码 | 用户完成实际授权、合法角色/区域、分页/限流/撤销、凭证/银行真实性和隐私审查之后才可调整访问开关；写入另行审批 |

工作原则：先复用既有表、服务、Orval 客户端和差异审计；不新建重复“真实性系统”，不修改历史 Flyway；后端审计优先于增加展示卡片。

## 最近已交付工程基线

- **V194（旧快 CI 已通过；V105 修补待完整 PG18/A3）**：版本化 V1 冻结订单/SKU 证据捕获和独立重算，显式隔离历史 V62 摘要与新指纹；金额汇总、CSV、V188 瀑布发现旧证据或错误时 fail closed。仍不能解锁真实净利润。[设计与测试边界](../finance/v194-versioned-profit-capture-fingerprints.md)。

- **V195（快 CI 已通过）**：本地 Session 5xx/代理失败不再误导为“无法验证身份”；区分 401、403、连接错误和无效响应，有限自动重试与展开排错。**用户本机后端 HTTP 500 根因未证实**，不能把 UI 错误态优化当后端修复。见 [本地启动排错](../development.md#本地启动时显示服务暂时未就绪)。
- **V188B**：只读原币期间成本瀑布，修复固定 V64 OpenAPI 九路由边界；仍不是已核验利润。参见 [V188 交付说明](../finance/v188-period-profit-cost-waterfall-preview.md)。
- **V189**：移除不可达 PR-only CI 分支判断，并按用户要求维护 **main 直接开发**。
- **V190**：`pnpm` 以外包管理迁移前，默认本地 Next.js 16 Web 和受控 Vite fallback 已以 7/7 A3 验证。
- **V193**：复用 V62 表、V64 批处理和 V192 只读守恒，Flyway V103 将已采集订单、SKU、已完成任务及审计历史锁为**数据库级不可变**，阻止跨批次晚插 SKU；[说明](../finance/v193-frozen-profit-snapshot-database-guards.md)。本地旧 SHA-256 不升格外部验真，真实净利润仍不可解锁。
- **V192**：基于现有 V62/V188 在后端只读 SQL 增加**逐订单—SKU 五项原币金额守恒**，不让相互抵消的明细异常进入期间敏感度；对应 [V188/V192 说明](../finance/v188-period-profit-cost-waterfall-preview.md)。仍不计算已核验净利润。
- **V191**：统一 Node 24 / **pnpm 12.20.0**、冻结锁文件、Docker 与 GitHub Actions，并在精确 [main SHA](https://github.com/kunlong-luo/merchivra/commit/8dab97147010fdc440ee81abcfa67cd1d1d57261) 完成 [完整 A3 7/7](https://github.com/kunlong-luo/merchivra/actions/runs/44882890834)。

## 近期必须补齐的验证与文档

1. **V194 / V104**：针对含 V194/V105 的精确 main SHA 跑 PostgreSQL18/Flyway/Owner HTTP/历史旧证据、双向半指纹、篡改与金额阻断的 A3；前几轮发现旧瀑布预期问题及 V104 NULL/UNKNOWN CHECK 漏洞，不借用旧 A3 green。
2. **本地 HTTP 500**：取得用户本机 Spring/数据库日志与两条 curl 的可复现结果，定位真正故障；界面 V195 已友好化，但不是根因修复。
3. **文档治理**：每轮只维护 [单一当前状态](../current-status.md)、本计划、[ENG-001](../governance/engineering-standards.md) 与实际改变的运行指南；历史 Vxx 不再作为并列实时计划。

## 测试纪律与 NO-GO

每次 `main` 改动运行快速 CI；身份、租户、金额、库存、审核/迁移改变必须跑相应高价值负例，跨边界重大变更按实际风险运行完整 PG18/HTTP/Session/CSRF/Chromium/Compose A3。测试需要**记录执行的精确提交**，不能把上次 green 当本次 green。只读合成 GitHub Pages 不是真正 ERP 部署。真实 Seller/Ads OAuth、SP-API 出站/写入保持关闭，生产 #104 NO-GO。

跟踪：[全成本利润 #12](https://github.com/kunlong-luo/merchivra/issues/12) · [财务对账 #11](https://github.com/kunlong-luo/merchivra/issues/11) · [生产 G1/G2/G3 #104](https://github.com/kunlong-luo/merchivra/issues/104) · [需求决策 #15](https://github.com/kunlong-luo/merchivra/issues/15)。历史计划中已完成或关闭的技术 Issue 不要重复当成新 P0。
