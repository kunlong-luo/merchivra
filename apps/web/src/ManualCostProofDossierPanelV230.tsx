import {useQuery} from '@tanstack/react-query'
import {fetchExternalProofDossierV230} from './lib/manual-cost-proof-v230'

const statuses:Record<string,string>={
 LOCAL_AUDIT_INCONSISTENT_HOLD:'本地来源审计不完整 · 禁止确认',
 LOCAL_SOURCE_REJECTED:'本地来源已拒绝',
 LOCAL_METADATA_REVIEW_PENDING:'本地元数据仍待独立审核',
 LOCAL_CROSS_SOURCE_CONFLICT_HOLD:'存在跨来源归属冲突 · 必须复核',
 LOCAL_REVIEW_COMPLETE_EXTERNAL_UNVERIFIED:'本地审核链完整 · 外部原始凭证仍未核证'
}

/** Only renders a missing-proof checklist; cannot upload, verify or post. */
export default function ManualCostProofDossierPanelV230({
 tenantId,marketplaceId,sourceId,currency
}:{tenantId:string;marketplaceId:string;sourceId:string;currency:string}){
 const report=useQuery({
  queryKey:['manual-cost-proof-v230',tenantId,marketplaceId,sourceId,currency],
  queryFn:()=>fetchExternalProofDossierV230(marketplaceId,sourceId,currency),
  retry:false,staleTime:0,refetchOnWindowFocus:false
 })
 return <section className="core-tools" aria-label="来源外部凭证核验准备清单">
  <h3>V230 · 外部原始凭证缺口清单</h3>
  <p className="v179-evidence-warning" role="note">
   <strong>这不是已认证费用：</strong>这里只核对本地 V91 审计、
   V165 身份冲突与 V94/V102 结算关联，并列出实际外部证明要求。
   未取得授权的独立第三方证据前，不得据此扣减利润、确认 COGS 或放行生产。
  </p>
  {report.isPending&&<p role="status">正在读取当前来源的本地证据…</p>}
  {report.isError&&<div role="alert" className="setup-error">
   <strong>当前无法验证证据准备度：</strong>{report.error.message}。
   不得将读取失败当作无风险，也不得沿用其他站点的历史检查。
  </div>}
  {report.data&&<div>
   <p role="status">
    <strong>{statuses[report.data.localDossierStatus]}</strong>。
    来源 UUID：<code>{report.data.sourceId.slice(0,8)}…</code>；
    类别 {report.data.category}；原币 {report.data.originalCurrency}。
   </p>
   <p className="setup-hint">
    本地来源审计：{report.data.localSourceAuditComplete?'完整':'未通过'}；
    独立元数据审核：{report.data.independentMetadataReviewComplete?'完整':'未完成'}；
    V182 结算行/身份：{report.data.localAttributionStatus}；
    跨来源冲突：{report.data.localCollisionHeld?'存在':'没有当前已识别的本地冲突（不代表全局无重复）'}。
   </p>
   <h4>待取得和独立确认的第三方材料（当前均未核验）</h4>
   <ul>{report.data.requiredExternalProof.map(item=><li key={item.code}>
    <strong>{item.description}</strong>
    <span className="secondary-cell"> {item.code} · 未核验</span>
   </li>)}</ul>
   <p className="setup-hint">
    原文件字节、签发方、真实扣款、重复扣费排除、真实退款 COGS、
    会计口径及财务过账全部尚未获得独立确认。
    <strong> realizedNetProfit=null；生产 NO-GO。</strong>
   </p>
   <details><summary>查看本地与外部阻断代码（{report.data.blockerCodes.length} 项）</summary>
    <ul>{report.data.blockerCodes.map((code,i)=>
     <li key={code+'-'+i}><code>{code}</code></li>)}</ul>
   </details>
  </div>}
  <button type="button" className="refresh-button"
    disabled={report.isFetching}
    onClick={()=>{void report.refetch()}}>
   {report.isFetching?'正在复查…':'重新核对本地证据'}
  </button>
 </section>
}
