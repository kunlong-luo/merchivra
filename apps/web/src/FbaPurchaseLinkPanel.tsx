import {useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {getPurchaseLinks,getPurchaseCandidates,proposePurchaseLink,
 confirmPurchaseLink,revokePurchaseLink,type LocalPurchaseLink}
 from './lib/fba-purchase-links'
import type {InboundLine,InboundShipment} from './lib/fba-inbound'

export default function FbaPurchaseLinkPanel({tenantId,marketplaceId,shipment,lines}:{
 tenantId:string;marketplaceId:string;shipment:InboundShipment;lines:InboundLine[]
}){
 const client=useQueryClient()
 const [lineId,setLineId]=useState('')
 const [purchaseLineId,setPurchaseLineId]=useState('')
 const [asnId,setAsnId]=useState('')
 const [proof,setProof]=useState('')
 const [reviewReason,setReviewReason]=useState('')
 const [requestKey,setRequestKey]=useState(()=>crypto.randomUUID())
 const [notice,setNotice]=useState(''),[failure,setFailure]=useState('')
 const key=['fba-purchase-links',tenantId,marketplaceId,shipment.id]
 const links=useQuery({queryKey:[...key,'list'],
  queryFn:()=>getPurchaseLinks(marketplaceId,shipment.id),retry:false})
 const chosenLine=lineId||lines[0]?.id||''
 const candidates=useQuery({queryKey:[...key,'candidates',chosenLine],
  queryFn:()=>getPurchaseCandidates(marketplaceId,shipment.id,chosenLine),
  enabled:shipment.state==='DRAFT'&&!!chosenLine,retry:false})
 const selectedCandidate=candidates.data?.find(c=>c.purchaseOrderLineId===purchaseLineId)
 const asnChoices=(candidates.data??[]).filter(c=>
  c.purchaseOrderLineId===purchaseLineId&&c.asnId!==null)
 const distinctCandidates=[...new Map((candidates.data??[]).map(c=>
  [c.purchaseOrderLineId,c])).values()]
 const refresh=()=>{
  void client.invalidateQueries({queryKey:key})
  void client.invalidateQueries({queryKey:['fba-inbound',tenantId,marketplaceId]})
 }
 const propose=useMutation({mutationFn:()=>proposePurchaseLink(
  marketplaceId,shipment.id,{requestKey,inboundLineId:chosenLine,
   purchaseOrderLineId:purchaseLineId,asnId:asnId||null,proofReference:proof.trim()}),
  onSuccess:()=>{
   setNotice('本地身份关联已提出，须由另一名 Owner 审核确认；并非 Amazon 官方关联')
   setFailure('');setProof('');setPurchaseLineId('');setAsnId('');
   setRequestKey(crypto.randomUUID());refresh()
  },
  onError:(e:Error)=>setFailure(e.message)})
 const change=useMutation({
  mutationFn:({link,action}:{link:LocalPurchaseLink;action:'CONFIRMED'|'REVOKED'})=>
   action==='CONFIRMED'?
    confirmPurchaseLink(marketplaceId,shipment.id,link.id,reviewReason.trim()):
    revokePurchaseLink(marketplaceId,shipment.id,link.id,reviewReason.trim()),
  onSuccess:()=>{
   setNotice('本地身份审核及审计记录已保存');setFailure('');setReviewReason('');refresh()
  },
  onError:(e:Error)=>setFailure(e.message)})
 function submit(e:FormEvent){e.preventDefault();if(chosenLine&&purchaseLineId&&proof.trim().length>=10)propose.mutate()}
 return <section className="core-tools" aria-label="V75 FBA 货件与采购 ASN 明确关联">
  <div className="panel-heading"><div><h3>V75 · FBA 本地货件与采购 / ASN 身份关联</h3>
   <p>必须显式指定当前 Seller SKU 映射的已批准采购行；有 ASN 时可补充已申报发货的 ASN。
    仅能在货件仍为 DRAFT 时提出或审核，两名不同 Owner 确认。
    关联只代表人工核对标识，绝非 Amazon 货件核验、承运商签收或实物同批自动核销。</p></div>
   <button type="button" className="refresh-button" onClick={refresh}>刷新关联证据</button>
  </div>
  {shipment.state==='DRAFT'&&<form className="team-invite-form" onSubmit={submit}>
   <label htmlFor="v75-inbound-line">选择本地 FBA 计划行</label>
   <select id="v75-inbound-line" value={chosenLine} onChange={e=>{
    setLineId(e.target.value);setPurchaseLineId('');setAsnId('');setRequestKey(crypto.randomUUID())}}>
    {lines.map(l=><option key={l.id} value={l.id}>{l.sellerSku} · 计划 {l.plannedQuantity} 件</option>)}
   </select>
   <label htmlFor="v75-purchase-line">已审批采购单 / 明确 SKU 对应关系</label>
   <select id="v75-purchase-line" value={purchaseLineId}
    onChange={e=>{setPurchaseLineId(e.target.value);setAsnId('');setRequestKey(crypto.randomUUID())}}>
    <option value="">请选择采购行，不自动匹配</option>
    {distinctCandidates.map(c=><option key={c.purchaseOrderLineId} value={c.purchaseOrderLineId}>
     {c.orderNumber} · {c.supplierName} · 订购 {c.orderedQuantity} / 本地收货 {c.localReceivedQuantity}
    </option>)}
   </select>
   <label htmlFor="v75-asn">供应商 ASN（可选，仅已申报 SHIPPED）</label>
   <select id="v75-asn" value={asnId} disabled={!selectedCandidate}
    onChange={e=>{setAsnId(e.target.value);setRequestKey(crypto.randomUUID())}}>
    <option value="">无 ASN 身份证据；只建立采购行关联</option>
    {asnChoices.map(c=><option key={c.asnId!} value={c.asnId!}>
     已申报 ASN {c.asnId!.slice(0,8)} · {c.asnQuantity} 件 · 本地关联收货 {c.asnReceivedQuantity}
    </option>)}
   </select>
   <label htmlFor="v75-link-proof">人工证据编号 / 来源说明（10–200 字符）</label>
   <input id="v75-link-proof" value={proof} minLength={10} maxLength={200}
    onChange={e=>{setProof(e.target.value);setRequestKey(crypto.randomUUID())}}
    placeholder="例如：本地装箱单 PO-ABC-002，人工核对的采购行标识" required/>
   <button type="submit" className="primary-button" disabled={!purchaseLineId||
    !chosenLine||proof.trim().length<10||propose.isPending}>提交身份关联待复核</button>
   {candidates.isError&&<p className="setup-error" role="alert">
    读取对应采购行失败：{String(candidates.error)}</p>}
  </form>}
  <div className="table-scroll" role="region" tabIndex={0} aria-label="FBA 与采购证据关联表">
   {links.isPending?<p className="empty-state">读取明确关联记录…</p>:
    links.isError?<p className="setup-error" role="alert">{String(links.error)}</p>:
    !links.data?.length?<p className="empty-state">
     尚无身份关联：不能据此把本地采购或 ASN 当成 Amazon FBA 在途。</p>:
    <table><thead><tr>
     <th scope="col">FBA 计划 SKU / 数量</th>
     <th scope="col">采购单 / 本地收货</th>
     <th scope="col">供应商 ASN / 本地关联收货</th>
     <th scope="col">身份审核</th></tr></thead><tbody>
     {links.data.map(link=><tr key={link.id}>
      <td>{link.sellerSku} · {link.inboundPlannedQuantity} 件
       <div className="secondary-cell">本地 FBA 报告入仓 {link.inboundReportedReceived} 件</div></td>
      <td>{link.orderNumber} · {link.purchaseState}
       <div className="secondary-cell">PO 订购 {link.poOrderedQuantity} · 本地实收 {link.localPoReceived}</div></td>
      <td>{link.asnId?link.asnId.slice(0,8)+' · '+link.asnStatus:'没有明确关联 ASN'}
       <div className="secondary-cell">ASN 申报 {link.asnQuantity??'未知'} · 本地 ASN 实收 {link.asnLocalReceived??'未知'}</div></td>
      <td>{link.state==='CONFIRMED'?'第二 Owner 已确认身份':
       link.state==='REVOKED'?'关联已撤销':'待另一名 Owner 确认'}
       <div className="secondary-cell">{link.proofReference}</div>
       {link.state==='PROPOSED'&&shipment.state==='DRAFT'&&
        <div className="sandbox-actions">
         <button type="button" className="refresh-button"
          disabled={change.isPending||reviewReason.trim().length<10}
          onClick={()=>change.mutate({link,action:'CONFIRMED'})}>第二 Owner 确认关联</button>
         <button type="button" className="text-button"
          disabled={change.isPending||reviewReason.trim().length<10}
          onClick={()=>change.mutate({link,action:'REVOKED'})}>撤销待审关联</button>
        </div>}
      </td>
     </tr>)}
    </tbody></table>}
  </div>
  {links.data?.some(x=>x.state==='PROPOSED')&&shipment.state==='DRAFT'&&
   <div className="team-invite-form">
    <label htmlFor="v75-review-reason">复核说明（10–200 字符）</label>
    <input id="v75-review-reason" value={reviewReason} maxLength={200}
     onChange={e=>setReviewReason(e.target.value)} placeholder="例如：第二 Owner 比对装箱单、采购行和 ASN 标识"/>
   </div>}
  {notice&&<p role="status" className="panel-caption">{notice}</p>}
  {failure&&<p role="alert" className="setup-error">{failure}</p>}
  <p className="panel-caption">各数量均为所属原始模块的完整参考值，可能涵盖同采购行的多个本地货件，
   不能跨行求和为同一批次的实物核销。审核只确认用户提供的身份对应关系；
   不调整库存、不冲减采购未收、不增加 FBA 可售或 Amazon 货件。</p>
 </section>
}
