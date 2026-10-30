# ARCH-002 · 模块职责与依赖规则

| 模块 | 负责 | 不应该负责 |
|---|---|---|
| identity | 租户、用户、权限、审计主体 | 商品或订单的业务规则 |
| marketplace | Seller 连接、Marketplace、接口权限状态 | 订单的价格/利润逻辑 |
| catalog | 内部 SKU、商品与 Listing 关系 | 平台 HTTP 细节 |
| orders | 订单事实、订单项、状态与退款关联 | 财务收入认列或库存扣减的猜测 |
| inventory | 仓库库存与 FBA 快照视图 | 用快照写入仓库出入库流水 |
| finance | 成本有效期、财务交易、利润口径 | 把未完整数据宣传为净利润 |
| sync | 任务、检查点、重试、限流、监控 | 修改业务含义 |
| adapters | Amazon 接口 DTO/鉴权/标准化 | 存储领域规则与 UI 页面 |


```mermaid
flowchart LR
UI["API / Web"] --> APP["应用服务"]
APP --> ID["identity"]
APP --> CAT["catalog"]
APP --> ORD["orders"]
APP --> INV["inventory"]
APP --> FIN["finance"]
APP --> SYNC["sync"]
CAT --> PORT["Marketplace Gateway Ports"]
ORD --> PORT
INV --> PORT
FIN --> PORT
SYNC --> PORT
AMZ["Amazon Adapter"] -.实现.-> PORT
AMZ --> SP["SP-API"]
```

箭头为逻辑使用关系而非允许任意循环依赖；`identity` 的上下文策略跨模块复用但不开放修改租户权限的入口。

## API 契约草案

| 方法/路径（建议） | 用途 | 角色 |
|---|---|---|
| GET /api/v1/me | 当前用户与租户上下文 | 登录用户 |
| GET /api/v1/seller-accounts | 店铺连接列表 | Admin/运营 |
| POST /api/v1/seller-accounts | 配置连接（无密钥回显） | Admin |
| GET /api/v1/listings | Listing 查询与映射状态 | 运营/授权只读 |
| GET /api/v1/orders | 订单分页筛选 | 运营/财务/授权只读 |
| GET /api/v1/inventory/fba | FBA 最近快照 | 运营/授权只读 |
| GET /api/v1/finance/transactions | 交易与费用查询 | 财务/Admin |
| GET /api/v1/reports/margin | 预估利润 | 财务/Admin/特别授权 |
| POST /api/v1/sync-jobs | 创建指定资源同步任务 | Admin/运营 |
| GET /api/v1/sync-jobs/{id} | 查看进度与失败详情 | Admin/运营 |

统一错误结构（设计提案）：`{ "code": "SYNC_RATE_LIMITED", "message": "请求限流", "requestId": "...", "details": {} }`。禁止在 API 中使用客户端提供的 tenantId 进行授权决定。

## 模块解耦验收

对适配器依赖只允许从 Ports 层访问；禁止领域模块互相引用数据库表作为隐式接口；跨模块事务与事件发布策略需配套 ADR。
