import {useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import type {LocalWarehouse} from './lib/purchasing'
import {fetchLotDispositions} from './lib/lot-reconciliation'
import {fetchDispositionProofs,submitDispositionProof,decideDispositionProof,
 type NewDispositionProof} from './lib/lot-disposition-proofs'

const okay=(s:string)=>s.trim().length>=5&&s.trim().length<=300
export default function LotDispositionProofPanel({tenantId,currentUserId,warehouses}:{
 tenantId:string;currentUserId:string;warehouses:LocalWarehouse[]
}){
 const client=useQueryClient(),root=['purchasing',tenantId]
 const [warehouse,setWarehouse]=useState(''),[dispositionId,setDispositionId]=useState('')
 const [reference,setReference]=useState(''),[sha,setSha]=useState('')
 const [note,setNote]=useState(''),[key,setKey]=useState(()=>crypto.randomUUID())
 const [review,setReview]=useState(''),[message,setMessage]=useState(''),[error,setError]=useState('')
 const plans=useQuery({queryKey:[...root,'lot-dispositions',warehouse],
  queryFn:()=>fetchLotDispositions(warehouse),enabled:!!warehouse,retry:false})
 const proofs=useQuery({queryKey:[...root,'lot-proofs',warehouse],
  queryFn:()=>fetchDispositionProofs(warehouse),enabled:!!warehouse,retry:false})
 const eligible=(plans.data??[]).filter(p=>p.status==='EXECUTED_REPORTED'&&
  !proofs.data?.some(x=>x.dispositionId===p.id))
 const selected=plans.data?.find(p=>p.id===dispositionId)
 const refresh=()=>void client.invalidateQueries({queryKey:root})
 const submit=useMutation({mutationFn:(body:NewDispositionProof)=>submitDispositionProof(body),
  onSuccess:()=>{setMessage('已登记人工凭据元数据，等待独立内部审核；尚未验证外部文件');
   setError('');setDispositionId('');setNote('');setReference('');setSha('');
   setKey(crypto.randomUUID());refresh()},
  onError:(e:Error)=>setError(e.message)})
 const decide=useMutation({mutationFn:({id,decision}:{
  id:string;decision:'REVIEW'|'REJECT'
 })=>decideDispositionProof(id,{decision,reason:review.trim()}),
  onSuccess:proof=>{setMessage(proof.status==='METADATA_REVIEWED'
   ?'凭据元数据已完成内部审核；未进行外部文件真实性核验':'凭据元数据已被拒绝');
   setReview('');setError('');refresh()},
  onError:(e:Error)=>setError(e.message)})
 const valid=!!selected&&reference.trim().length>=4&&reference.trim().length<=120&&
  okay(note)&&(!sha||/^[a-f0-9]{64}$/.test(sha))&&!submit.isPending
 function post(e:FormEvent){
  e.preventDefault()
  if(!valid||!selected)return
  if(window.confirm('仅提交人工参考编号和可选文档哈希，不上传文件、不代表承运商或供应商已验证。'))
   submit.mutate({requestKey:key,dispositionId:selected.id,
    proofKind:selected.method==='DISPOSAL'?'DISPOSAL_CERTIFICATE':
      'SUPPLIER_RETURN_RECEIPT',
    evidenceReference:reference.trim(),documentSha256:sha||null,explanation:note.trim()})
 }
 return <section className="panel full-panel" aria-label="V51 隔离品处置凭据元数据复核">
  <div className="panel-heading"><div>
   <h2>V51 · 已报告处置的证明材料登记与内部复核</h2>
   <p>仅对 V50 已报告实际报废或供应商退货的处置记录登记凭据编号、可选
    SHA-256 摘要和说明。这里不上传证明文件，也不冒充第三方物流核验；
    审核结论仅表示本地 Owner 对记录元数据进行了检查。</p>
  </div><button className="refresh-button" type="button" onClick={refresh}>刷新</button></div>
  <label htmlFor="v51-proof-wh">本地仓库</label>
  <select id="v51-proof-wh" value={warehouse}
   onChange={e=>{setWarehouse(e.target.value);setDispositionId('')}}>
   <option value="">选择仓库</option>
   {warehouses.filter(w=>w.status==='ACTIVE').map(w=>
    <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
  </select>
  {!!warehouse&&<>
   <h3>提供已报告处置的参考证据</h3>
   <form className="team-invite-form" onSubmit={post}>
    <label htmlFor="v51-proof-source">处置来源</label>
    <select id="v51-proof-source" value={dispositionId}
     onChange={e=>{setDispositionId(e.target.value);setKey(crypto.randomUUID())}}>
     <option value="">选择已经人工报告执行的处置记录</option>
     {eligible.map(p=><option key={p.id} value={p.id}>
      {p.method==='DISPOSAL'?'已报告报废':'已报告供应商退货'} ·
      {p.quantity} 件 · {p.id.slice(0,8)}</option>)}
    </select>
    <label htmlFor="v51-proof-ref">人工凭据编号</label>
    <input id="v51-proof-ref" value={reference} minLength={4} maxLength={120}
     onChange={e=>{setReference(e.target.value);setKey(crypto.randomUUID())}}
     placeholder="DISPOSAL-CERT-2026-001"/>
    <label htmlFor="v51-proof-sha">SHA-256（可选，64 位小写十六进制）</label>
    <input id="v51-proof-sha" value={sha} maxLength={64}
     onChange={e=>{setSha(e.target.value.toLowerCase());setKey(crypto.randomUUID())}}
     placeholder="仅录入已有原件文件的摘要；此处不会验证原件"/>
    <label htmlFor="v51-proof-note">文档来源与登记说明</label>
    <input id="v51-proof-note" value={note} minLength={5} maxLength={300}
     onChange={e=>{setNote(e.target.value);setKey(crypto.randomUUID())}}/>
    <button className="primary-button" type="submit" disabled={!valid}>
     提交待内部审核的凭据元数据
    </button>
   </form>
   <h3>独立内部复核（不触发库存动作）</h3>
   <label htmlFor="v51-proof-review-note">审核理由</label>
   <input id="v51-proof-review-note" value={review} minLength={5} maxLength={300}
    onChange={e=>setReview(e.target.value)}/>
   {proofs.isPending?<p className="empty-state">正在加载凭据登记…</p>:
    proofs.isError?<p className="setup-error" role="alert">{String(proofs.error)}</p>:
    !proofs.data?.length?<p className="empty-state">该仓暂无处置凭据元数据。</p>:
    <div className="table-scroll" role="region" tabIndex={0}>
     <table><thead><tr><th scope="col">处置类型/凭据</th><th scope="col">摘要</th>
      <th scope="col">审核状态</th><th scope="col">不同 Owner 审核</th></tr></thead><tbody>
      {proofs.data.map(p=><tr key={p.id}>
       <td>{p.proofKind==='DISPOSAL_CERTIFICATE'?'报废':'供应商退货'} ·
        {p.evidenceReference}</td><td>{p.documentSha256?
         p.documentSha256.slice(0,12)+'…':'未提供'}</td>
       <td>{p.status==='PENDING_REVIEW'?'待内部审核':
        p.status==='METADATA_REVIEWED'?'元数据已内部审核（非外部验证）':'已拒绝'}</td>
       <td>{p.status==='PENDING_REVIEW'&&p.submittedBy!==currentUserId?
        <div className="sandbox-actions">
         <button className="primary-button" type="button"
          disabled={!okay(review)||decide.isPending}
          onClick={()=>{if(window.confirm('确认仅完成内部元数据复核，不声称外部文件真实有效？'))
           decide.mutate({id:p.id,decision:'REVIEW'})}}>内部审核通过</button>
         <button className="refresh-button" type="button"
          disabled={!okay(review)||decide.isPending}
          onClick={()=>decide.mutate({id:p.id,decision:'REJECT'})}>拒绝</button>
        </div>:'—'}</td>
      </tr>)}</tbody></table>
    </div>}
  </>}
  {message&&<p className="panel-caption" role="status">{message}</p>}
  {error&&<p className="setup-error" role="alert">{error}</p>}
 </section>
}
