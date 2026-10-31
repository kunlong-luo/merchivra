import {useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {getRefundAllocations,listRefundAllocationAudit,
 saveRefundAllocation,removeRefundAllocation} from './lib/refund-allocations'
import type {RefundSnapshot} from './lib/refund-review'

const labels:Record<string,string>={
 SOURCE_REFUND_NOT_READY:'商品退款来源金额尚未完全核对',
 CURRENCY_MISMATCH:'原币不一致，禁止分配',
 SIGNED_DIRECTION_MISMATCH:'金额与原始退款方向相反',
 OVER_ALLOCATED:'超过官方已核对退款金额',
 NO_ITEM_ALLOCATIONS:'尚未分配到商品',
 PARTIALLY_ALLOCATED:'已部分分配，仍有差额',
 SIGNED_TOTAL_MATCHES_UNVERIFIED:'来源商品退款金额与人工商品分配金额一致（非到账证明）'
}
export default function RefundAmountAllocationPanel({tenantId,marketplaceId,eventId,refund}:{
 tenantId:string;marketplaceId:string;eventId:string;refund:RefundSnapshot
}) {
 const cache=useQueryClient()
 const key=['finance','refund-allocations',tenantId,marketplaceId,eventId]
 const [itemId,setItemId]=useState('')
 const [amount,setAmount]=useState('')
 const [reference,setReference]=useState('')
 const [showAudit,setShowAudit]=useState(false)
 const [error,setError]=useState(''),[notice,setNotice]=useState('')
 const allocations=useQuery({queryKey:[...key,'view'],
  queryFn:()=>getRefundAllocations(marketplaceId,eventId),retry:false})
 const audit=useQuery({queryKey:[...key,'audit'],
  queryFn:()=>listRefundAllocationAudit(marketplaceId,eventId),
  enabled:showAudit,retry:false})
 const eligible=refund.items.filter(i=>i.reviewedQuantity!==null&&i.reviewedQuantity>0)
 const valid=eligible.some(i=>i.itemId===itemId)&&
  /^-?[0-9]{1,14}(?:\.[0-9]{1,6})?$/.test(amount)&&Number(amount)!==0&&
  /^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$/.test(reference)
 const refresh=()=>void cache.invalidateQueries({queryKey:key})
 const save=useMutation({mutationFn:()=>saveRefundAllocation(marketplaceId,eventId,{
  itemId,signedAmount:amount,currency:refund.currency,evidenceReference:reference}),
  onSuccess:()=>{setNotice('金额归属已审核，来源退款及成本未被改写')
   setError('');refresh()},onError:(e:Error)=>setError(e.message)})
 const withdraw=useMutation({mutationFn:(id:string)=>
  removeRefundAllocation(marketplaceId,eventId,id),
  onSuccess:()=>{setNotice('商品金额分配已撤销，操作已追加审计');
   setError('');refresh()},onError:(e:Error)=>setError(e.message)})
 function submit(e:FormEvent){e.preventDefault();if(valid)save.mutate()}
 const data=allocations.data
 return <section className="panel full-panel" aria-label="商品退款金额归属审核">
  <div className="panel-heading"><div><h3>商品退款金额归属 · 人工审核</h3>
   <p>将已核对的原币商品退款部分分配到完成 V34 数量审核的订单项。
    费用、税款或非商品退款部分不能擅自算作退款商品金额。</p></div>
   <button type="button" className="refresh-button"
    onClick={()=>void allocations.refetch()}>刷新</button></div>
  {allocations.isPending?<p>正在核对原币退款来源…</p>:
   allocations.isError?<p role="alert" className="setup-error">{String(allocations.error)}</p>:
   data&&<>
    <p role="status"><strong>{labels[data.summary.status]??data.summary.status}</strong></p>
    <div className="orders-sync-grid">
     <div><span>商品退款来源</span>
      <strong>{data.summary.sourceRefundAmount===null?'未知':
       refund.currency+' '+data.summary.sourceRefundAmount}</strong></div>
     <div><span>已分配商品金额</span>
      <strong>{data.summary.allocatedAmount===null?'不可核对':
       refund.currency+' '+data.summary.allocatedAmount}</strong></div>
     <div><span>尚未分配差额</span>
      <strong>{data.summary.remainingAmount===null?'不可核对':
       refund.currency+' '+data.summary.remainingAmount}</strong></div>
     <div><span>人工分配商品数</span><strong>{data.summary.itemCount}</strong></div>
    </div>
    {!!data.allocatedItems.length&&<div className="table-scroll" role="region"
      tabIndex={0} aria-label="商品退款金额分配记录">
     <table><thead><tr><th>订单项</th><th>原币有符号退款</th>
      <th>人工依据</th><th>审核版本</th><th>操作</th></tr></thead><tbody>
      {data.allocatedItems.map(i=><tr key={i.itemId}><td>{i.itemId}</td>
       <td>{i.currency} {i.signedAmount}</td><td>{i.evidenceReference}</td>
       <td>{i.revision}</td><td><button type="button" className="text-button"
        disabled={withdraw.isPending} onClick={()=>withdraw.mutate(i.itemId)}>
         解除金额归属</button></td></tr>)}</tbody></table></div>}
    {!data.reviewedFinancesRefund?<p className="setup-hint">
     请先使来源交易全部末级分类核平，并确认存在 PRODUCT_REFUND 商品退款金额。</p>:
     <form className="team-invite-form" onSubmit={submit}>
      <label htmlFor="refund-amount-item">已审核数量的订单项</label>
      <select id="refund-amount-item" value={itemId} onChange={e=>{
       const id=e.target.value;setItemId(id)
       const previous=data.allocatedItems.find(x=>x.itemId===id)
       setAmount(previous?.signedAmount??'')
       setReference(previous?.evidenceReference??'')
      }}>
       <option value="">选择订单项</option>
       {eligible.map(x=><option key={x.itemId} value={x.itemId}>
        {x.itemId} · 已审核 {x.reviewedQuantity} 件</option>)}
      </select>
      <label htmlFor="refund-amount-signed">原币 {refund.currency} 有符号退款金额</label>
      <input id="refund-amount-signed" inputMode="decimal" required
       pattern="-?[0-9]{1,14}(\.[0-9]{1,6})?" value={amount}
       onChange={e=>setAmount(e.target.value)} placeholder="-12.50"/>
      <label htmlFor="refund-amount-ref">金额归属审核依据编号</label>
      <input id="refund-amount-ref" minLength={8} maxLength={160}
       pattern="[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}"
       required value={reference} onChange={e=>setReference(e.target.value)}
       placeholder="REFUND-ALLOC-001"/>
      <button type="submit" className="primary-button" disabled={!valid||save.isPending}>
       审核并保存金额归属</button>
     </form>}
   </>}
  <button type="button" className="text-button"
   onClick={()=>setShowAudit(v=>!v)}>{showAudit?'收起':'查看'}退款金额归属审计</button>
  {showAudit&&(audit.isPending?<p>正在加载审计…</p>:
   audit.isError?<p role="alert" className="setup-error">{String(audit.error)}</p>:
   <div className="table-scroll" tabIndex={0} role="region" aria-label="商品退款金额审计">
    <table><thead><tr><th>商品项</th><th>操作</th><th>原币金额</th>
     <th>审核依据</th><th>版本</th><th>时间</th></tr></thead><tbody>
     {(audit.data??[]).map((i,n)=><tr key={n}>
      <td>{i.itemId}</td><td>{i.action}</td>
      <td>{i.currency} {i.signedAmount}</td><td>{i.evidenceReference}</td>
      <td>{i.revision}</td><td>{i.occurredAt}</td></tr>)}
    </tbody></table></div>)}
  {notice&&<p className="panel-caption" role="status">{notice}</p>}
  {error&&<p role="alert" className="setup-error">{error}</p>}
  <p className="setup-hint">金额分配与实际库存入库、退货运输、税费及银行到账属于不同证据链。
   即使金额完全对平，也不会自动冲回商品成本或将订单贡献额标为真实利润。</p>
 </section>
}
