# OPS-009 · V69 Marketplace 运营规则配置与站内待办

**实现：2026-11-01；产品阶段 V69，Flyway 迁移 V66。** 复用 V10/V67 的 \`ops_finding\`、扫描器、Spring Security Session/CSRF、PostgreSQL 租户级事务行锁与 TanStack Query；不新增消息队列、Scheduler、通知通道。

## 功能范围

- 每个 **Tenant + Seller Marketplace + 规则** 允许独立开启或关闭：
  \`UNMAPPED_LISTING\`、\`INVENTORY_UNKNOWN\`、\`LOW_STOCK\`、\`REFUND_REVIEW\`、\`FBA_INBOUND_REVIEW\`、\`ADS_REPORT_REVIEW\`。
- 未配置时六条规则均默认启用；\`LOW_STOCK\` 默认阈值仍由 \`MERCHIVRA_OPS_LOW_STOCK_THRESHOLD\` 设置（合法范围 0–10000，默认 3）。配置后各 Marketplace 之间**不互相影响**。未知/陈旧/部分观测缺失不会被当作 0；\`LOW_STOCK\` 仅适用于 \`OBSERVED\` 且数量非 NULL。
- Owner 通过 React 运营工作台打开规则表，更改开关与阈值后，点击已有“立即巡检”，才依据最新配置刷新队列。禁用规则不会删业务 Listing/订单/FBA/Ads，也不会立即清理待办；下一次巡检将该 Marketplace 下不再匹配的旧待办置为 \`CLEARED\`。重新启用且来源仍匹配时复用原唯一 Finding，恢复 \`OPEN\`。
- **并发与审计**：配置写入和扫描使用同一租户 PostgreSQL \`FOR UPDATE\` 行锁；\`expectedVersion\` 检测其他 Owner 的并发修改，版本不匹配返回 409。重复提交完全相同配置不额外增加版本或重复审计。每次有效配置变更记录到 \`ops_rule_setting_audit\`（Actor、旧/新状态、阈值、版本、时间），保留最近 100 条只读。
- 全部接口从服务端 Session 获取当前租户和 Owner，不接受浏览器 \`tenantId\`；站点属于其他租户时返回 404。Post 由 Spring Security CSRF 拦截。
- **通知**：本阶段“提醒”仍为工作台内的持久待办与审计，不自动发送邮件、Slack、钉钉、企业微信或浏览器 Push；任何未来外部通知都须有收件人/订阅/脱敏/重试/撤销策略，不能根据未核验 Mock 内容自动推送。

## API

\`GET /api/v1/operations/marketplaces/{marketplaceId}/rules\` 返回六个有效配置；缺省版本 \`0\` 表示部署默认值。

\`POST /api/v1/operations/marketplaces/{marketplaceId}/rules/{ruleCode}\` 请求：
\`\`\`json
{"enabled":true,"lowStockThreshold":4,"expectedVersion":0}
\`\`\`
只有 LOW_STOCK 需要非空整数阈值，其余规则必须省略或使用 null；版本不匹配 409。响应返回有效的 \`version\` 和 \`updatedAt\`，不会执行扫描。

\`GET /api/v1/operations/marketplaces/{marketplaceId}/rules/audit\` 最近 100 条只读变更审计。

前端必须使用 **实际运行 Spring MVC → springdoc OpenAPI → Orval 8** 生成的客户端。已复用 \`operations-v67\` group/file，新增三个 V69 operationId；\`npm run api:check\` 会检查生成文件漂移，不能手写静态 OpenAPI 冒充真实契约。

## 边界和验收

\`scripts/smoke_ops_v69.py\` 在隔离 PostgreSQL 18 + Spring Boot、Seller OAuth/Ads 远程默认关闭的环境中，模拟两店铺及来源：
- 未登录 401、错误站点 404、非法规则/阈值 400、旧版本写入 409；
- 六条规则的默认值、两店铺开关隔离、禁用下一轮清除旧 Finding、重新启用恢复 OPEN；
- 阈值变化、同版本相同值幂等、审计写入数一致；
- 同时保留已有 V67 Session/CSRF、双 Owner 认领/暂缓/清除、PG18 原生 V68 恢复演练。

这些是本地虚构业务场景；不能证明真实 Amazon 授权、平台限制、外部通知送达、生产权限与性能就绪。
