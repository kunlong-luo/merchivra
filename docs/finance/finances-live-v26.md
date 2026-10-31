# FIN-006 · Finances v2024-06-19 只读交易同步（V25/V26）

**状态：功能代码已添加，仍需集中进行真实 Amazon Seller 角色/权限、API 结果和财务口径验收。**

## 权限分层

- V25 引入 `Finance and Accounting` 的**专属** V12/V14 风格双人审核：`spapi_finances_role_review_request`、`spapi_finances_role_review_audit`、`spapi_finances_role_evidence`。申请人不能审核自身；只有已经被可信渠道授予 `SECURITY_REVIEWER` 的另一个当前 Owner 可审核，通过后证据 7 天有效；不同 Seller Grant 版本不复用；可以紧急撤销。仅保存外部案例号与 SHA-256 哈希，不上传证据内容。
- V25 `SpApiSellerGrantVault.loadFinancesApproved` 只有在 Seller 被验证、站点证据未过期、Reviewer 仍有权限、财务证据未撤销且匹配最新授权版本时返回 Finance Scope。Orders、Listing/FBA 的授权不自动等于财务权限。
- `MERCHIVRA_SPAPI_FINANCES_ROLE_REVIEW_ENABLED` 默认关闭；前端 Seller 连接页的 Finance 角色审核入口也是条件可用。

## 官方财务交易只读

- V26 通过官方 SDK `DefaultApi.listTransactions`（Finances v2024-06-19），固定当前已验证 Marketplace；只读取 Transaction ID、时间、原始有符号 `totalAmount`、原币、源业务类型及可验证的关联订单 ID。不接收用户可控 URL、Seller、时窗、来源 JSON 或 OAuth Token。
- **交易总额不一定是销售或净结算。** 所有官方来源金额一律写入现有 `finance_source_event` 为 `source_family=SPAPI_FINANCES`、`data_origin=LIVE`、`category=UNCLASSIFIED`。同 Marketplace 的交易 ID 做 SHA-256 稳定键，重复扫描幂等；同键不同事实失败、不能静默覆盖。真实卖家业务分类、费用拆解和净利润仍待完成。
- 同步默认只扫描**截至 48 小时前的最近 14 天**，降低最新财务入账延迟风险。业务人员可手动重复扫描窗口；旧交易超过 14 天的补录/回溯窗口仍待扩展。平台可比这一窗口更晚入账，不能宣称全量财务已覆盖。特别是美国 MFN 订单官方可能要求不带 marketplaceId 才能取得完整交易，本实现仍采用单站点过滤，不宣称该场景完整。
- 财务事件保持 `posted_at` 源时点、不加总 Orders 预计金额，人工未核验来源与 LIVE 完全分离；`UNCLASSIFIED` 未分类交易金额不代表收入、费用、结算或可得利润。

## 同步与自动化

- `MERCHIVRA_FINANCES_SYNC_ENABLED=false` 默认关闭。要求非 Demo、非 sandbox、禁止 Amazon 写操作、正式 Seller 授权和 Marketplace 核验、独立 Finance 角色审核。
- API（Owner + Session + CSRF）：`GET /api/v1/finances-sync/{marketplaceId}`、`POST /api/v1/finances-sync/{marketplaceId}/start`、`POST /api/v1/finances-sync/{marketplaceId}/step`。React 财务工作台展示状态、失败原因、开始/下一页；Seller 连接页展示角色双人审核。
- V26 `spapi_finances_sync_job`、`spapi_finances_page_commit` 保存站点、固定时间窗、游标、代次、独占租约 Epoch、重试次数与记录。官方 SDK 网络调用在事务外，写入原始事实与页 checkpoint 在事务内；事务结束前重新锁 Seller、站点、授权、角色证据以抵御并发撤销。不能把错误页当空页推进。
- 可选 `MERCHIVRA_FINANCES_SYNC_SCHEDULER_ENABLED=false` 开关和 `MERCHIVRA_FINANCES_SYNC_SCHEDULER_MS=60000`。只继续 Owner 显式启动的任务，429/5xx 有界退避，过期 RUNNING 租约可由新 Worker 恢复，默认没有自动向 Amazon 发任何请求。

## 后续重点

真正的 Finances 全角色与实际账号联调、原始 breakdown 分层/税费与卖家收入归属、退款/赔偿/仓储费分类规则、结算/支付关联、银行流水匹配、时间窗回补、财务审计异常清单、贡献额与成本/费用说明、通用报表及跨币种 FX。当前交易汇总是**未分类来源事实**，不能叫净利润或结算。


## V27 · Finances 来源拆分层级（仍不进行自动会计分类）

`finance_transaction_breakdown` 用 `source_path` 和 `parent_path` 保留官方 `breakdowns[]` 嵌套父子节点、sourceType、可选原币有符号金额和末级标记。主财务汇总仍**只统计** `finance_source_event.totalAmount` 对应的 UNCLASSIFIED 来源事实，不额外把 breakdown 父子金额加进去。

工作台可展开某个真实来源事件的明细，`GET /api/v1/finance/marketplaces/{marketplaceId}/events/{eventId}/breakdowns` 只允许当前租户 Owner 查看同站点事件。重放同键来源时，如果已有 breakdown 的父子、金额/类别不同，停止本页提交并保留先前可信内容；缺少 breakdown 不视为零费用。V27 是来源拆解基础，**并未自动证明佣金、税金、退款、结算类型和卖家可得净利润**。
