import {useEffect,useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {fetchWarehouseItem,fetchWarehousesPage,type LocalWarehouse} from './lib/purchasing'

/** Full-tenant warehouse picker, backed by an ACTIVE-only server keyset.
 * Exact server lookup validates an externally selected/deep-linked warehouse.
 * Search or pagination always clears the previous selection.
 */
export default function WarehousePicker({tenantId,id,label,value,onChange,
 activeOnly=true,excludeId=''}:{
 tenantId:string;id:string;label:string;value:string;
 onChange:(warehouseId:string,warehouse?:LocalWarehouse)=>void;
 activeOnly?:boolean;excludeId?:string
}){
 const [draft,setDraft]=useState('')
 const [prefix,setPrefix]=useState('')
 const [cursors,setCursors]=useState<(string|null)[]>([null])
 const cursor=cursors.at(-1)??null
 const status=activeOnly?'ACTIVE':'ALL'
 const page=useQuery({
  queryKey:['purchasing',tenantId,'warehouses-page',status,prefix,cursor],
  queryFn:()=>fetchWarehousesPage({limit:50,status,prefix,cursor}),retry:false
 })
 const exact=useQuery({
  queryKey:['purchasing',tenantId,'warehouse-exact',value],
  queryFn:()=>fetchWarehouseItem(value),enabled:!!value,retry:false
 })
 const rows=page.data?.items??[]
 const selected=exact.data?.id===value?exact.data:undefined
 const valid=!!selected&&(!activeOnly||selected.status==='ACTIVE')&&
  selected.id!==excludeId
 useEffect(()=>{
  if(value&&exact.isFetched&&!exact.isFetching&&(!valid||exact.isError))
   onChange('')
 },[value,exact.isFetched,exact.isFetching,exact.isError,valid,onChange])
 function resetSelection(){if(value)onChange('')}
 function apply(){
  const term=draft.trim().toUpperCase()
  if(!/^[A-Z0-9_-]{0,60}$/.test(term))return
  setPrefix(term)
  setCursors([null])
  resetSelection()
 }
 function move(next:boolean){
  if(next&&!page.data?.nextCursor)return
  setCursors(v=>next?[...v,page.data!.nextCursor!]:
   v.length>1?v.slice(0,-1):v)
  resetSelection()
 }
 const disabled=page.isPending||page.isError||page.isFetching||
  (!!value&&(exact.isPending||exact.isFetching||exact.isError||!valid))
 return <div className="core-tools" aria-label={label+' 全库可检索选择'}>
  <div className="core-search-field">
   <label htmlFor={id+'-prefix'}>仓库编号前缀（服务器全租户搜索）</label>
   <input id={id+'-prefix'} type="search" value={draft} maxLength={60}
    pattern="[A-Za-z0-9_-]*" autoComplete="off" placeholder="例如 WH-"
    onChange={e=>setDraft(e.target.value)}
    onKeyDown={e=>{if(e.key==='Enter'){e.preventDefault();apply()}}}/>
   <button type="button" className="refresh-button" disabled={page.isFetching}
    onClick={apply}>搜索仓库</button>
   {(draft||prefix)&&<button type="button" className="text-button"
    onClick={()=>{setDraft('');setPrefix('');setCursors([null]);resetSelection()}}>
    清除筛选</button>}
  </div>
  <label htmlFor={id}>{label}</label>
  <select id={id} value={valid?value:''} disabled={disabled}
   onChange={e=>{
    const next=e.target.value
    if(!next){onChange('');return}
    const item=rows.find(w=>w.id===next)
    if(item&&(!activeOnly||item.status==='ACTIVE')&&item.id!==excludeId)
     onChange(item.id,item)
   }}>
   <option value="">选择当前页{activeOnly?'已启用':''}仓库</option>
   {valid&&!rows.some(w=>w.id===selected!.id)&&
    <option value={selected!.id}>{selected!.code} · {selected!.name}（已精确核验）</option>}
   {rows.filter(w=>(!activeOnly||w.status==='ACTIVE')&&w.id!==excludeId)
    .map(w=><option value={w.id} key={w.id}>
     {w.code} · {w.name}{w.status==='ARCHIVED'?'（已归档）':''}</option>)}
  </select>
  {page.isError?<p className="setup-error" role="alert">仓库目录读取失败：
   {String(page.error)}</p>:
   <div className="ops-toolbar" role="group" aria-label={label+' 分页'}>
    <button type="button" className="refresh-button"
     disabled={cursors.length<=1||page.isFetching} onClick={()=>move(false)}>
     上一页</button>
    <span role="status">第 {cursors.length} 页 · {rows.length} 条</span>
    <button type="button" className="refresh-button"
     disabled={!page.data?.hasMore||!page.data.nextCursor||page.isFetching}
     onClick={()=>move(true)}>下一页</button>
   </div>}
  {value&&exact.isError&&<p role="alert" className="setup-error">
   之前选择的仓库无法在当前租户核验，请重新选择。</p>}
  <p className="setup-hint">每页最多 50 条，搜索/翻页会清除之前的选择。
   这里不是租户全部仓库数；写操作始终由服务器重新核验租户和 ACTIVE 状态。</p>
 </div>
}
