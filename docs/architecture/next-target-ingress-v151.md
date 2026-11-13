# V151 — Next.js 目标域名 HTTPS / Ingress 只读核查工具（人工运行）

> **状态：已实现核查工具和离线测试，不代表真实目标环境已存在、已测试或已经批准上线。** 跟踪 [Next 正式切流 #63](https://github.com/kunlong-luo/merchivra/issues/63)、[生产 G1/G2/G3 #104](https://github.com/kunlong-luo/merchivra/issues/104)。本工具无法替代 V143 G1/G2/G3 线下审批，不能独立签发放行令。

## 为什么新增

V146–V150 的真实 HTTP、PG18、Next Node、Vite 回滚及本地 CA-verified HTTPS 都在**一次性 CI loopback** 完成，证书是短期本地合成证书。它不能证明运维真实域名、外部证书链、实际 ingress 对错误 Host 的处理、公开访问下的 Cookie 属性。生产目标不提供给 GitHub CI，也不会由本工具从配置自行发现。

因此增加 [`scripts/check_next_target_ingress_v151.py`](../../scripts/check_next_target_ingress_v151.py)：仅在具备运维权限的工作站**显式确认授权**后，对运维自己选定的 HTTPS 域名做固定的匿名 GET 检查。

| 检查 | 要求 | 不能据此声称 |
| --- | --- | --- |
| HTTPS 证书、域名 | Python 标准库 TLS 完整证书链及主机名验证，最低 TLS 1.2；可选私有受信 CA 文件 | 证书轮换、HSTS 和目标基础设施安全性已批准 |
| Next/Spring 同源 | `GET /api/v1/ping` 返回 200 | Next 是唯一上线 runtime、负载均衡已正确回源 |
| 匿名隔离 | `GET /api/v1/auth/me` 必须返回 401 | MFA/IAM 已完成，其他权限都正确 |
| CSRF 与 Session | `GET /api/v1/auth/csrf` 为 200 且含有效 token；`JSESSIONID` 必须有 `Secure; HttpOnly; SameSite=Strict; Path=/` | 已验证带权限用户、所有敏感写入和实际 Session 失效 |
| 异常 Host | 对同一 TLS 目标以固定 `Host: untrusted.invalid` 请求 `/api/v1/ping`，须返回 400/403/404/421 | 已审查全部 Forwarded 信任链、WAF、日志脱敏 |

严格限制：仅 `https://`、有合法 DNS 名的目标、443 端口、不可包含用户信息、路径、查询串或片段；拒绝 localhost/原始 IP/示例或保留测试域名。固定只用匿名 GET（不会发 Cookie、Authorization、客户端凭据、Seller/Ads Token）；不跟随跳转，任何 302/登录跳转都会阻止核查。CSRF GET **可能在目标 Spring Session store 创建一个匿名 Session**，但不会进行业务写入；运维应在获授权的私有受控目标执行。

## 仅由获授权运维在真实目标执行

在自己的安全运维主机上设置实际域名，例如在受保护 shell 环境中设置 `TARGET_ERP_HOST`；**无需把真实域名、证书或凭据发送到 GitHub、聊天或公共 CI**：

```bash
# 仅对本组织明确授权的受控目标执行：
python3 scripts/check_next_target_ingress_v151.py \
  --operator-authorized-target \
  --origin "https://${TARGET_ERP_HOST}"

# 若真实目标使用组织内部 CA，另行指定仅含受信 CA 证书的私有 PEM：
python3 scripts/check_next_target_ingress_v151.py \
  --operator-authorized-target \
  --origin "https://${TARGET_ERP_HOST}" \
  --ca-file /secure-ops/org-root-ca.pem
```

- **退出 2：BLOCKED。** 安全断言或安全前提未满足；只打印固定检查代码和布尔值，绝不打印目标、Cookie、CSRF、证书、响应正文或 Python 异常详情。
- **退出 3：AWAITING_INDEPENDENT_HUMAN_G2_G3_REVIEW。** 只说明这些匿名 HTTPS 观察项满足要求，仍然**不是**发版成功/可以切流。输出固定 `productionApproved=false`、`deploymentPermitted=false`、`g1g2g3Signed=false`，不得被 CI/部署自动化解释为授权。
- `GITHUB_ACTIONS=true` 时工具强制拒绝，即使提供确认开关也不得向目标发送请求；CI 只运行不访问网络的 `scripts/test_next_target_ingress_v151.py` 单测。

## 依然需要独立完成

人工记录真实发布候选镜像摘要和 SHA、同源 Next→Vite→Next 带登录 Session 回滚和浏览器截图、目标域名的证书更新测试、可信代理/WAF/Forwarded 策略、IAM/MFA 和审批；以及 [#104](https://github.com/kunlong-luo/merchivra/issues/104) 的目标环境真实 PG18 加密离站物理备份/WAL/PITR、真实 Alertmanager 外部 ACK、私网 ClamAV/EICAR、G1/G2/G3 独立签署。真实 Seller/Ads 用户本地 OAuth 与只读 API 联调仍需单独批准，远程写权限保持关闭。

本工具的成功只能作为目标 HTTPS **匿名入口的一个小范围现场观察证据**，不代表已验证可登录生产环境。不要上传报告里可能关联内部基础设施的旁证到公共仓库；证据原件存放在维护者授权的私有系统。
