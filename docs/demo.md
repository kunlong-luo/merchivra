# DEMO-001 · M1 本地 Mock 运营工作台

**状态：Pre-alpha / 已提交基本只读 UI 与 Mock API，但不代表 Amazon SP-API 已接入。** 此演示无真实 Seller 数据、无账号授权和数据库业务事实持久化。

## 本次可运行的业务演示

- 后端 `MarketplaceOverviewGateway` 定义业务读取 Port，`MockMarketplaceOverviewGateway` 使用编造的美区和欧区店铺、Listing、订单、FBA 库存。
- `GET /api/v1/demo/overview` 返回 `mode: MOCK`、明确来源说明、账户独立汇总、原始订单履约/退款/财务状态及库存质量。
- 前端支持**店铺切换**，有经营总览、订单、Listing 和 FBA 库存四个只读视图。
- US/USD 与 EU/EUR **分别聚合，不计算没有汇率口径的跨币种合计**。
- 演示订单可以同时 `SHIPPED + PARTIAL refund + NOT_OBSERVED finance`；FBA 库存 `null` 表示 UNKNOWN，来源明确的 `0` 才表示零。
- 因费用、商品成本及 Finances 尚未落库，**预估订单贡献额显示“不可计算”**，不虚构毛利或实际入账。
- 演示仅使用虚构日期和 ID，不请求 Amazon API，不存取个人数据。

## 运行方法（仅本地）

```bash
cp .env.example .env
# 请把 MERCHIVRA_DB_PASSWORD 改为随机强密码
docker compose -f deploy/compose.yaml up --build
# 浏览器访问 http://localhost:3000
# 概览 API（本机）：http://localhost:8080/api/v1/demo/overview
```

Docker Compose 在 **127.0.0.1** 绑定对外端口，并在后端服务上设置 `MERCHIVRA_DEMO_ENABLED=true`。如独立运行后端，需显式开启：

```bash
MERCHIVRA_DEMO_ENABLED=true DB_PASSWORD=your_local_db_password mvn -f apps/server/pom.xml spring-boot:run
```

默认（不传 `MERCHIVRA_DEMO_ENABLED`）为 **false**，此时 `/api/v1/demo/overview` 不注册，前端会提示 Mock 未开启。该开关不提供安全认证，**只允许开发机或受控本地环境演示**；不要公开暴露 API/Redis/数据库、不要输入真实 Seller 凭证。

## 简化契约示例（展示结构，数字均非真实业务）

```json
{
  "mode": "MOCK",
  "notice": "全部数据为纯虚构演示",
  "fixtureAsOf": "2026-10-28T12:00:00Z",
  "accounts": [{"id":"11111111-1111-4111-8111-111111111111","region":"NA","currency":"USD","connectionStatus":"MOCK_ONLY"}],
  "listings": [],
  "orders": [],
  "inventory": [],
  "summaries": []
}
```

此处 JSON 为**概念节选**，不是本接口所有字段的完整响应；完整 DTO 见 `apps/server/src/main/java/org/merchivra/erp/demo/DemoModels.java` 和 `apps/web/src/lib/demo.ts`。

## 约束和后续

1. 这不是生产订单 API：规划中的 `/api/v1/orders`、`/seller-accounts` 等受保护业务端点**尚未实现**，OpenAPI v0.1 仍为候选协议。
2. Mock Gateway 的 Port 可复用，但真正 Amazon Adapter 必须先完成真实授权、租户 ACL、API 操作级限流/重试与幂等事实存储。
3. 当前演示没有用户登录、数据持久化、同步任务或真实利润；请勿将 demo 数据迁移进生产业务表。
4. CI 仅执行现有 Maven / TypeScript / Vitest / 构建，不做 E2E，参见 [轻量 CI 策略](testing/ci-policy.md)。
5. 关键下一步：搭建带租户范围约束的业务表/Mock 导入和完整授权后，逐步实现可持久化订单/商品/库存链路。

本次涉及 Issue [#5 Mock Provider](https://github.com/kunlong-luo/merchivra/issues/5)、[#7 Listing](https://github.com/kunlong-luo/merchivra/issues/7)、[#8 Orders](https://github.com/kunlong-luo/merchivra/issues/8)、[#9 FBA](https://github.com/kunlong-luo/merchivra/issues/9) 的只读演示阶段，不代表正式实现或验收完毕。
