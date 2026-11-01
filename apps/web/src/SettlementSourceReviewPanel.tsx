import {useState} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {decideSourceReview,getSourceReview,getSourceReviewAudit,
 requestSourceReview} from './lib/settlement-source-review'

const states:Record<string,string>={
 NOT_REQUESTED:'尚未提交来源复核',PENDING:'等待另一位 Owner 审核',
 APPROVED:'两种来源金额一致，已完成双人复核',
 REJECTED:'来源复核已驳回',STALE:'财务来源已发生变化，原审核证据失效'
}
const actions:Record<string,string>={
 REQUESTED:'申请复核',REOPENED:'重新提交',APPROVED:'确认两来源一致',REJECTED:'驳回'
}
export default function SettlementSourceReviewPanel({tenantId,marketplaceId,reportId,
 sourceState}:{tenantId:string;marketplaceId:string;reportId:string;sourceState:string}){
 const qc=useQueryClient()
 const [reason,setReason]=useState('')
 const [showHistory,setShowHistory]=useState(false)
 const base=['finance','reports',tenantId,marketplaceId,reportId,'source-review']
 const status=useQuery({queryKey:base,queryFn:()=>getSourceReview(marketplaceId,reportId),
  retry:false,staleTime:15000})
 const history=useQuery({queryKey:[...base,'audit'],enabled:showHistory,
  queryFn:()=>getSourceReviewAudit(marketplaceId,reportId),retry:false})
 const refresh=()=>{
   void qc.invalidateQueries({queryKey:base})
   void qc.invalidateQueries({queryKey:['finance','reports',tenantId,marketplaceId,reportId,'finance-comparison']})
 }
 const request=useMutation({
   mutationFn:()=>requestSourceReview(marketplaceId,reportId,reason),
   onSuccess:()=>{setReason('');refresh()}
 })
 const decide=useMutation({
   mutationFn:(action:'approve'|'reject')=>decideSourceReview(
     marketplaceId,reportId,action,reason),
   onSuccess:()=>{setReason('');refresh()}
 })
 const busy=request.isPending||decide.isPending
 const eligible=reason.trim().length>=10&&reason.length<=500&&!/[\x00-\x1F\x7F]/.test(reason)
 const s=status.data
 return <div className="core-tools" aria-label="结算来源双人复核">
  <h3>结算来源人工复核（双人审核）</h3>
  <p className="setup-hint">仅针对 Reports 和 Finances 两种来源之间的匹配证据。
   需一位 Owner 提交、另一位 Owner 审批。即使审批通过，也不意味着 Amazon 已打款、银行到账或会计已记账。</p>
  {status.isPending?<p>正在检查复核状态…</p>:
   status.isError?<p className="setup-error" role="alert">{String(status.error)}</p>:
   s&&<>
    <p role="status"><strong>{states[s.state]??s.state}</strong>
     {' · '}第 {s.version} 版审核
     {s.state==='STALE'?'（旧审批不可继续使用）':''}</p>
    {s.requestedAt&&<p className="setup-hint">
     申请人：{s.requestedBy} · 申请日期：{new Date(s.requestedAt).toLocaleString('zh-CN')}</p>}
    {s.decidedAt&&<p className="setup-hint">
     审核人：{s.decidedBy} · 审核日期：{new Date(s.decidedAt).toLocaleString('zh-CN')}</p>}
   </>}
  <label htmlFor="settlement-source-review-reason">审核依据或差异说明（不含个人信息）</label>
  <textarea id="settlement-source-review-reason" rows={3} maxLength={500}
   value={reason} onChange={e=>setReason(e.target.value)}
   placeholder="例如：对照同站点的 Settlement ID、报告明细有符号合计与 Finances 来源记录，未发现币种冲突。"/>
  <div className="sandbox-actions">
   <button type="button" className="refresh-button" onClick={()=>request.mutate()}
    disabled={!eligible||busy||status.isPending||s?.state==='PENDING'}>
    {s?.state==='STALE'?'重新提交审核':'申请人工复核'}</button>
   <button type="button" className="primary-button"
    disabled={!eligible||busy||s?.state!=='PENDING'||!s?.evidenceCurrent||
     sourceState!=='SOURCE_AMOUNTS_AGREE_UNVERIFIED'}
    onClick={()=>decide.mutate('approve')}>另一位 Owner 确认来源金额一致</button>
   <button type="button" className="text-button"
    disabled={!eligible||busy||s?.state!=='PENDING'||!s.evidenceCurrent}
    onClick={()=>decide.mutate('reject')}>另一位 Owner 驳回</button>
  </div>
  {(request.error||decide.error)&&<p className="setup-error" role="alert">
    {String(request.error??decide.error)}</p>}
  <button type="button" className="text-button"
   onClick={()=>setShowHistory(x=>!x)}>{showHistory?'收起':'查看'}审核审计</button>
  {showHistory&&(history.isPending?<p>读取审计…</p>:
   history.isError?<p className="setup-error" role="alert">{String(history.error)}</p>:
   !history.data?.length?<p>尚无审核记录。</p>:
   <div className="table-scroll" role="region" tabIndex={0}
    aria-label="结算来源审核审计">
    <table><thead><tr><th>版本</th><th>动作</th><th>操作人</th><th>依据说明</th><th>时间</th></tr></thead>
     <tbody>{history.data.map((entry,i)=><tr key={i}>
      <td>{entry.version}</td><td>{actions[entry.action]??entry.action}</td>
      <td>{entry.actorId}</td><td>{entry.reason}</td>
      <td>{new Date(entry.occurredAt).toLocaleString('zh-CN')}</td>
     </tr>)}</tbody></table></div>)}
 </div>
}
