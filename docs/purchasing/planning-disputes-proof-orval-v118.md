# V118 — 供应商采购计划、差异索赔与处置凭据的 Orval 统一

> 2026-11-09，A1 / Issue #62。基线 V117 main `bb100ec`。V118 是既有业务接口的**OpenAPI/Orval 标准化**，不新增 Amazon、供应商外部 API 或财务记账。

## 合同与迁移范围

`apps/web/openapi/purchasing-v114.json` 保留原文件名以复用生成脚本，版本更新为 **118.0.0**，从 V117 的 **71** 增加到 **100 个唯一 operationId**。本阶段新增 **29** 个：

| 子域 | 接口 | 重要语义 |
| --- | ---: | --- |
| V42 供应商单 SKU MOQ/箱规/交期/原币报价及本地采购计划 | 11 | 规则全租户 keyset、仓库补货推荐与计划 keyset、设置/删除规则、人工 propose/convert/cancel；`unitCost` 始终是 decimal **string**。`NO_LEDGER` 时 `onHandQuantity`、推荐采购量可能为 **null**，绝不当 0。转换仅生成本地 PO 草稿，不向供应商下单。 |
| V45 实物采购差异、争议与供应商索赔 | 14 | 差异/索赔/可索赔来源及不可覆盖事件的 keyset 分页、幂等上报、人工 review/close、索赔草稿/提交/独立 approve/close/cancel 和绩效计数。来源是否已被索赔必须由后端全租户验证，客户不能靠当前 50 行自行判断。**本地索赔状态不是外部供应商承认或退款确认。** |
| V51 处置证明**元数据**提交、列表和复核 | 3 | `requestKey`、真实处置来源、证据参考编号、可选 SHA-256 和双人复核；`METADATA_REVIEWED` 不是原件真实性验证。 |
| V72 采购本地交付概览读取 | 1 | 仅通过同源 Session 读本地 ASN、QC、采购单状态，不代表 Carrier 或 FBA；旧接口依然 **LIMIT 300**。 |

前端 `purchase-planning.ts`、`supplier-disputes.ts`、`lot-disposition-proofs.ts`、`purchase-delivery.ts` 均复用 Orval v8 生成函数与 `merchivraFetch`，继续用原有 Zod 对服务器实际返回结构进行 fail-closed 校验；既有业务调用函数签名保持。所有动态路径的 UUID 在插值前先 Zod 校验。POST、DELETE 使用统一 CSRF/Header/Session/cookie/no-store。

**重要例外**：V52 私人证据附件 `POST /lot-proof-files/{proofId}` 是有真实字节/大小/MIME/SHA 的 `multipart/form-data` 上传，刻意不迁入这轮仅支持 JSON 的 Orval mutator。原 `lot-evidence-files.ts` 继续保留单独的 FormData、强制文件大小≤2MiB、PDF/PNG/JPEG 检验、CSRF；二进制下载使用 `attachment`、`nosniff`、no-store，不能误当 JSON。其他供应商退货发运/回执审计、证据元数据关联和端到端 FBA 对账仍有待迁移/联调。

## 数据完整性与分页审计

- V42 SKU 价格/采购规则已存在服务端完整分页，`GET /planning/supplier-rules/page`；仓库补货推荐、计划已有 keyset。V45 差异/索赔、可索赔来源及事件也已有服务端全页接口。本轮**直接使用已实现的端点**，未再创建第二套分页逻辑。
- V42 兼容旧 `/supplier-rules` 仍有限条数（500）；V45 兼容查询仍可能有上限；主工作台已存在服务器分页路径，不能将旧数组视作全租户真值。
- V72 `GET /delivery/overview` 的 Java 代码仍只查询最新 300 条，`Totals` 仅从这 300 条计算。原 UI 容易把这个结果误认为租户全部采购交付。本次**将页面标题和提示明确改成“当前返回行汇总，最多 300”**，达到上限时高亮语义警告；这只是防止误导，**没有解除后端 300 条上限**。完整按全库分页/真正全局汇总列为下一阶段优先级。
- V51 证明元数据列表仍受旧查询大小限制，附件二进制不混入普通 API DTO。供应商仓库之外的标识不通过浏览器参数控制租户。

## 测试

- 旧 V42/V45/V51/V72 Vitest 仍检验服务器路由、库存未知与原币、状态机、审批和证据边界；此前手写对象请求头断言更新为可兼容标准 `Headers` 的 CSRF 检查。
- 新 `planning-disputes-orval-v118.test.ts` 涵盖采购价格字符串与 CSRF、规则 DELETE 真正 204、采购计划 keyset、供应商差异/索赔/事件多列表游标、索赔关闭备注、SHA256 元数据区别于二进制、错误 UUID、本地库存 UNKNOWN、409 和不一致分页响应的拒绝。
- Java 业务服务与 PostgreSQL 18 schema **本轮不改**，保留现有仓库/PO/ASN/审批/金额/库存锁等事务约束。
- 快速 CI 仅包含 Java 25 Gradle 单测、React/Vitest/Vite/Next 类型检查和 Compose 静态检查；A3 `full_suite` 真正运行 PostgreSQL 18 Flyway + HTTP + 跨租户/Owner/CSRF/并发库存验收仍未完成，不能以“类型检查通过”替代。

## 下一阶段和上线门槛

保持 [Issue #62](https://github.com/kunlong-luo/merchivra/issues/62) 开放：优先补 V72 300 行交付汇总的全库正确性，补 V51 证明/退货追踪及供应商绩效附属合同；之后集中 PG18 验收，停止无限增加功能。Next.js 正式服务器路由、反向代理和 Session+CSRF 联调由 [Issue #63](https://github.com/kunlong-luo/merchivra/issues/63) 管理；GitHub Pages 仅为只读演示。真实 Amazon Seller OAuth/SP-API 保持关闭，等用户本地执行授权和真实测试。
