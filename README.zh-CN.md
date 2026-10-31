# Merchivra ERP（麦奇维拉）

**面向跨境电商卖家的开源 ERP，第一阶段专注 Amazon Seller。**

[English](README.md) · [MVP 需求](docs/requirements/mvp.md) · [技术架构](docs/architecture/overview.md) · [路线图](docs/roadmap.md) · [参与贡献](CONTRIBUTING.md)

> **当前状态：Pre-alpha / 登录后的核心业务只读工作台 + Mock 演示。** 已有服务端 Session、首个管理员初始化、**本地未连接店铺/站点配置和操作审计**，以及按当前登录租户过滤的 Marketplace、Listing、订单、FBA 只读查询；新租户通常没有真实店铺数据。原来的经营总览与模拟同步仍只使用虚构数据。完整角色权限、真实 Amazon 接入与财务功能尚未完成，禁止生产使用。

## 产品愿景

帮助卖家在自有基础设施统一管理店铺、商品、订单、FBA 库存与利润。坚持私有部署、数据可追溯、模块化设计、开放的平台适配器，以及可重复的自动化测试。

## 技术栈

- 后端：**Java 25 LTS / Spring Boot 4.1.2 / Spring Security 7 / Jackson 3 / Maven**
- 前端：**Node.js 24 LTS / Vue 3.5 / Vite 8.3.5 / TypeScript 6.0.3 / Vitest 5**（npm lockfile 已提交）
- 数据库：PostgreSQL；缓存预留 Redis
- 架构：模块化单体，后续根据业务量拆分 Worker
- 部署：Docker Compose；CI：GitHub Actions（`npm ci` + Maven 快速单测，不运行 E2E）

升级细节与尚未运行的容器验证见 [2026 技术栈升级记录](docs/architecture/stack-upgrade-2026-10-30.md)。

## 快速启动（仅本地模拟演示）

```bash
cp .env.example .env
# 修改 .env 中的 MERCHIVRA_DB_PASSWORD 为强密码
# 再填写 MERCHIVRA_BOOTSTRAP_USERNAME 和 MERCHIVRA_BOOTSTRAP_PASSWORD（自设至少 16 位）
docker compose --env-file .env -f deploy/compose.yaml up --build
```

- 前端：http://localhost:3000
- API：http://localhost:8080/api/v1/ping
- Mock 业务概览：http://localhost:8080/api/v1/demo/overview （需显式启用 Mock；Compose 已在本地开启）
- 健康检查：http://localhost:8080/actuator/health

**已实现首个管理员 Session 登录、最小租户隔离和本地配置审计；[PostgreSQL V1–V4 一次性冒烟](https://github.com/kunlong-luo/merchivra/actions/runs/41059134533) 已通过，但尚未验收整套 Docker Compose、完整角色授权及生产安全。端口仅监听 127.0.0.1，不得直接部署到公网。**

更多命令见 [开发指南](docs/development.md)、[受保护核心只读 API](docs/api/core-read-api.md)、[本地店铺配置](docs/api/local-seller-setup.md)、[Mock 演示](docs/demo.md)、[本地登录指南](docs/security/local-auth.md) 以及 [Agent 策略](Agent.md)。

## 开发路线

1. M0：需求、架构、工程骨架、Docker、CI。
2. M1：账户与 SP-API 授权、凭证安全、Mock 适配器、同步任务机制。
3. M2：Listing、订单与 FBA 库存数据同步。
4. M3：商品历史成本、财务流水、预估利润与对账。
5. M4：权限隔离、安全审计、错误恢复、Beta 试用。

详情见 [MVP 需求文档](docs/requirements/mvp.md) 和 [路线图](docs/roadmap.md)。

## 开源协议

[Apache License 2.0](LICENSE)。欢迎提 Issue 和 PR。请勿提交 Amazon 密钥、客户个人信息或卖家真实报表。

## 纯虚构数据全链路模拟

已新增登录后“核心业务数据”页的手动 Sandbox 回放：为本地未连接的 US/DE 站点模拟分批 Orders、Listing 和 FBA 来源，并在结构化表明确写入 `data_origin=MOCK` 后通过受保护的业务 API 查询。财务始终 `NOT_AUTHORIZED`；这**不是**真实 Amazon 账号授权或 Finances 入账。操作与实际联调脚本参见 [TEST-004](docs/testing/mock-full-chain.md)。

**实际验证：** [Compose 全链路成功运行（2026-10-30）](https://github.com/kunlong-luo/merchivra/actions/runs/41061679196) 已实际构建并运行 Compose 的 Nginx、Spring Boot、PostgreSQL，模拟 US/DE 的订单/Listing/FBA 同步，检查幂等、审计与服务器重启后数据仍在。**但仍不是**真实 Amazon 授权、Finances 对账或生产上线验证。

开发期新增 **V6 模拟订单逐页提交**：可在核心工作台查看已完成页数、手动推进一页、通过 PostgreSQL 持久化检查点与幂等键验证重启后恢复。它不是真实后台同步 Worker、Amazon 分页或已授权来源。详见 [TEST-004](docs/testing/mock-full-chain.md)。

## Amazon 官方 SDK 接入代码（默认关闭）

后端已引入 Amazon **官方 Java SP-API SDK 1.13.0**，包含多个模块的生成 API 类型；新增仅供受授权服务端调用的 LWA/区域 Client、Orders/Listing/FBA/Finances/Reports/Feeds 等读取封装，以及测试中使用的纯虚构 Token 和响应结构。**已有默认关闭的 OAuth/加密 Vault 代码，但尚无真实 Amazon 网络验收、完整角色授权或生产 Worker，默认不开启出站**。模块与差距详见 [INT-004](docs/integrations/sp-api-code-integration.md)。

**Seller OAuth 下一层代码：** 已增加 V7、Amazon 公有应用的两阶段授权回调、一次性 State、租户绑定 AES-256-GCM 凭证 Vault、本地撤销和审计。授权回调后仅 `PENDING_VERIFICATION`，没有接入真实账户或完成生产级核验；默认禁用。见 [SEC-004](docs/security/seller-oauth.md)。

**V8/V9 进一步实现（仍默认关闭）：** V8 增加 Amazon 官方 Sellers v1 Marketplace 参与状态核验与 Worker Vault 24 小时站点证据门禁；V9 增加版本化 AES-256-GCM 密钥环、Owner+CSRF 按店铺原子重加密和审计。**未做真实 Amazon Seller 账号验证、角色证明、KMS Envelope Encryption 或真实订单/库存 Worker。**本地加密密钥轮换不等于 Amazon Refresh Token 续期。见 [SEC-005](docs/security/seller-verification.md) / [SEC-006](docs/security/seller-key-rotation.md)。


## V10 运营自动化工作台

- [竞品能力矩阵与路线图](docs/product/competitor-gap-and-automation.md)：对照 Sellercloud、Linnworks 和 Helium 10 的订单自动化、库存风险、采购与财务模块，标记已完成、待集成和高风险写入能力。
- [OPS-001](docs/operations/ops-automation.md)：提供只读事实上的异常识别、持久待办、认领完成、审计与显式开启的定期巡检；默认无需调用真实 Amazon。建议先在本地模拟导入数据，然后通过“运营自动化”工作台手动执行巡检。
- **没有**实现物流发货、广告自动调价、正式补货单/采购审批、财务对账或正式 Amazon Orders Worker；功能对标不是宣称生产连接已全部完成。
