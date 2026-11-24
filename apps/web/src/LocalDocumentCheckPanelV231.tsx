import {useEffect,useRef,useState} from 'react'
import {
 compareLocalDocumentDigestV231,MAX_LOCAL_DIGEST_BYTES_V231,
 type LocalDigestResultV231
} from './lib/local-document-digest-v231'

/** File bytes stay in the browser; comparing a USER CLAIM never authenticates
 * the file's external issuer, amount, payment or V162/V64 profit. */
export default function LocalDocumentCheckPanelV231({
 sourceId,claimedSha256
}:{sourceId:string;claimedSha256:string}){
 const request=useRef(0)
 const [status,setStatus]=useState<LocalDigestResultV231|null>(null)
 const [error,setError]=useState('')
 const [busy,setBusy]=useState(false)
 useEffect(()=>()=>{request.current++},[])
 async function compare(file:File){
  const seq=++request.current
  setStatus(null);setError('');setBusy(true)
  try{
   const result=await compareLocalDocumentDigestV231(file,claimedSha256)
   if(seq===request.current)setStatus(result)
  }catch(e){
   if(seq===request.current)
    setError(e instanceof Error?e.message:'本地文件比对失败')
  }finally{
   if(seq===request.current)setBusy(false)
  }
 }
 return <section className="core-tools" aria-label="V231 本地原文件字节摘要比对（不上传）">
  <h4>V231 · 本地文件字节与手工声明摘要核对</h4>
  <p className="setup-hint">
   来源 <code>{sourceId.slice(0,8)}…</code>。
   可选择自己合法持有的原始文件（1 B–{MAX_LOCAL_DIGEST_BYTES_V231/1024/1024} MiB），
   浏览器使用 Web Crypto SHA-256 核对 **V91 手工声明摘要**。
   文件只在当前浏览器内读取；不上传、不保存原文件或文件名、不新增审核或财务记录。
  </p>
  <label htmlFor={'v231-document-'+sourceId}>选择需本地核对的文件</label>
  <input id={'v231-document-'+sourceId}
   type="file" disabled={busy}
   onChange={e=>{
    const selected=e.currentTarget.files?.item(0)
    // Do not keep a File object or disclose its name in component state.
    e.currentTarget.value=''
    if(selected)void compare(selected)
   }}/>
  {busy&&<p role="status">正在本地重新计算 SHA-256；没有上传文件…</p>}
  {error&&<p role="alert" className="setup-error">
   <strong>没有完成本地摘要比较：</strong>{error}。原始来源仍未核验。
  </p>}
  {status&&<p role="status" className={
   status.status==='LOCAL_BYTES_MISMATCH_HOLD'?'setup-error':'setup-hint'}>
   {status.status==='LOCAL_BYTES_MATCH_USER_CLAIM_ONLY'
    ?'字节 SHA-256 与你之前声明的摘要一致（仅本地自述一致）'
    :'文件字节 SHA-256 与已暂存的摘要不一致，必须暂停并核对来源'}。
   <strong> 这不证明文件来自 Amazon/Ads、银行或货代；
   不证明交易已扣款，不确认 COGS 或真实净利润。</strong>
  </p>}
 </section>
}
