import {useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import type {LocalWarehouse} from './lib/purchasing'
import {fetchWarehouseStock} from './lib/purchasing'
import {fetchReceiptBatches} from './lib/receipt-batches'
import {fetchReceiptLotPieces,fetchStockBins} from './lib/lot-locations'
import {fetchPieceGrades,gradePiece,finalizePieceGrades,
 fetchLotPositions,putawayReleasedLot,previewLotPick,pickReleasedLots,
 type LotPolicy} from './lib/released-lots'

export default function ReleasedLotStockPanel({tenantId,orderId,warehouses}:{
 tenantId:string;orderId:string;warehouses:LocalWarehouse[]
}){
 const client=useQueryClient()
 const root=['purchasing',tenantId]
 const [receipt,setReceipt]=useState('')
 const [piece,setPiece]=useState('')
 const [rejected,setRejected]=useState(0)
 const [reason,setReason]=useState('')
 const [gradeKey,setGradeKey]=useState(()=>crypto.randomUUID())
 const [finalReason,setFinalReason]=useState('')
 const [finalKey,setFinalKey]=useState(()=>crypto.randomUUID())
 const [warehouse,setWarehouse]=useState('')
 const [storageBin,setStorageBin]=useState('')
 const [putawayQty,setPutawayQty]=useState(1)
 const [expiry,setExpiry]=useState('')
 const [putawayReason,setPutawayReason]=useState('')
 const [putawayKey,setPutawayKey]=useState(()=>crypto.randomUUID())
 const [sku,setSku]=useState('')
 const [policy,setPolicy]=useState<LotPolicy>('FIFO')
 const [pickQty,setPickQty]=useState(1)
 const [pickReason,setPickReason]=useState('')
 const [pickKey,setPickKey]=useState(()=>crypto.randomUUID())
 const [message,setMessage]=useState(''),[failure,setFailure]=useState('')
 const batches=useQuery({queryKey:[...root,'receipt-batches',orderId],
  queryFn:()=>fetchReceiptBatches(orderId),enabled:!!orderId,retry:false})
 const pieces=useQuery({queryKey:[...root,'pieces',receipt],
  queryFn:()=>fetchReceiptLotPieces(receipt),enabled:!!receipt,retry:false})
 const grades=useQuery({queryKey:[...root,'piece-grades',receipt],
  queryFn:()=>fetchPieceGrades(receipt),enabled:!!receipt,retry:false})
 const bins=useQuery({queryKey:[...root,'bins',warehouse],
  queryFn:()=>fetchStockBins(warehouse),enabled:!!warehouse,retry:false})
 const positions=useQuery({queryKey:[...root,'lot-positions',warehouse],
  queryFn:()=>fetchLotPositions(warehouse),enabled:!!warehouse,retry:false})
 const stock=useQuery({queryKey:[...root,'stock',warehouse],
  queryFn:()=>fetchWarehouseStock(warehouse),enabled:!!warehouse,retry:false})
 const preview=useQuery({queryKey:[...root,'lot-pick-preview',warehouse,sku,policy],
  queryFn:()=>previewLotPick(warehouse,sku,policy),
  enabled:!!warehouse&&!!sku,retry:false})
 const refresh=()=>void client.invalidateQueries({queryKey:root})
 const grade=useMutation({mutationFn:()=>gradePiece(receipt,{requestKey:gradeKey,
  pieceId:piece,acceptedQuantity:Math.max(0,(selectedPiece?.quantity??0)-rejected),
  rejectedQuantity:rejected,reason:reason.trim()}),
  onSuccess:()=>{setMessage('待检分段质检已记录，尚未放行可用库存');
   setFailure('');setPiece('');setReason('');setRejected(0);
   setGradeKey(crypto.randomUUID());refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 const finalize=useMutation({mutationFn:()=>finalizePieceGrades(receipt,{
  requestKey:finalKey,reason:finalReason.trim()}),
  onSuccess:r=>{setMessage('已汇总 '+r.inspectedQuantity+
   ' 件质检结果；合格品仍须由不同 Owner 在 V46 批次面板审批放行');
   setFailure('');setFinalKey(crypto.randomUUID());setFinalReason('');refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 const putaway=useMutation({mutationFn:()=>putawayReleasedLot({
  requestKey:putawayKey,receiptId:receipt,binId:storageBin,
  quantity:putawayQty,expiryDate:expiry||null,reason:putawayReason.trim()}),
  onSuccess:()=>{setMessage('已标记合格品库位，不重复增加仓库可用库存');
   setFailure('');setPutawayKey(crypto.randomUUID());setPutawayReason('');refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 const pick=useMutation({mutationFn:()=>pickReleasedLots({requestKey:pickKey,
  warehouseId:warehouse,productSkuId:sku,policy,
  quantity:pickQty,expectedOnHand:usable,reason:pickReason.trim()}),
  onSuccess:r=>{setMessage('已按 '+r.policy+' 从 '+r.allocations.length+
   ' 个批次库位拣货 '+r.quantity+' 件，并在同一事务登记仓库出库');
   setFailure('');setPickKey(crypto.randomUUID());setPickReason('');refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 const selectedBatch=batches.data?.find(b=>b.receiptId===receipt)
 const selectedPiece=pieces.data?.find(p=>p.id===piece)
 const gradeMap=new Set(grades.data?.map(g=>g.pieceId)??[])
 const allGraded=!!pieces.data?.length&&
  pieces.data.every(p=>gradeMap.has(p.id))&&
  (grades.data?.reduce((n,g)=>n+g.acceptedQuantity+g.rejectedQuantity,0)??0)===
  selectedBatch?.receivedQuantity
 const sourcePieces=pieces.data??[]
 const availableBins=bins.data?.filter(b=>b.purpose==='STORAGE'&&b.status==='ACTIVE')??[]
 const remainingToPlace=Math.max(0,(selectedBatch?.acceptedQuantity??0)-
  (positions.data?.filter(p=>p.receiptId===receipt)
   .reduce((sum,p)=>sum+p.placedQuantity,0)??0))
 const skus=stock.data??[]
 const usable=skus.find(s=>s.productSkuId===sku)?.onHandQuantity??0
 const availablePositioned=preview.data?.reduce((n,p)=>n+p.remainingQuantity,0)??0
 const canGrade=!!selectedBatch&&selectedBatch.status==='PENDING_QC'&&
  !!selectedPiece&&!gradeMap.has(selectedPiece.id)&&
  Number.isSafeInteger(rejected)&&rejected>=0&&rejected<=selectedPiece.quantity&&
  reason.trim().length>=5&&reason.trim().length<=300&&!grade.isPending
 const canPlace=!!selectedBatch&&selectedBatch.status==='RELEASED'&&
  warehouse===selectedBatch.warehouseId&&
  availableBins.some(b=>b.id===storageBin)&&
  Number.isSafeInteger(putawayQty)&&putawayQty>0&&putawayQty<=remainingToPlace&&
  putawayReason.trim().length>=5&&putawayReason.trim().length<=300&&!putaway.isPending
 const canPick=!!sku&&!!warehouse&&Number.isSafeInteger(pickQty)&&
  pickQty>=1&&pickQty<=Math.min(usable,availablePositioned)&&
  pickReason.trim().length>=5&&pickReason.trim().length<=300&&
  !stock.isFetching&&!preview.isFetching&&!pick.isPending
 return <section className="panel full-panel" aria-label="V48 分段质检与批次库位拣货">
  <div className="panel-heading"><div>
   <h2>V48 · 分段质检、合格品上架与 FIFO/FEFO 拣货</h2>
   <p>先为真实收货的各待检分段登记合格/不合格件数，汇总后仍由另一位 Owner
    审批放行；放行后的合格件才允许标记到 STORAGE 库位。拣货同时扣减批次库位
    与本地仓库的签名库存流水，不能自动操作 Amazon。</p>
  </div><button className="refresh-button" type="button" onClick={refresh}>刷新</button></div>
  <label htmlFor="v48-warehouse">本地仓库</label>
  <select id="v48-warehouse" value={warehouse}
   onChange={e=>{setWarehouse(e.target.value);setReceipt('');setPiece('');
    setStorageBin('');setSku('');setPickKey(crypto.randomUUID())}}>
   <option value="">选择仓库</option>
   {warehouses.filter(w=>w.status==='ACTIVE').map(w=>
    <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
  </select>
  {!!warehouse&&!!orderId&&<>
   <h3>真实收货分段质检与上架</h3>
   <label htmlFor="v48-receipt">V46+ 收货批次</label>
   <select id="v48-receipt" value={receipt}
    onChange={e=>{setReceipt(e.target.value);setPiece('');setStorageBin('')}}>
    <option value="">选择实收批次</option>
    {(batches.data??[]).filter(b=>b.warehouseId===warehouse).map(b=>
     <option key={b.receiptId} value={b.receiptId}>
      {b.lotCode} · {b.receivedQuantity} 件 · {b.status}</option>)}
   </select>
   {!!receipt&&<>
    <p className="setup-hint">分段质检只能对尚未完成整单质检的批次进行；开始质检后不能再改动分段数量或库位。</p>
    <div className="table-scroll" role="region" tabIndex={0}>
     <table><thead><tr><th scope="col">分段</th><th scope="col">件数</th>
      <th scope="col">质检结果</th></tr></thead><tbody>
      {sourcePieces.map(p=>{const g=grades.data?.find(q=>q.pieceId===p.id)
       return <tr key={p.id}><td>{p.pieceCode}</td><td>{p.quantity}</td>
        <td>{g?('合格 '+g.acceptedQuantity+'，不合格 '+g.rejectedQuantity):
         '尚未质检'}</td></tr>})}
     </tbody></table>
    </div>
    {selectedBatch?.status==='PENDING_QC'&&<>
     <form className="team-invite-form" onSubmit={(e:FormEvent)=>{
      e.preventDefault();if(!canGrade)return
      if(window.confirm('记录不可覆盖的分段质检结果？此操作不会直接增加可用库存。'))
       grade.mutate()
     }}>
      <h3>分段数量质检</h3>
      <label htmlFor="v48-piece">待检分段</label>
      <select id="v48-piece" value={piece}
       onChange={e=>{setPiece(e.target.value);setRejected(0);
        setGradeKey(crypto.randomUUID())}}>
       <option value="">选择未质检分段</option>
       {sourcePieces.filter(p=>!gradeMap.has(p.id)).map(p=>
        <option key={p.id} value={p.id}>{p.pieceCode} · {p.quantity} 件</option>)}
      </select>
      <label htmlFor="v48-rejected">不合格件数（其余为合格）</label>
      <input id="v48-rejected" type="number" min={0}
       max={selectedPiece?.quantity??0} value={rejected}
       onChange={e=>{setRejected(Number(e.target.value));setGradeKey(crypto.randomUUID())}}/>
      <label htmlFor="v48-grade-reason">质检证据说明</label>
      <input id="v48-grade-reason" minLength={5} maxLength={300}
       value={reason} onChange={e=>{setReason(e.target.value);
        setGradeKey(crypto.randomUUID())}} required/>
      <button type="submit" className="primary-button"
       disabled={!canGrade}>登记本段质检结果</button>
     </form>
     {allGraded&&<form className="team-invite-form" onSubmit={e=>{
      e.preventDefault()
      if(finalReason.trim().length<5||finalize.isPending)return
      if(window.confirm('全部分段已质检。汇总为完整收货质检并交另一位 Owner 放行？'))
       finalize.mutate()
     }}>
      <h3>完成整笔收货质检</h3>
      <p className="setup-hint">全部 {selectedBatch?.receivedQuantity} 件已完成分段记录。
       汇总合格 {grades.data?.reduce((n,g)=>n+g.acceptedQuantity,0)??0} 件，
       不合格 {grades.data?.reduce((n,g)=>n+g.rejectedQuantity,0)??0} 件。</p>
      <label htmlFor="v48-final-reason">最终质检汇总说明</label>
      <input id="v48-final-reason" minLength={5} maxLength={300}
       value={finalReason} onChange={e=>{setFinalReason(e.target.value);
        setFinalKey(crypto.randomUUID())}} required/>
      <button className="primary-button" type="submit"
       disabled={finalize.isPending||finalReason.trim().length<5}>
       完成质检汇总（仍待独立放行）</button>
     </form>}
    </>}
    {selectedBatch?.status==='RELEASED'&&<>
     <h3>已放行合格品上架</h3>
     <p className="setup-hint">本批次已独立放行合格
      {selectedBatch.acceptedQuantity} 件；累计已登记库位
      {selectedBatch.acceptedQuantity-remainingToPlace} 件，
      仍未分配具体 STORAGE 库位 {remainingToPlace} 件。
      上架不重复计入仓库总库存。</p>
     <form className="team-invite-form" onSubmit={e=>{
      e.preventDefault();if(!canPlace)return
      if(window.confirm('确认已将合格品放入所选 STORAGE 库位？此操作只绑定可用库存来源，不重复入账。'))
       putaway.mutate()
     }}>
      <label htmlFor="v48-storage-bin">可用 STORAGE 库位</label>
      <select id="v48-storage-bin" value={storageBin}
       onChange={e=>{setStorageBin(e.target.value);setPutawayKey(crypto.randomUUID())}}>
       <option value="">选择普通存放库位（可在 V47 新建）</option>
       {availableBins.map(b=><option key={b.id} value={b.id}>{b.code}</option>)}
      </select>
      <label htmlFor="v48-putaway-qty">本次上架件数</label>
      <input id="v48-putaway-qty" type="number" min={1}
       max={remainingToPlace} value={putawayQty}
       onChange={e=>{setPutawayQty(Number(e.target.value));
        setPutawayKey(crypto.randomUUID())}}/>
      <label htmlFor="v48-expiry">有效期（可选；FEFO 优先有效期更早且未过期的批次）</label>
      <input id="v48-expiry" type="date" value={expiry}
       onChange={e=>{setExpiry(e.target.value);setPutawayKey(crypto.randomUUID())}}/>
      <label htmlFor="v48-putaway-reason">上架清点依据</label>
      <input id="v48-putaway-reason" minLength={5} maxLength={300}
       value={putawayReason} onChange={e=>{setPutawayReason(e.target.value);
        setPutawayKey(crypto.randomUUID())}} required/>
      <button className="primary-button" type="submit" disabled={!canPlace}>
       登记合格品库位</button>
     </form>
    </>}
   </>}
  </>}
  {!!warehouse&&<>
   <h3>已放行库位库存和 FIFO/FEFO 出库</h3>
   <p className="setup-hint">这里只展示明确上架的合格品批次。未上架的可用库存可走普通出库；
    已上架数量必须使用批次拣货，不得被普通出库或调拨悄悄扣减。
    有效期过期批次不会自动被选中，缺有效期的批次无法证明完整 FEFO。</p>
   {positions.isPending?<p className="empty-state">读取合格品库位台账…</p>:
    positions.isError?<p className="setup-error" role="alert">{String(positions.error)}</p>:
    !positions.data?.length?<p className="empty-state">暂无明确上架的合格批次库存。</p>:
    <div className="table-scroll" role="region" tabIndex={0}>
     <table><thead><tr><th scope="col">批次</th><th scope="col">库位</th>
      <th scope="col">上架/余量</th><th scope="col">有效期</th></tr></thead><tbody>
      {positions.data.map(p=><tr key={p.id}>
       <td>{p.lotCode}</td><td>{p.binCode}</td>
       <td>{p.placedQuantity} / {p.remainingQuantity}</td>
       <td>{p.expiryDate??'未登记'}</td></tr>)}
     </tbody></table>
    </div>}
   <form className="team-invite-form" onSubmit={e=>{
    e.preventDefault();if(!canPick)return
    if(window.confirm('确认从指定批次/库位实际拣货并扣减本地仓库库存 '+pickQty+
       ' 件？无法自动撤回，且不调用 Amazon。'))pick.mutate()
   }}>
    <label htmlFor="v48-pick-sku">拣货 SKU</label>
    <select id="v48-pick-sku" value={sku}
     onChange={e=>{setSku(e.target.value);setPickKey(crypto.randomUUID())}}>
     <option value="">选择已存在的 SKU</option>
     {skus.filter(x=>x.onHandQuantity>0).map(s=>
      <option key={s.productSkuId} value={s.productSkuId}>
       {s.internalSku} · 当前可用 {s.onHandQuantity}</option>)}
    </select>
    <label htmlFor="v48-policy">拣货策略</label>
    <select id="v48-policy" value={policy}
     onChange={e=>{setPolicy(e.target.value as LotPolicy);setPickKey(crypto.randomUUID())}}>
     <option value="FIFO">FIFO · 优先较早收货</option>
     <option value="FEFO">FEFO · 优先较早有效期</option>
    </select>
    <p className="setup-hint">符合策略的非过期已上架批次余量：
     {availablePositioned} 件；仓库总可用 {usable} 件。</p>
    {!!preview.data?.length&&<div className="table-scroll" role="region" tabIndex={0}>
     <table><thead><tr><th scope="col">预计拣货顺序</th><th scope="col">批次/库位</th>
      <th scope="col">余量</th><th scope="col">有效期</th></tr></thead><tbody>
      {preview.data.map((p,i)=><tr key={p.id}>
       <td>{i+1}</td><td>{p.lotCode} · {p.binCode}</td>
       <td>{p.remainingQuantity}</td><td>{p.expiryDate??'未知'}</td></tr>)}
     </tbody></table>
    </div>}
    <label htmlFor="v48-pick-qty">确认实际拣货件数</label>
    <input id="v48-pick-qty" type="number" min={1}
     max={Math.min(usable,availablePositioned)} value={pickQty}
     onChange={e=>{setPickQty(Number(e.target.value));setPickKey(crypto.randomUUID())}}/>
    <label htmlFor="v48-pick-reason">实际出库原因</label>
    <input id="v48-pick-reason" minLength={5} maxLength={300}
     value={pickReason} onChange={e=>{setPickReason(e.target.value);
      setPickKey(crypto.randomUUID())}} required/>
    <button className="primary-button" type="submit" disabled={!canPick}>
     确认 FIFO/FEFO 拣货并记本地出库</button>
   </form>
  </>}
  {message&&<p className="panel-caption" role="status">{message}</p>}
  {failure&&<p className="setup-error" role="alert">{failure}</p>}
 </section>
}
