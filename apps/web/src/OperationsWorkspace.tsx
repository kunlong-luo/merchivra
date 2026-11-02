import {useState} from 'react'
import SyncTaskMonitorPanel from './SyncTaskMonitorPanel'
import OpsRulesPanel from './OpsRulesPanel'
import OpsAttentionControls from './OpsAttentionControls'
import OpsExceptionInsightsPanel from './OpsExceptionInsightsPanel'
import {fetchViewer} from './lib/auth'
import {useMutation,useQueries,useQuery,useQueryClient} from '@tanstack/react-query'
import {getMyExceptionGrants} from './lib/exception-capabilities-v84'
import {fetchFindings,fetchOpsSummary,scanOperations,changeFindingState,ruleNames,severityNames,stateNames} from './lib/operations'
import type {FindingFilter,FindingState} from './lib/operations'
import {formatSourceTime,sourceLabel} from './lib/core-view'
export default function OperationsWorkspace({tenantId,onOpenFbaException}:{
 tenantId:string;onOpenFbaException:(marketplaceId:string,lineId:string)=>void
}){
 const client=useQueryClient()
 const [filter,setFilter]=useState<FindingFilter>('ACTIVE'),[message,setMessage]=useState(''),[failure,setFailure]=useState(''),[editing,setEditing]=useState('')
 const viewer=useQuery({queryKey:['auth','me'],queryFn:fetchViewer,staleTime:0})
 const summary=useQuery({queryKey:['operations',tenantId,'summary'],queryFn:fetchOpsSummary,staleTime:30000})
 const findings=useQuery({queryKey:['operations',tenantId,'findings',filter],queryFn:()=>fetchFindings(filter),staleTime:30000})
 const fbaSites=[...new Set((findings.data??[]).filter(x=>
  x.ruleCode==='FBA_PO_EVIDENCE_CHANGED'||
  x.ruleCode==='FBA_PO_EXCEPTION_OVERDUE').map(x=>x.marketplaceId))]
 const fbaAccess=useQueries({queries:fbaSites.map(id=>({
  queryKey:['operations',tenantId,'fba-capabilities-v85',id],
  queryFn:()=>getMyExceptionGrants(id),staleTime:15000,retry:false
 }))})
 const fbaCan=(marketplaceId:string,cap:'assign'|'review'|'resolve')=>{
  const position=fbaSites.indexOf(marketplaceId)
  return position>=0&&fbaAccess[position]?.data?.grants[cap]===true
 }
 const isFba=(code:string)=>code==='FBA_PO_EVIDENCE_CHANGED'||
  code==='FBA_PO_EXCEPTION_OVERDUE'
 const refresh=()=>void client.invalidateQueries({queryKey:['operations',tenantId]})
 const scan=useMutation({mutationFn:scanOperations,onSuccess:r=>{setFailure('');setMessage(`已完成巡检：识别 ${r.matchedCount} 个符合规则的数据项，清除 ${r.clearedCount} 个过期待办。`);refresh()},onError:(e:Error)=>{setFailure(e.message);setMessage('')}})
 const change=useMutation({mutationFn:({id,state}:{id:string;state:FindingState})=>changeFindingState(id,state),onMutate:a=>setEditing(a.id),onSuccess:()=>{setMessage('待办状态已保存');setFailure('');refresh()},onError:(e:Error)=>setFailure(e.message),onSettled:()=>setEditing('')})
 return <div className="ops-workspace">
 <div className="core-intro"><strong>运营自动化 · PostgreSQL 任务队列</strong><p>根据已有 Listing、订单、FBA 入仓、离线 Ads 及采购/FBA 本地证据变化与人工处理超期生成待办，不连接 Amazon、不自动采购或发送客户消息；保留 MOCK/LIVE 来源。</p></div>
 {summary.isError&&<p className="setup-error" role="alert">
  当前运营待办统计暂不可用，不能将失败数据当作零：
  {summary.error instanceof Error?summary.error.message:'未知错误'}</p>}
 <div className="ops-summary" aria-label="异常待办状态统计">{([{title:'待处理',value:summary.data?.open},{title:'处理中',value:summary.data?.acknowledged},{title:'已处理',value:summary.data?.done},{title:'条件已消失',value:summary.data?.cleared},{title:'暂缓提醒中',value:summary.data?.snoozed}]).map(v=><div key={v.title}><span>{v.title}</span><strong>{summary.isPending||summary.isError?'—':v.value??0}</strong></div>)}</div>
 <section className="panel" aria-label="运营巡检与待办">
 <div className="panel-heading"><div><h2>智能运营待办</h2><p>复用 V10 队列，识别 SKU 映射、库存、退款、FBA 货件复核、Ads 日报待审核及 V77 本地处理证据变化/超期；同一来源不重复生成待办。</p></div><span className="status-chip chip-muted">仅建议 · 不自动改动业务事实</span></div>
 <div className="ops-toolbar"><label className="shop-filter"><span>查看范围</span><select aria-label="筛选运营待办状态" value={filter} onChange={e=>setFilter(e.target.value as FindingFilter)}><option value="ACTIVE">所有活跃（含暂缓）</option><option value="ACTIONABLE">当前需要处理（不含暂缓）</option><option value="ALL">全部状态</option></select></label>
 <button className="primary-button" disabled={scan.isPending} onClick={()=>scan.mutate()}>{scan.isPending?'巡检中…':'立即巡检当前租户'}</button>
 <button className="refresh-button" onClick={refresh}>刷新待办</button></div>
 <div className="ops-rule-note">规则：待映射 Listing、FBA 数据不完整、可售库存 ≤ 部署阈值（默认 3）、部分/全部退款订单、FBA 本地货件复核、Ads 未授权日报待审核、采购/FBA 人工处理超期与证据重新核对。新规则由站点配置控制，定时巡检需部署者显式开启。</div>
 {message&&<div className="panel-caption" role="status">{message}</div>}{failure&&<div className="setup-error" role="alert">{failure}</div>}
 {findings.isPending?<div className="empty-state" role="status">正在读取运营待办…</div>:findings.isError?<div className="core-query-error" role="alert"><strong>读取运营待办失败</strong><p>{findings.error instanceof Error?findings.error.message:'服务端暂不可用'}</p><button className="refresh-button" onClick={refresh}>重试</button></div>:
 !findings.data?.length?<div className="empty-state">当前条件下没有待办。首次使用点击“立即巡检”；没有记录不等于真实 Amazon 经营无异常。</div>:
 <div className="table-scroll" role="region" tabIndex={0} aria-label="可水平滚动的运营待办列表"><table><thead><tr>{['级别','异常类型','关联来源','来源','状态','上次发现','Owner / 提醒','操作'].map(h=><th key={h}>{h}</th>)}</tr></thead>
 <tbody>{findings.data.map(i=><tr key={i.id}>
 <td><span className={`status-chip ${i.severity==='CRITICAL'?'chip-amber':'chip-muted'}`}>{severityNames[i.severity]}</span></td>
 <td className="strong-cell">{ruleNames[i.ruleCode]}</td><td className="small-cell">{i.sourceKey}</td>
 <td><span className={`status-chip ${i.dataOrigin==='MOCK'?'chip-amber':'chip-muted'}`}>{sourceLabel(i.dataOrigin)}</span></td>
 <td>{stateNames[i.state]}</td><td className="small-cell">{formatSourceTime(i.lastSeenAt)}</td>
 <td><OpsAttentionControls finding={i} tenantId={tenantId}
  userId={viewer.data?.userId??''}
  canAssign={!isFba(i.ruleCode)||fbaCan(i.marketplaceId,'assign')}
  canReview={!isFba(i.ruleCode)||fbaCan(i.marketplaceId,'review')}/></td>
 <td><div className="ops-row-actions">
 {(i.ruleCode==='FBA_PO_EVIDENCE_CHANGED'||
   i.ruleCode==='FBA_PO_EXCEPTION_OVERDUE')&&
  <button type="button" className="text-button"
   onClick={()=>onOpenFbaException(i.marketplaceId,i.sourceKey)}>
   查看 FBA 原处理单</button>}
 {(i.state==='OPEN'||i.state==='ACKNOWLEDGED')&&<button className="text-button" disabled={!!editing||(isFba(i.ruleCode)&&!fbaCan(i.marketplaceId,'resolve'))}
  onClick={()=>change.mutate({id:i.id,state:'DONE'})}>标记已处理</button>}
 {(i.state==='DONE'||i.state==='ACKNOWLEDGED')&&<button className="text-button" disabled={!!editing||(isFba(i.ruleCode)&&!fbaCan(i.marketplaceId,'review'))}
  onClick={()=>change.mutate({id:i.id,state:'OPEN'})}>重新打开</button>}
 </div></td></tr>)}</tbody></table></div>}
 <div className="panel-caption">V85：采购/FBA 本地异常的领取、复核、完成和规则操作以 V84 站点授权为准；无权限的操作按钮会禁用，服务端仍独立核验。全租户手动巡检遇到任何相关站点禁止 VIEW 或 CONFIGURE 时会拒绝整次巡检。最多显示 100 条；采购/FBA 异常需回到 FBA 本地工作台确认原证据及重新确认处理，不可仅以运营队列 DONE 代替原单闭环。测试来源的待办不可直接用于自动补货、发货或财务结算。</div>
 </section>
 <OpsExceptionInsightsPanel tenantId={tenantId} onOpenFbaException={onOpenFbaException}/>
 <OpsRulesPanel tenantId={tenantId}/>
 <SyncTaskMonitorPanel tenantId={tenantId}/>
 </div>
}
