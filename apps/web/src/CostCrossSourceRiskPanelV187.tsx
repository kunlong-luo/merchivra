import {useQuery} from '@tanstack/react-query'
import {fetchCostCrossSourceRiskV187}
 from './lib/cost-cross-source-risk-v187'

const codes:Record<string,string>={
 OTHER_MANUAL_COST_SAME_CLAIMED_DOCUMENT:'人工费用宣称同一文档',
 MANUAL_ADS_SPEND_SAME_KEY:'手工广告费用参考编号重叠',
 OFFLINE_ADS_REPORT_SAME_REFERENCE:'离线 Ads 报告参考编号重叠',
 OFFLINE_ADS_REPORT_SAME_CLAIMED_DOCUMENT:'离线 Ads 报告文档摘要重叠',
 SPAPI_FINANCES_EVENT_SAME_KEY:'平台 Finances 事件编号重叠',
 SPAPI_SETTLEMENT_REPORT_SAME_REFERENCE:'平台结算报告参考编号重叠',
 SPAPI_SETTLEMENT_REPORT_SAME_CLAIMED_DOCUMENT:'平台结算文档摘要重叠',
 MANUAL_SETTLEMENT_STATEMENT_SAME_REFERENCE:'人工结算声明编号重叠'
}
/** Unpaginated counts, bounded examples. Never show a verified debit/profit. */
export default function CostCrossSourceRiskPanelV187({tenantId,marketplaceId,start,end}:{
 tenantId:string;marketplaceId:string;start:string;end:string
}){
 const state=useQuery({
  queryKey:['finance','v187-cross-source-risk',tenantId,marketplaceId,start,end],
  queryFn:()=>fetchCostCrossSourceRiskV187(marketplaceId,start,end),
  enabled:!!marketplaceId,retry:false
 })
 return <section className="v181-line-identity"
  aria-label="不同费用来源的本地重复风险与利润阻断">
  <h3 className="v179-evidence-section">不同来源的重复收费风险 · 整期核对</h3>
  <p className="setup-hint">
   这里检查不同 V91 来源与 V165 多种参考编号/文档线索的重叠，
   以及 V168 多来源关联同一导入结算行。所有匹配只是本地风险线索，
   不是 真实重复扣费、发票认证或允许冲抵利润的结论。
   风险汇总不受来源列表每页 50 条的限制。
  </p>
  {state.isPending?<p className="setup-hint" role="status">正在核对全期间来源冲突…</p>:
   state.isError?<p className="setup-error" role="alert">
    {String(state.error)}</p>:
   state.data&&<div>
    <dl className="v179-evidence-facts">
     <div><dt>本期来源总数</dt><dd>{state.data.sourceCount}</dd></div>
     <div><dt>跨来源身份线索重叠</dt>
      <dd>{state.data.potentialIdentityOverlapSourceCount} 条来源</dd></div>
     <div><dt>共用结算行的来源</dt>
      <dd>{state.data.sharedSettlementLineSourceCount} 条来源</dd></div>
     <div><dt>存在任一类本地风险</dt>
      <dd>{state.data.anyLocalCollisionSourceCount} 条来源（交集不重复计数）</dd></div>
     <div><dt>没有本地匹配的来源</dt>
      <dd>{state.data.noLocalMatchSourceCount} 条（并非无重复费用证明）</dd></div>
     <div><dt>真实重复扣费已认证</dt><dd>否 · 不自动拒付、冲回或入账</dd></div>
    </dl>
    {state.data.examples.length>0&&
     <div className="table-scroll" role="region" tabIndex={0}
      aria-label="跨来源身份风险示例，最多 20 条">
      <table><thead><tr>
       <th scope="col">费用来源 UUID</th><th scope="col">业务日</th>
       <th scope="col">本地身份重叠线索</th><th scope="col">共用结算行</th>
       <th scope="col">认证状态</th>
      </tr></thead><tbody>{state.data.examples.map(s=>
       <tr key={s.sourceId}>
        <td><code title={s.sourceId}>{s.sourceId.slice(0,8)}</code></td>
        <td>{s.businessDate}</td>
        <td>{s.localSignalCodes.length
         ?s.localSignalCodes.map(c=>codes[c]??c).join('、')
         :'没有编号/文档重叠'}</td>
        <td>{s.sharedSettlementLineClaim?'有（待证明）':'没有匹配'}</td>
        <td>仅本地线索 · 未认证</td>
       </tr>)}</tbody></table>
     </div>}
    {state.data.examplesTruncated&&<p className="setup-hint">
     仅展示最近 20 条风险来源示例。上方风险总数覆盖整个查询期间。</p>}
    <p className="v179-evidence-warning" role="status">
     {state.data.anyLocalCollisionSourceCount===0
      ?'本期间未观察到本地身份或共用结算行重叠，不等于真实费用无重复。'
      :'存在本地跨来源风险，必须独立核对原始外部证据，不能自动认定或清除。'}
     退款 COGS 回转仍需要签名实收与独立财务会计政策；FX、银行回款和净利润
     均未完成验证。阻断原因：{state.data.blockerCodes.join('、')}。
    </p>
   </div>}
 </section>
}
