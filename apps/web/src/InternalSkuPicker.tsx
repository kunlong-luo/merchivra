import {useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {fetchSkusPage,type Sku} from './lib/catalog'

/** Owner-only SKU master-data selector. Reuses /catalog/skus?prefix&after&limit.
 * Explicitly typed selection; only a row in the loaded current page is accepted.
 */
export default function InternalSkuPicker({tenantId,id,label,value,onChange,
 activeOnly=true}:{tenantId:string;id:string;label:string;value:string;
 onChange:(id:string,sku?:Sku)=>void;activeOnly?:boolean}){
 const [draft,setDraft]=useState('')
 const [prefix,setPrefix]=useState('')
 const [afters,setAfters]=useState<string[]>([''])
 const after=afters.at(-1)??''
 const page=useQuery({
  queryKey:['catalog',tenantId,'sku-page-v91',prefix,after],
  queryFn:()=>fetchSkusPage(prefix,after,100),
  staleTime:15000,retry:false
 })
 const rows=page.data??[]
 const available=rows.filter(s=>!activeOnly||s.status==='ACTIVE')
 const valid=available.some(s=>s.id===value)
 function submitSearch(){
  if(draft.trim().length>200)return
  setPrefix(draft.trim());setAfters(['']);onChange('')
 }
 function move(next:boolean){
  if(next&&rows.length!==100)return
  setAfters(prev=>next?[...prev,rows.at(-1)!.internalSku]:
   prev.length>1?prev.slice(0,-1):prev)
  onChange('')
 }
 return <div className="core-tools" aria-label={label+' 服务端全库选择'}>
  <div className="core-search-field">
   <label htmlFor={id+'-prefix'}>内部 SKU 前缀检索（本租户所有已入库商品）</label>
   <input id={id+'-prefix'} type="search" maxLength={200}
    autoComplete="off" placeholder="例如 INTERNAL-"
    value={draft} onChange={e=>setDraft(e.target.value)}
    onKeyDown={e=>{if(e.key==='Enter'){e.preventDefault();submitSearch()}}}/>
   <button type="button" className="refresh-button" onClick={submitSearch}
    disabled={page.isFetching}>搜索 SKU</button>
   {(draft||prefix)&&<button type="button" className="text-button"
    onClick={()=>{setDraft('');setPrefix('');setAfters(['']);onChange('')}}>
    清除筛选</button>}
  </div>
  <label htmlFor={id}>{label}</label>
  <select id={id} value={value}
   disabled={page.isPending||page.isError||page.isFetching}
   onChange={e=>{
    const id=e.target.value
    if(!id){onChange('');return}
    const item=available.find(row=>row.id===id)
    if(item)onChange(item.id,item)
   }}>
   <option value="">请选择此页的{activeOnly?'已启用':'内部'} SKU</option>
   {value&&!valid&&<option disabled value={value}>
    旧选择未在当前已核验页，请重新选择
   </option>}
   {available.map(s=><option value={s.id} key={s.id}>
    {s.internalSku} · {s.displayName}{s.status==='ARCHIVED'?' · 已归档':''}
   </option>)}
  </select>
  {page.isPending?<p className="setup-hint" role="status">正在读取内部 SKU…</p>:
   page.isError?<p className="setup-error" role="alert">
    {page.error instanceof Error?page.error.message:'无法读取 SKU，不能提交旧选择'}</p>:
   <div className="ops-toolbar" role="group" aria-label={label+' 分页'}>
    <button type="button" className="refresh-button"
     disabled={afters.length===1||page.isFetching}
     onClick={()=>move(false)}>上一页</button>
    <span role="status">第 {afters.length} 页 · {rows.length} 条
     {activeOnly?'（已归档不可选）':''}</span>
    <button type="button" className="refresh-button"
     disabled={rows.length!==100||page.isFetching}
     onClick={()=>move(true)}>检查下一页</button>
   </div>}
  <p className="setup-hint">每页最多 100 条。满页仅表示**可能**还有下一页，
   不能把当前页的数量当作本租户全部 SKU 数。
   搜索/翻页会取消之前的操作选择；服务端写入时仍验证 SKU 状态和租户范围。</p>
 </div>
}
