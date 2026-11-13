# V160 · 运行中镜像与候选提交的只读核对（生产始终 NO-GO）

本阶段给 Merchivra 的**三个自构建镜像**添加 OCI revision/source 标签，并新增受权运维可在自己 Docker 目标上执行的**只读**核查。该工具不安装、启动、停止、拉取、重建或修改任何镜像，也不会读取完整容器详情/环境变量。真实目标未在公共 CI 中检查，不代表生产批准。

## 校验对象和可信边界

- 后端 Spring Boot：`server`，最终镜像 `apps/server/Dockerfile`。
- Next.js 16 App Router：`next-web`，最终镜像 `apps/web/Dockerfile.next`。
- 随时可回退的 Vite/Nginx：`web`，最终镜像 `apps/web/Dockerfile`。

`deploy/compose.yaml` 和 `deploy/compose.next.yaml` 仅将构建参数 `MERCHIVRA_BUILD_REVISION` 传递给三个镜像的最终阶段，最终层加入 `org.opencontainers.image.revision` 和固定 `org.opencontainers.image.source`。本地/CI 默认留空，以免用构造或冒充的 Git SHA 伪造构建来源。**空标签不能通过 V160 目标核查**；只有获授权的发布构建者才能设置经审查的候选 SHA。

V160 在受权运维主机通过固定的 `docker container inspect --format` 与 `docker image inspect --format`，分别读取每个运行中容器的：

1. 实际使用的不可变**本地镜像配置 ID**（`sha256:` + 64 位十六进制），与来自**独立可信发布评审记录**中的预期 ID 作等值比较。不要用刚从同一目标查询到的 ID 再作为预期值，这会形成自证循环。
2. 容器本身的 `com.docker.compose.service` 必须严格对应 `server`、`next-web` 或 `web`，避免误看另一服务。
3. 已匹配的镜像配置的 OCI `org.opencontainers.image.revision` 必须等于受审 Git SHA。
4. 镜像配置的 OCI `org.opencontainers.image.source` 必须严格等于项目 GitHub 仓库地址。

OCI label **不是签名**，Docker 本地 image ID **不是 registry manifest digest**。即使全部匹配，也未证明构建系统未被入侵、基础镜像可信、目标运行端证书轮换、镜像受到组织签名或独立供应链证明，也没有验证 GitHub 受保护分支/审查者身份。真实发布仍须运维使用组织可信的签名、制品登记、权限审计和变更审批系统独立核对。

## 受信构建和人工目标操作顺序

1. 先由组织发布负责人核对待审的不可变 Git main SHA，独立安全人员审核发布构建来源和批准记录。仅在隔离/受控的构建环境中设置 `MERCHIVRA_BUILD_REVISION`。这只会加入元数据标签，不生成签名或许可；禁止直接在未经 G1/G2/G3 批准的生产目标执行 `up` 或切换流量。
2. 将**外部可信制品登记**中同平台/架构的 `server`、`next-web`、`web` 三个预期本地镜像配置 ID，以及受审 Git SHA，在受信运维终端保存为私有环境变量：`REVIEWED_MAIN_SHA`、`EXPECTED_SERVER_IMAGE_ID`、`EXPECTED_NEXT_IMAGE_ID`、`EXPECTED_VITE_IMAGE_ID`。这些值不是秘密，但不得用被检查目标的实时输出代替外部审核。
3. 获得访问目标 Docker context 的授权，核对本机当前 context 指向的唯一目标。目标可为独立受控预生产 Docker；不要在公有 CI 中接生产 Docker socket。
4. 从受信终端运行，仅填入真实服务的 Compose 容器名，不向 GitHub/聊天发送目标环境名称：

   `python3 scripts/check_target_image_ids_v160.py --operator-authorized-target --candidate-sha "$REVIEWED_MAIN_SHA" --container-server "$TARGET_SERVER_CONTAINER" --expected-server-image-id "$EXPECTED_SERVER_IMAGE_ID" --container-next-web "$TARGET_NEXT_CONTAINER" --expected-next-web-image-id "$EXPECTED_NEXT_IMAGE_ID" --container-web "$TARGET_VITE_CONTAINER" --expected-web-image-id "$EXPECTED_VITE_IMAGE_ID"`

5. 将脚本的脱敏固定状态报告交给独立安全/发布人员，并与目标镜像签名/仓库 manifest digest、实际源 commit、CI 原始制品记录和正式更改审批链**在受信系统内**复核。该工具不会私自读取原始容器 JSON、Env、日志、证书、Seller Token 或任何原件；命令错误也不回显 Docker stderr。

返回状态只可能为 `BLOCKED`（退出 **2**）或 `AWAITING_INDEPENDENT_ARTIFACT_AND_G1_G2_G3_REVIEW`（退出 **3**），**绝不 0**，且固定输出 `productionApproved=false`、`deploymentPermitted=false`、`imageSignatureCryptographicallyVerified=false`、`actualRegistryManifestDigestVerified=false`、`operatorExpectedImageIdsIndependentlyVerified=false`。不提供部署开关；不得将非零返回忽略成准入成功。

## 单元与集成验收

`python3 -m unittest discover -s scripts -p 'test_target_image_ids_v160.py' -v`

测试完全使用可注入的 Python 假 Docker 执行器，覆盖三容器匹配、错误/缺失 label、镜像 ID 不匹配、伪造 Compose service、非法 SHA/容器/预期 ID、目标访问被禁或 CI 中运行、秘密 stderr 不回显；并静态校验三份 Dockerfile 和 Compose 参数一致性。**离线测试不会连接任何 Docker socket 或目标**。

这轮改变了最终镜像元数据和 Compose 构建参数，合并后需在**同一精确主线 SHA**运行完整 A3，确保隔离 PG18、Next/Vite 回退、Spring/CSRF、Chromium 与 Compose 路径没有回归。A3 是合成技术验收而非实际发布签署。

## 尚未具备

- [G1/G2/G3 生产阻塞 #104](https://github.com/kunlong-luo/merchivra/issues/104) 仍 Open；需要真实镜像签名/可信制品审查、目标 HTTPS/代理、IAM/MFA/KMS、ClamAV/EICAR，独立加密离站 PostgreSQL 18 PITR/RPO/RTO 和外部 Alertmanager 人工 ACK，以及产品/财务/安全/DBA/发布负责人签署。
- [V159 目标 HTTPS 探针](../architecture/next-target-ingress-v151.md) 的 `operatorClaimedCandidateSha` 是运维声明；V160 的镜像 revision/ID 匹配可以作为另一项人工审查线索，**仍不能单独宣称目标发布版本已通过独立签名验证**。
- [#115](https://github.com/kunlong-luo/merchivra/issues/115) V148 历史间歇 HTTPS 错误根因仍未证实。Amazon Seller/Ads 真实 OAuth、外部读取和写入始终关闭。
