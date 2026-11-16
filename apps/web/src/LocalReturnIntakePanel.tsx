import {useCallback,useState} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import WarehousePicker from './WarehousePicker'
import {getLocalReturnIntakes,requestLocalReturnIntake,
 decideLocalReturnIntake,auditLocalReturnIntake,type LocalReturnCursor}
 from './lib/customer-return-intakes'

const conditionLabels:Record<string,string>={
 SELLABLE_CLAIMED:'人工声称可售',UNSELLABLE_CLAIMED:'人工声称不可售',
 UNKNOWN:'暂无法确认'}
const statuses:Record<string,string>={
 PENDING:'待独立实收核对',ATTESTED_LOCAL:'本地双人声明已核对（未认证 WMS）',
 REJECTED:'实收记录被驳回',STALE:'V63/V171 来源已变更'}
const noteValid=(s:string)=>s.trim().length>=10&&s.length<=500&&
 !/[\x00-\x1F\x7F]/.test(s)
export default function LocalReturnIntakePanel({tenantId,marketplaceId,eventId}:{
 tenantId:string;marketplaceId:string;eventId:string
}) {
 const qc=useQueryClient()
 const [history,setHistory]=useState<Array<LocalReturnCursor|undefined>>([undefined])
 const [claimId,setClaimId]=useState('')
 const [warehouseId,setWarehouseId]=useState('')
 const onWarehouse=useCallback((id:string)=>setWarehouseId(id),[])
 const [receiptReference,setReceiptReference]=useState('')
 const [quantity,setQuantity]=useState(1)
 const [condition,setCondition]=useState<'SELLABLE_CLAIMED'|'UNSELLABLE_CLAIMED'|'UNKNOWN'>('UNKNOWN')
 const [note,setNote]=useState('')
 const [reason,setReason]=useState('')
 const [auditId,setAuditId]=useState<string|null>(null)
 const key=['finance','customer-return-intakes',tenantId,marketplaceId,eventId]
 const cursor=history.at(-1)
 const data=useQuery({queryKey:[...key,cursor],
  queryFn:()=>getLocalReturnIntakes(marketplaceId,eventId,cursor),retry:false})
 const audit=useQuery({queryKey:[...key,'audit',auditId],
  enabled:!!auditId,queryFn:()=>auditLocalReturnIntake(marketplaceId,eventId,auditId!),
  retry:false})
 const reset=()=>{setHistory([undefined]);void qc.invalidateQueries({queryKey:key})}
 const create=useMutation({mutationFn:()=>requestLocalReturnIntake(marketplaceId,eventId,{
  claimId,warehouseId,receiptReference,quantity,condition,note}),
  onSuccess:()=>{setNote('');setReceiptReference('');reset()}})
 const decide=useMutation({mutationFn:({id,action}:{id:string;action:'attest'|'reject'})=>
  decideLocalReturnIntake(marketplaceId,eventId,id,action,reason),
  onSuccess:()=>{setReason('');reset()}})
 const selected=data.data?.coverage.find(x=>x.claimId===claimId)
 const validRef=/^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$/.test(receiptReference)
 const canCreate=!!selected&&selected.evidenceCurrent&&!!warehouseId&&
  Number.isInteger(quantity)&&quantity>0&&quantity<=selected.remainingQuantity&&
  validRef&&noteValid(note)
 return <section className="panel full-panel" aria-label="V172 本地仓退货实收候选对账">
  <div className="panel-heading"><div><h3>V172 · 本地仓退货包裹实收候选与数量核对</h3>
   <p>分包裹记录消费者退货的本地仓库、包裹参考号、数量和处置；按原订单退款商品合计预留、人工核对和剩余数量。与采购入仓流水严格分离。</p>
  </div><button className="refresh-button" type="button"
   disabled={data.isFetching} onClick={()=>void data.refetch()}>刷新实收对账</button></div>
  <p className="setup-hint">本地双人记录不等于经过 WMS、Amazon FBA/FBM 独立签名的实收证明。库存入账、可售确认、COGS 冲回、真实净利润全部未验证。</p>
  {data.isError?<p role="alert" className="setup-error">{String(data.error)}</p>:
   data.isPending?<p>读取原退款项与本地实收候选记录…</p>:
   <>
    <div className="table-scroll" role="region" tabIndex={0} aria-label="退货包裹数量保守核对">
     <table><thead><tr>{['V171 核查项','人工申报件数','待核对包裹件数',
      '本地已核对件数','剩余件数','实物来源认证'].map(h=>
       <th key={h} scope="col">{h}</th>)}</tr></thead>
      <tbody>{data.data?.coverage.map(x=><tr key={x.claimId}>
       <td>{x.itemId} · {x.claimId.slice(0,8)}
        {!x.evidenceCurrent?'（证据过期）':''}</td>
       <td>{x.manualClaimedQuantity}</td><td>{x.pendingQuantity}</td>
       <td>{x.locallyAttestedQuantity}</td><td>{x.remainingQuantity}</td>
       <td>未核实第三方 WMS</td>
      </tr>)}</tbody></table></div>
    {data.data?.coverage.length===0&&<p className="empty-state">无当前 V171 核查单；没有可信入库证据不等于零件退货。</p>}
    <div className="core-controls">
     <label htmlFor="v172-claim">选择已完成 V171 双人核查的原退款商品</label>
     <select id="v172-claim" value={claimId} onChange={e=>setClaimId(e.target.value)}>
      <option value="">选择 V171 核查单</option>
      {data.data?.coverage.filter(x=>x.evidenceCurrent).map(x=><option
       key={x.claimId} value={x.claimId}>{x.itemId} · 剩余 {x.remainingQuantity} 件
      </option>)}</select>
     <WarehousePicker tenantId={tenantId} id="v172-warehouse"
      label="本地退货包裹接收仓库（非采购收货）"
      value={warehouseId} onChange={onWarehouse}/>
     <label htmlFor="v172-receipt-reference">包裹/实收参考号（同仓不得重复）</label>
     <input id="v172-receipt-reference" maxLength={160} value={receiptReference}
      onChange={e=>setReceiptReference(e.target.value)}
      placeholder="RETURN-PARCEL-20261116-001"/>
     <label htmlFor="v172-quantity">本包裹人工实收件数（含待核对包裹合计不得超过 V171 数量）</label>
     <input id="v172-quantity" type="number" min={1} step={1} value={quantity}
      onChange={e=>setQuantity(Number(e.target.value))}/>
     <label htmlFor="v172-condition">人工声称本包裹处置</label>
     <select id="v172-condition" value={condition}
      onChange={e=>setCondition(e.target.value as typeof condition)}>
      {Object.entries(conditionLabels).map(([value,label])=>
       <option key={value} value={value}>{label}</option>)}</select>
     <label htmlFor="v172-note">物理核查记录说明（10–500 字，勿填个人信息）</label>
     <textarea id="v172-note" value={note} rows={2} maxLength={500}
      onChange={e=>setNote(e.target.value)}/>
     <button type="button" className="refresh-button"
      disabled={!canCreate||create.isPending}
      onClick={()=>create.mutate()}>记录待第二人核查的本地退货包裹</button>
    </div>
    {create.error&&<p className="setup-error" role="alert">{String(create.error)}</p>}
    <label htmlFor="v172-decision-reason">独立核查说明（禁止原申报人/原 V171/V34/V35 编制人核对）</label>
    <textarea id="v172-decision-reason" value={reason} rows={2} maxLength={500}
     onChange={e=>setReason(e.target.value)}/>
    {decide.error&&<p className="setup-error" role="alert">{String(decide.error)}</p>}
    <div className="table-scroll" role="region" tabIndex={0}
     aria-label="本地退货包裹逐笔证据">
     <table><thead><tr>{['包裹引用','退款商品','仓库','数量',
      '状态','独立核对'].map(x=><th key={x} scope="col">{x}</th>)}</tr></thead>
      <tbody>{data.data?.receipts.map(r=><tr key={r.id}>
       <td>{r.sourceReference}</td><td>{r.itemId}</td>
       <td>{r.warehouseId.slice(0,8)}</td><td>{r.quantity}</td>
       <td>{statuses[r.status]??r.status} · {conditionLabels[r.condition]}</td>
       <td>{r.status==='PENDING'&&r.evidenceCurrent&&<>
        <button type="button" className="refresh-button"
         disabled={!noteValid(reason)||decide.isPending}
         onClick={()=>decide.mutate({id:r.id,action:'attest'})}>核对本地声明</button>
        <button type="button" className="text-button"
         disabled={!noteValid(reason)||decide.isPending}
         onClick={()=>decide.mutate({id:r.id,action:'reject'})}>驳回</button>
       </>}
       <button type="button" className="text-button"
        onClick={()=>setAuditId(v=>v===r.id?null:r.id)}>审计</button></td>
      </tr>)}</tbody></table></div>
    <div className="sandbox-actions">
     <span>第 {history.length} 页 · 当前页 {data.data?.receipts.length} 条</span>
     {history.length>1&&<button type="button" className="refresh-button"
      onClick={()=>setHistory(x=>x.slice(0,-1))}>上一页</button>}
     {data.data?.hasMore&&data.data.nextBeforeAt&&data.data.nextBeforeId&&
      <button type="button" className="refresh-button"
       onClick={()=>setHistory(x=>[...x,{beforeAt:data.data!.nextBeforeAt!,
        beforeId:data.data!.nextBeforeId!}])}>下一页</button>}
    </div>
   </>}
  {auditId&&(audit.isPending?<p>读取包裹审计…</p>:
   audit.isError?<p className="setup-error" role="alert">{String(audit.error)}</p>:
   <ul>{audit.data?.map((x,i)=><li key={i}>{x.action} · {x.actorId} · {x.note}
    {' · '}{x.occurredAt}</li>)}</ul>)}
  <p className="panel-caption">V172 的 externalWmsVerified、inventoryPosted、cogsReversalEligible 固定 false，actualCogsReversal=null；所有来源未知/证据过期均保守阻断，绝不因“本地已核对件数”直接增加销售库存或净利润。</p>
 </section>
}
