import {useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import type {PurchaseLine,LocalWarehouse} from './lib/purchasing'
import {
 fetchSupplierAsns,createSupplierAsn,supplierAsnAction,updateSupplierAsnEta,
 fetchSupplierAsnFindings,scanSupplierAsns,supplierAsnFindingState,
 type NewSupplierAsn,type SupplierAsnFindingState
} from './lib/supplier-asns'

const ruleLabels:Record<string,string>={
 SUPPLIER_ASN_OVERDUE:'供应商申报货件预计到货日期已过',
 SUPPLIER_ASN_DUE_SOON:'供应商申报货件预计三日内到货'
}
const findingLabels:Record<string,string>={
 OPEN:'待处理',ACKNOWLEDGED:'已认领',DONE:'人工已处理',CLEARED:'条件已消失'
}
export default function SupplierAsnPanel({tenantId,orderId,orderStatus,lines,
 warehouses,onPrepareReceipt}:{
 tenantId:string;orderId:string;orderStatus:string;lines:PurchaseLine[];
 warehouses:LocalWarehouse[];
 onPrepareReceipt:(value:{asnId:string;lineId:string;warehouseId:string;quantity:number})=>void
}){
 const client=useQueryClient()
 const [lineId,setLineId]=useState(''),[warehouseId,setWarehouseId]=useState('')
 const [quantity,setQuantity]=useState(1)
 const [eta,setEta]=useState(()=>new Date(Date.now()+86400000).toISOString().slice(0,10))
 const [tracking,setTracking]=useState('')
 const [requestKey,setRequestKey]=useState(()=>crypto.randomUUID())
 const [editEta,setEditEta]=useState<Record<string,string>>({})
 const [editReason,setEditReason]=useState<Record<string,string>>({})
 const [filter,setFilter]=useState<'ACTIVE'|'ALL'>('ACTIVE')
 const [message,setMessage]=useState(''),[failure,setFailure]=useState('')
 const [editing,setEditing]=useState('')
 const root=['purchasing',tenantId]
 const asns=useQuery({queryKey:[...root,'asns',orderId],
  queryFn:()=>fetchSupplierAsns(orderId),enabled:!!orderId,retry:false})
 const findings=useQuery({queryKey:[...root,'asn-findings',filter],
  queryFn:()=>fetchSupplierAsnFindings(filter),retry:false})
 const refresh=()=>void client.invalidateQueries({queryKey:root})
 const created=useMutation({
  mutationFn:(body:NewSupplierAsn)=>createSupplierAsn(orderId,body),
  onSuccess:()=>{setMessage('供应商预到货通知已创建；这还不是发货证据');
   setFailure('');setRequestKey(crypto.randomUUID());setTracking('');refresh()},
  onError:(error:Error)=>setFailure(error.message)})
 const action=useMutation({
  mutationFn:({id,kind}:{id:string;kind:'ship'|'cancel'})=>
   supplierAsnAction(orderId,id,kind),
  onSuccess:()=>{setMessage('供应商 ASN 状态已更新；不代表承运商验证或仓库入库');
   setFailure('');refresh()},
  onError:(error:Error)=>setFailure(error.message)})
 const changedEta=useMutation({
  mutationFn:({id,previous,next,reason}:{id:string;previous:string;next:string;reason:string})=>
   updateSupplierAsnEta(orderId,id,next,previous,reason),
  onSuccess:()=>{setMessage('供应商申报到货日期已更新，并保留变更审计');
   setFailure('');setEditReason({});refresh()},
  onError:(error:Error)=>setFailure(error.message)})
 const scan=useMutation({mutationFn:scanSupplierAsns,
  onSuccess:r=>{setMessage('本地 ASN 巡检完成：匹配 '+r.matchedCount+
   ' 项，清除 '+r.clearedCount+' 项');setFailure('');refresh()},
  onError:(error:Error)=>setFailure(error.message)})
 const changeFinding=useMutation({
  mutationFn:({id,state}:{id:string;state:SupplierAsnFindingState})=>
   supplierAsnFindingState(id,state),
  onMutate:a=>setEditing(a.id),
  onSuccess:()=>{setMessage('到货异常待办状态已保存');setFailure('');refresh()},
  onError:(error:Error)=>setFailure(error.message),
  onSettled:()=>setEditing('')})
 const activeOrder=['APPROVED','RECEIVING'].includes(orderStatus)
 const line=lines.find(l=>l.id===lineId)
 const remaining=line?line.orderedQuantity-line.receivedQuantity:0
 const canCreate=activeOrder&&!!line&&!!warehouseId&&
  warehouses.some(w=>w.id===warehouseId&&w.status==='ACTIVE')&&
  Number.isInteger(quantity)&&quantity>0&&quantity<=Math.min(remaining,1000000)&&
  /^\d{4}-\d{2}-\d{2}$/.test(eta)&&tracking.length<=120&&!created.isPending
 function submit(e:FormEvent){
  e.preventDefault()
  if(!canCreate)return
  if(!window.confirm('确定记录供应商预到货通知？这不是实际发货或入仓确认。'))return
  created.mutate({requestKey,lineId,warehouseId,quantity,
   expectedArrivalDate:eta,trackingReference:tracking.trim()||null})
 }
 return <section className="panel full-panel" aria-label="供应商预到货通知与交期异常">
  <div className="panel-heading"><div><h2>供应商 ASN 预到货与交期跟踪</h2>
   <p>ASN 是人工登记的供应商通知。只有显式申报发货后才能关联实物收货；
    关联收货以本地收货台账为准，不是 Amazon FBA 货件状态。</p></div>
   <button className="refresh-button" type="button" onClick={refresh}>刷新</button></div>
  {!orderId?<p className="setup-hint">在采购单列表选择一张已审批采购单，填写供应商 ASN。</p>:<>
   <p className="setup-hint">当前采购单状态：{orderStatus}。
    只有 APPROVED 或 RECEIVING 可建立 ASN。</p>
   {activeOrder&&<form className="team-invite-form" onSubmit={submit}>
    <label htmlFor="asn-line">采购单 SKU 明细</label>
    <select id="asn-line" value={lineId} onChange={e=>{setLineId(e.target.value);
     setRequestKey(crypto.randomUUID())}}>
     <option value="">选择采购明细</option>
     {lines.filter(l=>l.receivedQuantity<l.orderedQuantity).map(l=>
      <option key={l.id} value={l.id}>{l.productSkuId} ·
       剩余可收 {l.orderedQuantity-l.receivedQuantity}</option>)}
    </select>
    <label htmlFor="asn-warehouse">预计入库的本地仓库</label>
    <select id="asn-warehouse" value={warehouseId}
     onChange={e=>{setWarehouseId(e.target.value);setRequestKey(crypto.randomUUID())}}>
     <option value="">选择仓库</option>
     {warehouses.filter(w=>w.status==='ACTIVE').map(w=>
      <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
    </select>
    <label htmlFor="asn-quantity">供应商预计发货件数</label>
    <input id="asn-quantity" type="number" min={1} max={Math.max(1,remaining)}
     value={quantity} onChange={e=>{setQuantity(Number(e.target.value));
      setRequestKey(crypto.randomUUID())}}/>
    <label htmlFor="asn-eta">申报预计到货日期（非承诺）</label>
    <input id="asn-eta" type="date" value={eta}
     onChange={e=>{setEta(e.target.value);setRequestKey(crypto.randomUUID())}}/>
    <label htmlFor="asn-tracking">供应商发货参考号（可选）</label>
    <input id="asn-tracking" maxLength={120} value={tracking}
     onChange={e=>{setTracking(e.target.value);setRequestKey(crypto.randomUUID())}}/>
    <button className="primary-button" type="submit" disabled={!canCreate}>
     保存 ASN 预到货通知</button>
   </form>}
   <h3>采购单 ASN 与实际本地收货</h3>
   {asns.isPending?<p className="empty-state">读取预到货通知…</p>:
    asns.isError?<p className="setup-error" role="alert">{String(asns.error)}</p>:
    !asns.data?.length?<p className="empty-state">当前采购单暂无供应商 ASN 记录。</p>:
    <div className="table-scroll" role="region" tabIndex={0} aria-label="供应商 ASN 列表">
     <table><thead><tr><th scope="col">发货参考 / 目标仓</th>
      <th scope="col">ASN 件数 / 实际已收</th><th scope="col">申报 ETA</th>
      <th scope="col">供应商申报状态</th><th scope="col">操作</th></tr></thead><tbody>
      {asns.data.map(a=><tr key={a.id}>
       <td>{a.trackingReference??a.id.slice(0,8)}
        <div className="secondary-cell">
         {warehouses.find(w=>w.id===a.warehouseId)?.code??a.warehouseId}</div></td>
       <td>{a.quantity} / {a.receivedQuantity}</td>
       <td>{a.expectedArrivalDate}</td>
       <td>{a.status==='SHIPPED'?'供应商申报已发货':
        a.status==='DRAFT'?'通知草稿':'已取消'}</td>
       <td><div className="sandbox-actions">
        {a.status==='DRAFT'&&activeOrder&&
         <button type="button" className="primary-button" disabled={action.isPending}
          onClick={()=>{if(window.confirm('确认供应商已报告发货？此操作不验证承运商物流状态。'))
           action.mutate({id:a.id,kind:'ship'})}}>标记供应商已发货</button>}
        {a.status!=='CANCELLED'&&activeOrder&&
         <button type="button" className="text-button" disabled={action.isPending}
          onClick={()=>{if(window.confirm('取消 ASN？已有实际关联收货时会拒绝。'))
           action.mutate({id:a.id,kind:'cancel'})}}>取消通知</button>}
        {a.status==='SHIPPED'&&a.receivedQuantity<a.quantity&&activeOrder&&
         <button type="button" className="refresh-button" onClick={()=>{
          const l=lines.find(l=>l.id===a.lineId)
          const available=l?l.orderedQuantity-l.receivedQuantity:0
          if(available>0)onPrepareReceipt({asnId:a.id,lineId:a.lineId,
           warehouseId:a.warehouseId,
           quantity:Math.min(available,a.quantity-a.receivedQuantity)})
         }}>选择关联收货</button>}
        {a.status!=='CANCELLED'&&activeOrder&&
         <div className="team-invite-form">
          <label htmlFor={'asn-eta-update-'+a.id}>调整申报 ETA</label>
          <input id={'asn-eta-update-'+a.id} type="date"
           value={editEta[a.id]??a.expectedArrivalDate}
           onChange={e=>setEditEta(v=>({...v,[a.id]:e.target.value}))}/>
          <label htmlFor={'asn-eta-reason-'+a.id}>调整原因</label>
          <input id={'asn-eta-reason-'+a.id} value={editReason[a.id]??''}
           minLength={5} maxLength={300} placeholder="至少 5 字符"
           onChange={e=>setEditReason(v=>({...v,[a.id]:e.target.value}))}/>
          <button type="button" className="refresh-button"
           disabled={changedEta.isPending||(editEta[a.id]??a.expectedArrivalDate)
            ===a.expectedArrivalDate||(editReason[a.id]??'').trim().length<5}
           onClick={()=>changedEta.mutate({id:a.id,previous:a.expectedArrivalDate,
            next:editEta[a.id],reason:editReason[a.id].trim()})}>保存 ETA</button>
         </div>}
       </div></td>
      </tr>)}
     </tbody></table>
    </div>}
  </>}
  <div className="panel-heading"><div><h3>供应商预到货异常待办</h3>
   <p>仅对申报已发货且尚未完全关联收货的 ASN 提醒，预计三日内到货或已逾期。
    定时巡检默认关闭。</p></div>
   <button type="button" className="primary-button" disabled={scan.isPending}
    onClick={()=>scan.mutate()}>{scan.isPending?'巡检中…':'巡检当前租户 ASN'}</button></div>
  <label htmlFor="asn-findings-filter">待办状态</label>
  <select id="asn-findings-filter" value={filter}
   onChange={e=>setFilter(e.target.value as 'ACTIVE'|'ALL')}>
   <option value="ACTIVE">待处理 / 已认领</option><option value="ALL">全部</option>
  </select>
  {findings.isPending?<p className="empty-state">加载 ASN 待办…</p>:
   findings.isError?<p className="setup-error" role="alert">{String(findings.error)}</p>:
   !findings.data?.length?<p className="empty-state">当前没有该状态的 ASN 异常。
    首次使用请手动巡检。</p>:
   <div className="table-scroll" role="region" tabIndex={0} aria-label="ASN 到货待办">
    <table><thead><tr><th scope="col">ASN / 参考号</th>
     <th scope="col">异常</th><th scope="col">申报 ETA</th>
     <th scope="col">状态</th><th scope="col">操作</th></tr></thead><tbody>
     {findings.data.map(f=><tr key={f.id}>
      <td>{f.trackingReference??f.asnId.slice(0,8)}</td>
      <td>{ruleLabels[f.ruleCode]??f.ruleCode}</td>
      <td>{f.expectedArrivalDate}</td>
      <td>{findingLabels[f.state]??f.state}</td>
      <td><div className="sandbox-actions">
       <button type="button" className="refresh-button" onClick={()=>{
        // Parent selection is changed by browsing its purchase orders.
        window.scrollTo({top:0,behavior:'smooth'})
       }} title="可在上方采购单列表打开关联订单">查看采购单列表</button>
       {f.state==='OPEN'&&<button type="button" className="text-button"
        disabled={!!editing} onClick={()=>changeFinding.mutate({
         id:f.id,state:'ACKNOWLEDGED'})}>认领</button>}
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
