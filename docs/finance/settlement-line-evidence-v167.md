# V167 · 费用来源与 Settlement 交易行的可审计本地关联（未核销）

> **状态：代码候选，待 PR CI、Flyway PostgreSQL 18/HTTP 真实验证。**
> 这是 V166 冲突案件后的增量，不把 V166 的人工初判等同于本次交易行证据。

## 为什么需要这一层

V165/V166 只能识别相同来源键、人工宣称的报告 SHA 或参考编号。报告 ID 与 SHA 是**报告级身份**，不能代表某一笔已证实的双重扣费。V167 将 V163 的原币费用凭证关联到 V54 已入库的**具体 Settlement 报告行**（实际 `report_id + line_number`），保存不可变来源/行金额与币种快照、登记者、案件状态和不可修改的操作审计。

没有链接时应显示「尚无交易行证据」，不能推断为零成本或已经对账。当前由 Owner **人工选择已有报告和行号**；它不是自动从 Amazon 下载、验证发票真实性或发现所有匹配项。只使用本地已持久化 `source_origin=LIVE` 的报告记录；隔离 CI 中的 LIVE 标志仍可来自合成数据，并非用户真实 Seller 授权。

## 分类与业务语义

分类取自同租户、同 Marketplace 的 V91 来源和 V54 已入库的原始报告行。所有分类均是**关联线索，而非财务结论**：

| `similarity` | 解释 |
|---|---|
| `CURRENCY_MISMATCH` | 两条记录原币不一致，不能直接比较金额 |
| `CREDIT_OR_ZERO_NOT_DEBIT` | 平台行为正数或零额，不是当前负向扣费 |
| `SIMILAR_REFERENCE_AND_AMOUNT` | 人工来源键与交易行 `external_order_id` 完全相同且负向扣款绝对值相等；仅强烈相似，不等于独立费用同一性证明 |
| `SIMILAR_REFERENCE_ONLY` | 相同订单/参考编号但借方金额不等，不能判定重复 |
| `AMOUNT_ONLY_NO_IDENTITY` | 金额相同，但没有相同的引用，不能凭金额判重 |
| `UNRELATED_LINE_NO_EVIDENCE` | 没有引用及金额匹配的证据 |

特别注意：手工单据 `source_key` 与结算行的 `external_order_id` **并不保证是同一种编号类型**；即使二者字符串相同，也可能仅表示相同订单的多笔正常费用。V94 从不产生 `CONFIRMED_DUPLICATE`、`REVIEWED_COST`、`CLEAR`、`REALIZED_NET_PROFIT`。

## 数据库及审计约束

Flyway `V94__cost_settlement_line_evidence_links.sql`：

- `finance_cost_settlement_line_link_v94`：同租户/Marketplace + 来源 + 已存在的 Settlement Report ID/Line Number，唯一约束保证重放幂等；复合 FK 阻断跨 Marketplace 报告行的直接挂接，实际行复合 FK 防止虚构行号。
- PostgreSQL 触发器二次核查 V91 来源及 V54 行金额/原币快照和六类状态计算，禁止改写来源、归属、交易行、金额、分类及首次登记信息；独立复核一次性 `OPEN` → `ESCALATED_NEEDS_PROOF`、`POTENTIAL_DUPLICATE_UNVERIFIED` 或 `NOT_SAME_CHARGE_UNVERIFIED`，不得由来源创建人或登记该关联的人复核。
- `finance_cost_settlement_line_audit_v94` 只追加固定操作码、操作者和时间，复用 V92 审计禁止修改/删除的数据库触发器，不存原始发票、订单编号、审核理由、买家 PII、Access Token。
- 来源、报告、行都以当前数据库内容确认；对既有关联再次登记必须核对快照，一旦发现变化返回冲突。V94 不构成**对未来所有导入/修改的全局并发冻结**；需要后续单独解决批次封账与版本化重算。

## API（Session Owner / CSRF / 不缓存）

`/api/v1/finance/marketplaces/{marketplaceId}/cost-sources/{sourceId}/settlement-line-links`

- `POST /`：`{"reportId":"<uuid>","lineNumber":12}`。仅服务端 Session 提供租户和用户身份，从同租户/Marketplace 已入库报告行读取全部金额/币种/来源；不允许客户端直接指定 `similarity` 或金额。相同完整关联重试幂等且不追加第二条 OPEN 审计。
- `GET /`：最近 100 个关联及分类/状态，最多 100 条**不是全库收入证明**；没有关联返回空列表，表示「未知」而非「无费用」。
- `POST /{linkId}/review`：`{"disposition":"ESCALATED_NEEDS_PROOF","reason":"Independent review requires external invoice"}`，只允许另一 Owner 一次性人工研判，永不财务放行。
- `GET /{linkId}/audit`：该来源下该关联最近 100 条追加审核记录。
- 所有响应均使用 `Cache-Control: no-store`；Owner、Session 与 Spring Security CSRF 继承现有控制器约束。该端点**没有平台远程读取/写入能力**，没有对外开放原始敏感字段。

## 全成本/利润边界和下一步

本次仅让**费用归属线索可追溯**，不能证明真正重复扣费，也不能把某笔已关联的手工来源直接变为 V162 `REVIEWED_COST`。不改 V164 金额守恒、不取消 V165 审批冲突 409、不写 V33/V64 利润快照、不让 Next.js 显示虚构「真实净利润」。后续按以下次序推进：

1. 批量/游标方式检查完整结算、Finances 与 Ads 费用交易行（当前 V54 行库最多 10,000 行/报告）；引入平台扣费事件的**交易级主键**、实际账单行与负载摘要的版本证据，避免把订单 ID 当成扣费 ID。
2. 建立独立外部凭证验证的事实类型、费用归属时间/订单/SKU、重复/冲正/税/汇兑/退款规则、冲突消解与不可变撤销及期间冻结；先完成真正「同一笔扣费」证据认定，才能决定唯一财务扣费归属。
3. 将通过独立核验且符合 V33 基线去重的金额明确接入 V162 八类完整性判断与 V64 版本化报表/CSV；Next.js 16 利润中心必须区分已知贡献、待核对费用、未知、不可计算原因和原币，不得混币种。
4. 通过 Flyway PG18、真实 Session/CSRF/双 Owner/重复请求/跨租户/跨 Marketplace/行号伪造和并发验证后，才考虑合并。G1 财务规则、G2/G3、真实 Seller/Ads 联调均须维护者本地单独批准。

**验收状态**：离线 JUnit 只覆盖分类、独立复核、接口作用域和 DDL 约束静态断言。未运行 PG18/HTTP/Next 浏览器实机测试之前不得称 V167 验收完成。生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) 仍 OPEN/NO-GO，Seller/Ads OAuth 继续关闭，Next.js 16 为正式前端目标，Vite 仅用于受控回退。
