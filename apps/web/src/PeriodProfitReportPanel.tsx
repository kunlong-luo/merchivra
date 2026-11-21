import {useEffect,useState} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import SourceEvidenceDrawer from './components/SourceEvidenceDrawer'
import PeriodProfitWaterfallPanelV188 from './PeriodProfitWaterfallPanelV188'
import {compareFrozenReports,summarizeCandidatePage} from './lib/profit-report-insights'
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
 const [inspectedOrderId,setInspectedOrderId]=useState('')
 const [compareTarget,setCompareTarget]=useState('')
 const cache=useQueryClient()
 const scope=['finance','period-profit',tenantId,marketplaceId]
 const jobs=useQuery({queryKey:[...scope,'jobs'],
  queryFn:()=>getPeriodProfitJobs(marketplaceId),staleTime:5000,
  refetchInterval:15000,retry:false})
 const selected=active||jobs.data?.[0]?.id||''
 // Do not carry a report ID or comparison across authenticated Marketplace scopes.
 useEffect(()=>{setActive('');setCompareTarget('');setOffset(0);
  setInspectedOrderId('');setFilter('');setShowAudit(false)},[tenantId,marketplaceId])
 const comparisonId=jobs.data?.some(j=>j.id===compareTarget)&&compareTarget!==selected?
  compareTarget:''
 const report=useQuery({queryKey:[...scope,selected,offset],
  queryFn:()=>getPeriodProfitReport(marketplaceId,selected,offset),
  enabled:!!selected,staleTime:5000,refetchInterval:15000,retry:false})
 const reportVersion=useQuery({queryKey:[...scope,'version-compare',comparisonId],
  queryFn:()=>getPeriodProfitReport(marketplaceId,comparisonId,0),
  enabled:!!selected&&!!comparisonId,staleTime:30_000,retry:false})
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
 },onSuccess:result=>{setActive(result.id);setOffset(0);setCompareTarget('');
  setInspectedOrderId('');refresh()}})
 const next=useMutation({mutationFn:()=>progressPeriodProfitReport(marketplaceId,selected),
  onSuccess:refresh})
 const resume=useMutation({mutationFn:()=>resumePeriodProfitReport(marketplaceId,selected),
  onSuccess:refresh})
 const current=report.data
 const j=current?.job
 const inspected=current?.orders.find(o=>o.orderId===inspectedOrderId)
 const pageFacts=summarizeCandidatePage(current?.orders??[])
 const versionComparison=current&&reportVersion.data?
  compareFrozenReports(current,reportVersion.data):null
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
     setShowAudit(false);setFilter('');setInspectedOrderId('');setCompareTarget('')}}>
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
   <div className="mv3-profit-audit" aria-label="V64 冻结报表的来源证明">
    <div className="mv3-profit-audit-lead">
     <span className="mv3-profit-kicker">V64 · ORIGINAL-CURRENCY CONTRIBUTION</span>
     <h3>本期来源贡献（非最终净利润）</h3>
     <strong className="mv3-profit-contribution">{j.state==='SUCCEEDED'?
       j.currency+' '+current.totals.contribution:'候选订单尚未全部完成'}</strong>
     <p>只有候选订单冻结处理完成时才展示本期已核对贡献；
      缺失费用不自动记零，财务来源仍未完整认证、银行到账未核验。</p>
    </div>
    <div className="mv3-profit-audit-stats">
     <div><span>候选订单</span><strong>{j.totalOrders}</strong></div>
     <div><span>已纳入贡献</span><strong>{j.eligibleOrders}</strong></div>
     <div><span>缺可信来源阻断</span><strong>{j.blockedOrders}</strong></div>
     <div><span>处理批次状态</span><strong className="mv3-profit-status">{states[j.state]??j.state}</strong></div>
    </div>
   </div>
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
   <PeriodProfitWaterfallPanelV188 tenantId={tenantId}
    marketplaceId={marketplaceId} reportId={j.id}/>
   <p className="setup-hint">单独的人工广告参考金额：{j.currency} {j.manualAdsReference}，
    {j.manualAdsCount} 条（MANUAL_UNVERIFIED），不进入贡献利润公式。
    未分配退款、其他调整、税费、汇兑、真实广告账单仍可能未覆盖。</p>
   <section className="v180-version-panel" aria-label="V64 报表版本原币对照">
    <div className="v180-version-heading"><div>
     <span className="v180-section-kicker">VERSION-TO-VERSION / AUDIT ONLY</span>
     <h3>报表版本差异 · 原币快照</h3>
     <p>读取另一份已存在的 V64 持久任务；仅展示算术差额，不追溯重写原报告。</p>
    </div></div>
    <label className="shop-filter" htmlFor="v180-report-compare">
     对照历史冻结版本
     <select id="v180-report-compare" value={comparisonId}
      onChange={e=>setCompareTarget(e.target.value)}>
      <option value="">不比较（选择另一版）</option>
      {jobs.data?.filter(x=>x.id!==selected).map(x=>
       <option key={x.id} value={x.id}>
        {x.after} 至 {x.before} · {x.currency} · {x.state} · {x.id.slice(0,8)}
       </option>)}
     </select>
    </label>
    {comparisonId&&reportVersion.isPending&&<p role="status" className="setup-hint">
     正在读取对照版本的已冻结合计…</p>}
    {comparisonId&&reportVersion.isError&&<p role="alert" className="setup-error">
     对照版本读取失败：{String(reportVersion.error)}。不计算差额。</p>}
    {versionComparison&&!versionComparison.comparable&&
     <p role="status" className="v180-compare-hold">
      <strong>差额比较已阻断：</strong>{versionComparison.reason}。
      请选择同站点、同原币、同 UTC 期间且完整处理的版本。</p>}
    {versionComparison?.comparable&&<>
     <p className="v180-compare-hold">
      <strong>注意：</strong>两版候选/合格/阻断数量一致，
      但尚未独立证明候选订单 ID 集合、金额来源和银行记录完全相同。
      以下仅是历史冻结快照之间的原币算术差额，不是利润改善或已核实净利润。</p>
     <div className="table-scroll" tabIndex={0} role="region"
       aria-label="同原币同期间报表快照差额">
      <table><thead><tr><th scope="col">费用与收入项目</th>
       <th scope="col">当前版本 · {j.currency}</th>
       <th scope="col">对照版本 · {j.currency}</th>
       <th scope="col">算术差额 · {j.currency}</th></tr></thead><tbody>
       {versionComparison.rows.map(x=><tr key={x.label}>
        <td>{x.label}</td><td>{x.current}</td><td>{x.baseline}</td>
        <td><strong>{x.delta}</strong></td></tr>)}
       </tbody></table></div>
    </>}
    {!comparisonId&&<p className="setup-hint">
     {jobs.data?.some(x=>x.id!==selected)?
       '选择另一版后验证原币、期间、冻结进度和订单覆盖结构。':
       '目前没有其他可供对照的已记录版本；不会制造对比金额。'}</p>}
   </section>
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
   <section className="v180-blockers" aria-label="当前候选页阻断原因分布">
    <div className="v180-version-heading"><div>
     <span className="v180-section-kicker">READINESS / CURRENT PAGE ONLY</span>
     <h3>候选订单阻断原因 · 当前页</h3>
     <p>仅统计当前已加载的 {pageFacts.loaded} 条候选记录，
      不代表整期全部 {j.totalOrders} 单；来源缺失不是费用为零。</p>
    </div></div>
    <div className="v180-page-facts">
     <div><span>当前页阻断</span><strong>{pageFacts.blocked}</strong></div>
     <div><span>当前页待处理</span><strong>{pageFacts.pending}</strong></div>
     <div><span>当前页已纳入</span><strong>{pageFacts.eligible}</strong></div>
    </div>
    {pageFacts.reasons.length>0?
     <div className="table-scroll" role="region" tabIndex={0}
       aria-label="当前分页具体来源阻断原因">
      <table><thead><tr><th scope="col">原始阻断代码 / 来源情况</th>
       <th scope="col">当前页订单数</th></tr></thead><tbody>
       {pageFacts.reasons.map(row=><tr key={row.code}>
        <td><code>{row.code}</code></td><td>{row.count}</td>
       </tr>)}</tbody></table></div>:
     <p className="setup-hint">当前页未出现已标记 BLOCKED 的候选记录；
      不代表其他分页或全部报表没有阻断。</p>}
   </section>
   <h3>候选订单覆盖与排除原因</h3>
   <p className="setup-hint">可点“核对来源”查看当前页该订单的逐项金额、阻断状态、提取时间和哈希。
    只查看当前已加载的候选订单，不再请求其他租户或未加载记录。</p>
   <div className="table-scroll" role="region" tabIndex={0}
    aria-label="完整候选订单进度分页">
    <table><thead><tr><th>候选位置</th><th>来源订单 UUID</th><th>状态</th>
     <th>数据条件</th><th>原币贡献</th><th>提取时间</th><th>核对</th></tr></thead>
     <tbody>{current.orders.map(o=><tr key={o.orderId}>
      <td>{o.position+1}</td><td>{o.orderId}</td><td>{o.state}</td>
      <td>{o.readiness??'等待本批处理'}</td>
      <td>{o.contribution??'来源不完整，不显示数值'}</td>
      <td>{o.extractedAt??'尚未提取'}</td>
      <td><button className="text-button" type="button"
       onClick={()=>setInspectedOrderId(o.orderId)}
       aria-label={'核对候选订单来源 '+o.orderId}>核对来源</button></td></tr>)}</tbody></table>
   </div>
   <div className="sandbox-actions">
    <button type="button" className="text-button" disabled={offset===0}
     onClick={()=>{setInspectedOrderId('');setOffset(x=>Math.max(0,x-100))}}>上一页</button>
    <button type="button" className="text-button"
     disabled={!current.hasMore} onClick={()=>{setInspectedOrderId('');setOffset(x=>x+100)}}>下一页</button>
   </div>
   <SourceEvidenceDrawer open={!!inspected} title="候选订单 · 来源证据"
    description="V64 冻结任务中的当前页提取快照；不等于全成本净利润或实际到账"
    onClose={()=>setInspectedOrderId('')}>
    {inspected&&<>
     <div className="v179-evidence-tags"><span className="v179-source-tag">{inspected.state}</span>
      <span className="v179-source-tag">{inspected.currency}</span>
      <span className="v179-source-tag">候选位置 {inspected.position+1}</span></div>
     <dl className="v179-evidence-facts">
      <div><dt>来源订单 UUID</dt><dd><code>{inspected.orderId}</code></dd></div>
      <div><dt>审核与阻断条件</dt><dd>{inspected.readiness??'未完成来源核对'}</dd></div>
      <div><dt>提取时间</dt><dd>{inspected.extractedAt??'未提取'}</dd></div>
      <div><dt>提取快照 SHA-256</dt><dd><code>{inspected.extractSha256??'未生成'}</code></dd></div>
     </dl>
     <h4 className="v179-evidence-section">本单原币金额 · {inspected.currency}</h4>
     <dl className="v179-evidence-facts">
      {([['已审核销售',inspected.sales],['Amazon 费用',inspected.fees],
       ['FBA 费用',inspected.fba],['历史商品成本',inspected.cogs],
       ['合格订单贡献',inspected.contribution]] as const).map(([label,value])=>
       <div key={label}><dt>{label}</dt><dd>{inspected.state==='ELIGIBLE'&&value!==null?
        inspected.currency+' '+value:'来源不完整 · 不显示金额'}</dd></div>)}
     </dl>
     <p className="v179-evidence-warning">只有 ELIGIBLE 的金额才可查看。缺少可信来源时保持未知；
      即使本单 ELIGIBLE，也不代表广告、头程、税费、汇兑、银行到账与最终会计净利润已认证。</p>
    </>}
   </SourceEvidenceDrawer>
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
