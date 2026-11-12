import WarehousePicker from './WarehousePicker'
import {useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import type {LocalWarehouse} from './lib/purchasing'
import {fetchDispositionProofs} from './lib/lot-disposition-proofs'
import {fetchLotDispositions} from './lib/lot-reconciliation'
import {fetchProofFileInfo,uploadProofFile,proofDownloadHref,
 fetchProofGovernance,fetchProofCapacity,commandProofGovernance,
 fetchProofHistory,fetchProofCapacityAlert,changeProofCapacityThreshold,
 fetchSupplierReturnTraces,reportSupplierReturnDispatch,
 reportSupplierReturnReceipt,type LotProofGovernanceAction} from './lib/lot-evidence-files'
import CursorPageControls from './CursorPageControls'

const noteOk=(text:string)=>text.trim().length>=5&&text.trim().length<=300
export default function LotEvidenceFilesPanel({tenantId,currentUserId,warehouses}:{
 tenantId:string;currentUserId:string;warehouses:LocalWarehouse[]
}){
 const client=useQueryClient(),root=['purchasing',tenantId]
 const [warehouse,setWarehouse]=useState('')
 const [proofId,setProofId]=useState('')
 const [file,setFile]=useState<File|null>(null)
 const [uploadKey,setUploadKey]=useState(()=>crypto.randomUUID())
 const [governanceAction,setGovernanceAction]=useState<LotProofGovernanceAction>('PLACE_HOLD')
 const [governanceReason,setGovernanceReason]=useState('')
 const [retentionDate,setRetentionDate]=useState('')
 const [auditCursor,setAuditCursor]=useState<string|null>(null)
 const [auditPrevious,setAuditPrevious]=useState<(string|null)[]>([])
 const [warningMib,setWarningMib]=useState('')
 const [warningAction,setWarningAction]=useState<'SET'|'DISABLE'>('SET')
 const [warningReason,setWarningReason]=useState('')

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
 const governance=useQuery({queryKey:[...root,'proof-governance',proofId],
  queryFn:()=>fetchProofGovernance(proofId),
  enabled:!!proofId&&!!stored.data,retry:false})
 const storage=useQuery({queryKey:[...root,'proof-capacity'],
  queryFn:fetchProofCapacity,enabled:!!warehouse,retry:false})
 const history=useQuery({
  queryKey:[...root,'proof-history',proofId,auditCursor],
  queryFn:()=>fetchProofHistory(proofId,auditCursor),
  enabled:!!proofId&&!!stored.data,retry:false
 })
 const capacityAlert=useQuery({
  queryKey:[...root,'proof-capacity-alert'],
  queryFn:fetchProofCapacityAlert,enabled:!!warehouse,retry:false
 })
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
 const governanceChange=useMutation({mutationFn:()=>commandProofGovernance(proofId,{
  action:governanceAction,reason:governanceReason.trim(),
  ...(governanceAction==='PROPOSE_RETENTION'&&retentionDate?
   {retentionUntil:new Date(retentionDate).toISOString()}:{})
 }),onSuccess:()=>{
  setMessage('私有证据保留/保全审批记录已写入审计；未执行任何原件删除')
  setFailure('');setGovernanceReason('');refresh()
 },onError:(error:Error)=>setFailure(error.message)})
 const warningChange=useMutation({
  mutationFn:()=>changeProofCapacityThreshold({
   action:warningAction,
   ...(warningAction==='SET'?{warningBytes:Number(warningMib)*1048576}:{}),
   reason:warningReason.trim()
  }),
  onSuccess:()=>{
   setMessage('已审计保存本租户原件字节量告警设置；仅提供提示，不执行任何清理')
   setFailure('');setWarningReason('');refresh()
  },
  onError:(error:Error)=>setFailure(error.message)
 })
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
    label="本地仓库（全库）" value={warehouse}
    onChange={id=>{
   setWarehouse(id);setProofId('');setDispositionId('');setTraceId('')
   setAuditCursor(null);setAuditPrevious([])}}/>
  {!!warehouse&&<>
   <h3>1. 私有凭据原件</h3>
   <form className="team-invite-form" onSubmit={submitUpload}>
    <label htmlFor="v52-proof">原 V51 报废 / 退货凭据元数据</label>
    <select id="v52-proof" value={proofId} onChange={e=>{
     setProofId(e.target.value);setFile(null);setUploadKey(crypto.randomUUID())
     setAuditCursor(null);setAuditPrevious([])}}>
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
   <h3>原件容量、法律保全与保留审批（V138）</h3>
   <p className="setup-hint">目前只记录容量及经两位 Owner 分别申请、审查的保留与处置意见。
    未经正式保留政策和运维审批，不启用自动删除，也没有删除 API。</p>
   {storage.data&&<p className="setup-hint">
    当前租户原件 {storage.data.fileCount} 份，
    原始字节总计 {storage.data.originalBytes.toLocaleString()}，
    法律保全 {storage.data.heldFiles} 份，
    尚无获批保留期限 {storage.data.retentionUnsetFiles} 份，
    待审保留方案 {storage.data.pendingRetentionFiles} 份，
    已完成不删除处置审查 {storage.data.reviewedDisposalFiles} 份。
   </p>}
   {storage.isError&&<p className="setup-error" role="alert">
    无法取得租户原件容量统计：{String(storage.error)}</p>}
   {capacityAlert.data&&<div className="setup-hint" role="status">
    V140 · 原始证据字节量监测：
    {capacityAlert.data.status==='UNCONFIGURED'?
      '未设置提醒阈值（没有默认配额）':
     capacityAlert.data.status==='WARNING'?
      '已达到配置的预警线，请人工检查实际存储容量':
      '原始文件字节量低于配置的提醒阈值'}。
    {capacityAlert.data.warningBytes!==null&&
     <>阈值 {Math.round(capacityAlert.data.warningBytes/1048576)} MiB；
       当前 {capacityAlert.data.originalBytes.toLocaleString()} 字节。</>}
    提醒仅表示原始文件字节数，不代表数据库磁盘、TOAST、WAL 或备份占用；
    不自动上传、删除、清理或通知第三方。
   </div>}
   {capacityAlert.isError&&<p className="setup-error" role="alert">
    无法检查证据容量告警：{String(capacityAlert.error)}；不得视为容量正常。</p>}
   <form className="team-invite-form" onSubmit={e=>{
    e.preventDefault()
    const mib=Number(warningMib)
    if(warningChange.isPending||warningReason.trim().length<10||
       warningReason.trim().length>500||
       (warningAction==='SET'&&(!Number.isSafeInteger(mib)||mib<1||mib>1048576)))
      return
    if(window.confirm('确认变更当前租户的原件容量提醒？只记录审计，不设置存储配额。'))
     warningChange.mutate()
   }}>
    <label htmlFor="v140-warning-action">当前租户的可选容量提醒</label>
    <select id="v140-warning-action" value={warningAction}
      onChange={e=>setWarningAction(e.target.value as 'SET'|'DISABLE')}>
     <option value="SET">设置原始附件容量提醒</option>
     <option value="DISABLE">关闭本租户容量提醒（审计留痕）</option>
    </select>
    {warningAction==='SET'&&<>
     <label htmlFor="v140-warning-mib">提醒阈值（MiB，1 至 1,048,576）</label>
     <input id="v140-warning-mib" type="number" min={1} max={1048576}
      step={1} value={warningMib} onChange={e=>setWarningMib(e.target.value)}/>
    </>}
    <label htmlFor="v140-warning-reason">设置原因（10–500 字符）</label>
    <input id="v140-warning-reason" minLength={10} maxLength={500}
     value={warningReason} onChange={e=>setWarningReason(e.target.value)}/>
    <button type="submit" className="primary-button"
     disabled={warningChange.isPending||warningReason.trim().length<10||
      warningReason.trim().length>500||
      (warningAction==='SET'&&(!Number.isSafeInteger(Number(warningMib))||
      Number(warningMib)<1||Number(warningMib)>1048576))}>
     保存租户提醒阈值（不删除）
    </button>
   </form>
   {!!stored.data&&governance.data&&<form className="team-invite-form" onSubmit={e=>{
    e.preventDefault()
    if(!proofId||!noteOk(governanceReason)||governanceReason.trim().length<10
       ||governanceChange.isPending)return
    if(governanceAction==='PROPOSE_RETENTION'&&
       (!retentionDate||!Number.isFinite(new Date(retentionDate).getTime())))return
    if(window.confirm('确认登记原件治理状态？仅记录保全/保留/人工处置审批，绝不删除文件。'))
     governanceChange.mutate()
   }}>
    <p className="setup-hint">
     当前原件 {governance.data.fileId.slice(0,8)}；
     法律保全：{governance.data.legalHold?'已生效':'未设置'}；
     已批准保留到期：{governance.data.retentionUntil??'尚未批准（不可进入处置审查）'}；
     待审保留期限：{governance.data.retentionProposedUntil??'无'}；
     处置审查：{governance.data.disposalState==='REVIEWED_NO_DELETE'?
      '已审核，不代表允许删除':governance.data.disposalState==='REQUESTED'?
      '待另一位 Owner 审核':'未申请'}。
    </p>
    <label htmlFor="v138-action">保全、保留或处置审查操作</label>
    <select id="v138-action" value={governanceAction}
      onChange={e=>setGovernanceAction(e.target.value as LotProofGovernanceAction)}>
     <option value="PLACE_HOLD">立即设置法律保全（阻止处置）</option>
     <option value="REQUEST_HOLD_RELEASE">申请解除保全（需另一名 Owner）</option>
     <option value="APPROVE_HOLD_RELEASE">审核解除保全（不得自己审批）</option>
     <option value="PROPOSE_RETENTION">提议保留期限（不自动到期删除）</option>
     <option value="APPROVE_RETENTION">独立审核保留期限</option>
     <option value="REQUEST_DISPOSAL">保留期届满后申请处置审查</option>
     <option value="REVIEW_DISPOSAL">独立审核处置申请（仅记录，不删除）</option>
    </select>
    {governanceAction==='PROPOSE_RETENTION'&&<>
     <label htmlFor="v138-retain-until">建议保留到期时间</label>
     <input id="v138-retain-until" type="datetime-local"
      value={retentionDate} onChange={e=>setRetentionDate(e.target.value)}/>
    </>}
    <label htmlFor="v138-reason">审批原因（10–500 字符，勿填敏感内容）</label>
    <input id="v138-reason" value={governanceReason} minLength={10}
      maxLength={500} onChange={e=>setGovernanceReason(e.target.value)}/>
    <button className="primary-button" type="submit"
      disabled={governanceChange.isPending||governanceReason.trim().length<10||
       governanceReason.trim().length>500||
       (governanceAction==='PROPOSE_RETENTION'&&!retentionDate)}>
     提交治理记录（不删除原件）
    </button>
   </form>}
   {governance.isError&&!!stored.data&&<p className="setup-error" role="alert">
    无法验证原件治理状态：{String(governance.error)}</p>}
   {!!stored.data&&<section aria-label="V140 私有证据治理审计历史">
    <h3>V140 · 原件治理审计历史（按时间与 ID 稳定分页）</h3>
    <p className="setup-hint">当前仅显示此原件对应的治理事件，每页最多 50 条；
     操作人、理由和时间来自后端审计记录，当前页不代表完整历史。</p>
    {history.data?.items.length?<>
     <div className="table-scroll" role="region" tabIndex={0}
      aria-label="原件治理审计记录">
      <table><thead><tr><th scope="col">操作时间</th>
       <th scope="col">操作类型</th><th scope="col">Owner</th>
       <th scope="col">审计原因</th></tr></thead>
       <tbody>{history.data.items.map(entry=><tr key={entry.id}>
        <td>{entry.recordedAt}</td><td>{entry.action}</td>
        <td>{entry.actorUserId.slice(0,8)}</td><td>{entry.reason}</td>
       </tr>)}</tbody>
      </table>
     </div>
    </>:history.isPending?<p className="empty-state">查询本原件审计历史…</p>:
       <p className="empty-state">本原件尚无治理审计事件。</p>}
    {history.isError&&<p className="setup-error" role="alert">
     治理审计分页请求失败：{String(history.error)}</p>}
    <CursorPageControls label="私有原件审计历史"
     page={auditPrevious.length+1}
     hasMore={history.data?.hasMore??false}
     nextCursor={history.data?.nextCursor??null}
     busy={history.isFetching}
     onPrev={()=>{if(auditPrevious.length){
      setAuditCursor(auditPrevious[auditPrevious.length-1])
      setAuditPrevious(p=>p.slice(0,-1))
     }}}
     onNext={next=>{
      setAuditPrevious(p=>[...p,auditCursor])
      setAuditCursor(next)
     }}/>
   </section>}
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
