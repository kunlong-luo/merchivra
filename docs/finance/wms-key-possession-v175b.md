# V175-B · WMS 租户登记公钥的独立密钥持有权挑战

状态：**V175-B 已合并，完整合成技术 A3 首次 7/7 成功（2026-11-17）**。这不是独立外部 WMS 经营主体认证，不能证明真实退货签收或库存、历史 COGS、全成本净利润。

## 合成技术验收记录

PR [#166](https://github.com/kunlong-luo/merchivra/pull/166) 合并业务 SHA `325dd84eb4b02540de9ef27e9e2259b413dad204`；PR [CI #44086459396](https://github.com/kunlong-luo/merchivra/actions/runs/44086459396) **run_attempt=1、4/4 SUCCESS**（含真实 PostgreSQL18/Flyway V101 三名独立 Owner Spring HTTP+CSRF、OpenSSL Ed25519 正向/错签/重放与撤销）；精确业务 SHA [完整 A3 #44087005800](https://github.com/kunlong-luo/merchivra/actions/runs/44087005800) **run_attempt=1、7/7 SUCCESS**（PG18、Java25/Gradle、React19/Next16/Vite、Chromium、Compose HTTP）。

**验证范围**：V101 真实 PostgreSQL 18 迁移及复合 FK、三 Owner 真实 Session/CSRF、限定时间的 Ed25519 规范签验，错签、跨来源、同人决策、重放与来源撤销拒绝，以及 V39/V162/V64/银行/Ads 无过账。只验证合成密钥的控制权，**没有**独立真实 WMS 运营商身份证明、真实仓库/官方 FBA 实收或原币净利润核销；#104 生产继续 NO-GO、Seller/Ads OAuth OFF。

## 为何需要单独挑战

V173/V174 只验证收到的 Ed25519 签名和租户自己登记的公钥匹配；公钥登记及两名 Owner 批准并不意味着该密钥属于真实第三方 WMS。V175-A 三人独立审阅人工 QC 也不能填补该证明缺口。本轮 V175-B 引入**一次性、限时、不可篡改的持有权挑战**，将“掌握当前公钥对应的私钥”与“真实运营商身份/实物收货”严格分开。

## 契约

- Flyway V101：`finance_wms_key_challenge_v101` 保存（tenant、source、warehouse、256-bit 安全随机 nonce、challlenge UUID、UTC 到期、规范内容哈希、Owner 签发者）；`finance_wms_key_proof_v101` 保存独立 Owner 验证过的 Ed25519 原始签名和规范哈希。按 tenant / source / warehouse 复合外键及惟一约束，两个表均 append-only，防重放。
- 仅 `APPROVED` 租户登记公钥可签发；同来源 1 分钟内不可重复签发、任意滚动 24 小时至多 5 次。有效期 10 分钟、数据库最长 15 分钟约束。失效挑战不能复用，错签不创建证明。
- 规范内容必须为固定 UTF-8 LF 含末尾换行，字段顺序：`MERCHIVRA_WMS_KEY_POSSESSION_V1`、tenant UUID、warehouse UUID、source UUID、challenge UUID、nonce 64 位小写十六进制、expiry UTC ISO-8601；每字段独立一行。由持有私钥的一方离线做 Ed25519 detached 签名；**不上传私钥**。
- 只有另一位 Owner 可以验证，而且验证者不得是租户公钥登记者或挑战签发者；Java 和 PG18 插入触发器独立检查。Spring Session 获取 tenant/actor，CSRF 防护写入，no-store 响应；不可由请求参数设置已验证的仓库经营主体。
- V98 公钥被撤销后，历史有效持有权签名保留作为审计材料，但动态标记 `SOURCE_REVOKED_HOLD`，不再显示来源可用。
- 每条结果的 `operatorIdentityVerified=false`、`physicalReceiptVerified=false`、`inventoryPosted=false`、`cogsReversalEligible=false`、`actualCogsReversal=null`。不修改 V39/V162/V64 任何业务库存或会计余额。

## API 与页面

在 `/api/v1/finance/wms-signing-sources/{sourceId}/key-possession` 下：

- `GET /`：当前租户指定公钥最近 10 条持有权挑战、时间与动态状态。
- `POST /challenges`：生成一条不可变挑战，限流与租户 Owner 会话保护。
- `POST /challenges/{challengeId}/verify`：`{signatureBase64}` 由独立 Owner 验证。签名需匹配 tenant+warehouse+source+challenge+nonce+期限，单挑战仅一条证明，重复验证 409。

React 19 + Next.js 16 工作台 `WmsSignedEvidencePanel` 新增 `WmsKeyPossessionPanel`，可复制规范原文与粘贴 detached Ed25519 签名，明确显示“仅密钥持有权”，不称外部经营主体已认证。

## 验收要求

1. Java 25 JUnit 覆盖 Ed25519 真实签验、tenant/source/warehouse/nonce/expiry 任何字节变动失败，Controller Owner/CSRF Session 调用隔离，SQL 不可变与强制 false 结构。
2. 专项 PostgreSQL 18：Flyway V101，真实 Spring HTTP 三个独立 Owner、会话/CSRF，临时生成的合成 Ed25519 私钥离线签名（仅 disposable runner），非 Owner、同人批准/验签、错签、重放、公钥撤销、来源隔离均必须拒绝；V39/财务报表/Ads/银行数量零变更。
3. 合并后必须运行**精确业务 main SHA**的完整 A3 7 个 job 后才能写入合成技术验收。

## 后续必须补齐的 P0

V175-C：从独立第三方可核验渠道获得并验证 WMS 法人/域名/服务运营身份与公钥绑定，外部可查询或可证伪的签收/质检/分件来源，真实 WMS/官方 FBA 接入。未获得独立源信息不能把本挑战当身份认证。

V176：真实独立签收和可售 QC 审核通过以后，才讨论受控库存、原历史成本及原币 COGS 回转。V162/V64/Next 的真实利润及生产 G1/G2/G3 仍是 NO-GO，Seller/Ads OAuth OFF，#104 不开放。
