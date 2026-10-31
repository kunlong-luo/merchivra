import {useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import type {LocalWarehouse} from './lib/purchasing'
import {fetchQualityReceipts,inspectQualityReceipt,fetchQualityReturns,
 createQualityReturn,actQualityReturn,fetchQualityFindings,scanQualityFindings,
 changeQualityFinding,type QualityFindingState} from './lib/purchase-quality'

const findingLabels:Record<string,string>={
 QC_REJECTED_PENDING:'质检不合格件待处置',
 QC_HOLD_AGED:'质检隔离件超过 7 天仍未处理'
}
const stateLabels:Record<string,string>={
 OPEN:'待处理',ACKNOWLEDGED:'已认领',DONE:'已人工处理',CLEARED:'条件已消失'
}
export default function PurchaseQualityPanel({tenantId,orderId,warehouses}:{
 tenantId:string;orderId:string;warehouses:LocalWarehouse[]
}){
 const client=useQueryClient()
 const [receiptId,setReceiptId]=useState('')
 const [rejected,setRejected]=useState(0)
 const [reason,setReason]=useState('')
 const [inspectionKey,setInspectionKey]=useState(()=>crypto.randomUUID())
 const [inspectionId,setInspectionId]=useState('')
 const [returnQty,setReturnQty]=useState(1)
 const [returnReference,setReturnReference]=useState('')
 const [returnKey,setReturnKey]=useState(()=>crypto.randomUUID())
 const [filter,setFilter]=useState<'ACTIVE'|'ALL'>('ACTIVE')
 const [message,setMessage]=useState(''),[failure,setFailure]=useState('')
 const [editing,setEditing]=useState('')
 const root=['purchasing',tenantId]
 const receipts=useQuery({queryKey:[...root,'quality-receipts',orderId],
  queryFn:()=>fetchQualityReceipts(orderId),enabled:!!orderId,retry:false})
 const returns=useQuery({queryKey:[...root,'quality-returns',inspectionId],
  queryFn:()=>fetchQualityReturns(inspectionId),enabled:!!inspectionId,retry:false})
 const findings=useQuery({queryKey:[...root,'quality-findings',filter],
  queryFn:()=>fetchQualityFindings(filter),retry:false})
 const refresh=()=>void client.invalidateQueries({queryKey:root})
 const inspect=useMutation({
  mutationFn:()=>inspectQualityReceipt(receiptId,{requestKey:inspectionKey,
   acceptedQuantity:(selected?.receivedQuantity??0)-rejected,
   rejectedQuantity:rejected,reason:reason.trim()}),
  onSuccess:r=>{setInspectionId(r.id);setMessage('已记录完整实收质检；不合格件数已从本地仓库余额隔离');
   setFailure('');setInspectionKey(crypto.randomUUID());setReceiptId('');
   setRejected(0);setReason('');refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 const createReturn=useMutation({
  mutationFn:()=>createQualityReturn(inspectionId,{requestKey:returnKey,
   quantity:returnQty,reference:returnReference.trim()||null}),
  onSuccess:()=>{setMessage('供应商退货草稿已保存，隔离库存未再次扣减');
   setFailure('');setReturnKey(crypto.randomUUID());setReturnReference('');refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 const changeReturn=useMutation({
  mutationFn:({id,action}:{id:string;action:'dispatch'|'cancel'})=>
   actQualityReturn(id,action),
  onSuccess:r=>{setMessage(r.status==='DISPATCHED'?'已记录本地退货发出（非供应商签收）':
   '退货草稿已取消');setFailure('');refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 const scan=useMutation({mutationFn:scanQualityFindings,
  onSuccess:r=>{setMessage('质检异常巡检完成：命中 '+r.matchedCount+
   ' 项，清除 '+r.clearedCount+' 项');setFailure('');refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 const changeFinding=useMutation({
  mutationFn:({id,state}:{id:string;state:QualityFindingState})=>
   changeQualityFinding(id,state),onMutate:v=>setEditing(v.id),
  onSuccess:()=>{setMessage('质检异常状态已更新');setFailure('');refresh()},
  onError:(e:Error)=>setFailure(e.message),onSettled:()=>setEditing('')})
 const selected=receipts.data?.find(r=>r.id===receiptId)
 const selectedInspection=receipts.data?.find(r=>r.inspectionId===inspectionId)
 const draftAllocated=returns.data?.filter(r=>r.status==='DRAFT')
   .reduce((n,r)=>n+r.quantity,0)??0
 const unallocated=Math.max(0,(selectedInspection?.heldQuantity??0)-draftAllocated)
 const canInspect=!!selected&&!selected.inspectionId&&!!selected.warehouseId&&
  Number.isSafeInteger(rejected)&&rejected>=0&&rejected<=selected.receivedQuantity&&
  reason.trim().length>=5&&reason.trim().length<=300&&!inspect.isPending
 function submitInspection(e:FormEvent){
  e.preventDefault();if(!canInspect)return
  if(!window.confirm('确认对整笔本地实收进行一次性质检？不合格件将离开台账余额，不能直接撤销。'))return
  inspect.mutate()
 }
 return <section className="panel full-panel" aria-label="本地采购到货质检及供应商退货">
  <div className="panel-heading"><div><h2>到货质检、隔离库存与供应商退货</h2>
   <p>质检仅针对已经真实登记的本地采购收货，不能把 ASN 申报当作实收。
    既有收货先入账，随后质检不合格件一次性扣到独立隔离记录；
    供应商退货发出不重复扣库存。</p></div>
   <button className="refresh-button" type="button" onClick={refresh}>刷新</button></div>
  {!orderId?<p className="empty-state">选择已有采购单后查看其实际收货及质检。</p>:<>
   <h3>实物收货与一次性质检</h3>
   {receipts.isPending?<p className="empty-state">读取真实收货记录…</p>:
    receipts.isError?<p className="setup-error" role="alert">{String(receipts.error)}</p>:
    !receipts.data?.length?<p className="empty-state">
     当前采购单还没有实际收货，不可进行质检。</p>:
    <div className="table-scroll" role="region" tabIndex={0} aria-label="采购收货与质检结果">
     <table><thead><tr><th scope="col">收货记录 / 仓库</th>
      <th scope="col">实收件数</th><th scope="col">合格 / 不合格</th>
      <th scope="col">仍在隔离</th><th scope="col">操作</th></tr></thead><tbody>
      {receipts.data.map(r=><tr key={r.id}>
       <td>{r.id.slice(0,8)}
        <div className="secondary-cell">
         {warehouses.find(w=>w.id===r.warehouseId)?.code??
          (r.warehouseId??'历史收货未分配仓库')}</div></td>
       <td>{r.receivedQuantity}</td>
       <td>{r.inspectionId?r.acceptedQuantity+' / '+r.rejectedQuantity:'尚未质检'}</td>
       <td>{r.inspectionId?r.heldQuantity:'未知'}</td>
       <td><div className="sandbox-actions">
        {!r.inspectionId&&r.warehouseId&&
         <button className="refresh-button" type="button"
          onClick={()=>{setReceiptId(r.id);setRejected(0);
           setReason('');setInspectionKey(crypto.randomUUID())}}>开始质检</button>}
        {r.inspectionId&&r.rejectedQuantity>0&&
         <button className="refresh-button" type="button"
          onClick={()=>setInspectionId(r.inspectionId!)}>查看退货</button>}
       </div></td>
      </tr>)}</tbody></table>
    </div>}
   {!!selected&&<form className="team-invite-form" onSubmit={submitInspection}>
    <h3>质检记录：实际收货 {selected.receivedQuantity} 件</h3>
    <p className="setup-hint">仅允许对整条收货质检一次，合格件数自动等于实收减去不合格件数。
     已有出库造成不足以隔离时，服务端会拒绝，不能产生负库存。</p>
    <label htmlFor="qc-rejected">本次不合格件数</label>
    <input id="qc-rejected" type="number" min={0}
     max={selected.receivedQuantity} step={1} value={rejected}
     onChange={e=>{setRejected(Number(e.target.value));setInspectionKey(crypto.randomUUID())}}/>
    <p className="setup-hint">合格 {selected.receivedQuantity-rejected} 件，
     不合格 {rejected} 件。</p>
    <label htmlFor="qc-reason">质检原因 / 证据说明</label>
    <input id="qc-reason" minLength={5} maxLength={300} value={reason}
     placeholder="例如：外包装破损待供应商退货"
     onChange={e=>{setReason(e.target.value);setInspectionKey(crypto.randomUUID())}} required/>
    <button className="primary-button" type="submit" disabled={!canInspect}>
     确认质检并隔离不合格品</button>
    <button className="text-button" type="button" onClick={()=>setReceiptId('')}>
     取消编辑</button>
   </form>}
   {!!inspectionId&&<div className="team-invite-form">
    <h3>质检隔离品退货处理</h3>
    <p className="setup-hint">隔离品合计 {selectedInspection?.rejectedQuantity??'—'} 件，
     已申报发出后待处置 {selectedInspection?.heldQuantity??'—'} 件；
     未分配可创建退货 {unallocated} 件。这里的发出状态不代表供应商实际签收或退款。</p>
    {returns.isPending?<p className="empty-state">读取供应商退货…</p>:
     returns.isError?<p className="setup-error" role="alert">{String(returns.error)}</p>:
     <div className="table-scroll" role="region" tabIndex={0} aria-label="供应商退货历史">
      <table><thead><tr><th scope="col">退货记录</th><th scope="col">件数</th>
       <th scope="col">参考号</th><th scope="col">状态</th>
       <th scope="col">操作</th></tr></thead><tbody>
       {(returns.data??[]).map(r=><tr key={r.id}>
        <td>{r.id.slice(0,8)}</td><td>{r.quantity}</td>
        <td>{r.reference??'—'}</td>
        <td>{r.status==='DRAFT'?'退货草稿':
         r.status==='DISPATCHED'?'人工报告已发出':'已取消'}</td>
        <td><div className="sandbox-actions">
         {r.status==='DRAFT'&&<>
          <button type="button" className="primary-button" disabled={changeReturn.isPending}
           onClick={()=>{if(window.confirm('确认本地隔离品已向供应商发出？不等于供应商签收。'))
            changeReturn.mutate({id:r.id,action:'dispatch'})}}>记录退货发出</button>
          <button type="button" className="text-button" disabled={changeReturn.isPending}
           onClick={()=>{if(window.confirm('取消退货草稿并释放隔离件数预留？'))
            changeReturn.mutate({id:r.id,action:'cancel'})}}>取消草稿</button>
         </>}
        </div></td>
       </tr>)}
      </tbody></table>
     </div>}
    {unallocated>0&&<form className="team-invite-form" onSubmit={e=>{
     e.preventDefault()
     if(returnQty<1||returnQty>unallocated||createReturn.isPending)return
     if(!window.confirm('确认保存供应商退货草稿？不自动出库或向供应商发送指令。'))return
     createReturn.mutate()
    }}>
     <label htmlFor="qc-return-qty">退货草稿件数</label>
     <input id="qc-return-qty" type="number" step={1} min={1} max={unallocated}
      value={returnQty} onChange={e=>{
       setReturnQty(Number(e.target.value));setReturnKey(crypto.randomUUID())}}/>
     <label htmlFor="qc-return-reference">供应商 RMA / 退货参考号（可选）</label>
     <input id="qc-return-reference" maxLength={120} value={returnReference}
      onChange={e=>{setReturnReference(e.target.value);
       setReturnKey(crypto.randomUUID())}}/>
     <button type="submit" className="primary-button" disabled={createReturn.isPending||
      !Number.isSafeInteger(returnQty)||returnQty<1||returnQty>unallocated}>
      保存供应商退货草稿</button>
    </form>}
   </div>}
  </>}
  <div className="panel-heading"><div><h3>质检异常待办</h3>
   <p>不合格待处置，或隔离 7 天仍未申报全部退货。处理状态留审计，
    条件消失后巡检自动清除。</p></div>
   <button className="primary-button" type="button" disabled={scan.isPending}
    onClick={()=>scan.mutate()}>{scan.isPending?'巡检中…':'巡检本地质检异常'}</button></div>
  <label htmlFor="qc-filter">待办状态</label>
  <select id="qc-filter" value={filter}
   onChange={e=>setFilter(e.target.value as 'ACTIVE'|'ALL')}>
   <option value="ACTIVE">待处理 / 已认领</option><option value="ALL">全部</option>
  </select>
  {findings.isPending?<p className="empty-state">读取质检异常…</p>:
   findings.isError?<p className="setup-error" role="alert">{String(findings.error)}</p>:
   !findings.data?.length?<p className="empty-state">当前筛选范围没有质检异常。</p>:
   <div className="table-scroll" role="region" tabIndex={0} aria-label="采购质检异常">
    <table><thead><tr><th scope="col">收货 ID</th><th scope="col">异常类型</th>
     <th scope="col">状态</th><th scope="col">操作</th></tr></thead><tbody>
     {findings.data.map(f=><tr key={f.id}>
      <td>{f.receiptId.slice(0,8)}</td><td>{findingLabels[f.ruleCode]??f.ruleCode}</td>
      <td>{stateLabels[f.state]??f.state}</td>
      <td><div className="sandbox-actions">
       <button type="button" className="refresh-button"
        onClick={()=>setInspectionId(f.inspectionId)}>查看隔离品</button>
       {f.state==='OPEN'&&<button type="button" className="text-button"
        disabled={!!editing} onClick={()=>changeFinding.mutate({id:f.id,
         state:'ACKNOWLEDGED'})}>认领</button>}
       {['OPEN','ACKNOWLEDGED'].includes(f.state)&&
        <button type="button" className="primary-button" disabled={!!editing}
         onClick={()=>changeFinding.mutate({id:f.id,state:'DONE'})}>标记已处理</button>}
       {['DONE','ACKNOWLEDGED'].includes(f.state)&&
        <button type="button" className="text-button" disabled={!!editing}
         onClick={()=>changeFinding.mutate({id:f.id,state:'OPEN'})}>重新打开</button>}
      </div></td>
     </tr>)}</tbody></table>
   </div>}
  {message&&<p className="panel-caption" role="status">{message}</p>}
  {failure&&<p className="setup-error" role="alert">{failure}</p>}
 </section>
}
