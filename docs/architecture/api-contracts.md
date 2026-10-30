# API-001 · 前后端契约基线（设计稿）

> **不是最终 OpenAPI YAML，也不是已经实现的 API。** 本文先冻结资源语义、租户身份、请求幂等、分页与错误行为。精确 JSON Schema/状态码在 G3 前生成 OpenAPI 文件并评审。

## 1. 设计原则

- 根路径 `/api/v1`；经 TLS + 认证调用（health 等有限白名单除外）。
- 用户身份/tenantId 从服务端可信认证上下文读取；**请求体的 tenantId 不参与授权**。
- 使用 UTC ISO-8601 时间戳（RFC3339），金额用字符串十进制 + ISO 4217 currency：`{"amount":"40.00","currency":"USD"}`，避免 JS 浮点。
- 时间查询采用 `from` 包含、`to` 不包含，页面必须回显用户时区与数据截止时间。
- 展示操作结果带 `sourceSystem`、`sourceUpdatedAt`、`lastSyncedAt`、`completeness`。
- 响应中不返回 Amazon Refresh Token、客户 PII 或内部密钥引用。
- POST/PUT 需要明确定义幂等性：手动触发同步建议使用 `Idempotency-Key` 请求头（租户+操作者+资源作用域内唯一）。

## 2. 资源端点（提案）

| Method | Path | 角色 | 成功响应 | 关键错误 |
|---|---|---|---|---|
| POST | /auth/login | 未登录 | 安全会话 + 最小用户信息 | AUTH_FAILED、RATE_LIMITED |
| GET | /me | 已登录 | 用户角色及作用域 | UNAUTHENTICATED |
| GET | /seller-accounts | Admin/运营只读 | 店铺与授权状态 | FORBIDDEN |
| POST | /seller-accounts | Admin | 创建连接草稿，不回显秘密 | CONFLICT、VALIDATION_ERROR |
| GET | /listings | 运营/授权只读 | 映射状态与最新来源时间 | INVALID_FILTER |
| PATCH | /listings/{id}/mapping | 运营/Admin | 新映射版本 | VERSION_CONFLICT、AMBIGUOUS_MAPPING |
| GET | /orders | 运营/财务/授权只读 | 订单分页与状态分离 | INVALID_TIME_RANGE |
| GET | /orders/{id} | 同上 | 订单行 + 来源/关联 | NOT_FOUND |
| GET | /inventory/fba | 运营/授权只读 | 状态桶/新鲜度 | STALE_DATA（元数据警告） |
| GET | /finance/transactions | 财务/Admin | 事件和分配/归属 | FORBIDDEN |
| GET | /reports/margin | 财务/Admin | 预估/已入账**不同列** | INCOMPLETE_DATA（正常数据状态而非 HTTP 错误） |
| POST | /sync-runs | Admin/运营 | 202 + runId | ACTIVE_RUN_EXISTS、ROLE_MISSING |
| GET | /sync-runs/{id} | Admin/运营 | 进度/警告/最后检查点 | NOT_FOUND |

**HTTP 状态约定**：查询成功 200；创建异步 run 202；验证失败 400/422；未认证 401；认证但无权 403；跨租户资源 404 或统一不可识别拒绝策略（不能泄露存在性）；幂等冲突 409；服务内部 500。确切实现前制定错误字典。

## 3. 页面统一响应示例（虚构）
```json
{
  "data": [{
    "id": "ord_mock_001",
    "externalOrderId": "MOCK-001",
    "marketplaceId": "MARKETPLACE_MOCK_US",
    "fulfillmentStatus": "SHIPPED",
    "refundStatus": "PARTIAL",
    "financialStatus": "PARTIALLY_RECONCILED",
    "amount": {"amount": "40.00", "currency": "USD"},
    "sourceUpdatedAt": "2026-10-28T10:00:00Z",
    "lastSyncedAt": "2026-10-28T10:01:10Z",
    "completeness": "PARTIAL"
  }],
  "page": {"nextCursor": null, "hasMore": false},
  "meta": {"requestId": "req_mock_123"}
}
```
**注意**：示例 Marketplace ID 仅虚构，不能拿来调用 Amazon。

## 4. 异步任务创建示例
```json
{
  "sellerAccountId": "acct_mock_001",
  "marketplaceIds": ["MARKETPLACE_MOCK_US"],
  "resource": "ORDERS",
  "mode": "INCREMENTAL"
}
```
服务端根据权限和已记录的 `sync_stream` 决定窗口，不允许客户端直接提交任意 `highWatermark`。返回 `202 {"runId":"run_mock_01","state":"QUEUED"}`。

## 5. 错误结构与可执行行为

```json
{
  "error": {
    "code": "AMAZON_AUTH_REVOKED",
    "message": "店铺授权已失效，请管理员重新连接",
    "retryable": false,
    "details": {}
  },
  "meta": {"requestId": "req_mock_124"}
}
```

| 错误码 | HTTP（建议） | UI 行为 | 服务端行为 |
|---|---|---|---|
| UNAUTHENTICATED | 401 | 返回登录 | 记录安全事件 |
| FORBIDDEN / RESOURCE_NOT_FOUND | 403/404 | 不显示对象身份 | 防止资源枚举 |
| VERSION_CONFLICT | 409 | 提示刷新并重试编辑 | 不覆盖历史 |
| ACTIVE_RUN_EXISTS | 409 | 跳转已有任务或排队 | 保持单 stream 租约 |
| AMAZON_AUTH_REVOKED | 422/503 视触发入口而定 | 提醒管理员连接 | 停止对应同步 |
| AMAZON_RATE_LIMITED | 429/503 视代理场景而定 | 告知任务等待 | 在 Worker 内退避重试 |
| SOURCE_DATA_INCOMPLETE | 200 + completeness | 展示数据不完整 | 不把未知当 0 |
| INTERNAL_ERROR | 500 | 展示 requestId | 日志脱敏并关联 trace |

外部 Amazon HTTP 错误与本地 API HTTP 状态不要求完全相同；**不要把后台任务的 429 原样直通为用户的普通查询 HTTP 429**。

## 6. 分页与排序

本地列表使用不透明游标 `nextCursor`（标识固定排序键+过滤条件），限制 `pageSize` 的最大值待压测决定。默认按 `sourceUpdatedAt DESC, id DESC` 保持稳定；游标失效返回结构化错误。Amazon 的 `paginationToken` 是内部适配器专用，永不对浏览器公开。

## 7. G3 补件

正式 OpenAPI 3.1 文件、JSON Schemas、鉴权 scheme、错误枚举、请求/响应脱敏样本、权限矩阵和契约测试必须在第一条业务 API 开发前批准。
