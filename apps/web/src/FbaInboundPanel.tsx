import {useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {fetchCoreListings} from './lib/core'
import FbaPurchaseLinkPanel from './FbaPurchaseLinkPanel'
import FbaPurchaseReconciliationPanel from './FbaPurchaseReconciliationPanel'
import FbaPurchaseExceptionPanel from './FbaPurchaseExceptionPanel'
import {addShipment,getShipments,getShipment,getTransit,dispatchShipment,addReceipt,
 beginReview,resolveVariance,closeReview,type InboundOrigin} from './lib/fba-inbound'

type DraftLine={rowId:string;sellerSku:string;quantity:number}
const states:Record<string,string>={
 DRAFT:'草稿',IN_TRANSIT:'运输中',RECEIVING:'记录入仓中',
 REVIEWING:'差异复核中',RECONCILED:'本地对账完毕'
}
const sources:Record<InboundOrigin,string>={
 MOCK:'模拟数据',MANUAL_UNVERIFIED:'手工未核验'
}
const initialLine=():DraftLine=>({rowId:crypto.randomUUID(),sellerSku:'',quantity:1})
export default function FbaInboundPanel({tenantId,marketplaceId}:{
 tenantId:string;marketplaceId:string
}){
 const client=useQueryClient()
 const key=['fba-inbound',tenantId,marketplaceId]
 const [selectedId,setSelectedId]=useState('')
 const [reference,setReference]=useState('')
 const [origin,setOrigin]=useState<InboundOrigin>('MOCK')
 const [lines,setLines]=useState<DraftLine[]>(()=>[initialLine()])
 const [createKey,setCreateKey]=useState(()=>crypto.randomUUID())
 const [receiptSku,setReceiptSku]=useState(''),[receiptQuantity,setReceiptQuantity]=useState(1)
 const [receiptProof,setReceiptProof]=useState('')
 const [receiptKey,setReceiptKey]=useState(()=>crypto.randomUUID())
 const [reviewSku,setReviewSku]=useState(''),[reviewNote,setReviewNote]=useState('')
 const [notice,setNotice]=useState(''),[failure,setFailure]=useState('')
 const listings=useQuery({queryKey:[...key,'listings'],queryFn:()=>fetchCoreListings(marketplaceId),retry:false})
 const shipments=useQuery({queryKey:[...key,'shipments'],queryFn:()=>getShipments(marketplaceId),retry:false})
 const transit=useQuery({queryKey:[...key,'transit'],queryFn:()=>getTransit(marketplaceId),retry:false})
 const detail=useQuery({queryKey:[...key,'shipment',selectedId],
  queryFn:()=>getShipment(marketplaceId,selectedId),enabled:!!selectedId,retry:false})
 const data=detail.data
 const refresh=()=>void client.invalidateQueries({queryKey:key})
 const editDraft=()=>setCreateKey(crypto.randomUUID())
 const editReceipt=()=>setReceiptKey(crypto.randomUUID())
 const validLines=lines.length>0&&lines.length<=100&&lines.every(l=>l.sellerSku.trim()&&
  Number.isInteger(l.quantity)&&l.quantity>0&&l.quantity<=1000000)&&
  new Set(lines.map(l=>l.sellerSku)).size===lines.length
 const create=useMutation({
  mutationFn:()=>addShipment(marketplaceId,{requestKey:createKey,reference:reference.trim(),
   origin,lines:lines.map(l=>({sellerSku:l.sellerSku,quantity:l.quantity}))}),
  onSuccess:result=>{
   setNotice('已建立本地货件 '+result.shipment.reference+'；不会提交到 Amazon')
   setFailure('');setSelectedId(result.shipment.id);setReference('');setLines([initialLine()])
   setCreateKey(crypto.randomUUID());refresh()
  },
  onError:(error:Error)=>{setNotice('');setFailure(error.message)}
 })
 const transition=useMutation({
  mutationFn:(action:'dispatch'|'review'|'reconcile')=>{
   if(!selectedId)throw new Error('请先选择货件')
   if(action==='dispatch')return dispatchShipment(marketplaceId,selectedId)
   if(action==='review')return beginReview(marketplaceId,selectedId)
   return closeReview(marketplaceId,selectedId)
  },
  onSuccess:result=>{
   setNotice('货件当前状态：'+(states[result.shipment.state]??result.shipment.state))
   setFailure('');refresh()
  },
  onError:(error:Error)=>{setNotice('');setFailure(error.message)}
 })
 const receipt=useMutation({
  mutationFn:()=>{
   if(!data||!selectedId)throw new Error('请选择货件')
   const sku=receiptSku||data.lines[0]?.sellerSku
   if(!sku)throw new Error('请选择有效 Seller SKU')
   return addReceipt(marketplaceId,selectedId,{
    requestKey:receiptKey,sellerSku:sku,quantity:receiptQuantity,
    evidenceReference:receiptProof.trim(),origin:data.shipment.origin
   })
  },
  onSuccess:()=>{
   setNotice('已保存本地入仓报告；未写入 Amazon FBA 可售库存')
   setFailure('');setReceiptProof('');setReceiptKey(crypto.randomUUID());refresh()
  },
  onError:(error:Error)=>{setNotice('');setFailure(error.message)}
 })
 const unresolved=(data?.lines??[]).filter(l=>l.delta!==0&&l.decision===null)
 const resolution=useMutation({
  mutationFn:()=>{
   if(!selectedId)throw new Error('请选择货件')
   const sku=reviewSku||unresolved[0]?.sellerSku
   if(!sku)throw new Error('当前无待复核差异')
   return resolveVariance(marketplaceId,selectedId,sku,reviewNote.trim())
  },
  onSuccess:()=>{
   setNotice('差异复核意见已锁定，后续不可修改')
   setFailure('');setReviewNote('');setReviewSku('');refresh()
  },
  onError:(error:Error)=>{setNotice('');setFailure(error.message)}
 })
 function createSubmit(e:FormEvent){e.preventDefault();if(validLines)create.mutate()}
 function receiptSubmit(e:FormEvent){e.preventDefault();receipt.mutate()}
 function resolutionSubmit(e:FormEvent){e.preventDefault();resolution.mutate()}
 function updateLine(rowId:string,patch:Partial<DraftLine>){
  setLines(old=>old.map(l=>l.rowId===rowId?{...l,...patch}:l));editDraft()
 }
 return <section className="panel full-panel" aria-label="本地 FBA 货件与差异对账">
  <div className="panel-heading"><div><h2>FBA 货件 · 在途与入仓差异</h2>
   <p>仅本地计划与 MOCK / 手工未核验报告；不会调用 Amazon、修改 FBA 可售库存或确认平台实际收货。</p></div>
   <button type="button" className="refresh-button" onClick={refresh}>刷新货件</button>
  </div>
  <FbaPurchaseReconciliationPanel tenantId={tenantId} marketplaceId={marketplaceId}
    onOpenShipment={id=>{setSelectedId(id);setFailure('');setNotice('')}}/>
  <FbaPurchaseExceptionPanel tenantId={tenantId} marketplaceId={marketplaceId}
    onOpenShipment={id=>{setSelectedId(id);setFailure('');setNotice('')}}/>
  <section className="core-tools" aria-label="SKU 在途预估">
   <h3>在途预估（不计入可售）</h3>
   <p className="setup-hint">仅统计运输中/记录入仓中的货件。已进入复核或对账完成的短少量不继续算在途。按数据来源分开列示。</p>
   {transit.isPending?<p className="empty-state">读取在途统计…</p>:
    transit.isError?<p className="setup-error" role="alert">{String(transit.error)}</p>:
    !transit.data?.length?<p className="empty-state">当前没有本地在途货件。</p>:
    <div className="table-scroll" role="region" tabIndex={0} aria-label="按 SKU 的在途预估表">
     <table><thead><tr>{['Seller SKU','来源','计划件数','本地报告入仓','预估在途'].map(h=><th key={h} scope="col">{h}</th>)}</tr></thead>
     <tbody>{transit.data.map(x=><tr key={x.origin+'|'+x.sellerSku}>
      <td>{x.sellerSku}</td><td>{sources[x.origin]}</td><td>{x.planned}</td>
      <td>{x.reportedReceived}</td><td>{x.estimatedInTransit}</td>
     </tr>)}</tbody></table></div>}
  </section>
  <details className="core-tools">
   <summary><strong>建立本地模拟货件</strong><span>支持最多 100 行 Seller SKU · 不创建 Amazon 货件</span></summary>
   <form className="team-invite-form" onSubmit={createSubmit}>
    <label htmlFor="fba-reference">本地货件编号</label>
    <input id="fba-reference" required maxLength={100} value={reference}
     placeholder="例如 LOCAL-FBA-2026-001" onChange={e=>{setReference(e.target.value);editDraft()}}/>
    <label htmlFor="fba-origin">证据来源</label>
    <select id="fba-origin" value={origin} onChange={e=>{setOrigin(e.target.value as InboundOrigin);editDraft()}}>
     <option value="MOCK">MOCK · 仅演示</option>
     <option value="MANUAL_UNVERIFIED">手工记录 · 未经平台确认</option>
    </select>
    {lines.map((l,i)=><div className="core-tools" key={l.rowId}>
     <label htmlFor={'fba-sku-'+l.rowId}>第 {i+1} 行 Seller SKU</label>
     <select id={'fba-sku-'+l.rowId} required value={l.sellerSku}
      onChange={e=>updateLine(l.rowId,{sellerSku:e.target.value})}>
      <option value="">选择当前 Marketplace 的 Listing</option>
      {(listings.data??[]).map(x=><option key={x.id} value={x.sellerSku}>{x.sellerSku}</option>)}
     </select>
     <label htmlFor={'fba-units-'+l.rowId}>计划发货件数</label>
     <input id={'fba-units-'+l.rowId} type="number" min={1} max={1000000} required
      value={l.quantity} onChange={e=>updateLine(l.rowId,{quantity:Number(e.target.value)})}/>
     {lines.length>1&&<button type="button" className="text-button" onClick={()=>{
      setLines(old=>old.filter(x=>x.rowId!==l.rowId));editDraft()}}>移除该行</button>}
    </div>)}
    <div className="sandbox-actions">
     <button type="button" className="refresh-button" disabled={lines.length>=100}
      onClick={()=>{setLines(old=>[...old,initialLine()]);editDraft()}}>新增 SKU 行</button>
     <button type="submit" className="primary-button"
      disabled={create.isPending||!reference.trim()||!validLines}>建立 DRAFT 货件</button>
    </div>
    {listings.data?.length===50&&<p className="setup-hint">下拉框仅为首 50 个 Listing；完整 SKU 可通过 API 提交。</p>}
    {listings.isError&&<p className="setup-error" role="alert">{String(listings.error)}</p>}
   </form>
  </details>
  <section className="core-tools" aria-label="本地货件列表">
   <h3>近期货件（最多 100 个）</h3>
   {shipments.isPending?<p className="empty-state">读取货件…</p>:
    shipments.isError?<p className="setup-error" role="alert">{String(shipments.error)}</p>:
    !shipments.data?.length?<p className="empty-state">尚未建立本地货件。</p>:
    <div className="table-scroll" role="region" tabIndex={0} aria-label="货件历史表">
     <table><thead><tr>{['货件编号','状态','来源','创建时间','查看'].map(h=><th key={h} scope="col">{h}</th>)}</tr></thead>
     <tbody>{shipments.data.map(s=><tr key={s.id}>
      <td>{s.reference}</td><td>{states[s.state]??s.state}</td>
      <td>{sources[s.origin]}</td><td>{s.createdAt}</td>
      <td><button type="button" className="text-button" aria-pressed={s.id===selectedId}
       onClick={()=>{setSelectedId(s.id);setReceiptSku('');setReviewSku('');setFailure('');setNotice('')}}>
       {s.id===selectedId?'当前查看':'查看与处理'}</button></td>
     </tr>)}</tbody></table></div>}
  </section>
  {selectedId&&<section className="core-tools" aria-label="选定货件明细与操作">
   <h3>货件明细与入仓对账</h3>
   {detail.isPending?<p className="empty-state">读取货件明细…</p>:
    detail.isError?<p className="setup-error" role="alert">{String(detail.error)}</p>:
    data&&<>
     <p>编号：{data.shipment.reference} · 状态：{states[data.shipment.state]??data.shipment.state} · 来源：{sources[data.shipment.origin]}</p>
     <p className="setup-hint">Amazon 已核验：否；FBA 可售库存已记账：否。已报告入仓与库存观测互不相加。</p>
     <div className="table-scroll" role="region" tabIndex={0} aria-label="货件 SKU 计划与差异表">
      <table><thead><tr>{['Seller SKU','计划','本地已报告入仓','预估在途','差异','复核决定','说明'].map(h=><th key={h} scope="col">{h}</th>)}</tr></thead>
      <tbody>{data.lines.map(l=><tr key={l.id}>
       <td>{l.sellerSku}</td><td>{l.plannedQuantity}</td><td>{l.reportedReceived}</td>
       <td>{l.estimatedInTransit??'不计在途'}</td>
       <td>{l.delta===0?'一致':l.delta>0?'多报 '+l.delta:'少报 '+Math.abs(l.delta)}</td>
       <td>{l.decision==='ACCEPT_VARIANCE'?'已独立接受差异':l.delta===0?'无差异':'待复核'}</td>
       <td>{l.reviewNote??'—'}</td>
      </tr>)}</tbody></table></div>
     <FbaPurchaseLinkPanel key={data.shipment.id} tenantId={tenantId}
       marketplaceId={marketplaceId} shipment={data.shipment} lines={data.lines}/>
     <div className="sandbox-actions">
      {data.shipment.state==='DRAFT'&&<button type="button" className="primary-button"
       disabled={transition.isPending} onClick={()=>transition.mutate('dispatch')}>标记本地发运</button>}
      {(data.shipment.state==='IN_TRANSIT'||data.shipment.state==='RECEIVING')&&
       <button type="button" className="refresh-button" disabled={transition.isPending}
        onClick={()=>transition.mutate('review')}>冻结入仓记录并开始差异复核</button>}
      {data.shipment.state==='REVIEWING'&&<button type="button" className="primary-button"
       disabled={transition.isPending||unresolved.length>0}
       onClick={()=>transition.mutate('reconcile')}>完成本地对账</button>}
     </div>
     {(data.shipment.state==='IN_TRANSIT'||data.shipment.state==='RECEIVING')&&
      <form className="team-invite-form" onSubmit={receiptSubmit}>
       <h3>添加本地入仓报告（只追加证据）</h3>
       <label htmlFor="fba-received-sku">报告 Seller SKU</label>
       <select id="fba-received-sku" value={receiptSku||data.lines[0]?.sellerSku||''}
        onChange={e=>{setReceiptSku(e.target.value);editReceipt()}}>
        {data.lines.map(l=><option key={l.id} value={l.sellerSku}>{l.sellerSku}</option>)}
       </select>
       <label htmlFor="fba-received-quantity">本次报告数量</label>
       <input id="fba-received-quantity" required type="number" min={1} max={1000000}
        value={receiptQuantity} onChange={e=>{setReceiptQuantity(Number(e.target.value));editReceipt()}}/>
       <label htmlFor="fba-received-proof">本地证据编号 / 单据引用</label>
       <input id="fba-received-proof" required maxLength={200} value={receiptProof}
        onChange={e=>{setReceiptProof(e.target.value);editReceipt()}}/>
       <button type="submit" className="primary-button"
        disabled={receipt.isPending||!receiptProof.trim()||!Number.isInteger(receiptQuantity)||
         receiptQuantity<1||receiptQuantity>1000000}>保存入仓报告（未核验）</button>
      </form>}
     {data.shipment.state==='REVIEWING'&&unresolved.length>0&&
      <form className="team-invite-form" onSubmit={resolutionSubmit}>
       <h3>独立复核短少/多报</h3>
       <p className="setup-hint">复核人不能是该 SKU 入仓证据的记录人。请由另一个 Owner 登录填写依据；决定不可撤销。</p>
       <label htmlFor="fba-review-sku">差异 Seller SKU</label>
       <select id="fba-review-sku" value={reviewSku&&unresolved.some(l=>l.sellerSku===reviewSku)?
        reviewSku:unresolved[0]?.sellerSku??''} onChange={e=>setReviewSku(e.target.value)}>
        {unresolved.map(l=><option key={l.id} value={l.sellerSku}>{l.sellerSku}</option>)}
       </select>
       <label htmlFor="fba-review-note">独立复核说明（至少 10 字）</label>
       <textarea id="fba-review-note" required minLength={10} maxLength={500} value={reviewNote}
        onChange={e=>setReviewNote(e.target.value)} rows={3}/>
       <button type="submit" className="primary-button"
        disabled={resolution.isPending||reviewNote.trim().length<10||reviewNote.length>500}>
        接受差异并保留审计证据</button>
      </form>}
     <h3>入仓证据历史</h3>
     {!data.receipts.length?<p className="empty-state">尚无本地入仓证据。</p>:
      <div className="table-scroll" role="region" tabIndex={0} aria-label="入仓证据事件">
       <table><thead><tr>{['Seller SKU','数量','证据引用','记录时间','记录人'].map(h=><th key={h} scope="col">{h}</th>)}</tr></thead>
       <tbody>{data.receipts.map(e=><tr key={e.id}><td>{e.sellerSku}</td><td>{e.quantity}</td>
        <td>{e.evidenceReference}</td><td>{e.recordedAt}</td><td>{e.recordedBy}</td></tr>)}</tbody></table></div>}
    </>}
  </section>}
  {notice&&<p className="panel-caption" role="status">{notice}</p>}
  {failure&&<p className="setup-error" role="alert">{failure}</p>}
 </section>
}
