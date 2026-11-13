# V158 · 生产验收证据分工清单（只读、永不自动放行）

**状态：G1/G2/G3 NO-GO。** 本阶段只增加“下一步该由谁提供什么证据”的离线清单，不部署服务、不读取生产凭据、不生成批准证明、不访问 Amazon。

## 证据基线

到 V157，main SHA [5ac412dab7e71a0703c4887614bfba17fbe2eb25](https://github.com/kunlong-luo/merchivra/commit/5ac412dab7e71a0703c4887614bfba17fbe2eb25) 首次通过 [完整 A3 #43422914368](https://github.com/kunlong-luo/merchivra/actions/runs/43422914368) **7/7 SUCCESS**。仅证明隔离 PG18/Flyway、Spring Session/CSRF、Next/Vite Chromium 和临时 CA HTTPS 回切可运行，**不**证明目标生产证书、离站 PITR、真实值班 ACK 或人工签署。

唯一生产准入追踪是 [#104](https://github.com/kunlong-luo/merchivra/issues/104)；Next 技术 Issue [#63](https://github.com/kunlong-luo/merchivra/issues/63) 已由维护者关闭，**该状态不代表生产部署批准**。历史 HTTPS 间歇故障 [#115](https://github.com/kunlong-luo/merchivra/issues/115) 仍 Open；V157 的安全加固不能倒推出 V148 历史失败根因。

## 新增只读工具及信任边界

- **输入：** 现有 [V143 生产预检](v143-production-release-preflight.md) 已输出的**脱敏检查结果 JSON**。不能把原始 Compose、真实 `.env`、数据库备份、证书私钥、电子签章原件或证据正文喂给本工具。
- **程序：** `scripts/production_evidence_worklist_v158.py`，提供 `--format markdown` 和 `--format json`；只在运维有权限的受信主机执行。
- **输出：** 只含静态允许的检查代码、责任角色、证据类型、元数据状态和格式校验过的候选 SHA。不输出运维身份、密码、Cookie、密钥、私有证据、输入中随意添加的字段或异常原文。未知、矛盾、伪造或过长输入固定拒绝。
- **不可自动放行：** 即使元数据都显示 verified，工具仍然固定 `productionApproved=false`、`deploymentPermitted=false`，**总是退出码 2**。`STRUCTURAL_PASS_NOT_TARGET_PROOF` 只代表本地静态字段满足要求，不能当作外部目标真实性证明。

工具复用 V143 的 `CONTROL_GROUPS` 和 `safety_checks()`，不会重新解析原始密钥或生成授权。单测对齐 `ops/release/v143-production-evidence-template.json`；初始每项继续 `pending`。

## 在真实目标的受信运维主机上执行

1. 核对 main 候选 SHA、镜像构建指纹和目标环境。仅使用已经审批的秘密管理器注入真实配置，不要把凭据发往聊天、公开 CI、Git 或 Pages；`.env.example` 不是生产配置。
2. 各责任人收集来自真实目标的证据原件。原件、签名和身份只存放受信的私有审核系统，使用 [V143 模板](../../ops/release/v143-production-evidence-template.json) 的 `pending` 元数据结构记录处理进度。CI 日志、合成 CA 和内部 Webhook 不能冒充真实目标证据。
3. 按 [V143 操作手册](v143-production-release-preflight.md) 的目标 Compose 叠加顺序运行只读预检。**只将 V143 脱敏 stdout 存入受信的私有文件**（如 `/secure-ops/v143-redacted-result.json`），原始 `docker compose config` 只通过本机管道传递，绝不保存在公共日志。
4. 在同一主机将上一步的 **V143 脱敏 JSON** 输入：

   `python3 scripts/production_evidence_worklist_v158.py --format markdown < /secure-ops/v143-redacted-result.json > /secure-ops/v158-owner-worklist.md`

   工具总是退出 **2 (NO-GO)**，同时在输入合法时写出工作清单；不要用 `|| true` 把它变成“部署通过”。
5. 产品/运营/财务、安全/架构、DBA/值班/发布各自独立复核原件和目标身份，在组织正式审批系统签署。仅在 [#104](https://github.com/kunlong-luo/merchivra/issues/104) 中登记**不含秘密**的证据引用和正式状态，不能用本工具代签、代审或声称已批准。
6. Seller/Ads 真实授权仍单独进行；是否开启本地只读联调也需要维护者另行批准，远程写入继续关闭。

## 目标证据责任矩阵

| 门禁 | 真实环境最少需要的证明 | 责任分工 |
| --- | --- | --- |
| G1 产品/运营/财务 | 首站、账号和 DEC 政策；脱敏真实采购、订单、库存/FBA、异常工作流；原币、成本生效日期、退款、费用、结算复算 | 产品、运营、财务分别签署 |
| G2 安全/架构 | 实际证书链和反向代理、Session/CSRF/IAM/MFA、KMS/凭据轮换、租户隔离、真实私网 ClamAV/EICAR、Next/Vite 回切 | 安全、架构、基础设施独立签署 |
| G3 DBA/运维/发布 | 离站加密 PG18 物理备份、WAL/多时间点 PITR、实测 RPO/RTO；真实外部告警投递、重试、故障转移和值班人 ACK；回滚/发布变更 | DBA、值班、发布负责人独立签署 |

未准备的任何一项按缺项处理，绝不能把自填摘要、模拟签名或 GitHub Actions 运行链接当作目标验收。

## 轻量回归

`python3 -m unittest discover -s scripts -p 'test_production_evidence_worklist_v158.py' -v`

纯 Python 标准库/固定合成输入，无数据库、Docker、外部 API、凭据或签名调用。PR/main 快速 CI 足以覆盖此报告辅助功能；避免仅为文档清单反复触发昂贵的完整 7 作业 A3。后续涉及服务部署、认证、DB、资金或真实发布流程时仍须按风险执行专项验证。

**退出码 2 永远是 NO-GO，不构成任何形式的上线授权。**
