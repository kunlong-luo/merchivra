# PO-005 · V42 供应商 SKU 采购规则与仓库采购计划

**范围**：本地仓库低库存建议 → Owner 手工保存采购计划 → Owner 再次确认生成本地采购单 `DRAFT` → 原有 `SUBMITTED` 与第二名 Owner `APPROVED` 审批。没有自动订单采购、自动 Amazon FBA 补货、供应商下单、货件写入或付款。

## 数据表与来源

1. `erp_supplier_sku_rule`：`(tenant_id,supplier_id,product_sku_id)` 唯一的 SKU 级供应商采购规则，包含 MOQ 最小起订量、包装倍数、0–365 天自然日交期、原币 `currency_code`、单位采购价。OWNER 变更/删除有独立 audit，价格不跨币种混合；活跃供应商 + 内部 SKU 才能更新。规则版本采用 `updated_at`，更新时至少增长 1 微秒。
2. `erp_local_purchase_plan`：只保存可追溯**本地仓库**计划。快照仓库、内部 SKU、供应商、最近一条正负库存移动 `source_movement_id`、策略及供应商规则的更新版本、本地余额、阈值、目标、MOQ/包装倍数/交期、原币单价、整箱调整后的数量和估算到货日。按 `(tenant,warehouse,sku,source_movement_id)` 唯一，禁止相同库存证据重复建立采购计划，**包括计划被取消或已转换**。按租户唯一 `request_key` 实现原请求重放幂等。状态 `PROPOSED`、`CONVERTED`、`CANCELLED`；转采购草稿后一对一关联原 PO，所有状态动作保留审计。
3. `erp_local_purchase_plan_audit`、`erp_supplier_sku_rule_audit` 为追加式日志。不修改 V39/V40 本地仓库流水或 V41 告警队列；不改变 Amazon FBA 观测和历史采购来源。

## 算法及停止条件

- **无库存证据**（SKU 在指定仓库没有任何移动）=> `NO_LEDGER`；不能以未知当零生成计划。余额异常负值 => `LEDGER_ANOMALY`；未触发本地低库存阈值 => `HEALTHY`。
- 仅 `0 <= onHand <= reorderPoint` 且存在最近一次本地移动证据时，可提出原始建议件数 `targetStock - onHand`。
- 供应商订购件数 = `ceil(max(原始建议件数,MOQ) / packMultiple) × packMultiple`；计算使用 long 整数避免溢出；如数量超过采购单单行 1,000,000 件，显示 `LIMIT_EXCEEDED`，不得创建计划。
- **交期只是从计划创建日（UTC 日期）+ 设置的自然日得到的估计，不是供应商交货承诺，也不自动更新真实货件预计日期。** MOQ、包装倍数与采购价仅来自用户提供的本地规则；没有 Amazon 费用或财务对账含义。
- 任何已审批但未完成的采购订单均**不能分配到具体本地仓库，不能直接抵减当前仓库补货建议**。计划也不能自动改写 FBA 库存。
- 如果来源库存、最近移动 ID、策略版本、供应商规则版本、供应商/仓库/SKU ACTIVE 状态发生变化，**创建和转换均返回 409**，要求刷新后重新审阅。
- `propose` 与 `convert` 先串行锁定 ACTIVE tenant，再按顺序锁定仓库；采购单创建与计划转换在同一个 Spring 事务中。重复转换返回既有 PO ID，不再新建 PO。需要重新补货的同 SKU 必须有新的库存流水证据。人工取消不能绕过此来源去重。
- **防止待采购重复计入**：同 SKU 存在未转换计划、或存在任意仓库尚未完成的本地 PO（DRAFT/SUBMITTED/APPROVED/RECEIVING）时，禁止新建计划；转换前亦须确认没有其他未完成 PO。现有采购单目的仓库未分配，不能当作已入某仓库存或直接扣减建议。

## API

| 操作 | 路径 | 输入 / 说明 |
|---|---|---|
| GET | `/api/v1/purchasing/planning/supplier-rules` | 租户全部本地规则，最多 500 |
| POST | `/api/v1/purchasing/planning/supplier-rules` | `supplierId,productSkuId,minimumOrderQuantity,packMultiple,leadTimeDays,currency,unitCost` |
| DELETE | `/api/v1/purchasing/planning/supplier-rules/{supplierId}/{skuId}` | 删除规则、审计；已有计划保留 |
| GET | `/api/v1/purchasing/planning/warehouses/{warehouseId}/recommendations` | 基于仓库台账、V41 策略与供应商规则的建议，最多 500 |
| GET | `/api/v1/purchasing/planning/warehouses/{warehouseId}/plans` | 本地计划历史，最多 100 |
| POST | `/api/v1/purchasing/planning/plans` | `requestKey,warehouseId,supplierId,productSkuId,expectedLastMovementId,expectedPolicyUpdatedAt,expectedRuleUpdatedAt,expectedOnHand,expectedOrderQuantity` |
| POST | `/api/v1/purchasing/planning/plans/{id}/convert` | 显式确认并重新检查来源，调用 `PurchasingService.create` 生成 PO DRAFT |
| POST | `/api/v1/purchasing/planning/plans/{id}/cancel` | 明确取消尚未转换的本地计划 |

全部从 OWNER Session 推导 tenant/actor，写操作经 CSRF，FK 采用 tenant 复合约束。计划只是建议，**两段用户确认**，不会直接调用 SP-API、供应商系统、支付系统或 Amazon 货件写接口。

## 前端与验收

Owner → 采购与供应商 → 供应商采购规则与采购计划：录入/编辑 SKU 供应商起订量、包装倍数、原币单价、交期；选仓库后查看带证据的建议；手工保存计划，再独立点击生成采购单草稿，并在既有采购列表提交和第二人审批。新状态有中文提示。

新增 JUnit 单测覆盖 MOQ 向上取整、数量上限、缺失证据、过期库存、重复幂等请求、转换时证据变更及转换生成本地 PO DRAFT；Vitest 校验原币规则、CSRF、证据 ID/版本、保存与转换的两步请求。**是否实际通过需检查最新 GitHub CI。** PostgreSQL 18 V42 新迁移、不同会话并发/租户隔离 HTTP、真实数据库下供应商规则与 V41 快照版本，以及 Compose 完整链路，仍需专项验证；不能仅凭单测声称验收成功。

## 后续

供应商合同与报价阶梯、不同供应商优先级 / MOQ 最优分配、供应商采购合同确认、物流 ASN / 在途与预期到货追踪、自动化审批策略，以及 Amazon FBA Inbound 只读对账尚未实现。
