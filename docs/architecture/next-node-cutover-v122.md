# V122 — Next.js 16 Node 生产候选、保留 Vite 回退与 A3 主线验收

2026-11-09 · A2/A3 · [Issue #63](https://github.com/kunlong-luo/merchivra/issues/63) · [Issue #62](https://github.com/kunlong-luo/merchivra/issues/62)

> 这是**可单独启动的 Node App Router 正式运行候选**，**不是生产上线通知**。PostgreSQL18+Spring+Session/CSRF 在 GitHub Actions 的一次性环境中验收，维护者须自行批准 HTTPS、秘钥、业务准入和 Amazon 本地 Seller 授权；不能把 GitHub Pages（仅合成只读演示）当作 ERP。

## 结构与原因

历史 `apps/web/Dockerfile` 是 Vite 构建 + Nginx 静态资源。它不会运行 Next.js 16.4.5 App Router、服务端 rewrite 或真实 Node 运行时，因此不能作为 Next 上线证据。

V122 新增：

- `apps/web/Dockerfile.next`：Node 24 锁定安装、`npm run build:next`，使用 `.next/standalone` + `.next/static` 形成可执行 Node 镜像，**非 root nextjs 用户**、端口 3000。以 `/workspace/apps/web` 构建保证 `next.config.ts` 的 `outputFileTracingRoot: ../..` 与 Docker 复制路径一致。不会把本地 `.env`、密钥、`node_modules` 送进构建上下文。
- `deploy/compose.next.yaml`：独立 `next-web` 服务，默认仅绑定宿主机 **127.0.0.1:3001**，与原 `web` Nginx/Vite 并存。构建时使用 Docker 私有服务 DNS `http://server:8080` 编译 `/api/:path*` 同源转发，后端 Session 与 CSRF 权威检查仍在 Spring。**build arg 不是公开浏览器可访问的 AWS/数据库地址，更不能是秘密。Next rewrites 配置变更需要重新 build 镜像。**
- `scripts/check_next_runtime_v122.py`：普通 CI 检查镜像非 root、独立 Next 运行入口、生产 Compose 真实有效配置、只绑定 loopback、PG18、后端 Seller/演示/写入关闭、Next 无数据库和 Seller 密钥、旧 Nginx 可回退。
- `.github/workflows/mock-full-chain.yml`：仅完整 A3 里程碑，启动**真实 Docker standalone Next Node**（非 `next start` 开发迁移候选）连接同一一次性 PG18/Spring。复用 `smoke_next_session_csrf_v101.py` 全链验证匿名请求 401、禁止无 CSRF 登录、身份 Cookies 的 HttpOnly/SameSite、登录、403 写拒绝、合法 CSRF 登出、另一个浏览器无会话、Next HTML 不包含 userId/tenantId/密码/CSRF Token、后端 OAuth 保持禁用。已有 Vite/Next Chromium 和 PG18/Flyway/备份恢复继续运行；本轮在主分支触发 V122 整套集中验证。

## 本地一次性候选验证（不触发实际生产、无 Amazon 凭据）

使用项目现有 `deploy/.env` 方式，在仓库根目录自备本地 **合成/隔离** 的 `.env`，保持所有 SP-API/OAuth/远程写入开关 false：

```sh
docker compose --env-file .env -f deploy/compose.yaml \
  -f deploy/compose.next.yaml up -d --build postgres server next-web

curl -fsS http://127.0.0.1:3001/api/v1/ping
curl -fsS http://127.0.0.1:3001/orders >/dev/null
# 正式员工授权、真实操作应仅在维护者私有测试环境执行。
```

这样 Next 在 127.0.0.1:3001，旧 Vite 仍可按现有 `docker compose ... up -d web` 在 127.0.0.1:3000 启动。**默认本地 HTTP 环境不能使用 `compose.production.yaml` 的 Secure Cookie 并宣称登录可用**；生产必须由审过的 HTTPS 终止代理承载，正确转发可信的 `Host/X-Forwarded-Proto`，防止代理伪造及 Cookie 泄漏。

生产候选必须先在维护者私有网络正确配置 TLS/域名/外部 secrets，再按照以下原则由代理切流：入口代理由 `127.0.0.1:3000` (Vite) 指向 `127.0.0.1:3001` (Next)，保持独立 Spring / PG18，不修改数据库。若异常，**只回切代理到原 Vite**，不要倒回 Flyway 或覆盖 PG18 volume。关闭 Node 测试容器使用：

```sh
docker compose --env-file .env -f deploy/compose.yaml \
  -f deploy/compose.next.yaml stop next-web
```

**尚未配置的上线要件**：实际公网 TLS/Ingress、可信代理来源、外部 Secrets/Vault、MFA/IAM、备份/PITR 演练、应用限流/资源配额/日志审计、跨租户 225+ 用户工况、完整授权撤销/真实 Seller/Ads 数据一致性与 G1/G2/G3 批准。预览站不携带真实 Seller token，不可被当作正式业务入口。

## 验收追踪与客观放行门槛

快速 CI 只验证镜像部署配置、React/Next TS 与现有单测。A3 里程碑运行 `.github/workflows/milestone-validation.yml`，自动调用 **完整 CI PG18 / Flyway / Next 生产构建 / Java / React**、**Vite+Next Chromium**、**Compose 三服务链及新 Node 独立容器真实 Spring Session/CSRF**。V122 合并后因该 workflow 本身发生变化会自动针对**V122 main 精确 SHA**触发一次，工作流 URL 和成功/失败必须真实记录；不能沿用 V108 或 V121 快速 CI 假称 A3 放行。

与主线已有 V119/V120 的 PG18 冒烟互补，V121 225 条与 V48 原批次等高数据量测试的真实 SQL/索引与 P0 仍需验证；全绿也只代表**合成一次性环境**，不代表已走完真实 Amazon 生产授权或生产安全审批。

## V128 容器就绪探针（无需生产密钥）

`next-web` 的 Docker Compose healthcheck 现在使用 Node 24 内置 `fetch`，从**容器内部**访问 `http://127.0.0.1:3000/api/v1/ping`：该请求必须经 Next 同源转发实际到达 Spring，且 HTTP 状态严格为 200；单次超时 3 秒，Compose 每 15 秒重试、启动缓冲 60 秒。不安装 curl、不访问 Amazon、不注入数据库或 OAuth 凭证。探针只观察**运行时就绪**，不证明外部 TLS/证书/IAM 或真实 Seller 授权安全。

在一次性合成环境运行 `docker compose --env-file .env -f deploy/compose.yaml -f deploy/compose.next.yaml ps` 查看 `next-web` 是否 `healthy`；完整 A3 Compose 脚本会在现有 HTTP/Session/CSRF 检查以外核实这个状态。若 Spring 中断，Next 服务可能仍在监听端口，但应被标记为 `unhealthy`，发布代理/部署平台不得只依赖 TCP 存活作流量切换判断。是否将此健康探针绑定到目标 ingress 的流量摘除机制，必须在真实部署环境另行验证。

**V128 提交只运行快速 PR CI；如最终生产候选 SHA 因合并而变化，必须针对该新 SHA 重新执行完整 A3 里程碑，不能挪用旧 V126 结果冒充对新镜像的完整验证。**
