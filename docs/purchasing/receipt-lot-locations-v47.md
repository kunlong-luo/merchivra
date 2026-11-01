# PO-010 · V47 收货分段、待检库位和补货压力可视化

**原则**：V47 只处理本地 V46 真实收货的*接收阶段*批次分段及待检物理位置标记。它**不改变库存台账**，不拆分 V44 质检快照，也不改变 V46 需要不同 Owner 审批后才写入的 `QC_RELEASE` 合格库存移动。不调用 Amazon SP-API，也不把供应商 ASN SHIPPED 当作实际发货的承运商证据。

## 数据模型

- `erp_local_stock_bin`：租户+本地仓库下唯一 `bin_code`，`QC_HOLD` 和 `STORAGE` 两种用途，ACTIVE/ARCHIVED 状态；每个新建/已有仓库都有系统虚拟 `QC-HOLD`，其存在仅代表内部系统默认收货待检区，**不证明真实货架/货位的存在或扫描定位**。可创建其他可读的待检库位及普通库位目录。
- `erp_receipt_lot_piece`：属于唯一真实 `erp_receipt_quality_batch` 收货证据的分段，tenant+receipt 下 `piece_code` 唯一，仓库/sku 固定，分段的 `bin_id` 必须在相同仓库，数量 1～1,000,000。新 V47 收货在同一事务创建 **ROOT 分段**，其数量 = 原始实收件数，并指向虚拟 `QC-HOLD`。迁移只为此前已经存在且仍为 `PENDING_QC` 的 V46 批次补一个同等数量的 ROOT；已检/已放行的历史批次不虚构分段。
- `erp_receipt_lot_piece_event`：追加式来源事件、split/move 双 UUID 关联、请求 UUID（租户唯一、自动创建 ROOT 时为空）、责任 Owner、前后库位、数量、原因。反复提交同一请求 UUID 只允许完全一致的原始动作重放；不同 Owner 或不同内容必须拒绝。

## 拆分、库位移动和数量守恒

- 仅 `PENDING_QC` 状态可拆分或更改分段位置。V46 的完整收货 QC/独立放行继续以**真实 receipt** 为单位；分段数量仅反映**待检收货货物的接收标签与存放来源**，不能独立代表“已质检”“可售”“可退”或“批次级 FEFO 扣减”。
- Owner 使用当前分段 ID、所需拆分数量（必须至少 1 且小于原段数量）、新的唯一 piece_code、目标 QC_HOLD 库位、原因和 requestKey：后端在 tenant 行锁 + 待检批次行锁下使原段 `quantity -= n`，插入新段 `quantity=n`，并记录 split 事件；**整笔收货分段合计保持恒定**，无采购收货、仓库库存台账和 Amazon 改动。更改库位同样要求原 bin 快照，错仓/归档/STORAGE 库位不允许接收待检分段。
- 质检之后分段被冻结，成为历史接收阶段来源信息。若未来要把已放行批次拆成独立可售货位，必须另建合格品专属货位库存分录及正/负源证据，不可直接把原 gross-received pieces 等同可用库存。
- 仓库创建与真实收货批次建立在原 `@Transactional` 事务中同时保存默认 QC-HOLD 和 ROOT；若缺少必需的 QC-HOLD 库位，真实收货事务失败回滚，不能留下没有分段归属的新收货。

## 不自动补货的供应可见性

GET `/api/v1/purchasing/lot-locations/warehouses/{warehouseId}/pressure` 单独计算 SKU：
- `usableUnits`：V39–V46 签名库存流水实际可用库存余额（含独立 V46 QC_RELEASE，减去历史 V44 QC_QUARANTINE 和 V40 出库）。
- `pendingQcUnits`：该仓库该 SKU V46/V47 `PENDING_QC` 收货原始数量，尚不可用。
- `acceptedAwaitingReleaseUnits`：已 INSPECTED、但尚未有另一位 Owner 放行的合格数量，仍不可用。
- `declaredAsnInTransitUnits`：供应商人工申报 SHIPPED 且 PO 仍可收货的 ASN 数量减去真正关联 ASN 的累计已收货件数；**不能理解为承运商跟踪确认**。

当前只对**有 V46+ 批次的 SKU** 返回此细分表格；不替换 V42 基于已放行本地库存流水的补货计划，也不会把待检或 ASN 申报数量自动当作库存或悄悄转采购单。仅作为人工审核库存压力、减少重复采购的上下文。

## API

Owner Session 自动推导租户和操作者；所有 POST 使用 CSRF。

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/api/v1/purchasing/lot-locations/warehouses/{warehouseId}/bins` | 查看当前仓库所有库位 |
| POST | `/api/v1/purchasing/lot-locations/warehouses/{warehouseId}/bins` | `code,purpose` 创建库位 |
| GET | `/api/v1/purchasing/lot-locations/receipts/{receiptId}/pieces` | 查看真实收货分段，显示数量总和 |
| POST | `/api/v1/purchasing/lot-locations/receipts/{receiptId}/split` | `requestKey,pieceId,quantity,pieceCode,destinationBinId,reason` |
| POST | `/api/v1/purchasing/lot-locations/receipts/{receiptId}/move` | `requestKey,pieceId,expectedBinId,destinationBinId,reason` |
| GET | `/api/v1/purchasing/lot-locations/warehouses/{warehouseId}/pressure` | 分别列出可用、待检、待放行、申报在途数量 |

React Owner → 采购与供应商 → V47 待检批次拆分与库位：先选择仓库，查看/创建库位，选择正在待检的真实收货批次及其中一段，明确拆分数量或来源/目标库位，记录原因后确认。页面展示分段总数与原始真实收货数量供对照。供应压力是只读，不自动生成采购或 Amazon 货件。

## 质量与遗留验收

新增 Java 单测校验不合法输入、不能取走整段全部数量、目标库位必须是同仓待检区、数量扣加、独立事件且绝不生成 stock movement、错租户/错仓库、只读待检压力；更新仓库真实收货测试确保创建 ROOT 和事件而非可用库存。Vitest 检查 Zod schema、CSRF、幂等请求和压力展示。

仍需 **PostgreSQL 18 真正 Flyway 迁移/Compose**、跨租户 HTTP 权限、并发锁争用与多用户浏览器完整链路验证；Java/React CI 不能代替上述验收。后续可扩展正式批次/序列号层级的可售库存、质检批次拆分与独立放行、FEFO/先到先出库存履历。真实 Amazon 授权仍由项目 Owner 在本地所有功能完成后自行进行。
