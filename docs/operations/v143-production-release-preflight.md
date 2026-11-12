# V143 — 生产部署前安全核查与 G1/G2/G3 证据门禁（不可自动放行）

状态：**仅实现可执行的只读预检。实际生产部署未批准、未尝试。**

技术基线：V142 [PR #102](https://github.com/kunlong-luo/merchivra/pull/102) 于主线提交 `25a1eebb1b21083f6e0b84394bd97f525d731221` 合并；[完整 A3 #43198515512](https://github.com/kunlong-luo/merchivra/actions/runs/43198515512) 7/7 通过。该测试确实验证了隔离 PostgreSQL 18 两容器 WAL 归档/指定时间恢复，以及内部 Alertmanager -> 虚构 webhook 送达，**绝没有**证明目标生产环境已有异地加密备份、外部值班接收方或正式上线权限。

## 已实施：可运行、始终不可自动放行的生产预检

- `scripts/check_production_gate_v143.py` 使用 **Python 标准库**。只从 stdin 读取由 `docker compose ... config --format json` 生成的实际叠加配置（可能含密码；程序不会输出原值），可选读取运维在私有空间保存的 G1–G3 **证据元数据** JSON。固定检查项目以代码标识输出；没有 Docker/网络、数据库调用、修改 GitHub 或启动服务的能力，不能启用 Amazon 或清除证据原件。
- 必须采用 PostgreSQL 18、数据库/Redis/clamd 端口不对外暴露、Spring 和 Next/Vite 回退端口仅绑定 `127.0.0.1`、Next 存在健康检查、Demo 关闭、Session Secure Cookie 开启、所有 Seller/OAuth/远程写和订单同步保持关闭。检查内部 clamd DNS 不得为空或明显为公网示例/回环地址；**不会因 DNS 字符串符合格式就判定真实 clamd 可用**。检查数据库密码已配置且非已知示例/CI 占位符，但不会泄露原密码，也不会声称它由真正的 KMS 注入。
- G1/G2/G3 控制项分别要求来自**目标环境的独立运维声明元数据**（代码为 `operator_attested_target`），包含对应项的 `status=verified`、证据文件 SHA-256 摘要、带时区实测时间和复核者标识。G3 记录不得超过 7 天，G1/G2 不得超过 30 天（只是 **候选新鲜度窗口**，不是经过批准的合规保留期）。G1/G2/G3 的主审标识必须互不相同，单项复核者不能是该组主审。交叉环境、伪造 SHA、未来/过期证明、CI 合成数据证明、未复核或自批准都拒绝。
- **关键防伪边界：这是自声明元数据的结构/时效验证，不是数字签名验证，更不是对证据原件的真实性、证书链、归档存储或人员身份进行独立验证。** 脚本输出字段 `externalApprovalCryptographicallyVerified=false`、`productionApproved=false`、`deploymentPermitted=false`、`automaticReleasePossible=false` **永远不会变成 true**。当资料齐备且预检未发现问题时，只能得到 `AWAITING_OUT_OF_BAND_G1_G2_G3_APPROVAL` 并以退出码 **3** 提示人工线下复核，绝不是“允许部署”。默认/缺项输出 `BLOCKED`、退出码 **2**。结构错误也阻止运行，不输出密钥。

V143 不创建新的 Amazon 凭证、外部账号、Webhook URL、DB 用户或部署基础设施；不能通过硬编码 CI/示例凭据使生产预检通过。

## G1 / G2 / G3 负责人应提供的实际证据

| 门禁 | 需要线下复核的事实 | 必须由谁批准 |
|---|---|---|
| **G1 · 业务** | 目标卖家/站点、FBA/FBM、SKU 规则与首批 P0 范围已确定；运营已检查进销存/采购/FBA 异常闭环；财务独立核准原币/成本/退款/费用口径及历史复算，关闭相应 DEC-01～08 | 产品负责人、运营负责人、财务负责人，各自提交文档、验收记录与真实但脱敏的样本 |
| **G2 · 安全架构** | 实际 HTTPS/TLS、trusted proxy/Session Cookie、MFA/IAM 与租户隔离、专用 KMS/密钥轮换、最小化 DB 权限；私网 clamd 真实 EICAR 签名与故障 fail-closed；Next 登录/CSRF/刷新代理和 Vite 回滚实测 | 安全负责人、技术/架构负责人、基础设施负责人 |
| **G3 · 运行/发布** | PostgreSQL 18 独立加密离站物理备份；实际 WAL archive / PITR 从目标备份到隔离实例，验证数据和恢复时间；经认可的 RPO/RTO 数值；实际磁盘/WAL/备份/归档地告警与接收方值班确认；可执行回滚与发布变更批准 | DBA、值班负责人、发布负责人，业务/安全联合最终复核 |

模板 [`ops/release/v143-production-evidence-template.json`](../../ops/release/v143-production-evidence-template.json) **所有项初始均为 `pending`**。它不能作为生产证明，也不能把包含真实签名或实名人员/密钥的文件提交到 Git、GitHub Issues、公共 CI 或公开演示站。实际审核资料与证据文件在受信安全系统中保存；模板只说明需要的字段。上传的摘要是文件指纹，不是已验证的外部电子签章。

两项现有 GitHub Issues：[#62](https://github.com/kunlong-luo/merchivra/issues/62) 实际标题涉及采购/供应商/仓库分页，[#63](https://github.com/kunlong-luo/merchivra/issues/63) 实际标题涉及 Next.js 正式运行切换。它们保持 OPEN 且是**技术待办**；不得将它们误称为 G1/G2/G3 的正式批准记录。治理文档 [G1 静态审查](../governance/g1-review-2026-10-30.md)、[G2 静态审查](../governance/g2-review-2026-10-30.md)、[未决决策](../governance/decision-register.md)均明确仍待负责人签署。

## 运维在自己的受信环境执行（**不启动生产 Compose**）

1. 先人工核对目标数据库、HTTPS 反向代理、证书、IAM、真实私网 ClamAV、PG18 备份归档与 Alertmanager，以及 Git 仓库的发布候选 SHA。运维从秘密管理器把必要的配置注入 **本机临时环境**；不要使用仓库中的 `.env.example` 当作生产配置，不能把真实 `.env` 放入 Git。
2. 在 **受信运维主机**外部将待审模板复制到私有目录（例如 `/secure-ops/v143-evidence.json`），填写每项真实目标环境证据的哈希、日期、复核人。仍需在线下复核其身份和签名，不接受只填 `verified` 的所谓审计完成状态。
3. 在目录与环境确认正确之后，仅解析配置并运行预检：

```bash
set -o pipefail
# .env 和外部证据文件必须来自运维秘管；只传递给本地私有进程。
# 真正环境里的 SECRET 可能包含在 docker compose config 的 JSON 中：
# 绝不向终端、聊天、日志或 CI 输出中间 JSON。
docker compose --env-file /secure-ops/merchivra.env \
  -f deploy/compose.yaml -f deploy/compose.production.yaml \
  -f deploy/compose.next.yaml --profile optional-cache \
  config --format json |
  python3 scripts/check_production_gate_v143.py --compose-stdin \
    --candidate-sha "$(git rev-parse HEAD)" \
    --evidence-json /secure-ops/v143-evidence.json
```

命令 **只读**；`config` 不会启动容器，脚本不会调用数据库或外部告警。若输入可疑，退出 2；若只是结构上完整，退出 3，需要业务/安全/运维线下批准、真实 Target 验证与单独的变更审批，**不得把 3 当作 CI 部署成功**。任何 release automation 必须独立验证经批准的 G1/G2/G3 签章和组织变更流程，不能将此脚本的任何输出视为令牌或 deploy permission。

## CI 中的可复现负例

普通 PR/Main CI：Python 标准库单测包括 Demo/Cookie 不安全、公开数据库端口、Seller/Orders 开启、空白 clamd、明文示例数据库密码、Next 进程注入 Secret、遗漏 G1/2/3、过期/未来/错误 SHA 的证明、虚构 V142 CI 资料与自签/同一人复核；完整**自填资料即使都为 verified，也不得允许自动发布**。

Compose CI 会解析现有的实际生产覆盖层+Next App Router+可选 Redis，使用 `.env.example` 和没有任何真实外部审批的场景运行 V143 预检，要求 HTTP 之外的静态控制**明确以 2 拒绝**。仅上传**不含 secret、仅有检查代码与状态**的 `v143-release-preflight-blocked` 7 天保留 JSON 作为失败关闭的证据，不能上传合成批准书。

由于 V143 不更改 ERP/Flyway 业务逻辑，继承 V142 真实 PostgreSQL 18 时间点恢复、内网 Alertmanager 送达、V141 磁盘/WAL、V140 原件治理分页、Java 25、Next/React Chromium 与 Compose 全套 A3。新里程碑应在**合并后的同一主线 SHA**验证全部 7 个作业。绿色 A3 只证明代码与**拒绝上线安全门禁**有效；不意味着通过任何业务/生产签署。真实 Amazon Seller/Ads OAuth、外部写入以及原件删除仍保持关闭。
