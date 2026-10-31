import {useEffect,useState} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import type {AuthViewer} from './lib/auth'
import CoreWorkspace from './CoreWorkspace'
import OperationsWorkspace from './OperationsWorkspace'
import TeamWorkspace from './TeamWorkspace'
import SellerConnections from './SellerConnections'
import PurchasingWorkspace from './PurchasingWorkspace'
import {fetchPing} from './lib/api'
import {fetchDemoOverview,fetchDemoSyncRuns,startDemoSync,fetchDemoOrders,fetchDemoListings,fetchDemoInventory,fetchDemoCoreStatus} from './lib/demo'
type View='core'|'ops'|'team'|'connections'|'purchasing'|'overview'|'orders'|'catalog'|'inventory'|'sync'
const nav:{key:View;label:string;symbol:string}[]=[
 {key:'core',label:'核心业务数据',symbol:'▣'},{key:'ops',label:'运营自动化',symbol:'✳'},
 {key:'team',label:'团队与权限',symbol:'♙'},{key:'purchasing',label:'采购与供应商',symbol:'▤'},{key:'connections',label:'卖家授权',symbol:'◇'},{key:'overview',label:'经营总览',symbol:'◫'},
 {key:'orders',label:'订单管理',symbol:'▤'},{key:'catalog',label:'商品与 Listing',symbol:'▧'},
 {key:'inventory',label:'FBA 库存',symbol:'▥'},{key:'sync',label:'模拟同步',symbol:'↻'}
]
function money(v?:{amount:string;currency:string}|null){return v?`${v.currency} ${v.amount}`:'不可计算'}
function timestamp(v?:string|null){if(!v)return '暂无来源时间';const date=new Date(v);return Number.isNaN(date.getTime())?'时间未知':date.toLocaleString('zh-CN',{timeZone:'UTC',hour12:false})+' UTC'}
function qualityLabel(v:string){const names:Record<string,string>={OBSERVED:'已观测',UNKNOWN:'未取得',STALE:'已过期',PARTIAL:'部分数据',UNMAPPED:'待映射',ACTIVE:'已映射',SHIPPED:'已发货',UNSHIPPED:'待发货',PARTIAL_REFUND:'部分退款',NOT_OBSERVED:'未取得财务数据'};return names[v]??v}
function Table({heads,children,label='可水平滚动的演示数据表格'}:{heads:string[];children:React.ReactNode;label?:string}){return <div className="table-scroll" role="region" tabIndex={0} aria-label={label}><table><thead><tr>{heads.map(h=><th key={h} scope="col">{h}</th>)}</tr></thead><tbody>{children}</tbody></table></div>}
export default function App({viewer,signingOut,onLogout}:{viewer:AuthViewer;signingOut:boolean;onLogout:()=>void}){
 const [activeView,setActiveView]=useState<View>(() => typeof window !== 'undefined' &&
  new URLSearchParams(window.location.search).get('amazon_oauth') === 'pending_verification'
  ? 'connections' : 'core'),[accountId,setAccountId]=useState(''),[syncMessage,setSyncMessage]=useState('')
 const client=useQueryClient()
 const demo=activeView!=='core'&&activeView!=='ops'&&activeView!=='team'&&activeView!=='connections'&&activeView!=='purchasing'&&viewer.isOwner
 const ping=useQuery({queryKey:['api','ping'],queryFn:fetchPing,staleTime:30000,retry:1})
 const overview=useQuery({queryKey:['api','demo','overview'],queryFn:fetchDemoOverview,enabled:demo,staleTime:60000,retry:1})
 useEffect(()=>{if(overview.data?.accounts.length&&!overview.data.accounts.some(a=>a.id===accountId))setAccountId(overview.data.accounts[0].id)},[overview.data,accountId])
 const coreStatus=useQuery({queryKey:['api','demo','core-status'],queryFn:fetchDemoCoreStatus,enabled:activeView==='sync',staleTime:15000})
 const syncRuns=useQuery({queryKey:['api','demo','sync-runs'],queryFn:fetchDemoSyncRuns,enabled:activeView==='sync',staleTime:15000})
 const run=useMutation({mutationFn:startDemoSync,onSuccess:r=>{setSyncMessage(r.status==='SUCCEEDED'?'模拟同步成功：快照和运行记录已保存':'模拟失败已记录：快照未覆盖，可重新创建任务');void client.invalidateQueries({queryKey:['api','demo']})},onError:(e:Error)=>setSyncMessage(e.message)})
 const orders=useQuery({queryKey:['api','demo','orders',accountId],queryFn:()=>fetchDemoOrders(accountId),enabled:!!accountId&&activeView==='orders',staleTime:30000})
 const listings=useQuery({queryKey:['api','demo','listings',accountId],queryFn:()=>fetchDemoListings(accountId),enabled:!!accountId&&activeView==='catalog',staleTime:30000})
 const inventory=useQuery({queryKey:['api','demo','inventory',accountId],queryFn:()=>fetchDemoInventory(accountId),enabled:!!accountId&&activeView==='inventory',staleTime:30000})
 const account=overview.data?.accounts.find(a=>a.id===accountId)
 const summary=overview.data?.summaries.find(a=>a.accountId===accountId)
 const recent=overview.data?.orders.filter(a=>a.accountId===accountId)??[]
 const title=nav.find(n=>n.key===activeView)?.label??'经营总览'
 const status=ping.isPending?'连接检查中':ping.isError?'API 不可用':ping.data?.status==='ok'?'API 已连接':'API 状态异常'
 return <div className="workspace"><a className="skip-link" href="#main-content">跳转到主要内容</a>
 <aside className="sidebar"><div className="brand-wrap"><div className="brand-icon">M</div><div className="brand-copy"><strong>merchivra</strong><small>SELLER OPERATIONS</small></div></div>
 <div className="sidebar-section-title">工作空间</div><nav className="nav-links" aria-label="主导航">
 {(viewer.isOwner?nav:nav.filter(n=>n.key==='core')).map(item=><button key={item.key} type="button" className={`nav-link ${activeView===item.key?'active':''}`} aria-current={activeView===item.key?'page':undefined} onClick={()=>setActiveView(item.key)}><span className="nav-icon" aria-hidden="true">{item.symbol}</span>{item.label}</button>)}
 {viewer.isOwner&&<div className="nav-link nav-disabled" aria-disabled="true"><span className="nav-icon">▦</span>财务与对账<span className="soon">后续</span></div>}</nav>
 <div className="sidebar-spacer"/><div className="sidebar-info"><span className="local-indicator"/><div><strong>{demo?'本地演示环境':'核心业务工作台'}</strong><small>{demo?'纯虚构数据 · 无真实连接':'会话租户隔离 · 数据记录标注来源'}</small></div></div>
 <a className="sidebar-repo" href="https://github.com/kunlong-luo/merchivra" target="_blank" rel="noopener noreferrer">GitHub 源代码 <span aria-hidden="true">↗</span></a></aside>
 <main id="main-content" className="main-panel" tabIndex={-1}><header className="topbar">
 <div className="breadcrumb">工作空间 <span>/</span> <strong>{title}</strong></div>
 <div className="topbar-right"><span className={`connection ${ping.isError?'connectionError':''}`} role="status" aria-label="后端 API 连接状态"><span className="connection-dot" aria-hidden="true"/>{status}</span>
 <span className="avatar" aria-label="已登录的工作台">✓</span><button className="logout-control" type="button" disabled={signingOut} onClick={onLogout}>{signingOut?'退出中…':'退出登录'}</button></div></header>
 <div className="content"><div className="page-heading"><div><div className="eyebrow">{demo?'COMMERCE INTELLIGENCE / DEMO':'SECURE ERP / CORE DATA'}</div><h1>{title}</h1>
 <p>{activeView==='core'?'基于当前 Session 的租户权限读取 PostgreSQL 核心业务表。':activeView==='ops'?'通过规则检查来源数据，生成可认领、可审计的运营待办。':activeView==='team'?'使用一次性邀请开通成员，并审计 Orders 安全审核员权限。':activeView==='connections'?'仅 Owner 可操作 Amazon OAuth、站点校验和 Orders 角色审核；所有真实连接仍受后端开关保护。':activeView==='purchasing'?'管理供应商、采购草稿、独立审批和收货；本地入库不会改动 Amazon FBA 库存。':'集中查看店铺运营数据，所有示例来自本地 Mock Gateway。'}</p></div>
 {demo&&<div className="heading-actions"><label className="shop-filter"><span>当前店铺</span><select aria-label="切换演示店铺" value={accountId} disabled={!overview.data?.accounts.length} onChange={e=>setAccountId(e.target.value)}>{(overview.data?.accounts??[]).map(a=><option key={a.id} value={a.id}>{a.name}</option>)}</select></label>
 <button className="refresh-button" disabled={overview.isFetching} onClick={()=>void overview.refetch()}>↻ {overview.isFetching?'读取中':'刷新'}</button></div>}</div>
 {demo&&<div className="mock-banner" role="status"><span className="mock-banner-icon" aria-hidden="true">✦</span><div><strong>MOCK 演示模式</strong><span>全部商品、订单、账户与库存为虚构数据；无真实 Amazon 凭证或财务结算。</span></div><span className="mock-pill">DEMO ONLY</span></div>}
 {activeView==='core'?<CoreWorkspace tenantId={viewer.activeTenantId} isOwner={viewer.isOwner}/>:
 activeView==='ops'&&viewer.isOwner?<OperationsWorkspace tenantId={viewer.activeTenantId}/>:
 activeView==='team'&&viewer.isOwner?<TeamWorkspace tenantId={viewer.activeTenantId} currentUserId={viewer.userId}/>:
 activeView==='connections'&&viewer.isOwner?<SellerConnections tenantId={viewer.activeTenantId} currentUserId={viewer.userId}/>:
 activeView==='purchasing'&&viewer.isOwner?<PurchasingWorkspace tenantId={viewer.activeTenantId} currentUserId={viewer.userId}/>:
 overview.isPending?<div className="state-panel"><div className="loading-ring"/><strong>正在读取模拟数据…</strong><p>连接后端 Mock Marketplace Gateway</p></div>:
 overview.isError?<div className="state-panel error-panel" role="alert"><h2>暂时无法加载业务演示</h2><p>{overview.error instanceof Error?overview.error.message:'请检查服务状态及 Mock 配置。'}</p><button className="primary-button" onClick={()=>void overview.refetch()}>重新尝试</button></div>:
 overview.data&&account&&summary?<><div className="section-meta"><span><span className="data-dot"/> {account.name} · {account.marketplaceId} · {account.region}</span><span>模拟数据截止 {timestamp(overview.data.fixtureAsOf)}</span></div>
 {activeView==='overview'&&<>
 <section className="metric-grid" aria-label="经营数据摘要">{[
 ['订单预计商品收入',money(summary.provisionalSales),'按订单来源口径，不等于平台入账'],
 ['订单数',summary.orderCount+' 笔','仅当前所选演示店铺'],
 ['已观测退款',money(summary.observedRefunds),'单独列示，不重复相加'],
 ['预估订单贡献额','不可计算','缺少完整成本和费用；不显示虚假利润']
 ].map((m,i)=><div className={`metric-card ${i===3?'accent-card':''}`} key={i}><div className="metric-label">{m[0]}</div><div className="metric-value">{m[1]}</div><div className="metric-foot">{m[2]}</div></div>)}</section>
 <section className="two-column"><div className="panel"><div className="panel-heading"><div><h2>近期订单</h2><p>来源状态、退款与财务状态分别记录</p></div><button className="text-button" onClick={()=>setActiveView('orders')}>查看全部 →</button></div>
 <Table heads={['订单编号','SKU','预计金额','履约状态']}>{recent.map(o=><tr key={o.id}><td className="strong-cell">{o.externalOrderId}</td><td>{o.sellerSku}</td><td>{money(o.provisionalSales)}</td><td><span className={`status-chip ${o.fulfillmentStatus==='SHIPPED'?'chip-green':'chip-amber'}`}>{qualityLabel(o.fulfillmentStatus)}</span></td></tr>)}</Table>{!recent.length&&<div className="empty-state">暂无订单</div>}</div>
 <div className="panel insight-panel"><div className="panel-heading"><div><h2>需要关注</h2><p>仅反映模拟数据质量</p></div></div>
 <div className="insight-row"><span className="insight-icon warning">!</span><div><strong>{summary.unmappedListings} 个 SKU 待映射</strong><small>原始 Listing 已保留</small></div></div>
 <div className="insight-row"><span className="insight-icon info">~</span><div><strong>{summary.unknownInventoryCount} 条库存数量未知</strong><small>未知 ≠ 0，不触发缺货结论</small></div></div>
 <div className="insight-row"><span className="insight-icon muted">◌</span><div><strong>平台财务交易未接入</strong><small>预计销售不能算结算利润</small></div></div></div></section>
 <div className="note-panel"><span className="note-mark">i</span><div><strong>关于本页面指标</strong><p>演示指标按店铺和币种汇总；真实财务、结算与利润尚未接入。</p></div></div></>}
 {activeView==='orders'&&<section className="panel full-panel"><div className="panel-heading"><div><h2>订单列表</h2><p>订单业务事实与退款、财务状态保持独立</p></div><span className="count-pill">{orders.data?.length??0} 笔</span></div>
 {orders.isPending?<div className="empty-state">读取订单中…</div>:orders.isError?<div className="empty-state">订单查询失败</div>:
 <Table heads={['Amazon 订单（模拟）','Seller SKU','预计商品收入','退款','履约','财务','来源更新时间']}>{(orders.data??[]).map(o=><tr key={o.id}><td className="strong-cell">{o.externalOrderId}</td><td>{o.sellerSku}</td><td>{money(o.provisionalSales)}</td><td>{o.refundStatus==='NONE'?'无退款':'部分退款'}{o.refundStatus!=='NONE'&&<div className="secondary-cell">{money(o.refundAmount)}</div>}</td><td>{qualityLabel(o.fulfillmentStatus)}</td><td>未入账</td><td className="small-cell">{timestamp(o.sourceUpdatedAt)}</td></tr>)}</Table>}</section>}
 {activeView==='catalog'&&<section className="panel full-panel"><div className="panel-heading"><div><h2>Listing 与 SKU 归属</h2><p>ASIN、Seller SKU 与内部 SKU 不同</p></div><span className="count-pill">{listings.data?.length??0} 个 Listing</span></div>
 {listings.isPending?<div className="empty-state">读取 Listing 中…</div>:listings.isError?<div className="empty-state">Listing 查询失败</div>:<Table heads={['Seller SKU','ASIN（模拟）','内部 SKU','映射状态']}>{(listings.data??[]).map(i=><tr key={i.id}><td className="strong-cell">{i.sellerSku}</td><td>{i.asin}</td><td>{i.internalSku??'—'}</td><td>{qualityLabel(i.mappingStatus)}</td></tr>)}</Table>}
 <div className="panel-caption">待映射 SKU 仍保留来源记录；审核与版本化映射将在后续接入。</div></section>}
 {activeView==='inventory'&&<section className="panel full-panel"><div className="panel-heading"><div><h2>FBA 库存快照</h2><p>观测型库存，未知数量不可当成零</p></div><span className="count-pill">{inventory.data?.length??0} 条观测</span></div>
 {inventory.isPending?<div className="empty-state">读取库存中…</div>:inventory.isError?<div className="empty-state">库存查询失败</div>:<Table heads={['Seller SKU','可售','预留','数据质量','观测时间']}>{(inventory.data??[]).map(i=><tr key={i.id}><td className="strong-cell">{i.sellerSku}</td><td className="quantity-cell">{i.fulfillable===null?'未知':i.fulfillable} {i.fulfillable===0&&<span className="zero-label">已观测为 0</span>}</td><td>{i.reserved===null?'未知':i.reserved}</td><td>{qualityLabel(i.quality)}</td><td>{timestamp(i.observedAt)}</td></tr>)}</Table>}
 <div className="panel-caption">静态 Mock 库存；不代表实时 FBA/FBM。</div></section>}
 {activeView==='sync'&&<section className="panel full-panel"><div className="panel-heading"><div><h2>本地模拟同步记录</h2><p>运行历史保存至 PostgreSQL，只刷新虚构数据</p></div><span className="count-pill">最近 {syncRuns.data?.length??0} 次</span></div>
 <div className="sync-actions"><button className="primary-button" disabled={run.isPending} onClick={()=>run.mutate(false)}>{run.isPending?'正在同步…':'开始模拟同步'}</button><button className="refresh-button" disabled={run.isPending} onClick={()=>run.mutate(true)}>模拟失败</button><span className="sync-hint">历史失败记录不会消失。</span></div>
 {syncMessage&&<div className="panel-caption" role="status">{syncMessage}</div>}
 <section className="core-storage" aria-label="核心数据库入库情况"><div className="core-storage-header"><strong>PostgreSQL · 核心实体入库</strong><span>固定虚构租户 · 非正式 Amazon 数据</span></div>
 {coreStatus.isPending?<div className="core-storage-note">正在读取数据表…</div>:coreStatus.isError?<div className="core-storage-note">核心数据读取失败</div>:coreStatus.data&&<div className="core-count-grid">
 {([['店铺',coreStatus.data.accounts],['内部 SKU',coreStatus.data.internalSkus],['Listing',coreStatus.data.listings],['订单',coreStatus.data.orders],['订单项',coreStatus.data.orderItems],['FBA 观测',coreStatus.data.inventoryObservations]]).map(([n,v])=><div key={n}><strong>{v}</strong><span>{n}</span></div>)}</div>}</section>
 {syncRuns.isPending?<div className="empty-state">读取同步历史中…</div>:syncRuns.isError?<div className="empty-state">无法读取同步记录</div>:!syncRuns.data?.length?<div className="empty-state">暂无任务</div>:
 <Table heads={['任务 ID','结果','快照记录数','完成时间','说明']}>{syncRuns.data.map(r=><tr key={r.id}><td className="strong-cell">{r.id.slice(0,8)}…</td><td>{r.status==='SUCCEEDED'?'已完成':'失败，可重试'}</td><td>{r.fixtureCount}</td><td>{timestamp(r.finishedAt)}</td><td>{r.failureCode??'本地快照写入'}</td></tr>)}</Table>}
 <div className="panel-caption">仅本地演示；不代表真实 Amazon 同步。</div></section>}
 </>:null}
 <footer className="footer"><span>© Merchivra · Pre-alpha</span><span>开发演示 · 不包含真实 Seller 数据</span></footer></div></main></div>
}
