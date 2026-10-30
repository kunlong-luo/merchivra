# INT-004 · Amazon SP-API 官方 SDK 与离线可执行接入层

**状态：真实接口调用代码已提交；真实账号授权和业务接入仍未上线。更新于 2026-10-30。**

## 1. 适用范围及代码位置

- Maven 使用 Amazon **官方 Java SDK** `software.amazon.spapi:spapi-sdk:1.13.0`，包含其生成的 API/DTO、LWA 处理、限制数据 Token (RDT) 支持和操作级限流器。上游仓库：[Amazon official SDK](https://github.com/amzn/selling-partner-api-sdk)。
- `spapi/SpApiOfficialSdkFactory.java`：内部工厂按服务端认可的 `SpApiSellerScope` 构造 SDK，支持 SDK 中的全部已发布 API 类型。不能从前端传入 `refreshToken`、Seller ID、租户或任意主机；没有通用的 HTTP API 代理。
- `spapi/SpApiOfficialModules.java`：已提供**类型化接入入口**，覆盖新版 Orders、Listings、FBA、Catalog、Finances、Reports、Feeds、Sellers、Sales、Pricing、Product Type Definitions、Notifications、Shipping 和 FBA Inbound/Outbound 等。官方 SDK 的 API 类包含写方法，真正调用前仍须完成业务授权与审计，不可直接向终端用户开放。
- `spapi/SpApiReadOperations.java`：已实现核心读取方法调用参数封装（Orders `searchOrders/getOrder`、Listing、FBA、Catalog、Pricing、Finances、Reports、Feeds、Seller Marketplaces、Sales）；使用另一个安全型 `SpApiClient` 发送与返回 raw JSON，不存入正式业务表。用于小范围 contract/mock HTTP 检查。
- `spapi/LwaTokenClient.java`：使用官方 LWA token endpoint、以 refresh token 换取短期 access token、在本机内存中按 token 摘要缓存；不持久化卖家授权、不会写日志。
- `spapi/SpApiPayloadDecoder.java`：对 Orders v2026-01-01 和 FBA v1 有保守的结构解析，读取 Orders 页中的 `pagination.nextToken`、记录 ID/时间；FBA 将缺失数量保留为 NULL，不自动变 0。没有交易事实映射/写入。
- `spapi/SpApiOperation.java`：经文档核对的部分操作路径/方法注册表，默认禁止 BUYER/RECIPIENT PII 和所有未经额外允许的远程写调用。**这个注册表只覆盖当前手写薄适配器的操作，不代表官方 SDK 的全部 API。**

## 2. 覆盖状态与“所有 API”的准确含义

| 领域 | 已提交的基础能力 | 尚未完成的业务连接 |
|---|---|---|
| Orders v2026-01-01 | 官方 SDK 生成的 SearchOrders/GetOrder API；读取方法、无 PII 默认查询、分页 Token 字段解析 | Amazon 时间窗/限额恢复、真实增量 Worker、交易级 Schema、订单来源写入与回填 |
| Listing / Product Types / Catalog | 官方 Listing/Definitions/Catalog 类型和单项/搜索方法 | Schema 版本检查、上架/修改流程审核、数据来源 SKU 匹配 |
| FBA Inventory | 官方 FBA API、`details=true` 查询、缺失/零库存分开 | 实际卖家权限、真实观测幂等入库、仓库级质量/库存时效 |
| Finances | SDK Finance 模块与 `listTransactions` 读请求；**Finance and Accounting 角色门禁** | 获批真实财务权限、来源稳定 ID、科目/费用对账、币种和结算分离 |
| Reports / Feeds | 官方 SDK 类型；获取列表/明细/文档元数据，已注册创建方法但默认禁止写 | 预签名文件下载校验、报表解析、Feeds 上传签名/处理回执、操作审计 |
| Notifications / Sales / Pricing / Seller | SDK 生成模块以及当前部分读操作 | SQS/EventBridge 订阅、销售报表累计边界、限流容量与事件回放 |
| Shipping / FBA Inbound / Outbound 等 | 官方 SDK 类型可由内部工厂构造 | 独立真实权限、业务流程、危险操作双重确认和验收 |
| 其他官方模块 | 依赖官方 SDK 含现有生成 API/DTO；内部工厂可创建其 API 类 | **没有声称全部 endpoint 已逐个映射到 Merchivra 业务 API、权限、数据库和 UI** |

**官方 SDK 可引用全部其发布版本已包含的 API 类型 ≠ 所有 API 在 ERP 里已经自动运行。** Amazon 的版本更替可能快于 SDK 发布；正式连接前应核对 [官方 SDK Releases](https://github.com/amzn/selling-partner-api-sdk/releases)、[官方 OpenAPI 模型](https://github.com/amzn/selling-partner-api-models)、每个操作的角色/速率和版本文档。不能因为 SDK 编译成功而认为真实卖家接口已批准。

## 3. 安全配置（默认不允许网络出站）

`application.yml` 默认：

```properties
MERCHIVRA_SPAPI_ENABLED=false
MERCHIVRA_SPAPI_SANDBOX=true
MERCHIVRA_SPAPI_ALLOW_WRITES=false
```

启动时没有任何真实 LWA 凭证也能运行既有 ERP/Mock 工作台。之后取得合法注册的 App LWA client ID/secret 与**按 Seller 单独安全储存的 refresh token**，并完成 Tenant/Seller 区域与权限核验，才能在受限服务端进程里启用出站：

```properties
MERCHIVRA_LWA_CLIENT_ID=<来自受控密钥管理器>
MERCHIVRA_LWA_CLIENT_SECRET=<来自受控密钥管理器>
```

上面的两行只说明环境变量**名称**，不要提交真实值、不要写进公开 `.env`、Issue、测试或日志。只有启用 `MERCHIVRA_SPAPI_ENABLED=true` 后，Spring 才注入连接组件；即使启用，**当前仍没有 Seller OAuth callback/凭证 Vault/生产 Worker/任何真实业务执行路由**，故不会从网页上自动发送 Amazon 请求。打开 `MERCHIVRA_SPAPI_ALLOW_WRITES=true` 不等于获得平台角色/卖家批准，仍需逐操作限制；开发期保持 false。

所有 remote endpoint 由 NA/EU/FE 枚举固定；禁止浏览器传 URL 或 Host。官方 SDK 与自写薄客户端**不共享**限流缓存；正式 Worker 应优先采用官方 SDK 已实现的限流器，并按账户/应用复用实例，避免创建 API 对象时重复消耗令牌桶。

## 4. 本轮验证与下一步

- Java 单元测试使用**纯虚构 Token 与 mock HTTP**验证没有启用开关则不交换 Token、不发请求；编码 Amazon 路径、403/429 的安全错误、Finance 角色拒绝、BUYER/RECIPIENT 拒绝；官方 SDK Sellers/Orders API builder 只在内存中构造。**没有进行实际 Amazon 请求**。
- 产品真正接入所需的后续工作：OAuth 授权的 state/PKCE 与回调核验、安全存储/加密刷新令牌和撤销授权、Seller Marketplace/角色实测与来源批准、账户级 Rate Limit、分页过期恢复、事件账务事实与报表文档下载，以及多租户隔离/生产安全评审。切勿把此前 V6 Mock 页级检查点直接用于生产 Amazon。
- 建议服务端未来仅能通过受授权的 `SellerCredentialVault`（尚未实现）查询 Token，不在浏览器/API Body 暴露 `SpApiSellerScope`。不启用真实网络的情况下，前端不应新增“已连接 Amazon”误导性状态。

官方证据：[连接/LWA](https://developer-docs.amazon.com/sp-api/docs/connecting-to-the-selling-partner-api)、[Orders v2026 迁移](https://developer-docs.amazon.com/sp-api/docs/orders-api-migration-guide)、[Finances v2024](https://developer-docs.amazon.com/sp-api/reference/listtransactions)、[FBA Inventory](https://developer-docs.amazon.com/sp-api/reference/getinventorysummaries)。
