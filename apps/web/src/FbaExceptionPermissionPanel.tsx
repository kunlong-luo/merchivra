import {useEffect,useState} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {getMyExceptionGrants,getExceptionOwners,getExceptionPermissionAudit,
 saveExceptionOwnerGrants,type ExceptionGrants} from './lib/exception-capabilities-v84'

const names:Record<keyof ExceptionGrants,string>={
 view:'查看本地处理单与审计',assign:'分派负责人 / 调整期限',
 review:'人工确认 / 重新打开',resolve:'人工完成 / 批量完成',
 configure:'配置提醒与建议优先级',manage:'管理 Owner 权限'
}
const fields=(Object.keys(names) as Array<keyof ExceptionGrants>)
export default function FbaExceptionPermissionPanel({tenantId,marketplaceId}:{
 tenantId:string;marketplaceId:string
}){
 const client=useQueryClient()
 const prefix=['fba-inbound',tenantId,marketplaceId]
 const mine=useQuery({queryKey:[...prefix,'grants-v84'],
  queryFn:()=>getMyExceptionGrants(marketplaceId),retry:false})
 const management=mine.data?.grants.manage===true
 const owners=useQuery({queryKey:[...prefix,'owner-permissions-v84'],
  queryFn:()=>getExceptionOwners(marketplaceId),enabled:management,retry:false})
 const [chosen,setChosen]=useState('')
 const [draft,setDraft]=useState<ExceptionGrants|null>(null)
 const [reason,setReason]=useState('')
 const [notice,setNotice]=useState('')
 const [error,setError]=useState('')
 const [historyOpen,setHistoryOpen]=useState(false)
 const audit=useQuery({queryKey:[...prefix,'owner-permission-audit-v84'],
  queryFn:()=>getExceptionPermissionAudit(marketplaceId),
  enabled:management&&historyOpen,retry:false})
 const members=owners.data?.filter(x=>x.ownerId!==mine.data?.ownerId)??[]
 const current=members.find(x=>x.ownerId===chosen)??members[0]
 useEffect(()=>{
  if(!current){setDraft(null);return}
  setDraft(current.grants);setReason('')
 },[current?.ownerId,current?.version,marketplaceId])
 const update=useMutation({
  mutationFn:()=>{
   if(!current||!draft)throw Error('当前 Owner 权限未加载')
   return saveExceptionOwnerGrants(marketplaceId,current.ownerId,{
    grants:draft,expectedVersion:current.version,reason:reason.trim()})
  },
  onSuccess:()=>{
   setNotice('Owner 的本地异常权限已更新并追加审计。')
   setError('');setReason('')
   void client.invalidateQueries({queryKey:prefix})
  },
  onError:(err:Error)=>{setError(err.message);setNotice('')}
 })
 const valid=!!current&&!!draft&&reason.trim().length>=10&&
  reason.trim().length<=300&&(!draft.manage||(draft.view&&draft.configure))
 return <section className="core-tools" aria-label="V84 异常处理 Owner 细分权限">
  <h4>V84 · 本地异常工作台授权</h4>
  <p className="setup-hint">权限仅约束本地采购/FBA 异常处理链路。
   既有 Owner 未配置时保持兼容；只读员工不会获得任何写入权限。
   授权管理人不能修改自己的权限，所有变更都记录版本和审计。</p>
  {mine.isPending?<p>读取当前 Owner 异常操作权限…</p>:
   mine.isError?<p role="alert" className="setup-error">
    当前 Owner 权限不可用：{String(mine.error)}</p>:
   mine.data&&<>
    <p className="setup-hint">当前账户：
     {fields.filter(key=>mine.data.grants[key]).map(key=>names[key]).join('、')||'无本模块权限'}
     {mine.data.customized?'（显式限定）':'（继承 Owner 默认权限）'}。</p>
    {management&&<>
     {owners.isPending?<p>正在读取本租户有效 Owner…</p>:
      owners.isError?<p role="alert" className="setup-error">
       获取可管理 Owner 失败：{String(owners.error)}</p>:
      !members.length?<p>目前没有其他可管理 Owner。</p>:
      <form className="team-invite-form" onSubmit={e=>{
       e.preventDefault();if(valid)update.mutate()
      }}>
       <label htmlFor="v84-owner">选择其他有效 Owner（不可编辑自身）</label>
       <select id="v84-owner" value={current?.ownerId??''}
        onChange={e=>{setChosen(e.target.value);setError('')}}>
        {members.map(x=><option key={x.ownerId} value={x.ownerId}>
         {x.displayName} · {x.customized?'显式限制':'Owner 默认全权限'}</option>)}
       </select>
       <p className="setup-hint">当前版本 {current?.version??0}。
        新权限保存前由服务端再次验证 Owner 是否仍然有效。</p>
       {draft&&fields.map(key=><label key={key}>
        <input type="checkbox" checked={draft[key]}
         onChange={e=>setDraft(old=>old?{...old,[key]:e.target.checked}:old)}/>
        {names[key]}
       </label>)}
       <label htmlFor="v84-grant-reason">修改理由（10–300 字）</label>
       <textarea id="v84-grant-reason" rows={2} minLength={10}
        maxLength={300} value={reason} onChange={e=>setReason(e.target.value)}/>
       <button type="submit" className="primary-button" disabled={!valid||update.isPending}>
        保存站点级 Owner 权限</button>
      </form>}
     <details className="core-tools" open={historyOpen}
      onToggle={e=>setHistoryOpen(e.currentTarget.open)}>
      <summary>最近 100 条权限变更历史</summary>
      {audit.isPending?<p>读取权限变更记录…</p>:
       audit.isError?<p role="alert" className="setup-error">{String(audit.error)}</p>:
       !audit.data?.length?<p>暂无已登记权限变更。</p>:
       <div className="table-scroll" tabIndex={0} role="region" aria-label="Owner 权限变更审计">
        <table><thead><tr>{['目标 Owner','操作者','版本','授予标志','理由','时间']
         .map(h=><th scope="col" key={h}>{h}</th>)}</tr></thead>
         <tbody>{audit.data.map(x=><tr key={x.ownerId+x.newVersion}>
          <td>{x.ownerId}</td><td>{x.actorId}</td>
          <td>{x.oldVersion} → {x.newVersion}</td><td>{x.newGrants}</td>
          <td>{x.reason}</td><td>{x.occurredAt}</td>
         </tr>)}</tbody></table></div>}
     </details>
    </>}
   </>}
  {notice&&<p role="status" className="panel-caption">{notice}</p>}
  {error&&<p role="alert" className="setup-error">{error}</p>}
 </section>
}
