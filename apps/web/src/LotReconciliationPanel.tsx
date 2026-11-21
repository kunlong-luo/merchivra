import WarehousePicker from './WarehousePicker'
import SupplierReturnTraceSection from './SupplierReturnTraceSection'
import {useEffect,useState,type FormEvent} from 'react'
import CursorPageControls from './CursorPageControls'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import type {LocalWarehouse} from './lib/purchasing'
import {fetchLotPositionsPage} from './lib/released-lots'
import {fetchLotActionHistoryPage} from './lib/lot-lifecycle'
import {
 fetchLotCountCasesPage,submitLotCount,decideLotCount,
 fetchDestinationTransfersPage,acknowledgeDestinationTransfer,
 fetchLotDispositionsPage,fetchUnplannedQuarantinesPage,proposeLotDisposition,
 decideLotDisposition,reportLotDispositionCompletion
} from './lib/lot-reconciliation'

const countLabels:Record<string,string>={
 PENDING_REVIEW:'待独立审核',APPLIED:'盘亏已正式调整',
 CONFIRMED_NO_CHANGE:'复核无差异',EVIDENCE_REQUIRED:'盘盈待来源凭据（未加库存）',
 REJECTED:'已拒绝'}
const methodLabels={DISPOSAL:'报废处置计划',SUPPLIER_RETURN:'退回供应商计划'}
const goodReason=(s:string)=>s.trim().length>=5&&s.trim().length<=300

export default function LotReconciliationPanel({tenantId,currentUserId,warehouses}:{
 tenantId:string;currentUserId:string;warehouses:LocalWarehouse[]
}){
 const qc=useQueryClient()
 const root=['purchasing',tenantId]
 const [warehouse,setWarehouse]=useState('')
 const [countCursors,setCountCursors]=useState<(string|null)[]>([null])
 const [transferCursors,setTransferCursors]=useState<(string|null)[]>([null])
 const [dispositionCursors,setDispositionCursors]=useState<(string|null)[]>([null])
 const [quarantineCursors,setQuarantineCursors]=useState<(string|null)[]>([null])
 const countCursor=countCursors.at(-1)??null
 const transferCursor=transferCursors.at(-1)??null
 const dispositionCursor=dispositionCursors.at(-1)??null
 const quarantineCursor=quarantineCursors.at(-1)??null
 useEffect(()=>{
  setCountCursors([null]);setTransferCursors([null])
  setDispositionCursors([null]);setQuarantineCursors([null])
 },[tenantId])
 const [positionId,setPositionId]=useState('')
 const [positionCursors,setPositionCursors]=useState<(string|null)[]>([null])
 const positionCursor=positionCursors.at(-1)??null
 useEffect(()=>setPositionCursors([null]),[tenantId,warehouse])
 const [observationCursors,setObservationCursors]=useState<(string|null)[]>([null])
 const observationCursor=observationCursors.at(-1)??null
 useEffect(()=>setObservationCursors([null]),[tenantId,positionId])
 const [observationId,setObservationId]=useState('')
 const [countKey,setCountKey]=useState(()=>crypto.randomUUID())
 const [countReason,setCountReason]=useState('')
 const [reviewReason,setReviewReason]=useState('')
 const [ackAction,setAckAction]=useState('')
 const [ackQuantity,setAckQuantity]=useState(0)
 const [ackReference,setAckReference]=useState('')
 const [ackReason,setAckReason]=useState('')
 const [ackKey,setAckKey]=useState(()=>crypto.randomUUID())
 const [quarantineId,setQuarantineId]=useState('')
 const [method,setMethod]=useState<'DISPOSAL'|'SUPPLIER_RETURN'>('DISPOSAL')
 const [planReason,setPlanReason]=useState('')
 const [planKey,setPlanKey]=useState(()=>crypto.randomUUID())
 const [planReviewReason,setPlanReviewReason]=useState('')
 const [completionReason,setCompletionReason]=useState('')
 const [completionReference,setCompletionReference]=useState('')
 const [message,setMessage]=useState(''),[failure,setFailure]=useState('')
 const positions=useQuery({queryKey:[...root,'lot-positions-page',warehouse,positionCursor],
  queryFn:()=>fetchLotPositionsPage(warehouse,positionCursor),
  enabled:!!warehouse,retry:false})
 const actionHistory=useQuery({queryKey:[...root,'lot-history-page',positionId,observationCursor],
  queryFn:()=>fetchLotActionHistoryPage(positionId,observationCursor),
  enabled:!!positionId,retry:false})
 const countCases=useQuery({queryKey:[...root,'count-reviews-page',warehouse,countCursor],
  queryFn:()=>fetchLotCountCasesPage(warehouse,countCursor),enabled:!!warehouse,retry:false})
 const transfers=useQuery({queryKey:[...root,'transfer-receiving-page',warehouse,transferCursor],
  queryFn:()=>fetchDestinationTransfersPage(warehouse,transferCursor),
  enabled:!!warehouse,retry:false})
 const dispositions=useQuery({queryKey:[...root,'lot-dispositions-page',warehouse,dispositionCursor],
  queryFn:()=>fetchLotDispositionsPage(warehouse,dispositionCursor),
  enabled:!!warehouse,retry:false})
 const unplanned=useQuery({queryKey:[...root,'unplanned-quarantines-page',warehouse,quarantineCursor],
  queryFn:()=>fetchUnplannedQuarantinesPage(warehouse,quarantineCursor),
  enabled:!!warehouse,retry:false})
 const reload=()=>void qc.invalidateQueries({queryKey:root})
 function succeeded(value:string){
  setCountCursors([null]);setTransferCursors([null]);
  setDispositionCursors([null]);setQuarantineCursors([null])
  setMessage(value);setFailure('');reload()
 }
 const makeCount=useMutation({
  mutationFn:()=>submitLotCount({requestKey:countKey,observationActionId:observationId,
   reason:countReason.trim()}),
  onSuccess:()=>{
   succeeded('盘点观察已提交独立复核。尚未修改任何可用库存。')
   setCountKey(crypto.randomUUID());setCountReason('');setObservationId('')
  },onError:(e:Error)=>setFailure(e.message)
 })
 const countDecision=useMutation({
  mutationFn:({id,decision}:{id:string;decision:'APPROVE'|'REJECT'})=>
   decideLotCount(id,{decision,reason:reviewReason.trim()}),
  onSuccess:r=>{
   succeeded(countLabels[r.status]||r.status)
   setReviewReason('')
  },onError:(e:Error)=>setFailure(e.message)
 })
 const receive=useMutation({
  mutationFn:()=>acknowledgeDestinationTransfer(ackAction,{requestKey:ackKey,
   receivedQuantity:ackQuantity,reference:ackReference.trim(),reason:ackReason.trim()}),
  onSuccess:r=>{
   succeeded(r.status==='MISMATCH'
    ?'已记录目的仓数量异常，仓库原过账库存暂不自动更正'
    :'目的仓另一位 Owner 已确认全部收到；不会二次计入仓库库存')
   setAckKey(crypto.randomUUID());setAckAction('');setAckReference('');setAckReason('')
  },onError:(e:Error)=>setFailure(e.message)
 })
 const propose=useMutation({
  mutationFn:()=>proposeLotDisposition({requestKey:planKey,
   quarantineActionId:quarantineId,method,reason:planReason.trim()}),
  onSuccess:()=>{
   succeeded('已提交隔离品处置计划，等待非提出人审核；不再次扣库存')
   setQuarantineId('');setPlanKey(crypto.randomUUID());setPlanReason('')
  },onError:(e:Error)=>setFailure(e.message)
 })
 const decidePlan=useMutation({
  mutationFn:({id,decision}:{id:string;decision:'APPROVE'|'REJECT'})=>
   decideLotDisposition(id,{decision,reason:planReviewReason.trim()}),
  onSuccess:r=>{
   succeeded(r.status==='APPROVED'?'隔离处置已独立批准，仍待人工执行':'处置计划已拒绝')
   setPlanReviewReason('')
  },onError:(e:Error)=>setFailure(e.message)
 })
 const complete=useMutation({
  mutationFn:(id:string)=>reportLotDispositionCompletion(id,{
   reference:completionReference.trim(),reason:completionReason.trim()}),
  onSuccess:()=>{
   succeeded('已记录物理处置的人工报告与凭据编号，没有第二次扣减库存')
   setCompletionReference('');setCompletionReason('')
  },onError:(e:Error)=>setFailure(e.message)
 })
 const availableObservations=(actionHistory.data?.items??[]).filter(a=>
  a.actionType==='COUNT_OBSERVATION'&&!countCases.data?.items.some(c=>c.observationActionId===a.id))
 const selectedTransfer=transfers.data?.items.find(x=>x.actionId===ackAction)
 function submitAck(e:FormEvent){
  e.preventDefault()
  if(!selectedTransfer||!Number.isSafeInteger(ackQuantity)||ackQuantity<0||
    ackQuantity>selectedTransfer.expectedQuantity||ackReference.trim().length<4||
    !goodReason(ackReason)||receive.isPending)return
  if(window.confirm('确认目的仓独立清点结果？原调拨已过账，本次确认不再次增加库存。'))
   receive.mutate()
 }
 return <section className="panel full-panel" aria-label="V50 批次双人复核与库存对账">
  <div className="panel-heading"><div>
   <h2>V50 · 盘点双人审核、跨仓收货确认与隔离处置</h2>
   <p>正式盘亏调账必须经第二位 Owner 核准并通过库存快照校验；
    盘盈仅标记为需要补充来源凭据。跨仓收货复核与已隔离品处置
    只追加证据，绝不重复增加或扣减本地可用库存。</p>
  </div><button type="button" className="refresh-button" onClick={reload}>刷新全部</button></div>
  <WarehousePicker tenantId={tenantId} id="v50-wh"
    label="本地仓库（全库）" value={warehouse}
    onChange={id=>{
   setWarehouse(id);setPositionId('');setObservationId('');
   setAckAction('');setQuarantineId('')
   setCountCursors([null]);setTransferCursors([null])
   setDispositionCursors([null]);setQuarantineCursors([null])}}/>
  {!!warehouse&&<>
   <p className="setup-hint">本页只显示每类当前 50 条真实历史。所有盘点、调拨与处置去重和签名库存快照均以服务端全库核对为准。</p>
   <h3>1. 盘点观察 → 独立复核 → 仅盘亏安全调账</h3>
   <p className="setup-hint">选择在 V49 已记录的库位盘点观察。审核人不得是观察者或提交人。
    已盘盈数量需额外实际收货来源，不能仅凭观察擅自增加放行库存。</p>
   <form className="team-invite-form" onSubmit={e=>{
    e.preventDefault()
    if(!observationId||!goodReason(countReason)||makeCount.isPending)return
    if(window.confirm('提交盘点差异给另一 Owner 复核？本次仅创建审核单。'))makeCount.mutate()
   }}>
    <label htmlFor="v50-pos">盘点对应批次库位</label>
    <select id="v50-pos" value={positionId} onChange={e=>{
     setPositionId(e.target.value);setObservationId('');setCountKey(crypto.randomUUID())}}>
     <option value="">选择批次来源</option>
     {(positions.data?.items??[]).map(p=><option value={p.id} key={p.id}>
      {p.lotCode} · {p.binCode} · {p.remainingQuantity} 件</option>)}
    </select>
    {positions.isError&&<p className="setup-error" role="alert">{String(positions.error)}</p>}
    {positions.data&&<CursorPageControls label="V48 批次来源仓库全库选择"
      page={positionCursors.length} hasMore={positions.data.hasMore}
      nextCursor={positions.data.nextCursor} busy={positions.isFetching}
      onPrev={()=>{setPositionId('');setObservationId('');
       setPositionCursors(v=>v.slice(0,-1))}}
      onNext={cursor=>{setPositionId('');setObservationId('');
       setPositionCursors(v=>[...v,cursor])}}/>}
    <label htmlFor="v50-obs">V49 盘点观察（当前历史页；是否已提交由服务器复核）</label>
    <select id="v50-obs" value={observationId} onChange={e=>{
     setObservationId(e.target.value);setCountKey(crypto.randomUUID())}}>
     <option value="">选择实物清点记录</option>
     {availableObservations.map(a=><option key={a.id} value={a.id}>
      账面 {a.expectedRemaining} → 实点 {a.observedQuantity}</option>)}
    </select>
    {actionHistory.isError&&<p className="setup-error" role="alert">
      {String(actionHistory.error)}</p>}
    {actionHistory.data&&<CursorPageControls label="V49 盘点观察完整操作历史"
      page={observationCursors.length} hasMore={actionHistory.data.hasMore}
      nextCursor={actionHistory.data.nextCursor} busy={actionHistory.isFetching}
      onPrev={()=>{setObservationId('');setObservationCursors(v=>v.slice(0,-1))}}
      onNext={cursor=>{setObservationId('');setObservationCursors(v=>[...v,cursor])}}/>}
    <label htmlFor="v50-count-reason">盘点差异复核说明</label>
    <input id="v50-count-reason" minLength={5} maxLength={300}
     value={countReason} onChange={e=>{setCountReason(e.target.value);
      setCountKey(crypto.randomUUID())}}/>
    <button className="primary-button" type="submit"
     disabled={!observationId||!goodReason(countReason)||makeCount.isPending}>
     提交待审核的盘点差异
    </button>
   </form>
   {countCases.isError?<p className="setup-error" role="alert">{String(countCases.error)}</p>:
    (countCases.data?.items??[]).length>0&&<div className="table-scroll" role="region" tabIndex={0}>
     <table><thead><tr><th scope="col">账面 / 实点</th><th scope="col">状态</th>
      <th scope="col">审核</th></tr></thead><tbody>
      {(countCases.data?.items??[]).map(c=><tr key={c.id}>
       <td>{c.expectedPositionQuantity} / {c.observedQuantity}</td>
       <td>{countLabels[c.status]||c.status}</td>
       <td>{c.status==='PENDING_REVIEW'&&c.submittedBy!==currentUserId?
        <div className="sandbox-actions">
         <button type="button" className="primary-button"
          disabled={!goodReason(reviewReason)||countDecision.isPending}
          onClick={()=>{if(window.confirm('独立审核该盘点：仅盘亏才可能实际减库存；盘盈须更多来源证据。'))
           countDecision.mutate({id:c.id,decision:'APPROVE'})}}>独立复核</button>
         <button type="button" className="refresh-button"
          disabled={!goodReason(reviewReason)||countDecision.isPending}
          onClick={()=>countDecision.mutate({id:c.id,decision:'REJECT'})}>拒绝</button>
        </div>:c.operationId?'已签名调整 '+c.operationId.slice(0,8):
         '不重复生成库存流水'}</td>
      </tr>)}
      </tbody></table>
    </div>}
   {countCases.data&&<CursorPageControls label="盘点复核完整历史"
    page={countCursors.length} hasMore={countCases.data.hasMore}
    nextCursor={countCases.data.nextCursor} busy={countCases.isFetching}
    onPrev={()=>setCountCursors(v=>v.slice(0,-1))}
    onNext={cursor=>setCountCursors(v=>[...v,cursor])}/>}
   {!!countCases.data?.items.some(c=>c.status==='PENDING_REVIEW'&&
     c.submittedBy!==currentUserId)&&<>
    <label htmlFor="v50-review-reason">盘点审核意见（适用于下方选择的记录）</label>
    <input id="v50-review-reason" value={reviewReason} minLength={5} maxLength={300}
     onChange={e=>setReviewReason(e.target.value)}/>
   </>}

   <h3>2. 跨仓调拨目的仓人工收货确认</h3>
   <p className="setup-hint">V49 调拨在提交时已完成双仓本地台账过账。
    目的仓另一位 Owner 需要核对实际到货件数；不足时记录异常，不自动修改既有库存。</p>
   {(transfers.data?.items??[]).length>0&&<div className="table-scroll" role="region" tabIndex={0}>
    <table><thead><tr><th scope="col">目标批次来源</th>
     <th scope="col">已过账数量</th><th scope="col">目的仓状态</th></tr></thead><tbody>
     {(transfers.data?.items??[]).map(t=><tr key={t.actionId}>
      <td>{t.destinationPositionId.slice(0,8)}</td><td>{t.expectedQuantity}</td>
      <td>{t.status==='POSTED_AWAITING_DESTINATION'?'待目的仓独立核对':
       t.status==='CONFIRMED'?'目的仓全部确认':'目的仓数量差异：实点 '+t.receivedQuantity}</td>
     </tr>)}
    </tbody></table>
   </div>}
   {transfers.data&&<CursorPageControls label="目的仓调拨全库收货历史"
    page={transferCursors.length} hasMore={transfers.data.hasMore}
    nextCursor={transfers.data.nextCursor} busy={transfers.isFetching}
    onPrev={()=>{setTransferCursors(v=>v.slice(0,-1));setAckAction('')}}
    onNext={cursor=>{setTransferCursors(v=>[...v,cursor]);setAckAction('')}}/>}
   <form className="team-invite-form" onSubmit={submitAck}>
    <label htmlFor="v50-transfer">待确认的跨仓调拨</label>
    <select id="v50-transfer" value={ackAction}
     onChange={e=>{const id=e.target.value;setAckAction(id);
      const selected=transfers.data?.items.find(t=>t.actionId===id);
      setAckQuantity(selected?.expectedQuantity??0);setAckKey(crypto.randomUUID())}}>
     <option value="">选择待收货的目标仓库批次</option>
     {(transfers.data?.items??[]).filter(t=>t.status==='POSTED_AWAITING_DESTINATION'&&
       t.transferredBy!==currentUserId).map(t=>
       <option key={t.actionId} value={t.actionId}>
        {t.actionId.slice(0,8)} · 应收 {t.expectedQuantity} 件</option>)}
    </select>
    <label htmlFor="v50-received">目的仓实际核对件数</label>
    <input id="v50-received" type="number" min={0}
     max={selectedTransfer?.expectedQuantity??0} value={ackQuantity}
     onChange={e=>{setAckQuantity(Number(e.target.value));setAckKey(crypto.randomUUID())}}/>
    <label htmlFor="v50-receiving-ref">签收 / 盘点参考编号</label>
    <input id="v50-receiving-ref" value={ackReference} minLength={4} maxLength={120}
     onChange={e=>{setAckReference(e.target.value);setAckKey(crypto.randomUUID())}}/>
    <label htmlFor="v50-ack-note">核对说明</label>
    <input id="v50-ack-note" value={ackReason} minLength={5} maxLength={300}
     onChange={e=>{setAckReason(e.target.value);setAckKey(crypto.randomUUID())}}/>
    <button type="submit" className="primary-button"
     disabled={!selectedTransfer||ackReference.trim().length<4||
      !goodReason(ackReason)||!Number.isSafeInteger(ackQuantity)||
      ackQuantity<0||ackQuantity>selectedTransfer.expectedQuantity||receive.isPending}>
     确认目的仓实收（不二次入库）
    </button>
   </form>

   <h3>3. 已隔离过期批次的处置审批与执行报告</h3>
   <p className="setup-hint">V49 已把过期品从可用库存扣除。
    V50 的报废或供应商退货审批不能重复扣库存；执行状态为责任人报告，
    并不等于承运商已核验或供应商已接收。</p>
   <form className="team-invite-form" onSubmit={e=>{
    e.preventDefault();if(!quarantineId||!goodReason(planReason)||propose.isPending)return
    if(window.confirm('提交已隔离批次的处置计划给另一 Owner 审核？'))propose.mutate()
   }}>
    <label htmlFor="v50-quarantine">未规划的过期隔离凭据</label>
    <select id="v50-quarantine" value={quarantineId} onChange={e=>{
     setQuarantineId(e.target.value);setPlanKey(crypto.randomUUID())}}>
     <option value="">选择已扣除本地可用库存的过期隔离流水来源</option>
     {(unplanned.data?.items??[]).map(id=>
      <option key={id} value={id}>隔离行动 {id.slice(0,8)}</option>)}
    </select>
    {unplanned.data&&<CursorPageControls label="未规划隔离来源全库"
     page={quarantineCursors.length} hasMore={unplanned.data.hasMore}
     nextCursor={unplanned.data.nextCursor} busy={unplanned.isFetching}
     onPrev={()=>{setQuarantineCursors(v=>v.slice(0,-1));setQuarantineId('')}}
     onNext={cursor=>{setQuarantineCursors(v=>[...v,cursor]);setQuarantineId('')}}/>}
    <label htmlFor="v50-method">处置方式</label>
    <select id="v50-method" value={method} onChange={e=>{
     setMethod(e.target.value as 'DISPOSAL'|'SUPPLIER_RETURN');
     setPlanKey(crypto.randomUUID())}}>
     {Object.entries(methodLabels).map(([id,label])=>
      <option key={id} value={id}>{label}</option>)}
    </select>
    <label htmlFor="v50-plan-reason">处置计划依据</label>
    <input id="v50-plan-reason" value={planReason} minLength={5} maxLength={300}
     onChange={e=>{setPlanReason(e.target.value);setPlanKey(crypto.randomUUID())}}/>
    <button type="submit" className="primary-button"
     disabled={!quarantineId||!goodReason(planReason)||propose.isPending}>
     提交待审批处置计划</button>
   </form>
   <label htmlFor="v50-plan-review">独立处置审核意见</label>
   <input id="v50-plan-review" minLength={5} maxLength={300}
    value={planReviewReason} onChange={e=>setPlanReviewReason(e.target.value)}/>
   <label htmlFor="v50-completion-ref">物理处置参考编号</label>
   <input id="v50-completion-ref" minLength={4} maxLength={120}
    value={completionReference} onChange={e=>setCompletionReference(e.target.value)}/>
   <label htmlFor="v50-completion-reason">物理处置执行报告说明</label>
   <input id="v50-completion-reason" minLength={5} maxLength={300}
    value={completionReason} onChange={e=>setCompletionReason(e.target.value)}/>
   {(dispositions.data?.items??[]).length>0&&<div className="table-scroll" role="region" tabIndex={0}>
    <table><thead><tr><th scope="col">隔离来源与件数</th><th scope="col">计划</th>
     <th scope="col">状态 / 独立处理</th></tr></thead><tbody>
     {(dispositions.data?.items??[]).map(d=><tr key={d.id}>
      <td>{d.quarantineActionId.slice(0,8)} · {d.quantity} 件</td>
      <td>{methodLabels[d.method]}</td>
      <td>{d.status==='PENDING_APPROVAL'&&d.proposedBy!==currentUserId?
       <div className="sandbox-actions">
        <button type="button" className="primary-button"
         disabled={!goodReason(planReviewReason)||decidePlan.isPending}
         onClick={()=>{if(window.confirm('独立批准该过期隔离处置计划？不再次扣减库存。'))
          decidePlan.mutate({id:d.id,decision:'APPROVE'})}}>批准计划</button>
        <button type="button" className="refresh-button"
         disabled={!goodReason(planReviewReason)||decidePlan.isPending}
         onClick={()=>decidePlan.mutate({id:d.id,decision:'REJECT'})}>拒绝</button>
       </div>:
       d.status==='APPROVED'&&d.reviewedBy!==currentUserId?
        <button className="primary-button" type="button"
         disabled={completionReference.trim().length<4||
          !goodReason(completionReason)||complete.isPending}
         onClick={()=>{if(window.confirm('仅报告实际处置及证据编号，不修改已隔离库存。'))
          complete.mutate(d.id)}}>报告已执行</button>:
        d.status==='EXECUTED_REPORTED'?
         '已报告执行 · '+(d.completionReference??'') :
         d.status==='PENDING_APPROVAL'?'待另一位 Owner 审批':
          d.status==='APPROVED'?'待其他 Owner 报告执行':'已拒绝'}</td>
     </tr>)}
    </tbody></table>
   </div>}
   {dispositions.data&&<CursorPageControls label="已隔离批次处置全历史"
    page={dispositionCursors.length} hasMore={dispositions.data.hasMore}
    nextCursor={dispositions.data.nextCursor} busy={dispositions.isFetching}
    onPrev={()=>setDispositionCursors(v=>v.slice(0,-1))}
    onNext={cursor=>setDispositionCursors(v=>[...v,cursor])}/>}
   <SupplierReturnTraceSection key={warehouse} tenantId={tenantId}
    warehouseId={warehouse} currentUserId={currentUserId}
    dispositions={dispositions.data?.items??[]}/>
  </>}
  {message&&<p className="panel-caption" role="status">{message}</p>}
  {failure&&<p className="setup-error" role="alert">{failure}</p>}
 </section>
}
