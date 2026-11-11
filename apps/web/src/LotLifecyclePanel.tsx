import {useWarehouseLabelMap} from './useWarehouseLabelMap'
import CursorPageControls from './CursorPageControls'
import WarehousePicker from './WarehousePicker'
import {useEffect,useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import type {LocalWarehouse} from './lib/purchasing'
import {fetchWarehouseStockItem} from './lib/purchasing'
import {fetchStockBins} from './lib/lot-locations'
import {fetchLotPositionsPage} from './lib/released-lots'
import {actLotPosition,fetchLotActionHistoryPage,fetchLotExpiryAlertsPage,fetchLotLineage,
 type LotActionRequest} from './lib/lot-lifecycle'

const labels:Record<LotActionRequest['actionType'],string>={
 MOVE:'同仓已放行货位移动（不改变可用总量）',
 TRANSFER:'跨仓实际调拨（两仓同步记账）',
 EXPIRED_QUARANTINE:'已过期商品隔离（减少可用库存）',
 COUNT_OBSERVATION:'库位实物盘点观察（暂不改账）'
}
export default function LotLifecyclePanel({tenantId,warehouses}:{
 tenantId:string;warehouses:LocalWarehouse[]
}){
 const root=['purchasing',tenantId]
 const client=useQueryClient()
 const [warehouseId,setWarehouseId]=useState('')
 const [expiryCursors,setExpiryCursors]=useState<(string|null)[]>([null])
 const expiryCursor=expiryCursors.at(-1)??null
 useEffect(()=>setExpiryCursors([null]),[tenantId,warehouseId])
 const [positionCursors,setPositionCursors]=useState<(string|null)[]>([null])
 const positionCursor=positionCursors.at(-1)??null
 useEffect(()=>setPositionCursors([null]),[tenantId,warehouseId])
 const [positionId,setPositionId]=useState('')
 const [type,setType]=useState<LotActionRequest['actionType']>('MOVE')
 const [destWarehouse,setDestWarehouse]=useState('')
 const [destBin,setDestBin]=useState('')
 const [quantity,setQuantity]=useState(1)
 const [observed,setObserved]=useState(0)
 const [reason,setReason]=useState('')
 const [requestKey,setRequestKey]=useState(()=>crypto.randomUUID())
 const [historyId,setHistoryId]=useState('')
 const [historyCursors,setHistoryCursors]=useState<(string|null)[]>([null])
 const historyCursor=historyCursors.at(-1)??null
 useEffect(()=>setHistoryCursors([null]),[tenantId,historyId])
 const [success,setSuccess]=useState(''),[error,setError]=useState('')
 const positions=useQuery({queryKey:[...root,'lot-positions-page',warehouseId,positionCursor],
  queryFn:()=>fetchLotPositionsPage(warehouseId,positionCursor),
  enabled:!!warehouseId,retry:false})
 const alerts=useQuery({queryKey:[...root,'expiry-alerts-page',warehouseId,expiryCursor],
  queryFn:()=>fetchLotExpiryAlertsPage(warehouseId,expiryCursor),
  enabled:!!warehouseId,retry:false})
 const chosen=positions.data?.items.find(p=>p.id===positionId)
 const targetWh=type==='TRANSFER'?destWarehouse:warehouseId
 const bins=useQuery({queryKey:[...root,'bins',targetWh],
  queryFn:()=>fetchStockBins(targetWh),enabled:!!targetWh,retry:false})
 const history=useQuery({queryKey:[...root,'lot-history-page',historyId,historyCursor],
  queryFn:()=>fetchLotActionHistoryPage(historyId,historyCursor),
  enabled:!!historyId,retry:false})
 const ancestry=useQuery({queryKey:[...root,'lot-lineage',historyId],
  queryFn:()=>fetchLotLineage(historyId),enabled:!!historyId,retry:false})
 const warehouseLabels=useWarehouseLabelMap(tenantId,
  ancestry.data?.map(a=>a.warehouseId)??[],warehouses).labels
 const stockItem=useQuery({
  queryKey:[...root,'stock-item-exact',warehouseId,chosen?.productSkuId],
  queryFn:()=>fetchWarehouseStockItem(warehouseId,chosen!.productSkuId),
  enabled:!!warehouseId&&!!chosen,retry:false
 })
 // An SKU absent from old first-500 rows is UNKNOWN, never zero.
 const usable=stockItem.data?.onHandQuantity
 const chosenBinAllowed=bins.data?.some(b=>b.id===destBin&&
  b.status==='ACTIVE'&&b.purpose==='STORAGE')??false
 const expiryExceeded=!!chosen?.expiryDate&&
  chosen.expiryDate<new Date().toISOString().slice(0,10)
 const action=useMutation({mutationFn:(payload:LotActionRequest)=>actLotPosition(positionId,payload),
  onSuccess:r=>{
   setSuccess(r.actionType==='COUNT_OBSERVATION'
    ?'盘点观察已记录，没有自动增加或减少库存'
    :r.actionType==='MOVE'
     ?'已记录实物同仓移位，仓库库存余额不变'
     :r.actionType==='TRANSFER'
      ?'真实跨仓调拨已同时过账，目标仓新增来源可追踪的批次库位'
      :'已将人工确认的过期商品从可用仓库台账扣除，保存隔离审计')
   setError('');setReason('');setDestBin('');setRequestKey(crypto.randomUUID());refresh()
  },onError:(e:Error)=>setError(e.message)})
 const canPost=!!chosen&&!positions.isFetching&&!stockItem.isFetching&&
  !stockItem.isError&&
  reason.trim().length>=5&&reason.trim().length<=300&&
  (type==='MOVE'?chosen.remainingQuantity>0&&chosenBinAllowed&&destBin!==chosen.binId:
   type==='TRANSFER'?!!destWarehouse&&destWarehouse!==warehouseId&&chosenBinAllowed&&
    !expiryExceeded&&Number.isSafeInteger(quantity)&&quantity>=1&&
    quantity<=Math.min(chosen.remainingQuantity,usable??-1):
   type==='EXPIRED_QUARANTINE'?expiryExceeded&&
    Number.isSafeInteger(quantity)&&quantity>=1&&
    quantity<=Math.min(chosen.remainingQuantity,usable??-1):
    Number.isSafeInteger(observed)&&observed>=0&&observed<=1000000)&&
  !action.isPending
 const refresh=()=>void client.invalidateQueries({queryKey:root})
 const change=()=>setRequestKey(crypto.randomUUID())

 function submit(e:FormEvent){
  e.preventDefault()
  if(!canPost||!chosen)return
  const explanation=type==='EXPIRED_QUARANTINE'
   ?'确认此批次已过期并执行本地库存隔离？可用库存与该库位余量均会减少，不能自动撤销。'
   :type==='TRANSFER'
    ?'确认已完成实物跨仓移动？来源仓和目标仓将同事务记账，不能自动撤销。'
    :type==='COUNT_OBSERVATION'
     ?'只记录实物盘点差异，不会自动修改库存。'
     :'确认真实货位已变更？不修改总库存。'
  if(!window.confirm(explanation))return
  action.mutate({requestKey,actionType:type,expectedBinId:chosen.binId,
   destinationBinId:['MOVE','TRANSFER'].includes(type)?destBin:null,
   quantity:['TRANSFER','EXPIRED_QUARANTINE'].includes(type)?quantity:0,
   expectedRemaining:chosen.remainingQuantity,
   expectedOnHand:['TRANSFER','EXPIRED_QUARANTINE'].includes(type)?usable??null:null,
   observedQuantity:type==='COUNT_OBSERVATION'?observed:null,
   reason:reason.trim()})
 }
 return <section className="panel full-panel" aria-label="V49 批次库位生命周期管理">
  <div className="panel-heading"><div>
   <h2>V49 · 批次移位、跨仓调拨、盘点与过期隔离</h2>
   <p>仅处理 V48 已独立放行并明确上架的本地合格品批次。移位不改总库存；
    跨仓调拨同步修改两个仓库的签名流水；过期隔离会减少可用库存；
    盘点观察只保存差异证据，不会擅自调整库存。</p>
  </div><button type="button" className="refresh-button" onClick={refresh}>刷新</button></div>
  <WarehousePicker tenantId={tenantId} id="v49-wh"
    label="来源本地仓库（全库）" value={warehouseId}
    onChange={id=>{
   setWarehouseId(id);setPositionId('');setDestWarehouse('');
   setDestBin('');setHistoryId('');change()}}/>
  {!!warehouseId&&<>
   <h3>近 30 天到期与已过期批次</h3>
   <p className="setup-hint">根据人工登记的到期日期提示；未登记日期的批次无法自动推断有效期。
    以下预警不会自动扣库存，过期隔离必须由 Owner 人工确认。</p>
   {alerts.isPending?<p className="empty-state">读取库位到期预警…</p>:
    alerts.isError?<p className="setup-error" role="alert">{String(alerts.error)}</p>:
    !alerts.data?.items.length?<p className="empty-state">本页暂无近 30 天到期库位记录。</p>:
    <div className="table-scroll" role="region" tabIndex={0}>
     <table><thead><tr><th scope="col">批次</th><th scope="col">库位</th>
      <th scope="col">余量</th><th scope="col">到期日</th>
      <th scope="col">状态</th></tr></thead><tbody>
      {(alerts.data?.items??[]).map(a=><tr key={a.positionId}>
       <td>{a.lotCode}</td><td>{a.binCode}</td><td>{a.remainingQuantity}</td>
       <td>{a.expiryDate}</td><td>{a.urgency==='EXPIRED'?'已过期 · 待人工隔离':'近 30 天到期'}</td>
      </tr>)}</tbody></table>
    </div>}
   {alerts.data&&<CursorPageControls label="V49 过期和近 30 天预警全库记录"
     page={expiryCursors.length} hasMore={alerts.data.hasMore}
     nextCursor={alerts.data.nextCursor} busy={alerts.isFetching}
     onPrev={()=>setExpiryCursors(v=>v.slice(0,-1))}
     onNext={cursor=>setExpiryCursors(v=>[...v,cursor])}/>}
   <h3>有来源的已上架批次</h3>
   <label htmlFor="v49-position">来源批次库位</label>
   <select id="v49-position" value={positionId} onChange={e=>{
    setPositionId(e.target.value);setDestBin('');change()}}>
    <option value="">选择有实际余量的批次库位</option>
    {(positions.data?.items??[]).filter(p=>p.remainingQuantity>0).map(p=>
     <option key={p.id} value={p.id}>
      {p.lotCode} · {p.binCode} · {p.remainingQuantity} 件</option>)}
   </select>
   {positions.isError&&<p className="setup-error" role="alert">{String(positions.error)}</p>}
   {positions.data&&<CursorPageControls label="V48 已上架批次库位全库位置选择"
     page={positionCursors.length} hasMore={positions.data.hasMore}
     nextCursor={positions.data.nextCursor} busy={positions.isFetching}
     onPrev={()=>{setPositionId('');setHistoryId('');
      setPositionCursors(v=>v.slice(0,-1));change()}}
     onNext={cursor=>{setPositionId('');setHistoryId('');
      setPositionCursors(v=>[...v,cursor]);change()}}/>}
   {!!chosen&&<>
    <p className="setup-hint">原始实收来源 {chosen.receiptId.slice(0,8)}；
     当前库位 {chosen.remainingQuantity} 件；SKU 仓库签名可用 {usable===undefined?'未知（未取得精确余额）':usable} 件；
     有效期 {chosen.expiryDate??'未登记'}。</p>
    <form className="team-invite-form" onSubmit={submit}>
     <label htmlFor="v49-action">实际操作类型</label>
     <select id="v49-action" value={type} onChange={e=>{
      setType(e.target.value as LotActionRequest['actionType']);setDestBin('');
      setDestWarehouse('');change()}}>
      {Object.entries(labels).map(([id,label])=>
       <option key={id} value={id}>{label}</option>)}
     </select>
     {type==='TRANSFER'&&<>
      <WarehousePicker tenantId={tenantId} id="v49-dst-wh"
    label="目标仓库（全库）" value={destWarehouse} excludeId={warehouseId}
    onChange={id=>{setDestWarehouse(id);setDestBin('');change()}}/>
     </>}
     {['MOVE','TRANSFER'].includes(type)&&<>
      <label htmlFor="v49-dest-bin">目标 STORAGE 库位</label>
      <select id="v49-dest-bin" value={destBin}
       onChange={e=>{setDestBin(e.target.value);change()}}>
       <option value="">选择当前目标仓库的 ACTIVE STORAGE 库位</option>
       {(bins.data??[]).filter(b=>b.status==='ACTIVE'&&b.purpose==='STORAGE').map(b=>
        <option key={b.id} value={b.id}>{b.code}</option>)}
      </select>
     </>}
     {['TRANSFER','EXPIRED_QUARANTINE'].includes(type)&&<>
      <label htmlFor="v49-qty">真实操作件数</label>
      <input id="v49-qty" type="number" min={1}
       max={Math.min(chosen.remainingQuantity,usable??0)} value={quantity}
       onChange={e=>{setQuantity(Number(e.target.value));change()}}/>
     </>}
     {type==='COUNT_OBSERVATION'&&<>
      <label htmlFor="v49-observed">实际清点件数</label>
      <input id="v49-observed" type="number" min={0} max={1000000}
       value={observed} onChange={e=>{setObserved(Number(e.target.value));change()}}/>
      <p className="setup-hint">系统快照 {chosen.remainingQuantity} 件；
       观察差异 {observed-chosen.remainingQuantity} 件。需后续独立审核才能作为正式库存调整依据。</p>
     </>}
     {type==='EXPIRED_QUARANTINE'&&!expiryExceeded&&
      <p className="setup-hint">此批次尚无已过期的人工有效期证据；后端会拒绝过期隔离操作。</p>}
     <label htmlFor="v49-reason">来源清点、出入库或隔离说明</label>
     <input id="v49-reason" minLength={5} maxLength={300} value={reason}
      onChange={e=>{setReason(e.target.value);change()}}
      placeholder="记录实际操作与证明材料，不得冒充 Amazon 操作" required/>
     <button type="submit" className="primary-button" disabled={!canPost}>
      {type==='COUNT_OBSERVATION'?'保存盘点观察证据':
       type==='EXPIRED_QUARANTINE'?'确认已过期品隔离并扣减本地可用':
       type==='TRANSFER'?'确认实物调拨并同步两仓账':'保存真实库位移位'}
     </button>
    </form>
    <button type="button" className="text-button" onClick={()=>
     setHistoryId(historyId===positionId?'':positionId)}>
     {historyId===positionId?'收起':'查看'}该批次来源与操作审计
    </button>
   </>}
   {!!historyId&&<>
    <h3>从原始真实收货到当前库位的来源链</h3>
    {ancestry.isPending?<p className="empty-state">读取原始批次链路…</p>:
     ancestry.isError?<p className="setup-error" role="alert">{String(ancestry.error)}</p>:
     <div className="table-scroll" role="region" tabIndex={0}>
      <table><thead><tr><th scope="col">来源阶段</th><th scope="col">真实实收</th>
       <th scope="col">仓库 / 库位</th><th scope="col">原始放置 / 当前余量</th>
      </tr></thead><tbody>
       {(ancestry.data??[]).map((a,i)=><tr key={a.positionId}>
        <td>{i===0?'最初放行来源':'后续跨仓链路'}</td>
        <td>{a.receiptId.slice(0,8)} · {a.lotCode}</td>
        <td>{warehouseLabels.get(a.warehouseId)?.code??a.warehouseId.slice(0,8)}
         {' / '+a.binCode}</td>
        <td>{a.placedQuantity} / {a.remainingQuantity}</td>
       </tr>)}
      </tbody></table>
     </div>}
    <h3>不可覆盖的批次操作审计</h3>
    {history.isPending?<p className="empty-state">读取操作历史…</p>:
     history.isError?<p className="setup-error" role="alert">{String(history.error)}</p>:
     !history.data?.items.length?<p className="empty-state">本页无 V49 操作记录。</p>:
     <div className="table-scroll" role="region" tabIndex={0}>
      <table><thead><tr><th scope="col">时间</th><th scope="col">类型</th>
       <th scope="col">件数/盘点</th><th scope="col">来源</th><th scope="col">说明</th></tr></thead><tbody>
       {(history.data?.items??[]).map(a=><tr key={a.id}>
        <td>{new Date(a.createdAt).toLocaleString('zh-CN')}</td>
        <td>{labels[a.actionType]}</td><td>{a.observedQuantity??a.quantity}</td>
        <td>{a.sourcePositionId.slice(0,8)}</td><td>{a.reason}</td>
       </tr>)}</tbody></table>
     </div>}
    {history.data&&<CursorPageControls label="批次来源与目标操作全库历史"
      page={historyCursors.length} hasMore={history.data.hasMore}
      nextCursor={history.data.nextCursor} busy={history.isFetching}
      onPrev={()=>setHistoryCursors(v=>v.slice(0,-1))}
      onNext={cursor=>setHistoryCursors(v=>[...v,cursor])}/>}
   </>}
  </>}
  {success&&<p role="status" className="panel-caption">{success}</p>}
  {error&&<p role="alert" className="setup-error">{error}</p>}
 </section>
}
