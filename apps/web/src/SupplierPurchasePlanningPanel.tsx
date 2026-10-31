import {useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import type {Sku} from './lib/catalog'
import type {Supplier} from './lib/purchasing'
import {
 fetchSupplierSkuRules,setSupplierSkuRule,removeSupplierSkuRule,
 fetchLocalPurchaseRecommendations,fetchLocalPurchasePlans,
 proposeLocalPurchasePlan,actOnLocalPurchasePlan,
 type PurchaseRecommendation,type NewPlanRequest
} from './lib/purchase-planning'

const states:Record<string,string>={
 NO_LEDGER:'无本地库存流水（不建议采购）',
 LEDGER_ANOMALY:'库存流水异常（不建议采购）',
 HEALTHY:'未触发低库存阈值',
 LOW_STOCK:'可生成本地采购计划',
 LIMIT_EXCEEDED:'MOQ 或整箱倍数超过单行上限'
}
export default function SupplierPurchasePlanningPanel({tenantId,warehouseId,skus,suppliers,
 onConverted}:{
 tenantId:string;warehouseId:string;skus:Sku[];suppliers:Supplier[];
 onConverted:(purchaseOrderId:string)=>void
}){
 const client=useQueryClient()
 const [supplierId,setSupplierId]=useState(''),[skuId,setSkuId]=useState('')
 const [moq,setMoq]=useState(1),[pack,setPack]=useState(1)
 const [lead,setLead]=useState(14),[currency,setCurrency]=useState('USD')
 const [unitCost,setUnitCost]=useState('')
 const [message,setMessage]=useState(''),[failure,setFailure]=useState('')
 const [requestKeys,setRequestKeys]=useState<Record<string,string>>({})
 const root=['purchasing',tenantId]
 const rules=useQuery({queryKey:[...root,'supplier-rules'],queryFn:fetchSupplierSkuRules})
 const recommendations=useQuery({queryKey:[...root,'planning',warehouseId,'recommendations'],
  queryFn:()=>fetchLocalPurchaseRecommendations(warehouseId),enabled:!!warehouseId})
 const plans=useQuery({queryKey:[...root,'planning',warehouseId,'plans'],
  queryFn:()=>fetchLocalPurchasePlans(warehouseId),enabled:!!warehouseId})
 const refresh=()=>void client.invalidateQueries({queryKey:root})
 const save=useMutation({mutationFn:()=>setSupplierSkuRule(
  supplierId,skuId,moq,pack,lead,currency,unitCost),
  onSuccess:()=>{setMessage('供应商 SKU 采购规则已更新');setFailure('');refresh()},
  onError:(error:Error)=>setFailure(error.message)})
 const remove=useMutation({mutationFn:({s,k}:{s:string;k:string})=>removeSupplierSkuRule(s,k),
  onSuccess:()=>{setMessage('供应商 SKU 规则已移除');setFailure('');refresh()},
  onError:(error:Error)=>setFailure(error.message)})
 const propose=useMutation({mutationFn:(request:NewPlanRequest)=>proposeLocalPurchasePlan(request),
  onSuccess:p=>{setMessage('本地采购计划已保存：'+p.id.slice(0,8));
   setFailure('');refresh()},
  onError:(error:Error)=>setFailure(error.message)})
 const action=useMutation({
  mutationFn:({id,kind}:{id:string;kind:'convert'|'cancel'})=>actOnLocalPurchasePlan(id,kind),
  onSuccess:p=>{setMessage(p.status==='CONVERTED'
   ?'已创建本地采购草稿，需要另外提交并由第二位 Owner 审批':'采购计划已取消');
   setFailure('');refresh();if(p.purchaseOrderId)onConverted(p.purchaseOrderId)},
  onError:(error:Error)=>setFailure(error.message)
 })
 const validTerms=!!supplierId&&!!skuId&&
  Number.isInteger(moq)&&moq>=1&&moq<=1_000_000&&
  Number.isInteger(pack)&&pack>=1&&pack<=1_000_000&&
  Number.isInteger(lead)&&lead>=0&&lead<=365&&
  /^[A-Z]{3}$/.test(currency)&&/^[0-9]{1,14}(\.[0-9]{1,6})?$/.test(unitCost)
 function submit(e:FormEvent){e.preventDefault();if(validTerms)save.mutate()}
 function recommendationKey(a:PurchaseRecommendation){
  return a.warehouseId+':'+a.productSkuId+':'+a.supplierId
 }
 function plan(a:PurchaseRecommendation){
  if(a.state!=='LOW_STOCK'||a.onHandQuantity===null||a.orderQuantity===null||
    !a.sourceMovementId||propose.isPending)return
  const key=recommendationKey(a)
  const requestKey=requestKeys[key]??crypto.randomUUID()
  if(!requestKeys[key])setRequestKeys(v=>({...v,[key]:requestKey}))
  if(!window.confirm('确认基于当前本地库存和供应商规则建立采购计划？此操作不会下单或向 Amazon 发货。'))return
  propose.mutate({requestKey,warehouseId:a.warehouseId,supplierId:a.supplierId,
   productSkuId:a.productSkuId,expectedLastMovementId:a.sourceMovementId,
   expectedPolicyUpdatedAt:a.policyUpdatedAt,expectedRuleUpdatedAt:a.ruleUpdatedAt,
   expectedOnHand:a.onHandQuantity,expectedOrderQuantity:a.orderQuantity})
 }
 const activeSkus=skus.filter(s=>s.status==='ACTIVE')
 const activeSuppliers=suppliers.filter(s=>s.status==='ACTIVE')
 return <section className="panel full-panel" aria-label="供应商规则与本地采购计划">
  <div className="panel-heading"><div><h2>供应商采购规则与采购计划</h2>
   <p>MOQ、箱规倍数、参考交期和原币价格由本地人工维护。库存建议是本地台账推算；
    计划转采购单后仍是 DRAFT，必须另行审批，不调用 Amazon。</p></div>
   <button type="button" className="refresh-button" onClick={refresh}>刷新</button></div>
  <form className="team-invite-form" onSubmit={submit}>
   <label htmlFor="supplier-rule-supplier">供应商</label>
   <select id="supplier-rule-supplier" value={supplierId}
    onChange={e=>setSupplierId(e.target.value)} required>
    <option value="">选择供应商</option>
    {activeSuppliers.map(s=><option key={s.id} value={s.id}>{s.code} · {s.name}</option>)}
   </select>
   <label htmlFor="supplier-rule-sku">内部 SKU</label>
   <select id="supplier-rule-sku" value={skuId} onChange={e=>setSkuId(e.target.value)}
    required><option value="">选择 SKU</option>
    {activeSkus.map(s=><option key={s.id} value={s.id}>{s.internalSku}</option>)}
   </select>
   <label htmlFor="supplier-rule-moq">最小起订量 MOQ（件）</label>
   <input id="supplier-rule-moq" type="number" min={1} max={1000000} step={1}
    value={moq} onChange={e=>setMoq(Number(e.target.value))}/>
   <label htmlFor="supplier-rule-pack">包装倍数（件/箱或订货单位）</label>
   <input id="supplier-rule-pack" type="number" min={1} max={1000000} step={1}
    value={pack} onChange={e=>setPack(Number(e.target.value))}/>
   <label htmlFor="supplier-rule-lead">供应商预计交期（自然日）</label>
   <input id="supplier-rule-lead" type="number" min={0} max={365} step={1}
    value={lead} onChange={e=>setLead(Number(e.target.value))}/>
   <label htmlFor="supplier-rule-currency">采购币种</label>
   <select id="supplier-rule-currency" value={currency}
    onChange={e=>setCurrency(e.target.value)}>
    {['USD','EUR','GBP','JPY','CNY','CAD','AUD'].map(c=>
     <option key={c} value={c}>{c}</option>)}
   </select>
   <label htmlFor="supplier-rule-cost">原币采购单价（{currency}）</label>
   <input id="supplier-rule-cost" value={unitCost} inputMode="decimal"
    onChange={e=>setUnitCost(e.target.value)}
    pattern="[0-9]{1,14}(\.[0-9]{1,6})?" required/>
   <button type="submit" className="primary-button" disabled={!validTerms||save.isPending}>
    保存供应商采购规则</button>
  </form>
  {rules.isPending?<p className="empty-state">加载供应商规则…</p>:
   rules.isError?<p className="setup-error" role="alert">{String(rules.error)}</p>:
   !rules.data?.length?<p className="empty-state">尚未建立供应商 / SKU 采购规则。</p>:
   <div className="table-scroll" role="region" tabIndex={0} aria-label="供应商采购规则">
    <table><thead><tr><th scope="col">供应商</th><th scope="col">SKU</th>
     <th scope="col">MOQ / 倍数</th><th scope="col">参考交期</th>
     <th scope="col">原币单价</th><th scope="col">操作</th></tr></thead><tbody>
     {rules.data.map(r=><tr key={r.supplierId+':'+r.productSkuId}>
      <td>{suppliers.find(s=>s.id===r.supplierId)?.name??r.supplierId}</td>
      <td>{skus.find(s=>s.id===r.productSkuId)?.internalSku??r.productSkuId}</td>
      <td>{r.minimumOrderQuantity} / {r.packMultiple}</td>
      <td>{r.leadTimeDays} 天（估算）</td><td>{r.currency} {r.unitCost}</td>
      <td><div className="sandbox-actions">
       <button className="refresh-button" type="button" onClick={()=>{
        setSupplierId(r.supplierId);setSkuId(r.productSkuId);
        setMoq(r.minimumOrderQuantity);setPack(r.packMultiple);
        setLead(r.leadTimeDays);setCurrency(r.currency);setUnitCost(r.unitCost)}}>编辑</button>
       <button className="text-button" type="button" disabled={remove.isPending}
        onClick={()=>{if(window.confirm('移除此供应商 SKU 规则？历史采购计划不删除。'))
         remove.mutate({s:r.supplierId,k:r.productSkuId})}}>移除</button>
      </div></td>
     </tr>)}</tbody></table>
   </div>}
  {!warehouseId?<p className="setup-hint">
    选择上方本地仓库后，才能查看该仓库的采购建议和计划。</p>:<>
   <h3>仓库补货建议 → 人工采购计划</h3>
   <p className="setup-hint">
    仅当前仓库有流水且低于阈值时给出数量，按 max(建议件数,MOQ) 向上取包装倍数。
    未按仓分配的待收货不自动扣减；预计到货日期仅为供应商配置交期的推算。</p>
   {recommendations.isPending?<p className="empty-state">正在读取建议…</p>:
    recommendations.isError?<p className="setup-error" role="alert">
     {String(recommendations.error)}</p>:
    !recommendations.data?.length?<p className="empty-state">
     尚无可关联的仓库补货策略及供应商规则。</p>:
    <div className="table-scroll" role="region" tabIndex={0} aria-label="供应商采购建议">
     <table><thead><tr><th scope="col">SKU / 供应商</th><th scope="col">本地库存</th>
      <th scope="col">策略阈值</th><th scope="col">基础建议</th>
      <th scope="col">MOQ/倍数修正</th><th scope="col">原因</th>
      <th scope="col">操作</th></tr></thead><tbody>
      {recommendations.data.map(a=><tr key={recommendationKey(a)}>
       <td>{a.internalSku}<div className="secondary-cell">{a.supplierName}</div></td>
       <td>{a.onHandQuantity===null?'未知':a.onHandQuantity}</td>
       <td>{a.reorderPoint} / {a.targetStock}</td>
       <td>{a.suggestedQuantity??'—'}</td>
       <td>{a.orderQuantity??'—'}<div className="secondary-cell">
        {a.currency} {a.unitCost} · 交期 {a.leadTimeDays} 天</div></td>
       <td>{states[a.state]??a.state}</td>
       <td><button type="button" className="primary-button"
        disabled={a.state!=='LOW_STOCK'||a.orderQuantity===null||
         propose.isPending} onClick={()=>plan(a)}>保存采购计划</button></td>
      </tr>)}</tbody></table>
    </div>}
   <h3>本地采购计划清单</h3>
   {plans.isPending?<p className="empty-state">读取计划中…</p>:
    plans.isError?<p className="setup-error" role="alert">{String(plans.error)}</p>:
    !plans.data?.length?<p className="empty-state">当前仓库暂无人工采购计划。</p>:
    <div className="table-scroll" role="region" tabIndex={0} aria-label="采购计划">
     <table><thead><tr><th scope="col">创建时间 / SKU</th>
      <th scope="col">采购件数</th><th scope="col">供应商</th>
      <th scope="col">估算到货日</th><th scope="col">状态</th>
      <th scope="col">操作</th></tr></thead><tbody>
      {plans.data.map(p=><tr key={p.id}>
       <td>{new Date(p.createdAt).toLocaleString('zh-CN')}
        <div className="secondary-cell">
         {skus.find(s=>s.id===p.productSkuId)?.internalSku??p.productSkuId}</div></td>
       <td>{p.orderedQuantity} · {p.currency} {p.unitCost}/件</td>
       <td>{suppliers.find(s=>s.id===p.supplierId)?.name??p.supplierId}</td>
       <td>{p.estimatedArrivalDate}（非承诺交期）</td>
       <td>{p.status==='PROPOSED'?'待人工确认':p.status==='CONVERTED'
        ?'已生成采购草稿':'已取消'}</td>
       <td><div className="sandbox-actions">
        {p.status==='PROPOSED'&&<>
         <button type="button" className="primary-button" disabled={action.isPending}
          onClick={()=>{if(window.confirm('重新检查库存与采购规则，生成本地采购单 DRAFT？还需另外提交和双人审批。'))
           action.mutate({id:p.id,kind:'convert'})}}>生成采购草稿</button>
         <button type="button" className="text-button" disabled={action.isPending}
          onClick={()=>{if(window.confirm('确认取消此采购计划？同一库存证据不能重新创建计划。'))
           action.mutate({id:p.id,kind:'cancel'})}}>取消计划</button>
        </>}
        {p.purchaseOrderId&&<button className="refresh-button" type="button"
         onClick={()=>onConverted(p.purchaseOrderId!)}>查看采购草稿</button>}
       </div></td>
      </tr>)}</tbody></table>
    </div>}
  </>}
  {message&&<p className="panel-caption" role="status">{message}</p>}
  {failure&&<p className="setup-error" role="alert">{failure}</p>}
 </section>
}
