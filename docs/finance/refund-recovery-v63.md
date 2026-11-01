# V63 · 退款、退货与历史成本影响来源复核

**交付范围：人工资料核对与回转敏感度分析，不是已验证的物理收货、库存回补或已入账成本回转。**

## 来源链路

1. V34：仅采纳人工关联同 Marketplace LIVE 订单的 LIVE Finances PRODUCT_REFUND 事件，逐原始订单项审核数量、人工退货处置和脱敏依据。数量审核应受原始订购数量及跨事件审核数量约束。
2. V35：按原币有符号金额将已核平的 PRODUCT_REFUND 部分逐项归属，须保持原始符号，不得超额或跨币种。
3. 订单原始业务日对应的内部 SKU 历史成本必须齐备，V63 从 V34/V35 逐项数量、处分、金额和历史成本版本构建 SHA-256 来源证据指纹。
4. 只有 V34 相关每个商品项都具有 V35 金额归属、所有退款金额分配后残差精确等于零时，允许提交第二名 Owner 复核。独立审批人不能为申请人，也不能是当前 V34/V35 数量或金额编制人。

## 保守金额口径

- signedRefundTotal：已核平 PRODUCT_REFUND 来源的原始有符号金额。
- historicalCostExposure：每个有归属金额的退款项的历史单位成本乘人工审核的退款数量。
- claimedRestockCostSensitivity：仅 RESTOCKED_CLAIMED 的人工声明商品计算的潜在成本回转影响，NOT_RESTOCKED / UNKNOWN 不计入该敏感度。
- actualCogsReversal：永远 null；inventoryReceiptVerified 与 ledgerPosted 均永远 false。即使来源双人复核为 REVIEWED，**也不得**视作真实库存入库、成本回转或财务凭证。
- 所有金额仅为当前来源的局部风险预览，不是净利润；V61/V62 的 REFUND_COST_REVERSAL_UNVERIFIED 阻塞仍保留，不能据此将退款订单纳入已确认贡献。

## Flyway 与 API

Flyway V61（与产品 V63 里程碑编号不同）新增 finance_refund_recovery_review / finance_refund_recovery_audit，按 tenant + Marketplace + event + order 的复合外键隔离，记录 PENDING/REVIEWED/REJECTED、MANUAL_UNVERIFIED 来源、证据 hash、申请/决策人与每次申请、重新申请、审批、驳回的不可变审计。

- GET /api/v1/finance/marketplaces/{marketplaceId}/events/{eventId}/refund-recovery
- GET /api/v1/finance/marketplaces/{marketplaceId}/events/{eventId}/refund-recovery/audit
- POST /api/v1/finance/marketplaces/{marketplaceId}/events/{eventId}/refund-recovery/request
- POST /api/v1/finance/marketplaces/{marketplaceId}/events/{eventId}/refund-recovery/approve
- POST /api/v1/finance/marketplaces/{marketplaceId}/events/{eventId}/refund-recovery/reject

POST body 仅含 reason（10–500 字可打印说明）。服务端从 Session Owner 推导租户和操作者，不信任客户端传入 tenant/actor。全部返回 no-store，写入需要 CSRF。涉及 V34/V35 编辑的写并发使用订单+事件锁及 REPEATABLE_READ；来源数量、类别、费用、处置或成本变化则旧审批 STALE。

## React 与验收

React 财务工作台的退款面板新增历史成本敞口、人工重新入库敏感度、未分配差额、逐项来源说明、双人申请/审核/驳回和审计。前端 Zod 强制 actualCogsReversal=null、inventoryReceiptVerified=false、ledgerPosted=false。

Java25/Gradle、React19/TypeScript/Vitest/Vite、PG18 Flyway V61 以及 Docker Compose CI 用于验证离线代码。真实 Amazon 授权、FBA/FBM 退货实收核验、卖方可售库存与报废事实、正式会计 COGS 回转/退款记账仍为后续必要验收。V64 整期异步利润报表必须继续把不能可信回转的退款订单标为阻塞，而非假设零损失。