import {useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {fetchSyncTaskSnapshot} from './lib/sync-task-monitor'
import type {SyncTaskKind,SyncTaskState} from './lib/sync-task-monitor'
import {formatSourceTime} from './lib/core-view'

const kindNames:Record<Exclude<SyncTaskKind,'ALL'>,string>={
 ORDERS:'订单',CATALOG_LISTINGS:'Listing',CATALOG_FBA:'FBA 库存',FINANCES:'财务'
}
const stateNames:Record<Exclude<SyncTaskState,'ALL'>,string>={
 READY:'待执行',RUNNING:'运行中',RETRY:'等待重试',WAITING_RETRY:'等待重试',
 PAUSED:'已暂停',SUCCEEDED:'已完成'
}
export default function SyncTaskMonitorPanel({tenantId}:{tenantId:string}){
 const [kind,setKind]=useState<SyncTaskKind>('ALL')
 const [state,setState]=useState<SyncTaskState>('ALL')
 const tasks=useQuery({
  queryKey:['operations',tenantId,'sync-tasks',kind,state],
  queryFn:()=>fetchSyncTaskSnapshot(kind,state),
  staleTime:15000,retry:false
 })
 const s=tasks.data?.summary
 return <section className="panel" aria-label="后台同步任务监控">
  <div className="panel-heading"><div><h2>后台同步任务监控</h2>
   <p>当前会话租户的 PostgreSQL 任务状态。只读监控，不触发 Amazon 请求、补单或重试。</p></div>
   <span className="status-chip chip-muted">租户 Owner 可见</span></div>
  <div className="ops-summary" aria-label="后台同步任务状态统计">{[
   {name:'任务总数',count:s?.total},{name:'待执行',count:s?.ready},
   {name:'运行中',count:s?.running},{name:'等待重试',count:s?.retrying},
   {name:'已暂停',count:s?.paused},{name:'已完成',count:s?.succeeded}
  ].map(item=><div key={item.name}><span>{item.name}</span><strong>{tasks.isPending?'—':item.count??0}</strong></div>)}</div>
  <div className="ops-toolbar">
   <label className="shop-filter"><span>同步类型</span><select aria-label="筛选同步类型" value={kind} onChange={e=>setKind(e.target.value as SyncTaskKind)}>
    <option value="ALL">全部类型</option><option value="ORDERS">订单</option><option value="CATALOG_LISTINGS">Listing</option><option value="CATALOG_FBA">FBA 库存</option><option value="FINANCES">财务</option>
   </select></label>
   <label className="shop-filter"><span>执行状态</span><select aria-label="筛选执行状态" value={state} onChange={e=>setState(e.target.value as SyncTaskState)}>
    <option value="ALL">全部状态</option><option value="READY">待执行</option><option value="RUNNING">运行中</option>
    <option value="RETRY">Catalog / 财务重试</option><option value="WAITING_RETRY">订单重试</option>
    <option value="PAUSED">已暂停</option><option value="SUCCEEDED">已完成</option>
   </select></label>
   <button type="button" className="refresh-button" disabled={tasks.isFetching} onClick={()=>void tasks.refetch()}>{tasks.isFetching?'读取中…':'刷新任务'}</button>
  </div>
  {tasks.isPending?<div className="empty-state" role="status">正在加载租户任务状态…</div>:
   tasks.isError?<div className="setup-error" role="alert">{tasks.error instanceof Error?tasks.error.message:'任务状态暂不可用'}</div>:
   !tasks.data?.tasks.length?<div className="empty-state">当前筛选下没有任务记录。后台同步功能可能尚未启用或没有创建任务；这不代表 Amazon 数据已经同步。</div>:
   <div className="table-scroll" role="region" tabIndex={0} aria-label="同步任务列表，可水平滚动">
    <table><thead><tr>{['类型','数据来源','店铺站点','状态','已提交页','重试次数','下次重试（UTC）','最近更新（UTC）','失败原因'].map(label=><th key={label} scope="col">{label}</th>)}</tr></thead>
    <tbody>{tasks.data.tasks.map(task=><tr key={task.jobId}>
     <td className="strong-cell">{kindNames[task.kind]}</td>
     <td><span className={'status-chip '+(task.origin==='MOCK'?'chip-amber':'chip-muted')}>{task.origin}</span></td>
     <td className="small-cell" title={task.marketplaceId}>{task.marketplaceId.slice(0,8)}…</td>
     <td><span className={'status-chip '+(task.state==='PAUSED'?'chip-amber':'chip-muted')}>{stateNames[task.state]}{task.leaseExpired?' · 租约到期':''}</span></td>
     <td>{task.committedPages}</td><td>{task.retryCount}</td>
     <td className="small-cell">{task.nextAttemptAt?formatSourceTime(task.nextAttemptAt):'—'}</td>
     <td className="small-cell">{formatSourceTime(task.updatedAt)}</td>
     <td className="small-cell">{task.lastFailureCode??'—'}</td>
    </tr>)}</tbody></table></div>}
  <div className="panel-caption">
   统计包含当前租户的所有任务，不随上方筛选变化；列表最多显示 50 条{tasks.data?.hasMore?'，尚有更多匹配记录':''}。不会暴露分页 Token、授权凭证或上游响应。
  </div>
 </section>
}
