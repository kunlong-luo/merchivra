# Merchivra · 当前执行计划（2026-11-21）

**状态：现行 P0 任务顺序，不是自动上线授权。** 以 [当前事实基线](../current-status.md) 和 [V191 精确 SHA 的合成 A3](https://github.com/kunlong-luo/merchivra/actions/runs/44882890834) 为前提；早期 [V108 MVP 计划](mvp-freeze-release-v108.md) 与 [PLAN-001 长路线图](../roadmap.md) 保留为历史迭代记录。

## 北极星与边界

Merchivra 最重要的核心能力是**汇集订单/SKU/店铺/期间所有有证据的真实收入、费用、货物成本和退款回转，核对重复归属并形成可追溯的利润报表**。不能为了“有利润数字”绕开不可信来源、币种和会计政策。正式本地 UI 已切 Next.js 16，pnpm 是前端唯一包管理器；这两个工程完成项**不能**代替业务真值。

## 执行优先级（按证据闭环，而非展示卡数量）

| 优先级 | 要完成什么 | 现有可复用基础 | 完成与不可完成的判据 |
| --- | --- | --- | --- |
| P0-A | **第三方来源真实性/账单独立核验**：定义 Amazon Finances/Settlement、Ads、物流/头程/关税、银行证据的合法取得和身份复核流程 | V91–V95、V102/V181、V182–V184、本地身份冲突与不可变审计 | 每笔来源能追溯原始系统/报告/行/摘要、币种与独立批准；手工/合成绝不自动升级为 VERIFIED |
| P0-B | **全成本、退款 COGS 和唯一归属**：明确 SKU/订单/期间及跨月金额守恒、退款负费用/成本回转、真实重复扣款裁决 | V162、V164、V170–V188 的只读引擎、跨来源 hold、原币预览 | 有明确 G1/DEC-06/07/08 财务口径，整期全量而非仅当前页，缺来源为 UNKNOWN、重复不叠加 |
| P0-C | **可审计利润核算与报表**：让 V64 已知范围贡献与经过独立认证的费用接入同一可重算版本化证据链 | V64 冻结快照、V180 版本差、V183/V186/V187/V188 敏感度瀑布、Next 财务中心 | 仅在成本完整/已签署情况下给出“真实净利润”；此前 \`realizedNetProfit=null\`，展示阻断原因而非填写零 |
| P0-D | **可信 WMS 及库存/COGS**：把 B1 合成密钥持有权扩为独立 B2 外部运营主体与物理签收、获准 B3 库存/成本过账 | V173–V175 QC/回执和 V101 密钥持有权技术基础 | 不能用内部双/三 Owner、签名 JSON、上传公钥冒充真实外部身份或实收；G1 签署前不能改变 COGS |
| P0-E | **安全与真实目标环境**：TLS/代理头、KMS/密钥、AV、数据库 PITR/RPO/RTO、外部告警 ACK、权限、容量及人工审批 | 受控 Next/PG18/Chromium/Compose A3、[目标预检](../architecture/next-target-ingress-v151.md)、[生产阻断](../testing/release-readiness-v68.md) | [#104](https://github.com/kunlong-luo/merchivra/issues/104) 的 G1/G2/G3 分别签署，目标环境证据独立审核；任何合成 CI 绿灯不等于发布 |
| 最后 | **维护者本地真实 Seller/Ads 只读授权与 API 验收** | Mock Gateway、连接与同步代码 | 用户完成实际授权、合法角色/区域、分页/限流/撤销、凭证/银行真实性和隐私审查之后才可调整访问开关；写入另行审批 |

工作原则：先复用既有表、服务、Orval 客户端和差异审计；不新建重复“真实性系统”，不修改历史 Flyway；后端审计优先于增加展示卡片。

## 最近已交付工程基线

- **V188B**：只读原币期间成本瀑布，修复固定 V64 OpenAPI 九路由边界；仍不是已核验利润。参见 [V188 交付说明](../finance/v188-period-profit-cost-waterfall-preview.md)。
- **V189**：移除不可达 PR-only CI 分支判断，并按用户要求维护 **main 直接开发**。
- **V190**：\`pnpm\` 以外包管理迁移前，默认本地 Next.js 16 Web 和受控 Vite fallback 已以 7/7 A3 验证。
- **V191**：统一 Node 24 / **pnpm 12.20.0**、冻结锁文件、Docker 与 GitHub Actions，并在精确 [main SHA](https://github.com/kunlong-luo/merchivra/commit/8dab97147010fdc440ee81abcfa67cd1d1d57261) 完成 [完整 A3 7/7](https://github.com/kunlong-luo/merchivra/actions/runs/44882890834)。

## 测试纪律与 NO-GO

每次 \`main\` 改动运行快速 CI；身份、租户、金额、库存、审核/迁移改变必须跑相应高价值负例，跨边界重大变更按实际风险运行完整 PG18/HTTP/Session/CSRF/Chromium/Compose A3。测试需要**记录执行的精确提交**，不能把上次 green 当本次 green。只读合成 GitHub Pages 不是真正 ERP 部署。真实 Seller/Ads OAuth、SP-API 出站/写入保持关闭，生产 #104 NO-GO。

跟踪：[全成本利润 #12](https://github.com/kunlong-luo/merchivra/issues/12) · [财务对账 #11](https://github.com/kunlong-luo/merchivra/issues/11) · [生产 G1/G2/G3 #104](https://github.com/kunlong-luo/merchivra/issues/104) · [需求决策 #15](https://github.com/kunlong-luo/merchivra/issues/15)。历史计划中已完成或关闭的技术 Issue 不要重复当成新 P0。
