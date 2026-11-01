import {useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {fetchSkuProfitPreview,profitCsvUrl} from './lib/sku-profit'

function iso(date:Date){return date.toISOString().slice(0,10)}
function defaultDates() {
 const tomorrow=new Date()
 tomorrow.setUTCDate(tomorrow.getUTCDate()+1)
 const start=new Date(tomorrow)
 start.setUTCDate(start.getUTCDate()-30)
 return {after:iso(start),before:iso(tomorrow)}
}
const reasons:Record<string,string>={
 ELIGIBLE_PREVIEW:'已复核来源 · 仅贡献估算',
 MULTI_SKU_UNALLOCATED:'多 SKU 订单：订单级费用不可猜分',
 ELIGIBLE_MULTI_SKU_PREVIEW:'多 SKU 订单：逐项费用来源全部已独立审核',
 ORDER_FINANCIAL_SOURCE_INCOMPLETE:'多 SKU 订单：历史成本或财务分类未完全满足门禁',
 ORDER_ITEM_EVIDENCE_INCOMPLETE:'多 SKU 订单：部分订单项成本或映射不完整',
 LINKED_EVENT_SET_INCOMPLETE:'多 SKU 订单：关联财务事件集合不完整',
 ALLOCATIONS_AWAITING_INDEPENDENT_APPROVAL:'多 SKU 订单：需审核全部事件逐项分配',
 ALLOCATION_ITEM_NO_LONGER_MAPPED:'多 SKU 订单：商品项映射失效',
 ALLOCATION_EVIDENCE_CHANGED:'多 SKU 订单：分配来源变化，需重新审批',
 ORDER_EVENT_TOTALS_CHANGED:'多 SKU 订单：分配额与订单财务来源合计不匹配',
 ORDER_COST_TOTALS_CHANGED:'多 SKU 订单：成本总额已变化',
 ORDER_CONTRIBUTION_TOTALS_CHANGED:'多 SKU 订单：贡献金额不守恒',
 SKU_NOT_MAPPED:'订单商品未完整映射内部 SKU',
 NO_POSITIVE_UNITS:'订单商品数量缺失或为零',
 SOURCE_RESULT_TRUNCATED:'财务来源超过单订单安全读取上限',
 ORDER_LINKS_AWAITING_REVIEW:'财务交易与订单仍待人工匹配',
 FINANCES_CLASSIFICATION_INCOMPLETE:'Finances 费用分类未完整审核',
 NO_REVIEWED_LIVE_FINANCES:'缺少审核过的真实 Finances 交易',
 PRODUCT_SALES_NOT_REVIEWED:'缺少已审核商品销售分类',
 REFUND_COST_REVERSAL_UNVERIFIED:'退款成本回转尚未验证',
 ADJUSTMENT_ATTRIBUTION_UNVERIFIED:'调整项来源归属尚未验证',
 UNKNOWN_FINANCES_CATEGORY:'存在未知类别',
 UNIT_COST_NOT_READY:'历史成本未准备好',
 COGS_READY_PREVIEW:'待完成贡献来源校验'
}
export default function SkuProfitPanel({tenantId,marketplaceId}:{
 tenantId:string;marketplaceId:string
}){
 const [dates,setDates]=useState(defaultDates)
 const [after,setAfter]=useState(dates.after)
 const [before,setBefore]=useState(dates.before)
 const [offset,setOffset]=useState(0)
 const [skuFilter,setSkuFilter]=useState('')
 const scope=['finance','sku-profit',tenantId,marketplaceId,dates.after,dates.before,offset]
 const query=useQuery({queryKey:scope,
  queryFn:()=>fetchSkuProfitPreview(marketplaceId,dates.after,dates.before,offset),
  retry:false,staleTime:15000})
 const report=query.data
 const visible=report?.skuRows.filter(r=>!skuFilter||
  r.sku.toLowerCase().includes(skuFilter.toLowerCase()))??[]
 const money=(value:string|null)=>value===null?'—':value
 return <section className="panel full-panel" aria-label="店铺和 SKU 贡献利润预览">
  <div className="panel-heading"><div><h2>店铺 / SKU 贡献利润预览</h2>
   <p>仅对真实来源订单、完整历史成本、人工复核的 Finances 费用做保守的原币估算。
    多 SKU 订单须先完整审核每笔 Finances 事件的逐项金额，未审核退款和未知费用不会被猜分或记为零。</p>
  </div><button type="button" className="refresh-button"
   disabled={query.isFetching} onClick={()=>void query.refetch()}>
   {query.isFetching?'读取中…':'刷新来源'}</button></div>
  <div className="ops-toolbar">
   <label className="shop-filter">开始日期（UTC）
    <input type="date" value={after} onChange={e=>setAfter(e.target.value)}/>
   </label>
   <label className="shop-filter">结束日期，不包含当日（UTC）
    <input type="date" value={before} onChange={e=>setBefore(e.target.value)}/>
   </label>
   <button className="primary-button" type="button"
    disabled={!after||!before||after>=before}
    onClick={()=>{setDates({after,before});setOffset(0);setSkuFilter('')}}>
    应用时间范围
   </button>
   {report&&<a className="text-button"
    href={profitCsvUrl(marketplaceId,dates.after,dates.before,offset)}
    download="merchivra-provisional-sku-profit-page.csv">导出当前页 CSV</a>}
  </div>
  {query.isPending?<p className="empty-state">读取订单、成本与费用分类来源…</p>:
   query.isError?<p className="setup-error" role="alert">{String(query.error)}</p>:
   report&&<>
    <p className="setup-hint">当前 UTC 期间 {report.periodAfter} 至 {report.periodBefore}（不含结束日），
     当前第 {Math.floor(report.offset/report.pageSize)+1} 页，每页最多 30 笔。
     {report.periodTruncated?' 这是部分页汇总，不能代替整期利润。':''}</p>
    <div className="ops-summary">
     {[
      {label:'当前页订单',value:report.totals.scannedOrders},
      {label:'可估算订单',value:report.totals.eligibleOrders},
      {label:'来源不完整/不安全订单',value:report.totals.blockedOrders},
      {label:'当前页多 SKU 订单',value:report.totals.multiSkuOrders},
      {label:'已人工关联的 Finances 事件',value:report.totals.sourceEvents}
     ].map(item=><div key={item.label}><span>{item.label}</span><strong>{item.value}</strong></div>)}
    </div>
    <div className="ops-summary">
     {[
      {label:'已审核销售符号金额',value:report.totals.sales},
      {label:'已审核 Amazon 平台费用',value:report.totals.amazonFees},
      {label:'已审核 FBA 费用',value:report.totals.fbaFees},
      {label:'匹配日期版本的预计商品成本',value:report.totals.cogs},
      {label:'可计算订单的贡献估算合计',value:report.totals.contribution}
     ].map(item=><div key={item.label}><span>{item.label} · {report.totals.currency}</span>
      <strong>{item.value}</strong></div>)}
    </div>
    <p className="setup-hint">单独的人工广告费用参考（未扣减贡献）：
     {report.totals.currency} {report.unverifiedAdsSpendReference}，
     共 {report.unverifiedAdsRecordCount} 条，来源 MANUAL_UNVERIFIED。
     广告费用、退款、税费、汇兑与银行到账不完整，以上**不是净利润**。</p>
    <h3>当前页已审核单 SKU 与多 SKU 贡献估算</h3>
    <label className="shop-filter">筛选本页内部 SKU
     <input value={skuFilter} onChange={e=>setSkuFilter(e.target.value)}
      placeholder="仅筛选当前页，非全店搜索"/>
    </label>
    {!visible.length?<p className="empty-state">此页暂无可安全归入内部 SKU 的贡献金额，检查下方来源状态。</p>:
     <div className="table-scroll" role="region" tabIndex={0}
      aria-label="当前页可估算 SKU 贡献金额">
      <table><thead><tr>{['内部 SKU','订单数','销售件数','销售原币','Amazon 费用','FBA 费用','商品成本','贡献估算'].map(x=>
       <th scope="col" key={x}>{x}</th>)}</tr></thead>
       <tbody>{visible.map(r=><tr key={r.sku}><td>{r.sku}</td>
        <td>{r.orderCount}</td><td>{r.units}</td><td>{r.currency} {r.sales}</td>
        <td>{r.amazonFees}</td><td>{r.fbaFees}</td><td>{r.cogs}</td>
        <td><strong>{r.contribution}</strong></td></tr>)}</tbody></table></div>}
    <h3>逐订单来源与排除原因</h3>
    {!report.orders.length?<p className="empty-state">所选期间当前页没有 LIVE 订单。未纳入的人工/模拟订单不会被当作真实销售。</p>:
     <div className="table-scroll" role="region" tabIndex={0} aria-label="逐订单来源覆盖">
      <table><thead><tr>{['原始订单号','时间 UTC','内部 SKU','数量','来源准备度','贡献估算'].map(x=>
       <th scope="col" key={x}>{x}</th>)}</tr></thead>
       <tbody>{report.orders.map(order=><tr key={order.orderId}>
        <td>{order.externalOrderId}</td><td>{order.orderedAt}</td>
        <td>{order.sku??'未明确归属'}</td><td>{order.units}</td>
        <td>{reasons[order.readiness]??order.readiness}</td>
        <td>{money(order.contribution)}</td>
       </tr>)}</tbody></table></div>}
    <div className="sandbox-actions">
     <button type="button" className="refresh-button" disabled={offset===0}
      onClick={()=>setOffset(x=>Math.max(0,x-30))}>上一页</button>
     <button type="button" className="refresh-button"
      disabled={!report.hasMore||offset>=3000}
      onClick={()=>setOffset(x=>x+30)}>下一页</button>
    </div>
    <p className="panel-caption">本模块所有结果均为只读、分页、原币种的历史来源贡献估算：
      financialSourceComplete=false、bankVerified=false、ledgerPosted=false。
      不把 Amazon 结算报告重复加进 Finances 交易，不按订单预计收入代替真实费用来源。
      CSV 仅导出当前页，未符合门禁的订单金额会留空，不会填上虚假的零。</p>
   </>}
 </section>
}
