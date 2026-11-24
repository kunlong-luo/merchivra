# DRAW-001 · 架构与业务图纸索引（可编辑 Mermaid）

**历史设计图纸登记。** 此索引保留 D-01–D-36 编号及原始评审边界；计划图不是现行待办，实际状态见 [当前事实](../current-status.md)，现行任务见 [P0 计划](../roadmap/current-plan.md)。

| 图号 | 图纸名称 / 类型 | 所在文档 | 阶段 | 评审重点 |
|---|---|---|---|---|
| D-01 | 系统上下文图 (C4-L1) | [总体架构](overview.md) | P0 | ERP 与 Amazon/用户边界 |
| D-02 | 应用容器图 (C4-L2) | [总体架构](overview.md) | P0 | Web/API/Worker/DB |
| D-03 | 业务模块依赖图 | [模块划分](modules.md) | P0 | 模块解耦与接口位置 |
| D-04 | 概念 ER 图 | [数据模型](data-model.md) | P0 | 主键、作用域、关系 |
| D-05 | 私有店铺接入业务流程图 | [业务流程](../product/core-flows.md) | P0 | 权限、失败处理 |
| D-06 | Listing/SKU 映射业务流程图 | [业务流程](../product/core-flows.md) | P0 | 避免错误映射 |
| D-07 | 订单履约/退款/结算平行状态图 | [业务流程](../product/core-flows.md) | P0 | 平台状态与 ERP 状态 |
| D-08 | FBA 库存同步/对账流程图 | [业务流程](../product/core-flows.md) | P0 | 快照非流水 |
| D-09 | 财务业务对账流程图 | [业务流程](../product/core-flows.md) | P0 | 估算/已入账/结算 |
| D-10 | 同步引擎时序图 | [同步引擎](sync-engine.md) | P0 | 分页/事务/幂等 |
| D-11 | 同步任务状态机 | [同步引擎](sync-engine.md) | P0 | 失败与重试 |
| D-12 | Amazon 授权逻辑时序图 | [SP-API](../integrations/amazon-sp-api.md) | P0 | 敏感凭证与外部权限 |
| D-13 | 安全信任边界图 | [安全设计](../security/security-design.md) | P0 | PII、token、隔离 |
| D-14 | 本地部署图 | [部署设计](deployment.md) | P0 | Mock / localhost |
| D-15 | 生产参考部署图 | [部署设计](deployment.md) | P0 | TLS、私网、备份 |
| D-16 | UI 导航结构图 | [交互设计](../design/navigation-and-page-states.md) | P0 | 页面完整性 |
| D-17 | 财务计算时序图 | [财务模型](finance-model.md) | P0 | 数据来源可追踪 |

## 第二轮新增的实际图纸

| 图号 | 图纸名称 / 类型 | 所在文档 | 阶段 | 评审重点 |
|---|---|---|---|---|
| D-24 | 履约/退款/财务平行状态图 | [DOM-001](domain-state-model.md) | P0 | 三轴状态不混淆 |
| D-25 | 平台数据来源与投影图 | [DATA-002](source-reconciliation.md) | P0 | 三层数据与可追溯 |
| D-26 | 事实入库到差异回流闭环图 | [DATA-002](source-reconciliation.md) | P0 | 差异处理与重算 |
| D-27 | 持久同步流 / Worker 租约时序图 | [SYNC-002](sync-stream-contract.md) | P0 | 任务重启/Token 过期 |
| D-28 | 首次管理员安全初始化图 | [IAM-001](tenant-bootstrap.md) | P0 | 无默认账号及重复引导 |

> D-18～D-23 仍为**计划图纸**；D-24～D-28 是第二轮新增的可编辑 Mermaid。图号按创建批次分配，不代表优先级。

## 第三轮 G1 业务图纸

| 图号 | 图纸名称 / 类型 | 所在文档 | 阶段 | 评审重点 |
|---|---|---|---|---|
| D-29 | G1 店铺至数据修复业务闭环 | [G1-001](../product/g1-review-package.md) | G1 | 权限、映射、费用不完整时可恢复 |
| D-30 | FBA/FBM 订单和库存分流图 | [BIZ-002](../product/fulfillment-scope.md) | G1 | FBM 不推断 FBA 库存 |
| D-31 | SKU 映射与版本状态机 | [BIZ-003](../product/sku-listing-policy.md) | G1 | 冲突/待映射/生效 |
| D-32 | 费用分类与差异回流图 | [BIZ-005](../product/finance-rulebook.md) | G1 | 计算/完整度/重新分类 |

当时累计 26 张源图；未完成图号见末尾。

## 第四轮 G2 技术图纸

| 图号 | 图纸名称 / 类型 | 所在文档 | 阶段 | 评审重点 |
|---|---|---|---|---|
| D-33 | 物理数据关系和来源分层图 | [DATA-003](physical-data-design.md) | G2 | 复合外键/来源事实/投影 |
| D-34 | 来源事实入库与 pageCommit 原子性时序 | [ARCH-003](consistency-boundaries.md) | G2 | 事务与投影补偿 |
| D-35 | Stream 租约、重试和进度安全推进流程 | [ARCH-003](consistency-boundaries.md) | G2 | 旧 Worker fencing |
| D-36 | API/Worker 租户授权与数据库防线 | [SEC-002](../security/tenant-enforcement.md) | G2 | 跨租户安全隔离 |

当时累计 30 张源图。

## 历史计划图纸（非现行 P0）

| 图号 | 计划图纸 | 何时制作 | 依赖 |
|---|---|---|---|
| D-18 | 详细物理 ERD / 表约束图 | G2 后 | 站点与订单口径、字段冻结 |
| D-19 | OAuth 具体回调/刷新 Token 时序图 | 开发者资格确认后 | 注册模式与获批权限 |
| D-20 | 各 API 用量/重试操作图 | SP-API 操作确认后 | 实际版本与 usage plan |
| D-21 | FBA 与国内仓供应链流转图 | P1 采购仓库需求冻结后 | 仓库/货件规则 |
| D-22 | 正式高保真界面图与页面状态图 | 角色和 PRD 确认后 | 设计系统/用户反馈 |
| D-23 | 完整费用分摊/资金结算核对图 | 财务负责人确认后 | 费用项目与汇率策略 |

## 图纸评审规则

本目录设计阶段现有 30 张 Mermaid 源图（第一轮 17 张、第二轮新增 5 张、第三轮新增 4 张、第四轮新增 4 张）；实际 GitHub 渲染和图纸质量仍须在评审时检查，不把提交视为签收。

每张图标注名称、版本、业务前提、维护者和对应需求 ID；图与文字冲突时以已签署的 ADR + 最新 PRD 为准。不要把尚未审核的 Mermaid 图视为生产架构。
