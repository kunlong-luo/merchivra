# V163 · 额外成本凭证来源：PostgreSQL 18 入库、幂等与独立复核

**已落地范围**：新的 Flyway V91、Owner Session/CSRF 受保护的 HTTP 读写、不可变手工成本来源、双人独立复核、有限查询和只含安全标识的追加审计。这是**费用凭证的待审核来源库**，不是已核验第三方支付/Ads 成本、SKU/订单成本分摊、会计过账或净利润。

## 业务定义与边界

V162 的 8 类额外成本（广告、头程、FBA 仓储、FBM 物流、退货处置、进口税、支付/汇兑、其他运营）作为受控 `category`。只允许 `MANUAL_INVOICE`、`MANUAL_CARRIER`、`MANUAL_OTHER` 三种本地上传前的**文字元数据**来源；存储唯一来源标识、业务日期、Marketplace 原币、正数金额、文档的**操作者提供的 SHA-256 字符串**。接口**不读取、上传或校验文档内容**，不保存原始票据、凭据、PII、银行或税务资料；用户输入哈希不等于真实文件真实性/独立签名。

- `STAGED`：创建后的默认状态；来源仍然 `MANUAL_UNVERIFIED`。
- `REVIEWED_UNVERIFIED`：第二名 Owner 核对元数据后通过；**仍然没有取得 Amazon/Ads/银行来源真实性认证**，不自动输入 V162 `REVIEWED_COST`，不修改 V64 冻结报表，不产生结算或净利润。
- `REJECTED`：审核者驳回；和已复核状态一样，终态不可被再次更改。修正必须使用**新的来源键/凭证版本**，旧数据保留审计。
- 即便独立复核，未来还需去重既有 `finance_source_event` 平台手续费、`ads_report_reviewed_cost` 广告、头程实际采购/承运商来源等；不可凭类别名、单一来源键或复核状态断言无双计。

`finance_cost_source_v91` 以 `(tenant_id,seller_marketplace_id,source_kind,source_key)` 建唯一约束（**与类别无关**，阻止同一凭证换类别重复入账）。同键同全部事实重放返回原行，同键改原币、金额、日期、类型、来源摘要或类别返回 HTTP 409；真实事务对 Marketplace 行先加锁，并保留数据库唯一约束二次防线。金额采用 PostgreSQL `NUMERIC(20,6)`，严格正数，三位 ISO 原币，须严格等于 Marketplace 的货币（没有未经批准的汇率转换）。

V91 的触发器保护来源原始列和初次创建时间不被 UPDATE 改写，同时强制从 STAGED **只能完成一次审核**；复合租户/Marketplace、Membership 外键和 `reviewed_by <> created_by` 检查共同防止伪造跨租户数据或自审。`finance_cost_source_audit_v91` 只记录 STAGED/REVIEWED_UNVERIFIED/REJECTED、操作人 UUID 和时间；审核备注留在私有来源行中、**不复制到审计列表**。当前**只提供最多 50 条新凭证列表**，不是全期全库利润汇总，后续需要稳定分页与范围查询。

## 受保护的 HTTP API

所有请求由服务器当前 Session 的 Owner 租户推出 `tenant_id`，路径只允许用户可访问的 `marketplaceId`。沿用 Spring Security CSRF，所有返回 `Cache-Control: no-store`，不会调用任何 SP-API 或 Ads 远程 API。

- `POST /api/v1/finance/marketplaces/{marketplaceId}/cost-sources`：Body 包含 `category`、`sourceKind`、`sourceKey`（8–128 个安全字符）、`documentSha256`（64 lowercase hex，非全零）、`businessDate`、`amount`（正十进制、≤6 位小数）及 `currency`。服务端强制唯一来源与 Marketplace 原币。
- `GET /api/v1/finance/marketplaces/{marketplaceId}/cost-sources`：最近 50 条已登记的手工来源，按 `business_date DESC,id DESC` 稳定排序；不是完整历史会计报表。
- `POST /api/v1/finance/marketplaces/{marketplaceId}/cost-sources/{sourceId}/review`：Body `{"accept":true,"reason":"Independent invoice metadata review"}`，必须由不同于创建者的 Owner 发起，只能从 STAGED 流向 REVIEWED_UNVERIFIED 或 REJECTED，理由 10–500 个非换行字符，数据库锁与不可逆转审计。
- `GET /api/v1/finance/marketplaces/{marketplaceId}/cost-sources/{sourceId}/audit`：最近 100 个固定审核码/操作者/时间，先验证资源严格属于当前租户和 Marketplace。

## 正确理解已做的技术验收

- Java 25 / Gradle：输入及数值精度负例、零与负费用拦截、伪造/空哈希和分类/来源系统白名单、maker-checker 自审拒绝、Owner Session 租户推导、不缓存响应、V91 DDL 安全检查。
- Spring HTTP/真实 PostgreSQL 18：V163 变更 Flyway 结构与真实控制器，**合并主线后必须跑精确 SHA 的完整 A3** 来检验迁移、权限/CSRF 基线、PG18 及 Next/Compose 不回归。A3 合成通过不等于新成本接口已经完成独立真实环境端到端验收，更不能代替 G1 财务真实性审核。
- **未完成**：财务第三方账单真实性、手工文件内容校验、财务真实费用来源去重、逐 SKU/订单/货件及期间可追踪分摊、完整不可变历史账单、V162 自动调用 V163 结果、V64 版本化全成本成本页、公开的 Next 完整成本表格/导出、正式税务会计口径、跨币外汇政策。

后续 V164 应优先围绕本地凭证的双人复核和**明确的来源身份/余额守恒**建立按 SKU/订单/期间分摊与待分摊池；必须在可靠的受审来源审计、金额核对和 G1 财务许可之后，才可能接入利润公式。临时存入的人工 Ads/费用依旧不能冒充已核验 Ads API 交易。

[利润主线 P0 #12](https://github.com/kunlong-luo/merchivra/issues/12) · [V162 覆盖计算引擎](full-cost-coverage-v162.md) · [V161 产品规划](../product/profit-first-next-primary-v161.md) · [生产阻塞 #104](https://github.com/kunlong-luo/merchivra/issues/104)
