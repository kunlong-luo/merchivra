import {useEffect,useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import SellerSetup from './SellerSetup'
import SandboxReplay from './SandboxReplay'
import OrdersSyncPanel from './OrdersSyncPanel'
import SkuCatalogManager from './SkuCatalogManager'
import ReplenishmentWorkspace from './ReplenishmentWorkspace'
import FbaInboundPanel from './FbaInboundPanel'
import CatalogSyncPanel from './CatalogSyncPanel'
import {coreStatusLabel,formatSourceTime,sourceLabel} from './lib/core-view'
import {fetchCoreMarketplaces,fetchCoreListingsPage,fetchCoreOrdersPage,fetchCoreOrderItems,fetchCoreInventoryPage} from './lib/core'

type Section='listings'|'orders'|'inventory'
export default function CoreWorkspace({tenantId,isOwner=true,focusException}:{
 tenantId:string;isOwner?:boolean;
 focusException?:{marketplaceId:string;lineId:string}|null
}){
 const [marketplaceId,setMarketplaceId]=useState(''),[section,setSection]=useState<Section>('listings'),[search,setSearch]=useState('')
 const [selectedOrderId,setSelectedOrderId]=useState('')
 const [inventorySearch,setInventorySearch]=useState('')
 const [inventoryQualityDraft,setInventoryQualityDraft]=useState<
  'ALL'|'OBSERVED'|'PARTIAL'|'UNKNOWN'|'STALE'>('ALL')
 const [inventoryOriginDraft,setInventoryOriginDraft]=useState<
  'ALL'|'MOCK'|'UNVERIFIED'|'LIVE'>('ALL')
 const [inventoryQuality,setInventoryQuality]=useState<typeof inventoryQualityDraft>('ALL')
 const [inventoryOrigin,setInventoryOrigin]=useState<typeof inventoryOriginDraft>('ALL')
 const [inventoryCursors,setInventoryCursors]=useState<Array<string|null>>([null])
 const inventoryCursor=inventoryCursors.at(-1)??null
 const [listingSearchDraft,setListingSearchDraft]=useState('')
 const [listingFieldDraft,setListingFieldDraft]=useState<'SELLER_SKU'|'ASIN'>('SELLER_SKU')
 const [listingStatusDraft,setListingStatusDraft]=useState<
  'ALL'|'UNMAPPED'|'SUGGESTED'|'VERIFIED'|'ACTIVE'|'CONFLICT'|'SUPERSEDED'>('ALL')
 const [listingSearch,setListingSearch]=useState('')
 const [listingField,setListingField]=useState<'SELLER_SKU'|'ASIN'>('SELLER_SKU')
 const [listingStatus,setListingStatus]=useState<typeof listingStatusDraft>('ALL')
 const [listingCursors,setListingCursors]=useState<Array<string|null>>([null])
 const listingCursor=listingCursors.at(-1)??null
 const [orderSearchDraft,setOrderSearchDraft]=useState('')
 const [orderSearch,setOrderSearch]=useState('')
 const [orderCursors,setOrderCursors]=useState<Array<string|null>>([null])
 const orderCursor=orderCursors.at(-1)??null
 const marketplaces=useQuery({queryKey:['core','marketplaces',tenantId],queryFn:fetchCoreMarketplaces,staleTime:30000,retry:1})
 useEffect(()=>{if(!marketplaces.data?.some(m=>m.id===marketplaceId))setMarketplaceId(marketplaces.data?.[0]?.id??'')},[marketplaces.data,marketplaceId])
 useEffect(()=>{
  if(!isOwner||!focusException||!marketplaces.data)return
  if(!marketplaces.data.some(m=>m.id===focusException.marketplaceId))return
  setMarketplaceId(focusException.marketplaceId)
  setSection('inventory');setSearch('');setSelectedOrderId('')
 },[focusException?.marketplaceId,focusException?.lineId,isOwner,marketplaces.data])
 const listings=useQuery({
  queryKey:['core',tenantId,'listings-v87',marketplaceId,listingField,
   listingStatus,listingSearch,listingCursor],
  queryFn:()=>fetchCoreListingsPage(marketplaceId,listingField,
   listingSearch,listingStatus,listingCursor),
  enabled:!!marketplaceId&&section==='listings',staleTime:30000,retry:1
 })
 const orders=useQuery({
  queryKey:['core',tenantId,'orders-v86',marketplaceId,orderSearch,orderCursor],
  queryFn:()=>fetchCoreOrdersPage(marketplaceId,orderSearch,orderCursor),
  enabled:!!marketplaceId&&section==='orders',staleTime:30000,retry:1
 })
 const detail=useQuery({queryKey:['core',tenantId,'order-items',marketplaceId,selectedOrderId],
  queryFn:()=>fetchCoreOrderItems(marketplaceId,selectedOrderId),
  enabled:!!marketplaceId&&!!selectedOrderId&&section==='orders',staleTime:30000,retry:1})
 const inventory=useQuery({
  queryKey:['core',tenantId,'inventory-v88',marketplaceId,
   inventorySearch,inventoryQuality,inventoryOrigin,inventoryCursor],
  queryFn:()=>fetchCoreInventoryPage(marketplaceId,inventorySearch,
   inventoryQuality,inventoryOrigin,inventoryCursor),
  enabled:!!marketplaceId&&section==='inventory',staleTime:30000,retry:1
 })
 const active=marketplaces.data?.find(m=>m.id===marketplaceId)
 const filteredListings=listings.data?.items??[]
 const filteredOrders=orders.data?.items??[]
 const filteredInventory=inventory.data?.items??[]
 function changeSection(id:Section){setSection(id);setSearch('');setSelectedOrderId('')}
 function applyInventorySearch(){
  if(search.trim().length>80)return
  setInventorySearch(search.trim())
  setInventoryQuality(inventoryQualityDraft)
  setInventoryOrigin(inventoryOriginDraft)
  setInventoryCursors([null])
 }
 function nextInventoryPage(){
  if(!inventory.data?.nextCursor)return
  setInventoryCursors(current=>[...current,inventory.data!.nextCursor])
 }
 function previousInventoryPage(){
  setInventoryCursors(current=>current.length>1?current.slice(0,-1):current)
 }
 function resetInventorySearch(){
  setSearch('');setInventorySearch('')
  setInventoryQualityDraft('ALL');setInventoryQuality('ALL')
  setInventoryOriginDraft('ALL');setInventoryOrigin('ALL')
  setInventoryCursors([null])
 }
 function applyListingSearch(){
  const normalized=listingSearchDraft.trim()
  if(normalized.length>80)return
  setListingSearch(normalized)
  setListingField(listingFieldDraft)
  setListingStatus(listingStatusDraft)
  setListingCursors([null])
 }
 function nextListingPage(){
  if(!listings.data?.nextCursor)return
  setListingCursors(current=>[...current,listings.data!.nextCursor])
 }
 function previousListingPage(){
  setListingCursors(current=>current.length>1?current.slice(0,-1):current)
 }
 function applyOrderSearch(){
  const normalized=orderSearchDraft.trim()
  if(normalized.length>80)return
  setOrderSearch(normalized)
  setOrderCursors([null]);setSelectedOrderId('')
 }
 function nextOrderPage(){
  if(!orders.data?.nextCursor)return
  setOrderCursors(current=>[...current,orders.data!.nextCursor])
  setSelectedOrderId('')
 }
 function previousOrderPage(){
  setOrderCursors(current=>current.length>1?current.slice(0,-1):current)
  setSelectedOrderId('')
 }
 function reload(){if(section==='listings')void listings.refetch();if(section==='orders')void orders.refetch();if(section==='inventory')void inventory.refetch()}
 const status=(origin:Parameters<typeof sourceLabel>[0])=><span className={`status-chip ${origin==='MOCK'?'chip-amber':'chip-muted'}`}>{sourceLabel(origin)}</span>
 const panel=(title:string,subtitle:string,count:number,total:number,loading:boolean,error:Error|null|undefined,empty:string,children:React.ReactNode,refresh:()=>void)=>
  <section className="panel full-panel" id={`core-panel-${section}`}>
   <div className="panel-heading"><div><h2>{title}</h2><p>{subtitle}</p></div><span className="count-pill" role="status">显示 {count} / {total} 条 · 本页最多 50 条</span></div>
   {loading?<div className="empty-state" role="status">正在读取数据…</div>:
    error?<div className="core-query-error" role="alert"><strong>读取失败</strong><p>{error.message}</p><button className="refresh-button" onClick={refresh}>重新读取</button></div>:
    total===0?<div className="empty-state">{empty}</div>:
    count===0?<div className="empty-state">本页没有匹配的记录。 <button type="button" className="text-button" onClick={()=>setSearch('')}>清除筛选</button></div>:children}
  </section>
 return <div className="core-workspace">
 <div className="core-intro"><strong>已认证租户 · PostgreSQL 结构化数据</strong><p>读取当前会话租户的核心业务事实。模拟记录明确标注 MOCK；未知库存不等于零。</p></div>
 {focusException&&marketplaces.data&&
  !marketplaces.data.some(m=>m.id===focusException.marketplaceId)&&
  <p role="alert" className="setup-error">目标异常不在当前会话可访问的 Marketplace，无法跳转。</p>}
 {marketplaces.isPending?<div className="state-panel">正在加载可访问的 Marketplace…</div>:
 marketplaces.isError?<div className="state-panel error-panel" role="alert"><h2>Marketplace 读取失败</h2><p>{marketplaces.error instanceof Error?marketplaces.error.message:'请检查登录会话'}</p><button className="refresh-button" onClick={()=>void marketplaces.refetch()}>重试</button></div>:
 !marketplaces.data?.length?<div className="core-onboarding"><div className="state-panel core-empty"><h2>当前租户尚未配置 Marketplace</h2><p>本地配置不等于 Amazon 正式授权。</p><button className="refresh-button" onClick={()=>void marketplaces.refetch()}>重新检查站点</button></div>{isOwner&&<SellerSetup tenantId={tenantId}/>}</div>:
 <>
 <div className="core-controls"><label className="shop-filter"><span>Marketplace（服务端授权范围）</span>
 <select aria-label="切换受保护 Marketplace" value={marketplaceId} onChange={e=>{
  setMarketplaceId(e.target.value);setSearch('');setSelectedOrderId('')
  setOrderSearchDraft('');setOrderSearch('');setOrderCursors([null])
  setListingSearchDraft('');setListingSearch('')
  setListingFieldDraft('SELLER_SKU');setListingField('SELLER_SKU')
  setListingStatusDraft('ALL');setListingStatus('ALL');setListingCursors([null])
  setInventorySearch('');setInventoryQualityDraft('ALL')
  setInventoryQuality('ALL');setInventoryOriginDraft('ALL')
  setInventoryOrigin('ALL');setInventoryCursors([null])
 }}>
 {marketplaces.data.map(m=><option key={m.id} value={m.id}>{m.marketplaceId} · {m.region} · {m.currency}</option>)}</select></label>
 <button className="refresh-button" disabled={!marketplaceId} onClick={reload}>刷新当前数据</button></div>
 {active&&<div className="core-marketplace-summary" role="status"><div><strong>{active.marketplaceId}</strong><span>{active.region} · {active.currency} · {active.timezone}</span></div>
 <span className={`status-chip ${active.connectionStatus==='CONNECTED'?'chip-green':'chip-amber'}`}>账户状态：{coreStatusLabel(active.connectionStatus)}</span></div>}
 <div className="core-subtabs" role="group" aria-label="核心数据分类">{([{id:'listings',label:'Listing'},{id:'orders',label:'订单'},{id:'inventory',label:'FBA 库存'}] as const).map(t=>
 <button className={`core-subtab ${section===t.id?'selected':''}`} aria-pressed={section===t.id} key={t.id} onClick={()=>changeSection(t.id)}>{t.label}</button>)}</div>
 <div className="core-table-controls">
 {section==='listings'?
  <form className="core-search-field"
   onSubmit={e=>{e.preventDefault();applyListingSearch()}}>
   <label htmlFor="core-listing-search-field">Listing 检索字段</label>
   <select id="core-listing-search-field" value={listingFieldDraft}
    onChange={e=>setListingFieldDraft(e.target.value as 'SELLER_SKU'|'ASIN')}>
    <option value="SELLER_SKU">Seller SKU</option>
    <option value="ASIN">ASIN</option>
   </select>
   <label htmlFor="core-listing-mapping-status">映射状态</label>
   <select id="core-listing-mapping-status" value={listingStatusDraft}
    onChange={e=>setListingStatusDraft(e.target.value as typeof listingStatusDraft)}>
    {(['ALL','UNMAPPED','SUGGESTED','VERIFIED','ACTIVE',
       'CONFLICT','SUPERSEDED'] as const).map(key=>
     <option key={key} value={key}>{key==='ALL'?'全部状态':coreStatusLabel(key)}</option>)}
   </select>
   <label htmlFor="core-listing-server-search">
    在当前 Marketplace 搜索全部已入库 Listing（前缀匹配）
   </label>
   <input id="core-listing-server-search" type="search" maxLength={80}
    value={listingSearchDraft} onChange={e=>setListingSearchDraft(e.target.value)}
    placeholder={listingFieldDraft==='ASIN'?'例如 B0':'例如 SKU-'}
    autoComplete="off"/>
   <button className="refresh-button" type="submit">检索 Listing</button>
   {(listingSearch||listingStatus!=='ALL'||listingField!=='SELLER_SKU')&&
    <button className="text-button" type="button" onClick={()=>{
     setListingSearchDraft('');setListingSearch('')
     setListingFieldDraft('SELLER_SKU');setListingField('SELLER_SKU')
     setListingStatusDraft('ALL');setListingStatus('ALL');setListingCursors([null])
    }}>清除筛选</button>}
  </form>:
 section==='orders'?
  <form className="core-search-field" onSubmit={e=>{e.preventDefault();applyOrderSearch()}}>
   <label htmlFor="core-order-server-search">在当前站点搜索全部已入库订单号（前缀匹配）</label>
   <input id="core-order-server-search" type="search" maxLength={80}
    value={orderSearchDraft} onChange={e=>setOrderSearchDraft(e.target.value)}
    placeholder="例如 123- 或 LOCAL-" autoComplete="off"/>
   <button className="refresh-button" type="submit"
    disabled={orderSearchDraft.trim().length>80}>搜索订单</button>
   {orderSearch&&<button className="text-button" type="button" onClick={()=>{
    setOrderSearchDraft('');setOrderSearch('');setOrderCursors([null]);setSelectedOrderId('')
   }}>清除全量搜索</button>}
  </form>:
  <label className="core-search-field" htmlFor="core-record-search">
   <span>筛选本页记录</span>
   <input id="core-record-search" type="search" value={search}
    onChange={e=>setSearch(e.target.value)}
    placeholder="搜索已加载 SKU、ASIN" autoComplete="off"
    aria-describedby="core-search-scope"/>
  </label>}
 <p className="core-search-scope" id="core-search-scope">
  {section==='listings'?
   `Listing 从服务端按 Seller SKU、UUID 稳定翻页；当前第 ${listingCursors.length} 页，每页最多 50 条。Seller SKU、ASIN 前缀和映射状态过滤当前站点全部已入库 Listing。翻页不是冻结快照。`:
   section==='orders'?
   `订单从服务端按来源更新时间降序、UUID 稳定翻页；当前第 ${orderCursors.length} 页，每页最多 50 条，前缀搜索范围为当前 Marketplace 全部已入库订单。翻页不是冻结的来源快照。`:
   'FBA 库存目前仅筛选已读取的最多 50 条记录，不搜索其他页。'}
  <span className="core-scroll-instruction">表格可左右滑动，键盘可使用方向键。</span>
 </p></div>
 {section==='listings'&&panel('Listing（核心业务表）',
  'Seller SKU 与 ASIN 独立检索；有效 SKU 映射和历史版本不混淆',
  filteredListings.length,listings.data?.items.length??0,
  listings.isPending,listings.error,
  '当前站点在指定字段和映射状态下没有 Listing 记录。',
 <div className="table-scroll core-data-scroll" role="region" tabIndex={0}
  aria-label="可水平滚动的 Listing 表格">
  <table><thead><tr>{['Seller SKU','ASIN','内部 SKU','映射状态',
   '映射版本','来源','来源更新时间'].map(h=><th key={h} scope="col">{h}</th>)}
  </tr></thead><tbody>{filteredListings.map(i=><tr key={i.id}>
   <td className="strong-cell">{i.sellerSku}</td>
   <td>{i.asin??'未知'}</td><td>{i.internalSku??'未确认'}</td>
   <td><span className={`status-chip ${i.mappingStatus==='ACTIVE'?
    'chip-green':'chip-amber'}`}>{coreStatusLabel(i.mappingStatus)}</span></td>
   <td>{i.latestMappingVersion===null?'尚无映射版本':
    `最新 v${i.latestMappingVersion}${i.activeMappingVersion!==null?
    ` · 生效 v${i.activeMappingVersion}`:' · 无生效映射'}`}</td>
   <td>{status(i.dataOrigin)}</td>
   <td className="small-cell">{formatSourceTime(i.sourceUpdatedAt)}</td>
  </tr>)}</tbody></table></div>,()=>void listings.refetch())}
 {section==='listings'&&!listings.isPending&&!listings.isError&&
  <div className="ops-toolbar" role="group" aria-label="Listing 服务端分页">
   <button type="button" className="refresh-button" onClick={previousListingPage}
    disabled={listingCursors.length===1}>上一页</button>
   <span role="status">第 {listingCursors.length} 页 · 当前 {filteredListings.length} 条
    {listings.data?.hasMore?' · 后面还有 Listing':' · 已到最后一页'}</span>
   <button type="button" className="refresh-button"
    onClick={nextListingPage}
    disabled={!listings.data?.hasMore||!listings.data?.nextCursor}>下一页</button>
  </div>}
 {section==='orders'&&panel('订单（核心业务表）','履约、退款与财务状态分开列示；不推测收入',filteredOrders.length,orders.data?.items.length??0,orders.isPending,orders.error,'当前站点尚无订单记录。',
 <div className="table-scroll core-data-scroll" role="region" tabIndex={0} aria-label="可水平滚动的订单表格"><table><thead><tr>{['外部订单号','订单项数量','履约','退款','财务','来源','来源时间','明细'].map(h=><th key={h}>{h}</th>)}</tr></thead><tbody>{filteredOrders.map(i=><tr key={i.id}><td className="strong-cell">{i.externalOrderId}</td><td>{i.itemsObservation==='OBSERVED'?i.itemCount:'未取得'}</td><td>{coreStatusLabel(i.fulfillmentStatus)}</td><td>{coreStatusLabel(i.refundStatus)}</td><td>{coreStatusLabel(i.financialStatus)}</td><td>{status(i.dataOrigin)}</td><td>{formatSourceTime(i.sourceUpdatedAt)}</td>
 <td><button className="text-button" type="button"
   aria-pressed={selectedOrderId===i.id} onClick={()=>setSelectedOrderId(
     selectedOrderId===i.id?'':i.id)}>{selectedOrderId===i.id?'收起':'查看订单项'}</button></td></tr>)}</tbody></table></div>,()=>void orders.refetch())}
 {section==='orders'&&!orders.isPending&&!orders.isError&&
  <div className="ops-toolbar" role="group" aria-label="订单服务端分页">
   <button type="button" className="refresh-button"
    onClick={previousOrderPage} disabled={orderCursors.length===1}>上一页</button>
   <span role="status">第 {orderCursors.length} 页 · 当前 {filteredOrders.length} 条
    {orders.data?.hasMore?' · 后面还有订单':' · 已到最后一页'}</span>
   <button type="button" className="refresh-button" onClick={nextOrderPage}
    disabled={!orders.data?.hasMore||!orders.data?.nextCursor}>下一页</button>
  </div>}
 {section==='orders'&&selectedOrderId&&<section className="panel full-panel"
   aria-label="当前订单明细"><div className="panel-heading"><div><h2>订单项详情</h2>
   <p>仅包含订单项 ID、Seller SKU、数量及 ITEM 商品小计。该小计不等于总成交额、净利润或平台已结算金额。</p></div>
   <button className="refresh-button" type="button" onClick={()=>void detail.refetch()}>刷新明细</button></div>
   {detail.isPending?<p className="empty-state">正在读取当前订单项…</p>:
     detail.isError?<p className="setup-error" role="alert">{String(detail.error)}</p>:
     (orders.data?.items.find(o=>o.id===selectedOrderId)?.itemsObservation??'NOT_OBSERVED')!=='OBSERVED'
      ?<p className="empty-state">源订单项尚未观测。不能把未知订单项当作零件。</p>:
     !detail.data?.length?<p className="empty-state">该订单的来源订单项集合已观测为空。</p>:
     <div className="table-scroll core-data-scroll" role="region" tabIndex={0}
        aria-label="订单项明细表"><table><thead><tr>
       {['来源订单项 ID','Seller SKU','订购数量','ITEM 商品小计（原币）'].map(h=>
         <th key={h} scope="col">{h}</th>)}</tr></thead><tbody>
       {detail.data.map(i=><tr key={i.id}><td className="strong-cell">{i.externalItemId}</td>
         <td>{i.sellerSku??'未知'}</td><td>{i.quantity??'未知'}</td>
         <td>{i.itemSubtotal===null?'未取得':`${i.currency} ${i.itemSubtotal}`}</td></tr>)}
       </tbody></table></div>}
 </section>}
 {section==='inventory'&&panel('最新 FBA 观测（核心业务表）','每个 Seller SKU 显示最新观测，未知不等于零',filteredInventory.length,inventory.data?.length??0,inventory.isPending,inventory.error,'当前站点尚无 FBA 库存观测。',
 <div className="table-scroll core-data-scroll" role="region" tabIndex={0} aria-label="可水平滚动的 FBA 库存表格"><table><thead><tr>{['Seller SKU','可售','预留','质量','来源','观测时间'].map(h=><th key={h}>{h}</th>)}</tr></thead><tbody>{filteredInventory.map(i=><tr key={i.id}><td className="strong-cell">{i.sellerSku}</td><td>{i.fulfillable===null?'未知':i.fulfillable}</td><td>{i.reserved===null?'未知':i.reserved}</td><td>{coreStatusLabel(i.quality)}</td><td>{status(i.dataOrigin)}</td><td>{formatSourceTime(i.observedAt)}</td></tr>)}</tbody></table></div>,()=>void inventory.refetch())}
 <div className="note-panel"><span className="note-mark">i</span><div><strong>数据完整性提醒</strong><p>Listing 和订单均已支持服务端搜索与分页；FBA 库存仍只读取本页 50 条。未知库存不能当零，Mock 不代表 Amazon 真实业务。</p></div></div>
 {isOwner&&active?.connectionStatus==='CONNECTED'&&section==='listings'&&
   <CatalogSyncPanel key={'catalog-'+active.id} tenantId={tenantId}
    marketplaceId={active.id} stream="LISTINGS"/>}
 {isOwner&&active?.connectionStatus==='CONNECTED'&&section==='inventory'&&
   <CatalogSyncPanel key={'fba-'+active.id} tenantId={tenantId}
    marketplaceId={active.id} stream="FBA"/>}
 {isOwner&&section==='inventory'&&<FbaInboundPanel key={'fba-inbound-'+marketplaceId}
    tenantId={tenantId} marketplaceId={marketplaceId}
    focusExceptionLine={focusException?.marketplaceId===marketplaceId?
      focusException.lineId:undefined}/>}
 {isOwner&&section==='inventory'&&<ReplenishmentWorkspace tenantId={tenantId}
    marketplaceId={marketplaceId}/>}
 {isOwner&&section==='listings'&&
    <SkuCatalogManager
     key={[marketplaceId,listingField,listingStatus,listingSearch,
      listingCursor??'FIRST'].join('|')}
     tenantId={tenantId}
     marketplaceId={marketplaceId} listings={listings.data?.items??[]}/>}
 {isOwner&&active?.connectionStatus==='CONNECTED'&&<OrdersSyncPanel key={'live-'+active.id} tenantId={tenantId} marketplaceId={active.id} isLive/>}
 {isOwner&&<details className="core-tools"><summary><strong>本地店铺配置与测试工具</strong><span>添加 Marketplace、手动 Mock 回放 · 不连接 Amazon</span></summary>
 <div className="core-tools-content"><SellerSetup tenantId={tenantId}/>{active?.connectionStatus==='UNCONFIGURED'&&<OrdersSyncPanel key={'mock-'+active.id} tenantId={tenantId} marketplaceId={active.id} isLive={false}/>}
 {active&&<SandboxReplay key={active.id} tenantId={tenantId} marketplaceId={active.id} marketplaceCode={active.marketplaceId} connectionStatus={active.connectionStatus}/>}</div></details>}
 </>}
 </div>
}
