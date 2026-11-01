import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {getReportScan,startReportScan,stepReportScan} from './lib/settlement-report-scan'

const names:Record<string,string>={
 NOT_STARTED:'尚未建立任务',READY:'待继续',RUNNING:'运行中',
 RETRY:'等待重试',PAUSED:'已暂停，需人工复核',SUCCEEDED:'本次窗口扫描完成'
}
export default function SettlementReportScanPanel({tenantId,marketplaceId}:{
 tenantId:string;marketplaceId:string
}){
 const qc=useQueryClient()
 const key=['finance','reports','scan',tenantId,marketplaceId]
 const status=useQuery({queryKey:key,queryFn:()=>getReportScan(marketplaceId),
  retry:false,staleTime:15000})
 const refresh=()=>{
  void qc.invalidateQueries({queryKey:key})
  void qc.invalidateQueries({queryKey:['finance','reports',tenantId,marketplaceId]})
 }
 const begin=useMutation({mutationFn:()=>startReportScan(marketplaceId),onSuccess:refresh})
 const step=useMutation({mutationFn:()=>stepReportScan(marketplaceId),onSuccess:refresh})
 const problem=begin.error??step.error
 const s=status.data
 const pending=begin.isPending||step.isPending
 return <section className="panel full-panel" aria-label="结算报告发现调度">
  <div className="panel-heading"><div><h2>结算报告发现任务</h2>
   <p>仅在已验证 Finance Seller 授权后，手动创建一个受控 14 天扫描窗口。
    自动续跑须由部署者另外开启；报告读取不会触发 Amazon 写入。</p></div>
   <button type="button" className="refresh-button" disabled={status.isFetching}
    onClick={()=>void status.refetch()}>刷新状态</button></div>
  {status.isError?status.error instanceof Error&&status.error.message==='NOT_ENABLED'?
    <p className="setup-hint">Reports 真实读取尚未启用（默认关闭）。你在本地完成授权后才能开启此功能。</p>:
    <p className="setup-error" role="alert">{String(status.error)}</p>:
   status.isPending?<p>读取扫描状态…</p>:
   s&&<div className="core-tools">
    <p role="status"><strong>{names[s.state]??s.state}</strong> · 已完成页数 {s.completedPages} ·
     已导入报告 {s.importedReports} · 重试 {s.retryCount} 次。</p>
    {s.lastFailureCode&&<p className="setup-hint">上次失败：{s.lastFailureCode}
      {s.nextAttemptAt?'；下次允许重试（UTC）：'+s.nextAttemptAt:''}</p>}
    <div className="sandbox-actions">
     <button type="button" className="primary-button" disabled={pending||s.state==='RUNNING'}
      onClick={()=>begin.mutate()}>建立／重新开始安全扫描窗口</button>
     <button type="button" className="refresh-button"
      disabled={pending||!['READY','RETRY'].includes(s.state)||
        (s.nextAttemptAt!==null&&Date.parse(s.nextAttemptAt)>Date.now())}
      onClick={()=>step.mutate()}>手动推进一页（最多两份报告）</button>
    </div>
   </div>}
  {problem&&<p className="setup-error" role="alert">{String(problem)}</p>}
  <p className="panel-caption">任务租约过期可由新 Worker 领取。分页 Token 超过保守有效期后从固定窗口重扫，
   相同报告按文档 ID 和 SHA-256 去重。无法获取真实报告时不得显示为成功。</p>
 </section>
}
