import {useState} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {getFinancesSync,startFinancesSync,stepFinancesSync} from './lib/finances-sync'
const labels:Record<string,string>={
 NOT_STARTED:'未创建',READY:'可读取下一页',RUNNING:'运行中',
 RETRY:'等待限流重试',SUCCEEDED:'本轮完成',PAUSED:'已暂停，需人工检查'
}
export default function FinancesSyncPanel({tenantId,marketplaceId}:{
 tenantId:string;marketplaceId:string
}){
 const cache=useQueryClient()
 const [failure,setFailure]=useState(''),[notice,setNotice]=useState('')
 const key=['finances-live-sync',tenantId,marketplaceId]
 const status=useQuery({queryKey:key,queryFn:()=>getFinancesSync(marketplaceId),
  retry:false,staleTime:12000})
 const refresh=()=>{
  void cache.invalidateQueries({queryKey:key})
  void cache.invalidateQueries({queryKey:['finance','events',tenantId,marketplaceId]})
  void cache.invalidateQueries({queryKey:['finance','buckets',tenantId,marketplaceId]})
 }
 const start=useMutation({mutationFn:()=>startFinancesSync(marketplaceId),
  onSuccess:()=>{setFailure('');setNotice('已建立新的 Finances 来源扫描');refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 const step=useMutation({mutationFn:()=>stepFinancesSync(marketplaceId),
  onSuccess:s=>{setFailure('');setNotice('已处理一页，状态 '+s.state);refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 const item=status.data
 return <section className="panel full-panel">
  <div className="panel-heading"><div><h2>Amazon Finances 官方只读同步</h2>
   <p>需要 Seller 有效授权及独立审核的 Finance and Accounting 应用角色；
    交易总金额暂按原币 UNCLASSIFIED 入库，不是利润、销售额或已回款金额。</p></div>
   <button type="button" className="refresh-button" onClick={refresh}>刷新</button></div>
  {status.isPending?<p className="empty-state">检查真实 Finances 配置和授权…</p>:
   status.isError?<p className="setup-hint" role="status">
    {String(status.error)}。默认关闭；不会自动连接 Amazon。</p>:
   <div className="core-tools">
    <p>同步状态：<strong>{labels[item?.state??'NOT_STARTED']??item?.state}</strong>
     {' · '}已提交页数 {item?.committedPages??0}
     {' · '}扫描代次 {item?.generation??0}</p>
    {item?.lastFailureCode&&<p className="setup-error">
     上次失败代码：{item.lastFailureCode}</p>}
    {item?.nextRetryAt&&<p>下次重试时间：{item.nextRetryAt}</p>}
    <div className="sandbox-actions">
     <button type="button" className="refresh-button" disabled={start.isPending||
      step.isPending||item?.state==='RUNNING'} onClick={()=>start.mutate()}>
      {item?.jobId?'重新扫描最近的已入账窗口':'启动最近已入账窗口'}</button>
     <button type="button" className="primary-button" disabled={step.isPending||
      start.isPending||!['READY','RETRY'].includes(item?.state??'')}
      onClick={()=>step.mutate()}>安全读取下一页</button>
    </div>
   </div>}
  {notice&&<p className="panel-caption" role="status">{notice}</p>}
  {failure&&<p className="setup-error" role="alert">{failure}</p>}
  <p className="setup-hint">每次仅推进一页，默认扫描截至 48 小时前的最近 14 天；
   对同源交易去重，未知财务类别不猜测分类。所有真实请求默认关闭。</p>
 </section>
}
