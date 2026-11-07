# V98 — GitHub Pages 公开静态 React 展示站

## 安全边界

GitHub Pages 只运行静态资源，**不能**运行 Java 25 / Spring Boot 4 / PostgreSQL 18 / Redis / Session / Amazon OAuth / SP-API。本站所有采购单、FBA 库存、金额、站点、供应商、异常工单等都是源代码中手工编写的**虚构示例**。只支持本地选项卡切换，不能登录或写入实际业务数据，也不会发送任何 /api 请求。

公开构建使用环境变量 VITE_PUBLIC_DEMO=true，Vite 资源根路径为 /merchivra/，只加载 PagesDemo.tsx 与静态样例。普通 Compose/Nginx 构建仍使用 / 根路径、真实 Root/Session 和服务端 /api 路由，不受展示模式影响。公开构建校验 scripts/check_pages_dist.mjs 会拒绝错误路径、真实登录/OAuth 客户端进入公开 bundle。

## 首次启用及发布

管理员必须先访问 https://github.com/kunlong-luo/merchivra/settings/pages，在 **Build and deployment → Source** 选择 **GitHub Actions**。默认工作流令牌不能为未启用 Pages 的仓库修改这一管理员配置。

之后打开 https://github.com/kunlong-luo/merchivra/actions/workflows/github-pages.yml，在 main 分支执行 Run workflow；或在 main 更新前端/部署工作流会自动触发。PR 仅构建校验，不发布公网。

官方 Actions 流程执行锁定依赖 npm ci、VITE_PUBLIC_DEMO=true npm run build、public bundle 断言，然后使用 actions/upload-pages-artifact@v4 与 actions/deploy-pages@v4，无需 PAT、部署密钥或第三方推送库。

只有当部署 Job 实际成功并返回 page_url 时，https://kunlong-luo.github.io/merchivra/ 才能称为已上线。如果出现 Pages not enabled、404 或 Resource not accessible by integration，请先完成上述 GitHub Pages 源配置；**构建成功不代表网站已发布**。

## 本地检查

1. 切换目录 apps/web，运行 npm ci。
2. 设置 VITE_PUBLIC_DEMO=true 并运行 npm run build。
3. 回到仓库根目录运行 node scripts/check_pages_dist.mjs apps/web/dist。
4. 仅从真实 GitHub Actions 的 Deploy job 获取网站部署状态与链接。

真实可操作 ERP 仍需自建 Spring Boot、PostgreSQL 18 与 Nginx（见 README.zh-CN.md）；真实 Amazon 授权与出站写入默认关闭，待核心功能完成后由维护者本地联调。**公开页面绝不作为真实 Seller 或银行核对服务。**
