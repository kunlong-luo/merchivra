import {useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {fetchSkuCosts,addSkuCost} from './lib/costs'
import type {Sku} from './lib/catalog'

export default function SkuCostManager({tenantId,skus}:{
 tenantId:string;skus:Sku[]
}){
 const client=useQueryClient()
 const [choice,setChoice]=useState(''),[amount,setAmount]=useState('')
 const [currency,setCurrency]=useState('USD'),[date,setDate]=useState('')
 const [message,setMessage]=useState(''),[failure,setFailure]=useState('')
 const id=skus.some(s=>s.id===choice)?choice:(skus[0]?.id??'')
 const history=useQuery({queryKey:['cost-history',tenantId,id],
  queryFn:()=>fetchSkuCosts(id),enabled:!!id,retry:false})
 const saved=useMutation({mutationFn:()=>addSkuCost(id,amount,currency,date),
  onSuccess:()=>{setMessage('新版本成本已登记，原币和旧生效期间保留')
   setFailure('');setAmount('');void client.invalidateQueries({queryKey:['cost-history',tenantId,id]})},
  onError:(e:Error)=>setFailure(e.message)})
 function save(e:FormEvent){e.preventDefault();if(id)saved.mutate()}
 return <section className="panel full-panel" aria-label="商品历史成本">
  <div className="panel-heading"><div><h2>商品历史成本</h2>
   <p>按内部 SKU、原币种及生效日追加成本版本，不视为 Amazon 已入账费用或净利润。</p>
  </div></div>
  <form className="team-invite-form" onSubmit={save}>
   <label htmlFor="sku-cost-id">内部 SKU</label>
   <select id="sku-cost-id" value={id} onChange={e=>setChoice(e.target.value)}>
    {skus.map(s=><option key={s.id} value={s.id}>{s.internalSku}</option>)}
   </select>
   <label htmlFor="sku-cost-amount">单位成本</label>
   <input id="sku-cost-amount" required value={amount} onChange={e=>setAmount(e.target.value)}
      inputMode="decimal" pattern="[0-9]{1,14}(\\.[0-9]{1,6})?"
      placeholder="12.500000"/>
   <label htmlFor="sku-cost-currency">成本币种（ISO 4217）</label>
   <select id="sku-cost-currency" value={currency} onChange={e=>setCurrency(e.target.value)}>
    {['USD','EUR','GBP','JPY','CAD','AUD','CNY','MXN','SGD'].map(c=>
      <option key={c} value={c}>{c}</option>)}
   </select>
   <label htmlFor="sku-cost-from">开始生效日期</label>
   <input id="sku-cost-from" type="date" value={date} required
     onChange={e=>setDate(e.target.value)}/>
   <button className="primary-button" type="submit" disabled={!id||saved.isPending||
     !/^[0-9]{1,14}(\\.[0-9]{1,6})?$/.test(amount)||!date}>添加成本版本</button>
  </form>
  {history.isPending?<p className="empty-state">读取成本历史…</p>:
   history.isError?<p className="setup-error" role="alert">{String(history.error)}</p>:
   !history.data?.length?<p className="empty-state">该 SKU 尚无有效日期成本记录。</p>:
   <div className="table-scroll" role="region" tabIndex={0} aria-label="成本生效历史">
    <table><thead><tr><th scope="col">生效开始</th><th scope="col">生效结束（不含）</th>
      <th scope="col">原币单位成本</th></tr></thead><tbody>
     {history.data.map(c=><tr key={c.id}><td>{c.effectiveFrom}</td>
      <td>{c.effectiveTo??'当前持续'}</td><td>{c.currency} {c.unitCost}</td></tr>)}
    </tbody></table>
   </div>}
  {message&&<p className="panel-caption" role="status">{message}</p>}
  {failure&&<p className="setup-error" role="alert">{failure}</p>}
 </section>
}
