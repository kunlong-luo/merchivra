import {useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {fetchSkus} from './lib/catalog'
import {createSupplier,fetchSuppliers,createPurchaseOrder,fetchPurchaseOrders,
 fetchPurchaseOrder,purchaseAction,receivePurchase,type NewPurchaseLine} from './lib/purchasing'

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
 const [message,setMessage]=useState(''),[failure,setFailure]=useState('')
 const suppliers=useQuery({queryKey:['purchasing',tenantId,'suppliers'],queryFn:fetchSuppliers})
 const orders=useQuery({queryKey:['purchasing',tenantId,'orders'],queryFn:fetchPurchaseOrders})
 const skus=useQuery({queryKey:['catalog',tenantId,'for-purchase'],queryFn:()=>fetchSkus()})
 const selected=useQuery({queryKey:['purchasing',tenantId,'detail',selectedId],
  queryFn:()=>fetchPurchaseOrder(selectedId),enabled:!!selectedId})
 const refresh=()=>void client.invalidateQueries({queryKey:['purchasing',tenantId]})
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
 const receipt=useMutation({mutationFn:()=>receivePurchase(selectedId,receiveLine,receiveQty),
  onSuccess:d=>{setMessage('收货记录已追加：'+d.order.status);setFailure('');refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 function addSupplier(e:FormEvent){e.preventDefault();supplier.mutate()}
 function create(e:FormEvent){e.preventDefault();if(draft.length&&supplierId)created.mutate()}
 const remaining=selected.data?.lines.filter(l=>l.receivedQuantity<l.orderedQuantity)??[]
 const supplierOptions=suppliers.data?.filter(s=>s.status==='ACTIVE')??[]
 const activeSkus=skus.data?.filter(s=>s.status==='ACTIVE')??[]
 return <div className="core-workspace">
  <div className="core-intro"><strong>采购与供应商 · 本地业务</strong><p>
   支持采购草稿、提交、独立管理员审批与分批收货。采购数量不得冒充 FBA 已入仓库存；
   金额仅为原币采购约定，并非 Amazon 财务结算。</p></div>
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
        <label htmlFor="receipt-line">待收货明细</label>
        <select id="receipt-line" value={receiveLine} onChange={e=>setReceiveLine(e.target.value)}>
         <option value="">选择未收完的行</option>
         {remaining.map(l=><option value={l.id} key={l.id}>
          {activeSkus.find(s=>s.id===l.productSkuId)?.internalSku??l.productSkuId}
          {' · 剩余 '+(l.orderedQuantity-l.receivedQuantity)}</option>)}
        </select>
        <label htmlFor="receipt-qty">本次收货数量</label>
        <input id="receipt-qty" type="number" min={1}
          max={remaining.find(l=>l.id===receiveLine)?.orderedQuantity??1000000}
          value={receiveQty} onChange={e=>setReceiveQty(Number(e.target.value))}/>
        <button className="primary-button" type="button" disabled={!receiveLine||
          !Number.isInteger(receiveQty)||receiveQty<1||
          receiveQty>(remaining.find(l=>l.id===receiveLine)?.orderedQuantity??0)-
            (remaining.find(l=>l.id===receiveLine)?.receivedQuantity??0)||receipt.isPending}
          onClick={()=>receipt.mutate()}>记录分批收货</button>
       </div>}
      </>}
   </div>}
  </section>
  {message&&<p className="panel-caption" role="status">{message}</p>}
  {failure&&<p className="setup-error" role="alert">{failure}</p>}
 </div>
}
