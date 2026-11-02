import {useState} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {getPriorityRules,getPriorityAudit,savePriorityRule,priorityReasons}
 from './lib/exception-priority-v82'

export default function OpsExceptionPriorityPanel({tenantId,marketplaceId}:{
 tenantId:string;marketplaceId:string
}){
 const client=useQueryClient()
 const prefix=['operations',tenantId]
 const rules=useQuery({queryKey:[...prefix,'priority-rules-v82',marketplaceId],
  queryFn:()=>getPriorityRules(marketplaceId),retry:false})
 const [reason,setReason]=useState('NEEDS_OWNER')
 const [priority,setPriority]=useState('P1')
 const [note,setNote]=useState('')
 const [message,setMessage]=useState('')
 const [error,setError]=useState('')
 const [auditOpen,setAuditOpen]=useState(false)
 const audit=useQuery({queryKey:[...prefix,'priority-audit-v82',marketplaceId],
  queryFn:()=>getPriorityAudit(marketplaceId),enabled:auditOpen,retry:false})
 const current=rules.data?.find(x=>x.reasonCode===reason)
 const priorities=['P0','P1','P2','P3'].filter(x=>!current||x<=current.minimumPriority)
 const valid=note.trim().length>=10&&note.trim().length<=300
 const save=useMutation({
  mutationFn:()=>{
   if(!current)throw Error('优先级规则尚未加载')
   return savePriorityRule(marketplaceId,reason,{
    priority,expectedVersion:current.version,note:note.trim()})
  },
  onSuccess:()=>{
   setError('');setMessage('优先级规则已保存并追加审计；所有必要的高风险等级下限仍生效。')
   setNote('')
   void client.invalidateQueries({queryKey:prefix})
  },
  onError:(err:Error)=>{setMessage('');setError(err.message)}
 })
 return <section className="core-tools" aria-label="V82 站点级异常优先级配置">
  <h3>V82 · 可配置处理优先级</h3>
  <p className="setup-hint">仅改变 V80 建议列表的人工处理先后顺序，
   不影响 V78 Ops 规则、人工期限或原始证据，也不触发自动分派。
   来源变更和超期必须保持 P0；身份与独立差异复核不得低于 P1。</p>
  {rules.isPending?<p className="empty-state">读取优先级设置…</p>:
   rules.isError?<p role="alert" className="setup-error">设置读取失败：{String(rules.error)}</p>:
   <form className="team-invite-form" onSubmit={e=>{
    e.preventDefault();if(current&&valid&&priorities.includes(priority))save.mutate()
   }}>
    <label htmlFor="v82-priority-reason">选择固定证据规则</label>
    <select id="v82-priority-reason" value={reason}
     onChange={e=>{
      const next=e.target.value
      setReason(next);setPriority(rules.data?.find(x=>x.reasonCode===next)?.priority??'P1')
      setNote('');setError('')
     }}>
     {rules.data?.map(x=><option key={x.reasonCode} value={x.reasonCode}>
      {priorityReasons[x.reasonCode]} · 当前 {x.priority}
     </option>)}
    </select>
    {current&&<>
     <p className="setup-hint">当前 {current.priority} · 安全等级下限 {current.minimumPriority} ·
      配置版本 {current.version}。再次保存时要求版本匹配，防止覆盖其他 Owner 更改。</p>
     <label htmlFor="v82-priority-select">建议处理等级（只能保持或提高风险等级）</label>
     <select id="v82-priority-select" value={priorities.includes(priority)?priority:current.priority}
      onChange={e=>{setPriority(e.target.value);setError('')}}>
      {priorities.map(x=><option key={x} value={x}>{x}</option>)}
     </select>
     <label htmlFor="v82-priority-note">调整依据（10–300 字，进入审计）</label>
     <textarea id="v82-priority-note" value={note}
      minLength={10} maxLength={300} rows={2} required
      onChange={e=>setNote(e.target.value)}/>
     <button className="primary-button" type="submit"
      disabled={save.isPending||!valid||!priorities.includes(priority)}>
      保存站点建议优先级</button>
    </>}
   </form>}
  {message&&<p className="panel-caption" role="status">{message}</p>}
  {error&&<p className="setup-error" role="alert">{error}</p>}
  <details className="core-tools" open={auditOpen}
   onToggle={e=>setAuditOpen(e.currentTarget.open)}>
   <summary>查看最近 100 条规则变更审计</summary>
   {audit.isPending?<p>读取规则变更历史…</p>:
    audit.isError?<p className="setup-error" role="alert">{String(audit.error)}</p>:
    !audit.data?.length?<p>暂无规则配置历史。</p>:
    <div className="table-scroll" role="region" tabIndex={0} aria-label="优先级规则变更历史">
     <table><thead><tr>{['规则','旧等级','新等级','配置版本','理由','操作时间']
      .map(x=><th scope="col" key={x}>{x}</th>)}</tr></thead>
      <tbody>{audit.data.map((x,i)=><tr key={x.reasonCode+x.newVersion+i}>
       <td>{priorityReasons[x.reasonCode]}</td><td>{x.oldPriority??'默认'}</td>
       <td>{x.newPriority}</td><td>{x.newVersion}</td>
       <td>{x.note}</td><td>{x.occurredAt}</td>
      </tr>)}</tbody></table>
    </div>}
  </details>
 </section>
}
