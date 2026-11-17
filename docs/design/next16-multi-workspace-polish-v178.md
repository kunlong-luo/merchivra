# Merchivra · Next.js 16 多工作台视觉深化（V178）

状态：**V178 业务代码已合并，精确 main SHA A3 首次 7/7 通过（2026-11-17）**。继此前经营总览 / 利润首页视觉升级后，本轮优化订单、FBA、采购供应商、运营异常和 V64 利润明细。不新增 API、数据库、认证或会计行为。

## 真实验收记录

[PR #170](https://github.com/kunlong-luo/merchivra/pull/170) 合并业务 SHA `c057074111cc826cd646696ee51b3be5b768e6bb`；合并前 [CI #44093231552](https://github.com/kunlong-luo/merchivra/actions/runs/44093231552) **run_attempt=1 SUCCESS**（Java25、React19/Vite/Next16、Compose、Next+Vite Chromium 360px 实测；PG18 在快 CI 按策略 SKIPPED）；合并后精确业务 SHA [A3 #44093676183](https://github.com/kunlong-luo/merchivra/actions/runs/44093676183) **run_attempt=1、7 个核心任务 SUCCESS**（PG18/Flyway、Java、React/Next、Chromium、Compose HTTP、安全回归；PR 专属额外浏览器 job 在主线 SKIPPED）；[GitHub Pages #44093675534](https://github.com/kunlong-luo/merchivra/actions/runs/44093675534) SUCCESS（公开静态虚构数据展示）。

已人工抽查合成 Chromium 截图中的 360px 订单表格、FBA 观测摘要及采购表单，确认已保留移动端分页/表格横向滚动、状态来源和 FBA UNKNOWN 文案。浏览器断言覆盖 Next16 下订单与 FBA 两种来源范围、采购六步锚链接的实际目标，并非全部页面每种尺寸的像素级人工认证。**运营异常和 V64 真实站点的更多视觉/交互质量仍可继续深化**，没有启用真实 WMS/Amazon 或新利润过账。

## 实际界面变更

- 订单页面：在服务端查询结果与筛选条件后插入 **当前订单页来源质量**摘要，三个指标分别来自当前页真实记录：已加载订单数、订单项未观测数、来源更新时间缺失数。错误/加载状态显示横线，不声称有全站点汇总。
- FBA 库存页面：**当前 FBA 页观测质量**卡片分别统计加载的 SKU 数、fulfillable 为 null 的未知数量、quality 为 STALE 的来源过期数。已观测 0 仍显示 0，绝不把 UNKNOWN 装饰成“无库存”。
- 采购/供应商：六步视觉导航（本地仓库、供应商、采购草稿、待检收货、订单审批、本地库存）使用原生锚链接跳到原有真实业务区域。保留 Owner 双人审核、Base UI 二次确认和所有原库存写入逻辑；不新增快捷写入。
- 异常队列：五项服务端汇总使用更高识别度的高密度卡片；新增“已处理不等于源单完成”的证据提醒；不新增自动巡检、自动关闭或客户通信。
- V64 期报：在任务详情新增读取原有 current.totals.contribution 的来源贡献审计区，只有任务 SUCCEEDED 才显示原币数额，其余显示“候选订单尚未全部完成”。展示真实候选、合格、阻断数和批次状态，不把任何贡献数当正式净利润、银行到账或会计过账。
- 样式全部加入既有 apps/web/src/visual-refresh.css，Next16、Vite 回退同源；保留 Base UI、TanStack Query/Table、Lucide、浏览器原生表格与滚动容器。布局支持 360px/760px/1120px 与减少动画。

## 验收门禁

1. PR Java25/Gradle 与 React19/Vite/Next16 TS 快速检查。
2. 本分支的 Chromium Vite + Next16 实际浏览器检查：切换 /orders 与 /inventory 验证真实来源摘要 DOM/语义和分页表格；采购链接 6/6 指向真实区块；保留 360px/桌面截图、表格隔离横向滚动、Session/CSRF 回归。
3. 合并前只有浏览器 CI 全绿才能解除 Draft，合并后对精确 main SHA 运行完整 7-job A3，再归档文档。真实生产 #104 继续 NO-GO，Amazon Seller/Ads OAuth OFF。

## 不得宣称的事实

所有卡片来自**当前筛选页**，不是全库数/实时 Amazon；内部本地收货不自动进入 Amazon FBA；运营异常 DONE 不代表 FBA/仓库/财务事实闭环；V64 SUCCEEDED 不代表财务来源完整。V175-C 独立真实 WMS 经营主体、实体签收、历史 COGS 调整、V162/V64 最终净利润仍待可信证据。不会通过前端设计推进 G1/G2/G3 生产放行。
