# V80 · 跨模块异常处理看板与可解释的人工建议

## 本轮已实现

新增 **只读** `GET /api/v1/exception-insights/marketplaces/{marketplaceId}`，由当前服务端 Owner Session 决定租户及可访问站点；不接受客户端租户 ID。继续使用 V10/V67/V69 Operations 扫描和 `ops_finding`、V76 本地采购/FBA 分阶段来源证据、V77 的人工处理单与审计、V78 SLA 统计、V79 Owner 工作量。

**没有新增数据库迁移、任务系统、模型服务或依赖**：V80 只读取既有表和 DTO。API 放在独立 `/api/v1/exception-insights/**` 下，与已锁定校验的 V67 Orval OpenAPI 分组互不干扰。前端使用已有 `merchivraFetch`、Zod 4 和 TanStack Query，运营工作台新增独立组件。

看板展示同一 Marketplace 的：
- **Ops 分类**：按已有 `rule_code` 汇总 OPEN / ACKNOWLEDGED / DONE / CLEARED。数据为最后一次已实际执行的运营巡检历史，GET 不触发扫描，用户可从原运营工作台手动巡检。
- **人工处理单闭环**：使用 V78 针对全部已持久化处理单的总数、OPEN、ACKNOWLEDGED、RESOLVED 和到期统计。RESOLVED 仅指本地人工操作，不是 Amazon 履约完成。未登记的行不算已处理单。
- **Owner 负载**：使用 V79 只读工作量结果；对未分配的建议行推荐当前有效 Owner 中已登记 OPEN + ACKNOWLEDGED 数最少者，逾期数打破平局，再按 ID 排序。推荐不考虑未知工作量、技能、时区及休假，**不自动分派**。若无有效 Owner 则推荐为空。
- **优先级建议**：根据 V77 最新本地证据指纹、人工截止期限、V65 差异与 V75 双 Owner 身份审核阻断条件，确定性分配 P0～P3 和具体原因与下一步建议。

## 优先级规则（固定、可复核）

| 优先级 | 条件（按优先顺序） | 人工建议 |
| --- | --- | --- |
| P0 | 上次人工审阅后来源指纹变化（包括旧 RESOLVED），或本地处理截止已超期 | 复查 PO/ASN/FBA 来源证据、协调 Owner 处理期限 |
| P1 | V75 身份待独立审核；V65 本地超报待差异复核；人工期限临近；来源 HIGH 风险 | 先完成所要求的独立审核与差异处理 |
| P2 | 未指派有效 Owner 的工作；来源 WARNING 但已分派 | 分派或继续核查，不把不同来源阶段的数量直接相减 |
| P3 | 其余本地常规人工跟进 | 回到原处理单记录原因、证据和跟进结果 |

界面展示最近 **100 张货件、最多 500 行证据** 中需人工跟进的最多 **30 条优先级建议**，服务器按优先级、截止时间和行 ID 稳定排序。返回 `shipmentScope/evidenceLines/sourceTruncated/actionableCount/recommendationsTruncated`，防止把局部结果宣传为全量历史。已 RESOLVED 且证据未变化的来源行不重复建议。处理单生命周期汇总和 Owner 负载则基于**全部已登记的本地处理单**，明确区分统计口径。

## 跨模块入口、安全与准确性

运营 Ops 标签通过 V79 现有 `onOpenFbaException` 入口跳到当前可访问 Marketplace 的原异常行、货件、PO/ASN 关联操作。没有创建任何通过 URL 或浏览器身份绕过 Owner Session 的后门。新的诊断接口完全只读，不触发 V78 scan、不写 V77 case、不修改 PO/ASN/入仓和库存，不执行采购建议，也无邮件/外部通知。

**P0 等级不是平台风险概率或 Amazon 仓库 SLA**。MOCK、人工未核验、LIVE 等不同来源不得混淆；本地采购收货仍不等于 Amazon 入仓。正式 Seller OAuth、SP-API 出站、生产写入默认保持关闭。

## 验证

新增 `OperationsExceptionInsightsServiceTest` 验证规则优先级、来源变更及人工独立证据阻断。React `exception-insights-v80.test.ts` 验证 Session 同源只读 GET、Zod 响应上界和 Amazon 未核验标识。新增 `scripts/smoke_exception_insights_v80.py` 并串入 PG18 CI，在 V76～V79 模拟数据后核对统计口径、P0/P1 规则、活跃 Owner 建议、独立站点权限、来源变化和零写入。实际通过范围以 CI 结果为准。
