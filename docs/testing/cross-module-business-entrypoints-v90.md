# V90 · 核心跨模块入口补齐：全库 Listing / Order 选择与人工操作联调

**日期：2026-11-04，基线 V89 main。** 核心完整度审计和 V86/V87 已提供 PostgreSQL 服务端订单、Listing 全库检索；V88 库存全库；V89 经营来源质量。但 **ReplenishmentWorkspace 的补货策略 SKU 下拉框仍调用旧 `fetchCoreListings`（最多前 50 条），FinanceWorkspace 的财务事件人工匹配、订单成本/贡献分析仍调用旧 `fetchCoreOrders`（最多前 50 条）**。这意味着 51+ 位的真实已入库记录无法通过 UI 进入已有安全业务链路。V90 解决此 P0 操作断点，不重复实现已经存在的 CRUD、审批、财务公式或 Amazon 接入。

## 真实功能交付

- 新建共享 React 19 `CoreMarketplacePicker` 和严格类型的纯函数 `listingChoices`、`orderChoices`、`onCurrentPickerPage`：复用 **V87 `GET .../listings/page`** 和 **V86 `GET .../orders/page`** 的 Session/Marketplace 授权、文字前缀检索、SQL 转义、opaque cursor 和最多 50 条每页。没有额外 search index、Redis 或后台全库拉取。
- 根据业务入口分别显示 Seller SKU（还显示 ASIN/映射状态）或来源订单的外部编号（还显示 MOCK/UNVERIFIED/LIVE 和履约状态）。下拉的值**只来自服务端当前一页记录**，不能手写 UUID 或 Seller SKU 来当作已验证选择。
- 每次明确执行新前缀搜索、清除筛选或游标“上一页/下一页”都会清掉当前选择。数据请求加载/失败时不可从旧页面继续选择；控件提供具体请求错误，不能假装搜索不到数据等于真 0。用户通过 Enter 或“搜索”显式提交前缀。为了保持已有补货页父级 `form` 语义，选择控件**不嵌套 HTML form**。
- 补货策略的原 Seller SKU 选择框升级成分页选择，允许从第 64 条（甚至更后页）Listing 选取 SKU 并保存已有 V70 规则。用户点击已保存策略“编辑”后，系统仅显示已有目标，**要求通过服务器搜索重新选择同一个 SKU** 才能修改策略，防止当映射、当前页或站点改变时误把旧选择用于新操作。切换 Marketplace 时强制 remount 补货工作区，清除 draft/已验证状态；已有采购草稿生成仍执行观察值、策略版本、供应商报价以及二人审批检查；选择框不会自动下 PO、自动 FBA 入仓或写 Amazon。
- 财务事件人工匹配与订单历史成本/贡献分析升级成各自独立的全库订单服务器搜索，避免前 50 单之外的业务断点。每次切换事件，必须重新选择**当前已入库本 Marketplace 的具体订单**；已审核订单的旧 UUID 只作为历史说明，不是一个预选可再次确认的订单。提交后的事件继续被标为 `MANUAL_UNVERIFIED` 或原来源，不等于 Amazon LIVE、V64 结算、银行已到账或净利润。变更事件/查询分页清除待确认的订单 UUID；浏览其他站点也不能继承选择。
- 两个选择器沿用既有 React Query `tenantId,marketplaceId,kind,search,cursor` 作用域缓存键；所有浏览器 GET 均 `same-origin`、`cache:no-store`，不发送可伪造的 tenantId/actor 或 token。Staff 只可根据既有 V16 已授权账户 read；财务匹配和补货策略写入仍是 Owner-only，不扩权。

## 真正的跨模块 Mock 验收（PG18 + HTTP）

CI 在 V89 多币种经营质量验收后运行 `scripts/smoke_cross_module_full_store_v90.py`，仅允许本地可丢弃 `DB_NAME=merchivra`、Demo、Seller OAuth/SP-API 双关闭。

1. 复用 V87 原有 64 个 Listing，保证首 50 个不含 `V87-PAGED-064`，沿 V87 游标到后 14 个找到实际 Seller SKU、来源和冲突映射状态；再用原 `POST /api/v1/replenishment/marketplaces/{mp}/policies` 为第 64 条保存人工补货策略，确认建议因缺最新 FBA 观测而不可计算、无自动采购单、无 FBA 数据写入。
2. 复用 V86 原有 64 笔订单，保证首 50 笔不含 `V86-PAGED-064`，沿 V86 原始来源更新时间 + UUID 游标在后 14 条找到准确 ID；从前缀搜索单条结果再次核实。
3. 通过原 `POST /api/v1/finance/manual-events` 建立只供测试的人工原币来源，再通过原 `/events/{eventId}/review` 匹配**第 64 笔订单 UUID**；同租户其他 Marketplace 的订单 ID 会 404，而真实匹配写入原有 finance_event_link_review/audit。审计保留 `MANUAL_UNVERIFIED`，订单 `NOT_OBSERVED` 不改成已入账；不会触发银行、Ads、FBA 或 Amazon 写入。
4. V89 PostgreSQL 全库指标在补货规则/财务人工关联前后保持总数量、FBA 最新观测数一致；不能把浏览器第一页的 50 条误计为站点全量。
5. Vitest 使用 50+14 两页模拟 Listing/订单的类型安全源数据，验证第 64 条只出现在正确页，错误页或未加载页不能被当作已核对选择；不向 API 提交浏览器 tenantId。Java/Gradle、React TypeScript/Vitest/Vite、PG18 HTTP 与 Docker Compose 所有 CI 完成后再 squash merge。

## 还未完成的业务边界

- 这里解决的是**实际 UI 不能从完整源数据选取待处理业务项**，而不是宣称整个 ERP 100% 完成、自动化可生产上线或下单智能补货已可信。
- Seller/Ads OAuth、SP-API、Amazon FBA Inbound、银行入账、FX 和 G1/G2/G3 真实验收仍然关闭；最终由用户本地逐项授权测试。
- 后续优先检查其他业务页是否仍把旧的 50 条只读接口作为业务选择框，以及跨模块明确的手工审批/取消/撤销操作边界；可把已有端到端模拟脚本整理为业务验收矩阵，而不是重复创建平行商品、采购、财务模型。
