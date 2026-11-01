import {useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {claimFinding,releaseFinding,snoozeFinding,unsnoozeFinding,
 fetchFindingAttentionHistory,type Finding} from './lib/operations'
import {formatSourceTime} from './lib/core-view'

export default function OpsAttentionControls({finding,tenantId,userId}:{
 finding:Finding;tenantId:string;userId:string
}){
 const cache=useQueryClient()
 const [showSnooze,setShowSnooze]=useState(false)
 const [showHistory,setShowHistory]=useState(false)
 const [minutes,setMinutes]=useState<15|60|240|1440>(60)
 const [note,setNote]=useState('')
 const [error,setError]=useState('')
 const mine=finding.assignedTo===userId,other=!!finding.assignedTo&&!mine
 const active=finding.state==='OPEN'||finding.state==='ACKNOWLEDGED'
 const suppressed=!!finding.snoozedUntil&&Date.parse(finding.snoozedUntil)>Date.now()
 const refresh=()=>void cache.invalidateQueries({queryKey:['operations',tenantId]})
 const claim=useMutation({mutationFn:()=>claimFinding(finding.id),
  onSuccess:()=>{setError('');refresh()},onError:(e:Error)=>setError(e.message)})
 const release=useMutation({mutationFn:()=>releaseFinding(finding.id),
  onSuccess:()=>{setError('');refresh()},onError:(e:Error)=>setError(e.message)})
 const snooze=useMutation({mutationFn:()=>snoozeFinding(finding.id,minutes,note),
  onSuccess:()=>{setShowSnooze(false);setNote('');setError('');refresh()},
  onError:(e:Error)=>setError(e.message)})
 const unsnooze=useMutation({mutationFn:()=>unsnoozeFinding(finding.id),
  onSuccess:()=>{setError('');refresh()},onError:(e:Error)=>setError(e.message)})
 const history=useQuery({queryKey:['operations',tenantId,'attention',finding.id],
  queryFn:()=>fetchFindingAttentionHistory(finding.id),enabled:showHistory,retry:false})
 const busy=claim.isPending||release.isPending||snooze.isPending||unsnooze.isPending
 function submit(e:FormEvent){e.preventDefault();if(note.trim().length>=10&&note.length<=300)snooze.mutate()}
 return <div className="ops-row-actions" aria-label="Owner 待办处理">
  {active&&!finding.assignedTo&&<button type="button" className="text-button"
   disabled={busy||!userId} onClick={()=>claim.mutate()}>认领给自己</button>}
  {mine&&<button type="button" className="text-button"
   disabled={busy} onClick={()=>release.mutate()}>释放认领</button>}
  {other&&<span className="setup-hint">其他 Owner 已认领</span>}
  {active&&!other&&!suppressed&&<button type="button" className="text-button"
   disabled={busy} onClick={()=>setShowSnooze(v=>!v)}>暂缓提醒</button>}
  {active&&suppressed&&<span className="setup-hint">
   暂缓至 {formatSourceTime(finding.snoozedUntil!)}</span>}
  {active&&suppressed&&!other&&<button type="button" className="text-button"
   disabled={busy} onClick={()=>unsnooze.mutate()}>恢复提醒</button>}
  <button type="button" className="text-button"
   onClick={()=>setShowHistory(v=>!v)}>{showHistory?'收起':'审计'}</button>
  {showSnooze&&active&&!other&&<form onSubmit={submit} className="team-invite-form">
   <label htmlFor={'ops-v67-duration-'+finding.id}>暂缓时长</label>
   <select id={'ops-v67-duration-'+finding.id} value={minutes}
    onChange={e=>setMinutes(Number(e.target.value) as 15|60|240|1440)}>
    <option value={15}>15 分钟</option><option value={60}>1 小时</option>
    <option value={240}>4 小时</option><option value={1440}>24 小时</option>
   </select>
   <label htmlFor={'ops-v67-note-'+finding.id}>原因（10–300 字）</label>
   <textarea id={'ops-v67-note-'+finding.id} required minLength={10} maxLength={300}
    rows={2} value={note} onChange={e=>setNote(e.target.value)}/>
   <button type="submit" className="primary-button"
    disabled={busy||note.trim().length<10||note.length>300}>保存暂缓</button>
  </form>}
  {showHistory&&(history.isPending?<span>读取审计…</span>:
   history.isError?<span className="setup-error">{String(history.error)}</span>:
   !history.data?.length?<span>暂无认领与暂缓审计</span>:
   <ul>{history.data.slice(0,20).map((a,i)=><li key={a.createdAt+a.action+i}>
    {formatSourceTime(a.createdAt)} · {a.action}
    {a.note?' · '+a.note:''}</li>)}</ul>)}
  {error&&<span className="setup-error" role="alert">{error}</span>}
 </div>
}
