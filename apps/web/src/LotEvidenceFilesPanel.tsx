import {useState,type FormEvent} from 'react'
import WarehousePicker from './WarehousePicker'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import type {LocalWarehouse} from './lib/purchasing'
import {fetchDispositionProofs} from './lib/lot-disposition-proofs'
import {fetchLotDispositions} from './lib/lot-reconciliation'
import {fetchProofFileInfo,uploadProofFile,proofDownloadHref,
 fetchSupplierReturnTraces,reportSupplierReturnDispatch,
 reportSupplierReturnReceipt} from './lib/lot-evidence-files'

const noteOk=(text:string)=>text.trim().length>=5&&text.trim().length<=300
export default function LotEvidenceFilesPanel({tenantId,currentUserId,warehouses}:{
 tenantId:string;currentUserId:string;warehouses:LocalWarehouse[]
}){
 const client=useQueryClient(),root=['purchasing',tenantId]
 const [warehouse,setWarehouse]=useState('')
 const [proofId,setProofId]=useState('')
 const [file,setFile]=useState<File|null>(null)
 const [uploadKey,setUploadKey]=useState(()=>crypto.randomUUID())
 const [dispositionId,setDispositionId]=useState('')
 const [carrierReference,setCarrierReference]=useState('')
 const [dispatchReason,setDispatchReason]=useState('')
 const [dispatchKey,setDispatchKey]=useState(()=>crypto.randomUUID())
 const [traceId,setTraceId]=useState('')
 const [received,setReceived]=useState(0)
 const [receiptReference,setReceiptReference]=useState('')
 const [receiptReason,setReceiptReason]=useState('')
 const [message,setMessage]=useState(''),[failure,setFailure]=useState('')
 const proofs=useQuery({queryKey:[...root,'lot-proofs',warehouse],
  queryFn:()=>fetchDispositionProofs(warehouse),enabled:!!warehouse,retry:false})
 const stored=useQuery({queryKey:[...root,'proof-file',proofId],
  queryFn:()=>fetchProofFileInfo(proofId),enabled:!!proofId,retry:false})
 const dispositions=useQuery({queryKey:[...root,'lot-dispositions',warehouse],
  queryFn:()=>fetchLotDispositions(warehouse),enabled:!!warehouse,retry:false})
 const returns=useQuery({queryKey:[...root,'supplier-return-trace',warehouse],
  queryFn:()=>fetchSupplierReturnTraces(warehouse),enabled:!!warehouse,retry:false})
 const refresh=()=>void client.invalidateQueries({queryKey:root})
 const chosenProof=proofs.data?.find(p=>p.id===proofId)
 const awaitingDispatch=(dispositions.data??[]).filter(p=>
  p.status==='EXECUTED_REPORTED'&&p.method==='SUPPLIER_RETURN'&&
  !returns.data?.some(t=>t.dispositionId===p.id))
 const openTrace=returns.data?.find(t=>t.id===traceId)
 const upload=useMutation({mutationFn:()=>uploadProofFile(proofId,uploadKey,file!),
  onSuccess:s=>{setMessage('私有原件已保存，服务端 SHA-256：'+s.sha256+
   '；这并不是供应商或承运商验真。')
   setFailure('');setUploadKey(crypto.randomUUID());setFile(null);refresh()},
  onError:(error:Error)=>setFailure(error.message)})
 const dispatch=useMutation({mutationFn:()=>reportSupplierReturnDispatch({
  requestKey:dispatchKey,dispositionId,carrierReference:carrierReference.trim(),
  reason:dispatchReason.trim()}),
  onSuccess:()=>{
   setMessage('供应商退货发运已与原采购收货/供应商关联；仍是本地人工报告')
   setFailure('');setDispositionId('');setCarrierReference('');setDispatchReason('');
   setDispatchKey(crypto.randomUUID());refresh()},
  onError:(error:Error)=>setFailure(error.message)})
 const report=useMutation({mutationFn:()=>reportSupplierReturnReceipt(traceId,{
  receivedQuantity:received,receiptReference:receiptReference.trim(),
  reason:receiptReason.trim()}),
  onSuccess:result=>{
   setMessage(result.status==='RECEIPT_MISMATCH'?
    '已登记人工报告的供应商实收数量差异；库存和财务账未自动调整':
    '已登记另一名 Owner 收集的供应商实收报告；尚未通过外部供应商 API 核验')
   setFailure('');setTraceId('');setReceiptReference('');setReceiptReason('');refresh()},
  onError:(error:Error)=>setFailure(error.message)})
 function submitUpload(event:FormEvent){
  event.preventDefault()
  if(!proofId||!file||stored.data||chosenProof?.status!=='PENDING_REVIEW'||upload.isPending)return
  if(window.confirm('将选中的文件保存在本地 PostgreSQL 证据表？文件不上传至 Amazon 或供应商。'))
   upload.mutate()
 }
 return <section className="panel full-panel" aria-label="V52 私有文件凭据与供应商退货对账">
  <div className="panel-heading"><div>
   <h2>V52 · 凭据原件哈希校验、供应商退货来源与人工签收</h2>
   <p>附件只允许 2 MiB 内 PDF/PNG/JPEG；后端计算文件实际 SHA-256，
    如与 V51 已登记的摘要不符便拒绝保存。人工报告发运和供应商实收
    不是外部物流 API 回执，也不重复改变库存。</p>
  </div><button className="refresh-button" type="button" onClick={refresh}>刷新</button></div>
  <WarehousePicker tenantId={tenantId} id="v52-warehouse"
   label="本地仓库" value={warehouse} onChange={id=>{
    setWarehouse(id);setProofId('');setDispositionId('');setTraceId('')}}/>
  {!!warehouse&&<>
   <h3>1. 私有凭据原件</h3>
   <form className="team-invite-form" onSubmit={submitUpload}>
    <label htmlFor="v52-proof">原 V51 报废 / 退货凭据元数据</label>
    <select id="v52-proof" value={proofId} onChange={e=>{
     setProofId(e.target.value);setFile(null);setUploadKey(crypto.randomUUID())}}>
     <option value="">选择已有的凭据元数据记录</option>
     {(proofs.data??[]).map(p=><option key={p.id} value={p.id}>
      {p.evidenceReference} · {p.status}</option>)}
    </select>
    {!!chosenProof&&<>
     <p className="setup-hint">V51 录入的 SHA-256：
      {chosenProof.documentSha256??'未登记，服务端将从原件计算'}。
      {chosenProof.status!=='PENDING_REVIEW'?
       '该凭据已完成内部审核或被拒绝，不允许事后偷偷补充原件。':''}</p>
     {stored.data?<p className="setup-hint">
      已保存原件 {stored.data.mediaType}，大小 {stored.data.sizeBytes} 字节；
      已验证的实际 SHA-256：{stored.data.sha256}。
      <a href={proofDownloadHref(proofId)}>私有下载（需当前账号登录）</a>
     </p>:stored.isPending?<p className="empty-state">检查已有原件…</p>:null}
    </>}
    <label htmlFor="v52-file">选择本地 PDF、PNG 或 JPEG 原件</label>
    <input id="v52-file" type="file" accept=".pdf,.png,.jpg,.jpeg,application/pdf,image/png,image/jpeg"
     onChange={e=>{setFile(e.target.files?.[0]??null);
      setUploadKey(crypto.randomUUID())}}/>
    <button type="submit" className="primary-button"
     disabled={!file||!proofId||!!stored.data||stored.isLoading||
      chosenProof?.status!=='PENDING_REVIEW'||upload.isPending}>
     上传私有原件并校验 SHA-256
    </button>
   </form>
   <h3>2. 与原采购订单供应商关联的退货发运</h3>
   <p className="setup-hint">来源从原始采购实收、采购订单和供应商记录自动推导，
    不使用 V44 的拒收品退货数量重复计算已放行过期品。</p>
   <form className="team-invite-form" onSubmit={e=>{
    e.preventDefault()
    if(!dispositionId||carrierReference.trim().length<4||!noteOk(dispatchReason)
       ||dispatch.isPending)return
    if(window.confirm('确认实际已人工报告退货发运？本操作不再扣减已隔离库存。'))
     dispatch.mutate()
   }}>
    <label htmlFor="v52-disposition">已人工报告执行的供应商退货计划</label>
    <select id="v52-disposition" value={dispositionId} onChange={e=>{
     setDispositionId(e.target.value);setDispatchKey(crypto.randomUUID())}}>
     <option value="">选择供应商退货处置</option>
     {awaitingDispatch.map(p=><option key={p.id} value={p.id}>
      {p.id.slice(0,8)} · {p.quantity} 件</option>)}
    </select>
    <label htmlFor="v52-carrier-reference">运单 / 退货单参考编号</label>
    <input id="v52-carrier-reference" value={carrierReference} minLength={4} maxLength={120}
     onChange={e=>{setCarrierReference(e.target.value);setDispatchKey(crypto.randomUUID())}}/>
    <label htmlFor="v52-dispatch-note">实际发运记录说明</label>
    <input id="v52-dispatch-note" value={dispatchReason} minLength={5} maxLength={300}
     onChange={e=>{setDispatchReason(e.target.value);setDispatchKey(crypto.randomUUID())}}/>
    <button type="submit" className="primary-button"
     disabled={!dispositionId||carrierReference.trim().length<4||
      !noteOk(dispatchReason)||dispatch.isPending}>记录本地人工退货发运</button>
   </form>
   <h3>3. 供应商实收报告与数量差异</h3>
   {(returns.data??[]).length>0&&<div className="table-scroll" role="region" tabIndex={0}>
    <table><thead><tr><th scope="col">原始实收 / 供应商</th>
     <th scope="col">退货件数</th><th scope="col">人工状态</th></tr></thead><tbody>
     {(returns.data??[]).map(t=><tr key={t.id}>
      <td>{t.sourceReceiptId.slice(0,8)} / {t.supplierId.slice(0,8)}</td>
      <td>{t.quantity}</td><td>{t.status==='DISPATCH_REPORTED'?'已报告发运':
       t.status==='SUPPLIER_RECEIPT_REPORTED'?'供应商实收已人工报告':
       '人工报告供应商少收 '+(t.quantity-(t.receivedQuantity??0))+' 件'}</td>
     </tr>)}</tbody></table>
   </div>}
   <form className="team-invite-form" onSubmit={e=>{
    e.preventDefault()
    if(!openTrace||!Number.isSafeInteger(received)||received<0||
      received>openTrace.quantity||receiptReference.trim().length<4||
      !noteOk(receiptReason)||report.isPending)return
    if(window.confirm('仅登记另一位 Owner 收集的供应商实收信息，尚未由供应商 API 验证。'))
     report.mutate()
   }}>
    <label htmlFor="v52-trace">待核对退货发运（必须另一位 Owner）</label>
    <select id="v52-trace" value={traceId} onChange={e=>{
     setTraceId(e.target.value);const t=returns.data?.find(x=>x.id===e.target.value)
     setReceived(t?.quantity??0)}}>
     <option value="">选择待签收记录</option>
     {(returns.data??[]).filter(t=>t.status==='DISPATCH_REPORTED'&&
      t.dispatchedBy!==currentUserId).map(t=>
      <option key={t.id} value={t.id}>
       {t.carrierReference} · 应收 {t.quantity} 件</option>)}
    </select>
    <label htmlFor="v52-received">人工报告供应商实收数量</label>
    <input id="v52-received" type="number" min={0} max={openTrace?.quantity??0}
     value={received} onChange={e=>setReceived(Number(e.target.value))}/>
    <label htmlFor="v52-receipt-ref">供应商回执参考编号</label>
    <input id="v52-receipt-ref" value={receiptReference} minLength={4} maxLength={120}
     onChange={e=>setReceiptReference(e.target.value)}/>
    <label htmlFor="v52-receipt-note">人工回执核对说明</label>
    <input id="v52-receipt-note" value={receiptReason} minLength={5} maxLength={300}
     onChange={e=>setReceiptReason(e.target.value)}/>
    <button className="primary-button" type="submit"
     disabled={!openTrace||receiptReference.trim().length<4||
      !noteOk(receiptReason)||!Number.isSafeInteger(received)||
      received<0||received>openTrace.quantity||report.isPending}>
     记录人工供应商实收或差异（不改库存）
    </button>
   </form>
  </>}
  {message&&<p className="panel-caption" role="status">{message}</p>}
  {failure&&<p className="setup-error" role="alert">{failure}</p>}
 </section>
}
