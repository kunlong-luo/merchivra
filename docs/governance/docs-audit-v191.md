> **历史归档 / 2026-11-22**：V191 的这次审计仅对其当时的代码与文档成立；现行规范已由 [DOC-196](docs-audit-2026-11-22.md)、[GOV-001](document-control.md) 与 [ENG-001](engineering-standards.md) 收敛。请勿将本页提及的“最新 V191”作为现在的开发结论。

# DOC-191 · 当前文档收敛与历史归档判定（2026-11-21）

状态：**文档信息架构整理**。核查版本基线为 [V191 已通过的完整 A3 SHA](https://github.com/kunlong-luo/merchivra/commit/8dab97147010fdc440ee81abcfa67cd1d1d57261)；本次修改自身仍须以新提交的文档一致性和 CI 结果判断。**文档更新不等于新业务功能、真实来源核证、G1/G2/G3 签署或生产 GO**。

## 发现的问题与本次处理

| 原问题 | 解决方式 |
| --- | --- |
| `docs/README.md` 把 V160/V161 说成最新、堆积早期 PR/CI 表格 | 只保留当前用途导航，权威事实移到 [当前状态](../current-status.md) |
| V108、PLAN-001、V148 等长文多次出现“当前阶段/下一 P0”，误导后续执行 | 增加 [唯一当前执行计划](../roadmap/current-plan.md)，历史长文加醒目归档说明，不改当时的证据/失败记录 |
| `CONTRIBUTING.md` 仍要求功能分支/PR、Node 前端旧锁文件和 Next 预览 | 更新为 main 直接开发、pnpm 12、Next 默认、风险分层验收 |
| README 中英及开发部署/CI 对技术栈、默认端口、手动 A3 有不同历史说法 | 以当前 `package.json`、Docker/Compose、工作流为准统一入口，静态展示/Vite 回退单独标明 |
| V161/V162、V175-B 的某些“尚未做”是早期真实历史，后续代码已经存在技术部分 | 保留原文历史，增加“以当前状态为准”的显式指向；B1 已落地技术基础但 B2/B3 未完成 |
| 把合成 PostgreSQL18/Chromium/Next/Orval 绿灯误读为真实 Amazon 成本/会计利润 | 当前状态表明确 `realizedNetProfit=null`、证据独立性、WMS 信任锚及 G1/DEC-06/07/08 仍待办 |
| 文档修改未强制核对现行事实与命令 | 扩展 `scripts/check_docs_consistency_v109.py`：必备索引、pnpm-only、main-only、Next 默认与历史提示检查 |

## 文档状态归属与更新纪律

- **Current**：`docs/current-status.md`、`docs/roadmap/current-plan.md`、`docs/README.md`、根目录 README、`docs/development.md`、`docs/deployment/local-spark-db.md`、`docs/testing/ci-policy.md`、`SECURITY.md`、`CONTRIBUTING.md`。修改代码默认行为时需**同步这些文件中受影响的描述**。
- **Implementation / evidence**：`docs/finance/vxxx-*.md` 及版本专用测试记录仅叙述本版本实际 SQL/服务/不可变事实和**精确 SHA**；可以追加勘误，不能抹掉曾失败的测试。
- **Proposal**：`docs/product/finance-rulebook.md`、G1/G2 草案、WMS B2/B3 和旧 ADR 中未签署部分。技术实现不等于 G1/G2/G3 自动批准。
- **Archive**：`docs/roadmap.md`、`docs/roadmap/mvp-freeze-release-v108.md`、`docs/requirements/implementation-acceptance-rtm-v148.md`、`docs/governance/docs-audit-v109.md` 与旧 Vxx 计划。保留以便审计，不再当实时任务列表。

## 未因本轮文档修复而完成的事

真实 Amazon/Ads/银行来源核验、外部 WMS 实收认证、退款 COGS 回转、跨币种 FX/会计口径、财务 G1/DEC-06/07/08、目标 HTTPS/密钥/备份/告警和 G1/G2/G3 人工批准。生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) 始终 **NO-GO**；仅经隔离 A3 的 Next 本地切换、pnpm 迁移和 CI 绿灯不能解除该阻断。
