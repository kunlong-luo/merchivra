import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import SellerSetup from './SellerSetup'
import SandboxReplay from './SandboxReplay'
import OrdersSyncPanel from './OrdersSyncPanel'
import { coreStatusLabel, filterLoadedRows, formatSourceTime, sourceLabel } from './lib/core-view'
import { fetchCoreMarketplaces, fetchCoreListings, fetchCoreOrders, fetchCoreInventory } from './lib/core'
import { Chip, DataTable, errorText, StatePanel } from './ui'

type Section = 'listings' | 'orders' | 'inventory'
export default function CoreWorkspace({ tenantId, isOwner = true }: {
  tenantId: string; isOwner?: boolean
}) {
  const [marketplaceId, setMarketplaceId] = useState('')
  const [section, setSection] = useState<Section>('listings')
  const [searchText, setSearchText] = useState('')
  const marketplaces = useQuery({
    queryKey: ['core', 'marketplaces', tenantId], queryFn: fetchCoreMarketplaces, staleTime: 30_000, retry: 1
  })
  const rows = marketplaces.data ?? []
  const selectedId = rows.some(row => row.id === marketplaceId) ? marketplaceId : (rows[0]?.id ?? '')
  const active = rows.find(m => m.id === selectedId)
  const listings = useQuery({
    queryKey: ['core', tenantId, 'listings', selectedId],
    queryFn: () => fetchCoreListings(selectedId),
    enabled: !!selectedId && section === 'listings', staleTime: 30_000, retry: 1
  })
  const orders = useQuery({
    queryKey: ['core', tenantId, 'orders', selectedId],
    queryFn: () => fetchCoreOrders(selectedId),
    enabled: !!selectedId && section === 'orders', staleTime: 30_000, retry: 1
  })
  const inventory = useQuery({
    queryKey: ['core', tenantId, 'inventory', selectedId],
    queryFn: () => fetchCoreInventory(selectedId),
    enabled: !!selectedId && section === 'inventory', staleTime: 30_000, retry: 1
  })
  const filteredListings = filterLoadedRows(listings.data, searchText, r => [r.sellerSku, r.asin, r.internalSku])
  const filteredOrders = filterLoadedRows(orders.data, searchText, r => [r.externalOrderId])
  const filteredInventory = filterLoadedRows(inventory.data, searchText, r => [r.sellerSku])
  function reloadCurrent() {
    if (section === 'listings') void listings.refetch()
    if (section === 'orders') void orders.refetch()
    if (section === 'inventory') void inventory.refetch()
  }
  function changeSection(value: Section) { setSection(value); setSearchText('') }
  return <div className="core-workspace">
    <div className="core-intro"><strong>已认证租户 · PostgreSQL 结构化数据</strong>
      <p>这里读取当前会话租户的核心业务表。测试记录会明确标注 MOCK；来源未核验的记录不会被展示为真实 Amazon 数据。</p></div>
    {marketplaces.isPending ? <StatePanel title="正在加载可访问的 Marketplace…" />
      : marketplaces.isError ? <StatePanel title="Marketplace 读取失败"
          detail={errorText(marketplaces.error, '请检查登录会话与服务')}
          error retry={() => void marketplaces.refetch()} />
      : !rows.length ? <div className="core-onboarding">
        <div className="state-panel core-empty">
          <div className="core-empty-icon" aria-hidden="true">▧</div>
          <h2>当前租户尚未配置 Marketplace</h2>
          <p>先创建本地占位店铺并添加站点，之后即可读取当前租户的业务表。配置站点不等于授权 Amazon。</p>
          <button className="refresh-button" type="button" onClick={() => void marketplaces.refetch()}>重新检查站点</button>
        </div>
        {isOwner && <SellerSetup tenantId={tenantId} />}
      </div> : <>
        <div className="core-controls">
          <label className="shop-filter"><span>Marketplace（服务端授权范围）</span>
            <select value={selectedId} onChange={e => { setMarketplaceId(e.target.value); setSearchText('') }}
              aria-label="切换受保护 Marketplace">
              {rows.map(m => <option key={m.id} value={m.id}>
                {m.marketplaceId} · {m.region} · {m.currency}</option>)}
            </select></label>
          <button className="refresh-button" type="button" disabled={!selectedId}
            onClick={reloadCurrent}>刷新当前数据</button>
        </div>
        {active && <div className="core-marketplace-summary" role="status">
          <div><strong>{active.marketplaceId}</strong><span>{active.region} · {active.currency} · {active.timezone}</span></div>
          <Chip positive={active.connectionStatus === 'CONNECTED'}>
            账户状态：{coreStatusLabel(active.connectionStatus)}</Chip>
        </div>}
        <div className="core-subtabs" role="group" aria-label="核心数据分类">
          {([['listings', 'Listing'], ['orders', '订单'], ['inventory', 'FBA 库存']] as const)
            .map(([id, title]) => <button key={id} type="button"
              className={'core-subtab' + (section === id ? ' selected' : '')}
              aria-pressed={section === id} onClick={() => changeSection(id)}>{title}</button>)}
        </div>
        <div className="core-table-controls">
          <label className="core-search-field" htmlFor="core-record-search"><span>筛选本页记录</span>
            <input id="core-record-search" type="search" value={searchText}
              onChange={e => setSearchText(e.target.value)}
              placeholder={section === 'orders' ? '搜索已加载订单号' : '搜索已加载 SKU、ASIN'}
              autoComplete="off" aria-describedby="core-search-scope" /></label>
          <p id="core-search-scope" className="core-search-scope">
            仅筛选已读取的最多 50 条记录，不搜索服务端其他页。
            <span className="core-scroll-instruction">表格可左右滑动，键盘可在表格区域使用方向键。</span></p>
        </div>
        {section === 'listings' && <section id="core-panel-listings" className="panel full-panel"
          aria-labelledby="core-title-listings">
          <div className="panel-heading"><div><h2 id="core-title-listings">Listing（核心业务表）</h2>
            <p>独立展示 Seller SKU 和已确认的内部 SKU 映射</p></div>
            <span className="count-pill" role="status">显示 {filteredListings.length} / {listings.data?.length ?? 0} 条 · 最多读取 50 条</span></div>
          {listings.isPending ? <div className="empty-state" role="status">正在读取 Listing…</div>
            : listings.isError ? <QueryError label="Listing" error={listings.error} retry={() => void listings.refetch()} />
            : !listings.data?.length ? <div className="empty-state">当前站点尚无 Listing 记录；本地占位配置不会自动产生业务数据。</div>
            : !filteredListings.length ? <NoMatches clear={() => setSearchText('')} label="Listing" />
            : <DataTable rows={filteredListings} rowKey={r => r.id} label="可水平滚动的 Listing 表格" columns={[
              { title: 'Seller SKU', className: 'strong-cell', render: r => r.sellerSku },
              { title: 'ASIN', render: r => r.asin ?? '未知' },
              { title: '内部 SKU', render: r => r.internalSku ?? '未确认' },
              { title: '映射状态', render: r => <Chip positive={r.mappingStatus === 'ACTIVE'}>{coreStatusLabel(r.mappingStatus)}</Chip> },
              { title: '来源', render: r => <Chip muted={r.dataOrigin !== 'MOCK'}>{sourceLabel(r.dataOrigin)}</Chip> },
              { title: '来源更新时间', className: 'small-cell', render: r => formatSourceTime(r.sourceUpdatedAt) }
            ]} />}
        </section>}
        {section === 'orders' && <section id="core-panel-orders" className="panel full-panel"
          aria-labelledby="core-title-orders">
          <div className="panel-heading"><div><h2 id="core-title-orders">订单（核心业务表）</h2>
            <p>独立展示履约、退款与财务状态；不推测结算收入</p></div>
            <span className="count-pill" role="status">显示 {filteredOrders.length} / {orders.data?.length ?? 0} 笔 · 最多读取 50 笔</span></div>
          {orders.isPending ? <div className="empty-state" role="status">正在读取订单…</div>
            : orders.isError ? <QueryError label="订单" error={orders.error} retry={() => void orders.refetch()} />
            : !orders.data?.length ? <div className="empty-state">当前站点尚无订单记录；未授权来源不自动生成真实订单。</div>
            : !filteredOrders.length ? <NoMatches clear={() => setSearchText('')} label="订单" />
            : <DataTable rows={filteredOrders} rowKey={r => r.id} label="可水平滚动的订单表格" columns={[
              { title: '外部订单号', className: 'strong-cell', render: r => r.externalOrderId },
              { title: '订单项数量', render: r => r.itemCount },
              { title: '履约', render: r => coreStatusLabel(r.fulfillmentStatus) },
              { title: '退款', render: r => coreStatusLabel(r.refundStatus) },
              { title: '财务', render: r => <Chip muted>{coreStatusLabel(r.financialStatus)}</Chip> },
              { title: '来源', render: r => <Chip muted={r.dataOrigin !== 'MOCK'}>{sourceLabel(r.dataOrigin)}</Chip> },
              { title: '来源时间', className: 'small-cell', render: r => formatSourceTime(r.sourceUpdatedAt) }
            ]} />}
        </section>}
        {section === 'inventory' && <section id="core-panel-inventory" className="panel full-panel"
          aria-labelledby="core-title-inventory">
          <div className="panel-heading"><div><h2 id="core-title-inventory">最新 FBA 观测（核心业务表）</h2>
            <p>每个 Seller SKU 只显示最新一次观测；未知数量不等于零</p></div>
            <span className="count-pill" role="status">显示 {filteredInventory.length} / {inventory.data?.length ?? 0} 条 · 最多读取 50 条</span></div>
          {inventory.isPending ? <div className="empty-state" role="status">正在读取 FBA 库存观测…</div>
            : inventory.isError ? <QueryError label="FBA 库存" error={inventory.error} retry={() => void inventory.refetch()} />
            : !inventory.data?.length ? <div className="empty-state">当前站点尚无 FBA 观测，未知库存不等于零。</div>
            : !filteredInventory.length ? <NoMatches clear={() => setSearchText('')} label="SKU" />
            : <DataTable rows={filteredInventory} rowKey={r => r.id} label="可水平滚动的 FBA 库存表格" columns={[
              { title: 'Seller SKU', className: 'strong-cell', render: r => r.sellerSku },
              { title: '可售', className: 'quantity-cell', render: r => r.fulfillable ?? '未知' },
              { title: '预留', render: r => r.reserved ?? '未知' },
              { title: '质量', render: r => <Chip positive={r.quality === 'OBSERVED'}>{coreStatusLabel(r.quality)}</Chip> },
              { title: '来源', render: r => <Chip muted={r.dataOrigin !== 'MOCK'}>{sourceLabel(r.dataOrigin)}</Chip> },
              { title: '观测时间', className: 'small-cell', render: r => formatSourceTime(r.observedAt) }
            ]} />}
        </section>}
        <div className="note-panel"><span className="note-mark">i</span>
          <div><strong>数据完整性提醒</strong><p>每类最多读取 50 条，筛选仅作用于本页；分页与真实 Amazon 同步尚未完成。测试数据必须标注 MOCK，未知库存不能按 0 处理。</p></div></div>
        {isOwner && active?.connectionStatus === 'CONNECTED' &&
          <OrdersSyncPanel key={'live-orders-' + active.id} tenantId={tenantId}
            marketplaceId={active.id} isLive />}
        {isOwner && <details className="core-tools"><summary><strong>本地店铺配置与测试工具</strong>
          <span>添加 Marketplace、手动 Mock 回放 · 不连接 Amazon</span></summary>
          <div className="core-tools-content">
            <SellerSetup tenantId={tenantId} />
            {active?.connectionStatus === 'UNCONFIGURED' &&
              <OrdersSyncPanel key={'orders-' + active.id} tenantId={tenantId} marketplaceId={active.id} isLive={false} />}
            {active && <SandboxReplay key={active.id} tenantId={tenantId} marketplaceId={active.id}
              marketplaceCode={active.marketplaceId} connectionStatus={active.connectionStatus} />}
          </div></details>}
      </>}
  </div>
}
function QueryError({ label, error, retry }: { label: string; error: unknown; retry: () => void }) {
  return <div className="core-query-error" role="alert"><strong>{label} 读取失败</strong>
    <p>{errorText(error, '请检查访问权限或稍后重试')}</p>
    <button className="refresh-button" type="button" onClick={retry}>重新读取</button></div>
}
function NoMatches({ label, clear }: { label: string; clear: () => void }) {
  return <div className="empty-state">本页没有匹配的 {label}。
    <button className="text-button" type="button" onClick={clear}>清除筛选</button></div>
}
