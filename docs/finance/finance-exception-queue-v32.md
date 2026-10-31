# V32 · 财务异常自动扫描与待办队列

**实现范围：仅扫描项目本地 PostgreSQL 已有数据，不请求 Amazon、银行或外部结算 API。**

## 数据与边界

`FinanceExceptionQueueService` 的 Owner GET `/api/v1/finance/marketplaces/{marketplaceId}/exceptions?limit=50` 只检查当前租户、当前 Marketplace：最近至多 100 笔 `SPAPI_FINANCES/LIVE` 来源交易、20 条人工参考结算单和 20 笔 LIVE 订单。默认每次最多 50 笔交易，返回检查样本数量及 `partialScan` 标记，**结果不等于全量历史已通过核查**。

- 来源交易：检查末级 breakdown 缺失、金额缺失、原币不一致、sourceType 尚未人工分类、末级合计与原始 signed totalAmount 不一致。
- 人工订单关联：有外部 Order ID 却没有审核完成的同站点 LIVE 订单匹配时，提示人工复核；不能因订单号碰巧相同自动过账。
- 结算批次：通过 V30 的已关联交易/账单 signed 总额比较给出未分配、缺来源、差额、币种和期间边界的异常。**金额对平不是已到账**。
- 成本：基于 V29 的有效日期 SKU 成本完整度，提示未知订单时间、映射缺失、订单项未观测等问题；只提示来源缺口，不认定真实利润亏损。
- 交易未分配到人工结算单仅为 INFO，可能是尚未生成结算参考单或未到账时间窗口，**不等于亚马逊漏付**。

## 操作性与安全

`FinanceExceptionEngine` 是纯 Java 无副作用分析器；QueueService 组合本地存储快照和已有 V30/V29 读取服务。API 不创建 Finance Event、Settlement Link、角色审核、订单或账务凭证。始终不会要求 Seller 密钥，不能自动标记完成结算、退款或税务事项。

UI `FinanceExceptionQueuePanel` 按站点显示发现次数、样本规模、严重度、来源编号、下一步建议；有界最多显示前 100 项，可按等级筛选，当前页面每 60 秒更新本地检查结果。不存在任何后台周期 SP-API 调用。

## 仍待解决的核心开发

真正的双向审核处置（含人工忽略/消除的追加审计）、长期历史分页增量扫描、对账差异的正式来源分类、退款后的成本回转、付款/银行入账核验、原始业务分类的有效期版本和可冻结的利润报表。此阶段所有数据均为预览或待处理项。
