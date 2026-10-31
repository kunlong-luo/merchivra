import {useEffect,useMemo,useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import SellerSetup from './SellerSetup'
import SandboxReplay from './SandboxReplay'
import OrdersSyncPanel from './OrdersSyncPanel'
import SkuCatalogManager from './SkuCatalogManager'
import ReplenishmentWorkspace from './ReplenishmentWorkspace'
import {coreStatusLabel,filterLoadedRows,formatSourceTime,sourceLabel} from './lib/core-view'
import {fetchCoreMarketplaces,fetchCoreListings,fetchCoreOrders,fetchCoreInventory} from './lib/core'

type Section='listings'|'orders'|'inventory'
export default function CoreWorkspace({tenantId,isOwner=true}:{tenantId:string;isOwner?:boolean}){
 const [marketplaceId,setMarketplaceId]=useState(''),[section,setSection]=useState<Section>('listings'),[search,setSearch]=useState('')
 const marketplaces=useQuery({queryKey:['core','marketplaces',tenantId],queryFn:fetchCoreMarketplaces,staleTime:30000,retry:1})
 useEffect(()=>{if(!marketplaces.data?.some(m=>m.id===marketplaceId))setMarketplaceId(marketplaces.data?.[0]?.id??'')},[marketplaces.data,marketplaceId])
 const listings=useQuery({queryKey:['core',tenantId,'listings',marketplaceId],queryFn:()=>fetchCoreListings(marketplaceId),enabled:!!marketplaceId&&section==='listings',staleTime:30000,retry:1})
 const orders=useQuery({queryKey:['core',tenantId,'orders',marketplaceId],queryFn:()=>fetchCoreOrders(marketplaceId),enabled:!!marketplaceId&&section==='orders',staleTime:30000,retry:1})
 const inventory=useQuery({queryKey:['core',tenantId,'inventory',marketplaceId],queryFn:()=>fetchCoreInventory(marketplaceId),enabled:!!marketplaceId&&section==='inventory',staleTime:30000,retry:1})
 const active=marketplaces.data?.find(m=>m.id===marketplaceId)
 const filteredListings=useMemo(()=>filterLoadedRows(listings.data,search,r=>[r.sellerSku,r.asin,r.internalSku]),[listings.data,search])
 const filteredOrders=useMemo(()=>filterLoadedRows(orders.data,search,r=>[r.externalOrderId]),[orders.data,search])
 const filteredInventory=useMemo(()=>filterLoadedRows(inventory.data,search,r=>[r.sellerSku]),[inventory.data,search])
 function changeSection(id:Section){setSection(id);setSearch('')}
 function reload(){if(section==='listings')void listings.refetch();if(section==='orders')void orders.refetch();if(section==='inventory')void inventory.refetch()}
 const status=(origin:Parameters<typeof sourceLabel>[0])=><span className={`status-chip ${origin==='MOCK'?'chip-amber':'chip-muted'}`}>{sourceLabel(origin)}</span>
 const panel=(title:string,subtitle:string,count:number,total:number,loading:boolean,error:Error|null|undefined,empty:string,children:React.ReactNode,refresh:()=>void)=>
  <section className="panel full-panel" id={`core-panel-${section}`}>
   <div className="panel-heading"><div><h2>{title}</h2><p>{subtitle}</p></div><span className="count-pill" role="status">显示 {count} / {total} 条 · 最多读取 50 条</span></div>
   {loading?<div className="empty-state" role="status">正在读取数据…</div>:
    error?<div className="core-query-error" role="alert"><strong>读取失败</strong><p>{error.message}</p><button className="refresh-button" onClick={refresh}>重新读取</button></div>:
    total===0?<div className="empty-state">{empty}</div>:
    count===0?<div className="empty-state">本页没有匹配的记录。 <button type="button" className="text-button" onClick={()=>setSearch('')}>清除筛选</button></div>:children}
  </section>
 return <div className="core-workspace">
 <div className="core-intro"><strong>已认证租户 · PostgreSQL 结构化数据</strong><p>读取当前会话租户的核心业务事实。模拟记录明确标注 MOCK；未知库存不等于零。</p></div>
 {marketplaces.isPending?<div className="state-panel">正在加载可访问的 Marketplace…</div>:
 marketplaces.isError?<div className="state-panel error-panel" role="alert"><h2>Marketplace 读取失败</h2><p>{marketplaces.error instanceof Error?marketplaces.error.message:'请检查登录会话'}</p><button className="refresh-button" onClick={()=>void marketplaces.refetch()}>重试</button></div>:
 !marketplaces.data?.length?<div className="core-onboarding"><div className="state-panel core-empty"><h2>当前租户尚未配置 Marketplace</h2><p>本地配置不等于 Amazon 正式授权。</p><button className="refresh-button" onClick={()=>void marketplaces.refetch()}>重新检查站点</button></div>{isOwner&&<SellerSetup tenantId={tenantId}/>}</div>:
 <>
 <div className="core-controls"><label className="shop-filter"><span>Marketplace（服务端授权范围）</span>
 <select aria-label="切换受保护 Marketplace" value={marketplaceId} onChange={e=>{setMarketplaceId(e.target.value);setSearch('')}}>
 {marketplaces.data.map(m=><option key={m.id} value={m.id}>{m.marketplaceId} · {m.region} · {m.currency}</option>)}</select></label>
 <button className="refresh-button" disabled={!marketplaceId} onClick={reload}>刷新当前数据</button></div>
 {active&&<div className="core-marketplace-summary" role="status"><div><strong>{active.marketplaceId}</strong><span>{active.region} · {active.currency} · {active.timezone}</span></div>
 <span className={`status-chip ${active.connectionStatus==='CONNECTED'?'chip-green':'chip-amber'}`}>账户状态：{coreStatusLabel(active.connectionStatus)}</span></div>}
 <div className="core-subtabs" role="group" aria-label="核心数据分类">{([{id:'listings',label:'Listing'},{id:'orders',label:'订单'},{id:'inventory',label:'FBA 库存'}] as const).map(t=>
 <button className={`core-subtab ${section===t.id?'selected':''}`} aria-pressed={section===t.id} key={t.id} onClick={()=>changeSection(t.id)}>{t.label}</button>)}</div>
 <div className="core-table-controls"><label className="core-search-field" htmlFor="core-record-search"><span>筛选本页记录</span>
 <input id="core-record-search" type="search" value={search} onChange={e=>setSearch(e.target.value)}
 placeholder={section==='orders'?'搜索已加载订单号':'搜索已加载 SKU、ASIN'} autoComplete="off" aria-describedby="core-search-scope"/></label>
 <p className="core-search-scope" id="core-search-scope">仅筛选已读取的最多 50 条记录，不搜索服务端其他页。<span className="core-scroll-instruction">表格可左右滑动，键盘可使用方向键。</span></p></div>
 {section==='listings'&&panel('Listing（核心业务表）','独立展示 Seller SKU 和已确认的内部 SKU 映射',filteredListings.length,listings.data?.length??0,listings.isPending,listings.error,'当前站点尚无 Listing 记录。',
 <div className="table-scroll core-data-scroll" role="region" tabIndex={0} aria-label="可水平滚动的 Listing 表格"><table><thead><tr>{['Seller SKU','ASIN','内部 SKU','映射状态','来源','来源更新时间'].map(h=><th key={h}>{h}</th>)}</tr></thead><tbody>{filteredListings.map(i=><tr key={i.id}><td className="strong-cell">{i.sellerSku}</td><td>{i.asin??'未知'}</td><td>{i.internalSku??'未确认'}</td><td><span className={`status-chip ${i.mappingStatus==='ACTIVE'?'chip-green':'chip-amber'}`}>{coreStatusLabel(i.mappingStatus)}</span></td><td>{status(i.dataOrigin)}</td><td className="small-cell">{formatSourceTime(i.sourceUpdatedAt)}</td></tr>)}</tbody></table></div>,()=>void listings.refetch())}
 {section==='orders'&&panel('订单（核心业务表）','履约、退款与财务状态分开列示；不推测收入',filteredOrders.length,orders.data?.length??0,orders.isPending,orders.error,'当前站点尚无订单记录。',
 <div className="table-scroll core-data-scroll" role="region" tabIndex={0} aria-label="可水平滚动的订单表格"><table><thead><tr>{['外部订单号','订单项数量','履约','退款','财务','来源','来源时间'].map(h=><th key={h}>{h}</th>)}</tr></thead><tbody>{filteredOrders.map(i=><tr key={i.id}><td className="strong-cell">{i.externalOrderId}</td><td>{i.itemCount}</td><td>{coreStatusLabel(i.fulfillmentStatus)}</td><td>{coreStatusLabel(i.refundStatus)}</td><td>{coreStatusLabel(i.financialStatus)}</td><td>{status(i.dataOrigin)}</td><td>{formatSourceTime(i.sourceUpdatedAt)}</td></tr>)}</tbody></table></div>,()=>void orders.refetch())}
 {section==='inventory'&&panel('最新 FBA 观测（核心业务表）','每个 Seller SKU 显示最新观测，未知不等于零',filteredInventory.length,inventory.data?.length??0,inventory.isPending,inventory.error,'当前站点尚无 FBA 库存观测。',
 <div className="table-scroll core-data-scroll" role="region" tabIndex={0} aria-label="可水平滚动的 FBA 库存表格"><table><thead><tr>{['Seller SKU','可售','预留','质量','来源','观测时间'].map(h=><th key={h}>{h}</th>)}</tr></thead><tbody>{filteredInventory.map(i=><tr key={i.id}><td className="strong-cell">{i.sellerSku}</td><td>{i.fulfillable===null?'未知':i.fulfillable}</td><td>{i.reserved===null?'未知':i.reserved}</td><td>{coreStatusLabel(i.quality)}</td><td>{status(i.dataOrigin)}</td><td>{formatSourceTime(i.observedAt)}</td></tr>)}</tbody></table></div>,()=>void inventory.refetch())}
 <div className="note-panel"><span className="note-mark">i</span><div><strong>数据完整性提醒</strong><p>最多读取 50 条，筛选只作用于本页；未知库存不能当零，Mock 不代表 Amazon 真实业务。</p></div></div>
 {isOwner&&section==='inventory'&&<ReplenishmentWorkspace tenantId={tenantId}
    marketplaceId={marketplaceId}/>}
 {isOwner&&section==='listings'&&<SkuCatalogManager tenantId={tenantId}
    marketplaceId={marketplaceId} listings={listings.data??[]}/>}
 {isOwner&&active?.connectionStatus==='CONNECTED'&&<OrdersSyncPanel key={'live-'+active.id} tenantId={tenantId} marketplaceId={active.id} isLive/>}
 {isOwner&&<details className="core-tools"><summary><strong>本地店铺配置与测试工具</strong><span>添加 Marketplace、手动 Mock 回放 · 不连接 Amazon</span></summary>
 <div className="core-tools-content"><SellerSetup tenantId={tenantId}/>{active?.connectionStatus==='UNCONFIGURED'&&<OrdersSyncPanel key={'mock-'+active.id} tenantId={tenantId} marketplaceId={active.id} isLive={false}/>}
 {active&&<SandboxReplay key={active.id} tenantId={tenantId} marketplaceId={active.id} marketplaceCode={active.marketplaceId} connectionStatus={active.connectionStatus}/>}</div></details>}
 </>}
 </div>
}
