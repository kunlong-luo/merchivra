import {useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {getPurchaseReconciliation,type FbaReconciliationLine} from './lib/fba-purchase-reconciliation'

const codeLabels:Record<FbaReconciliationLine['findingCode'],string>={
 LOCAL_OVER_REPORT:'本地报告数量超过计划，尚未接受差异',
 PENDING_IDENTITY_REVIEW:'采购/ASN 身份关联等待第二 Owner 审核',
 LINK_SOURCE_CHANGED:'已确认关联的采购或 ASN 状态已变化',
 LOCAL_VARIANCE_PENDING:'本地计划与报告数量差异尚待复核',
 LOCAL_REPORT_WITHOUT_IDENTITY:'已有本地入仓报告，但未建立已确认的采购身份关联',
 DISPATCHED_WITHOUT_LINK:'本地货件已登记发运，但无有效采购身份关联',
 UNLINKED_DRAFT:'尚未人工关联采购来源的本地草稿',
 LOCAL_VARIANCE_ACCEPTED:'本地数量差异已由人工接受',
 CONFIRMED_PO_ONLY:'采购身份已人工确认，尚无 ASN 关联',
 IDENTITY_CONFIRMED:'采购/ASN 身份已由不同 Owner 确认'
}
const actionLabels:Record<FbaReconciliationLine['recommendedAction'],string>={
 REVIEW_LOCAL_VARIANCE:'检查本地差异',
 SECOND_OWNER_REVIEW:'第二 Owner 核实身份',
 RECHECK_SOURCE_IDENTITY:'人工核查源单状态',
 REVIEW_MISSING_LINK:'检查是否应补充身份关联',
 OPTIONAL_MANUAL_LINK:'可选：提供人工关联证据',
 OPTIONAL_ASN_EVIDENCE:'可选：核对供应商 ASN',
 NONE:'仅保留事实记录'
}

export default function FbaPurchaseReconciliationPanel({
 tenantId,marketplaceId,onOpenShipment
}:{
 tenantId:string;marketplaceId:string;onOpenShipment:(id:string)=>void
}){
 const [filter,setFilter]=useState<'ALL'|'ACTIONABLE'|'HIGH'|'WARNING'|'INFO'>('ACTIONABLE')
 const query=useQuery({
  queryKey:['fba-inbound',tenantId,marketplaceId,'reconciliation-v76'],
  queryFn:()=>getPurchaseReconciliation(marketplaceId),retry:false
 })
 const rows=query.data?.lines.filter(row=>
  filter==='ALL'||filter==='ACTIONABLE'&&row.severity!=='INFO'||
  filter===row.severity)??[]
 return <section className="core-tools" aria-label="V76 采购 ASN FBA 本地证据差异工作台">
  <div className="panel-heading"><div>
   <h3>V76 · 采购 / ASN / FBA 本地证据核对</h3>
   <p>只读异常分类与人工复核建议。仅检查最近最多 100 张本地货件，最多显示 500 行；
    关联身份与数量均为本地或 MOCK 记录，不代表 Amazon 平台签收、可售或货权已转移。</p>
  </div><button type="button" className="refresh-button" disabled={query.isFetching}
    onClick={()=>void query.refetch()}>刷新差异</button></div>
  {query.isPending?<p className="empty-state">正在核对本地证据来源…</p>:
   query.isError?<p className="setup-error" role="alert">{String(query.error)}</p>:
   !query.data?<p className="empty-state">没有可用的核对数据。</p>:
   <>
    <div className="ops-summary" role="status">
     已检查 {query.data.totals.examinedLines} 行 · 高优先级 {query.data.totals.high} ·
     待人工核查 {query.data.totals.warning} · 等待第二 Owner {query.data.totals.pendingIdentity} ·
     未关联 {query.data.totals.unlinked}
    </div>
    {query.data.truncated&&<p className="setup-error" role="alert">
     已达到 500 行读取上限；本页汇总仅涵盖已展示范围，不代表所有货件。</p>}
    <label htmlFor="v76-severity-filter">筛选核对状态</label>
    <select id="v76-severity-filter" value={filter}
      onChange={e=>setFilter(e.target.value as typeof filter)}>
     <option value="ACTIONABLE">需要人工核查</option>
     <option value="ALL">全部记录</option>
     <option value="HIGH">高优先级</option>
     <option value="WARNING">待核查</option>
     <option value="INFO">仅提示</option>
    </select>
    {!rows.length?<p className="empty-state">当前筛选范围没有需要展示的本地核对记录。</p>:
    <div className="table-scroll" role="region" tabIndex={0}
       aria-label="V76 采购与 FBA 本地货件差异核对表">
     <table><thead><tr>
      <th scope="col">本地货件 / Seller SKU</th>
      <th scope="col">本地计划 / 报告</th>
      <th scope="col">采购 / ASN 来源证据</th>
      <th scope="col">风险解释与人工动作</th>
     </tr></thead><tbody>
     {rows.map(row=><tr key={row.inboundLineId}>
      <td><strong>{row.shipmentReference}</strong>
       <div className="secondary-cell">{row.sellerSku} · {row.shipmentOrigin==='MOCK'?'MOCK 虚构':'手工未核验'}</div>
       <button type="button" className="text-button" onClick={()=>onOpenShipment(row.shipmentId)}>
        查看对应货件</button></td>
      <td>计划 {row.plannedQuantity} · 本地报告 {row.locallyReportedQuantity}
       <div className="secondary-cell">
        {row.localVarianceDecision==='ACCEPT_VARIANCE'?'本地差异已人工接受':'未证明 Amazon 入仓'}</div></td>
      <td>{row.purchaseOrderNumber??'未确认采购来源'}
       <div className="secondary-cell">PO 状态：{row.purchaseOrderState??'未知'} ·
        订购 {row.purchaseOrderedQuantity??'未知'} · 本地实收 {row.purchaseLocalReceivedQuantity??'未知'}</div>
       <div className="secondary-cell">ASN：{row.asnId?row.asnId.slice(0,8)+' · '+row.asnState:'未关联'} ·
        申报 {row.asnReportedQuantity??'未知'} · 本地关联实收 {row.asnLocalReceivedQuantity??'未知'}</div>
       <div className="secondary-cell">身份审核：{row.linkState==='CONFIRMED'?'双人已确认':
         row.linkState==='PROPOSED'?'等待第二 Owner':'未确认'}</div>
      </td>
      <td><strong>{row.severity==='HIGH'?'高':row.severity==='WARNING'?'需核查':'提示'}</strong>
       <div>{codeLabels[row.findingCode]}</div>
       <div className="secondary-cell">建议：{actionLabels[row.recommendedAction]}</div>
      </td>
     </tr>)}
     </tbody></table>
    </div>}
   </>}
  <p className="panel-caption">不同环节的原始收货数量不允许相减推断“丢件”或“Amazon 已接收”；
   只对同一货件行的本地计划与本地报告数量做差异提醒。没有有效关联也不意味着货物丢失。
   所有风险和建议均只读，不自动创建 ASN、采购、库存流水或 Amazon API 操作。</p>
 </section>
}
