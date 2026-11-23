import {useEffect,useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {getRefundBatchLineageV212} from './lib/refund-batch-lineage-v212'

/** On-demand original-sale-lot vs refund/intake quantity envelope, never COGS. */
export default function RefundBatchLineagePanelV212({
 tenantId,marketplaceId,orders
}:{tenantId:string;marketplaceId:string;orders:{orderId:string}[]}) {
 const [selected,setSelected]=useState('')
 useEffect(()=>setSelected(''),[tenantId,marketplaceId])
 const q=useQuery({
  queryKey:['finance','v212-refund-batch-lineage',tenantId,marketplaceId,selected],
  queryFn:()=>getRefundBatchLineageV212(marketplaceId,selected),
  enabled:!!selected&&orders.some(o=>o.orderId===selected),
  retry:false,staleTime:60_000,refetchOnWindowFocus:false
 })
 const p=q.data
 if(orders.length===0)return <p className="setup-hint">
  当前冻结订单中未发现本地已分类退款证据，不代表 Amazon 实际没有退款。</p>
 return <section className="v180-version-panel"
  aria-label="V212 退款与原销售批次的数量和收货证据交叉核对">
  <div className="v180-version-heading"><div>
   <span className="v180-section-kicker">V212 / ORIGINAL SALE LOT · RETURN ENVELOPE</span>
   <h3>原销售批次 × 退货入库 · 数量归属检查</h3>
   <p>对 V203 原销售拣货认领、V210 整单退款数量和 V171/V172
    人工收货记录做交叉核对。不推断 FIFO，不为退回件自动指定采购批次，
    不把采购参考单价当实际 COGS。</p>
  </div></div>
  <label className="shop-filter">选择涉及退款的原始订单
   <select value={selected} onChange={e=>setSelected(e.target.value)}>
    <option value="">选择后才执行批次与退款来源重算</option>
    {orders.map(o=><option key={o.orderId} value={o.orderId}>
     {o.orderId}</option>)}
   </select>
  </label>
  {selected&&!orders.some(o=>o.orderId===selected)?
   <p role="alert" className="setup-error">订单不属于当前退款报表范围。</p>:
  !selected?<p className="setup-hint">
   未选择订单不会发起高成本查询。数量没有核对不等于实际收货完成。</p>:
  q.isPending?<p role="status" className="setup-hint">
   正在复核销售批次认领、全部退款行与本地收货候选…</p>:
  q.isError?<p role="alert" className="setup-error">
   {String(q.error)}。批次、库存和退款成本均视为未核证，不输出零值。</p>:
  p&&<>
   <p className="v180-compare-hold" role="status">
    <strong>本地数量包络：</strong>
    {p.localAllItemQuantitiesConsistent?
     '既有本地记录的数量能够相互覆盖，仅表示没有明显超额':
     '存在批次认领、退货数量、人工收货或来源完整性冲突'}。
    已核对 {p.refundEventCount} 个退款事件，{p.reviewedItemRows} 条退款审核行，
    {p.originalSaleLotClaimCount} 条原销售批次认领及
    {p.returnIntakeCandidateCount} 条本地入库候选。
   </p>
   <div className="table-scroll" role="region" tabIndex={0}
    aria-label="V212 原始订单行、退款、原销售批次及返仓数量守恒">
    <table><thead><tr>
     {['订单行','原销售数量','退款审核数量','已认领原批次销售量',
      '声称返仓','本地见证收货','退款缺批次量','返仓缺见证量','局部状态'].map(x=>
      <th key={x} scope="col">{x}</th>)}
    </tr></thead><tbody>{p.orderItems.map(i=><tr key={i.orderItemId}>
     <td>{i.orderItemId}</td>
     <td>{i.originalSaleQuantity??'未知'}</td>
     <td>{i.reviewedRefundQuantity}</td>
     <td>{i.locallyAttributedSaleQuantity}</td>
     <td>{i.restockClaimedQuantity}</td>
     <td>{i.attestedLocalReturnQuantity}
      {i.pendingLocalReturnQuantity>0?
       '（另有待复核 '+i.pendingLocalReturnQuantity+'）':''}</td>
     <td>{i.unassignedRefundQuantity}</td>
     <td>{i.unattestedRestockQuantity}</td>
     <td>{i.localQuantityEnvelopeConsistent?
      '仅本地数量相符':i.blockerCodes.join('、')}</td>
    </tr>)}</tbody></table>
   </div>
   <p className="setup-hint">
    原始采购批次的单价参考属于不同采购单及可能不同币种，
    在未确认退回件的原批次与实际入库分摊前，不计算合计批次成本：
    {p.orderItems.flatMap(i=>i.saleLotClaims).slice(0,8).map(c=>
     ' ['+c.originalPurchaseReceiptId.slice(0,8)+'…/'+
     (c.purchaseReferenceCurrency??'币种未知')+' '+
     (c.purchaseReferenceUnitCost??'单价未知')+']').join('')||' 无可用原批次来源'}。
   </p>
   <p className="v179-evidence-warning" role="status">
    <strong>真实退款 COGS 与净利润始终不可计算。</strong>
    原销售认领不是 Amazon 已实际发货；本地人工返仓不是第三方实物验收，
    即使数量相符也没有退回件与原采购批次的一对一证据，不能自动过账。
    阻断：{p.blockerCodes.join('、')}。
   </p>
  </>}
 </section>
}
