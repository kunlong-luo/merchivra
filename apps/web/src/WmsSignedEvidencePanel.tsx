import {useState} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {decideWmsSource,fetchSignedWmsReceipts,fetchWmsSources,
 importWmsReceipt,registerWmsSource} from './lib/signed-wms-evidence'
import type {LocalReturnIntake} from './lib/customer-return-intakes'

const reference=/^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$/
const noteValid=(s:string)=>s.trim().length>=10&&s.length<=500&&
 !/[\x00-\x1F\x7F]/.test(s)
const stateLabels:Record<string,string>={
 PENDING:'待另一位 Owner 批准密钥',APPROVED:'租户内部公钥已批准（身份未证实）',
 REVOKED:'密钥已撤销'}
function canonical(tenant:string,mp:string,event:string,
 intake:LocalReturnIntake,source:string,externalRef:string,observed:string){
 try {
  const stamp=new Date(observed)
  if(!Number.isFinite(stamp.getTime()))return ''
  const utc=stamp.toISOString().replace(/\.000Z$/,'Z')
  return ['MERCHIVRA_WMS_RETURN_V1',tenant,mp,event,intake.id,
   intake.warehouseId,source,externalRef,intake.sourceReference,
   String(intake.quantity),intake.condition,utc,''].join('\n')
 }catch{return ''}
}
/** Staged external cryptographic evidence: signature != real warehouse attestation. */
export default function WmsSignedEvidencePanel({tenantId,marketplaceId,eventId,intakes}:{
 tenantId:string;marketplaceId:string;eventId:string;intakes:LocalReturnIntake[]
}){
 const qc=useQueryClient()
 const [intakeId,setIntakeId]=useState('')
 const selected=intakes.find(i=>i.id===intakeId)
 const warehouseId=selected?.warehouseId??''
 const [sourceId,setSourceId]=useState('')
 const [sourceCode,setSourceCode]=useState('')
 const [publicKey,setPublicKey]=useState('')
 const [enrollReason,setEnrollReason]=useState('')
 const [reviewReason,setReviewReason]=useState('')
 const [externalRef,setExternalRef]=useState('')
 const [observedAt,setObservedAt]=useState(new Date().toISOString())
 const [signature,setSignature]=useState('')
 const sourceKey=['finance','wms-source-v173',tenantId,warehouseId]
 const proofKey=['finance','signed-wms-v173',tenantId,marketplaceId,eventId,intakeId]
 const sources=useQuery({queryKey:sourceKey,
  queryFn:()=>fetchWmsSources(warehouseId),enabled:!!warehouseId,retry:false})
 const proofs=useQuery({queryKey:proofKey,
  queryFn:()=>fetchSignedWmsReceipts(marketplaceId,eventId,intakeId),
  enabled:!!intakeId,retry:false})
 const enroll=useMutation({mutationFn:()=>registerWmsSource({
  warehouseId,sourceCode,publicKeySpkiBase64:publicKey,reason:enrollReason}),
  onSuccess:()=>{setEnrollReason('');setPublicKey('');
   void qc.invalidateQueries({queryKey:sourceKey})}})
 const decide=useMutation({mutationFn:(action:'approve'|'revoke')=>
  decideWmsSource(sourceId,action,reviewReason),
  onSuccess:()=>{setReviewReason('');
   void qc.invalidateQueries({queryKey:sourceKey})}})
 const importEvidence=useMutation({mutationFn:()=>importWmsReceipt(
  marketplaceId,eventId,intakeId,{
   sourceId,externalReceiptId:externalRef,observedAt,
   quantity:selected!.quantity,condition:selected!.condition,
   signatureBase64:signature}),
  onSuccess:()=>{setSignature('');void qc.invalidateQueries({queryKey:proofKey})}})
 const active=sources.data?.find(x=>x.id===sourceId)
 const payload=selected&&sourceId&&reference.test(externalRef)
  ?canonical(tenantId,marketplaceId,eventId,selected,sourceId,externalRef,observedAt)
  :''
 const canImport=!!selected&&selected.status==='ATTESTED_LOCAL'&&
  selected.evidenceCurrent&&active?.state==='APPROVED'&&!!payload&&
  signature.length>=80&&signature.length<=120
 return <section className="panel full-panel" aria-label="V173 Ed25519 仓储来源签名核验">
  <div className="panel-heading"><div>
   <h3>V173 · 外部仓储签名来源接入与包裹去重</h3>
   <p>登记合作方提供的 Ed25519 **公钥**（绝不上传私钥），由不同 Owner 批准；对已完成 V172 本地核对的包裹验证完整签名、原订单退款归属、件数与状态。已登记公钥不等于已核验仓储运营商身份。</p>
  </div></div>
  <label htmlFor="v173-intake">选择当前页已登记的本地退货包裹</label>
  <select id="v173-intake" value={intakeId}
   onChange={e=>{setIntakeId(e.target.value);setSourceId('');setSignature('')}}>
   <option value="">选择退款包裹</option>
   {intakes.map(i=><option key={i.id} value={i.id}>
    {i.sourceReference} · {i.quantity} 件 · {i.status}
   </option>)}</select>
  {!selected?<p className="setup-hint">选择包裹后，页面会显示该仓库的公钥登记和签名证据。</p>:<>
   <p className="setup-hint">仓库 {selected.warehouseId}，V172 当前状态 {selected.status}。只有 ATTESTED_LOCAL 且来源未过期的包裹允许签名导入。签名验证只验证密钥持有人，不能证明实收、可售或利润。</p>
   <label htmlFor="v173-source">来源公钥（选择已登记来源）</label>
   <select id="v173-source" value={sourceId}
    onChange={e=>setSourceId(e.target.value)}>
    <option value="">选择仓库公钥</option>
    {sources.data?.map(s=><option value={s.id} key={s.id}>
     {s.sourceCode} · {stateLabels[s.state]}
    </option>)}
   </select>
   {sources.isError&&<p role="alert" className="setup-error">{String(sources.error)}</p>}
   {active&&<div className="sandbox-actions">
    <label htmlFor="v173-review-reason">独立公钥复核或撤销理由</label>
    <textarea id="v173-review-reason" value={reviewReason} rows={2} maxLength={500}
     onChange={e=>setReviewReason(e.target.value)}/>
    {active.state==='PENDING'&&<button className="refresh-button" type="button"
     disabled={!noteValid(reviewReason)||decide.isPending}
     onClick={()=>decide.mutate('approve')}>由另一位 Owner 批准租户公钥</button>}
    {active.state!=='REVOKED'&&<button className="text-button" type="button"
     disabled={!noteValid(reviewReason)||decide.isPending}
     onClick={()=>decide.mutate('revoke')}>撤销公钥</button>}
   </div>}
   {decide.error&&<p className="setup-error" role="alert">{String(decide.error)}</p>}
   <div className="core-controls">
    <h4>登记外部来源公钥</h4>
    <label htmlFor="v173-code">仓库来源代码（8–80 位大写）</label>
    <input id="v173-code" value={sourceCode} maxLength={80}
     onChange={e=>setSourceCode(e.target.value)}
     placeholder="WAREHOUSE_VENDOR_V1"/>
    <label htmlFor="v173-public">合作方 Ed25519 X.509 SPKI DER 公钥 Base64</label>
    <textarea id="v173-public" value={publicKey} rows={2}
     maxLength={120} onChange={e=>setPublicKey(e.target.value)}/>
    <label htmlFor="v173-enroll-reason">登记理由</label>
    <textarea id="v173-enroll-reason" value={enrollReason} rows={2}
     maxLength={500} onChange={e=>setEnrollReason(e.target.value)}/>
    <button className="refresh-button" type="button"
     disabled={enroll.isPending||!noteValid(enrollReason)||
      !/^[A-Z0-9][A-Z0-9_-]{7,79}$/.test(sourceCode)||publicKey.length<58}
     onClick={()=>enroll.mutate()}>登记公钥（需不同 Owner 复核）</button>
   </div>
   {enroll.error&&<p className="setup-error" role="alert">{String(enroll.error)}</p>}
   <div className="core-controls">
    <h4>导入外部独立签名证据</h4>
    <label htmlFor="v173-external-id">外部签收事件/包裹唯一号</label>
    <input id="v173-external-id" value={externalRef} maxLength={160}
     onChange={e=>setExternalRef(e.target.value)}
     placeholder="WMS-RETURN-RECEIPT-20261117"/>
    <label htmlFor="v173-observed">仓库观测时刻 ISO UTC（不得超过 366 天）</label>
    <input id="v173-observed" value={observedAt}
     onChange={e=>setObservedAt(e.target.value)}/>
    <label htmlFor="v173-payload">由合作方使用私钥签名的固定 UTF-8 文本（含末尾换行）</label>
    <textarea id="v173-payload" value={payload} rows={9} readOnly
     aria-label="可复制的标准化签名内容"/>
    <label htmlFor="v173-signature">Ed25519 detached 签名 Base64（64 字节）</label>
    <textarea id="v173-signature" value={signature} rows={2}
     onChange={e=>setSignature(e.target.value)} maxLength={120}/>
    <button className="refresh-button" type="button"
     disabled={!canImport||importEvidence.isPending||!!proofs.data?.length}
     onClick={()=>importEvidence.mutate()}>校验并保存不可变签名证据</button>
   </div>
   {importEvidence.error&&<p className="setup-error" role="alert">{String(importEvidence.error)}</p>}
   {proofs.isError&&<p className="setup-error" role="alert">{String(proofs.error)}</p>}
   {proofs.data?.map(p=><p key={p.id} className="setup-hint">
    外部参考 {p.externalReceiptId}：Ed25519 签名有效（SHA256 {p.canonicalSha256.slice(0,16)}…），但仓储身份与实物签收未核验；库存/COGS 未入账。
   </p>)}
  </>}
  <p className="panel-caption">V173 仅证明签名载荷与租户登记公钥匹配；不保证公钥属于外部运营商，不验证真实 FBA/FBM/WMS 收货，不改变库存、V162/V64 利润或 #104 NO-GO 状态。</p>
 </section>
}
