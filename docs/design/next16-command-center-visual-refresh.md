# Merchivra · 2026 Next.js 16 经营指挥台视觉升级

阶段：**已合并、精确 main SHA 合成技术 A3 首次 7/7 通过；不等于生产 GO 或全页面人工像素审查**。
目标：在现有 A+B 信息架构基础上提升经营总览、利润中心、导航、通用页面与公开演示的设计质量。直接实现于真实 React 19/Next.js 16，而不是新建虚构原型。

## 实际交付与验收（2026-11-17）

[PR #168](https://github.com/kunlong-luo/merchivra/pull/168) 已合并，业务 SHA `7c01735887b511baa7adfb5edcf8b0882569a116`；[PR CI #44090737007](https://github.com/kunlong-luo/merchivra/actions/runs/44090737007) 首次 SUCCESS（Next16/Vite TS、Java25、Compose，以及 PR 专属 Vite+Next Chromium 中文 360px/桌面截图和真实来源表格检查，PG18 在快速 PR 中按策略跳过）；[精确业务 SHA A3 #44091196466](https://github.com/kunlong-luo/merchivra/actions/runs/44091196466) **run_attempt=1，7 个核心任务全 SUCCESS**（PG18/Flyway、Java25、Next16/Vite、Chromium、Compose HTTP；PR 专属额外浏览器任务在主线 SKIPPED）。[GitHub Pages 展示构建 #44091196262](https://github.com/kunlong-luo/merchivra/actions/runs/44091196262) SUCCESS。

已人工打开合成 Chrome 产物中的 **360px 财务首屏、360px Base UI 抽屉及 1280px 核心业务页面**，确认这些抽样截图中的正文、导航与主要表格层级。**经营总览真实财务 Hero、全部次级财务表单和所有屏幕尺寸尚未逐帧人工视觉验收**；用户看到的真实金额仍由数据库和原 V64 计算结果决定。本次只包含前端设计，不新增任何真实 WMS 身份、库存或 COGS 核销能力。

## 视觉语言与组件策略

- **专业经营指挥台**：深海军蓝侧栏与清晰的分组导航，米灰/瓷白画布，深色标题和蓝绿强调，降低大面积高饱和蓝色带来的视觉负担。
- **层级**：顶栏 > 清晰页标题/来源范围标签 > 当前站点真实 KPI > 重点利润区 > 来源对账表格 > 详细操作表单。首屏不再被长篇来源说明和独立表格占满。
- **利润中心主视觉**：仅显示 V64 已冻结且 SUCCEEDED、有原币贡献数值的报告；没有可信来源时始终显示“贡献金额暂不可计算”。右侧是候选订单/可纳入订单/被阻断订单的实际计数，不使用虚构增长曲线、预测利润或跨币种金额。
- **财务中心**：原币、来源分类分组和“最终可信净利润尚未认证”固定提示，禁止金额来源不明时显示已验证净利润。
- **原生组件复用**：保留现有 Base UI Dialog/Select、TanStack Query/Table、Lucide、原生 HTML 表格/表单；没有增加新的 UI 框架、图表依赖或外部字体/图片请求。
- **Next16 优先**：新增 shared `apps/web/src/visual-refresh.css`，从 `apps/web/app/layout.tsx` 在 design-system.css 后加载；Vite 的 `src/main.tsx` 同样加载共享样式作为受控回退。静态 GitHub Pages 同步视觉，但只显示原有虚构内容。
- **可访问性**：保留原有 skip link、原生导航、语义表格、焦点轮廓和 44px 左右操作目标；支持 360px / 760px / 880px / 1100px 断点和 `prefers-reduced-motion`。
- **谨慎动效**：仅在宽屏 metric card 上提供轻微 hover 抬升；不使用滚动劫持、不影响键盘操作或全局状态。

## 测试与发布门禁

1. 前端 quick CI 运行 Vite/React19 和 Next16 TypeScript，以及原有单元测试。
2. 本分支额外运行 **Chromium Vite+Next16 真实响应式布局与来源表格合成回归**；新增 finance-signal-strip 与 page-context-tag 的 DOM/CSS 断言，保存 360px 截图 artifact。测试从模拟接口读取真实结构化的来源内容，绝不连生产 Amazon。
3. 发布页面不在生产业务服务承载真实 Session/API，不开启真实 OAuth。公开 GitHub Pages 示例仍有显著 `MOCK ONLY`、`SYNTHETIC`、虚构指标声明。
4. 合并后在精确 `main` SHA 完成 7-job A3，财务过账/仓库真实性门禁和 #104 生产 NO-GO 均不变。
5. 设计进一步提升的人工视觉 QA：在 Chromium 360/desktop 截图中检查中文文本溢出、主导航焦点、利润说明、表格区域横向滚动以及颜色对比。自动化不等于人工视觉评分，未经人工比对不得声称“像素级设计验收”。

## 业务真值约束

本变更 **CSS + JSX + 合成 UI 测试**，不修改 Amazon OAuth、Spring Boot、Flyway、利润算法、费用/银行/WMS 账本或权限路由。API 连通不等于真实 Amazon 授权，手工来源和外部签名不构成真实 WMS 收货认证，`UNKNOWN` 库存不得显示为 0，`SUCCEEDED` 不是财务净利润已认证。V175-C/V176 仍按既定 P0 处理。
