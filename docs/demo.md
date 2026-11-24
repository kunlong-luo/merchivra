# 本地 Mock 演示（仅隔离环境）

**CURRENT：** 使用 [本地开发指南](development.md) 和 [根 README](../README.zh-CN.md) 的 Java 25 / Gradle、PostgreSQL 18、Next.js / pnpm 流程；不再维护一份独立启动命令。仅虚构数据，禁止输入真实账号、令牌、订单或向公网暴露测试服务。

- `MERCHIVRA_DEMO_ENABLED=true` 时启用 Demo；通过 [本地 Session 登录](security/local-auth.md) 后操作。POST 必须提交 CSRF 与所需幂等键。默认关闭。
- 早期 `/api/v1/demo/sync-runs` 仅处理固定虚构租户的快照，失败不覆盖成功快照；V5 `/api/v1/demo/sandbox/{marketplaceId}/replay` 仅用于获准的本地虚构 Marketplace。详见 [Mock 全链路测试](testing/mock-full-chain.md) 与 [核心领域](architecture/core-domain-foundation.md)。
- USD/EUR 独立汇总，未知不置零；模拟结果不代表真实 Seller/Ads、Finances、库存、银行或净利润。
- GitHub Pages 是合成展示，不是正式登录入口。现行验收和限制以 [当前事实](current-status.md) 为准。

**历史证据：** [2026-10-30 V5 Compose 全链路运行](https://github.com/kunlong-luo/merchivra/actions/runs/41061679196) 验证当时的模拟持久化和退出边界。旧 M1/M2/V5 API、fixture 和旧命令完整保留于 [清理前原文](https://github.com/kunlong-luo/merchivra/blob/96e6d97509930c88c85e31f013eb7bd483d90ce9/docs/demo.md)，不再作为现行操作指令。
