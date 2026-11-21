# V123 — A3 第一次真 PG18 + Chromium 集中测试发现并修复的回归

2026-11-09 · A3 · [V122 首轮 main 工作流 #42602507335](https://github.com/kunlong-luo/merchivra/actions/runs/42602507335) · 主线 `08dce7a593a047bbbc5acd011bc2355084f5d7f4`

## 初次验收结果（失败也必须留证）

V122 main 的 **A3 集中验证不是全绿**。从真实 GitHub Actions 日志：

| 实际执行阶段 | 结果 | 证据 |
| --- | --- | --- |
| Java25 Gradle + React TS/Vitest/Vite/Next build + Compose 静态 | 通过 | 初次 full milestone 的重型子 job |
| Docker Compose 三服务全链 + V122 **真实独立 Next Node** 容器 | **通过** | 隔离 Spring Boot、PostgreSQL 18 / Next 同源登录、HttpOnly/SameSite Cookie、CSRF、匿名 401、登出、非泄露页面 |
| Chromium 合成 Vite 用户路径 | **失败** | `scripts/smoke_browser_layout.mjs` 的 V72 合成 Mock 仍只支持旧 `/delivery/overview`，但 V119 真 UI 已同时读取 `/delivery/summary` 与 `/delivery/lines/page`；浏览器对 PO-V72-MOCK 等待超时 |
| PostgreSQL18/Flyway + 真 HTTP 长链 | **失败** | `smoke_warehouse_read_pages_v113.py` 的 `/pending-receipts/page?limit=50&status=RECEIVING&search=PO-V113-PEND-` 返回 500；PostgreSQL 日志 `trailing junk after parameter at or near "$2AND"`，源于 `WarehouseInventoryService.pendingReceiptsPage` 的动态状态与 SKU 搜索 SQL 字符串连接漏空格。首次真实跑到这里时 V119、V120 的补充 PG18 HTTP 冒烟已经成功。 |

以上**不能**视为 V122 A3 放行。这个失败证明集中回归的真实价值：普通 Java Mockito/React 单测通过不代表 PostgreSQL SQL 与跨版本浏览器 Mock 均保持一致。

## V123 实际修复

1. `WarehouseInventoryService.java`：确保 `o.status=? ` 后存在明确分隔符，同时在动态 `ORDER BY` 与 `GROUP BY` 文本块前显式拼接空格，避免 `?AND`、`0ORDER` 等错误。只修数据库查询字符串，不改库存计算或租户过滤。
2. `WarehouseInventoryServiceTest.java`：新增原样 `status=RECEIVING` + SKU/采购单搜索的动态 SQL 和绑定参数回归测试，禁止 `=?AND`。
3. `smoke_browser_layout.mjs`：在**同一组合成数据**中让旧 `/delivery/overview`、新 `/delivery/summary`、`/delivery/lines/page` 一致返回全库 1 条/当前页 1 条，继续验证 NO_ASN_RECORDED 不误认为已发货或超时。
4. `milestone-validation.yml`：V123 变更合并至 main 后自动触发另一次**精准新 SHA** 的完整 PG18 / Compose / Chromium 回归，而不是只重试旧 SHA 的失败 job。

## 仍需验证与操作边界

V123 在 PR 上需再次通过所有快速检查；合并后观察新的 **V123 A3 整套 full milestone** 结果。若新全量工作流再失败，只修真实失败项，并在新的最终 main SHA 上再次全测；不能以旧绿灯替代。PG18 的大租户性能/索引与 V121 真实 225+ 库位行动仍需单独审计。

全部测试仅使用隔离一次性 PG18 和合成 Seller/ASN 数据，**不**含实际 Amazon OAuth、SP-API 生产写入、真实供应商或银行数据；Next Node/Compose 测试成功也不代表已完成公网 HTTPS/Ingress、Secrets/Vault、MFA、生产备份与 G1/G2/G3 审批。GitHub Pages 始终仅为公开只读演示。
