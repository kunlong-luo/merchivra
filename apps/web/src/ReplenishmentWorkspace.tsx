import {useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {fetchCoreListings} from './lib/core'
import {getReplenishmentSuggestions,saveReplenishmentPolicy,removeReplenishmentPolicy,
 getReplenishmentPurchaseDrafts,createReplenishmentPurchaseDraft,getReplenishmentDemandSignals,
 getReplenishmentSupplierQuotes,
 type ReplenishmentDemandSignal,type ReplenishmentAdvice,type ReplenishmentSupplierQuote}
 from './lib/replenishment'
import {fetchSuppliers} from './lib/purchasing'

const labels:Record<string,string>={
 NO_OBSERVATION:'无库存观测，不能计算',STALE_OBSERVATION:'库存观测超 48 小时或时间异常',
 INCOMPLETE_OBSERVATION:'库存数据不完整，不能计算',REORDER:'可考虑补货',HEALTHY:'未触发补货点'
}
const demandLabels:Record<ReplenishmentDemandSignal['status'],string>={
 UNMAPPED:'Listing 尚未具有有效的内部 SKU 映射',
 INCOMPLETE_COVERAGE:'订单同步缺少完整 28 天窗口，不能估算',
 INCOMPLETE_ITEMS:'订单行未完整观测或来源混杂，不能估算',
 NO_OBSERVED_ORDERS:'有完整同步窗口，但没有观测到订单（不代表市场需求为零）',
 MOCK_SAMPLE:'仅模拟订单样本，数字仅供检查计算',
 OBSERVED_ONLY:'订单项历史观测（未扣退款、取消；非需求预测）'
}
export default function ReplenishmentWorkspace({tenantId,marketplaceId}:{
 tenantId:string;marketplaceId:string
}){
 const client=useQueryClient()
 const [sellerSku,setSellerSku]=useState(''),[reorder,setReorder]=useState(3)
 const [target,setTarget]=useState(20),[lead,setLead]=useState(14)
 const [message,setMessage]=useState(''),[failure,setFailure]=useState('')
 const [selected,setSelected]=useState<ReplenishmentAdvice|null>(null)
 const [supplierId,setSupplierId]=useState('')
 const [quoteSnapshot,setQuoteSnapshot]=useState<ReplenishmentSupplierQuote|null>(null)
 const [currency,setCurrency]=useState('USD'),[unitCost,setUnitCost]=useState('')
 const [requestKey,setRequestKey]=useState(()=>crypto.randomUUID())
 const listings=useQuery({queryKey:['replenishment','listings',tenantId,marketplaceId],
  queryFn:()=>fetchCoreListings(marketplaceId),retry:false})
 const suggestions=useQuery({queryKey:['replenishment','suggestions',tenantId,marketplaceId],
  queryFn:()=>getReplenishmentSuggestions(marketplaceId),retry:false})
 const demand=useQuery({queryKey:['replenishment','demand',tenantId,marketplaceId],
  queryFn:()=>getReplenishmentDemandSignals(marketplaceId),retry:false})
 const quotes=useQuery({queryKey:['replenishment','quotes',tenantId,marketplaceId],
  queryFn:()=>getReplenishmentSupplierQuotes(marketplaceId),
  enabled:selected!==null,retry:false})
 const suppliers=useQuery({queryKey:['purchasing',tenantId,'suppliers'],
  queryFn:fetchSuppliers,enabled:selected!==null,retry:false})
 const drafts=useQuery({queryKey:['replenishment','draft-history',tenantId,marketplaceId],
  queryFn:()=>getReplenishmentPurchaseDrafts(marketplaceId),retry:false})
 const refresh=()=>{void client.invalidateQueries({queryKey:['replenishment','suggestions',tenantId,marketplaceId]});
  void client.invalidateQueries({queryKey:['replenishment','demand',tenantId,marketplaceId]});
  void client.invalidateQueries({queryKey:['replenishment','quotes',tenantId,marketplaceId]})}
 const saved=useMutation({mutationFn:()=>saveReplenishmentPolicy(marketplaceId,sellerSku,
  reorder,target,lead),onSuccess:()=>{setMessage('补货策略已保存');setFailure('');refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 const removed=useMutation({mutationFn:(sku:string)=>removeReplenishmentPolicy(marketplaceId,sku),
  onSuccess:()=>{setMessage('补货策略已删除');setFailure('');refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 const purchaseDraft=useMutation({
  mutationFn:()=>{
   if(!selected?.observationId||selected.suggestedQuantity===null)
    throw Error('请先选择可计算的补货建议')
   if(quoteSnapshot&&(quoteSnapshot.status!=='READY'||quoteSnapshot.orderQuantity===null||
    quoteSnapshot.sellerSku!==selected.policy.sellerSku||
    quoteSnapshot.observationId!==selected.observationId||
    quoteSnapshot.policyUpdatedAt!==selected.policy.updatedAt||
    quoteSnapshot.baseSuggestedQuantity!==selected.suggestedQuantity))
    throw Error('供应商报价快照不再匹配当前补货建议，请刷新后重新选择')
   return createReplenishmentPurchaseDraft(marketplaceId,{
    requestKey,sellerSku:selected.policy.sellerSku,
    supplierId:quoteSnapshot?.supplierId??supplierId,
    currency:quoteSnapshot?.currency??currency,unitCost:quoteSnapshot?.unitCost??unitCost,
    expectedPolicyUpdatedAt:selected.policy.updatedAt,
    expectedObservationId:selected.observationId,expectedQuantity:selected.suggestedQuantity,
    ...(quoteSnapshot?{expectedSupplierRuleUpdatedAt:quoteSnapshot.supplierRuleUpdatedAt,
     expectedOrderQuantity:quoteSnapshot.orderQuantity!}:{})
   })
  },
  onSuccess:d=>{
   setMessage('已创建本地采购单 '+d.order.orderNumber+'（DRAFT），请到采购页面提交并由另一名 Owner 审批')
   setFailure('');setSelected(null);setQuoteSnapshot(null);setUnitCost('');setRequestKey(crypto.randomUUID())
   void client.invalidateQueries({queryKey:['replenishment','draft-history',tenantId,marketplaceId]})
   void client.invalidateQueries({queryKey:['purchasing',tenantId]})
   refresh()
  },
  onError:(e:Error)=>setFailure(e.message)
 })
 function submit(e:FormEvent){e.preventDefault();if(sellerSku)saved.mutate()}
 function createDraft(e:FormEvent){e.preventDefault();if(selected)purchaseDraft.mutate()}
 function selectDraft(s:ReplenishmentAdvice){
  setSelected(s);setQuoteSnapshot(null);setSupplierId('');setRequestKey(crypto.randomUUID());setUnitCost('');setFailure('')
 }
 return <section className="panel full-panel" aria-label="SKU 补货建议">
   <div className="panel-heading"><div><h2>SKU 补货策略与建议</h2>
    <p>仅参考最近 48 小时的已观测 FBA 可售数量。未知/过期不视为 0；建议不考虑未核验在途。可人工生成本地采购草稿，但不自动审批或修改 Amazon。</p></div>
    <button type="button" className="refresh-button" onClick={refresh}>刷新建议</button>
   </div>
   <form className="team-invite-form" onSubmit={submit}>
    <label htmlFor="repl-sku">Seller SKU（当前站点 Listing）</label>
    <select id="repl-sku" value={sellerSku} onChange={e=>setSellerSku(e.target.value)}>
      <option value="">选择 Listing</option>
      {(listings.data??[]).map(l=><option value={l.sellerSku} key={l.id}>{l.sellerSku}</option>)}
    </select>
    <label htmlFor="repl-point">触发阈值（可售件数）</label>
    <input id="repl-point" type="number" min={0} max={10000000} value={reorder}
      onChange={e=>setReorder(Number(e.target.value))}/>
    <label htmlFor="repl-target">目标可售量</label>
    <input id="repl-target" type="number" min={1} max={10000000} value={target}
      onChange={e=>setTarget(Number(e.target.value))}/>
    <label htmlFor="repl-lead">预计采购提前期（天，仅作策略记录）</label>
    <input id="repl-lead" type="number" min={1} max={365} value={lead}
      onChange={e=>setLead(Number(e.target.value))}/>
    <button type="submit" className="primary-button" disabled={!sellerSku||
      !Number.isInteger(reorder)||!Number.isInteger(target)||!Number.isInteger(lead)||
      reorder<0||target<=reorder||target>10000000||lead<1||lead>365||saved.isPending}>
      保存 SKU 策略</button>
   </form>
   {listings.data?.length===50&&<p className="setup-hint">
    选择框只包含首 50 条已读取 Listing；更多 SKU 的策略可通过 API 设置。</p>}
   {suggestions.isPending?<p className="empty-state">正在读取补货建议…</p>:
    suggestions.isError?<p className="setup-error" role="alert">{String(suggestions.error)}</p>:
    !suggestions.data?.length?<p className="empty-state">尚未配置此站点的 SKU 补货策略。</p>:
    <div className="table-scroll" role="region" tabIndex={0} aria-label="补货策略计算结果">
     <table><thead><tr><th scope="col">Seller SKU</th><th scope="col">可售观测</th>
       <th scope="col">补货点 / 目标</th><th scope="col">状态</th>
       <th scope="col">建议补充件数</th><th scope="col">操作</th></tr></thead><tbody>
      {suggestions.data.map(s=><tr key={s.policy.sellerSku}>
       <td>{s.policy.sellerSku}</td><td>{s.fulfillable===null?'未知':s.fulfillable}</td>
       <td>{s.policy.reorderPoint} / {s.policy.targetStock}</td>
       <td>{labels[s.state]??s.state}</td><td>{s.suggestedQuantity===null?'不可计算':s.suggestedQuantity}</td>
       <td><div className="sandbox-actions">
         <button type="button" className="refresh-button" onClick={()=>{
           setSellerSku(s.policy.sellerSku);setReorder(s.policy.reorderPoint);
           setTarget(s.policy.targetStock);setLead(s.policy.leadTimeDays)}}>编辑</button>
         {s.state==='REORDER'&&s.observationId&&s.suggestedQuantity!==null&&
          s.suggestedQuantity>0&&s.suggestedQuantity<=1000000&&
          <button type="button" className="primary-button"
           onClick={()=>selectDraft(s)}>生成采购草稿</button>}
         <button type="button" className="text-button" disabled={removed.isPending}
           onClick={()=>{if(window.confirm('删除此 SKU 的补货策略？'))
             removed.mutate(s.policy.sellerSku)}}>删除</button>
       </div></td></tr>)}
     </tbody></table>
    </div>}
   <section className="core-tools" aria-label="近 28 天订单销量观测参考">
    <h3>7 / 28 天订单数量与提前期需求参考 · 仅分析</h3>
    <p className="setup-hint">只统计当前站点、已映射 Seller SKU 的最新订单项（下单件数，不是净销量）。需最近 28 天完整的已提交订单同步窗口、全量已观测订单行和一致来源才能计算；窗口终点固定比当前时间早 30 分钟。Mock 来源仅演示，不用于采购审批、在途预测或 Amazon 写操作。</p>
    {demand.isPending?<p className="empty-state">读取订单来源覆盖情况…</p>:
     demand.isError?<p className="setup-error" role="alert">{String(demand.error)}</p>:
     !demand.data?.length?<p className="empty-state">暂无配置补货策略的 SKU。</p>:
     <div className="table-scroll" tabIndex={0} role="region" aria-label="订单销量覆盖率分析表">
      <table><thead><tr><th scope="col">Seller SKU</th><th scope="col">28 天来源覆盖</th>
       <th scope="col">近 7 / 28 天下单件数</th><th scope="col">28 天日均</th>
       <th scope="col">提前期件数（示意）</th></tr></thead><tbody>
       {demand.data.map(d=><tr key={d.sellerSku}>
        <td className="strong-cell">{d.sellerSku}</td>
        <td><span>{demandLabels[d.status]}</span><div className="panel-caption">
         {d.sourceMode==='MOCK'?'MOCK 虚构来源':d.sourceMode==='LIVE'?'LIVE 来源标记（需真实联调）':'尚无来源'}
         </div></td>
        <td>{d.observedUnits28d===null?'不可计算':d.observedUnits7d+' / '+d.observedUnits28d}</td>
        <td>{d.observedDailyUnits28d??'不可计算'}</td>
        <td>{d.illustrativeLeadTimeUnits===null?'不提供':
         d.illustrativeLeadTimeUnits+' 件 / '+d.leadTimeDays+' 天（不可直接下单）'}</td>
       </tr>)}
      </tbody></table>
     </div>}
   </section>
   {selected&&<section className="core-tools" aria-label="确认补货采购草稿">
    <h3>确认本地采购草稿</h3>
    <p>Seller SKU：{selected.policy.sellerSku} · 观测可售：
      {selected.fulfillable??'未知'} · 建议订购：{selected.suggestedQuantity??'不可计算'} 件。
      系统将在保存时重新校验策略版本、最新观测和内部 SKU 映射。
      Mock 数据也只能生成本地草稿，不代表真实 Amazon 入仓或资金支出。</p>
    <div className="ops-rule-note">
     <label htmlFor="repl-rule-quote">优先采用已配置的 V42 供应商报价规则（自动按 MOQ/箱规取整，仍需人工确认）</label>
     <select id="repl-rule-quote" value={quoteSnapshot?.supplierId??''}
      onChange={e=>{
       const chosen=quotes.data?.find(q=>q.sellerSku===selected.policy.sellerSku&&
        q.supplierId===e.target.value&&q.status==='READY')??null
       setQuoteSnapshot(chosen);setSupplierId(chosen?.supplierId??'')
       setCurrency(chosen?.currency??'USD');setUnitCost(chosen?.unitCost??'')
       setRequestKey(crypto.randomUUID());setFailure('')
      }}>
      <option value="">人工输入模式（不自动应用 MOQ/箱规）</option>
      {(quotes.data??[]).filter(q=>q.sellerSku===selected.policy.sellerSku)
       .map(q=><option key={q.supplierId} value={q.supplierId}
        disabled={q.status!=='READY'}>
        {q.supplierName} · {q.currency} {q.unitCost} ·
        {q.status==='READY'?'建议采购 '+q.orderQuantity+' 件':'规则不可用：'+q.status}
       </option>)}
     </select>
     {quotes.isPending&&<p className="panel-caption">正在读取供应商规则…</p>}
     {quotes.isError&&<p role="alert" className="setup-error">读取供应商规则失败：
      {String(quotes.error)}。可改用人工单价，且不代表符合 MOQ/箱规。</p>}
     {quoteSnapshot&&<p className="panel-caption" role="status">
      基础补货 {quoteSnapshot.baseSuggestedQuantity} 件 → MOQ {quoteSnapshot.minimumOrderQuantity} /
      箱规 {quoteSnapshot.packMultiple} 的实际采购 {quoteSnapshot.orderQuantity} 件；
      供应商提前期 {quoteSnapshot.supplierLeadTimeDays} 天（非承诺送达日）；
      原币金额 {quoteSnapshot.currency} {quoteSnapshot.estimatedTotal}，不做汇率转换。
     </p>}
    </div>
    <form className="team-invite-form" onSubmit={createDraft}>
     <label htmlFor="repl-supplier">供应商（须先在采购与供应商页面建立）</label>
     <select id="repl-supplier" value={quoteSnapshot?.supplierId??supplierId}
      disabled={quoteSnapshot!==null} onChange={e=>{
       setSupplierId(e.target.value);setRequestKey(crypto.randomUUID())}}>
      <option value="">选择已有供应商</option>
      {(suppliers.data??[]).filter(x=>x.status==='ACTIVE').map(x=>
       <option value={x.id} key={x.id}>{x.code} · {x.name}</option>)}
     </select>
     <label htmlFor="repl-currency">采购币种（不会自动换汇）</label>
     <select id="repl-currency" value={quoteSnapshot?.currency??currency}
      disabled={quoteSnapshot!==null} onChange={e=>{
       setCurrency(e.target.value);setRequestKey(crypto.randomUUID())}}>
      {['USD','EUR','GBP','JPY','CNY','CAD','AUD'].map(x=>
       <option value={x} key={x}>{x}</option>)}
     </select>
     <label htmlFor="repl-cost">单位采购价格（{currency}，人工确认）</label>
     <input id="repl-cost" inputMode="decimal" value={quoteSnapshot?.unitCost??unitCost}
      readOnly={quoteSnapshot!==null}
      pattern="[0-9]{1,14}(\\.[0-9]{1,6})?" required
      onChange={e=>{setUnitCost(e.target.value);setRequestKey(crypto.randomUUID())}}/>
     <button type="submit" className="primary-button"
      disabled={!(quoteSnapshot?.supplierId??supplierId)||
       !/^[0-9]{1,14}(\\.[0-9]{1,6})?$/.test(quoteSnapshot?.unitCost??unitCost)
        ||purchaseDraft.isPending}>确认生成 DRAFT（不提交审批）</button>
     <button type="button" className="text-button" onClick={()=>{setSelected(null);setQuoteSnapshot(null)}}>
      取消</button>
    </form>
    {suppliers.isError&&<p className="setup-error" role="alert">{String(suppliers.error)}</p>}
   </section>}
   <section className="core-tools" aria-label="补货草稿来源历史">
    <h3>补货生成的采购草稿 · 来源记录</h3>
    <p className="setup-hint">保留库存观测、采购数量与原币单价快照；采购单后续状态会同步显示。</p>
    {drafts.isPending?<p className="empty-state">读取采购来源历史…</p>:
     drafts.isError?<p className="setup-error" role="alert">{String(drafts.error)}</p>:
     !drafts.data?.length?<p className="empty-state">还没有由补货建议生成的采购草稿。</p>:
     <div className="table-scroll" role="region" tabIndex={0} aria-label="补货草稿来源表">
      <table><thead><tr><th scope="col">采购单号</th><th scope="col">Seller SKU</th>
       <th scope="col">观测 / 基础 / 实购</th><th scope="col">来源单价</th>
       <th scope="col">当前状态</th></tr></thead><tbody>
       {drafts.data.map(d=><tr key={d.purchaseOrderId}>
        <td>{d.orderNumber}</td><td>{d.sellerSku}</td>
        <td>{d.fulfillable} / {d.suggestedQuantity} / {d.orderedQuantity}</td>
        <td>{d.currency} {d.unitCost} · {d.pricingSource==='V42_RULE'?'V42 供应商规则':'人工输入'}</td><td>{d.status}</td>
       </tr>)}
      </tbody></table>
     </div>}
   </section>
   {message&&<p className="panel-caption" role="status">{message}</p>}
   {failure&&<p className="setup-error" role="alert">{failure}</p>}
 </section>
}
