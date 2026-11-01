import {useState} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {decideRefundRecovery,getRefundRecovery,getRefundRecoveryAudit} from './lib/refund-recovery'

const names:Record<string,string>={
 NOT_REQUESTED:'尚未发起退货成本来源复核',PENDING:'待另一位 Owner 独立审核',
 REVIEWED:'人工凭据已双人核对（不证明物理入库）',
 REJECTED:'人工凭据已驳回',STALE:'数量、金额、映射或依据发生变化，旧复核作废'}
const dispositions:Record<string,string>={
 RESTOCKED_CLAIMED:'人工声称重新入库，未经库存验证',
 NOT_RESTOCKED:'人工判断未重新入库',UNKNOWN:'实际处置未知'
}
export default function RefundRecoveryPanel({tenantId,marketplaceId,eventId}:{
 tenantId:string;marketplaceId:string;eventId:string
}){
 const qc=useQueryClient()
 const [reason,setReason]=useState('')
 const [showAudit,setShowAudit]=useState(false)
 const key=['finance','refund-recovery',tenantId,marketplaceId,eventId]
 const current=useQuery({queryKey:key,queryFn:()=>
  getRefundRecovery(marketplaceId,eventId),retry:false,staleTime:15000})
 const history=useQuery({queryKey:[...key,'audit'],enabled:showAudit,
  queryFn:()=>getRefundRecoveryAudit(marketplaceId,eventId),retry:false})
 const review=useMutation({mutationFn:(action:'request'|'approve'|'reject')=>
  decideRefundRecovery(marketplaceId,eventId,action,reason),
  onSuccess:()=>{setReason('');void qc.invalidateQueries({queryKey:key});
   void qc.invalidateQueries({queryKey:['finance','sku-profit',tenantId,marketplaceId]})}
 })
 const valid=reason.trim().length>=10&&reason.length<=500&&
  !/[\x00-\x1F\x7F]/.test(reason)
 const d=current.data
 return <section className="panel full-panel" aria-label="V63 退款成本风险与退货处置">
  <div className="panel-heading"><div><h3>V63 · 退款成本风险与退货处置双人复核</h3>
   <p>合并 V34 退款数量、V35 已核对的商品退款金额与下单日期的 SKU 成本。
    “重新入库”仅是人工声明，不代表可信库存收货或成本会计回转。</p>
  </div><button type="button" className="refresh-button"
   onClick={()=>void current.refetch()} disabled={current.isFetching}>刷新来源</button></div>
  {current.isPending?<p>读取退款商品、金额归属及成本版本…</p>:
   current.isError?<p className="setup-hint" role="status">
    目前不能核对成本影响：{String(current.error)}</p>:
   d&&<>
    <p role="status"><strong>{names[d.state]??d.state}</strong>
     {' · '}版本 {d.revision}，来源 MANUAL_UNVERIFIED</p>
    <div className="orders-sync-grid">
     <div><span>已核对商品退款原币</span><strong>
      {d.exposure.currency} {d.exposure.signedRefundTotal}</strong></div>
     <div><span>尚未归属的商品退款差额</span><strong>
      {d.exposure.currency} {d.exposure.remainingRefund}</strong></div>
     <div><span>相关历史成本风险敞口（非损失入账）</span><strong>
      {d.exposure.currency} {d.exposure.historicalCostExposure}</strong></div>
     <div><span>声称重新入库的潜在回转敏感度</span><strong>
      {d.exposure.currency} {d.exposure.claimedRestockCostSensitivity}</strong></div>
    </div>
    <p className="setup-hint">核对：{d.exposure.status==='MANUAL_EVIDENCE_BALANCED_UNVERIFIED'?
     '退款金额已人工逐项归属（不证明到账/入库）':
     '仍存在退款金额或商品数量对应缺口，不能提交审核'}。
     可确认的实际成本回转：**尚无证据，未确认**。未更改库存、会计凭证或 V61 利润。</p>
    <div className="table-scroll" role="region" tabIndex={0}
     aria-label="退款对应历史成本风险">
     <table><thead><tr><th>订单项</th><th>SKU</th><th>退款件数</th>
      <th>有符号退款</th><th>历史成本敞口</th><th>人工处置</th><th>潜在回转</th></tr></thead>
      <tbody>{d.exposure.items.map(i=><tr key={i.itemId}>
       <td>{i.itemId}</td><td>{i.sku}</td><td>{i.refundedQuantity}</td>
       <td>{i.signedRefund}</td><td>{i.historicalCostExposure}</td>
       <td>{dispositions[i.disposition]}</td>
       <td>{i.claimedRestockCostSensitivity}</td>
      </tr>)}</tbody></table></div>
    <label htmlFor="refund-recovery-reason">退款及退货处置来源复核说明（不可含个人信息）</label>
    <textarea id="refund-recovery-reason" rows={2} maxLength={500}
     value={reason} onChange={e=>setReason(e.target.value)}
     placeholder="核对了来源交易的商品退款、原始商品行、退款数量和人工处置依据"/>
    <div className="sandbox-actions">
     <button type="button" className="refresh-button"
      disabled={!valid||review.isPending||d.state==='PENDING'||
       d.exposure.status!=='MANUAL_EVIDENCE_BALANCED_UNVERIFIED'}
      onClick={()=>review.mutate('request')}>申请人工证据复核</button>
     <button type="button" className="primary-button"
      disabled={!valid||review.isPending||d.state!=='PENDING'||!d.evidenceCurrent}
      onClick={()=>review.mutate('approve')}>独立 Owner 确认人工证据</button>
     <button type="button" className="text-button"
      disabled={!valid||review.isPending||d.state!=='PENDING'||!d.evidenceCurrent}
      onClick={()=>review.mutate('reject')}>驳回证据</button>
    </div>
    {review.error&&<p role="alert" className="setup-error">{String(review.error)}</p>}
    <button className="text-button" type="button"
     onClick={()=>setShowAudit(v=>!v)}>{showAudit?'收起':'查看'}复核审计</button>
    {showAudit&&(history.isPending?<p>正在读取审计…</p>:
     history.isError?<p role="alert" className="setup-error">{String(history.error)}</p>:
     <div className="table-scroll" role="region" tabIndex={0} aria-label="退款成本来源审计">
      <table><thead><tr><th>版本</th><th>动作</th><th>操作人</th><th>说明</th><th>时间</th></tr></thead>
       <tbody>{(history.data??[]).map((a,i)=><tr key={i}>
        <td>{a.revision}</td><td>{a.action}</td><td>{a.actorId}</td>
        <td>{a.reason}</td><td>{a.occurredAt}</td>
       </tr>)}</tbody></table></div>)}
    <p className="panel-caption">所有“实际收货已核实”“已进行 COGS 会计回转”和“财务过账”标志固定为 false。
     双人审核只核对**人工来源**。实际可回转成本必须依靠以后独立的受信退货收货/处置记录和正式会计规则。</p>
   </>}
 </section>
}
