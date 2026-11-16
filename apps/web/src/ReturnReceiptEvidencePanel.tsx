import {useState} from 'react'
import LocalReturnIntakePanel from './LocalReturnIntakePanel'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {decideReceiptClaim,listReceiptClaims,requestReceiptClaim,
 getReceiptClaimAudit,type ReceiptClaim} from './lib/return-receipts'
import type {RefundRecovery} from './lib/refund-recovery'

const labels:Record<ReceiptClaim['state'],string>={
 PENDING:'待第二名 Owner 核对人工实收声明',
 REVIEWED_MANUAL:'人工声明已复核，不代表真实入库',
 REJECTED:'人工声明已驳回',
 STALE:'V63 退款/历史成本证据已变更'
}
const grades=[
 {value:'SELLABLE_CLAIMED',label:'人工声称可售（未确认）'},
 {value:'UNSELLABLE_CLAIMED',label:'人工声称不可售'},
 {value:'UNKNOWN',label:'无法确认可售性'}
] as const
export default function ReturnReceiptEvidencePanel({tenantId,marketplaceId,eventId,
 recovery}:{tenantId:string;marketplaceId:string;eventId:string;
 recovery:RefundRecovery}){
 const qc=useQueryClient()
 const key=['finance','return-receipts',tenantId,marketplaceId,eventId]
 const claims=useQuery({queryKey:key,
  queryFn:()=>listReceiptClaims(marketplaceId,eventId),retry:false})
 const [itemId,setItemId]=useState('')
 const [quantity,setQuantity]=useState(1)
 const [condition,setCondition]=useState<
 'SELLABLE_CLAIMED'|'UNSELLABLE_CLAIMED'|'UNKNOWN'>('UNKNOWN')
 const [reference,setReference]=useState('')
 const [note,setNote]=useState('')
 const [reviewReason,setReviewReason]=useState('')
 const [auditId,setAuditId]=useState<string|null>(null)
 const audit=useQuery({queryKey:[...key,'audit',auditId],enabled:!!auditId,
  queryFn:()=>getReceiptClaimAudit(marketplaceId,eventId,auditId!),retry:false})
 const create=useMutation({
  mutationFn:()=>requestReceiptClaim(marketplaceId,eventId,{
   itemId,quantity,condition,localWarehouseId:null,evidenceReference:reference,
   note}),
  onSuccess:()=>{setNote('');setReference('');void qc.invalidateQueries({queryKey:key})}
 })
 const decide=useMutation({
  mutationFn:({id,action}:{id:string;action:'approve'|'reject'})=>
   decideReceiptClaim(marketplaceId,eventId,id,action,reviewReason),
  onSuccess:()=>{setReviewReason('');void qc.invalidateQueries({queryKey:key})}
 })
 const reviewed=recovery.state==='REVIEWED'&&recovery.evidenceCurrent
 const ready=reviewed&&recovery.exposure.items.some(x=>x.itemId===itemId&&
   x.disposition==='RESTOCKED_CLAIMED'&&quantity<=x.refundedQuantity)
 const validRef=/^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$/.test(reference)
 const validNote=(s:string)=>s.trim().length>=10&&s.length<=500&&
  !/[\x00-\x1F\x7F]/.test(s)
 return <section className="panel full-panel" aria-label="V171 退货实收人工证据">
  <div className="panel-heading"><div>
   <h3>V171 · 消费者退货实收证据核查（人工未核验）</h3>
   <p>独立记录原退款商品、申报数量、可售性与证据引用。不能用采购收货流水或 V34 人工声称代替消费者退货实物核验；即使复核通过也不自动回补库存或冲回 COGS。</p>
  </div><button type="button" className="refresh-button"
   onClick={()=>void claims.refetch()} disabled={claims.isFetching}>刷新核查单</button></div>
  {claims.isError&&<p role="alert" className="setup-error">
   {String(claims.error)}</p>}
  <div className="core-controls">
   <label htmlFor="v171-item">选择原始退款项</label>
   <select id="v171-item" value={itemId} onChange={e=>setItemId(e.target.value)}>
    <option value="">选择退款商品（须已完成 V63 双人复核）</option>
    {recovery.exposure.items.map(x=><option key={x.itemId} value={x.itemId}>
     {x.itemId} · {x.sku} · 退款 {x.refundedQuantity} 件 · {x.disposition}
    </option>)}
   </select>
   <label htmlFor="v171-quantity">声称实收件数（不得超过该事件退款件数）</label>
   <input id="v171-quantity" type="number" min={1} step={1}
    value={quantity} onChange={e=>setQuantity(Number(e.target.value))}/>
   <label htmlFor="v171-condition">人工声称商品处置</label>
   <select id="v171-condition" value={condition}
    onChange={e=>setCondition(e.target.value as typeof condition)}>
    {grades.map(g=><option key={g.value} value={g.value}>{g.label}</option>)}
   </select>
   <label htmlFor="v171-reference">不含个人信息的实收证据编号</label>
   <input id="v171-reference" value={reference} maxLength={160}
    onChange={e=>setReference(e.target.value)}
    placeholder="LOCAL-RETURN-REF-20261116"/>
   <label htmlFor="v171-note">提交说明（10–500 字）</label>
   <textarea id="v171-note" value={note} rows={2} maxLength={500}
    onChange={e=>setNote(e.target.value)}
    placeholder="描述实物核对记录及仍需独立确认的证据"/>
   <button className="refresh-button" type="button"
    disabled={!ready||!validRef||!validNote(note)||create.isPending||
      !Number.isInteger(quantity)||quantity<1}
    onClick={()=>create.mutate()}>申请独立审核实收声明</button>
  </div>
  {!reviewed&&<p className="setup-hint">必须先完成当前版本 V63 退款来源双人复核；只有 RESTOCKED_CLAIMED 项可申请，仍不代表真实库存。</p>}
  {create.error&&<p role="alert" className="setup-error">{String(create.error)}</p>}
  <label htmlFor="v171-review-reason">独立审核说明（不同 Owner；不得参与该退款项的 V34/V35 编制）</label>
  <textarea id="v171-review-reason" value={reviewReason} rows={2}
   maxLength={500} onChange={e=>setReviewReason(e.target.value)}
   placeholder="说明对记录、数量和商品处置的独立人工核查"/>
  {decide.error&&<p role="alert" className="setup-error">{String(decide.error)}</p>}
  {claims.isPending?<p>读取人工实收核查单…</p>:
   claims.data?.length===0?<p className="empty-state">暂无实收申报证据；未知不等于已回库件数为零。</p>:
   <div className="table-scroll" role="region" tabIndex={0}
    aria-label="V171 退货实收核查单">
    <table><thead><tr>{['订单项','件数','处置','凭据','状态',
     '实物核实/COGS','审核操作'].map(label=><th key={label} scope="col">{label}</th>)}
    </tr></thead><tbody>{claims.data?.map(c=><tr key={c.id}>
     <td>{c.itemId}</td><td>{c.claimedQuantity}</td>
     <td>{grades.find(g=>g.value===c.condition)?.label}</td>
     <td>{c.evidenceReference}</td><td>{labels[c.state]}</td>
     <td>未验证 · 不可入账</td>
     <td>{c.state==='PENDING'&&c.evidenceCurrent&&
      <><button type="button" className="refresh-button"
       disabled={decide.isPending||!validNote(reviewReason)}
       onClick={()=>decide.mutate({id:c.id,action:'approve'})}>复核人工声明</button>
      <button type="button" className="text-button"
       disabled={decide.isPending||!validNote(reviewReason)}
       onClick={()=>decide.mutate({id:c.id,action:'reject'})}>驳回</button></>}
      <button type="button" className="text-button"
       onClick={()=>setAuditId(auditId===c.id?null:c.id)}>审计</button>
     </td></tr>)}</tbody></table></div>}
  {auditId&&(audit.isPending?<p>读取审计记录…</p>:
    audit.isError?<p role="alert" className="setup-error">{String(audit.error)}</p>:
    <div aria-label="V171 审计历史"><ul>{audit.data?.map((x,i)=>
     <li key={i}>{x.occurredAt} · {x.action} · {x.actorId} · {x.note}</li>)}</ul></div>)}
  <LocalReturnIntakePanel tenantId={tenantId} marketplaceId={marketplaceId}
   eventId={eventId}/>
  <p className="panel-caption">V171 只接受人工申报、复核与审计；所有 physicalReceiptVerified、saleabilityVerified、cogsReversalEligible 恒为 false，actualCogsReversal 为 null。不得用于 V162/V64 实际利润。</p>
 </section>
}
