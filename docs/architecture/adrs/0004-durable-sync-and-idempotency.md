# ADR-0004 · 以 PostgreSQL 持久 Stream 管理同步与幂等

- **状态：Proposed（G2，尚未签署）**
- 日期：2026-10-30
- 相关文档：[SYNC-002](../sync-stream-contract.md)、[ARCH-003](../consistency-boundaries.md)、[DATA-003](../physical-data-design.md)

## 背景
SP-API 分页可失效，同一更新可能重复获取，Worker 可能在页面写入后崩溃，财务事实不得重复计入。

## 决策候选
持久 `sync_stream` 表示稳定作用域/高水位；每次同步生成 `sync_run` 和多次 `sync_attempt`。Worker 通过 DB 租约 + fencing epoch 防并发，同事务提交事实记录与 pageCommit。Token 失效按安全窗口重扫，由业务键去重实现逻辑“精确一次效果”（不声称上游保证一次投递）。

## 备选
- 无状态时间窗轮询：最简单，但审计/恢复困难。
- Redis 队列作为唯一检查点：丢数据/恢复风险和事务一致性复杂。
- 提前引入 MQ + outbox：更易扩展，但初版运维与调试成本高。

## 风险和退出条件
需用模拟 Worker 崩溃/租约失效、重复页、分页 token 过期验证；不同操作的增量策略单独定义。金融事件唯一键需要经实际脱敏样例核对，缺少可靠 ID 时必须进入待判定池。

**G2 不得标记 Accepted，直到业务范围、DB 约束和 G2-AC-05～11 设计/测试路径得到技术负责人批准。**
