import {useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import RefundAmountAllocationPanel from './RefundAmountAllocationPanel'
import {getRefundAudit,getRefundSnapshot,saveRefundItem,withdrawRefundItem,
 type RefundReviewDisposition} from './lib/refund-review'

const dispositions:Record<RefundReviewDisposition,string>={
 RESTOCKED_CLAIMED:'人工声称已重新入库（尚未验证库存凭证）',
 NOT_RESTOCKED:'未重新入库',
 UNKNOWN:'退货状态未知'
}
export default function RefundReviewPanel({tenantId,marketplaceId,eventId}:{
 tenantId:string;marketplaceId:string;eventId:string
}) {
 const cache=useQueryClient()
 const [itemId,setItemId]=useState('')
 const [quantity,setQuantity]=useState('1')
 const [status,setStatus]=useState<RefundReviewDisposition>('UNKNOWN')
 const [evidence,setEvidence]=useState('')
 const [showHistory,setShowHistory]=useState(false)
 const [notice,setNotice]=useState(''),[failure,setFailure]=useState('')
 const key=['finance','refund',tenantId,marketplaceId,eventId]
 const info=useQuery({queryKey:[...key,'snapshot'],
  queryFn:()=>getRefundSnapshot(marketplaceId,eventId),retry:false})
 const history=useQuery({queryKey:[...key,'history'],
  queryFn:()=>getRefundAudit(marketplaceId,eventId),enabled:showHistory,retry:false})
 const selected=info.data?.items.find(x=>x.itemId===itemId)
 const count=Number(quantity)
 const valid=!!selected&&Number.isInteger(count)&&count>0&&
   count<=(selected.orderedQuantity??0)&&
   /^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$/.test(evidence)
 const refresh=()=>void cache.invalidateQueries({queryKey:key})
 const save=useMutation({mutationFn:()=>saveRefundItem(marketplaceId,eventId,{
  itemId,quantity:count,disposition:status,evidenceReference:evidence}),
  onSuccess:()=>{setNotice('人工审核已保存并追加审计；并未变更库存或利润')
   setFailure('');refresh()},onError:(err:Error)=>setFailure(err.message)})
 const remove=useMutation({mutationFn:(id:string)=>
  withdrawRefundItem(marketplaceId,eventId,id),
  onSuccess:()=>{setNotice('审核已撤销，旧审计记录仍保留')
   setFailure('');refresh()},onError:(err:Error)=>setFailure(err.message)})
 function submit(e:FormEvent){e.preventDefault();if(valid)save.mutate()}
 return <section className="panel full-panel" aria-label="退款订单项审核">
  <div className="panel-heading"><div><h3>退款与退货商品处置审核 · 人工未核验</h3>
   <p>逐项记录退款涉及的商品和退货处置证据。手动标记“已重新入库”并非真实库存收货凭证，
     不会改变库存、成本核算或结算。</p></div>
   <button type="button" className="refresh-button" onClick={()=>void info.refetch()}>
    刷新</button></div>
  {info.isPending?<p className="empty-state">正在读取退款与订单项…</p>:
   info.isError?<p className="setup-error" role="alert">{String(info.error)}</p>:
   info.data&&<>
    <p><strong>关联来源订单：{info.data.externalOrderId}</strong>；
      交易有符号金额：{info.data.currency} {info.data.refundSourceAmount}</p>
    <p className="setup-hint">来源类别审核：
      {info.data.status==='MANUAL_REFUND_ITEM_REVIEW'?'已核平的退款类别，可人工填写处置':
       '来源分类尚未完全核平，不能新增退款处置审核'}；
      历史成本覆盖：{info.data.costReadiness}；
      库存收货独立验证：未完成。</p>
    {!!info.data.items.length&&<div className="table-scroll" role="region"
      tabIndex={0} aria-label="退款订单项审核明细"><table><thead><tr>
       {['来源订单项','Seller SKU','订购数量','审核数量','退货处置','成本参考影响','操作'].map(x=>
        <th key={x} scope="col">{x}</th>)}</tr></thead><tbody>
       {info.data.items.map(x=><tr key={x.itemId}>
        <td>{x.itemId}</td><td>{x.sellerSku??'未知'}</td>
        <td>{x.orderedQuantity??'未知'}</td><td>{x.reviewedQuantity??'未审核'}</td>
        <td>{x.disposition?dispositions[x.disposition]:'未审核'}</td>
        <td>{x.restockedCostSensitivity===null?'不可计算':
          (x.unitCurrency??info.data?.currency)+' '+x.restockedCostSensitivity+
          '（仅人工情景敏感度）'}</td>
        <td>{x.reviewedQuantity!==null&&
         <button className="text-button" type="button" disabled={remove.isPending}
          onClick={()=>remove.mutate(x.itemId)}>撤销审核</button>}</td>
       </tr>)}</tbody></table></div>}
    {info.data.status==='MANUAL_REFUND_ITEM_REVIEW'&&
     <form className="team-invite-form" onSubmit={submit}>
      <label htmlFor="refund-item-id">退款关联订单项</label>
      <select id="refund-item-id" value={itemId} onChange={e=>{
       setItemId(e.target.value)
       const row=info.data?.items.find(x=>x.itemId===e.target.value)
       setQuantity(String(row?.reviewedQuantity??1))
       setStatus(row?.disposition??'UNKNOWN')
       setEvidence(row?.evidenceReference??'')
      }}>
       <option value="">选择来源订单项</option>
       {info.data.items.map(x=><option value={x.itemId} key={x.itemId}>
        {x.itemId} · {x.sellerSku??'无 SKU'}</option>)}
      </select>
      <label htmlFor="refund-review-quantity">本次退款对应商品数量（不得超过原订购数量）</label>
      <input id="refund-review-quantity" type="number" min={1}
       max={selected?.orderedQuantity??undefined} step={1} value={quantity}
       onChange={e=>setQuantity(e.target.value)} required/>
      <label htmlFor="refund-review-status">人工退货处置判断</label>
      <select id="refund-review-status" value={status}
       onChange={e=>setStatus(e.target.value as RefundReviewDisposition)}>
       {Object.entries(dispositions).map(([code,label])=>
        <option key={code} value={code}>{label}</option>)}
      </select>
      <label htmlFor="refund-evidence">人工核对证据参考号（不上传个人信息）</label>
      <input id="refund-evidence" maxLength={160} minLength={8} required
       pattern="[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}" value={evidence}
       onChange={e=>setEvidence(e.target.value)} placeholder="RETURN-REVIEW-001"/>
      <button className="primary-button" type="submit"
       disabled={!valid||save.isPending}>保存人工退款项审核</button>
     </form>}
    <RefundAmountAllocationPanel tenantId={tenantId} marketplaceId={marketplaceId}
      eventId={eventId} refund={info.data}/>
    <button className="text-button" type="button"
      onClick={()=>setShowHistory(v=>!v)}>{showHistory?'收起':'查看'}审核历史</button>
    {showHistory&&(history.isPending?<p>正在读取审计…</p>:
     history.isError?<p className="setup-error">{String(history.error)}</p>:
     <div className="table-scroll" tabIndex={0} role="region" aria-label="退款审核历史">
      <table><thead><tr><th>订单项</th><th>操作</th><th>数量</th><th>处置</th>
       <th>证据编号</th><th>版本</th><th>操作时间</th></tr></thead><tbody>
      {(history.data??[]).map((x,i)=><tr key={i}>
       <td>{x.itemId}</td><td>{x.action}</td><td>{x.quantity}</td>
       <td>{dispositions[x.disposition]}</td><td>{x.evidenceReference}</td>
       <td>{x.revision}</td><td>{x.timestamp}</td></tr>)}
      </tbody></table></div>)}
   </>}
  {notice&&<p role="status" className="panel-caption">{notice}</p>}
  {failure&&<p role="alert" className="setup-error">{failure}</p>}
 </section>
}
