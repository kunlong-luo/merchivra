import {useState} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {decideBankManualReview,getBankManualReview,listBankManualAudit}
 from './lib/bank-manual-review'

const label:Record<string,string>={
 NOT_REQUESTED:'尚未申请复核',PENDING:'等待独立 Owner 审核',
 REVIEWED:'人工参考已双人检查，资金到账仍未验证',
 REJECTED:'已驳回',STALE:'银行或 Amazon 来源发生变化，旧复核已失效'
}
export default function BankManualReviewPanel({tenantId,marketplaceId,reportId,
 comparisonState}:{tenantId:string;marketplaceId:string;reportId:string;
 comparisonState:string}){
 const qc=useQueryClient()
 const [reason,setReason]=useState('')
 const [showAudit,setShowAudit]=useState(false)
 const key=['finance','bank-review',tenantId,marketplaceId,reportId]
 const status=useQuery({queryKey:key,
  queryFn:()=>getBankManualReview(marketplaceId,reportId),retry:false,staleTime:15000})
 const history=useQuery({queryKey:[...key,'audit'],enabled:showAudit,
  queryFn:()=>listBankManualAudit(marketplaceId,reportId),retry:false})
 const submit=useMutation({mutationFn:(action:'request'|'approve'|'reject')=>
  decideBankManualReview(marketplaceId,reportId,action,reason),
  onSuccess:()=>{setReason('');void qc.invalidateQueries({queryKey:key})}})
 const s=status.data
 const valid=reason.trim().length>=10&&reason.length<=500&&
  !/[\x00-\x1F\x7F]/.test(reason)
 return <div className="core-tools" aria-label="银行人工来源双人复核">
  <h3>银行人工来源检查 · 不代表到账确认</h3>
  <p className="setup-hint">审批人必须同时不同于银行参考上传人和审核申请人。
   没有银行受信来源时，bankVerified 始终为 false。</p>
  {status.isPending?<p>读取银行人工来源审核…</p>:
   status.isError?<p className="setup-error" role="alert">{String(status.error)}</p>:
   s&&<p role="status"><strong>{label[s.state]??s.state}</strong>
    {' · '}第 {s.version} 版
    {s.state==='STALE'?'，请重新提交审核':''}</p>}
  <label htmlFor="bank-manual-review-reason">复核说明（无银行账号或个人信息）</label>
  <textarea id="bank-manual-review-reason" rows={2} maxLength={500}
   placeholder="例如：检查了脱敏来源文件摘要、Settlement ID、同币种贷记与扣留手续费的核对结果。"
   value={reason} onChange={e=>setReason(e.target.value)}/>
  <div className="sandbox-actions">
   <button type="button" className="refresh-button" disabled={!valid||submit.isPending||s?.state==='PENDING'}
    onClick={()=>submit.mutate('request')}>提交人工银行参考复核</button>
   <button type="button" className="primary-button"
    disabled={!valid||submit.isPending||s?.state!=='PENDING'||!s.evidenceCurrent||
     comparisonState!=='MANUAL_AMOUNT_MATCH_UNVERIFIED'}
    onClick={()=>submit.mutate('approve')}>另一位 Owner 确认人工参考</button>
   <button type="button" className="text-button"
    disabled={!valid||submit.isPending||s?.state!=='PENDING'||!s.evidenceCurrent}
    onClick={()=>submit.mutate('reject')}>驳回人工参考</button>
  </div>
  {submit.error&&<p className="setup-error" role="alert">{String(submit.error)}</p>}
  <button type="button" className="text-button"
   onClick={()=>setShowAudit(v=>!v)}>{showAudit?'收起':'查看'}审核审计</button>
  {showAudit&&(history.isPending?<p>读取审计…</p>:
   history.isError?<p className="setup-error" role="alert">{String(history.error)}</p>:
   <div className="table-scroll" role="region" tabIndex={0} aria-label="人工银行参考审计">
    <table><thead><tr><th>版本</th><th>动作</th><th>操作人</th><th>说明</th><th>时间</th></tr></thead>
     <tbody>{(history.data??[]).map((a,i)=><tr key={i}>
      <td>{a.version}</td><td>{a.action}</td><td>{a.actorId}</td>
      <td>{a.reason}</td><td>{a.occurredAt}</td></tr>)}</tbody>
    </table></div>)}
 </div>
}
