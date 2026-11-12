# V68 · 发布前安全、备份/恢复、性能与验收手册

> **现行执行策略（V108 / 2026-11-08）**：本手册列出了仍然有效的生产安全与恢复阻塞项，但 V68 的“CI 自动运行”属于当时的记录；现在 **普通 PR 仅跑快速 CI**，完整 PostgreSQL 18/Flyway、一次性 Mock 恢复/HTTP 安全、Chromium 和容器链由 [MVP milestone full validation](https://github.com/kunlong-luo/merchivra/actions/workflows/milestone-validation.yml) 集中运行，或按风险手动触发。首次 [V108 集中合成数据基线](https://github.com/kunlong-luo/merchivra/actions/runs/42448160593) 已通过，但不等于真实数据/生产安全认证。必须结合 [V108 现行上线计划](../roadmap/mvp-freeze-release-v108.md) 阅读，G1/G2/G3 仍未批准。

> **V143 现行生产放行前置检查**：参阅 [G1/G2/G3 配置与运营证据 fail-closed 预检](../operations/v143-production-release-preflight.md)。V142 合成 PITR+内网告警通过不等于真实环境的外部签章、PITR 和真实值班通知完成。预检不会授予生产发布许可。

**2026-11-01，产品里程碑 V68；不是新的 Flyway 版本。** 本项目当前运行 Java 25 / Spring Boot 4.1.2 / Gradle Wrapper / PostgreSQL 18 / Node 24 / React 19。下文严格区分代码、CI 隔离环境演练、真实授权联调与生产批准。

## 1. 已有隔离测试能力（现按里程碑集中执行）

| 范围 | 已实施检查 | 明确不覆盖 |
|---|---|---|
| 数据库 | PG18 下实际 Spring Boot Flyway 启动，V1–V65 迁移完成；再用 PostgreSQL 官方 `pg_dump -Fc` 建立**一次性模拟数据**备份，`pg_restore --exit-on-error --single-transaction` 恢复到另一空数据库；比对所有 public 表的行数和 JSONB 内容 MD5、Flyway 历史、约束及索引总数，以及 V65/V64 关键索引 | 线上自动备份/PITR、WAL 归档、异地加密、RPO/RTO、生产量级恢复时长 |
| 权限/会话 | 使用已有 Spring Security Test + MockMvc 检查新运营/FBA/Ads/财务端点匿名拒绝和已认证请求无 CSRF 拒绝。真实本机 HTTP 检查 401、403、Session `HttpOnly` / `SameSite=Strict`、Security 响应头、注销后会话失效 | 第三方渗透、Browser/Proxy TLS 链条、正式 RBAC/IAM 签审、多节点会话失效竞态、OAuth 安全渗透 |
| 部署 | 通过 Docker Compose 解析后的 JSON 检验**默认**生产 overlay：Demo off、Seller 出站/写 off、HTTPS Cookie、PG18、数据库/Redis 无公开端口、Redis opt-in/noeviction、server/web 仅本机绑定 | 实际 HTTPS 证书、入口反向代理、安全组、防火墙、网络出站 ACL、外部 secrets 管理 |
| 前端 | TypeScript/Vitest、从真实 MVC 导出的 Orval 客户端漂移检查、Vite build、可选 Chromium 虚构布局冒烟 | 读屏器、WebKit/Firefox、真实卖家数据体验和 WCAG 全量验证 |
| 性能 | 在同一 CI runner 上针对已认证本机 Operations API 进行 35 次串行只读请求，要求 p95 低于 2500ms；此门槛仅捕捉显著慢响应和不可用 | 并发压测、数据库数百万行、长期稳定性、正式 SLA、负载容量、真实 Amazon 429/重试 |

历史 V68 检查（及后续 V108 集中验收）优先复用成熟组件：Spring Security Test/MockMvc、Spring Boot Actuator、PostgreSQL 原生数据库工具、Flyway、Docker Compose、GitHub Actions。此阶段**不新造** backup engine、不引入第二种 Session、消息队列或 Job scheduler。需要真实高强度安全审查与浏览器自动化后再选择成熟工具（Testcontainers、Playwright、OWASP Dependency-Check/OSV-Scanner 等），并由锁文件/CI 证明版本兼容。

## 2. 一次性 PG18 备份恢复演练

CI 脚本 `scripts/smoke_pg_restore_v68.sh` 必须满足：
- 显式 `MERCHIVRA_V68_DISPOSABLE_ONLY=true`；
- `DB_HOST=127.0.0.1`、`DB_NAME=merchivra`、`DB_USER=merchivra`，仅当前 CI 隔离 PG18；
- `MERCHIVRA_DEMO_ENABLED=true`、`MERCHIVRA_SPAPI_ENABLED=false`、`MERCHIVRA_SPAPI_OAUTH_ENABLED=false`。
不满足则拒绝运行。恢复数据库使用独立名称，脚本结束时自动删除；备份文件只存在 runner 随机临时目录，不作为 CI Artifact 上传，更不提交 Git。

在真实系统中，**不得把这份 CI 脚本作为生产备份调度器**。生产备份应由 DBA/运维在独立身份权限下使用 PostgreSQL 官方工具或受信托管备份，并包含：

1. 制定并签署 RPO、RTO、保留天数、数据驻留与加密密钥责任；当前这些指标没有实测数值。
2. 定期完整基线备份、WAL/PITR（如有要求）、离站/跨可用区加密副本，定期模拟丢失凭据、整库错误删除和数据校验失败。
3. 密钥管理外置；备份内容可能包含密码散列、授权密文、商业订单、用户标识及业务记录，备份也应视为敏感数据，绝不可写入日志、仓库或公共 Artifact。
4. 恢复到**新的、隔离的 PG18 实例/数据库**，验 Flyway checksum/迁移历史、租户/Marketplace 隔离、不可变成本、订单来源、财务数据及审计表。恢复后只在新环境手动启动应用验证；严禁默认覆盖线上。
5. 有双人批准的切换/回退方案、DNS/入口连通、冻结后台 Worker/自动巡检、校验可用性、复核权限以及事件复盘。
6. CI 的 MD5/JSONB 是对固定测试快照的逻辑一致性比对；它不验证底层存储无位腐蚀（需依赖 PG 自身和受信存储校验），也不是密码学签名或不可抵赖证明。

## 3. 正式上线前阻塞项（必须完成，不能因 CI 变绿而豁免）

- **真实授权**：SP-API Seller OAuth/角色与独立 Amazon Ads OAuth 都是关闭状态；不向公共 CI 注入真实 Token、买家信息、Seller 密钥；所有核心代码完成后由 Owner 在本地完成可信授权测试。完整凭证 Vault/KMS、凭证撤销、账号隔离/限流/429、出站 ACL 待验证。
- **生产 IAM**：管理员 MFA/SSO 取舍、操作权限矩阵、异常审计留存、会话轮换、用户停用后的多节点失效与最小数据库角色权限分离，尚需安全审查。
- **真正容灾**：生产备份工具/加密/留存/WAL/PITR 未安装，且 RPO/RTO 尚无正式签署及量化数据。Redis 8 可选，配置 noeviction；不能以 Redis Session 当作持久业务事实。
- **性能与可靠性**：实际 tenant/SKU/订单规模、并发 Owner+Worker、租约冲突、失效恢复、PostgreSQL 长事务/索引、Nginx 连接数、真正客户端终端质量、真实 API 限流还需专项压测。35 次本机读取仅是 CI 冒烟。
- **部署边界**：此项目的 `compose.production.yaml` 仅设置安全环境 overlay，**并不提供 TLS/外部入口**；不要把端口暴露公网。Session Secure 必须 true，Demo/OAuth/自动 Remote Writes 默认 off；正式服务需经独立审计的 HTTPS ingress/证书/安全组。
- **业务批准**：G1/G2/G3 决策尚未签署，财务与利润的跨来源结算/银行到账仍需受信数据和人工验收。

## 4. 发布/回滚核查

发布前记录：目标 Git commit / Gradle Wrapper checksum / Node lockfile hash / PG18 主版本 / Flyway 最新成功版本 / 备份唯一 ID / CI URL / 审批人 / 恢复验证环境 / 应用流量切换点。

遇到 Flyway 新版本与旧应用模型不兼容时**不能回滚 SQL 迁移文件**；必须使用显式向前修复版本/兼容期，或由 DBA 从隔离恢复点整库恢复并审批数据损失边界。绝不能在真实库运行 `flyway clean`、`dropdb` 或 CI 的一次性恢复脚本。

## 5. 运行方法（仅开发环境）

- **普通 PR / main push**：`./apps/server/gradlew -p apps/server test`、React 类型/Orval/Vitest/Vite、Next 类型检查、Compose 静态安全配置；**不会**自动执行 PG18 Mock HTTP、备份恢复或完整 Chromium/Compose。
- **集中验收**：在 GitHub Actions 运行 `milestone-validation.yml`（main 上人工触发），整合完整 Java/Next/PG18 Mock HTTP、安全/恢复、双前端 Chromium 和三容器链。仅在一次性环境测试，严禁连接真实卖家/银行或生产 PG。
- 部署配置静态核验：`docker compose --env-file .env.example -f deploy/compose.yaml -f deploy/compose.production.yaml --profile optional-cache config --format json | python3 scripts/check_release_config_v68.py`。
- 数据库恢复脚本有强制一次性环境守卫；如果没有匹配的本机 Mock PG18，就不执行。

后续以 GitHub Actions 实际成功结果为准；不能把“功能已提交”或“演练通过”写成“已生产上线”。
