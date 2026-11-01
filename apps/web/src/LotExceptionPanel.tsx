import {useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import type {LocalWarehouse} from './lib/purchasing'
import {fetchLotPositions} from './lib/released-lots'
import {fetchLotCountCases,fetchDestinationTransfers} from './lib/lot-reconciliation'
import {
 fetchLotExceptions,proposeLotException,decideLotException,
 type NewLotException
} from './lib/lot-exceptions'

const reasonOk=(s:string)=>s.trim().length>=5&&s.trim().length<=300
export default function LotExceptionPanel({tenantId,currentUserId,warehouses}:{
 tenantId:string;currentUserId:string;warehouses:LocalWarehouse[]
}){
 const client=useQueryClient()
 const root=['purchasing',tenantId]
 const [warehouse,setWarehouse]=useState('')
 const [type,setType]=useState<NewLotException['resolutionType']>('RELEASED_SURPLUS_BIND')
 const [sourceId,setSourceId]=useState('')
 const [reference,setReference]=useState('')
 const [reason,setReason]=useState('')
 const [key,setKey]=useState(()=>crypto.randomUUID())
 const [reviewReason,setReviewReason]=useState('')
 const [success,setSuccess]=useState(''),[error,setError]=useState('')
 const counts=useQuery({queryKey:[...root,'count-reviews',warehouse],
  queryFn:()=>fetchLotCountCases(warehouse),enabled:!!warehouse,retry:false})
 const transfers=useQuery({queryKey:[...root,'transfer-receiving',warehouse],
  queryFn:()=>fetchDestinationTransfers(warehouse),enabled:!!warehouse,retry:false})
 const positions=useQuery({queryKey:[...root,'lot-positions',warehouse],
  queryFn:()=>fetchLotPositions(warehouse),enabled:!!warehouse,retry:false})
 const resolutions=useQuery({queryKey:[...root,'lot-exceptions',warehouse],
  queryFn:()=>fetchLotExceptions(warehouse),enabled:!!warehouse,retry:false})
 const refresh=()=>void client.invalidateQueries({queryKey:root})
 const surplus=(counts.data??[]).filter(c=>c.status==='EVIDENCE_REQUIRED'&&
  c.observedQuantity>c.expectedPositionQuantity&&!resolutions.data?.some(r=>r.countReviewId===c.id))
 const shortages=(transfers.data??[]).filter(t=>t.status==='MISMATCH'&&
  t.expectedQuantity>t.receivedQuantity&&!resolutions.data?.some(r=>r.transferActionId===t.actionId))
 const count=counts.data?.find(c=>c.id===sourceId)
 const sourceReceipt=positions.data?.find(p=>p.id===count?.positionId)?.receiptId??null
 const valid=!!sourceId&&reasonOk(reason)&&reference.trim().length>=4&&
  reference.trim().length<=120&&
  (type==='TRANSFER_SHORTAGE'||!!sourceReceipt)
 const propose=useMutation({mutationFn:()=>proposeLotException({
  requestKey:key,resolutionType:type,sourceId,
  sourceReceiptId:type==='RELEASED_SURPLUS_BIND'?sourceReceipt:null,
  evidenceReference:reference.trim(),reason:reason.trim()}),
  onSuccess:()=>{
   setSuccess('异常纠正申请已保存，必须由另一位 Owner 审核；尚未修改库存')
   setError('');setKey(crypto.randomUUID());setSourceId('');setReference('');
   setReason('');refresh()
  },onError:(e:Error)=>setError(e.message)})
 const decide=useMutation({mutationFn:({id,decision}:{
  id:string;decision:'APPROVE'|'REJECT'
 })=>decideLotException(id,{decision,reason:reviewReason.trim()}),
  onSuccess:r=>{
   setSuccess(r.status==='REJECTED'?'异常纠正申请已拒绝':
    r.resolutionType==='TRANSFER_SHORTAGE'?
     '已按真实目的仓短收差额正式扣减目标仓和库位库存':
     '已将原始已放行的未定位库存分配到来源库位，没有增加仓库库存')
   setError('');setReviewReason('');refresh()
  },onError:(e:Error)=>setError(e.message)})
 function submit(e:FormEvent){
  e.preventDefault()
  if(!valid||propose.isPending)return
  if(window.confirm(type==='TRANSFER_SHORTAGE'?
   '提交目标仓少收异常纠正待独立审核？审批后会正式扣减已过账的多记库存。':
   '提交盘盈来源绑定申请？必须存在原收货的已放行未定位库存，审批时不产生任何新库存。'))
   propose.mutate()
 }
 return <section className="panel full-panel" aria-label="V51 有来源库存异常纠正">
  <div className="panel-heading"><div>
   <h2>V51 · 有来源盘盈绑定与跨仓少收纠正</h2>
   <p>不允许仅凭盘点观察制造可售库存。盘盈只分配已经质检放行、
    但尚未指定库位的真实库存；少收纠正则需要目标仓未发生后续拣货或移位，
    由另一位 Owner 审核后按签名盘点差额扣账。</p>
  </div><button type="button" className="refresh-button" onClick={refresh}>刷新</button></div>
  <label htmlFor="v51-warehouse">需要处理的仓库</label>
  <select id="v51-warehouse" value={warehouse} onChange={e=>{
   setWarehouse(e.target.value);setSourceId('');setKey(crypto.randomUUID())}}>
   <option value="">选择仓库</option>
   {warehouses.filter(w=>w.status==='ACTIVE').map(w=>
    <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
  </select>
  {!!warehouse&&<>
   <h3>提出有来源的异常纠正申请</h3>
   <form className="team-invite-form" onSubmit={submit}>
    <label htmlFor="v51-type">异常类型</label>
    <select id="v51-type" value={type} onChange={e=>{
     setType(e.target.value as NewLotException['resolutionType']);
     setSourceId('');setKey(crypto.randomUUID())}}>
     <option value="RELEASED_SURPLUS_BIND">盘盈 · 绑定原已放行但未定位数量</option>
     <option value="TRANSFER_SHORTAGE">目的仓少收 · 正式减仓库/批次库存</option>
    </select>
    <label htmlFor="v51-source">有来源的待纠正异常</label>
    <select id="v51-source" value={sourceId} onChange={e=>{
     setSourceId(e.target.value);setKey(crypto.randomUUID())}}>
     <option value="">选择原始 V50 异常</option>
     {type==='RELEASED_SURPLUS_BIND'?surplus.map(c=>
      <option key={c.id} value={c.id}>
       盘盈 {c.observedQuantity-c.expectedPositionQuantity} 件 ·
       原批次 {c.positionId.slice(0,8)}</option>):
      shortages.map(t=><option key={t.actionId} value={t.actionId}>
       目标仓少收 {t.expectedQuantity-t.receivedQuantity} 件 ·
       调拨 {t.actionId.slice(0,8)}</option>)}
    </select>
    {type==='RELEASED_SURPLUS_BIND'&&count&&
     <p className="setup-hint">来源真实收货：
      {sourceReceipt??'无法确定原始收货，不能提交'}。
      审核时必须再次核对已放行合格总量及尚未定位的仓库库存。</p>}
    <label htmlFor="v51-reference">实物证据编号（人工录入）</label>
    <input id="v51-reference" value={reference} minLength={4} maxLength={120}
     onChange={e=>{setReference(e.target.value);setKey(crypto.randomUUID())}}
     placeholder="COUNT-EVIDENCE-2026-001"/>
    <label htmlFor="v51-reason">异常原因与证据说明</label>
    <input id="v51-reason" value={reason} minLength={5} maxLength={300}
     onChange={e=>{setReason(e.target.value);setKey(crypto.randomUUID())}}
     placeholder="实物位置、复核凭据及来源原因"/>
    <button className="primary-button" type="submit"
     disabled={!valid||propose.isPending}>提交独立审核（暂不调账）</button>
   </form>
   <h3>独立审批队列</h3>
   <p className="setup-hint">审批前重新读取当前库位与库存快照。
    来源不符、库存被其他操作消费、或数量已变化，都将拒绝而非强行调账。</p>
   <label htmlFor="v51-review-reason">审批意见</label>
   <input id="v51-review-reason" value={reviewReason} minLength={5} maxLength={300}
    onChange={e=>setReviewReason(e.target.value)}/>
   {resolutions.isPending?<p className="empty-state">正在读取审核单…</p>:
    resolutions.isError?<p role="alert" className="setup-error">{String(resolutions.error)}</p>:
    !resolutions.data?.length?<p className="empty-state">当前仓库暂无 V51 异常纠正申请。</p>:
    <div className="table-scroll" role="region" tabIndex={0}>
     <table><thead><tr><th scope="col">异常类型</th><th scope="col">待纠正</th>
      <th scope="col">凭据</th><th scope="col">状态</th>
      <th scope="col">另一位 Owner 审核</th></tr></thead><tbody>
      {resolutions.data.map(x=><tr key={x.id}>
       <td>{x.resolutionType==='TRANSFER_SHORTAGE'?'跨仓少收':'有来源盘盈'}</td>
       <td>{x.adjustmentQuantity} 件</td><td>{x.evidenceReference}</td>
       <td>{x.status==='PENDING_REVIEW'?'待审核':
        x.status==='REJECTED'?'已拒绝':x.operationId?
         '已正式扣减 · '+x.operationId.slice(0,8):'已绑定存量（未入账）'}</td>
       <td>{x.status==='PENDING_REVIEW'&&x.proposedBy!==currentUserId?
        <div className="sandbox-actions">
         <button type="button" className="primary-button"
          disabled={!reasonOk(reviewReason)||decide.isPending}
          onClick={()=>{if(window.confirm('独立审核并执行严格库存来源与快照校验？'))
           decide.mutate({id:x.id,decision:'APPROVE'})}}>批准并应用</button>
         <button type="button" className="refresh-button"
          disabled={!reasonOk(reviewReason)||decide.isPending}
          onClick={()=>decide.mutate({id:x.id,decision:'REJECT'})}>拒绝</button>
        </div>:'—'}</td>
      </tr>)}</tbody></table>
    </div>}
  </>}
  {success&&<p className="panel-caption" role="status">{success}</p>}
  {error&&<p className="setup-error" role="alert">{error}</p>}
 </section>
}
