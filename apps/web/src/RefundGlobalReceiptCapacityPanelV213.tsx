import {useEffect,useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {getRefundGlobalReceiptCapacityV213}
 from './lib/refund-global-receipt-capacity-v213'

/** V213 is only queried when inspecting a V212 original-refund order. */
export default function RefundGlobalReceiptCapacityPanelV213({
 tenantId,marketplaceId,orderId
}:{tenantId:string;marketplaceId:string;orderId:string}) {
 const [expanded,setExpanded]=useState(false)
 useEffect(()=>setExpanded(false),[tenantId,marketplaceId,orderId])
 const q=useQuery({
  queryKey:['finance','v213-original-receipt-capacity',
   tenantId,marketplaceId,orderId],
  queryFn:()=>getRefundGlobalReceiptCapacityV213(marketplaceId,orderId),
  enabled:expanded&&!!orderId&&!!marketplaceId,
  retry:false,refetchOnWindowFocus:false,staleTime:60_000
 })
 const p=q.data
 return <section className="v180-version-panel"
   aria-label="V213 全部销售订单共享原采购批次的数量与成本证据阻断">
  <div className="v180-version-heading"><div>
   <span className="v180-section-kicker">V213 / SHARED RECEIPT · LANDED COST HOLD</span>
   <h3>原采购收货批次 · 跨订单数量守恒</h3>
   <p>独立核对同一批次被其他本地销售订单共同认领的全部数量。
    已撤销的历史认领仍占用原容量；共享收货单不代表重复扣费。
    采购参考单价不包含已核证的物流、关税、汇兑及最终入库成本。</p>
  </div></div>
  <div className="sandbox-actions">
   <button type="button" className="text-button"
    aria-expanded={expanded} onClick={()=>setExpanded(v=>!v)}>
    {expanded?'收起跨订单批次复核':'按需核对所有订单的原采购收货容量'}
   </button>
   {expanded&&<button type="button" className="refresh-button"
     disabled={q.isFetching} onClick={()=>{void q.refetch()}}>重新核对</button>}
  </div>
  {!expanded?<p className="setup-hint">未执行本次跨订单源复核不代表实际成本为零，
    也不代表所有真实退货都有原批次归属。</p>:
   q.isPending?<p role="status" className="setup-hint">
    正在复核原批次全部订单的领取数量与历史撤销状态…</p>:
   q.isError?<p role="alert" className="setup-error">
    {String(q.error)}。不得用部分原批次结果推算退款成本。</p>:
   p&&<>
    <div className="ops-summary">
     {[
      ['本订单涉及采购收货批次',String(p.originalReceiptCount)],
      ['这些批次的全局原销售认领记录',String(p.globalClaimRows)],
      ['被多个订单共享的采购批次',String(p.sharedOriginalReceiptCount)],
      ['其他订单/收货批次关联数',String(p.otherOrderReceiptMembershipCount)],
      ['已核证实际退货成本','证据不足 · 不计算']
     ].map(([name,value])=><div key={name}>
      <span>{name}</span><strong>{value}</strong>
     </div>)}
    </div>
    <p role="status" className="v180-compare-hold">
     <strong>全局本地数量状态：</strong>
     {p.completeGlobalLocalReceiptCapacity
       ?'现有全部本地原销售认领未超过原 QC 接受量；未获外部真实性核证'
       :'存在历史撤销、容量、来源版本或其他证据阻断'}。
    </p>
    {p.receipts.length>0&&<div className="table-scroll"
      role="region" tabIndex={0}
      aria-label="原采购收货跨订单的总领取与 QC 接受数量">
      <table><thead><tr>
       {['原采购收货 ID','本订单领取','所有订单累计领取',
        '原 QC 接受量','其他订单领取','本地未认领量',
        '订单数','原采购参考单价','读时状态'].map(x=>
         <th key={x} scope="col">{x}</th>)}
      </tr></thead><tbody>{p.receipts.map(r=><tr key={r.originalPurchaseReceiptId}>
       <td>{r.originalPurchaseReceiptId.slice(0,13)}…</td>
       <td>{r.thisOrderClaimedUnits}</td>
       <td>{r.allOrdersClaimedUnits}</td>
       <td>{r.originalQcAcceptedUnits}</td>
       <td>{r.unitsClaimedByOtherOrders}</td>
       <td>{r.locallyUnclaimedAcceptedUnits}</td>
       <td>{r.distinctOriginalSaleOrders}</td>
       <td>{r.originalCurrency??'未知'} {' '}
        {r.originalPurchaseReferenceUnitPrice??'未知'}</td>
       <td>{r.localCapacityState}</td>
      </tr>)}</tbody></table>
    </div>}
    <p className="v179-evidence-warning" role="status">
     <strong>实际落地成本、退货批次实际归属、退款 COGS 与净利润均未核证。</strong>
     本地仓库认领不是 Amazon 发货或第三方退货物理实收；
     同一收货单的共享使用不等于已重复会计过账，不能从本表计算真实利润。
     阻断项：{p.blockerCodes.join('、')}。
    </p>
   </>}
 </section>
}
