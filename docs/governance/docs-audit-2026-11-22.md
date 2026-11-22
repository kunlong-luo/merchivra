# DOC-196 · 文档规范收敛与查漏补缺（2026-11-22）

**状态：文档治理实施记录，非新功能/财务/生产验收。** 检查依据：整理前 `main` [`2136375`](https://github.com/kunlong-luo/merchivra/commit/2136375590abaae2373c565a1de930aa4ce45483)；该 SHA 的[快速 CI #45016097852](https://github.com/kunlong-luo/merchivra/actions/runs/45016097852) SUCCESS。V193 [完整 A3 #45012495202](https://github.com/kunlong-luo/merchivra/actions/runs/45012495202) SUCCESS 属于旧 SHA `6008832`，**不能继承为 V194/V195 或本次文档提交的 A3**。本轮新提交的 CI 以实际运行结果另行报告。

## 已发现且明确处理的高影响问题

| 严重度 | 具体问题 | 收敛措施 |
| --- | --- | --- |
| P0 | 根 `AGENTS.md` 近 1,000 行、`Agent.md` 700 多行，历史 Vxx 和现行指令叠加，仍包含旧包管理、Vite 默认、分支/PR 规则 | `AGENTS.md` 变为**唯一现行 Agent 入口**；`Agent.md` 只保留兼容跳转；原长版可在上述旧 Git SHA 查阅 |
| P0 | `document-control.md` 仍要求所有文档 PR、使用 V108 作为现行计划 | 更新为 **main 直接提交**、分层验收、单一当前计划与明确证据格式 |
| P0 | 文档首页、状态和计划把 V194 标成“快 CI 待验收”；V195 本地会话错误 UX 没有同步 | 更新精确 V194/V195 快 CI、V193 A3 和外部未知的区分；链接到实现说明 |
| P1 | 根 README、文档导航的版本时间不同步 | 用稳定的当前入口和实测技术基线；保留 V191/V193 历史验证链接供审计 |
| P1 | 规范散落在多份 Vxxx 说明里，实际开发反复重造/误读 | 建立 [ENG-001 工程实施规范](engineering-standards.md)、[文档分类与审批](document-control.md)、[唯一索引](../README.md) |
| P1 | 文档一致性脚本未对 Agent 双入口及新规范实施自动防回退 | 扩展 `scripts/check_docs_consistency_v109.py` 以检查新权威入口、pnpm/Next/main、外部利润门禁及相对链接 |

## 尚未关闭的真实缺口（按后续优先级）

| 优先级 | 缺口 | 何种证据才算关闭 |
| --- | --- | --- |
| P0 / 测试 | **V194 Flyway V104** 版本化指纹的真实 PG18/Owner HTTP/旧证据/篡改 fail-closed 完整 A3 | 在 V194 之后精确 main SHA 触发完整 A3，核对 PG18/Flyway/HTTP/Chromium、失败/跳过情况；旧 V193 A3 不可沿用 |
| P1 / 本地体验 | **本地 HTTP 500 根因**尚未知；V195 仅区分会话错误并改善提示 | 真实本地 Spring Boot 和 PostgreSQL 日志、直连 8080 与 Next 3000 对照请求、定位可复现根因并修复后专项回归 |
| P0 / 财务 | 外部 Seller Finances/Settlement/Ads/物流/银行独立核证、退款 COGS、全成本归属、G1/DEC-06/07/08 | 合法授权后的外部数据证据、双人审核、原币守恒、不可变审计与财务批准；当前 `realizedNetProfit=null` |
| P0 / 上线 | G1/G2/G3、真实 TLS/KMS/AV/PITR、外部告警 ACK、性能/恢复/运营商 WMS B2/B3 | 目标环境可审计验收 + 责任人正式签署；[#104](https://github.com/kunlong-luo/merchivra/issues/104) 解除之前 NO-GO |
| P2 / 长尾文档 | 大量早期 Vxxx、G2 草案及历史 API 含过去的“当前”时态和已淘汰指令 | **按触达模块逐步**添加 Superseded/历史标识；不要无证据逐字修改历史事实，也不宣称已逐篇审计 |

## 持续维护约束

- 每次改代码同时考虑 `docs/current-status.md`、`docs/roadmap/current-plan.md` 和相关操作文档；不要为每个 Vxxx 再新增一套最新开发守则。
- 所有当前文本只承诺**确实能用对应代码/精确 SHA 检验**的内容；把 G1/G2 未签方案和外部数据视作待办。
- 文档检查覆盖指定权威入口的**相对链接、已知危险规范回退**，不是所有旧 Markdown 及 URL 的完整审计。真实安全/财务/目标生产验证仍须单独执行。
