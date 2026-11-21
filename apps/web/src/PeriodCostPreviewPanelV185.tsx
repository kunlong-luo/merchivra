import {useQuery} from '@tanstack/react-query'
import {
 fetchPeriodAllocationPlans,fetchPeriodProjection
} from './lib/period-cost-accrual-v185'

/**
 * Only loaded in the already scoped source-evidence drawer. Illustrative
 * period math is NOT approved recognition, refund COGS or net profit.
 */
export default function PeriodCostPreviewPanelV185({tenantId,marketplaceId,sourceId}:{
 tenantId:string;marketplaceId:string;sourceId:string
}){
 const scope=['finance','v185-period-preview',tenantId,marketplaceId,sourceId]
 const plans=useQuery({queryKey:[...scope,'allocations'],
  queryFn:()=>fetchPeriodAllocationPlans(marketplaceId,sourceId),retry:false})
 const current=plans.data?.find(p=>p.targetKind==='PERIOD'&&
  (p.state==='PENDING_REVIEW'||p.state==='APPROVED_UNVERIFIED'))
 const preview=useQuery({queryKey:[...scope,'preview',current?.id],
  queryFn:()=>fetchPeriodProjection(marketplaceId,sourceId,current!.id),
  enabled:!!current,retry:false})
 return <section className="v181-line-identity"
  aria-label="费用跨月原币分摊预览">
  <h4 className="v179-evidence-section">跨月费用分摊 · V185 原币敏感度预览</h4>
  <p className="setup-hint">
   只读取已保存的 V92 期间分摊；按自然日等额摊开，并将不足一微币单位的尾差分给较早日期。
   这只是核对跨月金额的演算方法，尚未经 G1 财务政策批准，不能当作会计摊销或已核验利润。
  </p>
  {plans.isPending?<p className="setup-hint">读取本来源期间分摊方案…</p>:
   plans.isError?<p className="setup-error" role="alert">
    {String(plans.error)}</p>:
   !current?<p className="setup-hint">
    当前来源没有待复核或已批准但未核验的 PERIOD 分摊方案。
    不可据此推断本月费用是零，SKU/ORDER 分摊也不能自动转成期间费用。
   </p>:
   preview.isPending?<p className="setup-hint">计算原币跨月守恒预览…</p>:
   preview.isError?<p className="setup-error" role="alert">
    {String(preview.error)}</p>:
   preview.data&&<div>
    <div className="v179-evidence-tags">
     <span className="v179-source-tag">{preview.data.allocationState}</span>
     <span className="v179-source-tag">仅本地证据 · {preview.data.currency}</span>
    </div>
    <dl className="v179-evidence-facts">
     <div><dt>来源申报金额</dt><dd>{preview.data.currency} {preview.data.sourceAmount}</dd></div>
     <div><dt>跨月演算总额</dt><dd>{preview.data.currency} {preview.data.projectedAmount}</dd></div>
     <div><dt>仍未分摊</dt><dd>{preview.data.currency} {preview.data.unallocatedAmount}</dd></div>
     <div><dt>服务日区间重叠</dt><dd>
      {preview.data.overlappingServiceDays?'有，需单独核查':'未发现'}</dd></div>
     <div><dt>账务确认</dt><dd>未授权，未入账</dd></div>
    </dl>
    <div className="table-scroll" role="region" tabIndex={0}
     aria-label="逐月原币模拟分摊明细">
     <table><thead><tr><th scope="col">演算月份</th>
      <th scope="col">原币</th><th scope="col">分摊预览（未入账）</th>
     </tr></thead><tbody>
      {preview.data.months.map(m=><tr key={m.period}>
       <td>{m.period}</td><td>{m.currency}</td>
       <td>{m.projectedAmount}</td>
      </tr>)}
     </tbody></table>
    </div>
    <p className="v179-evidence-warning" role="status">
     原币金额守恒仅证明该本地分摊算法的算术；真实外部费用、汇率口径、退款 COGS 回转、
     银行回款及完整净利润均未认证。阻断代码：{preview.data.blockerCodes.join('、')}。
    </p>
   </div>}
 </section>
}
