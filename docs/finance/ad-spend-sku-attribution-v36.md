# V36 · 人工广告支出来源与 SKU 费用分摊

## 数据来源与安全边界

Amazon Ads 与 Selling Partner API 是不同的授权/应用权限体系。此阶段不使用任何真实 Ads API 凭证或请求，也不将 SP-API Seller 授权冒充 Ads 授权。V36 只提供本地 `MANUAL_UNVERIFIED` 广告支出参考事实和运营手工 SKU 归属。

`finance_ad_spend_reference` 储存当前租户 Marketplace、人工稳定 `source_key`、campaign ID 参考、市场当地日期、当前站点原币的非负支出、来源类型及创建人。来源内容在保存后不可修改；同一站点重复 key 若内容完全相同可幂等重放，内容不同会冲突，防止静默合并不同广告费用。没有 API Token、买家信息、投放账户私钥或个人支付资料。

`finance_ad_spend_sku_attribution` 由 Owner 显式为 tenant 内 ACTIVE 内部 `product_sku` 分配 1–10000 的基点比例，站点内单条支出全部 SKU 比例之和不得超过 10000。来源行锁序列化并发编辑。按来源原币和 basis points 计算每个 SKU 费用参考；总分配支出与未分配金额由纯 `AdSpendAttributionEngine` 计算，允许部分归属，但不自动猜测未知 SKU。

`finance_ad_spend_attribution_audit` 为 CREATED、UPDATED、WITHDRAWN 各保留原比例、依据编号、版本和审核人。当前 SKU 分配可修改/撤销，原始广告支出不可反向改写。

## 工作台与接口

财务工作台增加“广告支出·本地参考来源与 SKU 分摊”，支持录入运营参考支出、选活动/日期、按 SKU 分配百分比、查看分配金额、未分配余额及审计。

- `GET/POST /api/v1/finance/marketplaces/{marketplaceId}/ads-spend`
- `GET /.../ads-spend/sku-options`（当前租户前 200 个 ACTIVE SKU）
- `GET /.../ads-spend/{sourceId}/preview`
- `POST /.../ads-spend/{sourceId}/attributions`（skuId、basisPoints、evidenceReference）
- `DELETE /.../ads-spend/{sourceId}/attributions/{skuId}`
- `GET /.../ads-spend/{sourceId}/audit`

所有接口受 Owner Session、CSRF 和 tenant/Marketplace 边界保护。页面同步/刷新**只访问本地 ERP API**；不会因填入一个 campaign ID 就调用 Amazon Ads。

## 仍不具备的能力

真实 Sponsored Products / Brands / Display 等广告报表授权、异步报告生成与下载、报告粒度映射、按归因窗口区分广告点击/转化、真实 Ads 费用负担与 Finances 来源费用去重、广告费用与商品销量的可信时间归属、业务日期时区对齐、ROAS/ACOS 受信指标、真实净利润核算。这些在真实来源验证与费用去重模型前不允许推断。

V36 的 `adsApiVerified` 与 `includedInRecognizedProfit` **永久为 false**。V33 不读取 V36 人工广告支出，因此不会把此类未核验数据当成真实利润费用，也不会重复扣除未知的 Amazon 平台来源费用。

## 离线验收

纯 Java tests 覆盖完整/部分支出分配、未分摊金额、重复 SKU、超过 100%、负支出拒绝；配合 Java25/React TS/PG18 Compose 的离线构建和 UI smoke。待全部核心功能成熟之后，再由用户在本地自行授权 Amazon Ads 和 Seller 两套独立接入以测试真实来源。
