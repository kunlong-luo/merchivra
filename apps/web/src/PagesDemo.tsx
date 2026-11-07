import {useState} from 'react'
import './pages-demo.css'

/**
 * Static public Pages preview: NO backend, no auth, no tokens, no network calls.
 * Do not import business API clients here. All identifiers and statistics are
 * fictional, and UI buttons only switch local tabs (never mutate data).
 */
type Screen='overview'|'purchasing'|'finance'|'operations'|'fba'|'deploy'
const views:{id:Screen;label:string;icon:string}[]=[
 {id:'overview',label:'经营总览',icon:'◫'},
 {id:'purchasing',label:'采购与供应商',icon:'▤'},
 {id:'finance',label:'财务与对账',icon:'▥'},
 {id:'operations',label:'运营自动化',icon:'✳'},
 {id:'fba',label:'FBA 库存',icon:'▣'},
 {id:'deploy',label:'部署与说明',icon:'◇'}
]
const notes:Record<Screen,string>={
 overview:'仅用于展示界面层级与数据质量标记。所有样例指标均为虚构值，不代表订单收入或真实净利润。',
 purchasing:'演示采购差异、供应商跟进与两人审批的呈现方式；无实际 PO、供应商通信或库存变更。',
 finance:'演示按原币核对与人工来源复核的入口。参考净额不等于银行到账，也不等于已核实利润。',
 operations:'展示异常工单的责任、优先级、截止时间和状态；无定时任务、外部提醒或真实工单处理。',
 fba:'FBA 数量仅是模拟观测，未知不是 0；本地 PO 和 ASN 不等于 Amazon 在途库存。',
 deploy:'真实登录、采购审批、Finance 数据与 PostgreSQL 服务只在自托管环境运行；GitHub Pages 仅提供静态展示。'
}
const purchases=[
 ['PO-DEMO-1042','内网 SKU · DEMO-COFFEE-A','本地待复核','18','待另一位 Owner 复核'],
 ['PO-DEMO-1039','内网 SKU · DEMO-BAG-B','待收货','120','ASN 尚未核验'],
 ['PO-DEMO-1031','内网 SKU · DEMO-LAMP-C','草稿','24','尚未提交采购']
]
const finance=[
 ['MANUAL-STMT-DEMO-025','USD','125.000000','来源未确认'],
 ['MANUAL-STMT-DEMO-024','EUR','-18.900000','待核对差异'],
 ['MANUAL-STMT-DEMO-023','USD','0.000000','未验证银行到账']
]
const ops=[
 ['模拟 FBA 库存来源过期','FBA_INVENTORY_STALE','高优先级','待认领'],
 ['模拟供应商 ASN 覆盖不足','ASN_COVERAGE_GAP','一般','已认领'],
 ['模拟金额来源不一致','SOURCE_TOTAL_DIFFERS','高优先级','待人工复核']
]
function DataTable({headers,rows,label}:{headers:string[];rows:string[][];label:string}){
 return <div className="table-scroll" role="region" tabIndex={0} aria-label={label}>
  <table><thead><tr>{headers.map(h=><th key={h} scope="col">{h}</th>)}</tr></thead>
   <tbody>{rows.map((row,i)=><tr key={i}>{row.map((cell,j)=>
    <td key={j} className={j===0?'strong-cell':undefined}>{cell}</td>)}</tr>)}</tbody>
  </table></div>
}
function Metric({name,value,detail}:{name:string;value:string;detail:string}){
 return <div className="metric-card"><div className="metric-label">{name}<span aria-hidden="true">◇</span></div>
  <div className="metric-value">{value}</div><div className="metric-foot">{detail}</div></div>
}
export default function PagesDemo(){
 const [screen,setScreen]=useState<Screen>('overview')
 const current=views.find(x=>x.id===screen)!
 return <div className="workspace public-demo">
  <a className="skip-link" href="#main-content">跳转到主要内容</a>
  <aside className="sidebar">
   <div className="brand-wrap"><div className="brand-icon">M</div>
    <div className="brand-copy"><strong>merchivra</strong><small>SELLER OPERATIONS</small></div></div>
   <div className="sidebar-section-title">公开静态演示</div>
   <nav className="nav-links" aria-label="公开演示模块">
    {views.map(view=><button type="button" key={view.id}
     aria-current={screen===view.id?'page':undefined}
     className={'nav-link '+(screen===view.id?'active':'')}
     onClick={()=>setScreen(view.id)}>
     <span className="nav-icon" aria-hidden="true">{view.icon}</span>{view.label}</button>)}
   </nav>
   <div className="sidebar-spacer"/>
   <div className="sidebar-info"><span className="local-indicator"/>
    <div><strong>PUBLIC READ-ONLY DEMO</strong>
     <small>仅合成数据 · 无 API / 数据库连接 · 不接受登录或写入</small></div></div>
   <a className="sidebar-repo" href="https://github.com/kunlong-luo/merchivra"
    target="_blank" rel="noopener noreferrer">GitHub 开源仓库 <span aria-hidden="true">↗</span></a>
  </aside>
  <main id="main-content" tabIndex={-1} className="main-panel">
   <header className="topbar"><div className="breadcrumb">
    Merchivra <span>/</span><strong>{current.label}</strong></div>
    <div className="topbar-right"><span className="connection" role="status">
     <span className="connection-dot" aria-hidden="true"/>离线静态预览</span>
     <span className="public-demo-mark">MOCK ONLY</span></div>
   </header>
   <div className="content">
    <div className="page-heading"><div>
     <div className="eyebrow">MERCHIVRA / PUBLIC SHOWCASE / NO BACKEND</div>
     <h1>{current.label}</h1><p>{notes[screen]}</p>
    </div><a className="refresh-button public-demo-link"
     href="https://github.com/kunlong-luo/merchivra#readme"
     target="_blank" rel="noopener noreferrer">查看项目源码 ↗</a></div>
    <div className="mock-banner" role="note"><span className="mock-banner-icon">ⓘ</span>
     <div><strong>这不是在线 ERP，也不是已连接 Amazon 的店铺。</strong>
      <span>下方所有店铺、商品、金额、事件和工单均为公开虚构样例。
       GitHub Pages 不执行采购、退款、授权、银行对账或任何后端写入。</span></div>
     <span className="mock-pill">SYNTHETIC</span></div>
    {screen==='overview'&&<>
     <div className="metric-grid">
      <Metric name="模拟站点" value="02" detail="独立 USD / EUR，未合并币种"/>
      <Metric name="模拟待处理工单" value="12" detail="本地虚构异常，非真实监控"/>
      <Metric name="模拟 SKU" value="128" detail="不代表真实 Amazon Listing"/>
      <Metric name="已核实净利润" value="不可计算" detail="无受信来源、税费及银行确认"/>
     </div>
     <div className="two-column"><section className="panel">
      <div className="panel-heading"><div><h2>核心操作入口预览</h2>
       <p>点击模块仅切换静态样例，不会调用后台。</p></div></div>
      <div className="public-demo-shortcuts">
       {views.filter(x=>x.id!=='overview'&&x.id!=='deploy').map(v=>
        <button type="button" key={v.id} onClick={()=>setScreen(v.id)}>
         <strong>{v.icon}　{v.label}</strong><span>浏览虚构样例 ↗</span></button>)}
      </div></section><section className="panel insight-panel">
       <div className="panel-heading"><div><h2>数据完整性原则</h2>
        <p>与完整自托管版本一致的业务表述。</p></div></div>
       <div className="insight-row"><span className="insight-icon warning">!</span><div>
        <strong>未知库存 ≠ 零库存</strong><small>缺失观测时不会推断可售数量</small></div></div>
       <div className="insight-row"><span className="insight-icon info">i</span><div>
        <strong>人工来源 ≠ 真实到账</strong><small>保留原始币种与来源标签</small></div></div>
       <div className="insight-row"><span className="insight-icon muted">✓</span><div>
        <strong>需要第二位审核者</strong><small>关键审批保留人类复核门禁</small></div></div>
      </section></div>
    </>}
    {screen==='purchasing'&&<><div className="metric-grid">
     <Metric name="合成采购单" value="03" detail="纯静态 UI 样例"/>
     <Metric name="待人工差异复核" value="01" detail="不可在此审批"/>
     <Metric name="待到货件数" value="162" detail="演示库存与 ASN 分离"/>
     <Metric name="供应商证据评分" value="证据不足" detail="不编造准时率或质量评级"/>
    </div><section className="panel full-panel">
     <div className="panel-heading"><div><h2>采购单与供应商人工催办</h2>
      <p>模拟条目，无真实操作按钮。</p></div><span className="count-pill">3 条静态记录</span></div>
     <DataTable label="虚构采购示例" headers={['采购参考','商品','状态','件数','核对要求']} rows={purchases}/>
     <p className="panel-caption">正式版本支持来源差异记录、索赔双人审批、采购待办分页和不可覆盖审计。此处不发出采购单。</p>
    </section></>}
    {screen==='finance'&&<><div className="metric-grid">
     <Metric name="模拟结算参考" value="03" detail="MANUAL_UNVERIFIED"/>
     <Metric name="资金到账已核验" value="否" detail="bankVerified = false"/>
     <Metric name="跨币种总利润" value="不可计算" detail="不以错误汇率汇总"/>
     <Metric name="财务写入" value="禁用" detail="静态站不存在 API"/>
    </div><section className="panel full-panel">
     <div className="panel-heading"><div><h2>原币结算参考与来源核对</h2>
      <p>演示 V97 结算参考分页，以及 V98 审核审计的布局。</p></div></div>
     <DataTable label="虚构财务对账示例" headers={['人工参考号','币种','有符号净额','核对状态']} rows={finance}/>
     <p className="panel-caption">数字仅供界面查看。不能用于真实 Amazon 结算、银行收款证明或会计记账。</p>
    </section></>}
    {screen==='operations'&&<><div className="metric-grid">
     <Metric name="模拟 OPEN" value="08" detail="待认领"/>
     <Metric name="模拟 ACKNOWLEDGED" value="04" detail="已认领"/>
     <Metric name="自动通知" value="关闭" detail="无后台调度"/>
     <Metric name="已发送远端指令" value="0" detail="静态网页不可写"/>
    </div><section className="panel">
     <div className="panel-heading"><div><h2>异常工作台只读预览</h2><p>状态机、负责人和审计能力需本地部署使用。</p></div></div>
     <DataTable label="虚构运营异常示例" headers={['标题','规则','严重度','模拟状态']} rows={ops}/>
    </section></>}
    {screen==='fba'&&<><div className="metric-grid">
     <Metric name="模拟已观测 SKU" value="96" detail="非实时 Amazon 数据"/>
     <Metric name="模拟未知 SKU" value="32" detail="UNKNOWN 不视作 0"/>
     <Metric name="Amazon 已授权" value="否" detail="OAuth 保持关闭"/>
     <Metric name="远端入仓写入" value="禁用" detail="无外部连接"/>
    </div><section className="panel">
     <div className="panel-heading"><div><h2>FBA 虚构库存观测</h2><p>展示仓储与 FBA 数据来源隔离。</p></div></div>
     <DataTable label="虚构 FBA 库存示例"
      headers={['SKU','来源','质量','可售观测','说明']}
      rows={[
       ['DEMO-SKU-01','MOCK','OBSERVED','25','仅合成样例'],
       ['DEMO-SKU-02','MOCK','UNKNOWN','未知','不按零库存预警'],
       ['DEMO-SKU-03','MOCK','STALE','历史 8','不可视为当前库存']
      ]}/>
    </section></>}
    {screen==='deploy'&&<section className="panel full-panel">
     <div className="panel-heading"><div><h2>完整 ERP 需要自托管</h2>
      <p>GitHub Pages 仅负责展示静态 React 页面，不能托管 Java 25 或 PostgreSQL 18。</p></div></div>
     <div className="public-demo-deploy">
      <div><h3>可操作版本</h3><p>本地或私有服务器部署 React、Spring Boot 4、PostgreSQL 18，
       配置强密码与租户会话后才可使用真实业务工作台。真实 Seller OAuth/SP-API 仍默认关闭。</p>
       <a className="primary-button public-demo-link" href="https://github.com/kunlong-luo/merchivra/blob/main/README.zh-CN.md" target="_blank" rel="noopener noreferrer">阅读完整自托管部署说明 ↗</a></div>
      <div><h3>公开 Pages 边界</h3><p>无需登录，所有内容固定、公开且不可修改。
       不连接数据库、没有用户密码和凭证、不支持真实采购、财务、退款或 Amazon 调用。</p>
       <a className="refresh-button public-demo-link" href="https://github.com/kunlong-luo/merchivra" target="_blank" rel="noopener noreferrer">在 GitHub 查看源码 ↗</a></div>
     </div></section>}
    <footer className="footer"><span>© Merchivra · Open Source Pre-alpha</span>
     <span>GitHub Pages 静态样例 · 仅虚构数据 · 真实写入禁用</span></footer>
   </div>
  </main>
 </div>
}
