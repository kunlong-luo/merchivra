import {useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {compareSettlementReport} from './lib/settlement-comparison'
import SettlementReportScanPanel from './SettlementReportScanPanel'
import SettlementSourceReviewPanel from './SettlementSourceReviewPanel'
import {listSettlementReports,listSettlementReportLines,reviewSettlementReport} from './lib/settlement-reports'

export default function SettlementReportsPanel({tenantId,marketplaceId,connected}:{tenantId:string;marketplaceId:string;connected:boolean}){
 const [selected,setSelected]=useState('')
 const reports=useQuery({queryKey:['finance','reports',tenantId,marketplaceId],
  queryFn:()=>listSettlementReports(marketplaceId),retry:false,staleTime:30000})
 const current=reports.data?.find(x=>x.id===selected)
 const review=useQuery({queryKey:['finance','reports',tenantId,marketplaceId,selected,'review'],
  queryFn:()=>reviewSettlementReport(marketplaceId,selected),
  enabled:!!selected,retry:false,staleTime:30000})
 const compare=useQuery({queryKey:['finance','reports',tenantId,marketplaceId,selected,'finance-comparison'],
  queryFn:()=>compareSettlementReport(marketplaceId,selected),
  enabled:!!selected,retry:false,staleTime:30000})
 const lines=useQuery({queryKey:['finance','reports',tenantId,marketplaceId,selected,'lines'],
  queryFn:()=>listSettlementReportLines(marketplaceId,selected),
  enabled:!!selected,retry:false,staleTime:30000})
 return <section className="panel full-panel" aria-label="Amazon 结算报告来源核对">
  <div className="panel-heading"><div>
   <h2>Amazon 结算报告 · 来源明细</h2>
   <p>已导入的 Reports 文档只读明细，与 Finances 交易独立。尚不自动记账、不代表已核实银行到账。</p>
   </div><button type="button" className="refresh-button" onClick={()=>void reports.refetch()} disabled={reports.isFetching}>
    {reports.isFetching?'读取中…':'刷新报告'}</button></div>
  {connected&&<SettlementReportScanPanel key={marketplaceId} tenantId={tenantId} marketplaceId={marketplaceId}/>}
  {reports.isPending?<p className="empty-state">正在读取报告来源…</p>:
   reports.isError?<p className="setup-error" role="alert">
    {reports.error instanceof Error?reports.error.message:'报告读取失败'}</p>:
   !reports.data?.length?<p className="empty-state">
    当前站点尚无已验证来源且成功导入的结算报告。本地开发环境默认不调用 Amazon Reports，不显示虚构的已结算金额。
   </p>:
   <label className="shop-filter" htmlFor="reports-selected"><span>选择结算报告</span>
    <select id="reports-selected" value={selected} onChange={e=>setSelected(e.target.value)}>
     <option value="">选择报告</option>
     {reports.data.map(r=><option key={r.id} value={r.id}>
      {r.settlementReference} · {r.statementCurrency} · {r.lineCount} 条来源明细
     </option>)}
    </select></label>}
  {current&&<div className="core-tools">
   <p><strong>Settlement ID：</strong>{current.settlementReference}</p>
   <p className="setup-hint">原始报告编号：{current.reportId}；SHA-256：<code>{current.documentSha256}</code></p>
   <p>Amazon 报告声明净额：{current.declaredNet===null?'未提供':current.statementCurrency+' '+current.declaredNet}
    （来源声明，不代表银行到账）。</p>
   {review.isPending?<p>正在核对导入数据…</p>:
    review.isError?<p className="setup-error" role="alert">{String(review.error)}</p>:
    review.data&&<p>明细 {review.data.rowCount} 行；原币行合计：
     {review.data.currency} {review.data.signedLineTotal}；含订单号的不同订单：
     {review.data.distinctOrderReferences} 个。
     <strong> 尚未与 Finances 对账，也未验证银行到账。</strong></p>}
   {compare.isPending?<p>正在进行同 Settlement ID 的 Finances 来源核对…</p>:
    compare.isError?<p className="setup-error" role="alert">{String(compare.error)}</p>:
    compare.data&&<div className="core-tools">
     <strong>Reports 与 Finances 来源差异</strong>
     <p>报告行有符号合计：{compare.data.reportLinesNet??'未知'} {compare.data.currency}；
      同站点且来源 Settlement ID 精确匹配的 Finances 金额：
      {compare.data.financesNet??'暂无匹配记录'} {compare.data.currency}。</p>
     <p>符合标识和币种的 Finances 交易 {compare.data.financeTransactionCount} 笔；
       不同币种的来源交易 {compare.data.otherCurrencyTransactionCount} 笔。
       状态：<strong>{({
        REPORT_DETAIL_INCOMPLETE:'报表明细不完整',
        REPORT_INTERNAL_DIFFERENCE:'报告行合计与报表声明金额不同',
        CURRENCY_CONFLICT:'存在跨币种来源，禁止合并',
        NO_FINANCES_SETTLEMENT_REFERENCE:'没有精确 Settlement ID 来源证据',
        SOURCE_TOTAL_DIFFERENCE:'两种来源有符号金额不一致',
        SOURCE_AMOUNTS_AGREE_UNVERIFIED:'来源有符号金额一致，但仍需人工验真'
       } as Record<string,string>)[compare.data.state]??compare.data.state}</strong></p>
     <p className="setup-hint">仅比较来源，不自动关联交易、不产生利润或会计凭证，也不代表银行已到账。</p>
    </div>}
   {compare.data&&<SettlementSourceReviewPanel key={selected}
     tenantId={tenantId} marketplaceId={marketplaceId} reportId={selected}
     sourceState={compare.data.state}/>}
   {lines.isPending?<p>读取来源明细…</p>:
    lines.isError?<p className="setup-error" role="alert">{String(lines.error)}</p>:
    <div className="table-scroll" role="region" tabIndex={0} aria-label="Amazon 报告原始分类明细">
     <table><thead><tr>
      {['行号','交易类型','费用类型','原始描述','订单参考','原币有符号金额'].map(x=><th scope="col" key={x}>{x}</th>)}
     </tr></thead><tbody>{(lines.data??[]).map(r=><tr key={r.lineNumber}>
      <td>{r.lineNumber}</td><td>{r.transactionType??'未提供'}</td>
      <td>{r.amountType??'未提供'}</td><td>{r.amountDescription??'未提供'}</td>
      <td>{r.orderId??'无订单号'}</td><td>{r.currency} {r.signedAmount}</td>
     </tr>)}</tbody></table></div>}
   <p className="panel-caption">最多显示前 100 行；CSV/TSV 的其他字段未被持久化。该报表不得直接与同笔 Finances 交易金额相加。</p>
  </div>}
 </section>
}
