import {useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {fetchCoreListings} from './lib/core'
import {getReplenishmentSuggestions,saveReplenishmentPolicy,removeReplenishmentPolicy}
 from './lib/replenishment'

const labels:Record<string,string>={
 NO_OBSERVATION:'无库存观测，不能计算',STALE_OBSERVATION:'库存观测超 48 小时或时间异常',
 INCOMPLETE_OBSERVATION:'库存数据不完整，不能计算',REORDER:'可考虑补货',HEALTHY:'未触发补货点'
}
export default function ReplenishmentWorkspace({tenantId,marketplaceId}:{
 tenantId:string;marketplaceId:string
}){
 const client=useQueryClient()
 const [sellerSku,setSellerSku]=useState(''),[reorder,setReorder]=useState(3)
 const [target,setTarget]=useState(20),[lead,setLead]=useState(14)
 const [message,setMessage]=useState(''),[failure,setFailure]=useState('')
 const listings=useQuery({queryKey:['replenishment','listings',tenantId,marketplaceId],
  queryFn:()=>fetchCoreListings(marketplaceId),retry:false})
 const suggestions=useQuery({queryKey:['replenishment','suggestions',tenantId,marketplaceId],
  queryFn:()=>getReplenishmentSuggestions(marketplaceId),retry:false})
 const refresh=()=>void client.invalidateQueries({queryKey:['replenishment','suggestions',tenantId,marketplaceId]})
 const saved=useMutation({mutationFn:()=>saveReplenishmentPolicy(marketplaceId,sellerSku,
  reorder,target,lead),onSuccess:()=>{setMessage('补货策略已保存');setFailure('');refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 const removed=useMutation({mutationFn:(sku:string)=>removeReplenishmentPolicy(marketplaceId,sku),
  onSuccess:()=>{setMessage('补货策略已删除');setFailure('');refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 function submit(e:FormEvent){e.preventDefault();if(sellerSku)saved.mutate()}
 return <section className="panel full-panel" aria-label="SKU 补货建议">
   <div className="panel-heading"><div><h2>SKU 补货策略与建议</h2>
    <p>仅参考最近 48 小时的已观测 FBA 可售数量。未知/过期不视为 0；建议不考虑未核验在途，不自动采购或修改 Amazon。</p></div>
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
         <button type="button" className="text-button" disabled={removed.isPending}
           onClick={()=>{if(window.confirm('删除此 SKU 的补货策略？'))
             removed.mutate(s.policy.sellerSku)}}>删除</button>
       </div></td></tr>)}
     </tbody></table>
    </div>}
   {message&&<p className="panel-caption" role="status">{message}</p>}
   {failure&&<p className="setup-error" role="alert">{failure}</p>}
 </section>
}
