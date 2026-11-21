import {useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import CursorPageControls from './CursorPageControls'
import type {LotDisposition} from './lib/lot-reconciliation'
import {
 fetchSupplierReturnTracesPage,reportSupplierReturnDispatch,
 reportSupplierReturnReceipt
} from './lib/lot-supplier-returns'

/** V52: supplier return reports are local human assertions, not verified
 * supplier/transport acceptance or additional stock debit. */
const goodReason=(s:string)=>s.trim().length>=5&&s.trim().length<=300
const goodReference=(s:string)=>s.trim().length>=4&&s.trim().length<=120
export default function SupplierReturnTraceSection({tenantId,warehouseId,
 currentUserId,dispositions}:{
 tenantId:string;warehouseId:string;currentUserId:string;
 dispositions:LotDisposition[]
}){
 const cache=useQueryClient()
 const root=['purchasing',tenantId]
 const [cursors,setCursors]=useState<(string|null)[]>([null])
 const cursor=cursors.at(-1)??null
 const [dispositionId,setDispositionId]=useState('')
 const [requestKey,setRequestKey]=useState(()=>crypto.randomUUID())
 const [carrierReference,setCarrierReference]=useState('')
 const [dispatchReason,setDispatchReason]=useState('')
 const [receiptId,setReceiptId]=useState('')
 const [receivedQuantity,setReceivedQuantity]=useState(0)
 const [receiptReference,setReceiptReference]=useState('')
 const [receiptReason,setReceiptReason]=useState('')
 const [message,setMessage]=useState('')
 const [failure,setFailure]=useState('')
 const traces=useQuery({queryKey:[...root,'supplier-return-traces',warehouseId,cursor],
  queryFn:()=>fetchSupplierReturnTracesPage(warehouseId,cursor),retry:false})
 const reload=()=>{
  setCursors([null]);void cache.invalidateQueries({queryKey:root})
 }
 const dispatch=useMutation({
  mutationFn:()=>reportSupplierReturnDispatch({
   requestKey,dispositionId,carrierReference:carrierReference.trim(),
   reason:dispatchReason.trim()}),
  onSuccess:()=>{
   setMessage('已记录人工退货发运报告；不是物流公司或供应商确认')
   setFailure('');setRequestKey(crypto.randomUUID());setDispositionId('')
   setCarrierReference('');setDispatchReason('');reload()
  },
  onError:(e:Error)=>setFailure(e.message)
 })
 const receipt=useMutation({
  mutationFn:()=>reportSupplierReturnReceipt(receiptId,{
   receivedQuantity,receiptReference:receiptReference.trim(),
   reason:receiptReason.trim()}),
  onSuccess:r=>{
   setMessage(r.status==='RECEIPT_MISMATCH'?
    '已记录供应商实收数量不符的人工报告，需要进一步核对':
    '已记录由另一位 Owner 报告的供应商实收；不是外部自动核验')
   setFailure('');setReceiptId('');setReceiptReference('');setReceiptReason('')
   reload()
  },
  onError:(e:Error)=>setFailure(e.message)
 })
 const selected=traces.data?.items.find(t=>t.id===receiptId)
 const options=dispositions.filter(x=>x.method==='SUPPLIER_RETURN'&&
  x.status==='EXECUTED_REPORTED')
 const validReceipt=!!selected&&selected.status==='DISPATCH_REPORTED'&&
  selected.dispatchedBy!==currentUserId&&
  Number.isSafeInteger(receivedQuantity)&&receivedQuantity>=0&&
  receivedQuantity<=selected.quantity&&goodReference(receiptReference)&&goodReason(receiptReason)
 function reportDispatch(e:FormEvent){
  e.preventDefault()
  if(!dispositionId||!goodReference(carrierReference)||!goodReason(dispatchReason)||
   dispatch.isPending)return
  if(window.confirm('仅登记已执行处置的人工退回供应商发运，不调用承运商或重复扣库存。继续？'))
   dispatch.mutate()
 }
 function reportReceipt(e:FormEvent){
  e.preventDefault()
  if(!validReceipt||receipt.isPending)return
  if(window.confirm('另一位 Owner 已核对供应商回执吗？这里只记录人工报告，不代表物流系统已确认。'))
   receipt.mutate()
 }
 return <div aria-label="V52 本地供应商退货发运与回执追踪">
  <h3>4. 已执行供应商退货 · 发运和独立收货报告</h3>
  <p className="setup-hint">只有已由独立 Owner 批准并报告执行的供应商退货处置可以登记。
   人工发运参考号不是承运商实发证明；供应商收货由非发运者的 Owner 人工确认。
   本流程不重复扣减已隔离库存，也不会自动联系供应商或操作 Amazon。</p>
  <form className="team-invite-form" onSubmit={reportDispatch}>
   <label htmlFor="v52-return-disposition">已报告执行的供应商退货处置</label>
   <select id="v52-return-disposition" value={dispositionId}
    onChange={e=>{setDispositionId(e.target.value);setRequestKey(crypto.randomUUID())}}>
    <option value="">选择已执行处置</option>
    {options.map(d=><option key={d.id} value={d.id}>
     {d.id.slice(0,8)} · {d.quantity} 件 · {d.completionReference??'无参考号'}</option>)}
   </select>
   <label htmlFor="v52-carrier-ref">人工记录的物流参考号</label>
   <input id="v52-carrier-ref" value={carrierReference} minLength={4} maxLength={120}
    onChange={e=>{setCarrierReference(e.target.value);setRequestKey(crypto.randomUUID())}}/>
   <label htmlFor="v52-dispatch-reason">退货发运依据</label>
   <input id="v52-dispatch-reason" value={dispatchReason} minLength={5} maxLength={300}
    onChange={e=>{setDispatchReason(e.target.value);setRequestKey(crypto.randomUUID())}}/>
   <button type="submit" className="primary-button"
    disabled={!dispositionId||!goodReference(carrierReference)||
     !goodReason(dispatchReason)||dispatch.isPending}>登记本地发运报告</button>
  </form>
  {traces.isPending?<p className="empty-state">读取供应商退货历史…</p>:
   traces.isError?<p className="setup-error" role="alert">{String(traces.error)}</p>:
   !traces.data?.items.length?<p className="empty-state">当前页没有退货发运报告。</p>:
   <div className="table-scroll" tabIndex={0} role="region" aria-label="供应商退货报告历史">
    <table><thead><tr><th scope="col">退货处置</th><th scope="col">退货数量</th>
     <th scope="col">物流参考号</th><th scope="col">本地报告状态</th>
     <th scope="col">独立回执</th></tr></thead><tbody>
    {traces.data.items.map(t=><tr key={t.id}>
     <td>{t.dispositionId.slice(0,8)}</td>
     <td>{t.quantity} 件</td>
     <td>{t.carrierReference}</td>
     <td>{t.status==='DISPATCH_REPORTED'?'已人工报告发运，待回执':
      t.status==='SUPPLIER_RECEIPT_REPORTED'?'人工报告实收数量一致':
       '实收数量不符：需核对'}
      {t.receivedQuantity!==null&&<div className="secondary-cell">
       收货报告 {t.receivedQuantity} 件 · {t.receiptReference??''}</div>}</td>
     <td>{t.status==='DISPATCH_REPORTED'&&t.dispatchedBy!==currentUserId?
      <button type="button" className="refresh-button"
       onClick={()=>{setReceiptId(t.id);setReceivedQuantity(t.quantity);
        setReceiptReference('');setReceiptReason('')}}>以另一位 Owner 登记实收</button>:
       t.status==='DISPATCH_REPORTED'?'必须由另一位 Owner 核实':'已报告'}</td>
    </tr>)}
    </tbody></table>
   </div>}
  {traces.data&&<CursorPageControls label="供应商退货全库历史"
   page={cursors.length} hasMore={traces.data.hasMore}
   nextCursor={traces.data.nextCursor} busy={traces.isFetching}
   onPrev={()=>{setCursors(v=>v.slice(0,-1));setReceiptId('')}}
   onNext={c=>{setCursors(v=>[...v,c]);setReceiptId('')}}/>}
  {!!selected&&<form className="team-invite-form" onSubmit={reportReceipt}>
   <h3>独立 Owner 记录供应商回执</h3>
   <p className="setup-hint">当前发运报告 {selected.id.slice(0,8)}，数量 {selected.quantity} 件。
    本地手工回执只能由不同于发运报告人的 Owner 确认；不执行任何库存变更。</p>
   <label htmlFor="v52-received-quantity">报告的实收件数</label>
   <input id="v52-received-quantity" type="number" min={0} max={selected.quantity}
    value={receivedQuantity} onChange={e=>setReceivedQuantity(Number(e.target.value))}/>
   <label htmlFor="v52-receipt-ref">回执参考编号</label>
   <input id="v52-receipt-ref" value={receiptReference} minLength={4} maxLength={120}
    onChange={e=>setReceiptReference(e.target.value)}/>
   <label htmlFor="v52-receipt-reason">人工核实说明</label>
   <input id="v52-receipt-reason" value={receiptReason} minLength={5} maxLength={300}
    onChange={e=>setReceiptReason(e.target.value)}/>
   <button type="submit" className="primary-button"
    disabled={!validReceipt||receipt.isPending}>保存人工实收报告</button>
  </form>}
  {message&&<p role="status" className="panel-caption">{message}</p>}
  {failure&&<p role="alert" className="setup-error">{failure}</p>}
 </div>
}
