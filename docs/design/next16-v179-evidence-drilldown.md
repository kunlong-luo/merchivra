# V179 · Next.js 16 原始证据只读下钻与采购详情精修

状态：**V179 已合并，精确 main SHA 的合成 A3 首次 7/7 通过（2026-11-17）**。

## 合成技术验收归档

业务 [PR #172](https://github.com/kunlong-luo/merchivra/pull/172) 已合并，精确 SHA `6d352e706c4e64237ffeef302213116fbb2ae1a8`；[PR CI #44097349142](https://github.com/kunlong-luo/merchivra/actions/runs/44097349142) 首次 SUCCESS（Java25、React19/Vite/Next16、Compose、Chromium 异常证据抽屉与采购详情无额外写入）；合并后 [A3 #44097675646](https://github.com/kunlong-luo/merchivra/actions/runs/44097675646) 精确主线 SHA **run_attempt=1、7 个核心任务全部 SUCCESS**（PG18/Flyway、Java、Next16、Chromium、Compose HTTP、安全/恢复回归；额外 PR 专属浏览器任务在 main SKIPPED）。[GitHub Pages #44097675161](https://github.com/kunlong-luo/merchivra/actions/runs/44097675161) SUCCESS（只读虚构展示）。

**验收范围**：真实 Next16 Chromium 在模拟租户来源数据上验证运营异常抽屉打开/关闭、采购单详情打开/关闭和无额外业务 POST；Java25、Next/Vite 类型与单测、PG18/Flyway 和全链 Compose HTTP 通过。V64 逐单来源金额的条件渲染由前端编译和现有类型契约覆盖，**未**使用真实外部 Amazon/仓储账单完成逐金额业务核对，不能把它称为银行到账或最终利润核实。下一阶段继续提高真实报表和来源交叉核验，生产 #104 继续 NO-GO。

## 本轮目标

继续提升 Merchivra 经营指挥台的实际可用性，而非另外生成无法开发的原型。保持 Next.js 16 正式目标、React 19、Base UI、TanStack、Vite 受控回退和 PostgreSQL 18；不引入新的 UI 库、API、依赖或数据库迁移。

### 1. V64 来源贡献逐单审计

候选订单表新增“核对来源”，打开 Base UI 可访问且可关闭的只读证据抽屉。仅从**当前已查询的报告分页**读取已提取的订单，不从别的租户、别的页、别的市场直接读取。显示原始订单 UUID、候选位置、ELIGIBLE/BLOCKED/PENDING、readiness、提取时间、原始 SHA-256 及销售、Amazon 费用、FBA 费用、历史商品成本、来源贡献等原币字段。

**金额保护**：只有 state 为 ELIGIBLE 且对应金额非 null 才显示原币数值。其他情况固定显示“来源不完整 · 不显示金额”，绝不补 0。即使 V64 SUCCEEDED，仍不构成广告、税、汇兑、银行结算或 V162 最终净利润认证。换报告或翻页时清除已选择的订单，避免把旧页旧报告的证据挂在新数据上。

### 2. 运营异常来源只读抽屉

TanStack 异常表新增“查看证据”，使用现有 Base UI Dialog 实现焦点限制、ESC、关闭及焦点还原。展示当前服务端 50 行分页中已获得的 ruleCode/title/sourceKey/marketplaceId/dataOrigin/lastSeenAt/state/severity/assignedTo/snoozedUntil。关联 FBA 异常保留回原处理单的已有入口和后端授权；抽屉仅传出原动作调用，不添加额外关闭/复核/自动写入按钮。切换分页时被选行不在当前页即不展示抽屉。

### 3. 采购明细与收货过程

采购单详情仍在原有本地页面，不用新弹窗遮挡真实收货表单；提高层级、加入“关闭采购详情”、本单所有行的已收/订购数量汇总、原币与审批/收货状态。关闭时同时清理已选择的 ASN 归属。汇总仅反映当前已查询的本地采购单，不等于 FBA 已入仓、可售 QC 或历史 COGS 回转。所有库存写入事务、Owner 审批与收货上限保持原样。

### 4. 样式、无障碍和回归

共用 src/visual-refresh.css、Next16 App Router 与 Vite 回退。使用已有 Base UI Dialog、Lucide X 与原生 dl，不重复造轮子。抽屉适配 360px；滚动内容独立，键盘焦点可访问，ESC、遮罩和关闭按钮正常，不引入外部字体资源。

PR 专属 Chromium 真实 Next16 合成测试新增运营异常详情开启/关闭、来源状态与无写入声明，采购单详情打开/关闭及写入次数不增加。V64 数据结构由当前类型解析及 TS/Next16 编译验证，缺少实际带来源报告的真实第三方外部数据，因此不能宣称外部金额认证或生产端到端完成。合并前必须快速 CI 和专属浏览器通过；合并后对精确业务 main SHA 完整 A3 7/7。

**仍然禁止**：自动 Amazon Seller/Ads 授权或写入、把未知库存或成本当零、真实 WMS 身份/实体收货未证实情况下处理 COGS/库存/真实净利润。生产 #104 仍 NO-GO。
