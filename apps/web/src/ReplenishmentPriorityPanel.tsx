import {useQuery} from '@tanstack/react-query'
import {getReplenishmentPriorities,type ReplenishmentPriority} from './lib/replenishment-priorities'

const risk:Record<ReplenishmentPriority['riskCode'],string>={
 STOCK_NOT_OBSERVED:'库存未知、过期或不完整，禁止判断风险',
 DEMAND_NOT_ESTABLISHED:'28 天订单覆盖不完整，仅可参考库存阈值',
 SOURCE_MISMATCH:'销量与库存 MOCK/LIVE 来源不一致，禁止联算',
 RATE_NOT_ESTABLISHED:'无可用的该 SKU 历史下单速率',
 LEAD_WINDOW_EXPOSURE:'按历史销量示意，可售覆盖时间不长于策略提前期',
 POLICY_REORDER:'库存已触发人工补货点，历史覆盖仍长于提前期',
 HISTORICAL_COVERAGE_OK:'历史销量示意覆盖超过策略提前期，库存未触发补货点'
}
const ranking:Record<ReplenishmentPriority['priorityCode'],string>={
 HIGH:'高 · 人工优先核查',MEDIUM:'中 · 人工检查补货点',LOW:'低 · 当前只观察',
 REVIEW_STOCK_ONLY:'证据不全 · 单看库存',BLOCKED:'阻断 · 不可量化'
}
export default function ReplenishmentPriorityPanel({tenantId,marketplaceId}:{
 tenantId:string;marketplaceId:string
}){
 const query=useQuery({queryKey:['replenishment','priorities',tenantId,marketplaceId],
  queryFn:()=>getReplenishmentPriorities(marketplaceId),retry:false})
 const rows=query.data??[]
 return <section className="core-tools" aria-label="补货风险优先级与本地采购核对">
  <div className="panel-heading"><div><h3>V74 补货优先级与本地采购交叉核对</h3>
   <p>仅当库存新鲜、订单 28 天完整、MOCK/LIVE 来源一致且 SKU 有实际下单记录时，
    展示历史销量示意覆盖天数。未取得销量不代表需求为零。</p></div>
   <button type="button" className="refresh-button" disabled={query.isFetching}
    onClick={()=>void query.refetch()}>刷新风险</button></div>
  {query.isPending?<p className="empty-state">读取销量、库存与本地采购证据…</p>:
   query.isError?<p className="setup-error" role="alert">{String(query.error)}</p>:
   !rows.length?<p className="empty-state">尚无补货策略。</p>:
   <div className="table-scroll" role="region" tabIndex={0} aria-label="V74 SKU 补货风险优先级表">
    <table><thead><tr>
     <th scope="col">Seller SKU / 证据级别</th>
     <th scope="col">FBA 可售 / 历史覆盖</th>
     <th scope="col">人工提前期 / 供应商条款</th>
     <th scope="col">已有本地采购（不可抵扣 FBA）</th>
     <th scope="col">优先级 / 解释</th>
    </tr></thead><tbody>
    {rows.map(p=><tr key={p.sellerSku}>
     <td><strong>{p.sellerSku}</strong><div className="secondary-cell">{p.evidenceLevel}
       {' · '}{p.stockSourceMode??'库存未知'} / {p.demandSourceMode??'销量未知'}</div></td>
     <td>{p.fulfillable===null?'不可计算':p.fulfillable+' 件'}
      <div className="secondary-cell">历史速率覆盖：
       {p.illustrativeCoverageDays===null?'不可计算':p.illustrativeCoverageDays+' 天（非预测）'}</div>
      <div className="secondary-cell">28 天观测：
       {p.observedUnits28d===null?'未知':p.observedUnits28d+' 件'}</div></td>
     <td>策略 {p.policyLeadTimeDays} 天
      <div className="secondary-cell">可选报价 {p.readySupplierQuoteCount} 个
       {p.shortestSupplierTermDays!==null?
        ' · 条款 '+p.shortestSupplierTermDays+
        (p.longestSupplierTermDays!==p.shortestSupplierTermDays?
          '～'+p.longestSupplierTermDays:'')+' 天':' · 暂无可用报价'}</div>
      <div className="secondary-cell">策略提前期历史件数：
       {p.illustrativePolicyLeadUnits??'不可计算'}</div></td>
     <td>草稿/待审批：{p.localDraftOrSubmittedUnits}
      <div className="secondary-cell">已审批未收：{p.localApprovedUnreceivedUnits}</div>
      <div className="secondary-cell">本地已收：{p.localReceivedUnits}</div>
      {p.existingLocalOrderNeedsReview&&
       <strong>已有 {p.localPendingOrderCount} 张进行中采购单，需人工核查重复下单</strong>}
     </td>
     <td><strong>{ranking[p.priorityCode]}</strong>
      <div className="secondary-cell">{risk[p.riskCode]}</div></td>
    </tr>)}
    </tbody></table>
   </div>}
  <p className="panel-caption">覆盖天数按过去完整 28 天下单件数/28 计算；不扣除退款、取消、促销变化和实际未来需求，
   不是缺货预测。供应商提前期来自人工 V42 条款而非履约承诺。仅展示明确从 V37/V71 补货草稿生成的本地采购，
   一般采购单、未核验货件及不明仓库不能被推定成当前 Seller SKU 的 FBA 在途或可售。
   未做跨币种自动比较，不自动建单、审批、催单或改动 Amazon。</p>
 </section>
}
