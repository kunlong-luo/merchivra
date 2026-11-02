# V84 · Owner 站点级细分权限与真实批次—单行审计关联

V84 继续使用 V16 的已注册租户 OWNER / OPERATOR_READ 角色体系：**只有有效租户 Owner 可进入异常工作台**，本轮不新增任意新角色或扩张只读员工的写权限。此前 V77–V83 所有已授权 Owner 若未配置单独限制，继续维持完整既有能力；收紧仅作用于当前租户+Marketplace 的本地 FBA/采购人工异常处理，绝不影响订单、财务、仓储等其他服务的权限。

## Owner 六种站点级权限

| 授权位 | 本模块允许操作 |
| --- | --- |
| VIEW | 本地 FBA 采购证据、处理单、V80/V81 看板、批次和单行审计 |
| ASSIGN | 指派负责人、调整本地处理期限、批量分派 |
| REVIEW | ACKNOWLEDGE、REOPEN、批量确认 |
| RESOLVE | 单行 RESOLVE、V83 预检、V82 人工确认后批量完成 |
| CONFIGURE | V77 处理期限提醒配置、V82 优先级策略的 CAS 修改 |
| MANAGE | 查看和编辑其他活跃 Owner 的站点级授权、查看授权审计 |

新的 `fba_exception_owner_permission` 以 `(tenant_id,seller_marketplace_id,owner_user_id)` 唯一记录权限布尔位、乐观版本和修改人；`fba_exception_owner_permission_audit` 保存旧/新授权编码、版本、操作者、理由、时间。通过 Marketplace 行锁与版本号防并发覆盖；修改人**不得更改自己的授予状态**，并在共享锁内重新校验 MANAGE 防止并发相互撤权。MANAGE 必须同时持有 VIEW 和 CONFIGURE。只能修改当前租户有效 Owner，不能以此给 OPERATOR_READ 非 Owner 提权。

接口：

- `GET /api/v1/fba/marketplaces/{marketplaceId}/purchase-reconciliation/permissions/me`：当前 Owner 有效授权，无配置则沿用默认全权限。
- `GET .../permissions/owners`：仅 MANAGE，可编辑的有效 Owner 清单（最多 100）。
- `POST .../permissions/owners/{ownerId}`：MANAGE + CSRF + expectedVersion + 10–300 字理由，返回新配置；非法或自身编辑 400/403，版本变化 409。
- `GET .../permissions/audit`：仅 MANAGE，最近 100 条权限变更审计。

旧工作台所有动作在控制器入口按对应权限重新校验；V80/V81 的异常数据和 V82 规则配置也分别按 VIEW/CONFIGURE 校验。**Owner 身份仍由服务器 Session/数据库验证，前端展示或禁用按钮只是辅助。** 如果未来授权给非 Owner，需另一个明确的角色建模、受控邀请和完整安全评审；此版故意不扩大角色范围。

## 精确批次关联：无猜测历史

Flyway `V78__fba_exception_owner_permissions_batch_audit.sql` 为旧 `fba_purchase_exception_audit` 新增可空的 `batch_id`，外键 `(tenant_id,batch_id)` 指向 V77/V79/V82 原 `fba_purchase_exception_batch`，含对应查询索引。对**本阶段之后**的 ACKNOWLEDGE / ASSIGN / RESOLVE 批量调用，在原子事务里先建立批次主键，调用原 V77 人工状态机逐行写入审计时带上精确 batchId；任意一行失败，批次与全部写入、审计一起回滚。幂等重放依然只返回既存结果，不重复审计。

旧 V77–V83 批次的批次 ID 与逐行审计之前没有可验证关系，原来的审计 `batch_id=NULL`，**不能用日期、操作人或请求注释虚构映射**。

新增 `GET .../workbench/batches/{requestKey}`：在 Session Owner + VIEW、租户+Marketplace 约束内读取原始批次、`linkedCount`、`lineageComplete` 和通过审计外键精确获取的行 ID/货件/SKU/动作/操作人/新状态/动作时间。旧批次 `lineageComplete=false`，仅回退为 V77 原 `cases/{lineId}/history` 的单行事实。不存在/越界批次返回 404。仍保留 V83 最近 50 次批次摘要接口。

## 安全与验收

无 Amazon OAuth/SP-API 出站、无发货、采购、ASN、财务、库存、通知写入，也不自动赋予任何员工权限。SQL 使用 PostgreSQL 18、Flyway V78；后端 Java 25/Spring Boot 4.1.2/Gradle；前端 React 19 + TypeScript 6 + TanStack Query + Zod + Vitest；未引入新的权限引擎或审计组件。

在 V76–V83 虚构演示数据之后，`scripts/smoke_fba_owner_audit_v84.py` 使用真实第二 Owner Session 验证默认权限、站点隔离、禁止自行授权、CAS 冲突和无效 MANAGE、VIEW/REVIEW 的最小权限、未经授权的分派/配置/完成 403、新的只允许 REVIEW 的批量 ACKNOWLEDGE、精确行审计外键、幂等重试、旧批次不虚构关联及库存零变化。真实结果以 4 项 CI 完成状态为准。
