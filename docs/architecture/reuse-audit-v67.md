# V67 · 成熟组件复用审查与运营异常闭环

更新：2026-11-01。适用：Java25 / Spring Boot 4.1.2 / Gradle 9 / PostgreSQL18 / React19 / TS6 / Node24。

## 原则

已有的 V10 \`ops_finding\` / Spring Scheduling / JDBC 事务、V32 财务只读异常诊断、V41 仓库告警、V65 FBA 货件和 V66 Ads 离线日报，必须**复用并互联**，不新建一个与 V10 冲突的任务引擎。外部卖家 OAuth、Ads OAuth、自动出站和远程写入继续关闭。先确认库的许可、版本/运行时兼容、维护情况和实际价值；没必要就不引入依赖。

## 复用清单（按实际价值排序）

| 分类 | 成熟组件 / 方案 | 现状 / 决策 | 避免重新造的功能 |
|---|---|---|---|
| HTTP API + 权限 | Spring Boot 4.1.2、Spring MVC、Spring Security 7 | **已在使用** | 路由、Session、CSRF、权限中间件 |
| Java DTO 校验 | Spring Boot 官方 \`spring-boot-starter-validation\` / Hibernate Validator / Jakarta Bean Validation | **V67 已引入**，\`@Valid\` + \`@NotBlank\`、\`@Size\`、\`@Min\`、\`@Max\` 校验暂缓请求；业务规则（15/60/240/1440、状态竞态）仍保留 Service/DB 校验 | 复杂的手写空值、长度、范围校验 |
| 数据与迁移 | Spring JDBC / HikariCP、PostgreSQL18、Flyway | **已在使用**；使用 \`FOR UPDATE\` 和唯一索引串行化、去重，不加另一套数据库 | 数据库连接池、迁移及跨实例同租户事务锁 |
| API 代码生成 | springdoc OpenAPI 3.x + Orval 8.44 + TanStack Query 5 + Zod4 | **V67 扩展**到全部运营待办 API；契约从实际 Spring MVC 导出，而非 DESIGN ONLY JSON，生成客户端不手改 | 手写 API 路径/HTTP/重复 DTO、获取状态、缓存失效及响应校验 |
| 自动巡检 | Spring Framework \`@Scheduled\`（Spring Boot 自带）+ PostgreSQL 事务 | **已在 V10 实施，V67 直接扩展**；定时器 \`MERCHIVRA_OPS_SCHEDULER_ENABLED=false\` 默认关闭 | 自研定时器、同租户多副本重复巡检 |
| 日志与健康 | Spring Boot Actuator / Micrometer、SLF4J | **已使用**基础健康和日志；未来先加 bounded domain counters、无凭证标签，再考虑观察平台 | 自研 metrics/health/log transport |
| 前端工作台 | React 19 + TanStack Query + Zod | **已在使用**；运营待办手工巡检、认领、暂缓、审计复用现有组件；不增加全局 store | 自造状态轮询、缓存和验证框架 |
| 后台数据表格 | TanStack Table（当前最新版 v9） | **候选**：当财务/订单明细需要服务端分页、列排序/筛选/列可见性时再引入；V67 100 条轻量表目前无需额外包 | 表格交互状态、排序/过滤/分页 |
| 多实例调度互斥 | ShedLock（2026 年 7.x） | **暂不引入**：V10/V67 扫描在已有 PostgreSQL tenant \`FOR UPDATE\` 下串行化，追加 ShedLock 会重复锁；遇到任务跨事务或高度频繁的独立计划再评估 | 分布式互斥 |
| 多计划/cron/错过重试 | Spring Boot Quartz Starter | **暂不引入**：当前只有部署时可选的固定间隔巡检，Quartz JDBC JobStore 会增加表和恢复策略。需租户级可编辑 cron 和 misfire 语义时考虑 | 重复实现持久化计划触发器 |
| 可靠事件 / 应用模块 | Spring Modulith | **待实际跨模块事件驱动需求**：将来需要独立的 outbox/事件重放时再评估；不为了拆分项目而先引入 | 手写跨模块事件持久化 |
| 大规模批处理 | Spring Batch | **暂不引入**：已有异步财务报表快照/分页 Worker 与其检查点协议，没必要平行新建 Job 元数据；真正规模化批量作业再比较迁移成本 | 复杂持久作业、分片、重试 |
| 外部 HTTP 失败策略 | Resilience4j + 官方 Amazon SP-API SDK | **待真实 Seller/Ads 启用时评估**：不能用普通自动重试代替单次来源幂等与 Amazon 限流；现在禁止远程调用 | 断路器、退避限流、失败恢复 |
| 浏览器端表单 | React Hook Form / TanStack Form + Zod | **候选**：复杂采购/大表单验证时引入而非到处写状态；本轮暂不额外加锁文件依赖 | Form state、字段级校验 |
| 通知 | 现有运营工作台内状态和审计 | **V67 只做站内待办**，不发送邮件/Slack/企业微信或自动推送；需明确定义接收人/偏好和订阅策略后再复用稳定通知通道 | 非授权发信和未审核的自动化 |
| 银行/广告来源 | 官方 Ads API、Seller SP-API SDK、银行受信渠道 | **仍关闭**，Mock/人工未核验来源不得冒充 Verified，更不能被当作到账/已扣费 | 官方 API DTO/权限模型 |

外部文档：
- Spring Boot Starter: https://docs.spring.io/spring-boot/reference/using/build-systems.html
- Spring Bean Validation: https://docs.spring.io/spring-boot/reference/io/validation.html
- Quartz: https://docs.spring.io/spring-boot/reference/io/quartz.html
- TanStack Table: https://tanstack.com/table/latest
- ShedLock: https://github.com/lukas-krecan/ShedLock

## V67 实际功能与验收

迁移：Flyway **V65**（产品阶段 V67；不要混称）。对 V10 \`ops_finding\` 增加：
- 规则 \`FBA_INBOUND_REVIEW\`：V65 本地货件 \`REVIEWING\`；\`ADS_REPORT_REVIEW\`：V66 报告 \`STAGED\`。\`MOCK\` / \`MANUAL_UNVERIFIED\` 在 V10 来源规范化为 \`MOCK\` / \`UNVERIFIED\`，绝不升级为 \`LIVE\`。
- 同一 Tenant+Marketplace+Rule+Source 不重复生成，来源消失后 \`CLEARED\`，条件再次出现才重新 OPEN。过去 DONE/已认领的记录不因重复扫描被强制恢复；已清除重开的待办释放旧认领/暂缓。
- Owner 认领给自己（不接受浏览器 owner ID）、仅本人释放；其他 Owner 的认领冲突为 409。15/60/240/1440 分钟暂缓、输入说明并保存审计；\`ACTIONABLE\` 过滤排除未到期的暂缓，\`ACTIVE\` 则仍可查看和恢复；自然到期无需调度器/异步唤醒。
- 不可变操作历史 \`ops_finding_attention_audit\`：CLAIMED、RELEASED、SNOOZED、UNSNOOZED，关联租户/记录/Actor，记录旧/新指派和提醒截止时间，最多显示最近 100 条；原 V10 状态审核历史继续独立保留。
- \`MERCHIVRA_OPS_SCHEDULER_ENABLED=false\` 默认关闭；Owner 可手动扫描。无 Amazon 读/写调用、无通知发送、无任务实际执行、无库存/财务写入。

**工程验证：** Gradle Java25 tests、React TS/Vitest/Orval 漂移、PG18 Flyway、实际 Springdoc 9 个接口以及 \`scripts/smoke_ops_v67.py\` 的 Mock HTTP Session/CSRF/双 Owner/幂等/冲突/自动消失用例。由 CI 运行记录确认结果，不代表真实 Amazon 联调、生产告警、长周期性能或权限验收。
