import {useEffect,useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {
 getExceptionBoard,postExceptionAction,bulkAcknowledge,getExceptionHistory,
 saveReminderPolicy,getReminderPolicyHistory,getExceptionMetrics,
 type ExceptionAction,type ExceptionItem
} from './lib/fba-purchase-exceptions'

const statuses={OPEN:'待处理',ACKNOWLEDGED:'已确认待跟进',RESOLVED:'本地处理完成'}
const alerts={NONE:'未到提醒时间',DISABLED:'提醒已关闭',DUE_SOON:'即将到期',
 OVERDUE:'已超期',RECHECK:'来源已变化，须重新确认'}
const actions:Record<ExceptionAction,string>={
 ASSIGN:'分配负责人',ACKNOWLEDGE:'确认已知悉',SET_DUE:'调整处理期限',
 RESOLVE:'完成本地处理',REOPEN:'重新打开'
}
const timeText=(value:string|null)=>value?new Date(value).toLocaleString():'—'

export default function FbaPurchaseExceptionPanel({
 tenantId,marketplaceId,onOpenShipment
}:{tenantId:string;marketplaceId:string;onOpenShipment:(id:string)=>void}){
 const client=useQueryClient()
 const prefix=['fba-inbound',tenantId,marketplaceId]
 const query=useQuery({queryKey:[...prefix,'workbench-v77'],
  queryFn:()=>getExceptionBoard(marketplaceId),retry:false})
 const metrics=useQuery({queryKey:[...prefix,'metrics-v78'],
  queryFn:()=>getExceptionMetrics(marketplaceId),retry:false})
 const [filter,setFilter]=useState<'ACTIONABLE'|'ALL'|'OVERDUE'|'RESOLVED'>('ACTIONABLE')
 const [chosen,setChosen]=useState<string[]>([])
 const [selectedId,setSelectedId]=useState('')
 const [operation,setOperation]=useState<ExceptionAction>('ACKNOWLEDGE')
 const [assignee,setAssignee]=useState('')
 const [deadline,setDeadline]=useState('')
 const [note,setNote]=useState('')
 const [bulkNote,setBulkNote]=useState('')
 const [bulkKey,setBulkKey]=useState(()=>crypto.randomUUID())
 const [openHistory,setOpenHistory]=useState(false)
 const [openPolicyHistory,setOpenPolicyHistory]=useState(false)
 const [enabled,setEnabled]=useState(true)
 const [lead,setLead]=useState(24)
 const [defaultDue,setDefaultDue]=useState(72)
 const [policyNote,setPolicyNote]=useState('')
 const [notice,setNotice]=useState('')
 const [error,setError]=useState('')
 const data=query.data
 const selected=data?.items.find(row=>row.lineId===selectedId)
 const selectedRows=(data?.items??[]).filter(row=>chosen.includes(row.lineId))
 const rows=(data?.items??[]).filter(row=>
  filter==='ALL'||filter==='ACTIONABLE'&&(row.stale||row.state!=='RESOLVED')||
  filter==='OVERDUE'&&['OVERDUE','DUE_SOON','RECHECK'].includes(row.reminder)||
  filter==='RESOLVED'&&row.state==='RESOLVED')
 const refresh=()=>void client.invalidateQueries({queryKey:prefix})
 useEffect(()=>{
  if(!data)return
  setEnabled(data.policy.enabled)
  setLead(data.policy.leadHours)
  setDefaultDue(data.policy.defaultDueHours)
 },[data?.policy.version,marketplaceId])
 const pick=(row:ExceptionItem)=>{
  setSelectedId(row.lineId);setOpenHistory(false);setNote('')
  setOperation('ACKNOWLEDGE');setAssignee(row.assigneeId??'')
  setDeadline('');setError('')
 }
 const toggle=(id:string)=>{
  setChosen(old=>old.includes(id)?old.filter(x=>x!==id):
   old.length<50?[...old,id]:old)
  setBulkKey(crypto.randomUUID())
 }
 const single=useMutation({
  mutationFn:()=>{
   if(!selected)throw new Error('请先选择异常行')
   return postExceptionAction(marketplaceId,selected.lineId,{
    action:operation,expectedVersion:selected.version,
    expectedFingerprint:selected.fingerprint,
    assigneeId:operation==='ASSIGN'?assignee||null:null,
    dueAt:operation==='SET_DUE'&&deadline?new Date(deadline).toISOString():null,
    note:note.trim()
   })
  },
  onSuccess:()=>{
   setNotice('本地处理动作已登记，并保留历史。');setError('')
   setNote('');refresh()
  },
  onError:(err:Error)=>{setNotice('');setError(err.message)}
 })
 const bulk=useMutation({
  mutationFn:()=>bulkAcknowledge(marketplaceId,bulkKey,
   selectedRows.map(x=>({lineId:x.lineId,expectedVersion:x.version,
    expectedFingerprint:x.fingerprint})),bulkNote.trim()),
  onSuccess:result=>{
   setNotice('已批量确认 '+result.acknowledgedCount+' 条本地异常')
   setError('');setChosen([]);setBulkNote('');setBulkKey(crypto.randomUUID());refresh()
  },
  onError:(err:Error)=>{setNotice('');setError(err.message)}
 })
 const policyMutation=useMutation({
  mutationFn:()=>{
   if(!data)throw new Error('策略尚未加载')
   return saveReminderPolicy(marketplaceId,{enabled,leadHours:lead,
    defaultDueHours:defaultDue,expectedVersion:data.policy.version,
    note:policyNote.trim()})
  },
  onSuccess:()=>{
   setNotice('站内期限提醒策略已保存，设置变更可审计。')
   setError('');setPolicyNote('');refresh()
  },
  onError:(err:Error)=>{setNotice('');setError(err.message)}
 })
 const history=useQuery({
  queryKey:[...prefix,'exception-history',selectedId],
  queryFn:()=>getExceptionHistory(marketplaceId,selectedId),
  enabled:!!selectedId&&openHistory,retry:false
 })
 const policyHistory=useQuery({
  queryKey:[...prefix,'exception-policy-history'],
  queryFn:()=>getReminderPolicyHistory(marketplaceId),
  enabled:openPolicyHistory,retry:false
 })
 const noteValid=(text:string)=>text.trim().length>=10&&text.trim().length<=300
 const canSingle=!!selected&&!single.isPending&&noteValid(note)&&
  (operation!=='ASSIGN'||!!assignee)&&
  (operation!=='SET_DUE'||!!deadline&&!Number.isNaN(new Date(deadline).getTime()))
 function submitAction(e:FormEvent){e.preventDefault();if(canSingle)single.mutate()}
 function submitPolicy(e:FormEvent){e.preventDefault();policyMutation.mutate()}
 const attention=(data?.items??[]).filter(x=>x.reminder==='OVERDUE').length
 const soon=(data?.items??[]).filter(x=>x.reminder==='DUE_SOON').length
 const changed=(data?.items??[]).filter(x=>x.stale).length
 return <section className="core-tools" aria-label="V77 采购与 FBA 异常处理工作台">
  <div className="panel-heading"><div>
   <h3>V77 · 异常处理与期限跟进</h3>
   <p>基于 V76 本地证据分类的人工处理记录。已确认、已完成不等于 Amazon 官方签收，
    不自动修正采购、ASN、入仓件数或库存。</p>
  </div><button type="button" className="refresh-button" disabled={query.isFetching}
    onClick={refresh}>刷新处理工作台</button></div>
  {query.isPending?<p className="empty-state">读取人工处理记录…</p>:
   query.isError?<p className="setup-error" role="alert">{String(query.error)}</p>:
   !data?<p className="empty-state">暂无可展示的本地异常。</p>:<>
    <p className="ops-summary" role="status">
     本页 {data.items.length} 行 · 已超期 {attention} · 临近到期 {soon} · 来源变化 {changed} ·
     最近 {data.shipmentScope} 张本地货件
    </p>
    <div className="ops-summary" role="status" aria-label="V78 本地处理单 SLA">
     {metrics.isPending?'读取已登记处理单时效…':
      metrics.isError?'SLA 统计读取失败，不能将缺失视为零':
      metrics.data?<span>V78 已登记处理单 {metrics.data.total} · 待处理 {metrics.data.open} ·
       已确认 {metrics.data.acknowledged} · 本地完成 {metrics.data.resolved} ·
       当前超期 {metrics.data.overdue} ·
       平均完成耗时 {metrics.data.meanResolutionHours===null?'样本不足':
         metrics.data.meanResolutionHours+' 小时'}</span>:null}
    </div>
    <p className="panel-caption">SLA 统计覆盖本店铺全部已登记的本地处理单（不限于本页 100 货件/500 行），
     按最后一次人工完成事件计算，不代表 Amazon 入仓 SLA。巡检可将证据变化、超期同步进统一运营待办。</p>
    {data.truncated&&<p className="setup-error" role="alert">
     已触及 500 行读取上限，本页处理与汇总不代表全部历史。</p>}
    <label htmlFor="v77-filter">工作台筛选</label>
    <select id="v77-filter" value={filter}
     onChange={e=>setFilter(e.target.value as typeof filter)}>
     <option value="ACTIONABLE">待处理 / 来源待复核</option>
     <option value="OVERDUE">已超期 / 临近到期 / 来源变化</option>
     <option value="RESOLVED">本地处理完成</option>
     <option value="ALL">全部</option>
    </select>
    {!rows.length?<p className="empty-state">当前筛选无记录。</p>:
    <div className="table-scroll" role="region" tabIndex={0}
      aria-label="异常负责人、处理期限与历史入口">
     <table><thead><tr>
      <th scope="col">批选</th><th scope="col">货件 / SKU</th>
      <th scope="col">来源风险</th><th scope="col">本地处理</th>
      <th scope="col">负责人 / 期限</th><th scope="col">操作</th>
     </tr></thead><tbody>{rows.map(row=><tr key={row.lineId}>
      <td><input type="checkbox" aria-label={'选择 '+row.sellerSku}
        checked={chosen.includes(row.lineId)} disabled={row.state==='RESOLVED'}
        onChange={()=>toggle(row.lineId)}/></td>
      <td><strong>{row.shipmentReference}</strong>
       <div className="secondary-cell">{row.sellerSku}</div></td>
      <td>{row.severity} · {row.findingCode}</td>
      <td>{statuses[row.state]}{row.stale&&<strong> · 来源变化</strong>}
       <div className="secondary-cell">{alerts[row.reminder]}</div></td>
      <td>{data.assignees.find(x=>x.userId===row.assigneeId)?.displayName??'未分配'}
       <div className="secondary-cell">{timeText(row.dueAt)}</div></td>
      <td><button type="button" className="text-button"
        aria-pressed={selectedId===row.lineId} onClick={()=>pick(row)}>处理 / 历史</button>
       <button type="button" className="text-button"
        onClick={()=>onOpenShipment(row.shipmentId)}>核查原货件</button></td>
     </tr>)}</tbody></table>
    </div>}
    <form className="team-invite-form" onSubmit={e=>{
      e.preventDefault();if(selectedRows.length&&noteValid(bulkNote))bulk.mutate()
    }}>
     <h4>批量确认已知悉（1–50 行，同一事务）</h4>
     <p className="setup-hint">已选择 {selectedRows.length} 行。每次批量操作使用幂等键；
      如出现并发修改或来源变化，整批回滚并要求刷新。</p>
     <label htmlFor="v77-bulk-note">批量确认依据（10–300 字）</label>
     <textarea id="v77-bulk-note" value={bulkNote} maxLength={300} minLength={10}
      onChange={e=>{setBulkNote(e.target.value);setBulkKey(crypto.randomUUID())}} rows={2}/>
     <button type="submit" className="primary-button"
      disabled={!selectedRows.length||!noteValid(bulkNote)||bulk.isPending}>
      确认所选 {selectedRows.length} 行</button>
    </form>
    {selected&&<form className="team-invite-form" onSubmit={submitAction}>
     <h4>处理：{selected.shipmentReference} · {selected.sellerSku}</h4>
     <p className="setup-hint">当前状态：{statuses[selected.state]} ·
      版本 {selected.version} · {selected.stale?'来源已发生变化，须重新确认':
       '来源指纹已核对'}</p>
     <label htmlFor="v77-action">处理动作</label>
     <select id="v77-action" value={operation}
      onChange={e=>setOperation(e.target.value as ExceptionAction)}>
      {Object.entries(actions).map(([key,label])=>
       <option value={key} key={key}>{label}</option>)}
     </select>
     {operation==='ASSIGN'&&<>
      <label htmlFor="v77-assignee">负责人（当前租户有效 Owner）</label>
      <select id="v77-assignee" required value={assignee}
       onChange={e=>setAssignee(e.target.value)}>
       <option value="">选择负责人</option>
       {data.assignees.map(person=><option key={person.userId}
        value={person.userId}>{person.displayName}</option>)}
      </select>
     </>}
     {operation==='SET_DUE'&&<>
      <label htmlFor="v77-due">新的处理期限（本地时间）</label>
      <input id="v77-due" required type="datetime-local"
       value={deadline} onChange={e=>setDeadline(e.target.value)}/>
     </>}
     <label htmlFor="v77-note">操作理由 / 处理依据（10–300 字）</label>
     <textarea id="v77-note" required minLength={10} maxLength={300}
      rows={3} value={note} onChange={e=>setNote(e.target.value)}/>
     <button type="submit" className="primary-button" disabled={!canSingle}>
      {actions[operation]} · 保留审计</button>
     <p className="setup-hint">“完成本地处理”需先确认；本地超报必须先经 V65
      独立差异复核，待第二 Owner 关联确认须先经 V75 审核。</p>
     <button type="button" className="text-button"
      onClick={()=>setOpenHistory(x=>!x)}>
      {openHistory?'收起处理历史':'查看处理历史（最多 100 条）'}</button>
     {openHistory&&(history.isPending?<p>正在读取处理历史…</p>:
      history.isError?<p className="setup-error" role="alert">{String(history.error)}</p>:
      !history.data?.length?<p>当前没有已登记的处理动作。</p>:
      <ol>{history.data.map((h,i)=><li key={h.occurredAt+i}>
       {timeText(h.occurredAt)} · {actions[h.action]} ·
       {h.oldState??'首次'} → {statuses[h.newState]} ·
       {h.note} · 操作人 {h.actorId.slice(0,8)}
      </li>)}</ol>)}
    </form>}
    <form className="team-invite-form" onSubmit={submitPolicy}>
     <h4>可配置站内提醒</h4>
     <p className="setup-hint">只在页面查询时判定临期、逾期和来源变更；
      默认关闭邮件、推送和自动扫描。不创建第二套消息系统。</p>
     <label><input type="checkbox" checked={enabled}
      onChange={e=>setEnabled(e.target.checked)}/> 启用期限提示</label>
     <label htmlFor="v77-lead">提前提醒小时数（1–168）</label>
     <input id="v77-lead" type="number" min={1} max={168} required value={lead}
      onChange={e=>setLead(Number(e.target.value))}/>
     <label htmlFor="v77-default">新处理单默认期限小时数（1–720）</label>
     <input id="v77-default" type="number" min={1} max={720} required
      value={defaultDue} onChange={e=>setDefaultDue(Number(e.target.value))}/>
     <label htmlFor="v77-policy-note">变更理由（10–300 字）</label>
     <textarea id="v77-policy-note" rows={2} required maxLength={300}
      value={policyNote} onChange={e=>setPolicyNote(e.target.value)}/>
     <button className="refresh-button" type="submit"
      disabled={policyMutation.isPending||!noteValid(policyNote)||
       !Number.isInteger(lead)||lead<1||lead>168||
       !Number.isInteger(defaultDue)||defaultDue<1||defaultDue>720}>
      保存提醒策略（当前版本 {data.policy.version}）</button>
     <button type="button" className="text-button"
      onClick={()=>setOpenPolicyHistory(x=>!x)}>查看策略审计历史</button>
     {openPolicyHistory&&(policyHistory.isPending?<p>正在读取策略历史…</p>:
      policyHistory.isError?<p className="setup-error" role="alert">
       {String(policyHistory.error)}</p>:
      !policyHistory.data?.length?<p>尚无策略变更。</p>:
      <ol>{policyHistory.data.map((h,i)=><li key={h.newVersion+i}>
       {timeText(h.occurredAt)} · 版本 {h.newVersion} ·
       {h.newEnabled?'启用':'关闭'} · 提前 {h.newLeadHours} 小时 ·
       默认 {h.newDefaultDueHours} 小时 · {h.note}
      </li>)}</ol>)}
    </form>
   </>}
  {notice&&<p className="panel-caption" role="status">{notice}</p>}
  {error&&<p className="setup-error" role="alert">{error}</p>}
  <p className="panel-caption">所有提醒只依据本地人工期限；不代表平台 SLA 或
   Amazon 已发货 / 已签收。原始本地货件、PO、ASN 仍通过各自模块处理。</p>
 </section>
}
