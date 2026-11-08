import WarehousePicker from './WarehousePicker'
import {useEffect,useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import type {LocalWarehouse} from './lib/purchasing'
import {fetchReceiptBatchesPage} from './lib/receipt-batches'
import CursorPageControls from './CursorPageControls'
import {fetchStockBinsPage,createStockBin,fetchReceiptLotPiecesPage,
 splitReceiptLot,moveReceiptLot,fetchReceiptSupplyPressurePage,
 type SplitReceiptLot,type MoveReceiptLot} from './lib/lot-locations'

export default function ReceiptLotLocationPanel({tenantId,orderId,warehouses}:{
 tenantId:string;orderId:string;warehouses:LocalWarehouse[]
}){
 const client=useQueryClient()
 const [warehouseId,setWarehouseId]=useState('')
 const [binCode,setBinCode]=useState('')
 const [binPurpose,setBinPurpose]=useState<'QC_HOLD'|'STORAGE'>('QC_HOLD')
 const [receiptId,setReceiptId]=useState('')
 const [pieceId,setPieceId]=useState('')
 const [splitCode,setSplitCode]=useState('')
 const [splitQuantity,setSplitQuantity]=useState(1)
 const [targetBin,setTargetBin]=useState('')
 const [action,setAction]=useState<'SPLIT'|'MOVE'>('SPLIT')
 const [reason,setReason]=useState('')
 const [key,setKey]=useState(()=>crypto.randomUUID())
 const [message,setMessage]=useState(''),[failure,setFailure]=useState('')
 const root=['purchasing',tenantId]
 const [batchCursors,setBatchCursors]=useState<(string|null)[]>([null])
 const batchCursor=batchCursors.at(-1)??null
 useEffect(()=>{
  setBatchCursors([null]);setReceiptId('');setPieceId('');setPieceCursors([null])
 },[orderId])
 const [binCursors,setBinCursors]=useState<(string|null)[]>([null])
 const [pieceCursors,setPieceCursors]=useState<(string|null)[]>([null])
 const binCursor=binCursors.at(-1)??null
 const pieceCursor=pieceCursors.at(-1)??null
 const [pressureCursors,setPressureCursors]=useState<(string|null)[]>([null])
 const pressureCursor=pressureCursors.at(-1)??null
 const batches=useQuery({queryKey:[...root,'receipt-batches-page',orderId,batchCursor],
  queryFn:()=>fetchReceiptBatchesPage(orderId,batchCursor),enabled:!!orderId,retry:false})
 const bins=useQuery({queryKey:[...root,'bins-page',warehouseId,binCursor],
  queryFn:()=>fetchStockBinsPage(warehouseId,binCursor),enabled:!!warehouseId,retry:false})
 const pieces=useQuery({queryKey:[...root,'pieces-page',receiptId,pieceCursor],
  queryFn:()=>fetchReceiptLotPiecesPage(receiptId,pieceCursor),enabled:!!receiptId,retry:false})
 const pressure=useQuery({queryKey:[...root,'pressure-page',warehouseId,pressureCursor],
  queryFn:()=>fetchReceiptSupplyPressurePage(warehouseId,pressureCursor),enabled:!!warehouseId,retry:false})
 const refresh=()=>void client.invalidateQueries({queryKey:root})
 const changed=()=>setKey(crypto.randomUUID())
 const created=useMutation({mutationFn:()=>createStockBin(warehouseId,binCode,binPurpose),
  onSuccess:()=>{setMessage('本地库位已建立；仅创建货位元数据，未生成库存');
   setFailure('');setBinCode('');setBinCursors([null]);refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 const split=useMutation({mutationFn:(body:SplitReceiptLot)=>splitReceiptLot(receiptId,body),
  onSuccess:()=>{setMessage('待检批次按数量拆分完成，库内总实收数不变');
   setFailure('');setSplitCode('');setPieceCursors([null]);setPieceId('');changed();refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 const moved=useMutation({mutationFn:(body:MoveReceiptLot)=>moveReceiptLot(receiptId,body),
  onSuccess:()=>{setMessage('已记录待检分段位置变化；可用库存未发生任何变动');
   setFailure('');setPieceCursors([null]);setPieceId('');changed();refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 const currentBatch=batches.data?.items.find(b=>b.receiptId===receiptId)
 const source=pieces.data?.items.find(p=>p.id===pieceId)
 const activeQcBins=bins.data?.items.filter(b=>b.purpose==='QC_HOLD'&&b.status==='ACTIVE')??[]
 const target=targetBin||source?.binId||''
 const canSubmit=!!currentBatch&&currentBatch.status==='PENDING_QC'&&!!source&&
  reason.trim().length>=5&&reason.trim().length<=300&&
  (action==='SPLIT'?Number.isSafeInteger(splitQuantity)&&splitQuantity>=1&&
   splitQuantity<source.quantity&&
   /^[A-Za-z0-9][A-Za-z0-9_.-]{1,79}$/.test(splitCode)&&
   activeQcBins.some(b=>b.id===target):
   activeQcBins.some(b=>b.id===target)&&target!==source.binId)&&
  !split.isPending&&!moved.isPending
 function submit(e:FormEvent){
  e.preventDefault()
  if(!canSubmit||!source)return
  if(!window.confirm('确认本地待检分段操作？仅记录收货批次的区位分配，不改采购收货和可用库存。'))return
  if(action==='SPLIT')split.mutate({requestKey:key,pieceId:source.id,
   pieceCode:splitCode,quantity:splitQuantity,
   destinationBinId:target,reason:reason.trim()})
  else moved.mutate({requestKey:key,pieceId:source.id,
   expectedBinId:source.binId,destinationBinId:target,reason:reason.trim()})
 }
 return <section className="panel full-panel" aria-label="V47 待检批次拆分和仓库库位">
  <div className="panel-heading"><div>
   <h2>V47 待检批次拆分与库位</h2>
   <p>所有库位属于本地 ERP。待检批次按实收数量拆分和定位，不会生成库存流水；
    质检仍针对整笔收货，拆分后的分段不能分别冒充已质检或已放行库存。</p>
  </div><button type="button" className="refresh-button" onClick={refresh}>刷新</button></div>
  <WarehousePicker tenantId={tenantId} id="lot-warehouse"
    label="本地仓库（全库）" value={warehouseId}
    onChange={id=>{setWarehouseId(id);setReceiptId('');setPieceId('');
    setTargetBin('');setBinCursors([null]);setPieceCursors([null]);setPressureCursors([null]);changed()}}/>
  {!!warehouseId&&<>
   <h3>库位管理</h3>
   <p className="setup-hint">QC-HOLD 为虚拟接收待检区；可新增 QC_HOLD 库位分配待检收货。
    STORAGE 库位在此阶段仅作为库位目录，不能将未检分段移入可售区。</p>
   {bins.isPending?<p className="empty-state">读取库位…</p>:
    bins.isError?<p className="setup-error" role="alert">{String(bins.error)}</p>:
    <p className="setup-hint">{(bins.data?.items??[]).map(b=>
     b.code+'（'+(b.purpose==='QC_HOLD'?'待检':'存放')+'）').join('、')||'暂无库位'}</p>}
   {!!bins.data&&<CursorPageControls label="库位" page={binCursors.length}
    hasMore={bins.data.hasMore} nextCursor={bins.data.nextCursor} busy={bins.isFetching}
    onPrev={()=>{setBinCursors(v=>v.length>1?v.slice(0,-1):v);setTargetBin('')}}
    onNext={v=>{setBinCursors(h=>[...h,v]);setTargetBin('')}}/>}
   <form className="team-invite-form" onSubmit={e=>{
    e.preventDefault()
    if(!/^[A-Z0-9][A-Z0-9_-]{1,63}$/.test(binCode)||created.isPending)return
    created.mutate()
   }}>
    <label htmlFor="lot-bin-code">新库位编号</label>
    <input id="lot-bin-code" value={binCode} maxLength={64}
     pattern="[A-Z0-9][A-Z0-9_-]{1,63}" required
     onChange={e=>setBinCode(e.target.value.toUpperCase())}
     placeholder="QC-RACK-A"/>
    <label htmlFor="lot-bin-purpose">区位用途</label>
    <select id="lot-bin-purpose" value={binPurpose}
     onChange={e=>setBinPurpose(e.target.value as 'QC_HOLD'|'STORAGE')}>
     <option value="QC_HOLD">待检 / 隔离分段</option>
     <option value="STORAGE">普通存放库位目录</option>
    </select>
    <button type="submit" className="primary-button"
     disabled={created.isPending||binCode==='QC-HOLD'}>创建本地库位</button>
   </form>
   <h3>待检与补货压力（仅提醒，不自动生成采购单）</h3>
   <p className="setup-hint">以下为按 SKU 分页遍历的收货压力视图，不是全仓库存总账。可用库存用于 V42 补货决策，待检、已检待放行及供应商申报在途均单独列示，
    不当作确定可售库存；ASN 在途是申报余量，不是承运商确认。</p>
   {pressure.isPending?<p className="empty-state">读取收货待检快照…</p>:
    pressure.isError?<p className="setup-error" role="alert">{String(pressure.error)}</p>:
    !pressure.data?.items.length?<p className="empty-state">该仓库暂无 V46 及以后批次记录。</p>:
    <div className="table-scroll" role="region" tabIndex={0}>
     <table><thead><tr><th scope="col">SKU</th><th scope="col">已可用</th>
      <th scope="col">待检实收</th><th scope="col">已检待放行</th>
      <th scope="col">供应商申报在途</th></tr></thead><tbody>
      {pressure.data.items.map(p=><tr key={p.productSkuId}>
       <td>{p.internalSku}</td><td>{p.usableUnits}</td>
       <td>{p.pendingQcUnits}</td><td>{p.acceptedAwaitingReleaseUnits}</td>
       <td>{p.declaredAsnInTransitUnits}</td>
      </tr>)}</tbody></table>
    </div>}
   {!!pressure.data&&<CursorPageControls label="收货 SKU 压力" page={pressureCursors.length}
    hasMore={pressure.data.hasMore} nextCursor={pressure.data.nextCursor}
    busy={pressure.isFetching}
    onPrev={()=>setPressureCursors(v=>v.length>1?v.slice(0,-1):v)}
    onNext={v=>setPressureCursors(h=>[...h,v])}/>}
  </>}
  {!!warehouseId&&!!orderId&&<>
   <h3>采购单待检批次分段</h3>
   <label htmlFor="lot-receipt">真实收货批次</label>
   <select id="lot-receipt" value={receiptId}
    onChange={e=>{setReceiptId(e.target.value);setPieceId('');setTargetBin('');
     setPieceCursors([null]);changed()}}>
    <option value="">按订单分页选择未完成质检的真实收货批次</option>
    {(batches.data?.items??[]).filter(b=>b.warehouseId===warehouseId&&
      b.status==='PENDING_QC').map(b=><option key={b.receiptId} value={b.receiptId}>
      {b.lotCode} · {b.receivedQuantity} 件</option>)}
   </select>
   {!!batches.data&&<CursorPageControls label="收货批次" page={batchCursors.length}
    hasMore={batches.data.hasMore} nextCursor={batches.data.nextCursor} busy={batches.isFetching}
    onPrev={()=>{setBatchCursors(v=>v.length>1?v.slice(0,-1):v);setReceiptId('');setPieceId('');setPieceCursors([null])}}
    onNext={v=>{setBatchCursors(h=>[...h,v]);setReceiptId('');setPieceId('');setPieceCursors([null])}}/>}
   {!!receiptId&&<>
    {pieces.isPending?<p className="empty-state">读取分段…</p>:
     pieces.isError?<p className="setup-error" role="alert">{String(pieces.error)}</p>:
     <div className="table-scroll" role="region" tabIndex={0}>
      <table><thead><tr><th scope="col">分段编号</th><th scope="col">件数</th>
       <th scope="col">待检库位</th></tr></thead><tbody>
       {(pieces.data?.items??[]).map(p=><tr key={p.id}>
        <td>{p.pieceCode}</td><td>{p.quantity}</td>
        <td>{bins.data?.items.find(b=>b.id===p.binId)?.code??p.binId}</td>
       </tr>)}</tbody></table>
     </div>}
    {!!pieces.data&&<CursorPageControls label="收货分段" page={pieceCursors.length}
     hasMore={pieces.data.hasMore} nextCursor={pieces.data.nextCursor} busy={pieces.isFetching}
     onPrev={()=>{setPieceCursors(v=>v.length>1?v.slice(0,-1):v);setPieceId('');setTargetBin('')}}
     onNext={v=>{setPieceCursors(h=>[...h,v]);setPieceId('');setTargetBin('')}}/>}
    <p className="setup-hint">数据库全批次分段件数合计：{pieces.data?.totalQuantity??'—'}；
     原始收货数量：{currentBatch?.receivedQuantity??'—'}。
     翻页不会改变全批次合计，当前分段与库位选择仅对本页有效。</p>
    <form className="team-invite-form" onSubmit={submit}>
     <label htmlFor="lot-piece">来源分段</label>
     <select id="lot-piece" value={pieceId}
      onChange={e=>{setPieceId(e.target.value);setTargetBin('');changed()}}>
      <option value="">选择来源分段</option>
      {(pieces.data?.items??[]).map(p=><option key={p.id} value={p.id}>
       {p.pieceCode} · {p.quantity} 件</option>)}
     </select>
     <label htmlFor="lot-action">操作</label>
     <select id="lot-action" value={action}
      onChange={e=>{setAction(e.target.value as 'SPLIT'|'MOVE');changed()}}>
      <option value="SPLIT">按数量拆分</option>
      <option value="MOVE">整段移动库位</option>
     </select>
     {action==='SPLIT'&&<>
      <label htmlFor="lot-split-qty">拆出的件数</label>
      <input id="lot-split-qty" type="number" min={1}
       max={Math.max(1,(source?.quantity??1)-1)} value={splitQuantity}
       onChange={e=>{setSplitQuantity(Number(e.target.value));changed()}}/>
      <label htmlFor="lot-split-code">新分段编号</label>
      <input id="lot-split-code" maxLength={80} value={splitCode}
       pattern="[A-Za-z0-9][A-Za-z0-9_.-]{1,79}"
       placeholder="ROOT-B" onChange={e=>{setSplitCode(e.target.value);changed()}}/>
     </>}
     <label htmlFor="lot-destination">目标待检库位</label>
     <select id="lot-destination" value={targetBin}
      onChange={e=>{setTargetBin(e.target.value);changed()}}>
      <option value="">选择目标库位</option>
      {activeQcBins.map(b=><option key={b.id} value={b.id}>{b.code}</option>)}
     </select>
     <label htmlFor="lot-change-reason">分段/库位操作说明</label>
     <input id="lot-change-reason" value={reason} minLength={5} maxLength={300}
      onChange={e=>{setReason(e.target.value);changed()}}
      placeholder="记录实物清点和分段标签依据"/>
     <button className="primary-button" type="submit" disabled={!canSubmit}>
      {action==='SPLIT'?'保存待检分段':'记录待检库位移动'}</button>
    </form>
   </>}
  </>}
  {message&&<p className="panel-caption" role="status">{message}</p>}
  {failure&&<p className="setup-error" role="alert">{failure}</p>}
 </section>
}
