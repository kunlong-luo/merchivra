import {useState} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {fetchItemAllocation,fetchItemAllocationAudit,
 reviewItemAllocation,saveItemAllocation,type AllocationAction} from './lib/item-allocation'

const categories:Record<string,string>={
 PRODUCT_SALES:'商品销售来源',AMAZON_FEE:'Amazon 平台费用',
 FBA_FEE:'FBA 费用'}
const statuses:Record<string,string>={
 NOT_STARTED:'尚未编制逐项分配',DRAFT:'人工编制中；仍有待分配金额',
 PENDING:'待另一位 Owner 独立审批',APPROVED:'人工逐项分配已独立审批',
 REJECTED:'已驳回；可重新编制',STALE:'订单／费用来源或映射发生变化，旧审批失效'}
export default function ItemAllocationPanel({tenantId,marketplaceId,eventId}:{
 tenantId:string;marketplaceId:string;eventId:string
}){
 const qc=useQueryClient()
 const [item,setItem]=useState(''),[category,setCategory]=useState('PRODUCT_SALES')
 const [amount,setAmount]=useState(''),[ref,setRef]=useState('')
 const [reason,setReason]=useState(''),[auditShown,setAuditShown]=useState(false)
 const key=['finance','item-allocation',tenantId,marketplaceId,eventId]
 const detail=useQuery({queryKey:key,queryFn:()=>
  fetchItemAllocation(marketplaceId,eventId),retry:false,staleTime:15000})
 const audit=useQuery({queryKey:[...key,'audit'],enabled:auditShown,
  queryFn:()=>fetchItemAllocationAudit(marketplaceId,eventId),retry:false})
 const refreshed=()=>{
  void qc.invalidateQueries({queryKey:key})
  void qc.invalidateQueries({queryKey:['finance','sku-profit',tenantId,marketplaceId]})
  void qc.invalidateQueries({queryKey:['finance','contribution',tenantId,marketplaceId]})
 }
 const save=useMutation({mutationFn:(withdraw:boolean)=>saveItemAllocation(
   marketplaceId,eventId,{itemId:item,category,
    signedAmount:withdraw?null:amount,currency:detail.data?.currency??'',
    evidenceReference:ref,reason}),
  onSuccess:()=>{refreshed();setAmount('')},
 })
 const review=useMutation({mutationFn:(action:AllocationAction)=>
  reviewItemAllocation(marketplaceId,eventId,action,reason),
  onSuccess:()=>{refreshed();setReason('')},
 })
 const d=detail.data
 const valid=reason.trim().length>=10&&reason.length<=500&&
  !/[\x00-\x1F\x7F]/.test(reason)
 const amountValid=/^-?[0-9]{1,14}(?:\.[0-9]{1,6})?$/.test(amount)&&
  !/^-?0(?:\.0+)?$/.test(amount)
 const refValid=/^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$/.test(ref)
 const editing=d&&(d.state==='NOT_STARTED'||d.state==='DRAFT')
 const busy=save.isPending||review.isPending
 return <section className="panel full-panel" aria-label="V62 订单行 Finances 金额人工分摊">
  <div className="panel-heading"><div><h3>V62 · 多 SKU 来源费用逐项分配</h3>
   <p>只接受同一 Marketplace、已人工匹配订单和完全核平的 LIVE Finances 事件；
    按真实订单项与原币有符号金额分配。绝不按销量自动推算、不改 Amazon 原始记录。</p>
  </div><button className="refresh-button" type="button"
   onClick={()=>void detail.refetch()} disabled={detail.isFetching}>刷新来源</button></div>
  {detail.isPending?<p>检查事件分类、历史 SKU 成本与订单项…</p>:
   detail.isError?<p className="setup-error" role="alert">
    当前事件不可进行多 SKU 分摊：{String(detail.error)}</p>:
   d&&<>
    <p role="status"><strong>{statuses[d.state]??d.state}</strong>
     {' · '}审核版本 {d.version} · 原币 {d.currency}</p>
    <p className="setup-hint">每个来源类别的已分配金额与未分配差额必须精确守恒；
     尚未完整审批时仍不会进入店铺 / SKU 贡献报表。</p>
    <div className="table-scroll" role="region" tabIndex={0} aria-label="来源类别剩余金额">
     <table><thead><tr><th>来源类别</th><th>原始有符号合计</th>
      <th>已分配</th><th>未分配</th></tr></thead>
      <tbody>{d.amounts.residuals.map(x=><tr key={x.category}>
       <td>{categories[x.category]??x.category}</td>
       <td>{x.currency} {x.sourceTotal}</td><td>{x.allocated}</td>
       <td>{x.exact?'0 · 完整':x.remainder}</td></tr>)}</tbody></table>
    </div>
    <h4>当前订单商品项</h4>
    <div className="table-scroll" role="region" tabIndex={0} aria-label="可分配的订单项">
     <table><thead><tr><th>商品项来源 ID</th><th>内部 SKU</th><th>件数</th>
      <th>历史成本合计</th></tr></thead>
      <tbody>{d.items.map(i=><tr key={i.externalItemId}><td>{i.externalItemId}</td>
       <td>{i.sku}</td><td>{i.quantity}</td><td>{i.currency} {i.cost}</td>
      </tr>)}</tbody></table>
    </div>
    <h4>人工输入来源金额</h4>
    <div className="core-tools">
     <label htmlFor="item-alloc-item">分配的 Amazon 订单项</label>
     <select id="item-alloc-item" value={item} onChange={e=>setItem(e.target.value)}
      disabled={!editing||busy}>
      <option value="">选择订单项</option>
      {d.items.map(i=><option value={i.externalItemId} key={i.externalItemId}>
       {i.externalItemId} · {i.sku}</option>)}
     </select>
     <label htmlFor="item-alloc-category">来源费用分类</label>
     <select id="item-alloc-category" value={category} disabled={!editing||busy}
      onChange={e=>setCategory(e.target.value)}>
      {Object.entries(categories).map(([k,v])=><option key={k} value={k}>{v}</option>)}
     </select>
     <label htmlFor="item-alloc-amount">原始有符号金额（不自动取负号）</label>
     <input id="item-alloc-amount" value={amount} inputMode="decimal"
      placeholder="-2.500000" disabled={!editing||busy}
      onChange={e=>setAmount(e.target.value)}/>
     <label htmlFor="item-alloc-ref">脱敏分摊依据编号</label>
     <input id="item-alloc-ref" value={ref} maxLength={160}
      placeholder="REVIEW-SKU-2026-001" disabled={!editing||busy}
      onChange={e=>setRef(e.target.value)}/>
     <label htmlFor="item-alloc-reason">操作依据说明（10–500 字，禁止含客户资料）</label>
     <textarea id="item-alloc-reason" rows={2} value={reason}
      maxLength={500} onChange={e=>setReason(e.target.value)}/>
     <div className="sandbox-actions">
      <button type="button" className="primary-button"
       disabled={!editing||!item||!valid||!amountValid||!refValid||busy}
       onClick={()=>save.mutate(false)}>保存当前订单项 / 类别金额</button>
      <button type="button" className="text-button"
       disabled={!editing||!item||!valid||busy}
       onClick={()=>save.mutate(true)}>撤销该项分配</button>
     </div>
    </div>
    {d.lines.length>0&&<div className="table-scroll" role="region"
      tabIndex={0} aria-label="已编制分摊明细">
     <table><thead><tr><th>订单项</th><th>类别</th><th>有符号金额</th>
      <th>依据编号</th></tr></thead><tbody>
      {d.lines.map(l=><tr key={l.externalItemId+l.category}>
       <td>{l.externalItemId}</td><td>{categories[l.category]??l.category}</td>
       <td>{l.currency} {l.signedAmount}</td><td>{l.evidenceReference}</td>
      </tr>)}</tbody></table></div>}
    <div className="sandbox-actions">
     <button type="button" className="primary-button"
      disabled={!valid||busy||d.state!=='DRAFT'||!d.amounts.fullyAllocated||!d.evidenceCurrent}
      onClick={()=>review.mutate('submit')}>提交独立审核（全部金额守恒）</button>
     <button type="button" className="primary-button"
      disabled={!valid||busy||d.state!=='PENDING'||!d.evidenceCurrent}
      onClick={()=>review.mutate('approve')}>另一位 Owner 审批分摊</button>
     <button type="button" className="text-button"
      disabled={!valid||busy||d.state!=='PENDING'||!d.evidenceCurrent}
      onClick={()=>review.mutate('reject')}>另一位 Owner 驳回</button>
     <button type="button" className="text-button"
      disabled={!valid||busy||!['PENDING','APPROVED','REJECTED','STALE'].includes(d.state)}
      onClick={()=>review.mutate('reopen')}>重开并清空旧分摊证据</button>
    </div>
    {(save.error||review.error)&&<p role="alert" className="setup-error">
      {String(save.error??review.error)}</p>}
    <button type="button" className="text-button"
     onClick={()=>setAuditShown(v=>!v)}>{auditShown?'收起':'查看'}版本审计</button>
    {auditShown&&(audit.isPending?<p>读取审计…</p>:
     audit.isError?<p role="alert">{String(audit.error)}</p>:
     <div className="table-scroll" role="region" tabIndex={0} aria-label="逐项金额分配审计">
      <table><thead><tr><th>版本</th><th>动作</th><th>来源项</th><th>操作人</th>
       <th>原因</th><th>时间</th></tr></thead><tbody>
       {(audit.data??[]).map((e,i)=><tr key={i}>
        <td>{e.version}</td><td>{e.action}</td><td>{e.itemId??'整体'}</td>
        <td>{e.actor}</td><td>{e.reason}</td><td>{e.occurredAt}</td>
       </tr>)}</tbody></table>
     </div>)}
    <p className="setup-hint">APPROVED 只代表财务来源在本地经人工分摊与二人审核。
     不表示 Finances 来源已完整覆盖全部费用、银行到账、税费或正式净利润。</p>
   </>}
 </section>
}
