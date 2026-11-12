# V146 — 隔离的 HTTPS / Secure Session / Next → Vite → Next 回切验收

**范围：真实运行的本地 Nginx TLS + Next.js 16 Node standalone + Spring Boot 4 + PostgreSQL 18 + Vite/Nginx 回退。不是目标生产 HTTPS、真实组织 CA、切换域名或业务上线。**

基线：V145 main `f05b1ccf0aaa38cf9baa1d8a82db6eab8ed8893c`，[完整 A3 #43222832240](https://github.com/kunlong-luo/merchivra/actions/runs/43222832240) 在第二次（失败 Compose 重试）达到 **7/7 success**。首次 Compose 构建因容器内 Gradle 依赖仓库下载失败而未通过；不能用其初次结果证明稳定性。V145 真 FBA PO/ASN Orval、V144 采购 P0、Session 缓存隔离和 11 个 Next 入口继续保留。

## 实现细节

`deploy/compose.v146-synthetic-https.yaml` 是只供测试的 Compose 覆盖层：仅对原有隔离 Spring 设置 `MERCHIVRA_SESSION_COOKIE_SECURE=true` 且强制 SP-API、OAuth 和远程写入保持 `false`。不能拿它替代 `deploy/compose.production.yaml`、ClamAV、可信代理或组织证书。

`scripts/smoke_https_cutover_v146.sh` 只有在 `GITHUB_ACTIONS=true`、`MERCHIVRA_V146_DISPOSABLE_ONLY=true`、合成固定 bootstrap 用户、关闭真实 Amazon 时才执行。它使用 OpenSSL **临时**生成自签名证书，包含 DNS localhost 与 IP 127.0.0.1 的 SubjectAltName；证书和私钥只写入严格权限的 `/tmp/merchivra-v146-...` 目录，在测试退出后删除。

测试 Nginx 以 `--network host` 运行，使用替换原镜像默认配置的自定义 `nginx.conf`，**仅监听 `127.0.0.1:3443`，没有 80/0.0.0.0 公网监听**，关闭 access log（不记录 OAuth 查询字符串或 Session），TLS 只允许 1.2 和 1.3。Nginx 固定向 `127.0.0.1:3001` (Next) 或 `127.0.0.1:3000` (Vite) 转发，不能传任意外部目标；拒绝伪造 Host 421，并用固定安全值**覆盖**入站 Forwarded、X-Forwarded-Host、X-Forwarded-Proto 和 X-Forwarded-For。入站测试也会提交恶意 Forwarded 头，检查真实 Spring Secure Cookie 未被影响。请注意：这只是本地受控代理拓扑，**不证明目标 ingress 的真实可信 IP 白名单、证书轮换或 HSTS 行为**。

`scripts/smoke_https_cutover_v146.py` 不允许 `https://` 参数指定任意地址，仅连接 `https://127.0.0.1:3443`；客户端使用临时自签证书作为 CA 并启用**真正的 SSL 证书/主机名验证**，不使用 `curl -k` 或 `verify=False`。它依次验证：

1. 通过本地 HTTPS 访问 Next 同源 `/api/v1/ping`、匿名 `/api/v1/auth/me` 401、CSRF 初始化；无 CSRF 的登录失败，带 CSRF 的合成账户登录成功。
2. 真正 Spring `JSESSIONID` Cookie 必须为 `Secure`、`HttpOnly`、`SameSite=Strict`；对所有 11 个 Next App Router 工作台路径直接访问均成功，HTML 不包含 tenant UUID、user UUID、CSRF 或密码。不同浏览器不共享 Session，非法 Host 421，敏感无 CSRF 写入 403，禁用 OAuth callback 401/404。
3. **同一个 HTTPS Origin 和同一个 CookieJar**，Nginx 改从 Next 指向 Vite，使用 Nginx `-t` 和 `-s reload`；无需重新登录即能从真实 Spring `/auth/me` 获取同一用户，验证无 CSRF 写仍 403，匿名浏览器仍 401。
4. 在不修改数据库、用户或业务工作流的前提下，再把入口指回 Next；仍保持同一个 Spring Session，带新 CSRF 退出为 204，之后 Owner 和匿名浏览器均不能访问私有 Session。
5. 仅保存固定的合成结果标识，包含 `externalTlsVerified=false` 与 `productionApproved=false`；不保存证书私钥、JSESSIONID、明文请求、密码、用户 ID、tenant ID 或 CSRF token。

CI 快速检查包括 Bash/Python 语法、纯 Python 合成配置、任意 Host/目标地址拒绝和非 CI 执行拒绝。完整 A3 将在既有真实 Node24+Spring+PG18 Compose HTTP 验收**之后**启动本地 TLS canary 并进行切换，不改变已有业务测试顺序；保留原 Vite/Nginx 回退容器。7 日的 `v146-local-https-cutover-evidence` 只包含非敏感布尔值，绝不上传 TLS 私钥/证书、Session、授权信息。

## 仍未完成的真正生产切换

- **TLS/安全：** 真实用户域名、可信 CA 签发与轮换、反向代理源地址白名单、是否采用 HSTS 的策略、OAuth 回调 URL 日志脱敏/可信 forward 处理、Session Cookie Domain/Path、实际共享身份系统及 MFA/IAM，都要在私有目标环境由安全负责人验证。项目原 Vite/Nginx 内层代理当前可能重写 `X-Forwarded-Proto` 为内部 HTTP `$scheme`；若未来依赖该头进行敏感信任判断，必须专门设计并验证内部信任拓扑。
- **操作：** 在真实受控目标用维护者自有负载均衡器/ingress 实施回滚/滚动升级，而不是简单照搬本地 Nginx `-s reload`。V146 不提供任何公网发布、证书、IAM 配置，也不会自动改变线上流量。
- **数据库/告警：** #104 仍要求真实离站加密 PG18 物理备份 + WAL/PITR、真实值班者 Alertmanager 送达并 ACK、真实 ClamAV 签名测试、G1/G2/G3 全组独立审批；先前合成 V142 PITR 和内网通知不可替代。
- **Amazon：** 卖家 Seller/Ads 生产授权、Token、远程写、银行和财务真实调用必须由维护者在本地最后单独审批并执行；V146 没有启用。

跟踪事项：[Next.js 生产切换 #63](https://github.com/kunlong-luo/merchivra/issues/63) 与 [G1/G2/G3 真实发布 #104](https://github.com/kunlong-luo/merchivra/issues/104) 保持 OPEN。采购分页 P0 #62 已在 V144 按合成 PG18 全库回归完成关闭，但不构成真实生产准入。
