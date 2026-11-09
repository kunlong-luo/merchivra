# V124 — A3 真实 PG18 + Chromium 里程碑阻塞修复及重跑

Date: 2026-11-09. Scope: synthetic full-suite validation; not a production deployment.

## 实际失败证据

合并至 main 的 V123 `ccb6646a` 执行 [MVP A3 full milestone #42604710661](https://github.com/kunlong-luo/merchivra/actions/runs/42604710661)：
- Java 25 / Gradle full backend、React/Next 全构建以及 Docker Compose 全链任务通过。
- PG18/Flyway/Spring 真实 HTTP 成功启动并通过从 V65 到多个 V90+ 的端到端脚本，但在 `smoke_warehouse_read_pages_v113.py:175` 遇到 `search=PO%25` 响应 400、测试错误期待 200。该接口明确禁止搜索通配符；与本脚本其他非法输入测试一致，修复为预期 400 而非放宽 SQL 校验。对应 [PR #83](https://github.com/kunlong-luo/merchivra/pull/83) 已通过快速 CI、合并为 `5b51d4a7`。
- Chromium V107 合成数据浏览器流程停在“two synthetic local audit movements” 20 秒超时。脚本查找 `aria-label="仓库完整库存流水"`，而 V112 转服务端全页之后组件 `WarehouseLedgerTable` 实际 `ScopedTable` 名为 `aria-label="仓库本地库存流水"`。因此存在选择器与真实表格语义的**一致性错误**，不代表实测库存行缺失。V124 将脚本中 6 处旧名称改为确切的新无障碍标签，继续严格检查两条实际合成库存流水、原始操作 UUID、符号数量和类型过滤，不放宽数量与业务断言。

## 重跑及出货门槛

V124 修改 `.github/workflows/milestone-validation.yml` 的默认里程碑标识为 `V124-A3-warehouse-ledger-and-PG18-release-gate`，在修复 PR 合并进 main 后触发**一次完整**真实 PG18/Flyway/HTTP + Chromium + Next standalone / Compose 检查（只有修改该工作流文件才会自动触发昂贵任务）。全部结果必须以 GitHub 上该合并 commit 的实际 job status 为准，不能用 PR 的快速 CI 成功代替 PG18 验收。

测试使用隔离的 PostgreSQL18 合成数据。Amazon OAuth/SP-API、Ads 与银行生产授权和写入保持关闭。即使 A3 全部通过，也仍需 [Issue #63](https://github.com/kunlong-luo/merchivra/issues/63) 的 Next.js 正式 HTTPS Ingress、Session/CSRF 真实部署及用户本地 Seller 授权/最终人工验收；GitHub Pages 是只读演示。
