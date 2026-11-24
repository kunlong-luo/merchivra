import {useEffect,useState,type FormEvent} from 'react'
import {useQuery} from '@tanstack/react-query'
import {getReceiptCostIntegrityV217,validPurchaseReceiptId}
 from './lib/receipt-cost-integrity-v217'

const statusText:Record<string,string>={
 LOCAL_RECEIPT_ALLOCATION_INTACT_UNVERIFIED:'本地人工分配守恒 · 外部费用未核证',
 POTENTIAL_EXTERNAL_CHARGE_IDENTITY_COLLISION_HOLD:'来源编号／报告行可能重叠 · 待独立核查',
 LOCAL_RECEIPT_EVIDENCE_STALE_OR_UNAUDITED:'源版本、币种、金额或审核审计异常 · 已阻断',
 PENDING_LOCAL_REVIEW:'本地提案待审核 · 不可入账',
 REJECTED_LOCAL_PROPOSAL:'历史已拒绝的人工提案 · 不可复用'
}
const categoryHint='手工运费、进口税或支付／汇兑来源只是候选元数据；原币分配不等于可资本化费用。'

/** No background fetch: Owner must explicitly request one original receipt audit. */
export default function ReceiptCostIntegrityPanelV217({
 tenantId,marketplaceId,receiptId
}:{tenantId:string;marketplaceId:string;receiptId?:string}){
 const [input,setInput]=useState('')
 const [selected,setSelected]=useState('')
 const [expanded,setExpanded]=useState(false)
 // Each explicit inspection has its own cache identity: never reuse a prior
 // receipt result while a new external/manual-source audit is in flight.
 const [inspectionRevision,setInspectionRevision]=useState(0)
 useEffect(()=>{
  setExpanded(false);setSelected('');setInput('');setInspectionRevision(0)
 },[tenantId,marketplaceId,receiptId])
 const target=receiptId??selected
 const valid=validPurchaseReceiptId(target)
 const q=useQuery({
  queryKey:['finance',tenantId,marketplaceId,'v218-receipt-cost-integrity',target,inspectionRevision],
  queryFn:({signal})=>getReceiptCostIntegrityV217(marketplaceId,target,signal),
  enabled:expanded&&!!marketplaceId&&valid,
  retry:false,refetchOnWindowFocus:false,staleTime:0
 })
 const data=q.data
 const submit=(e:FormEvent)=>{
  e.preventDefault()
  if(validPurchaseReceiptId(input)){
   setSelected(input.trim());setInspectionRevision(v=>v+1);setExpanded(true)
  }
 }
 return <section className="panel full-panel"
   aria-label="V217 原采购收货单人工费用与跨来源身份核对">
  <div className="panel-heading"><div>
   <span className="v180-section-kicker">V217 / RECEIPT · UNVERIFIED COST EVIDENCE</span>
   <h2>原采购收货单 · 费用归属与重复来源核对</h2>
   <p>{categoryHint} 每次只核对所选费用来源 Marketplace，
    采购收货单本身是租户内身份，并非某站点独占。</p>
  </div></div>
  {receiptId?
   <p className="setup-hint">已从采购收货批次选择本地收货 ID：
    <code title={receiptId}>{receiptId}</code>。</p>:
   <form className="team-invite-form" onSubmit={submit}>
    <label htmlFor="v217-original-receipt-id">原采购收货单 UUID</label>
    <input id="v217-original-receipt-id" value={input} autoComplete="off"
     maxLength={36} placeholder="在采购收货批次表选择后复制 UUID"
     onChange={e=>{setInput(e.target.value);setExpanded(false)}}
     aria-invalid={input.trim().length>0&&!validPurchaseReceiptId(input)}/>
    <button type="submit" className="text-button"
     disabled={!validPurchaseReceiptId(input)||!marketplaceId}>
     按收货单查询本地费用证据
    </button>
    <p className="setup-hint">请输入精确 V20 收货 UUID；不按 SKU 或到货日期猜测。
     仅在提交后发出请求。</p>
   </form>}
  {receiptId&&<div className="sandbox-actions">
   <button className="text-button" type="button" aria-expanded={expanded}
    disabled={!marketplaceId||!valid}
    onClick={()=>{if(!expanded)setInspectionRevision(v=>v+1);
     setExpanded(v=>!v)}}>
    {expanded?'收起本地费用证据':'按需核对这笔收货的费用来源'}
   </button>
  </div>}
  {expanded&&<div>
   <div className="sandbox-actions">
    <button type="button" className="refresh-button"
     disabled={!valid||q.isFetching} onClick={()=>setInspectionRevision(v=>v+1)}>
     重新核对完整来源
    </button>
   </div>
   {q.isPending||q.isFetching?<p className="setup-hint" role="status">
     正在核对采购收货单原币归属、审核审计与跨来源身份…</p>:
    q.isError?<p className="setup-error" role="alert">
     {String(q.error)}。当前来源不可用，不得使用上次结果或将缺证当成本零。</p>:
    data&&<>
     <dl className="v179-evidence-facts">
      <div><dt>本地关联费用提案</dt><dd>{data.localPlanCount} 份（最多 50 份）</dd></div>
      <div><dt>外部真实运费／税费已核验</dt><dd>否</dd></div>
      <div><dt>真实批次 COGS</dt><dd>未核证 · 不计算</dd></div>
      <div><dt>财务过账</dt><dd>阻断</dd></div>
     </dl>
     {data.plans.length===0?
      <p role="status" className="v179-evidence-warning">
       当前来源 Marketplace 没有该收货单的本地人工费用提案。
       这代表成本证据未知，不代表头程费用、关税或汇兑成本为零。</p>:
      <div className="table-scroll" role="region" tabIndex={0}
       aria-label="收货单人工费用来源与原币守恒清单（不计入已核证利润）">
       <table><thead><tr>
        {['来源／提案','审核状态','所选收货原币候选金额',
          '同来源全部收货原币候选额','全部收货行','本地跨来源风险',
          '证据状态'].map(label=><th scope="col" key={label}>{label}</th>)}
       </tr></thead><tbody>{data.plans.map(plan=><tr key={plan.planId}>
        <td><code title={plan.sourceId}>{plan.sourceId.slice(0,8)}…</code>
         <div className="secondary-cell" title={plan.planId}>
          提案 {plan.planId.slice(0,8)}…</div></td>
        <td>{plan.state==='APPROVED_UNVERIFIED'?'内部已审核（未核证）':
          plan.state==='REJECTED'?'已拒绝':'待内部审核'}</td>
        <td>{plan.originalCurrency} {plan.localReceiptCandidateAmount}</td>
        <td>{plan.originalCurrency} {plan.localAllReceiptsCandidateAmount}</td>
        <td>{plan.globalReceiptCount}</td>
        <td>{plan.crossSourceIdentitySignalCount} 类来源身份线索 ·
         {plan.sharedSettlementLineCount} 条共享报告行</td>
        <td>{statusText[plan.integrityState]??plan.integrityState}
         {plan.blockerCodes.length>0&&<div className="secondary-cell">
          {plan.blockerCodes.join('、')}</div>}</td>
       </tr>)}</tbody></table>
      </div>}
     <p role="status" className="v179-evidence-warning">
      <strong>实际 landed cost、退款 COGS 回转与真实净利润均未核证，禁止过账。</strong>
      本表不合并不同币种，不把同一人工来源加到原有 V92 分摊和利润报表，
      也不将 V165 身份线索自动判定为真实重复扣费。
      {data.plans.length>0?' 未发现本地冲突也不能证明来源完整。':''}
      阻断原因：{data.blockerCodes.join('、')}。
     </p>
    </>}
  </div>}
  {!expanded&&<p className="setup-hint">
   未执行此项核对不代表真实成本为零；当前界面不会请求 Amazon、
   银行或第三方仓储，也不会写入任何财务数据。</p>}
 </section>
}
