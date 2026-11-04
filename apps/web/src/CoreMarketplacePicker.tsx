import {useEffect,useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {fetchCoreListingsPage,fetchCoreOrdersPage} from './lib/core'
import {listingChoices,orderChoices,onCurrentPickerPage,
 type ScopedChoicePage} from './lib/scoped-core-picker-v90'

/** Reuses V86/V87 bounded, actor-scoped GET APIs; no new business endpoint.
 * Unlike initial 50-row select fields, it searches every persisted record.
 */
export default function CoreMarketplacePicker({tenantId,marketplaceId,kind,id,
 label,value,onChange}:{tenantId:string;marketplaceId:string;
 kind:'LISTING'|'ORDER';id:string;label:string;
 value:string;onChange:(value:string)=>void}){
 const [draft,setDraft]=useState('')
 const [prefix,setPrefix]=useState('')
 const [cursors,setCursors]=useState<(string|null)[]>([null])
 const cursor=cursors.at(-1)??null
 const page=useQuery({
  queryKey:['core-picker-v90',tenantId,marketplaceId,kind,prefix,cursor],
  queryFn:async():Promise<ScopedChoicePage>=>kind==='LISTING'?
   listingChoices(await fetchCoreListingsPage(marketplaceId,
    'SELLER_SKU',prefix,'ALL',cursor,50)):
   orderChoices(await fetchCoreOrdersPage(marketplaceId,prefix,cursor,50)),
  enabled:!!marketplaceId,retry:false,staleTime:15000
 })
 const choices=page.data?.choices??[]
 const verified=onCurrentPickerPage(page.data,value)
 useEffect(()=>{
  if(value&&(page.isError||(page.isSuccess&&!verified)))onChange('')
 },[value,page.isError,page.isSuccess,verified,onChange])
 function applySearch(){
  if(draft.trim().length>80)return
  setPrefix(draft.trim());setCursors([null]);onChange('')
 }
 function changePage(next:boolean){
  if(next&&!page.data?.nextCursor)return
  setCursors(prev=>next?[...prev,page.data!.nextCursor]:
   prev.length>1?prev.slice(0,-1):prev)
  onChange('')
 }
 return <div className="core-tools" aria-label={label+' 服务端全库选择'}>
  <div className="core-search-field">
   <label htmlFor={id+'-search'}>
    {kind==='LISTING'?'Seller SKU':'外部订单号'}全库前缀搜索 · 当前 Marketplace
   </label>
   <input id={id+'-search'} type="search" value={draft} maxLength={80}
    autoComplete="off" placeholder={kind==='LISTING'?'例如 SKU-':'例如 123-'}
    onChange={e=>setDraft(e.target.value)}
    onKeyDown={e=>{if(e.key==='Enter'){e.preventDefault();applySearch()}}}/>
   <button type="button" className="refresh-button" onClick={applySearch}
    disabled={!marketplaceId||page.isFetching}>搜索</button>
   {(prefix||draft)&&<button type="button" className="text-button"
    onClick={()=>{setPrefix('');setDraft('');setCursors([null]);onChange('')}}>
    清除筛选</button>}
  </div>
  <label htmlFor={id}>{label}</label>
  <select id={id} value={value}
   disabled={!marketplaceId||page.isError||page.isPending}
   onChange={e=>{
    const v=e.target.value
    if(!v||choices.some(c=>c.value===v))onChange(v)
   }}>
   <option value="">请选择已入库{kind==='LISTING'?' Listing':'订单'}</option>
   {value&&!verified&&<option value={value} disabled>
    原选择不在当前已核验页 · 请重新搜索确认
   </option>}
   {choices.map(c=><option key={c.value} value={c.value}>{c.label}</option>)}
  </select>
  {page.isPending?<p className="setup-hint" role="status">读取站点数据中…</p>:
   page.isError?<p className="setup-error" role="alert">
    {page.error instanceof Error?page.error.message:'源数据不可用'}
    · 未成功加载，不允许选择此页数据。</p>:
   <div className="ops-toolbar" role="group" aria-label={label+' 翻页'}>
    <button className="refresh-button" type="button"
     disabled={cursors.length===1||page.isFetching}
     onClick={()=>changePage(false)}>上一页</button>
    <span role="status">第 {cursors.length} 页 · {choices.length} 条 ·
     {page.data?.hasMore?' 后面还有记录':' 已到最后一页'}</span>
    <button className="refresh-button" type="button"
     disabled={!page.data?.hasMore||!page.data.nextCursor||page.isFetching}
     onClick={()=>changePage(true)}>下一页</button>
   </div>}
  <p className="setup-hint">服务端查询当前站点全部已入库
   {kind==='LISTING'?' Listing（含待映射及冲突）':'订单（含 MOCK/未核验来源）'}。
   搜索为订单号/Seller SKU 前缀，不是页面内包含匹配。最多 50 条/页；
   切换搜索或页码会取消本次选择，必须重新确认业务来源。
   {value&&!verified?'当前值不在服务端已加载页，不能把它视为新选择。':''}
  </p>
 </div>
}
