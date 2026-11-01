import {useState} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {createPeriodProfitReport,exportPeriodProfitUrl,
 getPeriodProfitAudit,getPeriodProfitJobs,getPeriodProfitReport,
 progressPeriodProfitReport,resumePeriodProfitReport} from './lib/period-profit'

function dateString(date:Date){return date.toISOString().slice(0,10)}
const defaults=()=>{
 const tomorrow=new Date()
 tomorrow.setUTCDate(tomorrow.getUTCDate()+1)
 const start=new Date(tomorrow)
 start.setUTCDate(start.getUTCDate()-30)
 return {after:dateString(start),before:dateString(tomorrow)}
}
const states:Record<string,string>={
 READY:'等待处理下一批订单',RETRY:'本批来源暂不可用，准备有限次数重试',
 PAUSED:'失败次数达到上限，需先检查来源再手动恢复',
 SUCCEEDED:'候选订单全部冻结完成（不等于来源数据完整）'
}
export default function PeriodProfitReportPanel({tenantId,marketplaceId}:{
 tenantId:string;marketplaceId:string
}){
 const [dates]=useState(defaults)
 const [after,setAfter]=useState(dates.after)
 const [before,setBefore]=useState(dates.before)
 const [active,setActive]=useState('')
 const [offset,setOffset]=useState(0)
 const [filter,setFilter]=useState('')
 const [showAudit,setShowAudit]=useState(false)
 const cache=useQueryClient()
 const scope=['finance','period-profit',tenantId,marketplaceId]
 const jobs=useQuery({queryKey:[...scope,'jobs'],
  queryFn:()=>getPeriodProfitJobs(marketplaceId),staleTime:5000,
  refetchInterval:15000,retry:false})
 const selected=active||jobs.data?.[0]?.id||''
 const report=useQuery({queryKey:[...scope,selected,offset],
  queryFn:()=>getPeriodProfitReport(marketplaceId,selected,offset),
  enabled:!!selected,staleTime:5000,refetchInterval:15000,retry:false})
 const history=useQuery({queryKey:[...scope,selected,'audit'],
  queryFn:()=>getPeriodProfitAudit(marketplaceId,selected),
  enabled:!!selected&&showAudit,retry:false})
 const refresh=()=>{
  void cache.invalidateQueries({queryKey:scope})
 }
 const create=useMutation({mutationFn:()=>{
  if(after>=before)throw new Error('结束日期必须晚于开始日期')
  // New user action -> new request key. Retry before successful response is
  // handled by the server idempotency key contract.
  return createPeriodProfitReport(marketplaceId,crypto.randomUUID(),after,before)
 },onSuccess:result=>{setActive(result.id);setOffset(0);refresh()}})
 const next=useMutation({mutationFn:()=>progressPeriodProfitReport(marketplaceId,selected),
  onSuccess:refresh})
 const resume=useMutation({mutationFn:()=>resumePeriodProfitReport(marketplaceId,selected),
  onSuccess:refresh})
 const current=report.data
 const j=current?.job
 const busy=create.isPending||next.isPending||resume.isPending
 const summary=current?.skus.filter(s=>
  s.sku.toLowerCase().includes(filter.trim().toLowerCase()))??[]
 return <section className="panel full-panel" aria-label="V64 全期来源贡献报告">
  <div className="panel-heading"><div><h2>整期店铺 / SKU 来源贡献报表</h2>
   <p>由 Owner 显式创建 PostgreSQL 持久任务，创建时冻结候选订单集合；每批从审核通过的 LIVE Finances
    与历史成本重新计算，不把退款未验证或缺失费用自动填零。</p>
  </div><button className="refresh-button" type="button"
   onClick={refresh} disabled={busy}>刷新任务</button></div>
  <div className="ops-toolbar">
   <label className="shop-filter">UTC 开始日期
    <input type="date" value={after} onChange={e=>setAfter(e.target.value)}/>
   </label>
   <label className="shop-filter">UTC 结束日期（不含）
    <input type="date" value={before} onChange={e=>setBefore(e.target.value)}/>
   </label>
   <button type="button" className="primary-button"
    onClick={()=>create.mutate()} disabled={busy||!after||!before||after>=before}>
    创建整期报告任务</button>
  </div>
  <p className="setup-hint">单任务最多 92 天和 5,000 笔 LIVE 订单；每批 10 单。
   后台自动调度默认关闭，可在服务端开启；不开启时使用“处理下一批”。
   处理中重启可继续，数据源异常五次后暂停，需人工检查并恢复。</p>
  {jobs.isError&&<p role="alert" className="setup-error">{String(jobs.error)}</p>}
  {(jobs.data?.length??0)>0&&
   <label className="shop-filter">选择历史报告
    <select value={selected} onChange={e=>{setActive(e.target.value);setOffset(0);
     setShowAudit(false);setFilter('')}}>
     {jobs.data?.map(x=><option key={x.id} value={x.id}>
      {x.after} 至 {x.before} · {x.currency} · {x.state} · {x.processedOrders}/{x.totalOrders}
     </option>)}
    </select>
   </label>}
  {(create.error||next.error||resume.error)&&<p role="alert" className="setup-error">
   {String(create.error??next.error??resume.error)}</p>}
  {selected&&report.isPending&&<p>载入已冻结的报告记录…</p>}
  {selected&&report.isError&&<p role="alert" className="setup-error">
   {String(report.error)}</p>}
  {j&&current&&<>
   <p role="status"><strong>{states[j.state]??j.state}</strong>
    {' · '}{j.processedOrders}/{j.totalOrders} 单 · 已纳入贡献 {j.eligibleOrders} 单
    {' · '}缺少可信来源 {j.blockedOrders} 单 · {j.currency}</p>
   <div className="sandbox-actions">
    {['READY','RETRY'].includes(j.state)&&<button type="button"
     className="primary-button" disabled={busy} onClick={()=>next.mutate()}>
     手动处理下一批 10 单</button>}
    {j.state==='PAUSED'&&<button type="button" className="text-button"
     disabled={busy} onClick={()=>resume.mutate()}>检查来源后恢复任务</button>}
    {j.state==='SUCCEEDED'&&<a className="text-button"
     download="merchivra-period-contribution-snapshot.csv"
     href={exportPeriodProfitUrl(marketplaceId,j.id)}>导出完整已冻结 CSV</a>}
   </div>
   {j.failureCode&&<p role="alert" className="setup-hint">最近处理错误：
    {j.failureCode}。不会将未完成批次标为成功。</p>}
   <p className="setup-hint">固定候选订单列表：是。所有订单财务来源在同一时刻冻结：否，
    按批处理当时复核并冻结每单提取结果。来源完整度 false、银行到账核实 false、会计过账 false。
    本报表是贡献估算，不是整期净利润或银行结算证明。</p>
   <div className="ops-summary">
    {[
     ['已审核商品销售',current.totals.signedSales],
     ['Amazon 平台有符号费用',current.totals.signedAmazonFees],
     ['FBA 有符号费用',current.totals.signedFbaFees],
     ['历史商品成本',current.totals.provisionalCogs],
     ['仅合格订单贡献合计',current.totals.contribution]
    ].map(([label,value])=><div key={label}><span>{label} · {j.currency}</span>
     <strong>{value}</strong></div>)}
   </div>
   <p className="setup-hint">单独的人工广告参考金额：{j.currency} {j.manualAdsReference}，
    {j.manualAdsCount} 条（MANUAL_UNVERIFIED），不进入贡献利润公式。
    未分配退款、其他调整、税费、汇兑、真实广告账单仍可能未覆盖。</p>
   <h3>本期已冻结 SKU 汇总</h3>
   <label className="shop-filter">当前报告 SKU 筛选
    <input value={filter} onChange={e=>setFilter(e.target.value)}
     placeholder="筛选已载入的报告 SKU 汇总"/>
   </label>
   {!summary.length?<p className="empty-state">暂无满足完整来源审核的 SKU 金额。</p>:
    <div className="table-scroll" role="region" tabIndex={0}
     aria-label="全期已冻结 SKU 贡献">
     <table><thead><tr>{['SKU','订单数','件数','销售','Amazon 费用','FBA 费用',
      '历史成本','贡献估算'].map(t=><th key={t}>{t}</th>)}</tr></thead>
      <tbody>{summary.map(x=><tr key={x.sku}><td>{x.sku}</td>
       <td>{x.orderCount}</td><td>{x.units}</td><td>{x.sales}</td>
       <td>{x.amazonFees}</td><td>{x.fbaFees}</td><td>{x.cogs}</td>
       <td><strong>{x.contribution}</strong></td></tr>)}</tbody></table>
    </div>}
   <h3>候选订单覆盖与排除原因</h3>
   <div className="table-scroll" role="region" tabIndex={0}
    aria-label="完整候选订单进度分页">
    <table><thead><tr><th>候选位置</th><th>来源订单 UUID</th><th>状态</th>
     <th>数据条件</th><th>原币贡献</th><th>提取时间</th></tr></thead>
     <tbody>{current.orders.map(o=><tr key={o.orderId}>
      <td>{o.position+1}</td><td>{o.orderId}</td><td>{o.state}</td>
      <td>{o.readiness??'等待本批处理'}</td>
      <td>{o.contribution??'来源不完整，不显示数值'}</td>
      <td>{o.extractedAt??'尚未提取'}</td></tr>)}</tbody></table>
   </div>
   <div className="sandbox-actions">
    <button type="button" className="text-button" disabled={offset===0}
     onClick={()=>setOffset(x=>Math.max(0,x-100))}>上一页</button>
    <button type="button" className="text-button"
     disabled={!current.hasMore} onClick={()=>setOffset(x=>x+100)}>下一页</button>
   </div>
   <button type="button" className="text-button"
    onClick={()=>setShowAudit(s=>!s)}>{showAudit?'收起':'查看'}报表批次审计</button>
   {showAudit&&(history.isPending?<p>读取审计中…</p>:
    history.isError?<p role="alert" className="setup-error">{String(history.error)}</p>:
    <div className="table-scroll" role="region" tabIndex={0} aria-label="报表批次审计">
     <table><thead><tr><th>事件</th><th>处理单数</th><th>错误码</th><th>时间</th></tr></thead>
      <tbody>{history.data?.map((h,i)=><tr key={i}><td>{h.action}</td>
       <td>{h.processedOrders}</td><td>{h.failureCode??'—'}</td>
       <td>{h.occurredAt}</td></tr>)}</tbody></table>
    </div>)}
   <p className="panel-caption">报告生成后不追溯自动改写原有金额；后续费用分类或退货审核变化，
    应创建新请求键、生成新的报表版本并对比。导出包含逐订单排除情况，缺失金额始终留空。</p>
  </>}
 </section>
}
