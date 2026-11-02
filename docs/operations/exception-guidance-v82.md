# V82 · 可配置处理优先级、理由模板和人工确认的批量完成

**本地业务建议与人工处置。** V82 建立在 V77–V81 已有的 FBA/采购证据、人工工作台和运营建议之上；不得自动写入 Amazon SP-API、FBA 库存、采购、ASN、财务数据或发送对外通知。

## 1. 站点级优先级配置

Flyway `V77__fba_exception_priority_guided_bulk.sql` 创建每个租户/Marketplace/证据原因唯一的 `fba_exception_priority_setting`，并通过 `fba_exception_priority_audit` 保留每次变更：旧值、新值、版本、操作者、理由和时间。

- `GET /api/v1/exception-insights/marketplaces/{marketplaceId}/priority-rules` 返回固定 9 种证据原因的有效配置、最低允许优先级和版本，未配置默认为 V80 固定优先级版本 0。
- `POST .../priority-rules/{reasonCode}` 接受 `priority`、`expectedVersion` 和长度 10–300 的理由。单事务 Marketplace 行锁 + 版本检查，冲突 409，非法规则或降级 400；新版本与审计同事务持久化。
- `GET .../priority-rules/audit` 返回最近 100 条已登记变更，按时间倒序。
- V80 建议读取配置后只对 **建议展示顺序** 进行提高等级（P0 优先于 P1、P2、P3），不修改 V78 Ops 规则的采集策略，也不自动变更原始处理单与人工期限。配置变更会按租户/站点作用于下次刷新。

保护下限：来源指纹变化、超期 **P0**；身份独立审核、入仓差异独立复核、临期和 HIGH 证据 **P1**；未分派和 WARNING **P2**；常规跟进 **P3**。在 UI 只能保持或提高等级，后端再次独立执行校验，禁止绕过 UI 把高风险原因降级。

## 2. 可复用的人工说明模板

React 工作台新增 5 种本地处理说明草稿：证据复核、负责人分派、确认待补证据、本地完成、期限调整。它们仅将文字填入可编辑的操作理由框；操作者必须结合当前处理单补充实际证据，填写理由后再人工提交。**使用模板不意味着自动确认了仓储/供应商事实**。本轮暂不提供可以伪造审核结果的自动填充，也不引入另一个模板数据库或富文本编辑器。

## 3. 显式人工确认的原子批量完成

复用原有 `fba_purchase_exception_batch` 幂等请求账本、V77 `apply(...RESOLVE...)` 独立复核状态机及 `fba_purchase_exception_audit`，通过迁移扩展账本允许 `RESOLVE`，原有 ACKNOWLEDGE/ASSIGN 字段与服务保持兼容。

`POST /api/v1/fba/marketplaces/{marketplaceId}/purchase-reconciliation/workbench/bulk-resolve` 请求：
```json
{
  "requestKey": "uuid",
  "confirmed": true,
  "lines": [{"lineId":"uuid","expectedVersion":1,
             "expectedFingerprint":"64-character SHA-256 lowercase hex"}],
  "note": "10–300 字人工核查完成依据"
}
```
- 必须由当前 Owner Session 加 CSRF 提交，`confirmed=true`；1–50 条唯一来源行、有效来源 SHA256 指纹和非负并发版本，提交前需人工勾选确认。
- 所有行使用 Marketplace 锁和确定顺序逐行加锁，要求 **ACKNOWLEDGED + 有负责人 + 来源未变化**，并再次检查 V65 入仓差异的独立复核、V75 身份来源独立确认等硬性阻断条件。
- 任意一行过期/无权限/重复/未达安全门槛则整批回滚，包括审计和批次账本；相同 `requestKey` + 完全相同内容安全重放返回 `resolvedCount`。不同内容或跨 ASSIGN/ACKNOWLEDGE 动作复用请求键拒绝 409。
- RESOLVE 仅是本地处理完结，不代表 Amazon 已核验入仓、平台库存可售或采购收货，也不替代 V78 Ops DONE 的独立操作。

## 4. 技术复用与验收

仍为 Java 25、Spring Boot 4.1.2、Gradle Wrapper、PostgreSQL 18 + Flyway、React 19 + TypeScript 6、TanStack Query、Zod 4、Vitest。模板只用静态类型化的 React 数据，优先级直接复用现有 V80 计算而不引入新的引擎、规则 DSL 或第三方任务服务。

`scripts/smoke_exception_workflow_v82.py` 只能用于 GitHub CI 中完全隔离、可抛弃的模拟数据库，在 V76–V81 证据后验证两个规则版本、审计、降级禁止、401/404 站点隔离、人工勾选强制、混合行回滚、确认成功与幂等重试及库存零变化。还添加 Java 风险下限单测与 React API/CSRF/模板单测。实际通过范围以 CI 结果为准。
