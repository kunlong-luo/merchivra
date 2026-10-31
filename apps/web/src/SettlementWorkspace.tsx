import {useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {assignSettlementEvent,createStatement,listStatements,settlementAudit,
 settlementCandidates,settlementPreview,unassignSettlementEvent} from './lib/settlements'

const labels:Record<string,string>={
 NO_LINKED_EVENTS:'尚无已分配的来源交易',
 CURRENCY_MISMATCH:'存在原币不一致，不能相加',
 OUTSIDE_STATEMENT_PERIOD:'有来源交易落在声明的结算期间之外',
 SIGNED_TOTAL_DIFFERS:'已关联交易合计与人工参考金额不同',
 SIGNED_TOTAL_MATCHES_UNVERIFIED:'金额对平，但未验证结算或银行到账'
}
function time(v:string){return new Date(v).toLocaleString('zh-CN',{hour12:false})}
export default function SettlementWorkspace({tenantId,marketplaceId,currency}:{
 tenantId:string;marketplaceId:string;currency:string
}){
 const client=useQueryClient()
 const [selected,setSelected]=useState('')
 const [reference,setReference]=useState('')
 const [start,setStart]=useState(''),[end,setEnd]=useState('')
 const [amount,setAmount]=useState('')
 const [notice,setNotice]=useState(''),[failure,setFailure]=useState('')
 const [showAudit,setShowAudit]=useState(false)
 const key=['finance','settlements',tenantId,marketplaceId]
 const statements=useQuery({queryKey:key,queryFn:()=>listStatements(marketplaceId),
   retry:false})
 const current=statements.data?.find(s=>s.id===selected)
 const details=useQuery({queryKey:[...key,selected,'preview'],
  queryFn:()=>settlementPreview(marketplaceId,selected),
  enabled:!!selected,retry:false})
 const candidates=useQuery({queryKey:[...key,selected,'candidates'],
  queryFn:()=>settlementCandidates(marketplaceId,selected),
  enabled:!!selected,retry:false})
 const audit=useQuery({queryKey:[...key,selected,'audit'],
  queryFn:()=>settlementAudit(marketplaceId,selected),
  enabled:!!selected&&showAudit,retry:false})
 function refresh(){
  void client.invalidateQueries({queryKey:key})
 }
 const create=useMutation({
  mutationFn:()=>createStatement(marketplaceId,{
   reference,periodStart:new Date(start).toISOString(),
   periodEnd:new Date(end).toISOString(),reportedNet:amount,currency}),
  onSuccess:s=>{setReference('');setAmount('');setStart('');setEnd('');
   setSelected(s.id);setNotice('已保存人工参考结算单，尚未证明 Amazon 已结算或银行到账')
   setFailure('');refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 const link=useMutation({mutationFn:(id:string)=>
  assignSettlementEvent(marketplaceId,selected,id),
  onSuccess:()=>{setNotice('已关联原始 Finances 交易（未确认真实结算）');
   setFailure('');refresh()},onError:(e:Error)=>setFailure(e.message)})
 const unlink=useMutation({mutationFn:(id:string)=>
  unassignSettlementEvent(marketplaceId,selected,id),
  onSuccess:()=>{setNotice('已撤销分配，记录审计');setFailure('');refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 const canCreate=reference.length>=8 && !!start && !!end &&
  Number.isFinite(Date.parse(start))&&Number.isFinite(Date.parse(end))&&
  Date.parse(start)<Date.parse(end)&&
  /^-?[0-9]{1,14}(?:\.[0-9]{1,6})?$/.test(amount)
 const submit=(e:FormEvent)=>{e.preventDefault();if(canCreate)create.mutate()}
 return <section className="panel full-panel" aria-label="结算参考与来源对账">
  <div className="panel-heading"><div><h2>结算批次与差异核对</h2>
   <p>建立人工参考结算单，再把正式 Finances 来源交易明确关联。
     本模块不验证银行到账，不提供真实 Amazon 结算已完成的证明。</p></div>
   <button type="button" className="refresh-button" onClick={refresh}>刷新</button>
  </div>
  <form className="team-invite-form" onSubmit={submit}>
   <label htmlFor="settlement-ref">人工账单参考号（同站点唯一）</label>
   <input id="settlement-ref" value={reference} onChange={e=>setReference(e.target.value.trim())}
    required minLength={8} maxLength={160} autoComplete="off"
    pattern="[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}"
    placeholder="STATEMENT-202610-001"/>
   <label htmlFor="settlement-start">来源期间起点（本地时间）</label>
   <input id="settlement-start" type="datetime-local" value={start} required
    onChange={e=>setStart(e.target.value)}/>
   <label htmlFor="settlement-end">来源期间终点（不含，本地时间）</label>
   <input id="settlement-end" type="datetime-local" value={end} required
    onChange={e=>setEnd(e.target.value)}/>
   <label htmlFor="settlement-net">人工账单有符号参考净额（{currency}）</label>
   <input id="settlement-net" inputMode="decimal" required
    pattern="-?[0-9]{1,14}(\.[0-9]{1,6})?" value={amount}
    onChange={e=>setAmount(e.target.value)} placeholder="245.660000"/>
   <button type="submit" className="primary-button"
    disabled={!canCreate||create.isPending}>建立参考结算单（非银行确认）</button>
  </form>
  {statements.isError?<p className="setup-error" role="alert">{String(statements.error)}</p>:
   !statements.data?.length?<p className="empty-state">当前站点还没有人工参考结算单。</p>:
   <label className="shop-filter" htmlFor="settlement-selected">
    <span>选择参考结算单</span>
    <select id="settlement-selected" value={selected}
     onChange={e=>{setSelected(e.target.value);setShowAudit(false)}}>
     <option value="">选择要核对的批次</option>
     {statements.data.map(s=><option key={s.id} value={s.id}>
      {s.reference} · {s.currency} {s.reportedNet}</option>)}
    </select>
   </label>}
  {current&&<div className="core-tools">
   <p><strong>{current.reference}</strong> · {current.currency} {current.reportedNet}</p>
   <p className="setup-hint">参考来源：人工未核验；期间：{time(current.periodStart)}
    {' — '}{time(current.periodEnd)}（终点不含）</p>
   {details.isPending?<p>正在核对…</p>:
    details.isError?<p className="setup-error" role="alert">{String(details.error)}</p>:
    details.data&&<>
     <p role="status"><strong>{labels[details.data.status]??details.data.status}</strong></p>
     <p>已关联 {details.data.linkedCount} 笔来源交易；
      本期间尚未分配 {details.data.unassignedSourceCount} 笔候选交易；
      期间外异常 {details.data.outsidePeriodCount} 笔。</p>
     <p>已分配交易的有符号合计：
      {details.data.linkedSourceNet??'不可核对'} {current.currency}；
      参考净额减去已关联金额的差异：
      {details.data.signedDifference??'不可核对'} {current.currency}</p>
     {!!details.data.linkedEvents.length&&<div className="table-scroll"
      tabIndex={0} role="region" aria-label="已分配的原始财务交易">
      <table><thead><tr><th>来源交易键</th><th>类型</th><th>有符号金额</th>
       <th>发生时间</th><th>期间</th><th>操作</th></tr></thead>
       <tbody>{details.data.linkedEvents.map(e=><tr key={e.eventId}>
        <td>{e.sourceEventKey}</td><td>{e.sourceType}</td>
        <td>{e.currency} {e.signedAmount}</td><td>{time(e.postedAt)}</td>
        <td>{e.withinPeriod?'期间内':'期间外异常'}</td>
        <td><button type="button" className="text-button" disabled={unlink.isPending}
          onClick={()=>unlink.mutate(e.eventId)}>解除关联</button></td>
       </tr>)}</tbody></table></div>}
    </>}
   <h3>尚未分配的正式 Finances 来源交易</h3>
   {candidates.isPending?<p>正在读取同站点同币种候选…</p>:
    candidates.isError?<p className="setup-error" role="alert">{String(candidates.error)}</p>:
    !candidates.data?.length?<p>此期间暂无未分配的受信来源记录。</p>:
    <div className="table-scroll" tabIndex={0} role="region" aria-label="结算候选交易">
     <table><thead><tr><th>来源交易键</th><th>类型</th><th>有符号金额</th>
      <th>发生时间</th><th>匹配证据</th><th>操作</th></tr></thead>
      <tbody>{candidates.data.map(e=><tr key={e.id}>
       <td>{e.sourceKey}</td><td>{e.sourceType}</td>
       <td>{e.currency} {e.amount}</td><td>{time(e.postedAt)}</td>
       <td>{e.matchedReferenceKind?
         '来源 '+e.matchedReferenceKind+' 编号精确匹配（仍需核对）':'无相同来源编号'}</td>
       <td><button type="button" className="refresh-button" disabled={link.isPending}
        onClick={()=>link.mutate(e.id)}>分配到参考结算单</button></td>
      </tr>)}</tbody></table></div>}
   <button type="button" className="text-button"
    onClick={()=>setShowAudit(v=>!v)}>{showAudit?'收起':'查看'}关联审计记录</button>
   {showAudit&&(audit.isPending?<p>正在读取审计…</p>:
    audit.isError?<p className="setup-error">{String(audit.error)}</p>:
    <div className="table-scroll" tabIndex={0} role="region" aria-label="结算分配审计">
     <table><thead><tr><th>操作</th><th>来源事件</th><th>操作人</th><th>时间</th></tr></thead>
      <tbody>{(audit.data??[]).map((a,i)=><tr key={i}>
       <td>{a.action}</td><td>{a.eventId??'创建结算参考'}</td>
       <td>{a.actorId}</td><td>{time(a.occurredAt)}</td>
      </tr>)}</tbody></table></div>)}
  </div>}
  {notice&&<p className="panel-caption" role="status">{notice}</p>}
  {failure&&<p className="setup-error" role="alert">{failure}</p>}
  <p className="setup-hint">即使有符号金额相等，也不证明 Amazon 的结算批次覆盖完整或实际到账；
   后续仍需支付/结算来源 ID、银行流水和税费明细逐项核实。</p>
 </section>
}
