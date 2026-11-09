import {useQuery,useQueryClient} from '@tanstack/react-query'
import {useEffect,useState} from 'react'
import CursorPageControls from './CursorPageControls'
import {fetchPurchaseDeliveryFullSummary,fetchPurchaseDeliveryLinesPage,
 type PurchaseDeliveryLine} from './lib/purchase-delivery'

const labels:Record<PurchaseDeliveryLine['deliveryState'],string>={
 DISCREPANCY_REVIEW:'存在未结采购差异，请核查',
 AWAITING_QC_RELEASE:'已登记收货，待质检/双人放行',
 QC_EVIDENCE_UNKNOWN:'有历史收货缺少 V46 批次证据',
 LOCAL_RECEIVED:'本地收货已登记（不代表 FBA 入库）',
 REPORTED_ETA_OVERDUE:'供应商申报 ETA 逾期，仍有未关联收货',
 REPORTED_ETA_DUE_SOON:'供应商申报 ETA 三日内到期',
 SHIPMENT_REPORTED:'供应商申报已发货（承运商未核验）',
 ASN_AWAITING_DISPATCH:'存在 ASN 草稿，尚未申报发货',
 NO_ASN_RECORDED:'已审批但没有有效供应商 ASN'
}
export default function PurchaseDeliveryPanel({tenantId,onOpenOrder}:{
 tenantId:string;onOpenOrder:(orderId:string)=>void
}){
 const client=useQueryClient()
 const [cursors,setCursors]=useState<(string|null)[]>([null])
 const cursor=cursors.at(-1)??null
 useEffect(()=>setCursors([null]),[tenantId])
 const root=['purchasing',tenantId,'delivery']
 const summary=useQuery({queryKey:[...root,'summary-v119'],
  queryFn:fetchPurchaseDeliveryFullSummary,retry:false})
 const page=useQuery({queryKey:[...root,'lines-page-v119',cursor],
  queryFn:()=>fetchPurchaseDeliveryLinesPage(cursor),retry:false})
 const items=page.data?.items??[]
 return <section className="panel full-panel" aria-label="采购交付进度与异常看板">
  <div className="panel-heading"><div><h2>采购交付进度与异常 · V119</h2>
   <p>按已审批采购单逐 SKU 汇总本地事实：申报 ASN ≠ 承运商发货；
    关联采购收货 ≠ 质检放行 ≠ Amazon FBA 入仓。无 ASN 时不会编造 ETA。</p></div>
   <button type="button" className="refresh-button" disabled={summary.isFetching||page.isFetching}
    onClick={()=>{setCursors([null]);void client.invalidateQueries({queryKey:root})}}>刷新交付进度</button></div>
  {summary.data&&<p className="setup-hint">
   截至 {summary.data.asOfDateUtc} UTC · 全租户 {summary.data.totals.lineCount} 条已批准采购明细
   （PostgreSQL 对所有符合条件的本地采购行汇总，非当前页估算） ·
   尚未本地收货 {summary.data.totals.pendingQuantity} 件 ·
   供应商申报已发未关联收货 {summary.data.totals.reportedUnreceivedQuantity} 件 ·
   待质检放行 {summary.data.totals.awaitingQcReleaseQuantity} 件 ·
   需关注 {summary.data.totals.warningLines} 条（其中 ETA 逾期 {summary.data.totals.overdueLines} 条，
   差异 {summary.data.totals.unresolvedVarianceLines} 条）。
   所有数量来自当前租户本地记录，不代表外部物流或资金状态。</p>}
  {summary.isPending||page.isPending?<p className="empty-state">读取采购交付证据…</p>:
   summary.isError||page.isError?<p className="setup-error" role="alert">{String(summary.error??page.error)}</p>:
   !items.length?<p className="empty-state">暂无已经审批的采购明细。采购草稿和待审批订单不在本表。</p>:
   <div className="table-scroll" role="region" tabIndex={0} aria-label="采购单交付追踪表">
    <table><thead><tr>
     <th scope="col">采购单 / SKU</th>
     <th scope="col">已收 / 订购 · 未收</th>
     <th scope="col">ASN 草稿 / 申报发货未收</th>
     <th scope="col">申报 ETA 区间</th>
     <th scope="col">质量与差异</th>
     <th scope="col">处理状态</th><th scope="col">操作</th>
    </tr></thead><tbody>
    {items.map(item=><tr key={item.lineId}>
     <td><strong>{item.orderNumber}</strong><div className="secondary-cell">
      {item.supplierName} · {item.internalSku} · {item.currency}</div>
      {item.replenishmentSellerSku&&<div className="secondary-cell">
       补货来源 {item.replenishmentSellerSku} ·
       {item.replenishmentPricingSource==='V42_RULE'?'V42 MOQ/箱规报价':'人工报价'}</div>}
     </td>
     <td>{item.receivedQuantity} / {item.orderedQuantity}
      <div className="secondary-cell">未收 {item.remainingQuantity} 件</div></td>
     <td>草稿 {item.draftAsnQuantity} · 已申报 {item.shippedAsnQuantity}
      <div className="secondary-cell">申报发货后未关联收货 {item.reportedUnreceivedQuantity}</div></td>
     <td>{item.earliestReportedEta??'未申报'}
      {item.latestReportedEta&&item.latestReportedEta!==item.earliestReportedEta?
       ' ～ '+item.latestReportedEta:''}</td>
     <td>待放行 {item.awaitingQcReleaseQuantity} · 已放行接受 {item.releasedQcAcceptedQuantity}
      <div className="secondary-cell">历史 QC 未知 {item.qcUnknownQuantity} ·
       未结差异 {item.unresolvedVarianceCount}</div></td>
     <td>{labels[item.deliveryState]}</td>
     <td><button type="button" className="refresh-button"
      onClick={()=>onOpenOrder(item.orderId)}>打开采购单 / ASN</button></td>
    </tr>)}
    </tbody></table>
   </div>}
  {page.data&&<CursorPageControls label="采购交付明细全库"
   page={cursors.length} hasMore={page.data.hasMore}
   nextCursor={page.data.nextCursor} busy={page.isFetching}
   onPrev={()=>setCursors(x=>x.slice(0,-1))}
   onNext={next=>setCursors(x=>[...x,next])}/>}
  <p className="setup-hint">当前表格是第 {cursors.length} 页、最多 50 条；
   上方数量由独立的全租户汇总接口计算，并非当前页求和。两个请求不是同一个数据库事务快照，
   如有新收货或订单状态变化请刷新核对。</p>
  <p className="panel-caption">到货逾期提醒沿用 V43 供应商 ASN 待办与手动扫描；
   本看板只读取已有业务记录，不创建另一套告警任务，不发邮件或操作 Amazon。
   无 ASN/草稿未发货为待跟进事项，绝不推断已经逾期或已经发运。</p>
 </section>
}
