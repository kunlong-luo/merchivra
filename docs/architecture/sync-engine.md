# SYNC-001 · Amazon 数据同步引擎设计

**设计目标：可恢复 + 幂等 + 可观测 + 平台限流友好。** 不依赖具体 Amazon 版本，按接口适配器隔离。

## 1. 时序图

```mermaid
sequenceDiagram
autonumber
participant T as Scheduler / User
participant J as Job Store (PG)
participant W as Sync Worker
participant L as Quota Limiter
participant A as Amazon Adapter
participant D as Domain DB

T->>J: 建立 job(scope, type, window)
W->>J: 租约领取待运行任务
J-->>W: checkpoint + attempt
loop 分页或时间窗口
W->>L: 请求配额
L-->>W: 允许执行
W->>A: Fetch page / cursor
alt success
A-->>W: 标准化数据 + next page
W->>D: 事务内幂等 upsert
D-->>W: 提交成功
W->>J: 安全推进 checkpoint
else 429 / 5xx / transient
A-->>W: 错误类型与 Retry-After(如有)
W->>J: 记录退避与下次执行
else 401 / 403 / validation
A-->>W: 非瞬时错误
W->>J: 停止任务并提示授权/数据修复
end
end
W->>J: 任务完成 + 统计
```

**事务边界**：数据提交后再推进游标，或在同事务内提交数据与检查点；绝不先提交检查点再写业务数据。

## 2. Job 生命周期

```mermaid
stateDiagram-v2
[*] --> QUEUED
QUEUED --> RUNNING: 获取租约
RUNNING --> SUCCEEDED: 所有分页已提交
RUNNING --> WAITING_RETRY: 可重试错误
WAITING_RETRY --> RUNNING: 退避到期
RUNNING --> FAILED: 权限或永久错误
RUNNING --> PAUSED: 用户停止/配额保护
PAUSED --> QUEUED: 恢复
FAILED --> QUEUED: 人工修复后重放
SUCCEEDED --> [*]
```

## 3. 幂等与回填约束

- 每条业务事件都必须建立稳定幂等键：`tenant + source + account + marketplace + external-id + event-type(如适用)`。
- 支持更新/更正事件；对同一订单采用来源更新时间及有定义的版本选择策略。
- 时间窗口同步采用**有重叠的安全回看区间**，重叠数据依靠幂等去重。
- 分页 Token 可能过期：按该操作版本的文档处理；Token 无效时从最近安全时间点补偿，而非永久放弃。
- 所有 429、5xx、超时与网络重置分类处理，支持指数退避、抖动和操作限流；不硬编码全平台一个固定 QPS。
- 失败任务必须能展示 account/marketplace/operation、脱敏错误、最后成功检查点、重试次数。
- 每次同步记录 sourceUpdatedAt、startedAt、completedAt、recordsSeen/Upserted、lag 与 traceId。

## 4. 特别注意

- 不能把 Amazon 平台通知当作唯一真相来源：通知驱动增量与定期补偿需要配合。
- Finances 的交易发生、发布和结算存在时间差；用独立流水并定期重对账。
- FBA 快照应保存观测时间与数据来源；不能将缺失数据解释为 0。
- 工作队列先采用 PostgreSQL 持久化任务，只有性能瓶颈经测量后再引入消息总线。

## 5. 失败注入验收

同一批数据三次重放；第三页中断；Token 过期；429 限流；401 授权失效；一条非法商品数据；跨租户任务被错误配置；Worker 崩溃后租约回收；竞态同作用域提交。这些测试必须对应 [QA 文档](../requirements/acceptance.md)。
