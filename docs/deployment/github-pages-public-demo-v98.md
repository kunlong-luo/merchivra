# GitHub Pages · 公开虚构静态展示（V191 当前构建规范）

> **2026-11-21 当前状态**：公开页面由 Vite 的 `VITE_PUBLIC_DEMO=true` 模式单独编译。真实自托管 ERP 的默认 `web` 已是 Next.js 16 Node；**`pnpm build` 是 Next 构建，不能用来发布 GitHub Pages**。本页与 [当前事实基线](../current-status.md) 一致，生产 #104 NO-GO，Seller/Ads 真实授权继续关闭。

## 和真正 ERP 的隔离

GitHub Pages 只有虚构的商品、采购单、库存、金额及异常卡片，不提供真实登录、数据库、Spring Session/CSRF、Amazon Seller/Ads OAuth、银行对账或业务写入，不会访问 `/api`。合成展示单独使用 `apps/web/src/PagesDemo.tsx`，资源基路径 `/merchivra/`。默认 Next ERP 使用 `app/` 的真实会话保护业务模块、同源 `/api` Spring 代理；这两条链路不可混淆。

## 真实发布流程

GitHub Pages 由 [`.github/workflows/github-pages.yml`](../../.github/workflows/github-pages.yml) 在相应 `main` 前端变更或人工运行时构建，使用 Node 24 + pnpm 12.20.0 冻结安装、`pnpm build:pages`、`scripts/check_pages_dist.mjs` 静态安全检查，再上传/部署 Pages artifact。它不会启动 Next Node、Spring 或 PostgreSQL。

如需首次启用 Pages，管理员在仓库 **Settings → Pages → Build and deployment → Source** 选择 **GitHub Actions**，再检查 [Pages workflow](https://github.com/kunlong-luo/merchivra/actions/workflows/github-pages.yml)。以**实际 Deploy job 的状态及 page_url**为准；构建绿灯不能保证仓库 Pages 管理员配置已启用。

## 本地离线构建检查

```bash
cd apps/web
corepack enable
pnpm install --frozen-lockfile
pnpm build:pages
cd ../..
node scripts/check_pages_dist.mjs apps/web/dist
```

此检查明确拒绝未经隔离的登录/授权请求、错用 `/merchivra/` 资源路径或缺少虚构数据警告。公开地址为 [Merchivra 合成展示](https://kunlong-luo.github.io/merchivra/)，不是可登录的生产 ERP。参阅 [本地真正的 Next ERP 部署指南](local-spark-db.md)。
