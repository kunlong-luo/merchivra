import {useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {fetchReceiptCostCandidateV253} from './lib/receipt-cost-candidate-v253'

const labels:Record<string,string>={
 PURCHASE_PO_REFERENCE:'采购原价（PO 参考）',
 INBOUND_FREIGHT:'头程运费',IMPORT_DUTY:'进口关税'
}
const reasons:Record<string,string>={
 RECEIPT_CLAIM_POPULATION_MISSING_OR_UNBOUNDED:'缺少完整销售批次认领，或认领数量超出查询上限。',
 ORIGINAL_CLAIM_OR_RANGE_INCOMPLETE_OR_STALE:'原销售区间缺失、撤销或失效。',
 FREIGHT_OR_DUTY_EVIDENCE_UNKNOWN_NOT_ZERO:'头程或关税费用证据缺失，不代表费用为零。',
 CROSS_MARKETPLACE_RECEIPT_SCOPE_UNRESOLVED:'跨站点费用或销售归属尚未解决。',
 RECEIPT_COST_SOURCE_NOT_INTACT:'费用来源审核、币种或重复归属存在问题。',
 PURCHASE_OR_REJECTED_UNIT_COST_POLICY_UNRESOLVED:'采购价或拒收商品成本口径未明确。',
 PAYMENT_FX_OR_OTHER_CAPITALIZATION_POLICY_UNRESOLVED:'支付／汇兑费用的会计归属未批准。',
 EXTERNAL_COST_AND_G1_UNAPPROVED:'外部真实成本与 G1 会计政策未核证。',
 EXTERNAL_COST_COVERAGE_UNPROVEN:'外部费用覆盖完整性尚未证明。',
 QC_DENOMINATOR_AND_G1_UNAPPROVED:'验收数量分母及 G1 会计口径未批准。'
}
/** Mounted only for an explicitly inspected receipt; parent keys exact scope. */
export default function ReceiptCostCandidatePanelV253({tenantId,marketplaceId,receiptId}:{
 tenantId:string;marketplaceId:string;receiptId:string
}){
 const [revision,setRevision]=useState(0)
 const q=useQuery({
  queryKey:['finance',tenantId,marketplaceId,'v253-receipt-cost-candidate',receiptId,revision],
  queryFn:({signal})=>fetchReceiptCostCandidateV253(marketplaceId,receiptId,signal),
  enabled:revision>0,retry:false,staleTime:0,refetchOnWindowFocus:false,
  refetchOnReconnect:false,gcTime:0
 })
 const d=q.data
 return <section className="core-tools" aria-label="采购批次成本组合候选">
  <h3>批次成本组合与销售归属</h3>
  <p className="setup-hint">按原币展示采购参考价、已归属头程及关税。只读候选，不是全部经营成本或实际 COGS。</p>
  <button type="button" className="refresh-button" disabled={q.isFetching}
   onClick={()=>setRevision(v=>v+1)}>{revision===0?'查询批次成本组合':'重新计算批次候选成本'}</button>
  {revision>0&&(q.isPending||q.isFetching?<p role="status">正在核对原销售区间与费用归属…</p>:
   q.isError?<p role="alert" className="setup-error">无法读取或校验当前成本：{q.error.message}。不沿用旧值。</p>:
   d&&<>
    {d.state==='LOCAL_COST_COMPOSITION_HOLD'?
     <p role="status" className="v179-evidence-warning">成本未知 · 未生成候选金额，不等于零。</p>:
     <>
      <dl className="v179-evidence-facts">
       <div><dt>批次成本候选</dt><dd>{d.originalCurrency} {d.receiptCandidateCost}</dd></div>
       <div><dt>已归属销售候选成本</dt><dd>{d.originalCurrency} {d.allocatedCandidateCost}</dd></div>
       <div><dt>未分配候选成本</dt><dd>{d.originalCurrency} {d.unallocatedCandidateCost}</dd></div>
       <div><dt>数量分母／已分配</dt><dd>{d.components[0].basisQuantity} / {d.components[0].allocatedQuantity}</dd></div>
      </dl>
      <div className="table-scroll" role="region" tabIndex={0} aria-label="批次成本原币组成">
       <table><thead><tr>{['来源','批次候选额','已归属销售','未分配'].map(x=><th key={x} scope="col">{x}</th>)}</tr></thead>
        <tbody>{d.components.map(c=><tr key={c.sourceId??'po'}>
         <td>{labels[c.category]}{c.sourceId&&<div className="secondary-cell"><code title={c.sourceId}>{c.sourceId.slice(0,8)}…</code></div>}</td>
         <td>{c.originalCurrency} {c.receiptCandidateAmount}</td>
         <td>{c.originalCurrency} {c.allocatedCandidateAmount}</td>
         <td>{c.originalCurrency} {c.unallocatedCandidateAmount}</td>
        </tr>)}</tbody></table>
      </div>
      <details><summary>销售认领候选成本（{d.saleShares.length} 条）</summary>
       <div className="table-scroll" role="region" tabIndex={0} aria-label="销售认领成本候选清单">
        <table><thead><tr><th scope="col">认领 UUID</th><th scope="col">原币候选金额</th></tr></thead>
         <tbody>{d.saleShares.map(s=><tr key={s.claimId}><td><code>{s.claimId}</code></td>
          <td>{d.originalCurrency} {s.candidateCost}</td></tr>)}</tbody></table>
       </div></details>
     </>}
    <ul>{d.blockerCodes.map((code,i)=><li key={code+'-'+i}>{reasons[code]??code}</li>)}</ul>
   </>)}
  <p className="v179-evidence-warning"><strong>实际 COGS 和真实净利润未核证，禁止过账。</strong></p>
 </section>
}
