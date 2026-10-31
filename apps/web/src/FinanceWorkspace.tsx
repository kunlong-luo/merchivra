import {useEffect,useState,type FormEvent} from 'react'
import FinancesSyncPanel from './FinancesSyncPanel'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {fetchCoreMarketplaces,fetchCoreOrders} from './lib/core'
import {createManualFinanceEvent,fetchFinanceBuckets,fetchFinanceEvents,
 reviewFinanceEvent,type FinanceEvent} from './lib/finance'

const categories={
 PRODUCT_SALES:'销售入账分类',PRODUCT_REFUND:'退款',AMAZON_FEE:'平台费用',
 FBA_FEE:'FBA 费用',ADJUSTMENT:'其他调整',UNCLASSIFIED:'未分类'
} as const
function displayCategory(value:string){return categories[value as keyof typeof categories]??value}
function displayOrigin(value:string){return value==='LIVE'?'SP-API 来源（待独立授权验证）':
 '人工录入，未经过 Amazon 核验'}
function showTime(iso:string){return new Date(iso).toLocaleString('zh-CN',{hour12:false})}

export default function FinanceWorkspace({tenantId}:{tenantId:string}){
 const client=useQueryClient()
 const [marketplaceId,setMarketplaceId]=useState('')
 const [cursor,setCursor]=useState<{beforeAt:string;beforeId:string}|undefined>(undefined)
 const [eventKey,setEventKey]=useState(''),[category,setCategory]=useState('UNCLASSIFIED')
 const [amount,setAmount]=useState(''),[currency,setCurrency]=useState('USD')
 const [postedAt,setPostedAt]=useState(''),[sourceType,setSourceType]=useState('MANUAL_ENTRY')
 const [orderRef,setOrderRef]=useState('')
 const [selectedEvent,setSelectedEvent]=useState(''),[selectedOrder,setSelectedOrder]=useState('')
 const [message,setMessage]=useState(''),[failure,setFailure]=useState('')
 const markets=useQuery({queryKey:['finance','marketplaces',tenantId],
  queryFn:fetchCoreMarketplaces,retry:false})
 const currentId=markets.data?.some(m=>m.id===marketplaceId)?marketplaceId:
  (markets.data?.[0]?.id??'')
 const events=useQuery({queryKey:['finance','events',tenantId,currentId,cursor],
  queryFn:()=>fetchFinanceEvents(currentId,cursor),enabled:!!currentId,retry:false})
 const buckets=useQuery({queryKey:['finance','buckets',tenantId,currentId],
  queryFn:()=>fetchFinanceBuckets(currentId),enabled:!!currentId,retry:false})
 const orders=useQuery({queryKey:['finance','order-options',tenantId,currentId],
  queryFn:()=>fetchCoreOrders(currentId),enabled:!!currentId,retry:false})
 useEffect(()=>{setCursor(undefined);setSelectedEvent('');setSelectedOrder('')},[currentId])
 const refresh=()=>{
  void client.invalidateQueries({queryKey:['finance','events',tenantId,currentId]})
  void client.invalidateQueries({queryKey:['finance','buckets',tenantId,currentId]})
 }
 const manual=useMutation({mutationFn:()=>createManualFinanceEvent({
  marketplaceId:currentId,eventKey,category,amount,currency,
  postedAt:new Date(postedAt).toISOString(),externalOrderId:orderRef.trim()||null,sourceType
 }),onSuccess:()=>{
  setMessage('财务事件已存为人工未核验来源；不代表 Amazon 已入账')
  setFailure('');setEventKey('');setAmount('');setOrderRef('');setCursor(undefined);refresh()
 },onError:(e:Error)=>setFailure(e.message)})
 const reviewed=useMutation({mutationFn:(orderId:string|null)=>
  reviewFinanceEvent(selectedEvent,currentId,orderId),onSuccess:r=>{
  setMessage(r.state==='MATCHED'?'已人工关联所选订单；不改变原始金额':'已标记为待分配/未匹配')
  setFailure('');refresh()
 },onError:(e:Error)=>setFailure(e.message)})
 function submit(e:FormEvent){e.preventDefault();if(currentId&&postedAt)manual.mutate()}
 const current:FinanceEvent|undefined=events.data?.find(e=>e.id===selectedEvent)
 const last=events.data?.at(-1)
 const next=()=>{
  if(last){setCursor({beforeAt:last.postedAt,beforeId:last.id});setSelectedEvent('')}
 }
 const isValid=/^-?[0-9]{1,14}(\.[0-9]{1,6})?$/.test(amount)
 return <div className="core-workspace">
  <div className="core-intro"><strong>财务来源与人工对账 · 原币种</strong>
    <p>订单预计销售、平台财务交易及最终结算是不同事实。这一页不会自动合并或把人工录入视为已入账；不计算虚假净利润。</p></div>
  {markets.isError?<p className="setup-error" role="alert">{String(markets.error)}</p>:
   !markets.data?.length?<p className="empty-state">暂无可访问 Marketplace，请先创建本地店铺及站点。</p>:
   <div className="core-controls"><label className="shop-filter" htmlFor="finance-marketplace">
    <span>Marketplace</span><select id="finance-marketplace" value={currentId}
      onChange={e=>{setMarketplaceId(e.target.value);setCursor(undefined)}}>
     {markets.data.map(m=><option key={m.id} value={m.id}>
      {m.marketplaceId} · {m.region} · {m.currency}</option>)}
    </select></label></div>}
  {currentId&&markets.data?.find(m=>m.id===currentId)?.connectionStatus==='CONNECTED'&&
    <FinancesSyncPanel key={currentId} tenantId={tenantId} marketplaceId={currentId}/>}
  <section className="panel full-panel">
   <div className="panel-heading"><div><h2>按来源 / 原币 / 类别统计</h2>
    <p>相同币种和同类事件才聚合。这里的金额不等于销售额、利润或到账金额。</p></div>
    <button className="refresh-button" onClick={refresh}>刷新</button></div>
   {buckets.isError?<p className="setup-error">{String(buckets.error)}</p>:
    !buckets.data?.length?<p className="empty-state">尚无财务事件记录。</p>:
    <div className="table-scroll" role="region" tabIndex={0} aria-label="财务类别金额">
     <table><thead><tr><th scope="col">来源</th><th scope="col">类别</th>
       <th scope="col">原币金额（带符号）</th><th scope="col">事件数</th></tr></thead>
      <tbody>{buckets.data.map((b,i)=><tr key={i}>
       <td>{displayOrigin(b.dataOrigin)}</td><td>{displayCategory(b.category)}</td>
       <td>{b.currency} {b.amount}</td><td>{b.eventCount}</td></tr>)}</tbody></table>
    </div>}
  </section>
  <section className="panel full-panel">
   <div className="panel-heading"><div><h2>录入人工待核对事件</h2>
    <p>录入的只是 ERP 未核验参考事实；不会伪装为 SP-API 回执、会计凭证或结算款。</p>
   </div></div>
   <form className="team-invite-form" onSubmit={submit}>
    <label htmlFor="fin-key">来源参考编号（需稳定唯一）</label>
    <input id="fin-key" required minLength={8} maxLength={200}
      pattern="[A-Za-z0-9][A-Za-z0-9._:/-]{7,199}" value={eventKey}
      onChange={e=>setEventKey(e.target.value)} placeholder="MANUAL-2026-001"/>
    <label htmlFor="fin-category">分类</label>
    <select id="fin-category" value={category} onChange={e=>setCategory(e.target.value)}>
     {Object.entries(categories).map(([code,label])=><option key={code} value={code}>{label}</option>)}
    </select>
    <label htmlFor="fin-amount">原币金额（正负号来自原始记录；勿推测费用正负）</label>
    <input id="fin-amount" required inputMode="decimal" value={amount}
      onChange={e=>setAmount(e.target.value)}
      pattern="-?[0-9]{1,14}(\.[0-9]{1,6})?" placeholder="-2.500000"/>
    <label htmlFor="fin-currency">币种</label>
    <select id="fin-currency" value={currency} onChange={e=>setCurrency(e.target.value)}>
     {['USD','EUR','GBP','JPY','CAD','AUD','CNY','MXN','SGD'].map(x=><option key={x}>{x}</option>)}
    </select>
    <label htmlFor="fin-date">发生/入账时间（按参考来源）</label>
    <input id="fin-date" type="datetime-local" value={postedAt}
      onChange={e=>setPostedAt(e.target.value)} required/>
    <label htmlFor="fin-type">来源类型</label>
    <input id="fin-type" value={sourceType} maxLength={120}
      pattern="[A-Za-z0-9][A-Za-z0-9._:/-]{0,119}"
      onChange={e=>setSourceType(e.target.value)} required/>
    <label htmlFor="fin-order-ref">关联 Amazon 外部订单号（可选，尚未确认匹配）</label>
    <input id="fin-order-ref" value={orderRef} maxLength={160}
      onChange={e=>setOrderRef(e.target.value)}/>
    <button className="primary-button" type="submit" disabled={!currentId||!isValid||
     !postedAt||manual.isPending}>保存待核对事件</button>
   </form>
  </section>
  <section className="panel full-panel">
   <div className="panel-heading"><div><h2>财务事件与订单人工关联</h2>
    <p>仅显示此站点事件，不跨币种汇总；人工匹配不会修改财务原始事件。</p></div></div>
   {events.isError?<p className="setup-error">{String(events.error)}</p>:
    events.isPending?<p className="empty-state">加载事件…</p>:
    !events.data?.length?<p className="empty-state">当前页无更多事件。</p>:
    <div className="table-scroll" role="region" tabIndex={0} aria-label="财务事件">
     <table><thead><tr><th scope="col">事件编号</th><th scope="col">来源</th>
      <th scope="col">类别</th><th scope="col">金额</th>
      <th scope="col">时间</th><th scope="col">关联状态</th><th scope="col">操作</th></tr></thead>
     <tbody>{events.data.map(e=><tr key={e.id}>
       <td>{e.eventKey}</td><td>{e.dataOrigin==='LIVE'?'SP-API':'人工未核验'}</td>
       <td>{displayCategory(e.category)}</td><td>{e.currency} {e.amount}</td>
       <td>{showTime(e.postedAt)}</td><td>{e.reviewStatus}</td>
       <td><button type="button" className="text-button"
        onClick={()=>{setSelectedEvent(e.id);setSelectedOrder(e.reviewedOrderId??'')}}>
        关联审核</button></td></tr>)}</tbody></table>
    </div>}
   <div className="sandbox-actions">
    {cursor&&<button type="button" className="refresh-button"
     onClick={()=>setCursor(undefined)}>返回第一页</button>}
    {events.data?.length===50&&<button type="button" className="refresh-button"
     onClick={next}>下一页</button>}
   </div>
   {current&&<div className="core-tools">
    <strong>当前事件：{current.eventKey}</strong>
    <p className="setup-hint">来源状态：{displayOrigin(current.dataOrigin)}。必须人工核对事件和订单是否真实相关。</p>
    <label htmlFor="fin-match-order">匹配同站点订单（仅当前读取的前 50 条）</label>
    <select id="fin-match-order" value={selectedOrder}
      onChange={e=>setSelectedOrder(e.target.value)}>
     <option value="">暂不关联</option>
     {(orders.data??[]).map(o=><option key={o.id} value={o.id}>
      {o.externalOrderId} · {o.dataOrigin}</option>)}
    </select>
    <div className="sandbox-actions">
     <button className="primary-button" type="button" disabled={!selectedOrder||
      reviewed.isPending} onClick={()=>reviewed.mutate(selectedOrder)}>确认关联</button>
     <button className="text-button" type="button" disabled={reviewed.isPending}
      onClick={()=>reviewed.mutate(null)}>标记未匹配</button>
    </div>
   </div>}
  </section>
  {message&&<p className="panel-caption" role="status">{message}</p>}
  {failure&&<p className="setup-error" role="alert">{failure}</p>}
 </div>
}
