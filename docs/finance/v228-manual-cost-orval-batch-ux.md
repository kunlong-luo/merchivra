# V228 · 有界手工费用批量暂存的 Orval 客户端与 Next.js 财务入口

**2026-11-24；现行实现已提交 `main`，真实 PostgreSQL 18 与浏览器 A3 需要按精确代码 SHA 完成后才能宣称通过。** 本阶段交付的是 V226 已验证 Owner-only **1–20 条本地 `MANUAL_UNVERIFIED` 费用来源暂存**的可操作入口，不是 Amazon、Ads、银行、货代或税务证据的真伪验证。真实性、G1/DEC 审批、退款 COGS、利润过账、生产 #104 均继续独立阻断。

## 实际运行中的 Springdoc 合同问题与修复历史（不可删除）

1. [V228A SHA `ccec47d9b0b8`](https://github.com/kunlong-luo/merchivra/commit/ccec47d9b0b8603d7a8a3f51ece3608f3cae3065)、[完整 A3 #45359294822](https://github.com/kunlong-luo/merchivra/actions/runs/45359294822)：**6/7**。真实 V221–V227 PG18/Owner HTTP 通过、Next Orval + Vitest 92 files/452 tests 通过；新增 Springdoc schema 断言在 200 响应采用实际 `*/*` 媒体键而非 `application/json` 时抛 `KeyError`。**未通过**新合同校验；仅修复测试对 HTTP 媒体键的错误假设，未修改生产接口。
2. [V228B SHA `ecd02b5bd5fc`](https://github.com/kunlong-luo/merchivra/commit/ecd02b5bd5fc0b33b15ee2a1c91f4303ec220c15)、[完整 A3 #45360086678](https://github.com/kunlong-luo/merchivra/actions/runs/45360086678)：**再次 6/7**。`*/*` 已被接受，真正的 `Source[]` 元素 schema 缺少所需的 V91 原始来源属性集合；严格核对失败。**这不能简单改成不检查 Source 以冒充 Springdoc 可信**。
3. V228C 新增 Java `@Schema(name="ManualCostDraftV228")` 与 `@Schema(name="ManualCostSourceV228")`，以唯一名称避免嵌套 `Draft`/`Source` 与其他 Springdoc 模型名称混淆，另加 Java 回归测试验证该身份；`scripts/check_manual_cost_openapi_v228.py` 在字段缺失时输出实际 schema 引用、可见字段和缺项以便下次诊断。**没有改变 V91 数据表、金额、HTTP JSON 或事务行为**。[V228C 精确代码完整 A3 #45360965067](https://github.com/kunlong-luo/merchivra/actions/runs/45360965067) **尚待最后验收**，不能借前两次的 6/7 宣称完成。

## 为什么现在要做、哪些能力直接复用

[V226](v226-bounded-owner-manual-source-batch.md) 已建立真实 Spring/PG18 POST `/api/v1/finance/marketplaces/{marketplaceId}/cost-sources/batch`，支持 1–20 个同 Marketplace 的费用来源、一笔 Spring 事务、单条来源独立 UUID 和 STAGED audit、完全相同事实重放不写入、不重复审核、旧 key 原始金额/文件摘要/业务日期冲突 HTTP409 整批回滚；[V227](v227-manual-batch-scale-replay-and-lock-evidence.md) 已真实 PG18/Owner HTTP 测量不同规模与重放、同租户跨 Marketplace 版本守恒、行锁等待。**没有理由再造第二套来源录入/归属/审核或手动绕过现有安全请求层。**

现行 Next.js 财务工作台之前只有 V21 财务事件手工录入，与 V91 原币费用来源不是同一种事实，不能为了方便将二者混成同一来源。V228 在现有财务原币和成本核查区域增加独立的费用暂存面板，明示 `MANUAL_UNVERIFIED`、不参与真正的净利润。

## V228 实现契约

| 层级 | 具体实现及不可放松的约束 |
| --- | --- |
| OpenAPI | `apps/web/openapi/manual-cost-v228.json` 为 **V226 现存 Java Controller 与 `Draft/Source` 的审阅契约快照**；`operationId=stageManualCostSourceBatch`，路径与 1–20 条限制明确列出，POST 需要 Session cookie + CSRF。不是用于创建新的生产端点，也不声称快照字段自动获外部证明。 |
| Orval | `apps/web/orval.manual-cost.config.ts` 复用 Orval v8 `react-query/fetch`、已有 `merchivraFetch`。`apps/web/scripts/ensure-purchasing-generated.mjs` 作为既有 `prebuild/pretest/pretypecheck/predev` 入口，新增按快照 SHA-256 自动重生成手工来源客户端；生成文件在 `src/generated` 下被 gitignore，**不手改生成代码、不引入 npm 或第二个 HTTP 客户端**。`pnpm run api:generate` 也包含本合同。 |
| 安全传输 | 实际调用生成的 `stageManualCostSourceBatch`，由共同的 `merchivraFetch` 在应用相对 `/api/v1` 路由下使用 same-origin cookie、CSRF、`no-store`；tenant 只能从 Session 得到，浏览器只传当前可访问 Marketplace UUID。 |
| TSV 输入 | 从电子表格复制带制表符的 **6 列**，顺序固定为 `category, sourceKind, sourceKey, documentSha256, businessDate, amount`，1–20 行且无表头。Currency **不能由逐行文本传递或推断**，只从当前 Marketplace 元数据取得。不得粘贴客户数据、银行账号、真实授权令牌或原始发票全文。 |
| 前置校验 | Zod 校验类别、来源类型、来源 key（8–128）、SHA-256（小写 64 位、非全零）、真正存在的 ISO 日期、正数原币金额（1–14 位整数、最多 6 位小数）、同 batch `sourceKind+sourceKey` 唯一、1–20 上限。任一行错误则整批不可提交，不存在前端“只提交合法行”的隐式部分成功。原币金额不经过 JavaScript Number、汇率或浮点换算。 |
| 返回校验 | 不仅依赖 HTTP200，还要求 `Cache-Control: no-store`、精确数量/输入顺序、互异原始来源 UUID、服务端 Marketplace/币种/类别/key/claimed SHA/业务日/BigInt 六位小数单位金额与输入一致，`origin=MANUAL_UNVERIFIED`，状态只能是 `STAGED/REVIEWED_UNVERIFIED/REJECTED`。历史同事实回放可能已由别人内部审核或被拒绝；**不可把响应条数当作新建数量**。 |
| 失败与切换 | HTTP409 显示明确的**整批来源冲突与不可假设部分成功**提示，不做逐行虚构错误定位；Session/CSRF 失败走既有安全层。切换 Marketplace 时使用 React key 重置输入、预览与返回，避免把上一个站点的原币或记录带到新的站点。 |
| 产品身份 | 入口仅位于已由 `App.tsx` 验证 `viewer.isOwner` 可进入的财务视图，**服务端仍独立执行 Owner/CSRF/租户/原币和 V91 不变审核约束**。只写 V91 本地 `STAGED/MANUAL_UNVERIFIED`，既不批量审批，也不增加 V92/V112 归属、Amazon OAuth、退款 COGS 或利润入账。 |

## 新增回归与验收口径

- `apps/web/src/lib/manual-cost-batch-v228.test.ts`：合法 TSV、无效/超量/重复 key、全零 SHA、零/负/超精度金额、不存在的业务日、跨币种、Orval 生成调用、无 `no-store`、服务端 Marketplace/金额漂移拒绝、已审核未核证 replay 保留原状态。
- `scripts/check_manual_cost_openapi_v228.py`：在一次性真实 Spring Boot + PG18 A3 内，从 **实际运行的 `/v3/api-docs`** 读取 Controller mapping，对照审阅快照的 operationId、path `marketplaceId`、JSON `Draft[]`/返回 `Source[]` 关键字段；缺少 Controller 或误改接口必须 fail-closed，不能依赖设计文档“看起来有路由”。
- `.github/workflows/ci.yml` 在真实 PG18 接续原 V221–V227 财务来源与 Owner HTTP 负例后执行上述核查；快速 CI 经 `pnpm run api:check`、`pnpm run typecheck`、`pnpm run test`、`pnpm run build` 和 `py_compile` 检查。
- 需要**精确代码 SHA** 的快速 CI 和完整 A3 7/7；如果 Orval 类型、实际 Springdoc 或 Java/Next/Cookie 安全回归失败，不宣称本轮通过。下面验收结果将依据实际日志更新。

## 必须保留的风险与下一阶段

TSV 粘贴只是一个受控的批量暂存体验，不能保证引用的 SHA 属于真正的外部发票，也不能代替独立 Maker/Checker 或银行对账。新面板的成功表示“服务端接受/重放原始来源事实”，不表示全部新建，更不等于真实费用确认。

**V229 候选**应回到评分短板与财务北极星：制定合法外部费用文件/行核验的可独立验收链和“从暂存到经第三方确认”的证据状态门禁；在维护者本地完成授权前仍保持全部生产开关关闭。其他待办包括移动端可用性、导入错误恢复/访问性真实浏览器验证、真实目标环境 TLS/PITR/KMS/告警、G1/DEC-06/07/08 独立审批。不要把更多页面当作财务闭环。

项目当下 `realizedNetProfit=null`，真实 Seller/Ads OAuth OFF，生产 [#104](https://github.com/kunlong-luo/merchivra/issues/104) **NO-GO**；评分按同一六维标准在 [新复评分表](../governance/project-scorecard-v228-2026-11-24.md) 单独记录，不能借 CI 通过百分比自动增加真实利润分。
