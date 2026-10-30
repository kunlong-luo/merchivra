# INT-002 · SP-API 操作级核对矩阵（2026-10-30）

**分类：部分已按官方公开文档核实，部分需应用账号权限和真实数据验证。**

## 1. 已有官方文档证据

| 范围 | 已核实内容 | 对设计的具体影响 | 官方文档 |
|---|---|---|---|
| Orders | Orders v0 被弃用，当前为 v2026-01-01 | 新实现不要以 v0 为首选 | [Orders API](https://developer-docs.amazon.com/sp-api/docs/orders-api) |
| Orders | `searchOrders`, `getOrder`, `includedData` | 订单和订单项统一读取；仅申请需要的字段 | [Migration Guide](https://developer-docs.amazon.com/sp-api/docs/orders-api-migration-guide) |
| Orders | `paginationToken` **24h 过期** | 必须按时间窗口恢复，不长期保存 token 作为唯一游标 | [Migration Guide](https://developer-docs.amazon.com/sp-api/docs/orders-api-migration-guide) |
| Orders | 使用 `fulfillmentStatuses`；值如 `PENDING`, `UNSHIPPED`, `PARTIALLY_SHIPPED`, `SHIPPED`, `CANCELLED` | 必须保留平台原始状态，不把退款当履约 | [Migration Guide](https://developer-docs.amazon.com/sp-api/docs/orders-api-migration-guide) |
| Orders | `fulfilledBy` 为 `MERCHANT` / `AMAZON`，代替旧 `MFN` / `AFN` 过滤语义 | 适配层负责映射 FBM/FBA | [Migration Guide](https://developer-docs.amazon.com/sp-api/docs/orders-api-migration-guide) |
| Orders PII | 此版本的 PII 授权按角色管理，而非该版本额外 RDT | MVP 默认不请求 BUYER/RECIPIENT，不泛化到其它 API | [Migration Guide](https://developer-docs.amazon.com/sp-api/docs/orders-api-migration-guide) |
| Finances | v2024-06-19 `listTransactions`，需 Finance and Accounting 角色 | 财务接入独立权限判断，避免误认为订单权限足够 | [Finances API](https://developer-docs.amazon.com/sp-api/docs/finances-api) |
| FBA Inventory | v1 `getInventorySummaries`，状态含 fulfillable/inbound/reserved/unfulfillable/researching | 明确状态分桶与来源观测时间 | [FBA Inventory API](https://developer-docs.amazon.com/sp-api/docs/fba-inventory-api) |
| Notifications | 通知类型与支持方式不同；例如 `TRANSACTION_UPDATE` 可经 SQS | 不能把通知作为唯一事实或认为所有事件有通知 | [Notification Types](https://developer-docs.amazon.com/sp-api/docs/notification-type-values) |
| Registration | 开发者注册需要用途、安全控制及政策接受/审批 | 公开源码不代表自动拥有 API 权限 | [Private Developer](https://developer-docs.amazon.com/sp-api/docs/register-as-a-private-developer) |

官方 API 与 ERP 内部字段的语义映射在 G2 的 [INT-003 规范化数据契约](amazon-data-contract.md) 中单独记录。该规范不能替代官方响应字段验证；操作权限和限额需按实际卖家角色重新确认。

## 2. 按操作应进一步确认

| 操作/资源 | P0 用途 | 需要记录的真实接入参数 | 状态 |
|---|---|---|---|
| `searchOrders` | 订单增量读取 | 账户区域、site、updated window、includedData、page size、rate limit | 待试点 |
| `getOrder` | 订单详情或补齐 | 可用字段、权限和错误/空值行为 | 待试点 |
| `getListingsItem` / Reports | Seller SKU 与 listing | marketplaceIds、可用 includedData、目录与 Listing 关系 | 待试点 |
| `getInventorySummaries` | FBA 观测 | granularity / granularityId / marketplaceIds、聚合维度及时间 | 待试点 |
| `listTransactions` | 财务事件 | 过滤、nextToken、relatedIdentifiers、空结果与交易 ID 有效范围 | 待试点 |
| 通知订阅 | 可选加速同步 | 当前支持的通知类型、传输、订阅策略 | 非 MVP 硬依赖 |

**认证状态说明**：`待试点` 指设计者尚未获得真实账号调用证据，不是指 API 不可用或缺少全部权限。

## 3. 权限最小化

- Orders 与 FBA Inventory 各有多种可接受的角色组合，具体按使用的 operation 核对；不以“Amazon 开发者”身份推断权限。
- `listTransactions` 要求 Finance and Accounting 角色，未获批准时财务模块显示不可用并可用 Mock/人工输入模式做产品演示，但不宣称已对账。
- 不采集 BUYER/RECIPIENT/地址；扩展前必须评审个人数据业务必要性、角色、保留和安全。
- 真实接入时用批准过的自身应用资料，绝不在 GitHub 分享 client secret 或 refresh token。

## 4. 真实验收记录格式

记录 `API version, operation, application registration mode, approved role, region, marketplace, request scope, limit observed, response fields (masked), pagination edge, sourceTime, result`。需要留可审计的**脱敏**样例与结论，任何 API 改版必须更新此矩阵、适配器契约与测试。
