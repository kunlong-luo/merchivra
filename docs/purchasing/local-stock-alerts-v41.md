# PO-004 · V41 本地仓库补货策略、异常提醒与审核队列

开发状态：**已提交代码，待全部 CI / PostgreSQL 18 Flyway / 真实 HTTP/并发场景集中验收**。本模块对本地仓库台账做保守建议，绝不修改 Amazon FBA/FBM、采购订单、仓库库存事实或真实货件。

## 数据模型

- V41 `erp_local_stock_policy`：租户、仓库、内部 SKU 三级作用域，`0 <= reorder_point < target_stock <= 10,000,000`。只有 ACTIVE 仓库和 ACTIVE 内部 SKU 能设置策略；策略更新与删除记录在 `erp_local_stock_policy_audit`。
- `erp_local_stock_finding`：每个租户/仓库/SKU/规则最多一条当前告警，状态 `OPEN`、`ACKNOWLEDGED`、`DONE`、`CLEARED`。扫描仅在原条件消失后清除；未消失的 DONE 状态不自动重开。后续条件重新出现才重开。人工状态变更写 `erp_local_stock_finding_audit`。
- `erp_local_stock_scan_run`：记每次人工或计划巡检的数量、来源、操作者和时间；调度巡检不伪造人工操作者。

## 保守计算语义

1. 台账余额 = 该仓库/内部 SKU 的 V39/V40 **已记录的带符号库存流水之和**。如完全没有流水，则为 `NO_LEDGER`，返回 `onHandQuantity=null`，绝不能把未知视为 0。即使 `reorderPoint=0` 也不能从无观测的 SKU 推导出缺货。
2. 台账余额异常负数 = `LEDGER_ANOMALY`，不计算补货量；余额正好等于 0 且已经有流水，是可信的本地记账零值。
3. 仅在已记录余额 `0 <= onHand <= reorderPoint` 时 `state=LOW_STOCK`，`suggestedQuantity=targetStock-onHand`。高于阈值为 `HEALTHY`，建议量为 null。建议不执行采购，也不保证库存账实相符。
4. `approvedUnreceivedQuantity` 显示该内部 SKU 在**全部仓库未分配目的地**的 `APPROVED/RECEIVING` 采购剩余件数，**不扣除**仓库补货建议，也不称作供应商实际已发货或 FBA 在途。
5. 新增持久异常：`LOCAL_LOW_STOCK` 由有库存流水且余额低于策略触发；`LOCAL_LEDGER_NEGATIVE` 捕获不合理的负数台账；`LOCAL_COUNT_VARIANCE` 捕获过去 7 日单次实盘差异绝对值至少 100 件。规则使用本地事实，不共享 V10 的 Amazon FBA 告警语义。若未配置策略、缺少库存流水，不生成 `LOCAL_LOW_STOCK`，但可在策略列表看到 `NO_LEDGER`。

## API 与安全

全部接口需有 Session OWNER 权限，跨租户请求返回未找到；写请求经过标准 CSRF。浏览器不提交 tenantId。

| 方法 | 路径 | 内容 |
|---|---|---|
| GET | `/api/v1/purchasing/warehouse-alerts/{warehouseId}/advice` | 本仓策略、库存来源状态、保守补货参考和全仓未分配待收货（最多 500 SKU） |
| POST | `/api/v1/purchasing/warehouse-alerts/{warehouseId}/policies` | `productSkuId,reorderPoint,targetStock` upsert 与审计 |
| DELETE | `/api/v1/purchasing/warehouse-alerts/{warehouseId}/policies/{skuId}` | 移除策略并审计；关联旧告警在下一次完整巡检清除 |
| GET | `/api/v1/purchasing/warehouse-alerts/{warehouseId}/findings?filter=ACTIVE\|ALL` | 审核队列（最多 100 条） |
| POST | `/api/v1/purchasing/warehouse-alerts/scan` | 当前租户所有 ACTIVE 仓库扫描、upsert 三类告警并清理旧命中 |
| POST | `/api/v1/purchasing/warehouse-alerts/findings/{findingId}/state` | `OPEN/ACKNOWLEDGED/DONE`，审计、禁止重开已清除记录 |

手动巡检可通过 Owner → 采购与供应商 → 选择仓库 → 本地仓库补货与异常待办。**计划巡检默认关闭**，部署者显式设置 `merchivra.warehouse-alerts.scheduler-enabled=true`，间隔 `merchivra.warehouse-alerts.scan-interval-ms`（默认 900000 ms）。扫描数据库租户行锁以在多实例之间序列化；实际 PostgreSQL 并发行为待集中测试。

## 后续待实现

- 真正的采购计划 / ASN / FBA Inbound 只读关联，供应商起订量和交期预测，以及仓库预留/质检/拣货、成本口径、仓库告警的外部邮件/即时消息分发。不能把规则匹配当成订单已采购、货已发出或真实 Seller API 成功。
