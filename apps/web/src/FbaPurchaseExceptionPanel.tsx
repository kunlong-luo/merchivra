import {useEffect,useRef,useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import ExceptionNoteTemplatePicker from './ExceptionNoteTemplatePicker'
import FbaExceptionPermissionPanel from './FbaExceptionPermissionPanel'
import {getMyExceptionGrants,type ExceptionGrants} from './lib/exception-capabilities-v84'
import {
 getExceptionBoard,postExceptionAction,bulkAcknowledge,getExceptionHistory,
 saveReminderPolicy,getReminderPolicyHistory,getExceptionMetrics,
 bulkAssignCases,getOwnerWorkload,bulkResolveCases,
 preflightBulkResolve,getExceptionBatchActivities,getExceptionBatchDetail,
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
const resolveReasons:Record<string,string>={
 READY:'符合本地完成的基本及来源复核条件',
 OUT_OF_SCOPE:'处理行不属于当前站点最近 100 张货件 / 500 行窗口',
 CASE_NOT_REGISTERED:'尚无已登记的本地人工处理单',
 EVIDENCE_CHANGED:'来源指纹已改变，先刷新并重新确认',
 VERSION_CONFLICT:'其他 Owner 更新了处理单版本，先刷新',
 NOT_ACKNOWLEDGED:'需要先由 Owner 确认待跟进（已完成的单需重新打开）',
 NO_ASSIGNEE:'缺少已分派的负责人',
 VARIANCE_REVIEW_REQUIRED:'V65 本地入仓差异尚未独立复核',
 IDENTITY_REVIEW_REQUIRED:'V75 身份证据尚需第二 Owner 独立确认'
}

export default function FbaPurchaseExceptionPanel({
 tenantId,marketplaceId,onOpenShipment,focusLineId
}:{tenantId:string;marketplaceId:string;onOpenShipment:(id:string)=>void;
  focusLineId?:string}){
 const client=useQueryClient()
 const prefix=['fba-inbound',tenantId,marketplaceId]
 const grants=useQuery({queryKey:[...prefix,'grants-v84'],
  queryFn:()=>getMyExceptionGrants(marketplaceId),retry:false})
 const allowed=(action:keyof ExceptionGrants)=>grants.data?.grants[action]===true
 const query=useQuery({queryKey:[...prefix,'workbench-v77'],
  queryFn:()=>getExceptionBoard(marketplaceId),retry:false})
 const metrics=useQuery({queryKey:[...prefix,'metrics-v78'],
  queryFn:()=>getExceptionMetrics(marketplaceId),retry:false})
 const workload=useQuery({queryKey:[...prefix,'owner-load-v79'],
  queryFn:()=>getOwnerWorkload(marketplaceId),retry:false})
 const lastFocused=useRef('')
 const [filter,setFilter]=useState<'ACTIONABLE'|'ALL'|'OVERDUE'|'RESOLVED'>('ACTIONABLE')
 const [chosen,setChosen]=useState<string[]>([])
 const [selectedId,setSelectedId]=useState('')
 const [operation,setOperation]=useState<ExceptionAction>('ACKNOWLEDGE')
 const [assignee,setAssignee]=useState('')
 const [deadline,setDeadline]=useState('')
 const [note,setNote]=useState('')
 const [bulkNote,setBulkNote]=useState('')
 const [bulkKey,setBulkKey]=useState(()=>crypto.randomUUID())
 const [assignOwner,setAssignOwner]=useState('')
 const [assignNote,setAssignNote]=useState('')
 const [assignKey,setAssignKey]=useState(()=>crypto.randomUUID())
 const [resolveNote,setResolveNote]=useState('')
 const [resolveKey,setResolveKey]=useState(()=>crypto.randomUUID())
 const [resolveConfirmed,setResolveConfirmed]=useState(false)
 const [previewResult,setPreviewResult]=useState<{
  snapshot:string;result:Awaited<ReturnType<typeof preflightBulkResolve>>
 }|null>(null)
 const [showBatches,setShowBatches]=useState(false)
 const [selectedBatch,setSelectedBatch]=useState('')
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
 const allResolvable=selectedRows.length>0&&selectedRows.every(
  row=>row.state==='ACKNOWLEDGED'&&!row.stale&&!!row.assigneeId)
 const selections=selectedRows.map(x=>({lineId:x.lineId,
  expectedVersion:x.version,expectedFingerprint:x.fingerprint}))
 const currentSnapshot=JSON.stringify({marketplaceId,lines:selections})
 const verifiedPreview=previewResult?.snapshot===currentSnapshot?
  previewResult.result:null
 const rows=(data?.items??[]).filter(row=>
  filter==='ALL'||filter==='ACTIONABLE'&&(row.stale||row.state!=='RESOLVED')||
  filter==='OVERDUE'&&['OVERDUE','DUE_SOON','RECHECK'].includes(row.reminder)||
  filter==='RESOLVED'&&row.state==='RESOLVED')
 const refresh=()=>void client.invalidateQueries({queryKey:prefix})
 useEffect(()=>{
  if(!focusLineId||!data)return
  const target=marketplaceId+':'+focusLineId
  if(lastFocused.current===target)return
  const match=data.items.find(row=>row.lineId===focusLineId)
  if(match){
   lastFocused.current=target
   setFilter('ALL');setSelectedId(match.lineId)
   setOperation('ACKNOWLEDGE');setAssignee(match.assigneeId??'')
   onOpenShipment(match.shipmentId)
   document.getElementById('v79-exception-workbench')?.scrollIntoView({block:'start'})
  }else if(!query.isFetching){
   lastFocused.current=target
   setNotice('目标处理单不在当前最近 100 张货件 / 500 行范围，请到原单核查')
  }
 },[focusLineId,marketplaceId,data?.items,query.isFetching])
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
  setBulkKey(crypto.randomUUID());setAssignKey(crypto.randomUUID())
  setResolveKey(crypto.randomUUID());setResolveConfirmed(false)
  setPreviewResult(null)
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
 const assignBatch=useMutation({
  mutationFn:()=>bulkAssignCases(marketplaceId,assignKey,assignOwner,
   selectedRows.map(x=>({lineId:x.lineId,expectedVersion:x.version,
    expectedFingerprint:x.fingerprint})),assignNote.trim()),
  onSuccess:receipt=>{
   setNotice('已为 '+receipt.assignedCount+' 行设置负责人；仍须人工确认来源')
   setError('');setChosen([]);setAssignNote('');setAssignKey(crypto.randomUUID())
   refresh();void client.invalidateQueries({queryKey:['operations',tenantId]})
  },
  onError:(err:Error)=>{setNotice('');setError(err.message)}
 })
 const preflight=useMutation({
  mutationFn:async(payload:{snapshot:string;lines:typeof selections})=>({
   snapshot:payload.snapshot,
   result:await preflightBulkResolve(marketplaceId,payload.lines)
  }),
  onSuccess:checked=>{
   setPreviewResult(checked);setResolveConfirmed(false)
   setError('');setNotice('已完成只读预检；提交时仍将重新核对所有行')
  },
  onError:(err:Error)=>{
   setPreviewResult(null);setResolveConfirmed(false);setNotice('')
   setError('预检未成功：'+err.message)
  }
 })
 const batchDetail=useQuery({
  queryKey:[...prefix,'batch-detail-v84',selectedBatch],
  queryFn:()=>getExceptionBatchDetail(marketplaceId,selectedBatch),
  enabled:showBatches&&!!selectedBatch,retry:false
 })
 const batchActivities=useQuery({
  queryKey:[...prefix,'batch-activities-v83'],
  queryFn:()=>getExceptionBatchActivities(marketplaceId),
  enabled:showBatches,retry:false
 })
 const resolveBatch=useMutation({
  mutationFn:()=>bulkResolveCases(marketplaceId,resolveKey,
   selectedRows.map(x=>({lineId:x.lineId,expectedVersion:x.version,
    expectedFingerprint:x.fingerprint})),resolveNote.trim(),resolveConfirmed),
  onSuccess:receipt=>{
   setNotice('已人工确认并批量完成 '+receipt.resolvedCount+' 条本地处理单；未向 Amazon 写入')
   setError('');setChosen([]);setResolveNote('');setResolveKey(crypto.randomUUID())
   setResolveConfirmed(false);setPreviewResult(null);refresh()
   void client.invalidateQueries({queryKey:['operations',tenantId]})
  },
  onError:(err:Error)=>{
   setNotice('');setError(err.message);setResolveConfirmed(false)
   setPreviewResult(null);refresh()
  }
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
 const permissionForAction:Record<ExceptionAction,keyof ExceptionGrants>={
  ASSIGN:'assign',SET_DUE:'assign',ACKNOWLEDGE:'review',
  REOPEN:'review',RESOLVE:'resolve'
 }
 const canSingle=allowed(permissionForAction[operation])&&!!selected&&
  !single.isPending&&noteValid(note)&&
  (operation!=='ASSIGN'||!!assignee)&&
  (operation!=='SET_DUE'||!!deadline&&!Number.isNaN(new Date(deadline).getTime()))
 function submitAction(e:FormEvent){e.preventDefault();if(canSingle)single.mutate()}
 function submitPolicy(e:FormEvent){e.preventDefault();policyMutation.mutate()}
 const attention=(data?.items??[]).filter(x=>x.reminder==='OVERDUE').length
 const soon=(data?.items??[]).filter(x=>x.reminder==='DUE_SOON').length
 const changed=(data?.items??[]).filter(x=>x.stale).length
 return <section id="v79-exception-workbench" className="core-tools" aria-label="V79 采购与 FBA 异常处理工作台">
  <div className="panel-heading"><div>
   <h3>V77 · 异常处理与期限跟进</h3>
   <p>基于 V76 本地证据分类的人工处理记录。已确认、已完成不等于 Amazon 官方签收，
    不自动修正采购、ASN、入仓件数或库存。</p>
  </div><button type="button" className="refresh-button" disabled={query.isFetching}
    onClick={refresh}>刷新处理工作台</button></div>
  {query.isPending?<p className="empty-state">读取人工处理记录…</p>:
   query.isError?<p className="setup-error" role="alert">{String(query.error)}</p>:
   !data?<p className="empty-state">暂无可展示的本地异常。</p>:<>
    <FbaExceptionPermissionPanel tenantId={tenantId} marketplaceId={marketplaceId}/>
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
    <section className="core-tools" aria-label="V79 负责人工作量">
     <h4>V79 · 负责人工作量（已登记本地处理单）</h4>
     {workload.isPending?<p>正在读取负责人工作量…</p>:
      workload.isError?<p role="alert" className="setup-error">负责人统计暂不可用，不能把缺失当作零</p>:
      workload.data&&<>
       <p className="setup-hint">全部已登记 {workload.data.totalCases} 单 ·
        未完成且未分派 {workload.data.unassigned} 单 ·
        负责人已停用/不可用 {workload.data.inactiveAssignee} 单。
        不包含尚未登记为人工处理单的 FBA 行。</p>
       <div className="table-scroll" role="region" tabIndex={0}
        aria-label="各负责人未完成与逾期处理单">
        <table><thead><tr><th scope="col">负责人</th><th scope="col">待处理</th>
         <th scope="col">已确认待跟进</th><th scope="col">已超期</th></tr></thead>
         <tbody>{workload.data.owners.map(owner=><tr key={owner.userId}>
          <td>{owner.displayName}</td><td>{owner.open}</td>
          <td>{owner.acknowledged}</td><td>{owner.overdue}</td>
         </tr>)}</tbody></table></div>
      </>}
    </section>
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
      e.preventDefault()
      if(selectedRows.length&&assignOwner&&noteValid(assignNote))assignBatch.mutate()
    }}>
     <h4>批量分派负责人（1–50 行，原子提交）</h4>
     <p className="setup-hint">与 V77 批量确认共用请求幂等表及逐行审计。
      任一行来源或版本发生变化时整批回滚；分派不等于已确认。</p>
     <label htmlFor="v79-assignee">选择当前租户有效 Owner</label>
     <select id="v79-assignee" required value={assignOwner}
      onChange={e=>{setAssignOwner(e.target.value);setAssignKey(crypto.randomUUID())}}>
      <option value="">请选择负责人</option>
      {data.assignees.map(person=><option key={person.userId}
       value={person.userId}>{person.displayName}</option>)}
     </select>
     <ExceptionNoteTemplatePicker label="V82 分派理由模板" onApply={value=>{
       setAssignNote(value);setAssignKey(crypto.randomUUID())
      }}/>
      <label htmlFor="v79-assign-note">分派依据（10–300 字）</label>
     <textarea id="v79-assign-note" value={assignNote} minLength={10}
      maxLength={300} rows={2}
      onChange={e=>{setAssignNote(e.target.value);setAssignKey(crypto.randomUUID())}}/>
     <button type="submit" className="primary-button"
      disabled={!allowed('assign')||!selectedRows.length||!assignOwner||
        !noteValid(assignNote)||assignBatch.isPending}>
      为所选 {selectedRows.length} 行批量分派</button>
    </form>
    <form className="team-invite-form" onSubmit={e=>{
      e.preventDefault();if(selectedRows.length&&noteValid(bulkNote))bulk.mutate()
    }}>
     <h4>批量确认已知悉（1–50 行，同一事务）</h4>
     <p className="setup-hint">已选择 {selectedRows.length} 行。每次批量操作使用幂等键；
      如出现并发修改或来源变化，整批回滚并要求刷新。</p>
     <ExceptionNoteTemplatePicker label="V82 批量确认理由模板" onApply={value=>{
       setBulkNote(value);setBulkKey(crypto.randomUUID())
      }}/>
      <label htmlFor="v77-bulk-note">批量确认依据（10–300 字）</label>
     <textarea id="v77-bulk-note" value={bulkNote} maxLength={300} minLength={10}
      onChange={e=>{setBulkNote(e.target.value);setBulkKey(crypto.randomUUID())}} rows={2}/>
     <button type="submit" className="primary-button"
      disabled={!allowed('review')||!selectedRows.length||!noteValid(bulkNote)||bulk.isPending}>
      确认所选 {selectedRows.length} 行</button>
    </form>
    <form className="team-invite-form" onSubmit={e=>{
      e.preventDefault()
      if(allResolvable&&verifiedPreview?.allEligible&&noteValid(resolveNote)
         &&resolveConfirmed)resolveBatch.mutate()
     }}>
      <h4>V82 · 经人工确认后批量完成本地处理单</h4>
      <p className="setup-hint">仅允许选择已确认、已分派且来源未变化的处理单，
       最多 50 行；原有身份审核和差异复核条件仍由后端逐行检查。
       有一行不符合要求或版本变化则整批回滚，不会修改 Amazon 资料。</p>
      <p className="setup-hint">本次选择 {selectedRows.length} 行
       · {allResolvable?'本页基本状态满足要求，仍须服务端预检':'包含未确认、未分派或来源已变化的行，不能完成'}</p>
      <button type="button" className="refresh-button" disabled={!selectedRows.length||
       preflight.isPending||resolveBatch.isPending||!allowed('resolve')} onClick={()=>{
        setResolveConfirmed(false)
        preflight.mutate({snapshot:currentSnapshot,lines:selections})
       }}>{preflight.isPending?'正在预检…':'预检所选处理单（不写入）'}</button>
      {verifiedPreview&&<section aria-label="V83 批量完成预检详情">
       <p className="panel-caption" role="status">预检结果：
        可完成 {verifiedPreview.eligibleCount} / {verifiedPreview.selectedCount} 行；
        {verifiedPreview.allEligible?'仍须人工确认并提交':'存在阻断条件，禁止整批提交'}。
        这是只读快照，不能代替正式提交时的重新校验。</p>
       <div className="table-scroll" role="region" tabIndex={0}
        aria-label="逐行来源、版本和独立审核预检结果">
        <table><thead><tr>{['本地货件 / SKU','状态','阻断原因 / 下一步']
         .map(h=><th scope="col" key={h}>{h}</th>)}</tr></thead>
         <tbody>{verifiedPreview.lines.map(line=><tr key={line.lineId}>
          <td>{line.shipmentReference??'窗口外处理单'} · {line.sellerSku??line.lineId}</td>
          <td>{line.eligible?'可继续':'必须处理阻断'}</td>
          <td>{resolveReasons[line.reasonCode]??line.reasonCode}</td>
         </tr>)}</tbody></table></div>
      </section>}
      <ExceptionNoteTemplatePicker label="V82 批量完成理由模板" onApply={value=>{
       setResolveNote(value);setResolveKey(crypto.randomUUID());setResolveConfirmed(false)
      }}/>
      <label htmlFor="v82-resolve-note">本地完成依据（10–300 字）</label>
      <textarea id="v82-resolve-note" value={resolveNote} rows={3}
       minLength={10} maxLength={300}
       onChange={e=>{
        setResolveNote(e.target.value);setResolveKey(crypto.randomUUID())
        setResolveConfirmed(false)
       }}/>
      <label htmlFor="v82-resolve-confirm">
       <input id="v82-resolve-confirm" type="checkbox" checked={resolveConfirmed}
        onChange={e=>setResolveConfirmed(e.target.checked)}
        disabled={!verifiedPreview?.allEligible||!allowed('resolve')}/>
       我已逐条核实所选处理单来源、负责人及独立复核要求，明确同意仅执行本地 RESOLVE
      </label>
      <button type="submit" className="primary-button"
       disabled={!allowed('resolve')||!allResolvable||!verifiedPreview?.allEligible||
        !noteValid(resolveNote)||!resolveConfirmed||resolveBatch.isPending}>
       人工确认完成所选 {selectedRows.length} 行（原子提交）</button>
     </form>
     <details className="core-tools" open={showBatches}
      onToggle={e=>setShowBatches(e.currentTarget.open)}>
      <summary>V83 · 最近 50 次已持久化批次操作记录</summary>
      <p className="setup-hint">来自 V77/V79/V82 原批次幂等账本，
       仅列出操作人、动作、成功行数、幂等键和提交时间。
       具体处理单的逐行动作请进入该行的“处理 / 历史”查看；
       本页不伪造不存在的批次与单行审计关联。</p>
      {batchActivities.isPending?<p>正在读取批次账本…</p>:
       batchActivities.isError?<p className="setup-error" role="alert">
        批次账本读取失败：{String(batchActivities.error)}</p>:
       !batchActivities.data?.length?<p>暂无已登记批次动作。</p>:
       <div className="table-scroll" role="region" tabIndex={0} aria-label="批次提交审计摘要">
        <table><thead><tr>{['动作','成功行数','操作人 ID','幂等键','提交时间']
         .map(h=><th scope="col" key={h}>{h}</th>)}</tr></thead>
         <tbody>{batchActivities.data.map(batch=><tr key={batch.requestKey}>
          <td>{batch.actionType}</td><td>{batch.affectedCount}</td>
          <td className="small-cell">{batch.actorId}</td>
          <td className="small-cell"><button type="button" className="text-button"
          onClick={()=>setSelectedBatch(batch.requestKey)}
          aria-pressed={selectedBatch===batch.requestKey}>
          {batch.requestKey}</button></td>
          <td>{timeText(batch.createdAt)}</td>
         </tr>)}</tbody></table></div>}
      {selectedBatch&&<section className="core-tools" aria-label="V84 批次具体操作行审计">
       <h4>批次 {selectedBatch} · 实际逐行审计</h4>
       {batchDetail.isPending?<p>核对批次外键审计…</p>:
        batchDetail.isError?<p className="setup-error" role="alert">
         批次审计不可用：{String(batchDetail.error)}</p>:
        batchDetail.data&&<>
         <p className="setup-hint">明确关联 {batchDetail.data.linkedCount} /
          {batchDetail.data.batch.affectedCount} 行。
          {batchDetail.data.lineageComplete?'审计外键关联完整':
          '历史批次无足够外键关联：不可推断具体行，请在逐行历史核查'}。</p>
         {batchDetail.data.linkedActions.length>0&&
          <div className="table-scroll" role="region" tabIndex={0}
           aria-label="与当前批次真实关联的审计行">
           <table><thead><tr>{['货件 / SKU','动作','结果状态','源处理单']
            .map(h=><th scope="col" key={h}>{h}</th>)}</tr></thead>
            <tbody>{batchDetail.data.linkedActions.map(line=><tr key={line.lineId}>
             <td>{line.shipmentReference} · {line.sellerSku}</td>
             <td>{line.action}</td><td>{line.newState}</td>
             <td>{data.items.some(row=>row.lineId===line.lineId)?
              <button type="button" className="text-button"
               onClick={()=>{
                const found=data.items.find(row=>row.lineId===line.lineId)
                if(found)pick(found)
               }}>查看本地逐行历史</button>:'超出当前货件窗口，请在源处理单核查'}</td>
            </tr>)}</tbody></table>
          </div>}
        </>}
      </section>}
     </details>
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
     <ExceptionNoteTemplatePicker label="V82 单条处理理由模板" onApply={setNote}/>
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
