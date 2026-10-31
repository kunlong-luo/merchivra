import {useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {getFinanceReviews,requestFinanceReview,decideFinanceReview,
 revokeFinanceReview} from './lib/finances-role'

export default function FinanceRoleReviewPanel({tenantId,accountId,currentUserId}:{
 tenantId:string;accountId:string;currentUserId:string
}){
 const [caseRef,setCaseRef]=useState(''),[digest,setDigest]=useState('')
 const [notice,setNotice]=useState(''),[failure,setFailure]=useState('')
 const cache=useQueryClient(),key=['finance-role',tenantId,accountId]
 const reviews=useQuery({queryKey:key,queryFn:()=>getFinanceReviews(accountId),
  retry:false,staleTime:10000})
 const refresh=()=>void cache.invalidateQueries({queryKey:key})
 const requested=useMutation({mutationFn:()=>requestFinanceReview(accountId,digest,caseRef),
  onSuccess:()=>{setCaseRef('');setDigest('');setNotice('财务角色审核申请已创建');
   setFailure('');refresh()},onError:(e:Error)=>setFailure(e.message)})
 const decided=useMutation({mutationFn:({id,action}:{id:string,action:'approve'|'reject'})=>
  decideFinanceReview(id,action),onSuccess:r=>{setNotice('审核结果：'+r.state);
   setFailure('');refresh()},onError:(e:Error)=>setFailure(e.message)})
 const revoked=useMutation({mutationFn:()=>revokeFinanceReview(accountId),
  onSuccess:()=>{setNotice('财务应用角色证据已在本地撤销');setFailure('');refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 const submit=(e:FormEvent)=>{e.preventDefault();requested.mutate()}
 return <section className="panel full-panel" aria-label="Amazon Finance and Accounting 应用角色审核">
  <div className="panel-heading"><div><h2>Finances · 财务角色独立审核</h2>
   <p>Finance and Accounting 与 Orders 角色不同，不能由 Seller 授权自动获得。
     必须由另一位具有 SECURITY_REVIEWER 资格的 Owner 审查外部证据。</p></div>
   <button type="button" className="refresh-button" onClick={refresh}>刷新</button></div>
  {reviews.isError?<p className="setup-hint" role="status">
    财务角色审批模块默认关闭：{String(reviews.error)}</p>:
   <form className="team-invite-form" onSubmit={submit}>
    <label htmlFor="finance-role-case">外部审核编号（不包含敏感内容）</label>
    <input id="finance-role-case" value={caseRef} minLength={8} maxLength={128}
     pattern="[A-Za-z0-9][A-Za-z0-9._:/-]{7,127}" required
     onChange={e=>setCaseRef(e.target.value.trim())}/>
    <label htmlFor="finance-role-digest">外部证据的 SHA-256 小写哈希</label>
    <input id="finance-role-digest" value={digest} minLength={64} maxLength={64}
     pattern="[0-9a-f]{64}" required autoComplete="off"
     onChange={e=>setDigest(e.target.value.trim())}/>
    <button type="submit" className="primary-button" disabled={requested.isPending||
      !/^[0-9a-f]{64}$/.test(digest)||caseRef.length<8}>提交独立财务角色审核</button>
   </form>}
  {!!reviews.data?.length&&<div className="table-scroll" role="region" tabIndex={0}
    aria-label="财务角色审核历史"><table><thead><tr>
     <th>编号</th><th>状态</th><th>申请时间</th><th>操作</th>
    </tr></thead><tbody>{reviews.data.map(r=><tr key={r.id}>
     <td>{r.externalCaseRef}</td><td>{r.state}</td>
     <td>{new Date(r.createdAt).toLocaleString('zh-CN')}</td>
     <td>{r.state==='PENDING'&&r.requestedBy!==currentUserId?
      <div className="sandbox-actions">
       <button type="button" className="refresh-button" disabled={decided.isPending}
        onClick={()=>decided.mutate({id:r.id,action:'approve'})}>审核通过</button>
       <button type="button" className="text-button" disabled={decided.isPending}
        onClick={()=>decided.mutate({id:r.id,action:'reject'})}>驳回</button>
      </div>:r.state==='PENDING'?'等待另一位审核员':'—'}</td>
    </tr>)}</tbody></table></div>}
  {reviews.isSuccess&&<button type="button" className="text-button"
    disabled={revoked.isPending} onClick={()=>{
     if(window.confirm('撤销当前 Seller 的财务读取角色审核证据？'))
       revoked.mutate()
    }}>撤销本地财务角色证据</button>}
  {notice&&<p className="panel-caption" role="status">{notice}</p>}
  {failure&&<p className="setup-error" role="alert">{failure}</p>}
 </section>
}
