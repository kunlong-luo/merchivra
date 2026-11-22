import {useEffect,useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {fetchRefundCostReadiness,type RefundCostCursor}
 from './lib/refund-cost-readiness'

function currentMonth(){
 const now=new Date()
 return now.getUTCFullYear()+'-'+String(now.getUTCMonth()+1).padStart(2,'0')
}
function endExclusive(month:string){
 const [year,m]=month.split('-').map(Number)
 return new Date(Date.UTC(year,m,1)).toISOString().slice(0,10)
}
const stateLabel:Record<string,string>={
 NOT_REQUESTED:'未申请历史成本复核',
 PENDING:'等待独立 Owner 审核',
 REVIEWED:'人工证据已审（未验证实收）',
 REJECTED:'人工证据已驳回',
 STALE:'来源证据缺失/已变更，需重新核对',
 INCOMPLETE_EVIDENCE:'缺少退款项、金额归属或历史成本证据'
}
const reversalBarrierLabel:Record<string,string>={
 REFUND_EVENT_IDENTITY_NOT_IN_SOURCE_SNAPSHOT:'退款事件身份与本次来源快照不一致',
 REFUND_ORDER_EVIDENCE_NOT_IN_SOURCE_SNAPSHOT:'订单关联与本次来源快照无法确认一致',
 REFUND_AMOUNT_OR_CURRENCY_CHANGED_SINCE_SNAPSHOT:'证据复核与来源快照金额或币种不一致',
 REFUND_ITEM_AMOUNT_OR_COST_EVIDENCE_UNAVAILABLE:'退款数量、金额分配或历史成本证据不完整',
 INDEPENDENT_CURRENT_REFUND_REVIEW_REQUIRED:'缺少当前有效的独立财务复核',
 REFUND_EVIDENCE_ORIGIN_INCONSISTENT:'来源证据标签不符合当前人工审核链',
 REFUND_REVIEW_EVIDENCE_NOT_CURRENT:'退款证据已失效或发生变化',
 RETURN_ITEM_DISPOSITION_UNRESOLVED:'退货商品处置仍为未知',
 EXTERNAL_REFUND_SOURCE_AUTHENTICITY_UNVERIFIED:'外部退款来源真实性尚未独立认证',
 PHYSICAL_RETURN_RECEIPT_AND_DISPOSITION_UNVERIFIED:'缺少可信实物签收与处置证明',
 G1_REFUND_COGS_REVERSAL_POLICY_NOT_APPROVED:'G1 退款成本冲回规则尚未批准',
 INVENTORY_AND_ACCOUNTING_POSTING_NOT_AUTHORIZED:'库存与会计过账尚未获准'
}
/** V170: read-only LIVE source refund risk, not a COGS reversal action. */
export default function RefundCostReadinessPanel({tenantId,marketplaceId}:{
 tenantId:string;marketplaceId:string
}){
 const [month,setMonth]=useState(currentMonth)
 const [history,setHistory]=useState<Array<RefundCostCursor|undefined>>([undefined])
 const cursor=history.at(-1)
 const from=month+'-01'
 const until=endExclusive(month)
 const q=useQuery({
  queryKey:['finance','refund-cost-readiness',tenantId,marketplaceId,from,until,cursor],
  queryFn:()=>fetchRefundCostReadiness(marketplaceId,from,until,cursor),
  enabled:!!marketplaceId,retry:false
 })
 useEffect(()=>setHistory([undefined]),[marketplaceId,month])
 const data=q.data
 return <section className="panel full-panel" aria-label="退款历史成本冲回准备度">
  <div className="panel-heading"><div>
   <h2>退款与退货历史成本风险 · 不能自动冲回</h2>
   <p>按原始 Finances 来源入账日（UTC）读取 PRODUCT_REFUND 事件，复用 V63 人工退货/历史成本证据。人工声明已回库并非物理收货，任何金额均未作为真实 COGS 回转。</p>
  </div><button type="button" className="refresh-button"
    disabled={q.isFetching} onClick={()=>void q.refetch()}>
    {q.isFetching?'正在读取…':'刷新'}</button></div>
  <div className="core-controls">
   <label htmlFor="refund-readiness-month">UTC 入账月份</label>
   <input id="refund-readiness-month" type="month" value={month}
    min="2020-01" max="2100-12" onChange={e=>{
     if(/^\d{4}-\d{2}$/.test(e.target.value))setMonth(e.target.value)
    }}/>
  </div>
  {q.isError?<p className="setup-error" role="alert">
    {q.error instanceof Error?q.error.message:'退款核算风险读取失败'}
   </p>:q.isPending?<p className="empty-state">读取退款源事件…</p>:data&&<>
   <p className="setup-hint" role="status">
    UTC 期间 {data.periodStartUtc} 至 {data.periodEndExclusiveUtc}（不含结束日）。
    已核验实际成本冲回：<strong>不可计算</strong>；
    退货实收与银行核对：未完成。原币退款金额不能直接当作利润损失或回款。
   </p>
   {data.buckets.length===0?
    <p className="empty-state">本期尚无标记为 PRODUCT_REFUND 的 LIVE Finances 来源；这不证明无退款，未分类来源和迟到数据仍需对账。</p>:
    <div className="table-scroll" role="region" tabIndex={0}
     aria-label="退款按原币入账源金额">
     <table><thead><tr>
      {['原币','已入库退款源事件数','来源有符号退款金额','完整性'].map(x=>
       <th key={x} scope="col">{x}</th>)}
     </tr></thead><tbody>{data.buckets.map(b=>
      <tr key={b.currency}>
       <td>{b.currency}</td><td>{b.sourceEventCount}</td>
       <td>{b.signedProductRefundAmount}</td>
       <td>仅当前已分类来源，其他退款未知</td>
      </tr>)}</tbody></table></div>}
   <div className="panel-heading"><div><h3>退款事件逐笔核对阻塞清单</h3>
    <p>金额敞口只在 V34/V35 退款数量及分配完整时展示；审批过期或证据缺失的事件不填造金额。每页 50 条。</p>
   </div></div>
   {data.events.length===0?<p className="empty-state">当前页无可展示的退款源事件。</p>:
    <div className="table-scroll" role="region" tabIndex={0}
     aria-label="退款历史成本风险来源">
     <table><thead><tr>
      {['来源事件','UTC 入账时间','有符号退款','订单人工关联',
       '复核状态','历史成本敞口（非冲回）','人工回库敏感度','会计冲回'].map(x=>
        <th key={x} scope="col">{x}</th>)}
     </tr></thead><tbody>{data.events.map(event=>
      <tr key={event.eventId}>
       <td title={event.eventId}><code>{event.eventId.slice(0,8)}</code></td>
       <td>{event.postedAtUtc}</td>
       <td>{event.currency} {event.signedSourceRefundAmount}</td>
       <td>{event.reviewedOrderId?event.reviewedOrderId.slice(0,8):'未关联'}</td>
       <td>{stateLabel[event.evidenceState]??event.evidenceState}
        {!event.evidenceCurrent?' · 未通过当前证据核验':''}</td>
       <td>{event.historicalCostExposure===null?'不可计算':
        event.currency+' '+event.historicalCostExposure}</td>
       <td>{event.claimedRestockCostSensitivity===null?'不可计算':
        event.currency+' '+event.claimedRestockCostSensitivity}</td>
       <td><details><summary>禁止自动冲回 · {event.cogsReversalBlockerCodes.length} 项阻断</summary>
        <ul>{event.cogsReversalBlockerCodes.map(code=>
         <li key={code}>{reversalBarrierLabel[code]??code}</li>)}</ul>
       </details></td>
      </tr>)}</tbody></table></div>}
   <div className="sandbox-actions" role="navigation" aria-label="退款成本事件分页">
    <span className="setup-hint">第 {history.length} 页 · 当前页 {data.events.length} 条</span>
    {history.length>1&&<button type="button" className="refresh-button"
      onClick={()=>setHistory(xs=>xs.slice(0,-1))}>上一页</button>}
    {data.hasMore&&data.nextBeforeAt&&data.nextBeforeId&&
     <button type="button" className="refresh-button" disabled={q.isFetching}
      onClick={()=>setHistory(xs=>[...xs,{
       beforeAt:data.nextBeforeAt!,beforeId:data.nextBeforeId!
      }])}>下一页</button>}
   </div>
  </>}
 </section>
}
