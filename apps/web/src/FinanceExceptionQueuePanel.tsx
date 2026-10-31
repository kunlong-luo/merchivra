import {useMemo,useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {getFinanceExceptions} from './lib/finance-exceptions'

const codes:Record<string,string>={
 SOURCE_BREAKDOWN_ABSENT:'官方财务明细尚未取得',
 SOURCE_AMOUNT_INCOMPLETE:'末级明细金额缺失',
 SOURCE_CURRENCY_MISMATCH:'末级明细包含不同原币',
 SOURCE_TYPE_UNCLASSIFIED:'末级明细尚未审核分类',
 SOURCE_BREAKDOWN_DIFFERS:'末级有符号合计与交易总额不符',
 ORDER_LINK_UNREVIEWED:'订单关联尚未人工确认',
 SETTLEMENT_UNASSIGNED:'来源交易尚未关联参考结算单',
 STATEMENT_NO_LINKED_EVENTS:'人工结算参考单尚无关联交易',
 STATEMENT_CURRENCY_MISMATCH:'结算关联存在混币种',
 STATEMENT_OUTSIDE_STATEMENT_PERIOD:'结算关联的交易在参考期间之外',
 STATEMENT_SIGNED_TOTAL_DIFFERS:'人工参考净额与来源交易合计不同',
 STATEMENT_UNASSIGNED_CANDIDATES:'参考期间存在未分配的来源候选',
 ORDER_COST_INCOMPLETE:'订单历史商品成本覆盖不足',
 ORDER_COST_ORDER_DATE_UNKNOWN:'订单历史创建时间缺失',
 ORDER_COST_ITEMS_NOT_OBSERVED:'订单项尚未同步',
 ORDER_COST_NO_ITEMS:'订单观测项为空'
}
const categories:Record<string,string>={
 SOURCE_TRANSACTION:'财务交易',SETTLEMENT_REFERENCE:'人工结算参考',
 ORDER_COST:'订单历史成本'
}
const severityText:Record<string,string>={
 BLOCKING:'影响金额核对',REVIEW:'需要人工核验',INFO:'待检查提示'
}
export default function FinanceExceptionQueuePanel({tenantId,marketplaceId}:{
 tenantId:string;marketplaceId:string
}){
 const [severity,setSeverity]=useState('ALL')
 const query=useQuery({
  queryKey:['finance','exceptions',tenantId,marketplaceId],
  queryFn:()=>getFinanceExceptions(marketplaceId),
  enabled:!!marketplaceId,staleTime:30_000,refetchInterval:60_000,
  retry:false
 })
 const visible=useMemo(()=>query.data?.findings.filter(x=>
  severity==='ALL'||x.severity===severity)??[],[query.data,severity])
 const blocking=query.data?.findings.filter(x=>x.severity==='BLOCKING').length??0
 const review=query.data?.findings.filter(x=>x.severity==='REVIEW').length??0
 const info=query.data?.findings.filter(x=>x.severity==='INFO').length??0
 return <section className="panel full-panel" aria-label="自动财务核对异常队列">
  <div className="panel-heading"><div>
   <h2>财务异常自动扫描 · 待办队列</h2>
   <p>仅分析已同步到本地的受信来源、人工参考结算单与订单历史成本。
      每分钟自动更新当前页面；不自动确认退款、分配交易或修改账务。</p></div>
   <button type="button" className="refresh-button" onClick={()=>void query.refetch()}>
     立即重新检查</button></div>
  {query.isPending?<p className="empty-state">正在核对财务来源、结算与成本覆盖…</p>:
   query.isError?<p className="setup-error" role="alert">{String(query.error)}</p>:
   query.data&&<>
    <div className="orders-sync-grid">
     <div><span>金额核对阻断</span><strong>{blocking}</strong></div>
     <div><span>需人工确认</span><strong>{review}</strong></div>
     <div><span>来源待检查</span><strong>{info}</strong></div>
     <div><span>发现的问题数</span><strong>{query.data.issueCount}</strong></div>
    </div>
    <p className="setup-hint">本轮检查：最近 {query.data.scannedSourceTransactions} 笔官方来源交易、
     {query.data.scannedSettlements} 条人工参考结算单、
     {query.data.scannedOrders} 笔 LIVE 订单。</p>
    {query.data.partialScan&&<p className="setup-hint" role="status">
     数据量超过本次扫描上限，结果仅代表近期样本，不代表全部历史记录都已核对。
     </p>}
    <label className="shop-filter" htmlFor="finance-exception-filter">
     <span>筛选待办等级</span>
     <select id="finance-exception-filter" value={severity}
      onChange={e=>setSeverity(e.target.value)}>
      <option value="ALL">全部类型</option>
      <option value="BLOCKING">金额核对阻断</option>
      <option value="REVIEW">需要人工确认</option>
      <option value="INFO">待检查提示</option>
     </select>
    </label>
    {!visible.length?<p className="empty-state">
      该筛选下没有发现异常；这不等于 Amazon 财务来源、结算和银行到账已完成核实。
     </p>:
     <div className="table-scroll" tabIndex={0} role="region"
      aria-label="财务异常检测结果"><table><thead><tr>
       {['等级','来源类型','具体问题','业务参考','原币金额','建议下一步'].map(t=>
        <th scope="col" key={t}>{t}</th>)}
      </tr></thead><tbody>{visible.slice(0,100).map((row,i)=><tr
       key={row.subjectType+':'+row.subjectId+':'+row.code+':'+i}>
       <td>{severityText[row.severity]}</td>
       <td>{categories[row.subjectType]}</td>
       <td>{codes[row.code]??row.code}</td>
       <td>{row.sourceReference}</td>
       <td>{row.signedAmount===null?'—':row.currency+' '+row.signedAmount}</td>
       <td>{row.nextAction}</td>
      </tr>)}</tbody></table></div>}
    {visible.length>100&&<p className="setup-hint">
      当前筛选显示前 100 项；请按等级筛选或处理后重新扫描。</p>}
   </>}
  <p className="setup-hint">异常状态是待核对提示，不代表正式会计异常定性。
    对没有实际来源数据的店铺，空队列只表示尚无可检查的来源。</p>
 </section>
}
