import {useQuery} from '@tanstack/react-query'
import {fetchOrderCostReadiness} from './lib/order-cost-readiness'
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
 UNIT_COST_MISSING_AS_OF_DATE:'订单业务日的有效成本缺失',
 CURRENCY_MISMATCH:'单位成本币种和站点不同'
}
export default function OrderCostReadinessPanel({tenantId,marketplaceId,orderId}:{
 tenantId:string;marketplaceId:string;orderId:string
}){
 const status=useQuery({queryKey:['finance','cost-readiness',tenantId,marketplaceId,orderId],
  queryFn:()=>fetchOrderCostReadiness(marketplaceId,orderId),
  enabled:!!marketplaceId&&!!orderId,retry:false})
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
    <p className="setup-hint">这里的 COGS 是人工 SKU 成本历史与来源数量的暂估值，
     尚未与采购批次、仓储费、退款、税款或 Amazon 结算逐项核对。</p>
   </>}
 </section>
}
