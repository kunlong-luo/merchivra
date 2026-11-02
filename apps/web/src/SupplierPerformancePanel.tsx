import {useState} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {
 fetchSupplierScorecards,fetchPurchaseFollowups,scanPurchaseFollowups,
 setPurchaseFollowupState,type FollowupState
} from './lib/supplier-performance'

const states={
 ASN_COVERAGE_GAP:'审批已到跟进日，但 ASN 覆盖件数仍不足',
 ASN_NOT_DISPATCHED:'ASN 已覆盖采购数量，但供应商尚未申报发货'
}
export default function SupplierPerformancePanel({tenantId,onOpenOrder}:{
 tenantId:string;onOpenOrder:(id:string)=>void
}){
 const client=useQueryClient()
 const [filter,setFilter]=useState<'ACTIVE'|'ALL'>('ACTIVE')
 const [message,setMessage]=useState(''),[error,setError]=useState('')
 const key=['purchasing',tenantId,'supplier-performance']
 const scores=useQuery({queryKey:[...key,'scorecards'],
  queryFn:fetchSupplierScorecards,retry:false})
 const followups=useQuery({queryKey:[...key,'followups',filter],
  queryFn:()=>fetchPurchaseFollowups(filter),retry:false})
 const refresh=()=>{void client.invalidateQueries({queryKey:key});
  void client.invalidateQueries({queryKey:['purchasing',tenantId,'delivery']})}
 const scan=useMutation({mutationFn:scanPurchaseFollowups,
  onSuccess:r=>{setMessage('已完成本地采购催办扫描：匹配 '+r.matchedCount+
    ' 条，清除 '+r.clearedCount+' 条');setError('');refresh()},
  onError:(e:Error)=>setError(e.message)})
 const change=useMutation({mutationFn:({id,state}:{id:string;state:FollowupState})=>
    setPurchaseFollowupState(id,state),
  onSuccess:()=>{setMessage('催办状态已审计保存');setError('');refresh()},
  onError:(e:Error)=>setError(e.message)})
 return <section className="panel full-panel" aria-label="供应商履约评分与采购人工催办">
  <div className="panel-heading"><div><h2>供应商履约证据评分 · V73</h2>
   <p>只按近 90 天完整 ASN 关联的本地收货日期与当时已登记的供应商 ETA 计算准时样本；
    QC 合格率来自已完成的实物质检。没有足够证据不显示评分。</p></div>
   <button type="button" className="refresh-button" onClick={refresh}>刷新评分</button></div>
  {scores.isPending?<p className="empty-state">读取供应商履约证据…</p>:
   scores.isError?<p className="setup-error" role="alert">{String(scores.error)}</p>:
   !scores.data?.length?<p className="empty-state">尚无有效供应商。</p>:
   <div className="table-scroll" tabIndex={0} role="region" aria-label="供应商履约样本评分表">
    <table><thead><tr><th scope="col">供应商</th>
     <th scope="col">完整 ASN / 准时样本</th><th scope="col">已检收货批次 / QC 接受量</th>
     <th scope="col">待结差异</th><th scope="col">临时证据评分</th></tr></thead><tbody>
     {scores.data.map(s=><tr key={s.supplierId}>
      <td>{s.supplierName}</td>
      <td>{s.observedOnTimeAsnCount} / {s.completeAsnSampleCount}
       <div className="secondary-cell">准时比例 {s.observedOnTimePercent===null?'证据不足':s.observedOnTimePercent+'%'}</div></td>
      <td>{s.inspectedReceiptSampleCount} 批 · 接受 {s.acceptedQcUnits} / 已检 {s.inspectedQcUnits}
       <div className="secondary-cell">接受率 {s.inspectedAcceptancePercent===null?'证据不足':s.inspectedAcceptancePercent+'%'}</div></td>
      <td>{s.activeVarianceCount}</td>
      <td>{s.evidenceScore===null?'未评分：样本不足':s.evidenceScore+' / 100（参考）'}</td>
     </tr>)}
    </tbody></table>
   </div>}
  <p className="panel-caption">参考评分＝60% × ASN 收货准时率 + 40% × QC 接受率；
   两类有效样本各至少 3 条才计算。人工 ETA 不是承运商承诺，评分不直接用于自动取消供应商、付款或采购决策。
   差异单独列示，未混入评分；正在运输/未验收的数据不算失败样本。</p>
  <div className="panel-heading"><div><h3>采购审批后人工跟进待办</h3>
   <p>审批后默认第 7 个 UTC 日开始检查尚未覆盖的 ASN 或已覆盖却未申报发货；
    已申报发货后的 ETA 逾期继续在原 V43 ASN 待办处理，不重复建单。</p></div>
   <button type="button" className="primary-button" disabled={scan.isPending}
    onClick={()=>scan.mutate()}>手动扫描待跟进采购</button></div>
  <label htmlFor="po-followup-filter">待办范围</label>
  <select id="po-followup-filter" value={filter}
   onChange={e=>setFilter(e.target.value as 'ACTIVE'|'ALL')}>
   <option value="ACTIVE">需要处理</option><option value="ALL">全部（含已清除）</option>
  </select>
  {followups.isPending?<p className="empty-state">正在读取采购催办记录…</p>:
   followups.isError?<p className="setup-error" role="alert">{String(followups.error)}</p>:
   !followups.data?.length?<p className="empty-state">暂无这类采购催办。可以手动扫描。</p>:
   <div className="table-scroll" tabIndex={0} role="region" aria-label="采购交付催办待办表">
    <table><thead><tr><th scope="col">采购单 / SKU</th>
     <th scope="col">跟进原因</th><th scope="col">订购 / 已收 / ASN 覆盖</th>
     <th scope="col">状态</th><th scope="col">操作</th></tr></thead><tbody>
     {followups.data.map(f=><tr key={f.id}>
      <td>{f.orderNumber}<div className="secondary-cell">{f.supplierName} · {f.internalSku}</div></td>
      <td>{states[f.ruleCode]}<div className="secondary-cell">
       该行未覆盖 {f.uncoveredQuantity} 件；批准于 {f.approvedAt.slice(0,10)} UTC</div></td>
      <td>{f.orderedQuantity} / {f.receivedQuantity} / {f.activeAsnQuantity}</td>
      <td>{f.state==='OPEN'?'待认领':f.state==='ACKNOWLEDGED'?'已认领':f.state==='DONE'?'人工已处理':'条件已消失'}</td>
      <td><div className="sandbox-actions">
       <button type="button" className="refresh-button"
        onClick={()=>onOpenOrder(f.orderId)}>打开采购单</button>
       {f.state!=='CLEARED'&&(['OPEN','ACKNOWLEDGED','DONE'] as const)
        .filter(x=>x!==f.state).map(next=>
         <button type="button" className="text-button" key={next}
          disabled={change.isPending}
          onClick={()=>change.mutate({id:f.id,state:next})}>
          {next==='ACKNOWLEDGED'?'认领':next==='DONE'?'标为已处理':'重开'}</button>)}
      </div></td>
     </tr>)}
    </tbody></table>
   </div>}
  {message&&<p className="panel-caption" role="status">{message}</p>}
  {error&&<p className="setup-error" role="alert">{error}</p>}
  <p className="panel-caption">可选定时扫描默认关闭，所有催办仅在本地形成审计记录，
   不自动联系供应商，不编造确认发运信息，也不重复 V43 已发货 ASN ETA 告警。</p>
 </section>
}
