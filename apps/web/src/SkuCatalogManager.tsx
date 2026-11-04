import {useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {createSku,fetchSkusPage,mapListing,unmapListing,updateSku,type Sku} from './lib/catalog'
import InternalSkuPicker from './InternalSkuPicker'
import type {CoreListing} from './lib/core'
import SkuCostManager from './SkuCostManager'

/** Explicit tenant-scoped master-data management, never a remote Amazon write. */
export default function SkuCatalogManager({tenantId,marketplaceId,listings}:{
 tenantId:string;marketplaceId:string;listings:CoreListing[]
}){
 const client=useQueryClient()
 const [code,setCode]=useState(''),[name,setName]=useState('')
 const [editing,setEditing]=useState(''),[editName,setEditName]=useState('')
 const [selectedListing,setSelectedListing]=useState(''),[selectedSku,setSelectedSku]=useState('')
 const [message,setMessage]=useState(''),[error,setError]=useState('')
 const [skuPrefixDraft,setSkuPrefixDraft]=useState(''),[skuPrefix,setSkuPrefix]=useState('')
 const [skuAfters,setSkuAfters]=useState<string[]>([''])
 const skuAfter=skuAfters.at(-1)??''
 const skus=useQuery({
  queryKey:['catalog',tenantId,'sku-page-v91',skuPrefix,skuAfter],
  queryFn:()=>fetchSkusPage(skuPrefix,skuAfter,100),
  retry:false,staleTime:30000
 })
 const refresh=()=>{void client.invalidateQueries({queryKey:['catalog',tenantId]})
   void client.invalidateQueries({queryKey:['core',tenantId,'listings',marketplaceId]})
    void client.invalidateQueries({queryKey:['core',tenantId,'listings-v87',marketplaceId]})}
 const created=useMutation({mutationFn:()=>createSku(code,name),onSuccess:()=>{
   setMessage('内部 SKU 已创建');setError('');setCode('');setName('');refresh()
 },onError:(e:Error)=>setError(e.message)})
 const edited=useMutation({mutationFn:({item,displayName,status}:{
   item:Sku;displayName:string;status:'ACTIVE'|'ARCHIVED'
 })=>updateSku(item.id,displayName,status),onSuccess:()=>{
   setMessage('SKU 已更新');setError('');setEditing('');refresh()
 },onError:(e:Error)=>setError(e.message)})
 const linked=useMutation({mutationFn:()=>mapListing(selectedListing,selectedSku),onSuccess:()=>{
   setMessage('映射已保存，原版本保留');setError('');refresh()
 },onError:(e:Error)=>setError(e.message)})
 const unlinked=useMutation({mutationFn:()=>unmapListing(selectedListing),onSuccess:()=>{
   setMessage('映射已解除，历史仍保留');setError('');refresh()
 },onError:(e:Error)=>setError(e.message)})
 function onCreate(e:FormEvent){e.preventDefault();created.mutate()}
 function onMap(e:FormEvent){e.preventDefault();if(selected&&selectedSku)linked.mutate()}
 const selected=listings.find(l=>l.id===selectedListing)
 return <section className="panel full-panel" aria-label="内部 SKU 管理">
  <div className="panel-heading"><div><h2>内部 SKU 与 Listing 映射</h2>
  <p>管理员维护内部商品编码、归档和版本化映射；不会修改 Amazon 端商品。</p></div>
  <button type="button" className="refresh-button" onClick={refresh}>刷新</button></div>
  <div className="core-search-field">
   <label htmlFor="catalog-sku-page-search">内部 SKU 前缀（本租户全库）</label>
   <input type="search" id="catalog-sku-page-search" maxLength={200}
    autoComplete="off" value={skuPrefixDraft}
    onChange={e=>setSkuPrefixDraft(e.target.value)}
    onKeyDown={e=>{if(e.key==='Enter'){e.preventDefault();setSkuPrefix(skuPrefixDraft.trim());setSkuAfters(['']);setEditing('')}}}/>
   <button type="button" className="refresh-button" onClick={()=>{
    setSkuPrefix(skuPrefixDraft.trim());setSkuAfters(['']);setEditing('')
   }}>检索内部 SKU</button>
   {(skuPrefix||skuPrefixDraft)&&<button type="button" className="text-button"
    onClick={()=>{setSkuPrefix('');setSkuPrefixDraft('');setSkuAfters(['']);setEditing('')}}>清除</button>}
  </div>
  <form className="team-invite-form" onSubmit={onCreate}>
   <label htmlFor="sku-create-code">内部 SKU</label>
   <input id="sku-create-code" value={code} onChange={e=>setCode(e.target.value)}
      required maxLength={200} pattern="[A-Za-z0-9][A-Za-z0-9._/-]{0,199}" autoComplete="off"/>
   <label htmlFor="sku-create-name">商品名称</label>
   <input id="sku-create-name" value={name} onChange={e=>setName(e.target.value)}
      required minLength={2} maxLength={300}/>
   <button className="primary-button" type="submit" disabled={created.isPending}>创建内部 SKU</button>
  </form>
  {skus.isPending?<p className="empty-state">正在读取 SKU…</p>:
    skus.isError?<p className="setup-error" role="alert">{String(skus.error)}</p>:
    <div className="table-scroll" role="region" tabIndex={0} aria-label="内部 SKU 列表">
     <table><thead><tr><th scope="col">内部 SKU</th><th scope="col">名称</th>
     <th scope="col">状态</th><th scope="col">操作</th></tr></thead>
     <tbody>{(skus.data??[]).map(s=><tr key={s.id}>
      <td className="strong-cell">{s.internalSku}</td>
      <td>{editing===s.id?<input aria-label="修改名称" value={editName}
          onChange={e=>setEditName(e.target.value)} maxLength={300}/>:s.displayName}</td>
      <td>{s.status==='ACTIVE'?'启用':'已归档'}</td><td><div className="sandbox-actions">
       {editing===s.id?<button className="refresh-button" type="button"
          disabled={edited.isPending||editName.trim().length<2}
          onClick={()=>edited.mutate({item:s,displayName:editName,status:s.status})}>保存</button>:
        <button type="button" className="refresh-button" onClick={()=>{
          setEditing(s.id);setEditName(s.displayName)}}>改名</button>}
       <button className="text-button" type="button" disabled={edited.isPending}
         onClick={()=>{if(window.confirm('切换 SKU 状态？归档前须解绑现有 Listing。'))
          edited.mutate({item:s,displayName:s.displayName,
            status:s.status==='ACTIVE'?'ARCHIVED':'ACTIVE'})}}>
         {s.status==='ACTIVE'?'归档':'恢复'}</button>
      </div></td></tr>)}</tbody></table>
    </div>}
  <div className="ops-toolbar" role="group" aria-label="内部 SKU 服务端分页">
   <button type="button" className="refresh-button" disabled={skuAfters.length===1||skus.isFetching}
    onClick={()=>{setSkuAfters(v=>v.length>1?v.slice(0,-1):v);setEditing('')}}>上一页</button>
   <span role="status">第 {skuAfters.length} 页 · 当前 {skus.data?.length??0} 条。
    满 100 条时仅表示可能还有下一页。</span>
   <button type="button" className="refresh-button"
    disabled={skus.isFetching||skus.data?.length!==100}
    onClick={()=>{const after=skus.data?.at(-1)?.internalSku
     if(after){setSkuAfters(v=>[...v,after]);setEditing('')}}}>检查下一页</button>
  </div>
  <form className="team-invite-form" onSubmit={onMap}>
   <label htmlFor="sku-map-listing">Seller Listing</label>
   <select id="sku-map-listing" value={selectedListing}
      onChange={e=>setSelectedListing(e.target.value)}>
     <option value="">选择当前页 Listing</option>
     {listings.map(l=><option key={l.id} value={l.id}>{l.sellerSku}</option>)}
   </select>
   <InternalSkuPicker tenantId={tenantId} id="sku-map-internal"
    label="选择任意已启用内部 SKU（全库）" value={selectedSku}
    onChange={setSelectedSku}/>
   <div className="sandbox-actions">
    <button className="primary-button" disabled={!selected||!selectedSku||linked.isPending}
      type="submit">确认映射</button>
    <button className="text-button" type="button" disabled={!selected||
      selected.mappingStatus!=='ACTIVE'||unlinked.isPending}
      onClick={()=>{if(window.confirm('解绑 Listing 并保留历史？'))unlinked.mutate()}}>
      解除映射</button>
   </div>
  </form>
  {message&&<p className="panel-caption" role="status">{message}</p>}
  {error&&<p className="setup-error" role="alert">{error}</p>}
  <SkuCostManager tenantId={tenantId}/>
 </section>
}
