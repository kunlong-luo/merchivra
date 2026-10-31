import {useState} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {catalogStart,catalogStatus,catalogStep,type CatalogStream} from './lib/catalog-sync'

const labels:Record<string,string>={
 NOT_STARTED:'尚未启动',READY:'已准备，可读取下一页',RUNNING:'正在执行',
 RETRY:'等待限流/服务器重试',PAUSED:'已暂停，需管理员重新开始',
 SUCCEEDED:'当前全量扫描完成'
}
export default function CatalogSyncPanel({tenantId,marketplaceId,stream}:{
 tenantId:string;marketplaceId:string;stream:CatalogStream
}){
 const client=useQueryClient()
 const [message,setMessage]=useState(''),[failure,setFailure]=useState('')
 const key=['catalog-live-sync',tenantId,marketplaceId,stream]
 const status=useQuery({queryKey:key,queryFn:()=>catalogStatus(marketplaceId,stream),
  retry:false,staleTime:15000})
 const refresh=()=>{void client.invalidateQueries({queryKey:key});
  void client.invalidateQueries({queryKey:['core',tenantId,stream==='LISTINGS'?'listings':'inventory',marketplaceId]});
  if(stream==='FBA')void client.invalidateQueries({
   queryKey:['replenishment','suggestions',tenantId,marketplaceId]})}
 const start=useMutation({mutationFn:()=>catalogStart(marketplaceId,stream),
  onSuccess:()=>{setFailure('');setMessage('已建立新一代来源扫描；尚未读取任何页');refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 const step=useMutation({mutationFn:()=>catalogStep(marketplaceId,stream),
  onSuccess:s=>{setFailure('');setMessage('本次操作完成，状态：'+
   (labels[s.state]??s.state));refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 const current=status.data
 return <section className="panel full-panel" aria-label="受保护 Amazon 只读同步">
  <div className="panel-heading"><div><h2>{stream==='LISTINGS'?'Listing':'FBA 库存'} 官方只读同步</h2>
   <p>需要正式 Seller 授权、验证通过的站点和独立应用角色审核。按页读取，不执行任何 Amazon 修改操作。</p></div>
   <button className="refresh-button" onClick={refresh} type="button">刷新</button></div>
  {status.isError?<p className="setup-error" role="alert">{String(status.error)}</p>:
   status.isPending?<p className="empty-state">检查同步授权和任务状态…</p>:
   <div className="core-tools">
    <p>当前：<strong>{labels[current?.state??'NOT_STARTED']??current?.state}</strong>
      {' · '}已提交页数 {current?.committedPages??0} · 扫描代次 {current?.generation??0}</p>
    {current?.lastFailureCode&&<p className="setup-error">
      上次失败（已脱敏）：{current.lastFailureCode}</p>}
    {current?.nextRetryAt&&<p>下次允许重试（UTC）：
      {new Date(current.nextRetryAt).toISOString()}</p>}
    <div className="sandbox-actions">
     <button className="refresh-button" type="button" disabled={start.isPending||
       step.isPending||current?.state==='RUNNING'} onClick={()=>start.mutate()}>
       {current?.state==='NOT_STARTED'?'建立扫描':'重新完整扫描'}</button>
     <button className="primary-button" type="button" disabled={step.isPending||
       start.isPending||!['READY','RETRY'].includes(current?.state??'')}
       onClick={()=>step.mutate()}>提交下一页</button>
    </div>
   </div>}
  {message&&<p className="panel-caption" role="status">{message}</p>}
  {failure&&<p className="setup-error" role="alert">{failure}</p>}
  <p className="setup-hint">平台限流时按任务状态重试。完成页数据按 LIVE 来源单独入库；
  未返回的 SKU 不会被推断为删除或零库存。</p>
 </section>
}
