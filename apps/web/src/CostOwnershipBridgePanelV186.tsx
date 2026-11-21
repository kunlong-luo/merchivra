import {useQuery} from '@tanstack/react-query'
import {fetchCostOwnershipBridgeV186}
 from './lib/cost-ownership-bridge-v186'

const dimLabels:Record<string,string>={
 SKU:'SKU 主分摊',ORDER:'订单主分摊',
 PERIOD:'期间主分摊',UNASSIGNED:'没有有效分摊'
}
const catLabels:Record<string,string>={
 ADS_SPEND:'广告',INBOUND_FREIGHT:'头程',
 FBA_STORAGE:'FBA 仓储',FBM_SHIPPING:'FBM 配送',
 RETURN_HANDLING:'退货处理',IMPORT_DUTY:'进口税费',
 PAYMENT_AND_FX:'支付与汇兑',OTHER_OPERATING:'其他经营成本'
}
/**
 * Full-period V91 claimed cost source count, once per source. Amounts never
 * enter recognized V162/V64 expense or any reported net-profit ledger.
 */
export default function CostOwnershipBridgePanelV186({tenantId,marketplaceId,start,end}:{
 tenantId:string;marketplaceId:string;start:string;end:string
}){
 const state=useQuery({
  queryKey:['finance','v186-single-source-bridge',tenantId,marketplaceId,start,end],
  queryFn:()=>fetchCostOwnershipBridgeV186(marketplaceId,start,end),
  enabled:!!marketplaceId,retry:false
 })
 return <section className="v181-line-identity"
  aria-label="成本来源单一归属维度与防重复扣减">
  <h3 className="v179-evidence-section">单一成本来源 · SKU / 订单 / 期间归属桥接</h3>
  <p className="setup-hint">按照来源业务日期统计**全期间**人工费用；每笔 V91 来源
   仅归入一个有效 V92 分摊维度，已驳回历史不构成新费用。若从 SKU、订单、
   期间再生成其他报表，只能引用此来源，不能再扣同一笔费用。
   以下金额仍是人工主张，不是账务确认日的费用，也不是可扣真实利润。</p>
  {state.isPending?<p className="setup-hint" role="status">
   核对全期间单一来源费用归属…</p>:
   state.isError?<p className="setup-error" role="alert">
    {String(state.error)}</p>:
   state.data&&<div>
    <p className="setup-hint" role="status">
     本期去重来源总数 <strong>{state.data.sourceCount}</strong>，
     主归属维度只代表本地分摊提案，不代表经认证费用。
     {state.data.sourceCount===0?'目前没有来源，不意味着成本为零。':''}
    </p>
    {state.data.buckets.length>0&&
     <div className="table-scroll" role="region" tabIndex={0}
      aria-label="全期间费用来源主归属维度汇总">
      <table><thead><tr>
       {['类别','原币','唯一主归属','来源数','已批准未核验来源数',
        '待审核来源数','尚无有效分摊','申报费用（未认证）',
        '已批准但未核验分配','已批准剩余未分配'].map(t=>
         <th scope="col" key={t}>{t}</th>)}
      </tr></thead><tbody>{state.data.buckets.map(b=>
       <tr key={b.category+'/'+b.currency+'/'+b.primaryDimension}>
        <td>{catLabels[b.category]??b.category}</td>
        <td>{b.currency}</td>
        <td>{dimLabels[b.primaryDimension]}</td>
        <td>{b.sourceCount}</td>
        <td>{b.approvedUnverifiedSourceCount}</td>
        <td>{b.pendingSourceCount}</td>
        <td>{b.unassignedSourceCount}</td>
        <td>{b.sourceClaimedAmount}</td>
        <td>{b.approvedUnverifiedAllocatedAmount??'未批准，金额未知'}</td>
        <td>{b.approvedUnverifiedRemainderAmount??'未批准，金额未知'}</td>
       </tr>)}</tbody></table>
     </div>}
    <p className="v179-evidence-warning" role="status">
     仍未收到来源声明的成本类别：
     {state.data.categoriesWithoutSourceClaims.length
      ?state.data.categoriesWithoutSourceClaims.map(c=>catLabels[c]??c).join('、')
      :'无（但仍缺外部费用完整性认证）'}。
     退款 COGS 回转需要独立实收与财务政策；跨币种换算、银行对账及真实净利润
     均未核验，不能把零个来源等同零费用。阻断代码：
     {state.data.blockerCodes.join('、')}。
    </p>
   </div>}
 </section>
}
