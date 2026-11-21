import {useEffect,useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {fetchSuppliersPage} from './lib/purchasing'

/** V111: server-scoped ACTIVE supplier picker. The selected UUID is only
 * accepted when present in the current verified page; writes recheck tenancy
 * and ACTIVE status transactionally on the backend.
 */
export default function SupplierPicker({tenantId,id,label,value,onChange}:{
 tenantId:string;id:string;label:string;value:string;onChange:(id:string)=>void
}){
 const [draft,setDraft]=useState('')
 const [prefix,setPrefix]=useState('')
 const [cursors,setCursors]=useState<(string|null)[]>([null])
 const cursor=cursors.at(-1)??null
 const page=useQuery({
  queryKey:['purchasing',tenantId,'supplier-picker',id,prefix,cursor],
  queryFn:()=>fetchSuppliersPage({limit:50,status:'ACTIVE',prefix,cursor}),
  retry:false
 })
 const rows=page.data?.items??[]
 const verified=rows.some(s=>s.id===value&&s.status==='ACTIVE')
 useEffect(()=>{
  if(value&&(page.isError||(page.isSuccess&&!verified)))onChange('')
 },[value,page.isError,page.isSuccess,verified,onChange])
 function search(next:string){
  const normalized=next.trim().toUpperCase()
  if(!/^[A-Z0-9_-]{0,60}$/.test(normalized))return
  setPrefix(normalized);setCursors([null]);onChange('')
 }
 function move(next:boolean){
  if(next){
   if(!page.data?.nextCursor)return
   setCursors(v=>[...v,page.data!.nextCursor])
  }else setCursors(v=>v.length>1?v.slice(0,-1):v)
  onChange('')
 }
 return <div className="core-tools" aria-label={label+' 服务器全库选择'}>
  <div className="core-search-field">
   <label htmlFor={id+'-prefix'}>供应商编号前缀（本租户全库检索）</label>
   <input id={id+'-prefix'} type="search" value={draft} maxLength={60}
    autoComplete="off" pattern="[A-Za-z0-9_-]*"
    onChange={e=>setDraft(e.target.value)}
    onKeyDown={e=>{if(e.key==='Enter'){e.preventDefault();search(draft)}}}
    placeholder="例如 SUP-"/>
   <button type="button" className="refresh-button"
    disabled={page.isFetching} onClick={()=>search(draft)}>搜索供应商</button>
   {(prefix||draft)&&<button type="button" className="text-button"
    onClick={()=>{setDraft('');search('')}}>清除筛选</button>}
  </div>
  <label htmlFor={id}>{label}</label>
  <select id={id} value={value} disabled={page.isPending||page.isFetching||page.isError}
   onChange={e=>{
    const chosen=rows.find(s=>s.id===e.target.value&&s.status==='ACTIVE')
    onChange(chosen?.id??'')
   }}>
   <option value="">选择当前页已启用供应商</option>
   {value&&!verified&&<option value={value} disabled>旧选择未核验，请重新检索</option>}
   {rows.map(s=><option key={s.id} value={s.id}>{s.code} · {s.name}</option>)}
  </select>
  {page.isPending?<p className="setup-hint" role="status">读取供应商中…</p>:
   page.isError?<p className="setup-error" role="alert">供应商目录读取失败，禁止使用旧选择</p>:
   <div className="ops-toolbar" role="group" aria-label={label+' 翻页'}>
    <button type="button" className="refresh-button"
     disabled={cursors.length===1||page.isFetching} onClick={()=>move(false)}>上一页</button>
    <span role="status">第 {cursors.length} 页 · 当前 {rows.length} 条（非全库总数）</span>
    <button type="button" className="refresh-button"
     disabled={!page.data?.hasMore||!page.data.nextCursor||page.isFetching}
     onClick={()=>move(true)}>下一页</button>
   </div>}
  <p className="setup-hint">每页最多 50 条；搜索和翻页会清除之前的选择。
   采购草稿及采购规则写入时，服务端仍验证供应商归属和启用状态。</p>
 </div>
}
