import {useQuery} from '@tanstack/react-query'
import {fetchOrderCostReadiness} from './lib/order-cost-readiness'
import {fetchOrderLotProvenanceV202} from './lib/order-lot-provenance-v202'
const statuses:Record<string,string>={
 ORDER_NOT_LIVE:'订单并非受信 LIVE 来源',
 ITEMS_NOT_OBSERVED:'还没有取得订单项',
 ORDER_DATE_UNKNOWN:'订单业务时间未知',
 NO_ITEMS:'来源订单项为空',
 INCOMPLETE:'部分订单项缺少时间匹配成本或来源信息',
 COGS_READY_PREVIEW:'订单项与有效期单位成本已覆盖（暂定）'
}
const issues:Record<string,string>={
 READY:'覆盖',ORDER_NOT_LIVE:'订单来源尚未核验',
 ITEMS_NOT_OBSERVED:'订单项尚未取得',ORDER_DATE_UNKNOWN:'订单日期缺失',
 ITEM_SOURCE_UNVERIFIED:'订单项来源未核验',
 QUANTITY_UNKNOWN:'缺少订购数量',SELLER_SKU_UNKNOWN:'Seller SKU 未知',
 MAPPING_MISSING_AS_OF_DATE:'订单时间对应的 SKU 映射缺失',
 MAPPING_AMBIGUOUS_AS_OF_DATE:'订单时点出现多个 SKU 映射版本，成本已阻断',
 UNIT_COST_VERSION_OVERLAP_AS_OF_DATE:'订单业务日存在重叠的历史成本版本，成本已阻断',
 COST_EVIDENCE_COUNT_UNKNOWN:'成本来源候选数量无法确认',
 UNIT_COST_MISSING_AS_OF_DATE:'订单业务日的有效成本缺失',
 CURRENCY_MISMATCH:'单位成本币种和站点不同'
}
const localLotStates:Record<string,string>={
 NO_PRIOR_LOCAL_RECEIPT:'未找到早于订单的本地采购收货（不代表没有 Amazon 库存）',
 LOCAL_RECEIPT_OR_QC_IDENTITY_CONFLICT:'采购收货/批次/QC/放行流水存在身份或数量冲突',
 LEGACY_LOCAL_RECEIPT_NO_QC_BATCH:'包含旧版未绑定 QC 批次的本地收货',
 MULTIPLE_LOCAL_PO_COST_REFERENCES:'存在不同采购币种或采购单价，不能取任意一个',
 NO_RELEASED_LOCAL_BATCH:'本地批次尚无 QC 放行记录',
 LOCAL_BATCHES_NOT_LINKED_TO_SALES:'本地批次已记录，但未绑定这笔销售的实际出库批次'
}
export default function OrderCostReadinessPanel({tenantId,marketplaceId,orderId}:{
 tenantId:string;marketplaceId:string;orderId:string
}){
 const status=useQuery({queryKey:['finance','cost-readiness',tenantId,marketplaceId,orderId],
  queryFn:()=>fetchOrderCostReadiness(marketplaceId,orderId),
  enabled:!!marketplaceId&&!!orderId,retry:false})
 const lots=useQuery({queryKey:['finance','lot-provenance-v202',tenantId,marketplaceId,orderId],
  queryFn:()=>fetchOrderLotProvenanceV202(marketplaceId,orderId),
  enabled:!!marketplaceId&&!!orderId&&
   status.data?.readiness==='COGS_READY_PREVIEW',retry:false})
 return <section className="panel full-panel" aria-label="订单历史成本完整度">
  <div className="panel-heading"><div><h3>订单有效期成本覆盖率（只读预览）</h3>
   <p>仅使用来源订单项、对应 Marketplace 的 Seller SKU 历史映射、订单当地日期的单位成本；
    不自动汇率折算，也不将成本预览冒充财务已结算利润。</p></div>
   <button type="button" className="refresh-button" onClick={()=>void status.refetch()}>刷新</button></div>
  {status.isPending?<p className="empty-state">正在核对订单历史成本…</p>:
   status.isError?<p className="setup-error" role="alert">{String(status.error)}</p>:
   <><p role="status">状态：<strong>{statuses[status.data.readiness]??status.data.readiness}</strong>，
    已覆盖 {status.data.coveredItems}/{status.data.itemCount} 条订单项</p>
    <p className="setup-hint">来源订单时间（UTC）：
     {status.data.orderCreatedAt??'未知'}；
     {status.data.provisionalCost===null?' 未形成可用成本合计':
       ' 暂定商品成本：'+status.data.currency+' '+status.data.provisionalCost}
    </p>
    {!!status.data.items.length&&<div className="table-scroll" tabIndex={0} role="region"
      aria-label="订单商品项历史成本详情"><table><thead><tr>
      {['来源订单项','Seller SKU','数量','内部 SKU','历史单位成本','暂定行成本','覆盖状态'].map(h=>
        <th key={h} scope="col">{h}</th>)}</tr></thead><tbody>
     {status.data.items.map(item=><tr key={item.itemId}>
      <td>{item.itemId}</td><td>{item.sellerSku??'未知'}</td>
      <td>{item.quantity??'未知'}</td><td>{item.internalSku??'未映射'}</td>
      <td>{item.unitCost===null?'未取得':item.currency+' '+item.unitCost}</td>
      <td>{item.extendedCost===null?'—':status.data.currency+' '+item.extendedCost}</td>
      <td>{issues[item.issue]??item.issue}</td>
     </tr>)}</tbody></table></div>}
    <div className="subsection" aria-label="V202 采购批次来源核对">
     <h4>本地采购批次来源核对 · 未形成销售批次 COGS</h4>
     {status.data.readiness!=='COGS_READY_PREVIEW'?
      <p className="setup-hint">历史 SKU 成本尚未完整，不能进一步推断本地批次成本来源。</p>:
      lots.isPending?<p className="setup-hint">正在核对采购行、收货批次、QC 与放行流水…</p>:
      lots.isError?<p className="setup-error" role="alert">{String(lots.error)}</p>:
      lots.data&&<>
       <p className="setup-hint">
        本地采购记录仅供对照，不证明该 Amazon 订单由这些批次履约。
        销售→批次归属：未核验；实际批次 COGS：不可计算。
       </p>
       <div className="table-scroll" role="region" tabIndex={0}
        aria-label="本地采购批次来源诊断"><table><thead><tr>
        {['内部 SKU','历史本地收货笔数','QC 已放行批次','已观察本地放行数量',
          '采购单价参考（非实际 COGS）','完整性诊断'].map(x=>
          <th scope="col" key={x}>{x}</th>)}
        </tr></thead><tbody>
        {lots.data.skuEvidence.map(x=><tr key={x.internalSku}>
         <td>{x.internalSku}</td>
         <td>{x.priorLocalReceiptCount}</td>
         <td>{x.releasedBatchCount}</td>
         <td>{x.observedReleasedLocalUnits}</td>
         <td>{x.singlePurchaseCurrency&&x.singlePurchaseUnitCost?
          x.singlePurchaseCurrency+' '+x.singlePurchaseUnitCost:'未能唯一确认'}</td>
         <td>{localLotStates[x.status]??x.status}</td>
        </tr>)}
       </tbody></table></div>
       <p className="setup-hint">本地出库没有绑定 Amazon 销售订单项与原始批次；
        无论采购单价恰好相等或数量充足，都不能据此确认 FIFO、销售成本或利润。</p>
      </>}
    </div>
    <p className="setup-hint">这里的 COGS 是人工 SKU 成本历史与来源数量的暂估值，
     尚未与采购批次、仓储费、退款、税款或 Amazon 结算逐项核对。</p>
   </>}
 </section>
}
