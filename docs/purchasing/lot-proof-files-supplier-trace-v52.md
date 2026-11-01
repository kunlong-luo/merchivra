# PO-015 · V52 实际凭据原件上传、SHA-256 校验、原采购供应商退货来源及 PostgreSQL 18 迁移验证

V52 承接 V51 的 `erp_local_lot_disposition_proof` 人工证据编号、可能填入的 `document_sha256` 以及独立 Owner 的内部元数据复核状态。本阶段**首次实现真正的二进制原件上传、服务端 SHA-256 校验、授权下载**，并增加已隔离的 V49/V50 供应商退货从原始 V20 真实采购订单追溯供应商的本地发运/手工收货回执。所有数据仅供当前租户 Owner 使用；绝不冒充原件由外部平台验证。

## 1. 私有原件（数据库存储，而非外部对象仓库）

- `erp_local_lot_disposition_proof_file` 为 V51 元数据 proof 记录提供**最多一个**不覆盖的原件副本，按 tenant、proof ID 和 tenant/requestKey 各自唯一。保存原始 BYTEA 字节、从字节检测的真实 `application/pdf`、`image/png` 或 `image/jpeg`、字节长度、由服务端 MessageDigest 算出的 SHA-256、小写十六进制摘要、上传 Owner 和时间。
- Spring Boot `POST /api/v1/purchasing/lot-proof-files/{proofId}?requestKey=UUID` 接收 CSRF 保护的 multipart `file`，前端不自行设置 multipart Content-Type。Spring 层 request 上限 3MB、单文件 2MB，Java 层再次验证 `1 <= size <= 2*1024*1024`，基于**实际字节前缀**识别 PDF/PNG/JPEG，而不相信上传文件的扩展名和 MIME 声明。未登记 V51 元数据 proof、非当前租户、元数据已审核/已拒绝的记录不能新上传。
- 如果 V51 `document_sha256` 事先填写了 64 位小写摘要，则必须与上传原件服务端计算的 digest 完全一致；不匹配立即拒绝保存。未填写时仍单独保存服务端实际 digest，供审计。一次上传成功后不可用另一个 requestKey/其他人/不同字节覆盖；同 requestKey、proof、actor、类型、长度及 SHA 全部一致才返回原上传结果。
- `GET .../{proofId}` 只返回元数据（没有文件字节）。`GET .../{proofId}/download` 需要有权访问该 tenant proof 的 Owner session；读取时再次以服务端 SHA-256 和长度校验字节，响应 `Content-Disposition: attachment`、`application/octet-stream`、`X-Content-Type-Options: nosniff`、`Cache-Control: no-store`，安全下载命名由 UUID 派生，不能被客户端文件名注入。无原件则返回 404。原件只存储在当前部署的 PostgreSQL 数据库中，不是跨区对象存储、病毒扫描系统、外部验签服务或证据法律效力认证。
- 即使上传成功且 SHA 与事先登记的摘要一致，状态也仅代表“该字节流与已登记摘要一致”，**不是**已验证供应商签名、承运商物流或原件真实性。V51 `METADATA_REVIEWED` 仍仅表示人工内部复核元数据，不应改写为 `EXTERNALLY_VERIFIED`。

## 2. 真实采购来源的供应商退货关联

- V49 `EXPIRED_QUARANTINE` 对已经 V46 放行、后来过期的商品写出一次 `LOT_QUARANTINE` 负库存；V50 `SUPPLIER_RETURN` 处置随后经独立 Owner 审批、报告执行。V52 新增 `erp_lot_supplier_return_trace`，仅在处置状态为 `EXECUTED_REPORTED` 且 method 真是 `SUPPLIER_RETURN` 时可以登记人工退货发运。
- 来源链严格按 tenant 连接：`disposition.quarantine_action_id → V49 source_position_id → V48 position.receipt_id → V20 erp_purchase_receipt.purchase_order_id → erp_purchase_order.supplier_id`；原 `receiptId`、真实 `supplierId`、已隔离数量、人工运单参考号、发运记录说明、Owner、时间都持久化，且同一处置唯一。**不允许客户端随意指定 supplierId 或退货数量**。
- V44 `erp_supplier_return` 专门对应**质检不合格品**的退货来源，而本 V52 流程对应**以前已质检放行、后续过期隔离**的商品，因此不能将其当成同一份实物重复关联、重复扣库存或虚构 V44 退货量。
- 另一位非发运操作人的 Owner 手工核对供应商收到的数量及参考号：全额为 `SUPPLIER_RECEIPT_REPORTED`，少收为 `RECEIPT_MISMATCH`（允许 0 件），超出原已隔离量直接拒绝。再次用相同数量、参考号、原因和 Owner 访问已报告记录可以幂等重读。该状态是**人工提供/核对的供应商回执报告**，绝非 Amazon SP-API、承运商 API 或供应商系统在线验收；V52 不由这些报告新增或扣减本地 signed warehouse stock，也不会自动建立应收款或财务收益。
- API：`GET /api/v1/purchasing/lot-supplier-returns/warehouses/{warehouseId}`、`POST /api/v1/purchasing/lot-supplier-returns`（requestKey,dispositionId,carrierReference,reason）、`POST /api/v1/purchasing/lot-supplier-returns/{id}/receipt`（receivedQuantity,receiptReference,reason）；各端点租户由当前 Owner session 决定，POST 需要 CSRF。

## 3. React 和 CI

- React 采购与仓储 Owner 工作台新增 V52 私有原件、实际 SHA-256 显示和私有下载，来源处置人工发运、原 supplier/receipt 查询及不同 Owner 的回执差异。
- Java/Mockito 新增：真实字节 magic、文件大小、上传后 SHA 规则、不能附加已审核证据、实际源供应商由原采购订单读取、非供应商退货的处置禁止发运、发运者不得自报供应商回执、差异只记证据不二次改库存。Vitest 新增 multipart CSRF、前端大小/格式校验、回执状态和不走库存出库接口。
- `.github/workflows/ci.yml` 增设 `PostgreSQL 18 / real Flyway migrations` job：启动全新的 `postgres:18-alpine` 服务，运行实际 Spring Boot + Flyway **全迁移**，等待健康检查并验证真实数据库 server_version_num=18、V46–V52 共 7 个迁移版本成功及新表存在。它与现有 Java 单测、React/Vitest 构建、慢速全链 Compose 冒烟是不同的验收维度；仅运行完成并出现成功结果后，才可在项目进度声明 PG18 真实迁移通过。

## 后续未完成

目前没有对象存储加密/密钥轮换与病毒扫描、证据文件外部数字签名验证、真实供应商/承运商回执 API、供应商退货财务红字冲销和结算对账；**不能**把上传的 PDF 自动标记为外部证书已认证。需要按私有原件数据保护要求安排 PostgreSQL 数据备份、容量监控、权限审计、数据保留和删除策略，未来可迁移到加密对象存储；不能把文件无意写入公共日志或构建产物。Amazon 真实 Seller OAuth、SP-API 调用留到核心功能完成后由项目 Owner 在本地授权测试。
