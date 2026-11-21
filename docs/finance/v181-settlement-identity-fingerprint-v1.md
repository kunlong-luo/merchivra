# V181 补强：V102 结算身份快照的可复算 SHA-256 指纹

状态：**实现分支待 PR CI 验证**。原 V181 业务合并与完整 A3 验收见 [V181 文档](v181-immutable-settlement-line-identity.md)；这里不重新宣称既有 SHA 的验收涵盖新代码。

## 范围与信任边界

- **不新增** Flyway 表、结算行关联、审批、财务过账，也不重构 V94/V95 的独立复核或共用行 hold。仅对已冻结的 V102 元数据按固定规则提供本地 SHA-256 复算结果。
- 在原 `GET .../settlement-line-links/{linkId}/composite-identity` 响应中附带 `fingerprintVersion`、`fingerprintSha256`、`fingerprintOrigin`。原身份字段以及全部 `externalChargeVerified=false`、`duplicateDebitVerified=false`、`bankVerified=false`、`netProfitEligible=false` 不改变。
- 冻结的 V102 行返回 `MERCHIVRA_SETTLEMENT_LINE_IDENTITY_V1`、64 位小写 SHA-256 和 `DERIVED_ON_READ_FROM_FROZEN_V102`。**这是从已冻结快照在读取时派生的指纹，绝非“创建时已保存的指纹”或独立外部签章**。对在新逻辑推出前已经保存的 V102 行，同样可以复算，不伪装成曾保存该摘要。
- V94 历史行若未在创建时捕获 V102 快照，则仍返回 `CAPTURE_NOT_AVAILABLE`，上述三个新增字段均为 `null`。禁止从今天的原报告元数据对这些旧关联“补算”成原始创建时证据。
- 此摘要既不能独立验证文档 SHA-256 与外部 Amazon 原始文档内容一致，也不能证明来源 ID 真实、重复扣费、银行到账、费用可入账、退款/COGS 或 V162/V64 最终净利润。

## 规范 V1 输入（固定字段顺序）

使用 SHA-256（JDK 内建，**不是 HMAC / 电子签名**）。逐字段按 UTF-8 编码：先写 4 字节**有符号大端长度**，然后写字段字节；`null` 用 `FF FF FF FF`，空字符串用 `00 00 00 00`，两者不相同。最终摘要输出 64 字符小写十六进制。

字段顺序（都作字符串；由 API 鉴权与 V102 行数据取得）：

1. `MERCHIVRA_SETTLEMENT_LINE_IDENTITY_V1`（域分隔 / 版本）
2. 租户 UUID（小写标准字符串）
3. Seller Marketplace UUID（小写标准字符串）
4. 人工费用 source UUID（小写标准字符串）
5. V94 关联 link UUID
6. V102 结算报告 UUID
7. 结算行行号十进制文本
8. 原 Amazon 报告 ID
9. 原文档 ID
10. 文档 SHA-256
11. 交易类型
12. 金额类型
13. 金额描述
14. 原始订单引用
15. 有符号行金额，采用数据库 `NUMERIC(20,6)` 读取的 plain text，例如 `-12.340000`
16. 原币
17. 当时关联人 UUID
18. V102 捕获时间：从 `captured_at` 转换为 UTC `Instant.toString()`

不得折叠大小写、修剪业务字符串、把空值填零，也不得按 UI 本地时区修改时间。V1 不纳入审核状态、利润状态、相似度、当前银行状态等可变化的结果。升级字段序列必须用新版本号，旧版本按旧规范继续复算。

## 测试与部署门禁

新增纯 Java 离线单元测试：**固定黄金摘要**、重复计算、租户/站点/来源隔离、空值与空字符串不同、金额及文档摘要敏感、旧记录拒绝生成、所有财务认证标志仍为 false。原 V181 Controller/Service/SQL 语义不变。通过 PR CI 的 Java25/Gradle、React19/Next16/Vite、Compose；真实 PostgreSQL 18 / 完整 A3 只能在对应新业务 SHA 的验收完成后宣称。

保持真实 Amazon Seller/Ads 授权 OFF；生产 #104 **NO-GO**。V182 仍要解决独立外部身份和行级冲突裁决，不能因为这里出现 SHA-256 而放行。
