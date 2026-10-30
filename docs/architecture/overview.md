# ARCH-001 · 系统总体架构（C4 思路）

**状态：Proposed**｜架构风格：模块化单体 + 异步任务 Worker（逻辑分离、初期可同一进程/部署）

## 1. 系统上下文（C4 / L1）

```mermaid
flowchart LR
User["卖家 / 运营 / 财务"] --> ERP["Merchivra ERP"]
Maint["开源贡献者"] --> ERP
ERP --> Amazon["Amazon SP-API"]
ERP -.未来独立集成.-> Ads["Amazon Ads API"]
ERP --> Email["可选邮件/告警服务"]
ERP --> Store["自有对象存储"]
```

范围：卖家拥有 Merchivra 的运行环境；Amazon 是第三方数据系统；Ads API 不是 SP-API 自动授权能力。

## 2. 容器图（C4 / L2）

```mermaid
flowchart TB
Browser["浏览器"] --> Web["Web / Vue 3 + TypeScript"]
Web --> API["API / Spring Boot 4.1"]
API --> Identity["授权/租户上下文"]
API --> Domain["领域模块"]
Scheduler["定时触发"] --> Worker["同步 Worker"]
Worker --> Domain
Worker --> Adapter["Marketplace Gateway + Amazon Adapter"]
Adapter --> SPAPI["Amazon SP-API"]
Domain --> PG[("PostgreSQL")]
Domain --> Redis[("Redis（预留缓存）")]
Domain --> Files[("S3-compatible 对象存储（按需）")]
Worker --> PG
```

将同步 Worker 视为单独的逻辑运行单元；MVP 可与 API 共享同一 Spring Boot 工程与数据库，不预设 Kubernetes 或 RabbitMQ。

## 3. 约束与边界

- 已实施的基础技术版本：**Java 25 LTS、Spring Boot 4.1.2（Spring Security 7 / Jackson 3）、Maven、PostgreSQL 17、Flyway、Vue 3.5 / TypeScript 6.0.3 / Vite 8.3.5 / Node 24**、Docker Compose。架构业务选择仍待 G1/G2 正式确认。
- 领域模型不可直接依赖 Amazon HTTP DTO，必须经过适配器标准化。
- Controller 只做协议与输入验证；应用服务编排；领域处理规则；存储层落实租户与事务约束。
- 命令/查询分离是代码组织原则而非必须实现 CQRS 基建。
- 交易事实与同步 Checkpoint 必须有持久化策略；短期缓存不可作为唯一真相来源。
- 不接受明文生产凭证或客户敏感数据写入仓库/CI。

## 3.1 领域事实、状态与同步契约

订单、退款与财务状态正交，见 [DOM-001](domain-state-model.md)；平台来源与投影对账见 [DATA-002](source-reconciliation.md)；一次任务的运行不能取代持久同步流，见 [SYNC-002](sync-stream-contract.md)。首次管理员与租户边界见 [IAM-001](tenant-bootstrap.md)。

## 3.2 G2 约束级架构候选

概念模型与实际数据库约束之间的差距详见 [DATA-003](physical-data-design.md)。事务、重试与租约 fencing 详见 [ARCH-003](consistency-boundaries.md)。服务器端账户/租户授权执行见 [SEC-002](../security/tenant-enforcement.md)；完整可机读接口初稿见 [API-002](../api/README.md)。这些文件为候选方案，业务和技术签署后才可作为正式实现契约。

## 4. API 边界

前端 API 默认 `/api/v1`（规划约定）；UI 不直接调用 Amazon；后台任务不得绕过授权和租户过滤。具体资源/错误契约见 [模块边界](modules.md)。

## 5. 关键架构决策

- [ADR-0001 模块化单体](adrs/0001-modular-monolith.md)
- [ADR-0002 多租户模型](adrs/0002-tenancy.md)
- [ADR-0003 事件与财务账本](adrs/0003-finance-ledger.md)

详细数据关系见 [ER 图](data-model.md)，同步机制见 [同步引擎](sync-engine.md)，网络部署见 [部署图](deployment.md)。
