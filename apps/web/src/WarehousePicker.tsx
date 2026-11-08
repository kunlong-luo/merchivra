import {useEffect,useState,type FormEvent} from 'react'
import {useQuery} from '@tanstack/react-query'
import {fetchWarehouseItem,fetchWarehousesPage,type LocalWarehouse} from './lib/purchasing'
import CursorPageControls from './CursorPageControls'

/**
 * Shared V114 local warehouse selector for receipt/lot/transfer operations.
 * Only a warehouse verified in the tenant's ACTIVE page (or via exact lookup)
 * can remain selected. Search, scope, paging and destination exclusion are
 * intentionally fail-closed and never trust the legacy first-200 list.
 */
export default function WarehousePicker({tenantId,id,label,value,onChange,excludeId}:{
 tenantId:string;id:string;label:string;value:string;
 onChange:(warehouseId:string)=>void;excludeId?:string
}){
 const [draft,setDraft]=useState('')
 const [prefix,setPrefix]=useState('')
 const [cursors,setCursors]=useState<(string|null)[]>([null])
 const cursor=cursors.at(-1)??null
 const page=useQuery({
  queryKey:['purchasing',tenantId,'warehouses-page','ACTIVE',prefix,cursor],
  queryFn:()=>fetchWarehousesPage({limit:50,status:'ACTIVE',prefix,cursor}),
  retry:false
 })
 // Allows a freshly created/externally selected warehouse to remain visible
 // even when its code is beyond the current page, but checks tenant & status.
 const exact=useQuery({
  queryKey:['purchasing',tenantId,'warehouse-item',value],
  queryFn:()=>fetchWarehouseItem(value),enabled:!!value,retry:false
 })
 const rows=page.data?.items??[]
 const known=exact.data?.id===value&&exact.data.status==='ACTIVE'&&value!==excludeId
 const available=rows.filter(x=>x.status==='ACTIVE'&&x.id!==excludeId)
 useEffect(()=>{
  if(value&&(value===excludeId||exact.isError||
    (exact.isSuccess&&!known)))onChange('')
 },[value,excludeId,exact.isError,exact.isSuccess,known,onChange])
 function apply(e:FormEvent){
  e.preventDefault()
  const next=draft.trim().toUpperCase()
  if(!/^[A-Z0-9_-]{0,60}$/.test(next))return
  setPrefix(next);setCursors([null]);onChange('')
 }
 function clear(){
  setDraft('');setPrefix('');setCursors([null]);onChange('')
 }
 return <div className="core-tools" aria-label={label+' 全库仓库选择'}>
  <form onSubmit={apply} className="v107-search-form">
   <label htmlFor={id+'-search'}>仓库编号前缀（服务器全库）</label>
   <input id={id+'-search'} type="search" value={draft}
    maxLength={60} pattern="[A-Za-z0-9_-]*" autoComplete="off"
    placeholder="例如 WH-" onChange={e=>setDraft(e.target.value)}/>
   <button type="submit" className="refresh-button"
    disabled={page.isFetching}>查询</button>
   {(draft||prefix)&&<button type="button" className="text-button"
    onClick={clear}>清除筛选</button>}
  </form>
  <label htmlFor={id}>{label}</label>
  <select id={id} value={value}
   disabled={page.isPending||page.isFetching||page.isError||
    (!!value&&exact.isPending)}
   onChange={e=>{
    const id=e.target.value
    if(!id){onChange('');return}
    const row=available.find(w=>w.id===id)
    if(row)onChange(row.id)
   }}>
   <option value="">请选择当前页的有效仓库</option>
   {!!value&&known&&!available.some(w=>w.id===value)&&
    <option value={value}>{exact.data!.code} · {exact.data!.name}（已验证选中）</option>}
   {!!value&&!known&&<option value={value} disabled>正在核验仓库选择</option>}
   {available.map(w=><option value={w.id} key={w.id}>
    {w.code} · {w.name}</option>)}
  </select>
  {page.isError?<p role="alert" className="setup-error">
   无法读取仓库目录：{String(page.error)}</p>:
   page.isPending?<p className="setup-hint">正在读取仓库目录…</p>:null}
  {!!value&&exact.isError&&<p role="alert" className="setup-error">
   该仓库不在当前租户可选范围内，已取消选择。</p>}
  {!!page.data&&<CursorPageControls label={label} page={cursors.length}
   busy={page.isFetching} hasMore={page.data.hasMore}
   nextCursor={page.data.nextCursor}
   onPrev={()=>{setCursors(v=>v.length>1?v.slice(0,-1):v);onChange('')}}
   onNext={next=>{setCursors(v=>[...v,next]);onChange('')}}/>}
  <p className="setup-hint">每页最多 50 个有效仓库；可以检索第 201 个及之后的仓库。
   换页或修改查询会取消旧选择；实际提交时服务端再次检查租户、状态和仓库。</p>
 </div>
}

/** Read-only full directory; archive status is a view filter, not RBAC. */
export function WarehouseDirectoryTable({tenantId}:{tenantId:string}){
 const [draft,setDraft]=useState('')
 const [prefix,setPrefix]=useState('')
 const [status,setStatus]=useState<'ALL'|'ACTIVE'|'ARCHIVED'>('ALL')
 const [cursors,setCursors]=useState<(string|null)[]>([null])
 const cursor=cursors.at(-1)??null
 const query=useQuery({
  queryKey:['purchasing',tenantId,'warehouses-page',status,prefix,cursor],
  queryFn:()=>fetchWarehousesPage({limit:50,status,prefix,cursor}),retry:false
 })
 function search(e:FormEvent){
  e.preventDefault()
  const term=draft.trim().toUpperCase()
  if(!/^[A-Z0-9_-]{0,60}$/.test(term))return
  setPrefix(term);setCursors([null])
 }
 const rows:LocalWarehouse[]=query.data?.items??[]
 return <div className="core-tools">
  <div className="v107-list-toolbar">
   <form className="v107-search-form" onSubmit={search}>
    <label htmlFor="v114-directory-search">仓库编码前缀（服务器全库）</label>
    <input id="v114-directory-search" type="search" value={draft}
     maxLength={60} pattern="[A-Za-z0-9_-]*"
     onChange={e=>setDraft(e.target.value)}/>
    <button type="submit" className="refresh-button">查询</button>
   </form>
   <label htmlFor="v114-directory-status">状态</label>
   <select id="v114-directory-status" value={status} onChange={e=>{
    const next=e.target.value
    if(next==='ALL'||next==='ACTIVE'||next==='ARCHIVED'){
     setStatus(next);setCursors([null])
    }
   }}>
    <option value="ALL">全部</option>
    <option value="ACTIVE">已启用</option>
    <option value="ARCHIVED">已归档</option>
   </select>
  </div>
  <p className="setup-hint">第 {cursors.length} 页 · 当前页 {rows.length} 个仓库；
   当前页数量并非租户全部仓库总数。</p>
  {query.isPending?<p className="empty-state">读取仓库目录中…</p>:
   query.isError?<p className="setup-error" role="alert">
    无法读取仓库目录：{String(query.error)}</p>:
   rows.length===0?<p className="empty-state">无匹配仓库。</p>:
   <div className="table-scroll" role="region" tabIndex={0}
    aria-label="全库仓库目录">
    <table><thead><tr><th scope="col">编码</th><th scope="col">仓库</th>
     <th scope="col">状态</th></tr></thead><tbody>
     {rows.map(w=><tr key={w.id}><td>{w.code}</td><td>{w.name}</td>
      <td>{w.status}</td></tr>)}
    </tbody></table>
   </div>}
  {!!query.data&&<CursorPageControls label="仓库目录" page={cursors.length}
   busy={query.isFetching} hasMore={query.data.hasMore}
   nextCursor={query.data.nextCursor}
   onPrev={()=>setCursors(v=>v.length>1?v.slice(0,-1):v)}
   onNext={next=>setCursors(v=>[...v,next])}/>}
 </div>
}
