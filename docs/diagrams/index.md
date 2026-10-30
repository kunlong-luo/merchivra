# DRAW-001 · 图纸目录（可编辑 Mermaid）

| 图号 | 图纸名称 / 类型 | 所在文档 | 阶段 | 评审重点 |
|---|---|---|---|---|
| D-01 | 系统上下文图 (C4-L1) | [总体架构](../architecture/overview.md) | P0 | ERP 与 Amazon/用户边界 |
| D-02 | 应用容器图 (C4-L2) | [总体架构](../architecture/overview.md) | P0 | Web/API/Worker/DB |
| D-03 | 业务模块依赖图 | [模块划分](../architecture/modules.md) | P0 | 模块解耦与接口位置 |
| D-04 | 概念 ER 图 | [数据模型](../architecture/data-model.md) | P0 | 主键、作用域、关系 |
| D-05 | 私有店铺接入业务流程图 | [业务流程](../processes/core-flows.md) | P0 | 权限、失败处理 |
| D-06 | Listing/SKU 映射业务流程图 | [业务流程](../processes/core-flows.md) | P0 | 避免错误映射 |
| D-07 | 订单状态图 | [业务流程](../processes/core-flows.md) | P0 | 平台状态与 ERP 状态 |
| D-08 | FBA 库存同步/对账流程图 | [业务流程](../processes/core-flows.md) | P0 | 快照非流水 |
| D-09 | 财务业务对账流程图 | [业务流程](../processes/core-flows.md) | P0 | 估算/已入账/结算 |
| D-10 | 同步引擎时序图 | [同步引擎](../architecture/sync-engine.md) | P0 | 分页/事务/幂等 |
| D-11 | 同步任务状态机 | [同步引擎](../architecture/sync-engine.md) | P0 | 失败与重试 |
| D-12 | Amazon 授权逻辑时序图 | [SP-API](../integrations/amazon-sp-api.md) | P0 | 敏感凭证与外部权限 |
| D-13 | 安全信任边界图 | [安全设计](../security/security-design.md) | P0 | PII、token、隔离 |
| D-14 | 本地部署图 | [部署设计](../architecture/deployment.md) | P0 | Mock / localhost |
| D-15 | 生产参考部署图 | [部署设计](../architecture/deployment.md) | P0 | TLS、私网、备份 |
| D-16 | UI 导航结构图 | [交互设计](../ux/navigation.md) | P0 | 页面完整性 |
| D-17 | 财务计算时序图 | [财务模型](../architecture/finance-model.md) | P0 | 数据来源可追踪 |

## 尚需补齐的图纸

| 图号 | 计划图纸 | 何时制作 | 依赖 |
|---|---|---|---|
| D-18 | 详细物理 ERD / 表约束图 | G2 后 | 站点与订单口径、字段冻结 |
| D-19 | OAuth 具体回调/刷新 Token 时序图 | 开发者资格确认后 | 注册模式与获批权限 |
| D-20 | 各 API 用量/重试操作图 | SP-API 操作确认后 | 实际版本与 usage plan |
| D-21 | FBA 与国内仓供应链流转图 | P1 采购仓库需求冻结后 | 仓库/货件规则 |
| D-22 | 正式高保真界面图与页面状态图 | 角色和 PRD 确认后 | 设计系统/用户反馈 |
| D-23 | 完整费用分摊/资金结算核对图 | 财务负责人确认后 | 费用项目与汇率策略 |

## 图纸评审规则

每张图标注名称、版本、业务前提、维护者和对应需求 ID；图与文字冲突时以已签署的 ADR + 最新 PRD 为准。不要把尚未审核的 Mermaid 图视为生产架构。
