import {useState} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import BankManualReviewPanel from './BankManualReviewPanel'
import {compareManualBank,importManualBankCsv,listManualBankBatches,
 listManualBankEntries} from './lib/manual-bank'

const labels:Record<string,string>={
 DUPLICATE_REPORT_SETTLEMENT_ID:'同一站点有重复 Settlement ID 报告，禁止匹配',
 NO_MANUAL_BANK_ENTRY:'尚无同 Settlement ID 的银行人工参考',
 AMBIGUOUS_BANK_ENTRY:'出现多个银行候选条目，禁止自动匹配',
 SOURCE_REVIEW_REQUIRED:'须先完成 Reports 与 Finances 双人来源审核',
 REPORT_NET_MISSING:'Amazon 报告缺少声明净额',
 BANK_AMOUNT_INCOMPLETE:'银行参考金额信息不完整',
 SAME_CURRENCY_RATE_CONFLICT:'同币种却录入了非 1 的汇率',
 SAME_CURRENCY_AMOUNT_DIFFERENCE:'银行贷记+扣留手续费与报告净额不一致',
 MANUAL_AMOUNT_MATCH_UNVERIFIED:'金额相等，但人工参考尚未核实银行到账',
 FX_RATE_REQUIRED:'跨币种缺少明确申报汇率',
 MANUAL_FX_AMOUNT_DIFFERENCE:'按人工申报汇率换算后仍有差异',
 MANUAL_FX_MATCH_UNVERIFIED:'按人工申报汇率金额相等，汇率和到账均未验真'
}
export default function ManualBankPanel({tenantId,marketplaceId,reportId}:{
 tenantId:string;marketplaceId:string;reportId:string
}){
 const qc=useQueryClient()
 const [statementRef,setStatementRef]=useState('')
 const [csv,setCsv]=useState<File|null>(null)
 const [batch,setBatch]=useState('')
 const key=['finance','bank-manual',tenantId,marketplaceId]
 const imports=useQuery({queryKey:[...key,'batches'],
  queryFn:()=>listManualBankBatches(marketplaceId),retry:false,staleTime:15000})
 const entries=useQuery({queryKey:[...key,'batch',batch],
  queryFn:()=>listManualBankEntries(marketplaceId,batch),
  enabled:!!batch,retry:false})
 const comparison=useQuery({queryKey:[...key,'report',reportId],
  queryFn:()=>compareManualBank(marketplaceId,reportId),
  enabled:!!reportId,retry:false,staleTime:15000})
 const upload=useMutation({mutationFn:async()=>{
  if(!csv)throw new Error('请选择 CSV 文件')
  return importManualBankCsv(marketplaceId,statementRef,csv)
 },onSuccess:result=>{
  setBatch(result.id);setCsv(null)
  void qc.invalidateQueries({queryKey:key})
 }})
 return <section className="panel full-panel" aria-label="银行回款人工来源与差异核对">
  <div className="panel-heading"><div><h2>银行回款参考 · 人工来源（未验证）</h2>
   <p>仅导入脱敏的标准化银行流水参考，不接入银行账号、网银或收款凭证。
    银行到账与账本过账状态始终未验证。</p></div></div>
  <p className="setup-hint">CSV 固定列（英文）：<code>
   entry_id,settlement_id,value_date,currency,credit_amount,withheld_fee,fx_rate
  </code>。最多 50 行、64 KiB。withheld_fee 表示从应付回款扣留的手续费；
  fx_rate 表示每 1 单位结算币种折算的银行币种数。不要包含客户、姓名、账号、IBAN 或银行登录资料。</p>
  <div className="core-tools">
   <label htmlFor="manual-bank-source-id">脱敏来源批次编号（不可包含银行账号）</label>
   <input id="manual-bank-source-id" value={statementRef} maxLength={160}
    onChange={e=>setStatementRef(e.target.value)}
    placeholder="BANKREF-2026-11-001"/>
   <label htmlFor="manual-bank-file">标准化 CSV 文件</label>
   <input id="manual-bank-file" type="file" accept=".csv,text/csv,text/plain"
    onChange={e=>setCsv(e.target.files?.[0]??null)}/>
   <div className="sandbox-actions">
    <button className="primary-button" type="button"
     disabled={!csv||!statementRef||upload.isPending}
     onClick={()=>upload.mutate()}>
     {upload.isPending?'导入中…':'导入人工参考（非银行确认）'}
    </button>
    <button className="refresh-button" type="button"
     disabled={imports.isFetching}
     onClick={()=>void imports.refetch()}>刷新来源批次</button>
   </div>
   {upload.error&&<p className="setup-error" role="alert">{String(upload.error)}</p>}
   {upload.isSuccess&&<p role="status">参考数据已暂存，来源仍是 MANUAL_UNVERIFIED。可以选择批次查看条目。</p>}
  </div>
  {imports.isPending?<p>读取人工参考批次…</p>:
   imports.isError?<p className="setup-error" role="alert">{String(imports.error)}</p>:
   <label className="shop-filter" htmlFor="manual-bank-batch">查看已导入批次
    <select id="manual-bank-batch" value={batch} onChange={e=>setBatch(e.target.value)}>
     <option value="">选择批次</option>
     {imports.data?.map(b=><option key={b.id} value={b.id}>
      {b.statementRef} · {b.lineCount} 行 · 人工未验证
     </option>)}
    </select></label>}
  {batch&&(entries.isPending?<p>读取来源条目…</p>:
   entries.isError?<p className="setup-error" role="alert">{String(entries.error)}</p>:
   <div className="table-scroll" role="region" tabIndex={0} aria-label="银行来源条目">
    <table><thead><tr><th>条目</th><th>Settlement ID</th><th>日期</th>
     <th>银行贷记</th><th>扣留手续费</th><th>申报汇率</th></tr></thead>
     <tbody>{(entries.data??[]).map(x=><tr key={x.id}>
      <td>{x.entryRef}</td><td>{x.settlementRef}</td><td>{x.valueDate}</td>
      <td>{x.bankCurrency} {x.creditAmount}</td>
      <td>{x.withheldFee}</td><td>{x.declaredFxRate??'无'}</td>
     </tr>)}</tbody></table></div>)}
  {reportId&&(comparison.isPending?<p>核对当前结算报告的银行参考…</p>:
   comparison.isError?<p className="setup-error" role="alert">{String(comparison.error)}</p>:
   comparison.data&&<div className="core-tools">
    <h3>所选 Amazon 报告与银行人工来源对比</h3>
    <p><strong>{labels[comparison.data.state]??comparison.data.state}</strong></p>
    <p>结算参考：{comparison.data.settlementRef}；
     Amazon 声明净额：{comparison.data.declaredNet??'未知'} {comparison.data.settlementCurrency}；
     匹配的银行条目：{comparison.data.candidateCount} 个。</p>
    {comparison.data.candidateCount===1&&<p>银行贷记：
     {comparison.data.observedCredit} {comparison.data.bankCurrency}；
     扣留手续费：{comparison.data.withheldFee}；
     声明汇率：{comparison.data.declaredFxRate??'未提供'}；
     折算应付总额：{comparison.data.expectedGross??'未知'}；
     银行币种差额：{comparison.data.sourceDifference??'未知'}。
    </p>}
    {comparison.data.candidateCount===1&&
     <BankManualReviewPanel key={reportId} tenantId={tenantId}
      marketplaceId={marketplaceId} reportId={reportId}
      comparisonState={comparison.data.state}/>}
    <p className="setup-hint">此视图不连接银行、不存储账户号、不验证文件真实性；
      即使金额为零差异或完成来源双人审核，也不能确认资金已到账。</p>
   </div>)}
 </section>
}
