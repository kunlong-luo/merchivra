import {useState} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {fetchOrderSync,startOrderSync,stepOrderSync} from './lib/order-sync'
import {formatSourceTime} from './lib/core-view'
export default function OrdersSyncPanel({tenantId,marketplaceId,isLive}:{tenantId:string;marketplaceId:string;isLive:boolean}){
 const client=useQueryClient(),[message,setMessage]=useState(''),[failure,setFailure]=useState('')
 const status=useQuery({queryKey:['orders-sync',tenantId,marketplaceId],queryFn:()=>fetchOrderSync(marketplaceId),retry:false,staleTime:10000})
 const refresh=()=>{void status.refetch();void client.invalidateQueries({queryKey:['core',tenantId,'orders',marketplaceId]})}
 const start=useMutation({mutationFn:()=>startOrderSync(marketplaceId),onSuccess:()=>{setMessage('已初始化同步窗口');setFailure('');refresh()},onError:(e:Error)=>{setFailure(e.message);setMessage('')}})
 const step=useMutation({mutationFn:()=>stepOrderSync(marketplaceId),onSuccess:value=>{setMessage(value.state==='SUCCEEDED'?'同步窗口已经完整提交':'已处理一页，最新检查点已保存');setFailure('');refresh()},onError:(e:Error)=>{setFailure(e.message);setMessage('')}})
 const data=status.data
 return <section className="panel orders-sync-panel" aria-label="V11 订单同步任务"><div className="panel-heading"><div><h2>{isLive?'订单增量 Worker · V11C LIVE':'订单增量 Worker · V11A MOCK'}</h2><p>{isLive?'正式只读订单模式：仅授权、角色审核并显式批准后可用；不自动发货或记账。':'持久分页、租约防并发、断点恢复；此模式仅使用虚构订单源，不连接 Amazon。'}</p></div><span className="status-chip chip-amber">{isLive?'LIVE · 权限强校验':'MOCK ONLY'}</span></div>
 {status.isPending?<div className="empty-state">正在读取同步检查点…</div>:status.isError?<p role="status" className="setup-hint">{status.error instanceof Error?status.error.message:'Worker 不可用'}</p>:data&&<>
 <div className="orders-sync-grid"><div><span>任务状态</span><strong>{data.state}</strong></div><div><span>已提交页数</span><strong>{data.committedPages}</strong></div><div><span>租约代数</span><strong>{data.leaseEpoch}</strong></div><div><span>安全水位（UTC）</span><strong>{formatSourceTime(data.lastSafeWatermark)}</strong></div></div>
 <p className="core-search-scope">{isLive?'分页 Token 不会发送到浏览器。缺少独立角色审核时服务端必须拒绝读取；LIVE 数据不代表已结算利润。':'分页 Token 不会发送到浏览器；模拟记录不可冒充 Amazon 经营数据。'}</p>
 <div className="sandbox-actions"><button type="button" className="primary-button" disabled={start.isPending||step.isPending||data.state==='RUNNING'} onClick={()=>start.mutate()}>开始新的增量窗口</button>
 <button type="button" className="refresh-button" disabled={step.isPending||start.isPending||['NOT_STARTED','SUCCEEDED','PAUSED','RUNNING','WAITING_RETRY'].includes(data.state)} onClick={()=>step.mutate()}>提交下一页</button>
 <button type="button" className="text-button" onClick={refresh}>刷新</button></div>
 {data.nextAttemptAt&&<p className="setup-hint">下次允许重试：{formatSourceTime(data.nextAttemptAt)}</p>}
 {data.lastFailureCode&&<p className="setup-hint">上次错误代码：{data.lastFailureCode}</p>}
 {message&&<p className="panel-caption" role="status">{message}</p>}{failure&&<p className="setup-error" role="alert">{failure}</p>}</>}
 </section>
}
