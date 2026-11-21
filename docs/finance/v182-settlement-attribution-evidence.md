# V182 — 按费用来源的结算行归属证据裁决（保守只读）

状态：**业务代码已合并，精确业务 main SHA 完整合成 A3 首次 7/7 通过**；不等同于外部平台费用、重复扣费或银行到账认证。

**V182 本地结算行归属证据分类 A3 7/7（2026-11-21）**：业务 [PR #180](https://github.com/kunlong-luo/merchivra/pull/180) 已合并，精确 main 业务 SHA `85bc6adc6e7d79c603f2d736d0be53d4be3df833`；合并前 [快速 CI #44860177994](https://github.com/kunlong-luo/merchivra/actions/runs/44860177994) run_attempt=1 SUCCESS（Java25/Gradle、React19/Next16/Vite、Compose）；精确 SHA [完整 A3 #44860315026](https://github.com/kunlong-luo/merchivra/actions/runs/44860315026) run_attempt=1 **7/7 核心任务 SUCCESS**（PG18/Flyway、Java、React/Next16、Chromium、Compose HTTP、安全/恢复，额外设计专项按策略跳过）。V182 在现有 V165/V94/V95/V102 上同一只读快照归集，新增合成 PG18 HTTP 覆盖缺关联 UNKNOWN、V94 独立审核+审计后本地元数据支持、共用行/跨来源冲突优先；无新数据库审批表或利润入账。**本地支持不是外部 Amazon 扣费认证，冲突不等于重复扣费已证实，所有银行/利润认证及清除 hold 标志仍 false；Seller/Ads OAuth OFF，生产 #104 NO-GO**。


## 审计现状与不重复建设原则

- **V91/92**：人工费用来源、独立元数据审核、原币分摊及审计。
- **V93/V165**：跨来源本地身份重叠提示和独立冲突核查；任何本地编号/认领文档 SHA 相同都不是同一扣费的事实证明。
- **V94**：实际 settlement 行关联、独立 Owner 单次核查、不可修改的审核事件。只存在未核验的状态：`ESCALATED_NEEDS_PROOF`、`POTENTIAL_DUPLICATE_UNVERIFIED`、`NOT_SAME_CHARGE_UNVERIFIED`。
- **V95**：跨不同人工费用来源共用一条结算行的冲突计数及分摊审批数据库 hold。
- **V102/V181B**：仅新关联当时冻结导入报告与行级数据；本地 SHA-256 在读取时可复算，旧 V94 必须返回 `CAPTURE_NOT_AVAILABLE`。

V182 **不新建审核表/事件表、不回填旧证据、不改变 V94 审核状态、不清除 V95 hold、不增加生产授权**。使用单个 `REPEATABLE_READ` 事务读取已有 V165 检测和 V94 审核/审计、V95 共用行、V102 快照，从而得出有明确优先级的**当次只读判断**。

## 新安全 API

`GET /api/v1/finance/marketplaces/{marketplaceId}/cost-sources/{sourceId}/settlement-attribution`

- 沿用 Owner Session 租户身份，服务端决定 `tenantId`；不存在、非活动或跨 Marketplace 返回 404，禁止客户端传入租户 ID。
- `Cache-Control: no-store`；新接口无写操作，无新的远端 Amazon/银行调用。
- 返回 `status`、`reasonCodes`、各类关联行与快照/独立核查/审计计数、`independentReviewAuditComplete`、原有本地冲突 hold。
- `externalChargeVerified`、`duplicateDebitVerified`、`financialHoldCleared`、`netProfitEligible` 永远为 `false`。这是 V162/V64 成本阻断**证据输入**，并非解除阻断的批准动作。

## 判定优先级（不可逆历史 + 动态重算）

1. **`CONFLICT_EVIDENCE`**：存在同租户/同 Marketplace 的不同费用来源共用同报告同结算行、V165 其他来源本地身份重叠信号，或者关联结算行币种不一致、方向为贷方/零金额。独立审核已完成亦不能清除这些信号。该状态只表示*冲突待核验*，不等于已经证明重复扣费。
2. **`SUPPORTING_LOCAL_METADATA_UNVERIFIED`**：仅当**恰好一条** V94 行，恰好一条创建时 V102 快照，订单引用+原币金额强匹配，V94 审核结果为 `POTENTIAL_DUPLICATE_UNVERIFIED`，且存在与审核人及最终状态匹配的不可变 V94 审计事件，并且无第 1 项冲突，才表示*本地资料支持待核验假设*。即使满足，也不代表重复扣费已证实，不能从此结果过账。
3. **`UNPROVEN`**：无关联、仅弱匹配、历史缺 V102、未独立审核、审核审计缺失、审核处置不支持该假设、多条分摊候选缺外部归属证明，或读取证据计数不一致，都不得升级为已证明。宁可保持未知，不得用 `0` 代替未知费用。

**本次状态不是新“不可逆裁决”**：一旦后来出现新的共用行或来源身份重叠，读取时立即变为冲突；独立审核不可逆性来自现有 V94 触发器和 append-only audit，而非新服务自称历史证明。

## 测试与上线门禁

Java25 单元/Controller 回归覆盖：冻结精确单行+审核+审计的有限支持、旧链接缺证、未审核/缺 audit、共用行优先、其他本地身份冲突、贷方/不同币种、多条候选、计数不一致 fail-closed、Owner Session 租户绑定和 `no-store`。在已有 V165 合成 PostgreSQL18/HTTP fixture 里验证真实 API 的无链接/待审核/交叉归属冲突与跨站点 404，保证不能改变利润/银行事实。

合并后需对精确业务 `main` SHA 运行完整 A3（PG18/Flyway、Java25/Gradle、React19/Next16、Chromium、Compose HTTP），不能把 V181 以前的验收移作 V182 的结果。**Seller/Ads OAuth OFF；生产 #104 NO-GO**，V162/V64 真实净利润仍不具备外部费用归属、WMS 收货和银行对账的完整证据。
