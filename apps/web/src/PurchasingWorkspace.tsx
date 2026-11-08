import {useEffect,useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {fetchSkus} from './lib/catalog'
import InternalSkuPicker from './InternalSkuPicker'
import SupplierPicker from './SupplierPicker'
import WarehousePicker from './WarehousePicker'
import {useConfirmAction} from './components/useConfirmAction'
import {
 SupplierDirectoryTable,PurchaseOrdersTable,WarehouseLedgerTable
} from './components/PurchasingDataTables'
import WarehouseAlertsPanel from './WarehouseAlertsPanel'
import {WarehouseStockPagePanel,PendingReceiptPages} from './WarehouseReadPages'
import SupplierPurchasePlanningPanel from './SupplierPurchasePlanningPanel'
import SupplierAsnPanel from './SupplierAsnPanel'
import PurchaseDeliveryPanel from './PurchaseDeliveryPanel'
import SupplierPerformancePanel from './SupplierPerformancePanel'
import PurchaseQualityPanel from './PurchaseQualityPanel'
import ReceiptBatchPanel from './ReceiptBatchPanel'
import ReceiptLotLocationPanel from './ReceiptLotLocationPanel'
import ReleasedLotStockPanel from './ReleasedLotStockPanel'
import LotLifecyclePanel from './LotLifecyclePanel'
import LotReconciliationPanel from './LotReconciliationPanel'
import LotExceptionPanel from './LotExceptionPanel'
import LotDispositionProofPanel from './LotDispositionProofPanel'
import LotEvidenceFilesPanel from './LotEvidenceFilesPanel'
import SupplierDisputesPanel from './SupplierDisputesPanel'
import {createSupplier,fetchSuppliersPage,createPurchaseOrder,
 fetchPurchaseOrder,purchaseAction,receivePurchase,fetchWarehouses,fetchWarehouseItem,createWarehouse,
 fetchWarehouseStockItem,postStockOperation,
 type NewPurchaseLine,type StockOperationRequest} from './lib/purchasing'

export default function PurchasingWorkspace({tenantId,currentUserId}:{
 tenantId:string;currentUserId:string
}){
 const client=useQueryClient()
 const confirmation=useConfirmAction()
 const [supplierCode,setSupplierCode]=useState(''),[supplierName,setSupplierName]=useState('')
 const [supplierId,setSupplierId]=useState(''),[currency,setCurrency]=useState('USD')
 const [notes,setNotes]=useState(''),[sku,setSku]=useState('')
 const [qty,setQty]=useState(1),[unitCost,setUnitCost]=useState('')
 const [draft,setDraft]=useState<NewPurchaseLine[]>([])
 const [selectedId,setSelectedId]=useState(''),[receiveLine,setReceiveLine]=useState('')
 const [receiveQty,setReceiveQty]=useState(1)
 const [receiveAsnId,setReceiveAsnId]=useState('')
 // Switching purchase orders must never silently carry an ASN attribution forward.
 useEffect(()=>setReceiveAsnId(''),[selectedId])
 const [warehouseCode,setWarehouseCode]=useState(''),[warehouseName,setWarehouseName]=useState('')
 const [warehouseId,setWarehouseId]=useState(''),[stockWarehouseId,setStockWarehouseId]=useState('')
 const [operationType,setOperationType]=useState<StockOperationRequest['type']>('OUTBOUND')
 const [operationSku,setOperationSku]=useState('')
 const [verifiedSkuLabels,setVerifiedSkuLabels]=useState<Record<string,string>>({})
 const [operationDestination,setOperationDestination]=useState('')
 const [operationQuantity,setOperationQuantity]=useState(1)
 const [operationReason,setOperationReason]=useState('')
 const [operationKey,setOperationKey]=useState(()=>crypto.randomUUID())
 const [message,setMessage]=useState(''),[failure,setFailure]=useState('')
 // Legacy first-200 warehouse directory is now read-only auxiliary labeling.
 const warehouses=useQuery({queryKey:['purchasing',tenantId,'warehouses'],queryFn:fetchWarehouses})
 const stockWarehouse=useQuery({
  queryKey:['purchasing',tenantId,'warehouse-exact',stockWarehouseId],
  queryFn:()=>fetchWarehouseItem(stockWarehouseId),
  enabled:!!stockWarehouseId,retry:false
 })
 const destinationWarehouse=useQuery({
  queryKey:['purchasing',tenantId,'warehouse-exact',operationDestination],
  queryFn:()=>fetchWarehouseItem(operationDestination),
  enabled:!!operationDestination,retry:false
 })
 const stockItem=useQuery({
  queryKey:['purchasing',tenantId,'stock-item',stockWarehouseId,operationSku],
  queryFn:()=>fetchWarehouseStockItem(stockWarehouseId,operationSku),
  enabled:!!stockWarehouseId&&!!operationSku,retry:false
 })
 const suppliers=useQuery({
  queryKey:['purchasing',tenantId,'suppliers-page','ALL','',null],
  queryFn:()=>fetchSuppliersPage({limit:50,status:'ALL'}),retry:false
 })
 const skus=useQuery({queryKey:['catalog',tenantId,'for-purchase'],queryFn:()=>fetchSkus()})
 const selected=useQuery({queryKey:['purchasing',tenantId,'detail',selectedId],
  queryFn:()=>fetchPurchaseOrder(selectedId),enabled:!!selectedId})
 const refresh=()=>void client.invalidateQueries({queryKey:['purchasing',tenantId]})
 const warehouseCreated=useMutation({
  mutationFn:()=>createWarehouse(warehouseCode,warehouseName),
  onSuccess:w=>{setWarehouseCode('');setWarehouseName('');
    setMessage('本地仓库已创建：'+w.code+'；请在全库选择器中搜索并选用。');
    setFailure('');refresh()},
  onError:(e:Error)=>setFailure(e.message)
 })
 const supplier=useMutation({mutationFn:()=>createSupplier(supplierCode,supplierName),
  onSuccess:()=>{setMessage('供应商已创建');setFailure('');setSupplierCode('');
    setSupplierName('');refresh()},onError:(e:Error)=>setFailure(e.message)})
 const created=useMutation({mutationFn:()=>createPurchaseOrder(supplierId,currency,notes,draft),
  onSuccess:d=>{setMessage('采购单草稿已建立');setFailure('');setDraft([]);
    setSelectedId(d.order.id);refresh()},onError:(e:Error)=>setFailure(e.message)})
 const action=useMutation({mutationFn:({id,kind}:{id:string;kind:'submit'|'approve'|'cancel'})=>
  purchaseAction(id,kind),onSuccess:d=>{
    setMessage('采购单状态已更新：'+d.order.status);setFailure('');refresh()
  },onError:(e:Error)=>setFailure(e.message)})
 const stockOperation=useMutation({
  mutationFn:(body:StockOperationRequest)=>postStockOperation(body),
  onSuccess:o=>{setMessage('本地库存操作已记录：'+o.type+' · 余额 '+o.afterQuantity);
    setFailure('');setOperationKey(crypto.randomUUID());setOperationReason('');
    setOperationSku('');setOperationQuantity(1);refresh()},
  onError:(e:Error)=>setFailure(e.message)
 })
 const receipt=useMutation({mutationFn:()=>receivePurchase(selectedId,receiveLine,receiveQty,
   warehouseId,receiveAsnId||null),
  onSuccess:d=>{setMessage('实物收货已登记到 V46 待检批次（尚未进入可用库存）：'+d.order.status);
    setReceiveAsnId('');setFailure('');refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 function addSupplier(e:FormEvent){e.preventDefault();supplier.mutate()}
 function addWarehouse(e:FormEvent){e.preventDefault();warehouseCreated.mutate()}
 function create(e:FormEvent){e.preventDefault();if(draft.length&&supplierId)created.mutate()}
 useEffect(()=>setSupplierId(''),[tenantId])
 const remaining=selected.data?.lines.filter(l=>l.receivedQuantity<l.orderedQuantity)??[]
 const supplierOptions=suppliers.data?.items.filter(s=>s.status==='ACTIVE')??[]
 const activeSkus=skus.data?.filter(s=>s.status==='ACTIVE')??[]

 // A missing SKU on the current list page is UNKNOWN, never zero.
 // Exact authenticated tenant/warehouse/SKU observation is required even for COUNT.
 const currentOnHand=stockItem.data?.onHandQuantity
 const quantityValid=currentOnHand!==undefined&&Number.isSafeInteger(operationQuantity)&&
  (operationType==='COUNT'?(operationQuantity>=0&&operationQuantity<=10000000
    &&operationQuantity!==currentOnHand):
   (operationQuantity>=1&&operationQuantity<=1000000&&operationQuantity<=currentOnHand))
 const operationReady=!!stockWarehouseId&&!!operationSku&&!!stockItem.data&&
  // V91: SKU is verified by the scoped server-paged picker, not the first 100 rows.
  
  stockWarehouse.data?.id===stockWarehouseId&&stockWarehouse.data.status==='ACTIVE'&&
  !stockWarehouse.isFetching&&
  (operationType!=='TRANSFER'||(destinationWarehouse.data?.id===operationDestination
    &&destinationWarehouse.data.status==='ACTIVE'&&
    !destinationWarehouse.isFetching&&operationDestination!==stockWarehouseId))&&quantityValid&&
  operationReason.trim().length>=5&&operationReason.trim().length<=300&&
  !stockItem.isFetching&&!stockItem.isPending&&!stockOperation.isPending
 function submitStockOperation(e:FormEvent){
  e.preventDefault()
  if(!operationReady||currentOnHand===undefined)return
  const body:StockOperationRequest={requestKey:operationKey,type:operationType,
   warehouseId:stockWarehouseId,destinationWarehouseId:operationType==='TRANSFER'
    ?operationDestination:null,productSkuId:operationSku,quantity:operationQuantity,
   expectedOnHand:currentOnHand,reason:operationReason.trim()}
  confirmation.request({
   title:'确认写入本地库存流水',
   description:'仓库余额变更将留下不可删除的审计记录。请核对仓库、SKU、数量与原因；本地操作不会同步 Amazon。',
   confirmLabel:'确认写入流水',danger:true,
   onConfirm:()=>stockOperation.mutate(body)
  })
 }
 return <div className="core-workspace">
  {confirmation.dialog}
  <div className="core-intro"><strong>采购与供应商 · 本地业务</strong><p>
   支持采购审批、真实收货待检批次、双人质检放行和不可覆盖的本地入库流水。
   本地累计入库不是可售库存，也不代表 Amazon FBA 已入仓；采购金额不等于平台结算。</p></div>
  <section className="panel full-panel" aria-label="本地仓库">
   <div className="panel-heading"><div><h2>本地仓库</h2>
    <p>收货时必须选定实际本地仓库；原有未分配仓库的历史收货不会计入仓库流水。</p></div></div>
   <form className="team-invite-form" onSubmit={addWarehouse}>
    <label htmlFor="warehouse-code">仓库编号</label>
    <input id="warehouse-code" required minLength={2} maxLength={60}
     pattern="[A-Z0-9][A-Z0-9_-]{1,59}" value={warehouseCode}
     onChange={e=>setWarehouseCode(e.target.value.toUpperCase())}/>
    <label htmlFor="warehouse-name">仓库名称</label>
    <input id="warehouse-name" required minLength={2} maxLength={200}
     value={warehouseName} onChange={e=>setWarehouseName(e.target.value)}/>
    <button type="submit" className="primary-button"
     disabled={warehouseCreated.isPending}>建立本地仓库</button>
   </form>
   {warehouses.isPending?<p className="empty-state">读取仓库中…</p>:
    warehouses.isError?<p className="setup-error" role="alert">{String(warehouses.error)}</p>:
    <p className="setup-hint">兼容接口本次读取 {warehouses.data?.length??0} 个仓库（最多 200），
     不代表本租户仓库总数。收货、库存和调拨使用可搜索全库选择器。</p>}
  </section>
  <section className="panel full-panel"><div className="panel-heading"><h2>供应商</h2></div>
   <form className="team-invite-form" onSubmit={addSupplier}>
    <label htmlFor="purchase-supplier-code">供应商编号</label>
    <input id="purchase-supplier-code" value={supplierCode}
      onChange={e=>setSupplierCode(e.target.value.toUpperCase())}
      required pattern="[A-Z0-9][A-Z0-9_-]{1,59}" maxLength={60}/>
    <label htmlFor="purchase-supplier-name">供应商名称</label>
    <input id="purchase-supplier-name" value={supplierName}
      onChange={e=>setSupplierName(e.target.value)} minLength={2} maxLength={200} required/>
    <button className="primary-button" type="submit" disabled={supplier.isPending}>
      新建供应商</button>
   </form>
   <SupplierDirectoryTable key={tenantId} tenantId={tenantId}/>
  </section>
  <section className="panel full-panel"><div className="panel-heading"><h2>创建采购单草稿</h2></div>
   <div className="team-invite-form">
    <SupplierPicker key={tenantId} tenantId={tenantId} id="purchase-supplier"
     label="采购草稿供应商" value={supplierId} onChange={setSupplierId}/>
    <label htmlFor="purchase-currency">币种</label>
    <select id="purchase-currency" value={currency} onChange={e=>setCurrency(e.target.value)}>
     {['USD','EUR','GBP','JPY','CNY','CAD','AUD'].map(c=><option value={c} key={c}>{c}</option>)}
    </select>
    <label htmlFor="purchase-notes">备注（不填敏感数据）</label>
    <input id="purchase-notes" value={notes} onChange={e=>setNotes(e.target.value)}
      maxLength={300}/>
    <InternalSkuPicker tenantId={tenantId} id="purchase-sku"
     label="添加采购内部 SKU 行（全库选择）" value={sku}
     onChange={(id,item)=>{
      setSku(id)
      if(item)setVerifiedSkuLabels(prev=>({...prev,[item.id]:item.internalSku}))
     }}/>
    <label htmlFor="purchase-quantity">订购数量</label>
    <input id="purchase-quantity" type="number" min={1} max={1000000} value={qty}
      onChange={e=>setQty(Number(e.target.value))}/>
    <label htmlFor="purchase-unit-cost">单位采购价（{currency}）</label>
    <input id="purchase-unit-cost" inputMode="decimal" value={unitCost}
      pattern="[0-9]{1,14}(\\.[0-9]{1,6})?"
      onChange={e=>setUnitCost(e.target.value)}/>
    <button type="button" className="refresh-button" disabled={!sku||!Number.isInteger(qty)||
      qty<1||qty>1000000||!/^[0-9]{1,14}(\\.[0-9]{1,6})?$/.test(unitCost)}
      onClick={()=>{setDraft(x=>[...x,{productSkuId:sku,quantity:qty,unitCost}]);
        setSku('');setUnitCost('')}}>加入采购明细</button>
   </div>
   <div className="table-scroll" role="region" tabIndex={0} aria-label="待提交采购明细">
    <table><thead><tr><th scope="col">SKU</th><th scope="col">数量</th>
     <th scope="col">原币单价</th><th scope="col">操作</th></tr></thead>
     <tbody>{draft.map(l=><tr key={l.productSkuId}>
      <td>{verifiedSkuLabels[l.productSkuId]??activeSkus.find(s=>s.id===l.productSkuId)?.internalSku??l.productSkuId}</td>
      <td>{l.quantity}</td><td>{currency} {l.unitCost}</td>
      <td><button className="text-button" type="button"
       onClick={()=>setDraft(v=>v.filter(x=>x.productSkuId!==l.productSkuId))}>移除</button></td>
     </tr>)}</tbody></table>
   </div>
   <form onSubmit={create}><button type="submit" className="primary-button"
     disabled={!supplierId||!draft.length||created.isPending}>保存采购单草稿</button></form>
  </section>
  <SupplierPerformancePanel tenantId={tenantId} onOpenOrder={setSelectedId}/>
  <PurchaseDeliveryPanel tenantId={tenantId} onOpenOrder={orderId=>{
   setSelectedId(orderId);setReceiveAsnId('')
  }}/>
  <section className="panel full-panel" aria-label="已审批待收货">
   <div className="panel-heading"><div><h2>已审批待收货</h2>
    <p>仅统计 APPROVED / RECEIVING 采购单的剩余数量，不代表供应商已发货或真实在途。</p></div></div>
   <PendingReceiptPages key={tenantId} tenantId={tenantId}
    onOpen={(orderId,lineId)=>{
     setSelectedId(orderId);setReceiveLine(lineId);setReceiveAsnId('')
    }}/>
  </section>
  <section className="panel full-panel"><div className="panel-heading"><div><h2>采购单列表与流程</h2>
   <p>批准人必须是另一名 Owner；收货可以分批，但不能超过每行订购数量。</p></div>
   <button className="refresh-button" onClick={refresh}>刷新</button></div>
   <PurchaseOrdersTable key={tenantId} tenantId={tenantId}
     suppliers={suppliers.data?.items??[]} currentUserId={currentUserId}
     busy={action.isPending} onView={setSelectedId}
     onAction={(id,kind)=>action.mutate({id,kind})}
     onCancel={o=>confirmation.request({
      title:'确认取消采购单',
      description:'采购单 '+o.orderNumber+' 将取消。此操作会改变本地采购审批状态，不会修改 Amazon 订单或 FBA 库存。',
      confirmLabel:'取消此采购单',danger:true,
      onConfirm:()=>action.mutate({id:o.id,kind:'cancel'})
     })}/>
   {selectedId&&<div className="core-tools">
    <h3>采购明细 / 分批收货</h3>
    {selected.isPending?<p>读取中…</p>:selected.isError?
      <p className="setup-error">{String(selected.error)}</p>:selected.data&&<>
      <p className="setup-hint">{selected.data.order.orderNumber} · {selected.data.order.status}</p>
      <div className="table-scroll" role="region" tabIndex={0}>
       <table><thead><tr><th scope="col">内部 SKU</th><th scope="col">订购</th>
       <th scope="col">已收</th><th scope="col">原币单价</th></tr></thead><tbody>
       {selected.data.lines.map(l=><tr key={l.id}><td>
        {activeSkus.find(s=>s.id===l.productSkuId)?.internalSku??l.productSkuId}</td>
        <td>{l.orderedQuantity}</td><td>{l.receivedQuantity}</td>
        <td>{selected.data?.order.currency} {l.unitCost}</td></tr>)}
       </tbody></table>
      </div>
      {['APPROVED','RECEIVING'].includes(selected.data.order.status)&&
       <div className="team-invite-form">
        <WarehousePicker tenantId={tenantId} id="receipt-warehouse"
         label="实际收货仓库（全库必选）" value={warehouseId}
         onChange={id=>{setWarehouseId(id);setReceiveAsnId('')}}/>
        <label htmlFor="receipt-line">待收货明细</label>
        <select id="receipt-line" value={receiveLine} onChange={e=>{setReceiveLine(e.target.value);setReceiveAsnId('')}}>
         <option value="">选择未收完的行</option>
         {remaining.map(l=><option value={l.id} key={l.id}>
          {activeSkus.find(s=>s.id===l.productSkuId)?.internalSku??l.productSkuId}
          {' · 剩余 '+(l.orderedQuantity-l.receivedQuantity)}</option>)}
        </select>
        {receiveAsnId&&<p className="setup-hint">
         本次与供应商 ASN {receiveAsnId.slice(0,8)} 关联，
         只在实际收到后提交。选择新仓库或采购明细会清除关联。
         <button className="refresh-button" type="button"
          onClick={()=>setReceiveAsnId('')}>改为不关联 ASN 收货</button>
        </p>}
        <label htmlFor="receipt-qty">本次收货数量</label>
        <input id="receipt-qty" type="number" min={1}
          max={remaining.find(l=>l.id===receiveLine)?.orderedQuantity??1000000}
          value={receiveQty} onChange={e=>setReceiveQty(Number(e.target.value))}/>
        <button className="primary-button" type="button" disabled={!warehouseId||!receiveLine||
          !Number.isInteger(receiveQty)||receiveQty<1||
          receiveQty>(remaining.find(l=>l.id===receiveLine)?.orderedQuantity??0)-
            (remaining.find(l=>l.id===receiveLine)?.receivedQuantity??0)||receipt.isPending}
          onClick={()=>receipt.mutate()}>记录分批收货</button>
       </div>}
      </>}
   </div>}
  </section>
  <section className="panel full-panel" aria-label="本地仓库库存与出入库流水">
   <div className="panel-heading"><div><h2>本地仓库库存台账</h2>
    <p>只统计明确分配仓库的采购入库、人工出库、仓间调拨与盘点差异。
     台账余额不含未知历史收货、未记录的损耗、预留或 Amazon FBA 库存。</p></div></div>
   <WarehousePicker tenantId={tenantId} id="warehouse-stock-select"
    label="选择仓库查看库存（全库）" value={stockWarehouseId}
    onChange={id=>{setStockWarehouseId(id);setOperationSku('');
     setOperationDestination('');setOperationKey(crypto.randomUUID())}}/>
   {stockWarehouseId&&<>
    <WarehouseStockPagePanel key={tenantId+':'+stockWarehouseId}
     tenantId={tenantId} warehouseId={stockWarehouseId}/>
    <form className="team-invite-form" onSubmit={submitStockOperation}>
     <h3>人工更新本地库存（有审计、不可直接删除）</h3>
     <label htmlFor="stock-op-type">操作类型</label>
     <select id="stock-op-type" value={operationType}
      onChange={e=>{setOperationType(e.target.value as StockOperationRequest['type']);
       setOperationKey(crypto.randomUUID())}}>
      <option value="OUTBOUND">本地出库（扣减）</option>
      <option value="TRANSFER">仓间调拨（两仓同步记账）</option>
      <option value="COUNT">盘点差异（输入实盘数量）</option>
     </select>
     <InternalSkuPicker tenantId={tenantId} id="stock-op-sku"
      label="本地库存操作内部 SKU（全库选择）" value={operationSku}
      onChange={id=>{setOperationSku(id);setOperationKey(crypto.randomUUID())}}/>
     <p className="setup-hint" role="status">
      当前本地台账精确核验余额：{!operationSku?'请先选择 SKU':
       stockItem.isPending?'读取服务端观测中…':
       stockItem.isError?'余额未知，禁止写入（'+String(stockItem.error)+'）':
       currentOnHand===undefined?'余额未知，禁止写入':currentOnHand}。
      当前列表页没有此 SKU 不代表库存为 0；写入时服务器仍锁仓并重新核对余额，
      变化则要求刷新。</p>
     {operationType==='TRANSFER'&&
      <WarehousePicker tenantId={tenantId} id="stock-op-dest"
       label="目标仓库（全库，不能与来源相同）" value={operationDestination}
       excludeId={stockWarehouseId}
       onChange={id=>{setOperationDestination(id);setOperationKey(crypto.randomUUID())}}/>}
     <label htmlFor="stock-op-quantity">
      {operationType==='COUNT'?'盘点实数（允许 0）':'本次操作件数'}
     </label>
     <input id="stock-op-quantity" type="number" step={1}
      min={operationType==='COUNT'?0:1} max={operationType==='COUNT'?10000000:1000000}
      value={operationQuantity} onChange={e=>{
       setOperationQuantity(Number(e.target.value));setOperationKey(crypto.randomUUID())}}/>
     <label htmlFor="stock-op-reason">业务原因（至少 5 字符）</label>
     <input id="stock-op-reason" value={operationReason} minLength={5} maxLength={300}
      onChange={e=>{setOperationReason(e.target.value);setOperationKey(crypto.randomUUID())}}
      placeholder="例如：本地订单已出库／仓库盘点差异" required/>
     <button type="submit" className="primary-button" disabled={!operationReady}>
      确认写入本地库存流水</button>
    </form>
    <h3>本地库存流水（可翻阅全历史）</h3>
    <WarehouseLedgerTable key={tenantId+':'+stockWarehouseId}
     tenantId={tenantId} warehouseId={stockWarehouseId}/>

   </>}
  </section>
  <SupplierDisputesPanel key={'disputes-'+(selectedId||'no-order')}
   tenantId={tenantId} orderId={selectedId} lines={selected.data?.lines??[]}
   currentUserId={currentUserId}/>
  <LotEvidenceFilesPanel key={'files-'+(selectedId||'no-order')}
   tenantId={tenantId} currentUserId={currentUserId} warehouses={warehouses.data??[]}/>
  <LotDispositionProofPanel key={'proofs-'+(selectedId||'no-order')}
   tenantId={tenantId} currentUserId={currentUserId} warehouses={warehouses.data??[]}/>
  <LotExceptionPanel key={'exceptions-'+(selectedId||'no-order')}
   tenantId={tenantId} currentUserId={currentUserId} warehouses={warehouses.data??[]}/>
  <LotReconciliationPanel key={'reconciliation-'+(selectedId||'no-order')}
   tenantId={tenantId} currentUserId={currentUserId}
   warehouses={warehouses.data??[]}/>
  <LotLifecyclePanel key={'lifecycle-'+(selectedId||'no-order')}
   tenantId={tenantId} warehouses={warehouses.data??[]}/>
  <ReleasedLotStockPanel key={'released-'+(selectedId||'no-order')}
   tenantId={tenantId} orderId={selectedId} warehouses={warehouses.data??[]}/>
  <ReceiptLotLocationPanel key={'locations-'+(selectedId||'no-order')}
   tenantId={tenantId} orderId={selectedId} warehouses={warehouses.data??[]}/>
  <ReceiptBatchPanel key={'lot-'+(selectedId||'no-order')}
   tenantId={tenantId} orderId={selectedId}
   currentUserId={currentUserId} warehouses={warehouses.data??[]}/>
  <PurchaseQualityPanel key={'qc-'+(selectedId||'no-order')}
   tenantId={tenantId} orderId={selectedId} warehouses={warehouses.data??[]}/>
  <SupplierAsnPanel key={selectedId||'no-order'} tenantId={tenantId} orderId={selectedId}
   orderStatus={selected.data?.order.status??''}
   lines={selected.data?.lines??[]} warehouses={warehouses.data??[]}
   onOpenOrder={orderId=>{setSelectedId(orderId);setReceiveAsnId('')}}
   onPrepareReceipt={v=>{setReceiveAsnId(v.asnId);setReceiveLine(v.lineId);
    setWarehouseId(v.warehouseId);setReceiveQty(v.quantity);
    setMessage('已选择 ASN '+v.asnId.slice(0,8)+'；请核对实物收货件数后提交。')}}/>
  {stockWarehouseId&&<WarehouseAlertsPanel key={stockWarehouseId}
   tenantId={tenantId} warehouseId={stockWarehouseId} skus={activeSkus}/>}
  <SupplierPurchasePlanningPanel key={tenantId} tenantId={tenantId} warehouseId={stockWarehouseId}
   skus={activeSkus} suppliers={supplierOptions}
   onConverted={purchaseOrderId=>{setSelectedId(purchaseOrderId);refresh()}}/>
  {message&&<p className="panel-caption" role="status">{message}</p>}
  {failure&&<p className="setup-error" role="alert">{failure}</p>}
 </div>
}
