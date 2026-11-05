import {useEffect,useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import type {LocalWarehouse} from './lib/purchasing'
import {fetchReceiptBatchesPage,fetchReceiptBatchSummary,relabelReceiptBatch,
 releaseReceiptBatch,fetchReceiptBatchEventsPage} from './lib/receipt-batches'
import CursorPageControls from './CursorPageControls'

const stateLabels:Record<string,string>={
 PENDING_QC:'待质量检验（不可用）',
 INSPECTED:'已质检，待另一 Owner 放行',
 RELEASED:'已放行合格件（进入可用本地库存）'
}
export default function ReceiptBatchPanel({tenantId,orderId,currentUserId,warehouses}:{
 tenantId:string;orderId:string;currentUserId:string;warehouses:LocalWarehouse[]
}){
 const client=useQueryClient()
 const [editId,setEditId]=useState('')
 const [expectedLot,setExpectedLot]=useState('')
 const [lotCode,setLotCode]=useState('')
 const [reason,setReason]=useState('')
 const [auditId,setAuditId]=useState('')
 const [message,setMessage]=useState(''),[failure,setFailure]=useState('')
 const root=['purchasing',tenantId]
 const [batchCursors,setBatchCursors]=useState<(string|null)[]>([null])
 const [auditCursors,setAuditCursors]=useState<(string|null)[]>([null])
 const batchCursor=batchCursors.at(-1)??null
 const auditCursor=auditCursors.at(-1)??null
 useEffect(()=>{setBatchCursors([null]);setAuditCursors([null])
  setEditId('');setAuditId('')},[orderId])
 const batches=useQuery({queryKey:[...root,'receipt-batches-page',orderId,batchCursor],
  queryFn:()=>fetchReceiptBatchesPage(orderId,batchCursor),enabled:!!orderId,retry:false})
 const summary=useQuery({queryKey:[...root,'receipt-batch-summary',orderId],
  queryFn:()=>fetchReceiptBatchSummary(orderId),enabled:!!orderId,retry:false})
 const events=useQuery({queryKey:[...root,'receipt-batch-events-page',auditId,auditCursor],
  queryFn:()=>fetchReceiptBatchEventsPage(auditId,auditCursor),enabled:!!auditId,retry:false})
 const clearPageSelection=()=>{setEditId('');setAuditId('');setAuditCursors([null])}
 const refresh=()=>void client.invalidateQueries({queryKey:root})
 const relabel=useMutation({mutationFn:()=>relabelReceiptBatch(editId,expectedLot,
  lotCode.trim(),reason.trim()),
  onSuccess:()=>{setMessage('批次编码已变更并记录审计');setFailure('');setEditId('');
   setReason('');refresh()},onError:(e:Error)=>setFailure(e.message)})
 const release=useMutation({mutationFn:(receiptId:string)=>releaseReceiptBatch(receiptId),
  onSuccess:r=>{setMessage('合格品 '+r.acceptedQuantity+
   ' 件已由独立 Owner 放行，已更新本地可用库存；不合格品仍隔离');
   setFailure('');refresh()},onError:(e:Error)=>setFailure(e.message)})
 function submit(e:FormEvent){
  e.preventDefault()
  if(!editId||!lotCode.match(/^[A-Za-z0-9][A-Za-z0-9_.-]{1,79}$/)
   ||reason.trim().length<5||reason.trim().length>300||relabel.isPending)return
  if(window.confirm('确认变更尚未质检的批次代码并保留原编号审计？'))relabel.mutate()
 }
 return <section className="panel full-panel" aria-label="本地收货批次及独立质检放行">
  <div className="panel-heading"><div>
   <h2>V46 收货待检批次与合格放行</h2>
   <p>新收货真实入库后先进入待检区，不计本地可用库存；质检员登记合格与不合格件数，
    必须由另一名 Owner 才能将合格件数放入本地可用库存。历史批次继续保持原始 V44 入账事实。</p>
  </div><button type="button" className="refresh-button" onClick={refresh}>刷新</button></div>
  {!orderId?<p className="setup-hint">在上方选择采购单以查看该订单的真实到货批次。</p>:<>
   <p className="setup-hint">整单待检实收 {summary.data?.pendingReceivedUnits??'—'} 件
    （{summary.data?.pendingBatchCount??'—'} 批次）；
    整单已检待放行合格品 {summary.data?.awaitingReleaseAcceptedUnits??'—'} 件
    （{summary.data?.awaitingReleaseBatchCount??'—'} 批次）。
    此合计来自数据库全订单，不是当前页；已记录实收不代表可用。</p>
   {summary.isError&&<p className="setup-error" role="alert">
    整单统计读取失败：{String(summary.error)}；请勿用当前页数量估算全单。</p>}
   {batches.isPending?<p className="empty-state">正在读取本地批次…</p>:
    batches.isError?<p className="setup-error" role="alert">{String(batches.error)}</p>:
    !batches.data?.items.length?<p className="empty-state">
     当前采购单没有 V46 待检批次。既有历史收货不进行静默回填。</p>:
    <div className="table-scroll" role="region" tabIndex={0} aria-label="本地真实收货批次">
     <table><thead><tr><th scope="col">批次编码 / 仓库</th>
      <th scope="col">实收到货</th><th scope="col">合格 / 不合格</th>
      <th scope="col">状态</th><th scope="col">操作</th></tr></thead><tbody>
      {batches.data.items.map(b=><tr key={b.receiptId}>
       <td>{b.lotCode}<div className="secondary-cell">
        {warehouses.find(w=>w.id===b.warehouseId)?.code??b.warehouseId}
        {' · 收货 '+b.receiptId.slice(0,8)}</div></td>
       <td>{b.receivedQuantity}</td>
       <td>{b.status==='PENDING_QC'?'尚未质检':
        b.acceptedQuantity+' / '+b.rejectedQuantity}</td>
       <td>{stateLabels[b.status]}</td>
       <td><div className="sandbox-actions">
        {b.status==='PENDING_QC'&&
         <button type="button" className="refresh-button"
          onClick={()=>{setEditId(b.receiptId);setExpectedLot(b.lotCode);
           setLotCode(b.lotCode);setReason('')}}>变更批次编码</button>}
        {b.status==='INSPECTED'&&b.inspectedBy!==currentUserId&&
         <button type="button" className="primary-button"
          disabled={release.isPending} onClick={()=>{
           if(window.confirm('确认以另一位 Owner 身份放行此批次已检合格品？将一次性进入本地可用库存，不可重复入账。'))
            release.mutate(b.receiptId)
          }}>独立审批并放行合格品</button>}
        {b.status==='INSPECTED'&&b.inspectedBy===currentUserId&&
         <span className="setup-hint">质检员不可自审</span>}
        <button type="button" className="text-button"
         onClick={()=>{setAuditCursors([null]);setAuditId(v=>v===b.receiptId?'':b.receiptId)}}>
         {auditId===b.receiptId?'收起来源事件':'批次审计'}</button>
       </div></td>
      </tr>)}</tbody></table>
    </div>}
   {!!batches.data&&<CursorPageControls label="批次放行工作台" page={batchCursors.length}
    hasMore={batches.data.hasMore} nextCursor={batches.data.nextCursor}
    busy={batches.isFetching}
    onPrev={()=>{setBatchCursors(v=>v.length>1?v.slice(0,-1):v);clearPageSelection()}}
    onNext={v=>{setBatchCursors(h=>[...h,v]);clearPageSelection()}}/>}
   {!!editId&&<form className="team-invite-form" onSubmit={submit}>
    <h3>修改待检批次编码（原编码保留审计）</h3>
    <label htmlFor="batch-lot-code">新的批次编码</label>
    <input id="batch-lot-code" value={lotCode} maxLength={80}
     pattern="[A-Za-z0-9][A-Za-z0-9_.-]{1,79}" required
     onChange={e=>setLotCode(e.target.value)}/>
    <label htmlFor="batch-lot-reason">修改原因</label>
    <input id="batch-lot-reason" value={reason} minLength={5} maxLength={300}
     onChange={e=>setReason(e.target.value)} required/>
    <button className="primary-button" type="submit" disabled={relabel.isPending||
     lotCode===expectedLot||reason.trim().length<5}>保存编码变更</button>
    <button className="text-button" type="button" onClick={()=>setEditId('')}>
     取消</button>
   </form>}
   {!!auditId&&<div aria-label="批次不可覆盖事件历史">
    <h3>批次来源与审批事件</h3>
    {events.isPending?<p className="empty-state">读取批次历史…</p>:
     events.isError?<p className="setup-error" role="alert">{String(events.error)}</p>:
     !events.data?.items.length?<p className="empty-state">未查询到事件。</p>:
     <div className="table-scroll" role="region" tabIndex={0}>
      <table><thead><tr><th scope="col">时间</th><th scope="col">事件</th>
       <th scope="col">批次变化</th><th scope="col">数量</th>
       <th scope="col">说明</th></tr></thead><tbody>
       {events.data.items.map(e=><tr key={e.id}>
        <td>{new Date(e.createdAt).toLocaleString('zh-CN')}</td>
        <td>{e.eventType}</td>
        <td>{e.oldLotCode??'—'} → {e.newLotCode??'—'}</td>
        <td>{e.quantity??'—'}</td><td>{e.note??'—'}</td>
       </tr>)}</tbody></table>
     </div>}
    {!!events.data&&<CursorPageControls label="批次审计历史" page={auditCursors.length}
     hasMore={events.data.hasMore} nextCursor={events.data.nextCursor}
     busy={events.isFetching}
     onPrev={()=>setAuditCursors(v=>v.length>1?v.slice(0,-1):v)}
     onNext={v=>setAuditCursors(h=>[...h,v])}/>}
   </div>}
  </>}
  {message&&<p className="panel-caption" role="status">{message}</p>}
  {failure&&<p className="setup-error" role="alert">{failure}</p>}
 </section>
}
