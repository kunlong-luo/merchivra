> **V213 跨订单原采购收货批次全局容量**：新增 Owner/Session/no-store `GET /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/refund-original-receipt-capacity`。完整复用 V212 冻结原退货/批次来源，跨同租户所有本地销售订单的 V106 原始 claim 核对原 QC 接受量、撤销占用、采购单币种/单价/接受量版本及合法共享；超过全量上限或源变更禁止出局部合计。没有外部实际退货逐批次匹配，没有含运费/关税的真实落地成本，也不提供库存/COGS posting；`actualRefundCogsReversal=null`、`realizedNetProfit=null`。见 [V213 财务契约](../finance/v213-global-receipt-capacity-and-landed-cost-hold.md)。

> **V212 退款 × 原销售批次 × 本地返仓数量核对（实际 Springdoc 路由，OpenAPI 候选快照未同步）**：Owner/Session/no-store `GET /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/refund-batch-lineage-readiness`。先重算 V210 整单全部退款与 V203 V106 原销售批次、然后整单冻结和再读取 V34/V106/V97/V96 的所有原始行（每维≤1000），按原订单行比较退款、原批次认领、声称返仓、ATTESTED_LOCAL 与 PENDING 返仓，报告不足或重复；原采购 `snapshot_unit_cost` 只做原币来源参考。未将退货件归属某原批次、没有实际成本、库存过账或外部真实性：`actualRefundCogsReversal=null`、`realizedNetProfit=null`。详见 [V212](../finance/v212-refund-original-sale-lot-quantity-envelope.md)。

> **V211 期间退款与版本化利润守恒（真实 Springdoc 路由，OpenAPI 候选快照未同步）**：`GET /api/v1/finance/marketplaces/{marketplaceId}/period-refund-profit-guard/{jobId}`，Owner/Session/no-store。先复用 V188/V194 冻结利润来源，再全期核对 LIVE `PRODUCT_REFUND` 退款按 `posted_at` 的原币金额、跨订单匹配数、本期 V64 按 `order_time` 冻结集合、该集合全生命周期 V210 手工退款、其他重叠 V64 **报表快照**。不同版本报表含相同订单不是财务双重记账证明。全部真实账本和 COGS 阻断，`refundAdjustedContribution=null` 与 `realizedNetProfit=null`；无 FX/退款再扣/外部 API/新表/凭证写入。Next.js 利润中心 V188 下按需展开 V211，详见 [V211 契约](../finance/v211-period-refund-profit-inclusion-gate.md)。

> **V210 全订单多次退款 COGS 守恒诊断（新增实际 Springdoc 路由，候选 OpenAPI 快照另行同步）**：Owner/Session/Marketplace 只读 `GET /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/refund-cogs-conservation`，`no-store`。一次冻结全部已匹配 LIVE 产品退款、所有 V34 人工审查数量与当前 LIVE 订单行；先做全事件累计数量守恒，再逐笔重算 V63/V197 来源快照、原币分组手工未核证成本敏感度。任何历史漂移、部分缺口、重复源、跨事件数量超额会将整单成本敏感度置 NULL；`actualCogsReversal=null`、`realizedNetProfit=null` 不变。没有 posting、财务自动认证或退款删除入口。见 [V210 契约](../finance/v210-whole-order-refund-cogs-conservation.md)。

> **V209 只读离线证书链技术准备度**：`GET /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/custody-external-trust-readiness`，Owner/Session/no-store，先独立重算 V208 Ed25519，再比对部署方受限本地 PKIX 根、Ed25519 SPKI 指纹、精确 DNS SAN、七天内签名 CRL（缺失/吊销/过期均失败关闭）。不能通过租户 API 上传根 CA 或改变信任锚，无公网自动获取/无文件下载/无成本写入。所有来源身份、承运实交、Amazon 履约与真实净利润仍未验证。详见 [V209 安全契约](../finance/v209-operator-offline-pkix-crl-readiness.md)。

> **V208 当前接口（实际 Springdoc，候选 OpenAPI JSON 未更新）**：`GET/POST /api/v1/finance/custody-issuer-keys` 用 Owner/Session/CSRF 管理租户自登记声称签发公钥（仅公开其非秘密 SHA 指纹），`POST /api/v1/finance/custody-issuer-keys/{issuerId}/review` 仅 `REVIEWED_UNVERIFIED`，`POST /revoke` 永久失效；`POST /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/custody-dossiers/{dossierId}/originals/{kind}/claimed-issuer-signatures` 仅导入 V1 Ed25519 detached 签名，`GET /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/custody-issuer-integrity` 读时独立重算来源/文件 SHA/签名并始终封锁真实身份/COGS。所有返回 no-store，不提供真实外部签发方验证、原件下载或 Amazon API 调用。详见 [V208 签名证据合同](../finance/v208-claimed-issuer-ed25519-signature-integrity.md)。

> **V207 当前受限原件治理 API（Springdoc 实际实现，候选 OpenAPI JSON 未同步）**：Owner/Session/CSRF `GET /api/v1/finance/custody-originals/capacity` 查看租户私有原件容量及法律保全状态；`GET /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/custody-dossiers/{dossierId}/originals/{kind}/governance` 在同一事务记不可变元数据读取日志后返回 no-store SHA/大小/法律保全元数据；同路径的 `POST /retention-proposals` 与 `POST /retention-proposals/{proposalId}/review` 只允许独立 Owner 审查未来保留期限，**永不解除 hold、删除原件或核验真实承运商/Amazon 履约**。详见 [V207 契约](../finance/v207-custody-original-retention-governance.md)。

> **V206 受限原件 API（Springdoc 实际代码，候选 JSON 未同步）**：Owner/Session/CSRF `POST /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/custody-dossiers/{dossierId}/originals/{kind}` 只接收 multipart PDF/PNG/JPEG（2 MiB 上限、V137 ClamAV 生产 fail-closed），上传会绑定到 V205 既有档案并触发 V109 PostgreSQL 从真实 BYTEA 生成和重算 SHA-256；`GET /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/custody-original-integrity` 返回 no-store 元数据及缺口，不公开原件 bytes 和下载能力。对外部主体、实际承运交接、Amazon 履约和 COGS 均固定不可声称 VERIFIED。详见 [V206 原件契约](../finance/v206-private-custody-original-content-integrity.md)。


> **V205 当前后端 API（Springdoc 实际代码，候选 JSON 未同步）**：Owner/Session/CSRF 的 `GET /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/custody-readiness` 返回只读 V108 外部证据缺口与失效状态；`POST /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/signed-dispatch/{signedDispatchId}/custody-dossiers` 只记录声称的运营商登记、承运交接及各自文件 SHA-256，`POST /api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}/custody-dossiers/{dossierId}/review` 只记录不可变独立 Owner 内部文件复核。三个响应 `no-store`，不能确认外部主体、Amazon 已履约、财务 COGS 或产生库存/GL 写入。详见 [V205 合同](../finance/v205-external-dispatch-custody-dossier.md)；实际 OpenAPI 以 Springdoc 为准。

> **V191 当前入口（2026-11-21）**：API 的现行事实以 Spring Boot MVC Controller/实际 Springdoc 生成合同及 Orval 代码为准。React19/Next16、pnpm12.20.0，前端校验使用 `cd apps/web && pnpm install --frozen-lockfile && pnpm api:check`；不要使用本文历史 G2 OpenAPI 草案代替真实接口。真实 Seller/Ads OAuth 关闭，生产 #104 NO-GO。[当前架构](../architecture/overview.md) · [开发指南](../development.md)。

# API-002 · G2 OpenAPI 设计草案与真实后端契约（2026-11-08）

**本页的 [候选 OpenAPI JSON](openapi.json) 只用于早期 G2 设计评审**（10 条路径、11 个 HTTP operation、19 个 Schema），不是现行所有后端接口的实时目录。其中历史 Bearer/JWT 方案未部署为当前业务认证，不应据此实现新客户端。

## 当前已实现的 API 与代码生成

- Spring Boot 4.1.2 MVC 已有 Session/CSRF、租户与 Marketplace 范围的实际业务接口，涉及 Seller/店铺、SKU/Listing、订单、FBA、采购/供应商/仓储、财务、运营异常和员工权限。代码从 `apps/server/src/main/java/org/merchivra/erp/` 的 Controller 与服务实现核查；现有 [核心 API 历史说明](core-read-api.md) 是 V3 的设计/实现记录，不是完整现行列表。
- **真实 Controller/OpenAPI/Orval 契约已经部分落地**：`apps/web/openapi/finance-profits.json`、`fba-inbound.json`、`ads-reports.json`、`operations-v67.json`；与 `apps/web/orval*.config.ts`、`apps/web/src/generated/` 配套。详见 [Springdoc / Orval 渐进接入方案](../architecture/mature-tool-first-api-client-2026-11-01.md)。这些 JSON 只覆盖对应模块，不能误称“所有 REST API 都已迁移至 Orval”。
- 实际运行时为 **Spring Security Session + HttpOnly Cookie + CSRF**，不是历史候选 JSON 的 JWT `bearerAuth`；所有请求的服务端权限和原币/来源数据校验仍不可仅依靠 TypeScript 类型。
- **真实 Amazon Seller/Ads 生产出站与写入依旧默认关闭**；`/api/v1/demo/*` 是单独的合成 Mock 命名空间，见 [Mock 演示指南](../demo.md)。已有 OAuth/Worker 代码不代表真实 Amazon 授权完成。
- 功能冻结前应按 [当前 P0 计划](../roadmap/current-plan.md)、[工程实施规范](../governance/engineering-standards.md) 与 [P0 采购容量历史审计](../testing/p0-purchasing-read-cap-audit-v108.md) 检查每个实际 Controller/Orval 约束；G1/G2/G3 仍需审批。

**V145 来源合同补齐（2026-11-12）**：FBA 货件/采购 PO/ASN 的五条现有本地关联读写接口已纳入 `fba-inbound.json`，该模块共 15 个 operationId；[合同、测试与仍存在的 100/200 条容量限制](../inventory/fba-purchase-link-v75.md)。这不表示整个 FBA API 或全库分页已经完成。

## 契约设计原则

- 所有业务路径基于 `/api/v1`；只读操作不暴露 Amazon SP-API token、买家 PII 或未验证的租户 ID。
- **已实现的最小本地认证**采用 Spring Security Session + HttpOnly Cookie + CSRF，见 [SEC-003](../security/local-auth.md)。JSON 文件中的 bearerAuth 仍是**未同步到实际实现的设计候选**，不能用于推断现有端点使用 JWT；正式 G2/G3 冻结时必须更正。
- 金额 `{amount: "40.00", currency: "USD"}`，string Decimal 保持精度；未知/未分配不可默认为零。
- Orders 的履约/退款/财务状态并列；FBA 观测允许 null 数量（UNKNOWN）；利润预计与平台入账分别报告。
- Sync Run 是可重试运行，内部持久 Sync Stream 和 Amazon paginationToken 不暴露给 Web。
- 每个 operation 当前只有设计级错误列表（401/403/404/409 等），真实角色、字段限制、异常全集、认证方案仍须 G2 评审。

## G2 → G3 的检查

- [ ] DEC-09 登录/Session/Auth 确定后调整 securityScheme、CSRF 与会话响应。
- [ ] 完整的 OpenAPI linter 与 Schema 验证加入 CI；当前仅做引用、operationId、响应基础自检。
- [ ] 端点权限矩阵和所有异常响应与 [API-001](../architecture/api-contracts.md) 一致。
- [ ] 确认实体 UUID、业务键/商店范围与 [DATA-003](../architecture/physical-data-design.md) 一致。
- [ ] 使用全虚构 Mock fixtures 校验设计请求/响应。
- [ ] 根据所获 Amazon API 角色调整不可用/延迟数据状态。
- [ ] 通过 [G2-AC](../requirements/g2-architecture-acceptance.md) 并留签署记录。

参考：[OpenAPI 3.1.1 官方规范](https://spec.openapis.org/oas/v3.1.1.html)。
