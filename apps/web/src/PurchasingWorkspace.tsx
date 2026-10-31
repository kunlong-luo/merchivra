import {useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {fetchSkus} from './lib/catalog'
import WarehouseAlertsPanel from './WarehouseAlertsPanel'
import SupplierPurchasePlanningPanel from './SupplierPurchasePlanningPanel'
import SupplierAsnPanel from './SupplierAsnPanel'
import {createSupplier,fetchSuppliers,createPurchaseOrder,fetchPurchaseOrders,
 fetchPurchaseOrder,purchaseAction,receivePurchase,fetchWarehouses,createWarehouse,
 fetchWarehouseStock,fetchWarehouseLedger,fetchPendingReceipts,postStockOperation,
 type NewPurchaseLine,type StockOperationRequest} from './lib/purchasing'

export default function PurchasingWorkspace({tenantId,currentUserId}:{
 tenantId:string;currentUserId:string
}){
 const client=useQueryClient()
 const [supplierCode,setSupplierCode]=useState(''),[supplierName,setSupplierName]=useState('')
 const [supplierId,setSupplierId]=useState(''),[currency,setCurrency]=useState('USD')
 const [notes,setNotes]=useState(''),[sku,setSku]=useState('')
 const [qty,setQty]=useState(1),[unitCost,setUnitCost]=useState('')
 const [draft,setDraft]=useState<NewPurchaseLine[]>([])
 const [selectedId,setSelectedId]=useState(''),[receiveLine,setReceiveLine]=useState('')
 const [receiveQty,setReceiveQty]=useState(1)
 const [receiveAsnId,setReceiveAsnId]=useState('')
 const [warehouseCode,setWarehouseCode]=useState(''),[warehouseName,setWarehouseName]=useState('')
 const [warehouseId,setWarehouseId]=useState(''),[stockWarehouseId,setStockWarehouseId]=useState('')
 const [operationType,setOperationType]=useState<StockOperationRequest['type']>('OUTBOUND')
 const [operationSku,setOperationSku]=useState('')
 const [operationDestination,setOperationDestination]=useState('')
 const [operationQuantity,setOperationQuantity]=useState(1)
 const [operationReason,setOperationReason]=useState('')
 const [operationKey,setOperationKey]=useState(()=>crypto.randomUUID())
 const [message,setMessage]=useState(''),[failure,setFailure]=useState('')
 const warehouses=useQuery({queryKey:['purchasing',tenantId,'warehouses'],queryFn:fetchWarehouses})
 const stock=useQuery({queryKey:['purchasing',tenantId,'stock',stockWarehouseId],
  queryFn:()=>fetchWarehouseStock(stockWarehouseId),enabled:!!stockWarehouseId,retry:false})
 const movements=useQuery({queryKey:['purchasing',tenantId,'ledger',stockWarehouseId],
  queryFn:()=>fetchWarehouseLedger(stockWarehouseId),enabled:!!stockWarehouseId,retry:false})
 const pending=useQuery({queryKey:['purchasing',tenantId,'pending'],
  queryFn:fetchPendingReceipts,retry:false})
 const suppliers=useQuery({queryKey:['purchasing',tenantId,'suppliers'],queryFn:fetchSuppliers})
 const orders=useQuery({queryKey:['purchasing',tenantId,'orders'],queryFn:fetchPurchaseOrders})
 const skus=useQuery({queryKey:['catalog',tenantId,'for-purchase'],queryFn:()=>fetchSkus()})
 const selected=useQuery({queryKey:['purchasing',tenantId,'detail',selectedId],
  queryFn:()=>fetchPurchaseOrder(selectedId),enabled:!!selectedId})
 const refresh=()=>void client.invalidateQueries({queryKey:['purchasing',tenantId]})
 const warehouseCreated=useMutation({
  mutationFn:()=>createWarehouse(warehouseCode,warehouseName),
  onSuccess:w=>{setWarehouseCode('');setWarehouseName('');setWarehouseId(w.id);
    setStockWarehouseId(w.id);setMessage('本地仓库已创建：'+w.code);setFailure('');refresh()},
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
  onSuccess:d=>{setMessage('本地仓库收货和库存流水已追加：'+d.order.status);
    setReceiveAsnId('');setFailure('');refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 function addSupplier(e:FormEvent){e.preventDefault();supplier.mutate()}
 function addWarehouse(e:FormEvent){e.preventDefault();warehouseCreated.mutate()}
 function create(e:FormEvent){e.preventDefault();if(draft.length&&supplierId)created.mutate()}
 const remaining=selected.data?.lines.filter(l=>l.receivedQuantity<l.orderedQuantity)??[]
 const supplierOptions=suppliers.data?.filter(s=>s.status==='ACTIVE')??[]
 const activeSkus=skus.data?.filter(s=>s.status==='ACTIVE')??[]
 const activeWarehouses=warehouses.data?.filter(w=>w.status==='ACTIVE')??[]
 const currentOnHand=stock.data?.find(s=>s.productSkuId===operationSku)?.onHandQuantity??0
 const quantityValid=Number.isSafeInteger(operationQuantity)&&
  (operationType==='COUNT'?(operationQuantity>=0&&operationQuantity<=10000000
    &&operationQuantity!==currentOnHand):
   (operationQuantity>=1&&operationQuantity<=1000000&&operationQuantity<=currentOnHand))
 const operationReady=!!stockWarehouseId&&!!operationSku&&!!stock.data&&
  activeSkus.some(s=>s.id===operationSku)&&
  activeWarehouses.some(w=>w.id===stockWarehouseId)&&
  (operationType!=='TRANSFER'||activeWarehouses.some(w=>w.id===operationDestination
    &&w.id!==stockWarehouseId))&&quantityValid&&
  operationReason.trim().length>=5&&operationReason.trim().length<=300&&
  !stock.isFetching&&!stockOperation.isPending
 function submitStockOperation(e:FormEvent){
  e.preventDefault()
  if(!operationReady)return
  if(!window.confirm('确认更新本地仓库库存流水？该操作不能通过删除记录撤销；不会同步 Amazon。'))return
  stockOperation.mutate({requestKey:operationKey,type:operationType,
   warehouseId:stockWarehouseId,destinationWarehouseId:operationType==='TRANSFER'
    ?operationDestination:null,productSkuId:operationSku,quantity:operationQuantity,
   expectedOnHand:currentOnHand,reason:operationReason.trim()})
 }
 return <div className="core-workspace">
  <div className="core-intro"><strong>采购与供应商 · 本地业务</strong><p>
   支持采购审批、指定本地仓库后分批收货，并记录不可覆盖的入库流水。
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
    <p className="setup-hint">已创建 {warehouses.data?.length??0} 个仓库；请在采购收货时选择实际接收仓库。</p>}
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
   {suppliers.isError&&<p className="setup-error">{String(suppliers.error)}</p>}
   <p className="setup-hint">已登记供应商：{suppliers.data?.length??0}</p>
  </section>
  <section className="panel full-panel"><div className="panel-heading"><h2>创建采购单草稿</h2></div>
   <div className="team-invite-form">
    <label htmlFor="purchase-supplier">供应商</label>
    <select id="purchase-supplier" value={supplierId}
      onChange={e=>setSupplierId(e.target.value)}>
     <option value="">选择供应商</option>
     {supplierOptions.map(s=><option value={s.id} key={s.id}>{s.code} · {s.name}</option>)}
    </select>
    <label htmlFor="purchase-currency">币种</label>
    <select id="purchase-currency" value={currency} onChange={e=>setCurrency(e.target.value)}>
     {['USD','EUR','GBP','JPY','CNY','CAD','AUD'].map(c=><option value={c} key={c}>{c}</option>)}
    </select>
    <label htmlFor="purchase-notes">备注（不填敏感数据）</label>
    <input id="purchase-notes" value={notes} onChange={e=>setNotes(e.target.value)}
      maxLength={300}/>
    <label htmlFor="purchase-sku">添加内部 SKU 行</label>
    <select id="purchase-sku" value={sku} onChange={e=>setSku(e.target.value)}>
     <option value="">选择 SKU</option>
     {activeSkus.filter(s=>!draft.some(d=>d.productSkuId===s.id)).map(s=>
       <option key={s.id} value={s.id}>{s.internalSku}</option>)}
    </select>
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
      <td>{activeSkus.find(s=>s.id===l.productSkuId)?.internalSku??l.productSkuId}</td>
      <td>{l.quantity}</td><td>{currency} {l.unitCost}</td>
      <td><button className="text-button" type="button"
       onClick={()=>setDraft(v=>v.filter(x=>x.productSkuId!==l.productSkuId))}>移除</button></td>
     </tr>)}</tbody></table>
   </div>
   <form onSubmit={create}><button type="submit" className="primary-button"
     disabled={!supplierId||!draft.length||created.isPending}>保存采购单草稿</button></form>
  </section>
  <section className="panel full-panel" aria-label="已审批待收货">
   <div className="panel-heading"><div><h2>已审批待收货</h2>
    <p>仅统计 APPROVED / RECEIVING 采购单的剩余数量，不代表供应商已发货或真实在途。</p></div></div>
   {pending.isPending?<p className="empty-state">读取待收货记录中…</p>:
    pending.isError?<p className="setup-error" role="alert">{String(pending.error)}</p>:
    !pending.data?.length?<p className="empty-state">当前没有已审批的待收货明细。</p>:
    <div className="table-scroll" role="region" tabIndex={0} aria-label="采购待收货明细">
     <table><thead><tr><th scope="col">采购单号</th><th scope="col">内部 SKU</th>
      <th scope="col">已收 / 订购</th><th scope="col">待收货</th>
      <th scope="col">原币单价</th><th scope="col">操作</th></tr></thead><tbody>
      {pending.data.map(p=><tr key={p.purchaseOrderLineId}>
       <td>{p.orderNumber}</td><td>{p.internalSku}</td>
       <td>{p.receivedQuantity} / {p.orderedQuantity}</td>
       <td>{p.remainingQuantity}</td><td>{p.currency} {p.unitCost}</td>
       <td><button type="button" className="refresh-button" onClick={()=>{
        setSelectedId(p.purchaseOrderId);setReceiveLine(p.purchaseOrderLineId)}}>打开收货</button></td>
      </tr>)}
     </tbody></table>
    </div>}
  </section>
  <section className="panel full-panel"><div className="panel-heading"><div><h2>采购单列表与流程</h2>
   <p>批准人必须是另一名 Owner；收货可以分批，但不能超过每行订购数量。</p></div>
   <button className="refresh-button" onClick={refresh}>刷新</button></div>
   {orders.isError&&<p className="setup-error">{String(orders.error)}</p>}
   <div className="table-scroll" role="region" tabIndex={0} aria-label="采购单列表">
    <table><thead><tr><th scope="col">采购单号</th><th scope="col">状态</th>
      <th scope="col">币种</th><th scope="col">操作</th></tr></thead>
     <tbody>{(orders.data??[]).map(o=><tr key={o.id}><td>{o.orderNumber}</td>
      <td>{o.status}</td><td>{o.currency}</td>
      <td><div className="sandbox-actions">
       <button className="refresh-button" type="button"
        onClick={()=>setSelectedId(o.id)}>明细</button>
       {o.status==='DRAFT'&&<button className="refresh-button" type="button"
        disabled={action.isPending}
        onClick={()=>action.mutate({id:o.id,kind:'submit'})}>提交审批</button>}
       {o.status==='SUBMITTED'&&o.createdBy!==currentUserId&&
        <button className="primary-button" type="button" disabled={action.isPending}
          onClick={()=>action.mutate({id:o.id,kind:'approve'})}>审批通过</button>}
       {['DRAFT','SUBMITTED','APPROVED'].includes(o.status)&&
        <button className="text-button" type="button" disabled={action.isPending}
         onClick={()=>{if(window.confirm('确认取消此采购单？'))
          action.mutate({id:o.id,kind:'cancel'})}}>取消</button>}
      </div></td></tr>)}</tbody></table>
   </div>
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
        <label htmlFor="receipt-warehouse">实际收货仓库（必选）</label>
        <select id="receipt-warehouse" value={warehouseId}
         onChange={e=>{setWarehouseId(e.target.value);setReceiveAsnId('')}}>
         <option value="">选择本地仓库</option>
         {activeWarehouses.map(w=><option value={w.id} key={w.id}>
          {w.code} · {w.name}</option>)}
        </select>
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
   <label htmlFor="warehouse-stock-select">选择仓库查看</label>
   <select id="warehouse-stock-select" value={stockWarehouseId}
    onChange={e=>{setStockWarehouseId(e.target.value);setOperationSku('');
     setOperationDestination('');setOperationKey(crypto.randomUUID())}}>
    <option value="">选择仓库</option>
    {(warehouses.data??[]).map(w=><option key={w.id} value={w.id}>
     {w.code} · {w.name}</option>)}
   </select>
   {stockWarehouseId&&<>
    {stock.isPending?<p className="empty-state">加载本地库存台账…</p>:
     stock.isError?<p className="setup-error" role="alert">{String(stock.error)}</p>:
     !stock.data?.length?<p className="empty-state">所选仓库还没有已入账的库存流水。</p>:
     <div className="table-scroll" role="region" tabIndex={0} aria-label="本地库存台账余额">
      <table><thead><tr><th scope="col">内部 SKU</th>
       <th scope="col">历史采购收货</th><th scope="col">本地台账余额</th></tr></thead><tbody>
       {stock.data.map(s=><tr key={s.productSkuId}><td>{s.internalSku}</td>
        <td>{s.recordedReceivedQuantity}</td><td>{s.onHandQuantity}</td></tr>)}
      </tbody></table>
     </div>}
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
     <label htmlFor="stock-op-sku">内部 SKU</label>
     <select id="stock-op-sku" value={operationSku}
      onChange={e=>{setOperationSku(e.target.value);setOperationKey(crypto.randomUUID())}}>
      <option value="">选择内部 SKU</option>
      {activeSkus.map(s=><option key={s.id} value={s.id}>{s.internalSku}</option>)}
     </select>
     <p className="setup-hint">当前本地台账余额：{operationSku?currentOnHand:'请先选择 SKU'}。
      操作时服务端重新验证余额，变化则要求刷新。</p>
     {operationType==='TRANSFER'&&<>
      <label htmlFor="stock-op-dest">目标仓库</label>
      <select id="stock-op-dest" value={operationDestination}
       onChange={e=>{setOperationDestination(e.target.value);setOperationKey(crypto.randomUUID())}}>
       <option value="">选择目标仓库</option>
       {activeWarehouses.filter(w=>w.id!==stockWarehouseId).map(w=>
        <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
      </select>
     </>}
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
    <h3>最近 100 条本地库存流水</h3>
    {movements.isPending?<p className="empty-state">加载流水中…</p>:
     movements.isError?<p className="setup-error" role="alert">{String(movements.error)}</p>:
     !movements.data?.length?<p className="empty-state">暂无入库流水。</p>:
     <div className="table-scroll" role="region" tabIndex={0} aria-label="仓库完整库存流水">
      <table><thead><tr><th scope="col">时间</th><th scope="col">内部 SKU</th>
       <th scope="col">变动</th><th scope="col">来源</th><th scope="col">原因</th></tr></thead><tbody>
       {movements.data.map(m=><tr key={m.id}>
        <td>{new Date(m.createdAt).toLocaleString('zh-CN')}</td>
        <td>{m.internalSku}</td><td>{m.quantity>0?'+':''}{m.quantity}</td>
        <td>{m.movementType} · {m.purchaseReceiptId??m.operationId??'无来源'}</td>
        <td>{m.reason??'采购已审批收货'}</td>
       </tr>)}
      </tbody></table>
     </div>}
   </>}
  </section>
  <SupplierAsnPanel key={selectedId||'no-order'} tenantId={tenantId} orderId={selectedId}
   orderStatus={selected.data?.order.status??''}
   lines={selected.data?.lines??[]} warehouses={warehouses.data??[]}
   onOpenOrder={orderId=>{setSelectedId(orderId);setReceiveAsnId('')}}
   onPrepareReceipt={v=>{setReceiveAsnId(v.asnId);setReceiveLine(v.lineId);
    setWarehouseId(v.warehouseId);setReceiveQty(v.quantity);
    setMessage('已选择 ASN '+v.asnId.slice(0,8)+'；请核对实物收货件数后提交。')}}/>
  {stockWarehouseId&&<WarehouseAlertsPanel key={stockWarehouseId}
   tenantId={tenantId} warehouseId={stockWarehouseId} skus={activeSkus}/>}
  <SupplierPurchasePlanningPanel tenantId={tenantId} warehouseId={stockWarehouseId}
   skus={activeSkus} suppliers={supplierOptions}
   onConverted={purchaseOrderId=>{setSelectedId(purchaseOrderId);refresh()}}/>
  {message&&<p className="panel-caption" role="status">{message}</p>}
  {failure&&<p className="setup-error" role="alert">{failure}</p>}
 </div>
}
