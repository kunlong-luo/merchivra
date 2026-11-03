# V88 · FBA 库存最新观测全量搜索、质量筛选与分页

**2026-11-03。** 本阶段落实 V86 核心功能审计、V87 Listing 全库分页之后的 P0 FBA 库存缺口。复用现有 fba_inventory_observation 原始来源事实表、V16 Session Owner/OPERATOR_READ 鉴权、TanStack Query、Zod 和 PostgreSQL 18；不创建额外余额表、搜索系统或新的消息队列。真实 Amazon OAuth/SP-API 保持关闭。

## 最新观测规则：有来源证据才有库存数字

一个 Seller SKU 可以有多条原始来源观测。本地现有 /inventory/fba API 已经采用的规则保持不变：先按 (seller_sku_raw) DISTINCT ON 选每 SKU 唯一一条，排序优先级是 observed_at DESC NULLS LAST，其次 ingested_at DESC，最后 id DESC。**来源时间存在的观测优于来源时间为空但新近入库的观测**；两条来源时间相同才比较 ERP 入库时间。不能因为本地入库较晚就自动宣称远端库存更实时。

质量 OBSERVED / PARTIAL / UNKNOWN / STALE 与数据来源 MOCK / UNVERIFIED / LIVE 是两个独立事实字段。原始值永不经本功能隐式升级：

- 已观测 fulfillable=0、reserved=0 是确切零；NULL 是未知，不可自动替换为 0。
- STALE 即使带有正的库存数字也必须显示过期质量，不能自动用它作为可信补货或发货承诺；质量是来源标志，不依据当前系统时间重算。
- **先选每 SKU 的最新记录，再过滤质量和来源**。旧 OBSERVED 不得在最新 UNKNOWN 时重新成为搜索结果，旧 MOCK 不得在最新 UNVERIFIED 时替代新记录。
- observedAt 为来源时间，可能 NULL；ingestedAt 为 ERP 入库时间，不得把前者替换成后者、now() 或人为时间。页之间来源状态可变化，因此不是冻结全量库存快照。

## 有界新接口

GET /api/v1/seller-marketplaces/{marketplaceId}/inventory/fba/page?limit=50&search=SKU-&quality=ALL&origin=ALL&cursor=...

服务器按 Session 当前租户、有效 Owner 或明确 seller_account 范围的 OPERATOR_READ 授权，所有 SQL 绑定当前 tenant、Marketplace。匿名返回 401，跨租户/不存在站点返回 404，不能通过 cursor 绕过授权。ReadOnlyStaffGuardFilter 仅开放这一条**精确 GET** 的只读员工通路，不增加写能力。

- search：最多 80 个可打印字符的大小写不敏感 Seller SKU **字面量前缀**，转义 SQL 中的 !、% 和 _。空搜索为当前站点全部已入库 SKU，不是所有站点。
- quality：ALL、OBSERVED、PARTIAL、UNKNOWN、STALE。origin：ALL、MOCK、UNVERIFIED、LIVE。服务端严格枚举，其他值 400。
- limit：1–100，默认 50；按 (seller_sku_raw ASC,id ASC) 进行 Keyset 分页，多读 1 条判断 hasMore。
- cursor：沿用 V87 多行/Unicode SKU 安全 Base64url 格式，绑定 Marketplace、类型 FBA_INVENTORY、前缀及 quality|origin。跨站点或更换筛选条件后旧游标 400，游标只是位置，不能作为权限凭证或结算依据。
- 响应字段：items（每条包含 id、sellerSku、quality、fulfillable、reserved、observedAt、ingestedAt、dataOrigin）、nextCursor、hasMore、pageSize、searchPrefix、quality、origin。Cache-Control:no-store。
- 旧 GET /inventory/fba?limit=50 保持兼容。Flyway V80 只增加最新观测排序索引和 lower(seller_sku_raw) 的 B-tree 前缀索引，未复制原始数据。

## React 工作台

FBA 库存页新增全库 Seller SKU 前缀搜索、最新观测质量/来源下拉筛选和上/下页控制，显示**来源观测时间与 ERP 入库时间两列**。实际观测零显示 0，未知显示“未知”，STALE 显示对应原始质量。切换站点、重设筛选或从异常处理跳转时重置页游标；FBA 来源同步后让 inventory-v88 的 TanStack Query 缓存失效。页面分页不是全站库存实时快照。

## 独立模拟验收

CI 在 V87 测试后执行 scripts/smoke_core_fba_inventory_page_v88.py：强制 PostgreSQL 18 localhost、demo 和双 Amazon OAuth/SP-API 关闭。向虚构站点写入 64 个 SKU 的最新观测、以及一组有相反质量或来源、观测时间晚/早/NULL、入库时间晚/早的历史事实。验证：

1. 64 SKU 为 50+14 两页，质量后过滤 59 OBSERVED（50+9）、2 UNKNOWN、2 PARTIAL、1 STALE；每 SKU 只出现一次。
2. OBSERVED 0/0 与 UNKNOWN NULL/NULL 不混淆；STALE 非零、PARTIAL 缺预留、源时间 NULL 与入库时间保持真实；相同源时间以入库时间打破并列。
3. 历史 OBSERVED/来源 MOCK 不会在最新 UNKNOWN/UNVERIFIED 的 SKU 中通过质量/来源筛选。
4. 字面量 %/_/!、包含换行符 Seller SKU 的 cursor、跨质量/来源/前缀/站点的游标拒绝、400/401/404、旧 API 兼容、Flyway V80 索引存在。
5. 测试 Mock 观测插入不修改 FBA 货件和人审审计；不调用 Amazon 出站、不生成真实货件或发票。

Java25、Gradle、React19、TypeScript6、Zod4、Vitest、Vite8、PG18、Docker Compose 的最终结果以当前 PR 的四项 CI 为准。

## V89 后续

完成 Listing、订单、FBA 三个核心读取模块后，下一阶段优先补齐有解释边界的经营总览/导出，复用 V64 已存在的财务快照和 CSV，保持币种/来源/UNKNOWN 的单独表达。所有真实 Seller、Ads 授权留到其他核心功能全部完成后由用户在本地联调。
