# V56 · Amazon SP-API 原子配额、批准的 Burst 与调度监控

## 边界
本阶段不需要 Seller 授权，也不调用真实 Amazon。Java 25、Spring Boot 4.1.2、Gradle 9.2.1、PostgreSQL 18、Redis 8 保持不变。

已审核的官方 SDK 只读入口：SEARCH_ORDERS、SEARCH_LISTINGS、GET_FBA_INVENTORY、LIST_TRANSACTIONS。分别通过 WithHttpInfo 获取 Amazon 响应头，先通过 Redis 操作级配额，再调用 SDK；操作与 SDK 类有强制映射。未接入的 SDK 方法仅维持原有保守 SDK 构建安全阀，尚不具备全面方法级配额。

## 原子 Redis GCRA
- 同一请求在 Redis Lua 中原子检查 429 fence、读取有效的动态速率和 GCRA 虚拟到达时间，用 Redis TIME 而非进程时钟协调多个实例。
- 操作键包含区域、操作和服务器端 grant 的 SHA-256 指纹，不明文保存凭证、Seller ID 或订单。
- x-amzn-RateLimit-Limit 不保证出现，不代表 Amazon 所有共享配额，更不代表 burst。有效 rate 学习结果保存 24h，间隔限制为 250ms–1h；缺失时回退至配置冷却时间（默认 2000ms），容量固定 1。
- 返回 0 表示已消耗许可，正整数表示建议等待毫秒；Redis 错误或空响应拒绝 Amazon 请求，绝不在请求线程 sleep 或自旋。
- Redis 429 fence 以 Lua 单调延长，不得被短 Retry-After 缩短。

## 显式批准的 Burst（默认关闭）
- 环境变量示例：MERCHIVRA_SPAPI_APPROVED_BURSTS=SEARCH_ORDERS=2,GET_FBA_INVENTORY=3
- 只允许已登记的只读操作，整数容量范围 2–10；重复、未知、写操作及无效表达式使启动失败。
- 只有先学习到有效 rate，手工批准的 burst 才生效；否则 capacity=1。必须先通过实际 usage-plan 证据确认，不得根据 rate 头自行猜测 Amazon burst。
- 审核通过的 typed read 直接使用操作级许可，避免额外消耗 SDK 家族创建时的固定冷却；其他 SDK 代码仍保留原安全阀。

## 后台任务与低敏指标
- Orders、Catalog、Finances 使用 PostgreSQL 持久化重试时间和租约 epoch，429/临时错误按 Retry-After + 指数退避 + jitter 排队，最多 8 次，之后暂停。
- 待处理任务按租户交错选择，避免一个租户持续占据所有执行机会。
- Micrometer 内部指标 merchivra.spapi.admission（operation/result）以及 merchivra.spapi.response（operation/status 类别）。标签不包含用户标识、凭证、SKU、订单内容。
- 默认为防止跨租户统计泄露，不公开 /actuator/metrics；生产运维需在受保护边界另行配置采集，tenant-safe 界面另行实施。

## 验证
- cd apps/server && ./gradlew test jacocoTestReport
- CI Redis 8 / Spring Session integration smoke 会运行 scripts/smoke_redis_quota.py，使用随机临时键和真实 Redis Lua，绝不执行 FLUSHDB。
- CI 同时校验 PostgreSQL 18 Flyway、React 19 和 Mock full-chain；最终仍需维护者本地授权后做 Amazon 实况验收。

## 后续待办
1. 为其它经过角色审查的官方 SDK 操作逐个加入 operation-level wrapper，禁止将 SDK builder gate 宣称为全部方法的动态保护。
2. 研究 Amazon 共享应用级与跨接口 usage-plan 联合保护，目前区域+操作+grant 指纹不涵盖所有共用限制。
3. 引入私有运维告警及 tenant-safe 后台任务监控，不能向普通用户公开跨租户计数。
