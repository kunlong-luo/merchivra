import {useQuery} from '@tanstack/react-query'
import {getOrderContribution} from './lib/order-contribution'

const reasons:Record<string,string>={
 UNIT_COST_NOT_READY:'订单项历史 SKU 成本尚未完整覆盖',
 SOURCE_RESULT_TRUNCATED:'关联财务交易超过本次读取上限',
 ORDER_LINKS_AWAITING_REVIEW:'存在相同来源订单号但尚未经人工核对的财务交易',
 NO_REVIEWED_LIVE_FINANCES:'尚无人工确认归属该订单的 LIVE Finances 交易',
 FINANCES_CLASSIFICATION_INCOMPLETE:'至少一笔来源交易的明细分类未全部核平',
 CURRENCY_MISMATCH:'财务来源与订单成本原币不一致',
 REFUND_COST_REVERSAL_UNVERIFIED:'存在退款，退货成本回转尚未核实',
 ADJUSTMENT_ATTRIBUTION_UNVERIFIED:'存在调整款，业务归属尚未核实',
 UNKNOWN_FINANCES_CATEGORY:'存在来源类别无法安全归属',
 PRODUCT_SALES_NOT_REVIEWED:'尚无经审核的商品销售来源',
 REVIEWED_SOURCE_CONTRIBUTION_PREVIEW:'已关联来源和历史成本可形成暂定贡献额'
}
export default function OrderContributionPanel({tenantId,marketplaceId,orderId}:{
 tenantId:string;marketplaceId:string;orderId:string
}){
 const query=useQuery({queryKey:['finance','contribution',tenantId,marketplaceId,orderId],
  queryFn:()=>getOrderContribution(marketplaceId,orderId),
  enabled:!!marketplaceId&&!!orderId,retry:false,staleTime:20_000})
 const data=query.data
 return <section className="panel full-panel" aria-label="订单已审核来源经营贡献预览">
  <div className="panel-heading"><div><h3>单笔订单 · 已审核来源经营贡献预览</h3>
   <p>使用同站点已人工关联的 LIVE 财务来源、逐笔已核平的原币明细分类和订单日期有效成本。
    不把 Orders 预计销售重复加到 Finances 来源总额。</p></div>
   <button type="button" className="refresh-button" onClick={()=>void query.refetch()}>
    重新核对</button></div>
  {query.isPending?<p className="empty-state">正在核对订单和受信来源…</p>:
   query.isError?<p className="setup-error" role="alert">{String(query.error)}</p>:
   data&&<>
    <p role="status">状态：<strong>{reasons[data.readiness]??data.readiness}</strong></p>
    <p className="setup-hint">已审核交易 {data.reviewedEventCount} 笔；
     订单来源关联待审 {data.pendingOrderLinks} 笔。全部金额保持 {data.currency} 原币。</p>
    {data.estimatedContribution===null?
      <p className="setup-hint">任一来源或成本前提缺失时，不提供可能误导的贡献额。
       请根据状态先在财务来源分类、订单关联或 SKU 历史成本中补齐证据。</p>:
      <div className="core-tools">
       <div className="orders-sync-grid">
        <div><span>已审核商品销售（有符号）</span>
         <strong>{data.currency} {data.signedSales}</strong></div>
        <div><span>平台费用来源（有符号）</span>
         <strong>{data.currency} {data.signedAmazonFees}</strong></div>
        <div><span>FBA 费用来源（有符号）</span>
         <strong>{data.currency} {data.signedFbaFees}</strong></div>
        <div><span>历史商品单位成本合计</span>
         <strong>{data.currency} {data.provisionalUnitCost}</strong></div>
       </div>
       <p><strong>暂定已审核来源经营贡献余额：
        {data.currency} {data.estimatedContribution}</strong></p>
      </div>}
    <p className="setup-hint">本结果始终不代表财务来源已经覆盖完整，
      也不证明银行到账或真实结算。退款成本回转、广告、税费、物流等来源可能尚未齐备；
      不能作为最终净利润或会计凭证。</p>
   </>}
 </section>
}
