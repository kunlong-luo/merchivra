# V175-B · 外部仓储运营商身份与实收证据准入合同（设计草案）

状态：**PROPOSED / NOT IMPLEMENTED / NO-GO**。本文件是 V175-A 后续实现和验收的约束清单，不是完成声明；不改变 #12、#104、G1/G2/G3 或 Seller/Ads OAuth 的关闭状态。

## 当前边界

V173 的 Ed25519 公钥由租户 Owner 登记，V174 的签名数量/处置差异保留原件，V175-A 的三人 QC 是内部人工复核。上述任一信号、乃至三者组合，都不能独立证明外部仓储运营商的真实身份、货物物理实收、可售库存或历史 COGS 冲回。任何新 API 默认保持 `operatorIdentityVerified=false`、`physicalReceiptVerified=false`、`inventoryPosted=false`、`cogsReversed=false`、`realizedNetProfit=null`。

## 下一增量的最小可审计合同

1. **独立身份锚点**：外部运营商主体标识、仓库与服务合同范围、可信登记来源、验证人/验证时间、有效期、撤销与密钥轮换均要有不可变证据。租户自己上传的证书、公钥、邮件或 JSON 不得自证运营商身份。没有独立验证渠道时只保存 `UNVERIFIED`，不得设置 `VERIFIED`。
2. **来源与实收分离**：签名报文身份验证、运营商主体认证、具体货件实收、QC 可售判定是四个不同状态；缺任何一环都不得过账。绑定 tenant、marketplace、warehouse、refund event、intake、外部 receipt ID、原始 payload SHA-256、来源版本与时刻；跨租户/仓库/事件引用一律拒绝。
3. **幂等与数量守恒**：以经验证的运营商 + 仓库 + 外部 receipt ID 作为重放键；同一物理包裹、同一退款行跨 V172–V175 不能重复认列。对部分收货、分包裹、拒收、损坏、撤销、重发和并发竞争锁定来源并保留 append-only 历史，超出可退数量返回冲突而非截断。
4. **独立审批**：来源录入人、运营商身份核验人、QC 审批人、库存/会计过账审批人须按 G1/G2 职责分离；同人、过期授权、已撤销密钥、证据变化后审批全部 fail-closed。外部身份认证必须由真实可信来源支撑，内部多 Owner 互审不能代替。
5. **财务边界**：V39 库存调整与退款历史 COGS 只能在单独批准的过账设计中原子、幂等执行；历史采购批次、原币、成本生效日、退货部分数量、汇率政策及原先销售 COGS 逐笔追溯。Finances 退款不能重复扣费；V162/V64 不得因本合同或内部 QC 通过而生成已实现净利润。
6. **安全与隐私**：保留原始外部回执的不可变哈希与受控存储引用，禁止将敏感原件、密钥或个人信息提交到仓库。API 复用 Owner Session、CSRF、tenant/marketplace 作用域、最小权限、no-store、OpenAPI/Orval；Next.js 16 为目标前端，Vite 仅回退。

## 分阶段实施与验证

**B1：身份登记/撤销（只读可信度）**。PG18 Flyway 新表及不可变审计，服务和 API 提供 `UNVERIFIED/PENDING_INDEPENDENT_PROOF/REVOKED/EXPIRED`；只有明确外部可验证的证据路径及独立审查机制经过安全评审后，才能引入 `VERIFIED`。B1 不改变库存或财务。

**B2：外部实收证据关联（仍不自动过账）**。为已认证运营商的外部 receipt 增加唯一索引、复合 FK、签名和来源绑定、版本与撤销检查、差异隔离及分页审计。真实运营商接入不可用时使用合成 fixture 并标为 `SYNTHETIC_ONLY`，不伪装生产认证。

**B3：库存/COGS 过账（独立 G1 审批后另立 PR）**。先确认 DEC-06/07/08、来源及可售 QC，再设计可逆的会计调整与原币守恒。没有真实来源、审批和账期证据时保持禁入。

每一阶段须有 Java 25 / Gradle / Spring Boot 4.1.x / PG18 Flyway + HTTP、Owner Session/CSRF/跨租户、重复 receipt 并发、密钥撤销、过期/伪造来源、负数/超量/分批、金额精度和无库存/COGS 副作用的负例；Next 16 / OpenAPI-Orval 合同校验。仅在代码实际存在后运行快速 CI，合并后按精确 main SHA 运行完整 A3；未执行不得打勾。生产 #104 仍需真实目标环境、独立 G1/G2/G3 签署，Seller/Ads OAuth/写入继续 OFF。

## 交付判断

本设计文档的合并仅确认**下一阶段接口与安全边界已被记录**，不代表 B1/B2/B3 已实现、真实仓储身份可信、PG18 本轮已通过、净利润已核销或 ERP 正式上线。
