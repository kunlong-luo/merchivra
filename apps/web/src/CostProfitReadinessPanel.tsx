import {useEffect,useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {fetchCostProfitReadiness,type CostReadinessCursor}
 from './lib/cost-profit-readiness'

const labels:Record<string,string>={
 ADS_SPEND:'广告',INBOUND_FREIGHT:'头程运输',
 FBA_STORAGE:'FBA 仓储',FBM_SHIPPING:'FBM 配送',
 RETURN_HANDLING:'退货处理',IMPORT_DUTY:'进口税费',
 PAYMENT_AND_FX:'银行支付/汇兑',OTHER_OPERATING:'其他经营成本'
}
function monthNow(){
 const d=new Date()
 return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')
}
function endOfMonthExclusive(month:string){
 const [year,m]=month.split('-').map(Number)
 return new Date(Date.UTC(year,m,1)).toISOString().slice(0,10)
}
/**
 * An original-currency, local-claim workbench shared by Next and Vite.
 * Deliberately NOT named a net-profit widget: V91/92/94 remain unverified.
 */
export default function CostProfitReadinessPanel({tenantId,marketplaceId}:{
 tenantId:string;marketplaceId:string
}){
 const [month,setMonth]=useState(monthNow)
 const [history,setHistory]=useState<Array<CostReadinessCursor|undefined>>([undefined])
 const cursor=history.at(-1)
 const start=month+'-01'
 const end=endOfMonthExclusive(month)
 const readiness=useQuery({
  queryKey:['finance','cost-readiness',tenantId,marketplaceId,start,end,cursor],
  queryFn:()=>fetchCostProfitReadiness(marketplaceId,start,end,cursor),
  enabled:!!marketplaceId,retry:false
 })
 useEffect(()=>setHistory([undefined]),[marketplaceId,month])
 const data=readiness.data
 const remaining=Object.keys(labels).filter(cat=>
  !data?.buckets.some(b=>b.category===cat))
 return <section className="panel full-panel" aria-label="八类成本核算准备度">
  <div className="panel-heading"><div>
   <h2>全成本费用准备度 · 原币（未核验）</h2>
   <p>从 PostgreSQL 的人工费用和分摊事实读取，展示原币申报、已审元数据、获批但未核验的分配，以及冲突。没有真实费用认证，不能扣入净利润。</p>
  </div><button type="button" className="refresh-button"
   disabled={readiness.isFetching} onClick={()=>void readiness.refetch()}>
   {readiness.isFetching?'读取中…':'刷新'}</button></div>
  <div className="core-controls">
   <label htmlFor="cost-readiness-month">业务月份</label>
   <input id="cost-readiness-month" type="month" value={month}
    min="2020-01" max="2100-12" onChange={e=>{
      if(/^\d{4}-\d{2}$/.test(e.target.value))setMonth(e.target.value)
    }}/>
  </div>
  {readiness.isError?<p role="alert" className="setup-error">
   {readiness.error instanceof Error?readiness.error.message:'费用准备度读取失败'}
  </p>:readiness.isPending?<p className="empty-state">正在读取当前月份成本来源…</p>:
  data&&<>
   <p className="setup-hint" role="status">
    当前月份 {data.periodStart} 至 {data.periodEndExclusive}（不含结束日）。
    完整净利润：<strong>不可计算</strong>；外部来源认证：未完成；
    平台到账核对：未完成。以下所有金额都是人工主张，不能计入已核验利润。
   </p>
   {data.buckets.length===0?
    <p className="empty-state">本期间暂无人工费用来源。八类成本仍为未知，不代表金额为零。</p>:
    <div className="table-scroll" role="region" tabIndex={0}
     aria-label="人工费用按类别及原币分组">
     <table><thead><tr>
      {['费用类别','原币','来源数','人工申报金额','元数据已审但未核验金额',
       '获批但未核验分配','获批未分配余额','共用账单行的来源数'].map(h=>
        <th scope="col" key={h}>{h}</th>)}
     </tr></thead><tbody>{data.buckets.map(b=>
      <tr key={b.category+'/'+b.currency}>
       <td>{labels[b.category]??b.category}</td><td>{b.currency}</td>
       <td>{b.sourceCount}</td><td>{b.claimedAmount}</td>
       <td>{b.reviewedUnverifiedAmount}</td>
       <td>{b.approvedUnverifiedAllocationAmount}</td>
       <td>{b.approvedUnverifiedUnallocatedAmount}</td>
       <td>{b.sharedLineSourceCount}</td>
      </tr>)}</tbody></table></div>}
   <p className="setup-hint">
    仍缺外部核验证据的科目：{data.categoriesWithoutVerifiedExternalCoverage
      .map(x=>labels[x]??x).join('、')}。
    {remaining.length?'当前月份无人工费用来源的类别：'+remaining.map(x=>labels[x]).join('、')+'。':''}
    “没有来源”与“零费用”不同。
   </p>
   <div className="panel-heading"><div>
    <h3>费用来源与独立分摊状态</h3>
    <p>逐来源查看现有独立复核、账单关联和重复归属风险；最多 50 条/页。</p>
   </div></div>
   {data.sources.length===0?<p className="empty-state">当前页无费用来源。</p>:
    <div className="table-scroll" role="region" tabIndex={0}
     aria-label="人工费用来源准备度">
     <table><thead><tr>{['来源 ID','业务日期','类别','申报原币金额',
      '元数据复核','分摊','已批准未核验分配','未分配',
      '结算行关联 / 共用','风险'].map(h=>
       <th key={h} scope="col">{h}</th>)}
     </tr></thead><tbody>{data.sources.map(s=>
      <tr key={s.sourceId}>
       <td title={s.sourceId}><code>{s.sourceId.slice(0,8)}</code></td>
       <td>{s.businessDate}</td>
       <td>{labels[s.category]??s.category}</td>
       <td>{s.currency} {s.claimedAmount}</td>
       <td>{s.metadataReviewState}</td>
       <td>{s.activeAllocationState??'尚无有效分摊'}</td>
       <td>{s.approvedUnverifiedAllocationAmount??'尚未批准'}</td>
       <td>{s.approvedUnverifiedUnallocatedAmount??'未知'}</td>
       <td>{s.linkedSettlementLineCount} / {s.sharedSettlementLineCount}</td>
       <td>{s.approvalHeld?'待核对 · 阻断':s.localIdentityStatus+
        '（仅本地线索，未验证）'}</td>
      </tr>)}</tbody></table></div>}
   <div className="sandbox-actions" role="navigation" aria-label="人工费用来源分页">
    <span className="setup-hint">第 {history.length} 页 · 当前 {data.sources.length} 条</span>
    {history.length>1&&<button type="button" className="refresh-button"
      onClick={()=>setHistory(xs=>xs.slice(0,-1))}>上一页</button>}
    {data.hasMore&&data.nextBeforeDate&&data.nextBeforeId&&
     <button type="button" className="refresh-button"
      disabled={readiness.isFetching}
      onClick={()=>setHistory(xs=>[...xs,{
       beforeDate:data.nextBeforeDate!,beforeId:data.nextBeforeId!
      }])}>下一页</button>}
   </div>
  </>}
 </section>
}
