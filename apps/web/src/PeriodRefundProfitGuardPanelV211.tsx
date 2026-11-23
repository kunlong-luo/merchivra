import {useEffect,useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {getPeriodRefundProfitGuardV211} from './lib/period-refund-profit-guard-v211'

/**
 * V211 lives immediately under V188, but its full-period / per-order V210
 * revalidation is only fetched on explicit user inspection. No heavy 15s
 * polling and no money is represented as realized net profit.
 */
export default function PeriodRefundProfitGuardPanelV211({
 tenantId,marketplaceId,reportId
}:{tenantId:string;marketplaceId:string;reportId:string}) {
 const [expanded,setExpanded]=useState(false)
 useEffect(()=>setExpanded(false),[tenantId,marketplaceId,reportId])
 const query=useQuery({
  queryKey:['finance','v211-period-refund-guard',tenantId,marketplaceId,reportId],
  queryFn:()=>getPeriodRefundProfitGuardV211(marketplaceId,reportId),
  enabled:expanded&&!!marketplaceId&&!!reportId,
  staleTime:60_000,refetchOnWindowFocus:false,retry:false
 })
 const p=query.data
 const alerts=p?.periodPostedEvents.filter(event=>event.blockerCodes.length>0)??[]
 const anomaly=p?p.unmatchedPeriodSourceCount+p.multiMatchedPeriodSourceCount+
  p.periodRefundSourceOutsideFrozenCohortCount:0

 return <section className="v180-version-panel"
  aria-label="V211 期间退款归属、跨期与利润阻断">
  <div className="v180-version-heading"><div>
   <span className="v180-section-kicker">V211 / REFUND PERIOD & PROFIT GUARD</span>
   <h3>退款跨期与成本回转 · 利润阻断复核</h3>
   <p>对照 V188 冻结利润基线与 V210 整单退款证据：
    订单创建日与退款入账日分别核对，不把多份报表快照当作账本，
    也不把同一笔退款从已有平台有符号收入中再次扣除。</p>
  </div></div>
  <div className="sandbox-actions">
   <button type="button" className="text-button"
    aria-expanded={expanded} onClick={()=>setExpanded(value=>!value)}>
    {expanded?'收起期间退款核对':'核对完整期间退款归属（按需读取）'}
   </button>
   {expanded&&<button type="button" className="refresh-button"
    onClick={()=>{void query.refetch()}} disabled={query.isFetching}>
    重新核对</button>}
  </div>
  {!expanded?<p className="setup-hint">
   整单退款与跨期检查需要读取历史来源，按需执行，不每 15 秒重复查询。
   未执行检查不代表本期没有退款；实际净利润仍不可计算。</p>:
  query.isPending?<p role="status" className="setup-hint">
   正在核对全期已分类退款、冻结订单和其他期间的历史退款…</p>:
  query.isError?<p role="alert" className="setup-error">
   {String(query.error)}。来源缺失时不将退款、退货成本或利润记为零。
   请复核 V194/V210 证据或缩小报表期间后重试。</p>:
  p&&<>
   <div className="ops-summary">
    {[
      ['期间已分类退款事件',String(p.periodPostedSourceEventCount)],
      ['冻结订单中涉及退款的订单',String(p.cohortRefundOrderCount)],
      ['冻结订单的其他期间退款',String(p.refundEventsOutsideV64OrderDateWindow)],
      ['退款归属异常信号',String(anomaly)],
      ['其他重叠报表快照',String(p.intersectingOtherFrozenReportCount)],
      ['退款调整后的可确认净利润','证据不足 · 不计算']
    ].map(([label,value])=><div key={label}>
     <span>{label}</span><strong>{value}</strong>
    </div>)}
   </div>
   <p className="v180-compare-hold" role="status">
    <strong>本地守恒：</strong>
    {p.allLocalManualRefundConservationComplete&&
      p.originalCurrencySourceSnapshotConsistent?
       '现有人工来源暂时匹配，但不证明官方 Amazon 退款已覆盖完整':
       '存在遗漏、跨单、数量、版本或来源冲突；利润继续阻断'}。
    V188 已冻结的原币订单贡献是
    {' '}{p.v188Baseline.eligibleContribution??'未知'}，
    <strong>不会</strong>将下列退款原币金额额外扣减一次。
   </p>
   {p.currencies.length>0?
    <div className="table-scroll" role="region" tabIndex={0}
     aria-label="V211 不同原币的期间退款原始金额，绝不混合汇率">
     <table><thead><tr>
      {['原币','已分类退款事件','有符号来源金额（非二次扣减）',
       '本地 V210 与冻结报表核对','真实 COGS 回转'].map(col=>
       <th key={col} scope="col">{col}</th>)}
     </tr></thead><tbody>
      {p.currencies.map(c=><tr key={c.currency}>
       <td>{c.currency}</td><td>{c.sourceEventCount}</td>
       <td>{c.signedRefundSourceAmount}</td>
       <td>{c.fullyAttributedToV64Cohort?'本地归属匹配 · 未核证':'证据不完整'}</td>
       <td>未知 · 未入账</td>
      </tr>)}
     </tbody></table>
    </div>:
    <p className="setup-hint">暂无已分类的本期产品退款事件，
     不代表 Amazon 全部退款为零或来源完整。</p>}
   {alerts.length>0&&<>
    <p className="setup-hint">下表仅列出前 12 条异常事件用于定位，
     所有事件都已参与上方全期源数据计数（未按 50 条分页计算金额）。</p>
    <div className="table-scroll" role="region" tabIndex={0}
     aria-label="V211 最近筛选出的异常退款来源，不是全量财务记账表">
     <table><thead><tr>
      {['事件','来源币种','来源金额','阻断原因'].map(col=>
       <th key={col} scope="col">{col}</th>)}
     </tr></thead><tbody>{alerts.slice(0,12).map(e=><tr key={e.eventId}>
      <td>{e.eventId.slice(0,13)}…</td>
      <td>{e.currency}</td><td>{e.signedRefundSourceAmount}</td>
      <td>{e.blockerCodes.join('、')}</td>
     </tr>)}</tbody></table>
    </div>
   </>}
   {p.repeatedFrozenOrderCount>0&&<p className="setup-hint">
    发现 {p.repeatedFrozenOrderCount} 个订单同时存在于其他重叠时间窗的
    V64 冻结报表。<strong>这属于报表快照交叉，不代表已经发生财务重复入账</strong>；
    不得将不同版本的冻结 CSV 直接相加作为总利润。</p>}
   <p className="v179-evidence-warning" role="status">
    <strong>退款 COGS、真实净利润：均未核证，保持 null。</strong>
    没有真实 Amazon/Ads/银行、物流实物归还与 G1 期间会计规则，
    本地双人复核和原币数量守恒不足以授权入账。
    阻断代码：{p.blockerCodes.join('、')}。
   </p>
  </>}
 </section>
}
