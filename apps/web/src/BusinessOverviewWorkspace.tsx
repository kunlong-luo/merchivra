import {useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {businessQualityCsvUrl,fetchBusinessOverview,
 type BusinessSources} from './lib/business-overview-v89'
import {exportPeriodProfitUrl} from './lib/period-profit'
import {formatSourceTime} from './lib/core-view'

function SourceBreakdown({sources}:{sources:BusinessSources}){
 return <span>模拟 {sources.mock} · 未核验 {sources.unverified} · LIVE 来源 {sources.live}</span>
}
export default function BusinessOverviewWorkspace({tenantId,onOpenFinance}:{
 tenantId:string;onOpenFinance:()=>void
}){
 const [selectedId,setSelectedId]=useState('')
 const data=useQuery({queryKey:['business-overview-v89',tenantId],
  queryFn:fetchBusinessOverview,staleTime:15000,retry:1})
 const list=data.data?.marketplaces??[]
 const selected=list.find(m=>m.id===selectedId)??list[0]
 const report=selected?.latestReport

 return <div className="core-workspace">
  <div className="core-intro"><strong>PostgreSQL 核心来源 · V89 数据质量总览</strong>
   <p>逐 Marketplace 汇总所有已入库 Listing、订单和每 SKU 最新 FBA 观测，
   不依赖 Demo Gateway，也不把浏览器第一页当作全量数据。</p>
   <p>这些是当前数据库读取结果，而非同一时点的 Amazon 上游快照、实盘库存或银行核对结果。</p>
  </div>
  <div className="core-controls">
   <button type="button" className="refresh-button"
    onClick={()=>void data.refetch()} disabled={data.isFetching}>
    {data.isFetching?'读取中…':'刷新经营质量'}</button>
   {data.data&&<a href={businessQualityCsvUrl}
    className="text-button" download="merchivra-business-source-quality.csv">
    导出当前数据质量 CSV</a>}
  </div>
  {data.isPending?<p className="empty-state" role="status">正在读取租户全部 Marketplace 来源质量…</p>:
   data.isError?<section className="state-panel error-panel" role="alert">
    <h2>经营来源质量读取失败</h2>
    <p>{data.error instanceof Error?data.error.message:'请检查会话与 PostgreSQL 状态'}</p>
    <button type="button" className="refresh-button"
     onClick={()=>void data.refetch()}>重新读取</button>
   </section>:
   data.data?<>
    <p role="status" className="panel-caption">
     本次查询时间 {formatSourceTime(data.data.capturedAt)} ·
     当前可访问的 Owner 租户共 {list.length} 个 Marketplace；
     没有全局资金快照、银行认证或会计过账。
     {data.data.mixedCurrencies?'检测到不同原始币种：不得直接合并金额。':
      '所有金额仅允许按各自 Marketplace 的 V64 已冻结报告分别查看。'}
    </p>
    {!list.length?<section className="panel full-panel">
     <h2>当前租户尚未建立 Marketplace</h2>
     <p>配置站点后再读取来源质量；不会自动开启 Amazon 授权。</p>
    </section>:<>
     <div className="core-controls">
      <label className="shop-filter" htmlFor="business-marketplace-select">
       <span>查看单站点来源详情</span>
       <select id="business-marketplace-select" value={selected?.id??''}
        onChange={e=>setSelectedId(e.target.value)}>
        {list.map(m=><option key={m.id} value={m.id}>
         {m.marketplaceId} · {m.region} · {m.currency}</option>)}
       </select>
      </label>
     </div>
     {selected&&<>
      <section className="metric-grid overview-kpi-grid" aria-label="当前站点来源质量摘要">
       {[
        ['Listing',selected.listings.total+' 条','当前站点已入库总量'],
        ['订单',selected.orders.total+' 笔','不推断预计收入或净利润'],
        ['最新 FBA',selected.inventory.skus+' 个 SKU','每 SKU 只选择最新一条来源观测'],
        ['未知库存',selected.inventory.unknown+' 个 SKU','UNKNOWN 不是 0，可售数缺失另计']
       ].map(([label,value,foot])=>
        <div className="metric-card" key={label}>
         <div className="metric-label">{label}</div>
         <div className="metric-value">{value}</div>
         <div className="metric-foot">{foot}</div>
        </div>)}
      </section>
      <section className="overview-profit-hero" aria-label="利润贡献与可审计来源完整性">
       <div className="overview-profit-main">
        <div className="overview-overline">
         <span className="overview-dot" aria-hidden="true"/>
         OPERATING INTELLIGENCE · {selected.currency}
        </div>
        <h2>利润总览 · 已知费用贡献（非净利润）</h2>
        <p className="overview-profit-description">
         优先回答这一站点能核对到哪一步、还有哪些成本尚未纳入。
         只读取已冻结的 V64 报告，绝不根据订单估算未经核实的净利润。</p>
        <div className="overview-profit-amount">
         <span className="overview-profit-amount-label">
          {report?'最近一期已冻结报告贡献金额':'本期来源贡献金额'}
         </span>
         <strong>{report?.state==='SUCCEEDED'&&report.contribution!==null?
          report.currency+' '+report.contribution:'贡献金额暂不可计算'}</strong>
         <small>贡献金额 ≠ 已核实净利润 ≠ 银行实际到账；未知费用不会记为 0。</small>
        </div>
        {report&&<span className="overview-report-period">
         UTC {report.after} 至 {report.before}（不含） · {report.currency} · {report.state}
        </span>}
        <div className="overview-profit-actions">
         <button className="primary-button" type="button"
          onClick={onOpenFinance}>进入利润与成本中心，核对原始费用及报表</button>
         {report?.state==='SUCCEEDED'&&
          <a href={exportPeriodProfitUrl(selected.id,report.id)}
           download="merchivra-period-contribution-snapshot.csv">
           导出已冻结的 V64 原币明细 CSV
          </a>}
        </div>
       </div>
       <div className="overview-profit-aside">
        <div className="overview-aside-heading">
         <span>订单与来源核对进度</span>
         <span className="overview-audit-state">未形成真实净利润</span>
        </div>
        <dl className="overview-audit-facts">
         <div><dt>候选订单</dt><dd>{report?.totalOrders??'—'}</dd></div>
         <div><dt>已纳入贡献</dt><dd>{report?.eligibleOrders??'—'}</dd></div>
         <div><dt>来源阻断订单</dt><dd>{report?.blockedOrders??'—'}</dd></div>
        </dl>
        <div className="overview-aside-alert">
         <strong>{report?'冻结任务的成功不代表全部费用已核对':'当前暂无可核对的 V64 报告'}</strong>
         <p>广告、头程、仓储、退款、税费、汇兑和其他费用仍须逐笔验证。
          外部 WMS 身份、仓库实收与历史 COGS 也未取得独立可信证明，
          因此不可据此确认最终净利润。</p>
        </div>
       </div>
      </section>
     </>}
     <section className="panel full-panel" aria-label="各 Marketplace 全库数据质量">
      <div className="panel-heading"><div>
       <h2>站点覆盖和来源风险</h2>
       <p>数量按各站点数据库事实独立计算，不包含任意本地演示预估销售额。</p>
      </div></div>
      <div className="table-scroll" role="region" tabIndex={0}
       aria-label="各 Marketplace 数据质量比较表">
       <table><thead><tr>
        {['Marketplace','原始币种','Listing','待映射','冲突','订单',
          '财务未观测订单','最新 FBA SKU','库存未知','库存过期','报告状态'].map(label=>
          <th key={label} scope="col">{label}</th>)}
       </tr></thead><tbody>
        {list.map(m=><tr key={m.id}>
         <td className="strong-cell">{m.marketplaceId}</td>
         <td>{m.currency}</td>
         <td>{m.listings.total}</td><td>{m.listings.unmapped}</td>
         <td>{m.listings.conflicts}</td><td>{m.orders.total}</td>
         <td>{m.orders.financeNotObserved}</td><td>{m.inventory.skus}</td>
         <td>{m.inventory.unknown}</td><td>{m.inventory.stale}</td>
         <td>{m.latestReport?.state??'尚无 V64 报告'}</td>
        </tr>)}
       </tbody></table>
      </div>
      <p className="panel-caption">没有跨店铺金额合计；库存列是最新每 SKU 质量数量，
       不是将多期观测或不同仓库库存相加。</p>
     </section>


     {selected&&<>
      <section className="two-column">
       <div className="panel"><div className="panel-heading"><div>
        <h2>Listing 映射与来源</h2>
        <p>全部 Listing，未映射/冲突不被自动丢弃或转换为可售 SKU。</p>
       </div></div>
       <div className="insight-row"><div>
        <strong>待映射 {selected.listings.unmapped} · 冲突 {selected.listings.conflicts}</strong>
        <small>缺失来源更新时间 {selected.listings.unknownSourceTime}</small>
       </div></div>
       <p className="panel-caption"><SourceBreakdown sources={selected.listings.sources}/></p>
       </div>
       <div className="panel"><div className="panel-heading"><div>
        <h2>订单来源完整度</h2>
        <p>仅反映入库订单的来源状态，不将订单商品小计当作净收入。</p>
       </div></div>
       <div className="insight-row"><div>
        <strong>订单项未观测 {selected.orders.itemsNotObserved}</strong>
        <small>财务未观测 {selected.orders.financeNotObserved} ·
         退款状态未知 {selected.orders.unknownRefund}</small>
        <small>缺少来源更新时间 {selected.orders.unknownSourceTime}</small>
       </div></div>
       <p className="panel-caption"><SourceBreakdown sources={selected.orders.sources}/></p>
       </div>
      </section>
      <section className="panel full-panel">
       <div className="panel-heading"><div>
        <h2>FBA 最新观测质量</h2>
        <p>严格复用原始观测排序：来源观测时间优先，
         再用 ERP 入库时间与 UUID 打破并列；不凭 UNKNOWN 补写 0。</p>
       </div></div>
       <div className="ops-summary">
        {[
         ['OBSERVED（已观测）',selected.inventory.observed],
         ['PARTIAL（不完整）',selected.inventory.partial],
         ['UNKNOWN（未知）',selected.inventory.unknown],
         ['STALE（原始过期标记）',selected.inventory.stale],
         ['可售数量为 NULL',selected.inventory.unknownFulfillable],
         ['已观测可售确切为 0',selected.inventory.observedZeroFulfillable],
         ['无来源观测时间',selected.inventory.unknownSourceTime]
        ].map(([title,count])=><div key={title}>
         <span>{title}</span><strong>{count}</strong>
        </div>)}
       </div>
       <p className="panel-caption"><SourceBreakdown sources={selected.inventory.sources}/></p>
       <p className="setup-hint">OBSERVED 为 0 不代表该站点所有 SKU 缺货；
       STALE 正数不能当作当前可售，质量计数不表示库存单位总和。</p>
      </section>

     </>}
    </>}
   </>:null}
 </div>
}
