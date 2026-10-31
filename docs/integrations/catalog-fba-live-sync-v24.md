# V24 · Listing / FBA 正式只读分页同步

**开发状态：代码与离线 SDK 模型测试已加入，真实 Amazon 卖家联调和生产安全验收未完成。**

## 数据来源和权限

- Listing 使用官方 SP-API SDK `ListingsApi.searchListingsItems`（2021-08-01），只选择一个经数据库验证的 Marketplace 的 `summaries` 数据集，提取 Seller SKU、ASIN、FNSKU 与来源更新时间。缺失字段留 NULL，不改动内部 SKU 映射历史和人工确认结果。
- FBA 使用官方 SDK `FbaInventoryApi.getInventorySummaries`（v1），`granularityType=Marketplace`、`details=true`、一个站点。只取 Seller SKU、fulfillable、reserved 与来源更新时间；缺失数量始终为 NULL，不当作零库存。
- 双方只开放 `Inventory and Order Tracking` 审核通过的真实 Seller 内部 Worker：复用 V12/V14 双人审核证据，V8 站点验证需在 24 小时内有效，Vault 检查授权与角色期限。官方 [Role mappings](https://developer-docs.amazon.com/sp-api/docs/role-mappings) 列明 Listings Items 读取允许此角色；FBA Inventory 可使用该角色。
- 运行开关默认 `MERCHIVRA_CATALOG_SYNC_ENABLED=false`，仅当 `MERCHIVRA_SPAPI_ENABLED=true`、真实生产 URL、Demo 关闭、allow-writes 关闭、OAuth 或私有卖家授权已配置、Marketplace verification 已打开时能初始化。**不会自动启用真实账号或越过角色审核。**

## 业务端点

仅当前 Session 租户 Owner + CSRF，可读/启动/提交单页：
- `GET /api/v1/catalog-sync/{marketplaceId}/{LISTINGS|FBA}`
- `POST /api/v1/catalog-sync/{marketplaceId}/{LISTINGS|FBA}/start`
- `POST /api/v1/catalog-sync/{marketplaceId}/{LISTINGS|FBA}/step`

浏览器只传受控站点 UUID 与 `LISTINGS/FBA` 类型，**不传 Seller ID、URL、Token、任意 SP-API 请求体或人员信息**。React Core 的 Listing/FBA 页在已连接账号上显示读数、启动新代次、执行下一页、查看失败状态。每次 `step` 请求最多读一页，后续可加 opt-in 定时调度器。

## 安全提交与恢复

- `spapi_catalog_sync_job` 保留已提交页、代次、Lease Epoch、独占 Owner、重试时间、opaque cursor。外网请求在事务外，DB 操作在事务内；提交前重新锁住 tenant、Seller Account、Role Evidence、Grant 和 Marketplace verification 并重新从 Vault 查明授权有效。
- 页写入与 cursor 更新 / `spapi_catalog_page_commit` 同事务；旧 Lease 的页永远不能推进当前 checkpoint；状态有 `READY/RUNNING/RETRY/PAUSED/SUCCEEDED`，对 429/5xx 按延迟重试，认证失败暂停。
- Listing 25 分钟、FBA 20 秒的本地分页 token 安全门槛，到期从完整新代次重扫，利用来源时间/原始键保护写入。每次完整扫描不会把没有返回的 Listing 解释为删除，也不会把未出现 SKU 的库存变成零。
- Listing 当前映射状态和内部 SKU 映射历史保持原样，来源 `MOCK` 不能升格为 `LIVE`；FBA 每条观测保留生成代次/页/SKU 对应的稳定幂等键，且标记 `LIVE`。
- 若库存缺少 fulfillable、reserved 或 lastUpdatedTime，`quality=PARTIAL`，补货策略不会凭它建议订货量。仅明确完整 `OBSERVED` 的源观测会触发已有补货策略计算。
- 严格拒绝大于界限的数据页、无效来源 SKU、负库存、跨 Marketplace Listing 与可疑分页 token，不记录官方响应原始 JSON、LWA 凭证或供应商个人资料。

## 不在本轮内

实际卖家可用凭证、真实 HTTP 联调、Seller Central 角色审计批准、完整平台限流/后台定时调度、Feeds 写回和 Listings API 写操作、自动缺货补单、FBA 货件/退货、站点分页前端加载更多、远程库存质量告警与真实利润。上述均不能因代码提交而宣称生产完成。


## 可选自动分页调度（V24B）

- `MERCHIVRA_CATALOG_SYNC_SCHEDULER_ENABLED=false` 默认关闭，只有 `MERCHIVRA_CATALOG_SYNC_ENABLED=true` 且显式打开 scheduler 才会启动。
- Scheduler 每隔 `MERCHIVRA_CATALOG_SYNC_SCHEDULER_MS`（默认 10 秒，以满足 FBA 官方约 30 秒的分页 token 有效期）读取最多 5 个已存在并处于 READY/RETRY 的任务，再逐一执行一页。不会自己创建或重启 Seller 授权、任务或更改 Amazon Listing/FBA。
- Owner 先通过 `start` 建立扫描任务，后续会按租约/游标持续推进到 SUCCEEDED。429/5xx 进入延迟重试，权限失效 PAUSED。跨进程并发仍由 DB Lease/Epoch 栅栏阻断。
- Scheduler 的运行状态本身不证明获得了 Seller Central 真实角色或真实数据已同步；必须有独立证据和部署开关。
