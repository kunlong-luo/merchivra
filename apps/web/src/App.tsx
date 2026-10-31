import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { AuthViewer } from './lib/auth'
import { fetchPing } from './lib/api'
import CoreWorkspace from './CoreWorkspace'
import OperationsWorkspace from './OperationsWorkspace'
import TeamWorkspace from './TeamWorkspace'
import {
  fetchDemoOverview, fetchDemoSyncRuns, startDemoSync,
  fetchDemoOrders, fetchDemoListings, fetchDemoInventory, fetchDemoCoreStatus
} from './lib/demo'
import { Chip, DataTable, errorText, money, qualityLabel, StatePanel, timestamp } from './ui'

type View = 'core' | 'ops' | 'team' | 'overview' | 'orders' | 'catalog' | 'inventory' | 'sync'
const navigation: { key: View; label: string; symbol: string }[] = [
  { key: 'core', label: '核心业务数据', symbol: '▣' },
  { key: 'ops', label: '运营自动化', symbol: '✳' },
  { key: 'team', label: '团队与权限', symbol: '♙' },
  { key: 'overview', label: '经营总览', symbol: '◫' },
  { key: 'orders', label: '订单管理', symbol: '▤' },
  { key: 'catalog', label: '商品与 Listing', symbol: '▧' },
  { key: 'inventory', label: 'FBA 库存', symbol: '▥' },
  { key: 'sync', label: '模拟同步', symbol: '↻' }
]
export default function App({ viewer, signingOut, onLogout, logoutError }: {
  viewer: AuthViewer; signingOut: boolean; onLogout: () => void; logoutError?: string
}) {
  const [activeView, setActiveView] = useState<View>('core')
  const [accountId, setAccountId] = useState('')
  const [syncMessage, setSyncMessage] = useState('')
  const queryClient = useQueryClient()
  const demoMode = !['core', 'ops', 'team'].includes(activeView)
  const ping = useQuery({ queryKey: ['api', 'ping'], queryFn: fetchPing, staleTime: 30_000, retry: 1 })
  const overview = useQuery({
    queryKey: ['api', 'demo', 'overview'], queryFn: fetchDemoOverview,
    enabled: demoMode, staleTime: 60_000, retry: 1
  })
  const effectiveAccountId = overview.data?.accounts.some(a => a.id === accountId)
    ? accountId : (overview.data?.accounts[0]?.id ?? '')
  const coreStatus = useQuery({
    queryKey: ['api', 'demo', 'core-status'], queryFn: fetchDemoCoreStatus,
    enabled: activeView === 'sync', staleTime: 15_000, retry: 1
  })
  const syncRuns = useQuery({
    queryKey: ['api', 'demo', 'sync-runs'], queryFn: fetchDemoSyncRuns,
    enabled: activeView === 'sync', staleTime: 15_000
  })
  const sync = useMutation({
    mutationFn: startDemoSync,
    onSuccess: run => {
      setSyncMessage(run.status === 'SUCCEEDED'
        ? '模拟同步成功：快照和运行记录已保存'
        : '模拟失败已记录：快照未覆盖，可重新创建任务')
      void queryClient.invalidateQueries({ queryKey: ['api', 'demo'] })
    },
    onError: error => setSyncMessage(errorText(error, '无法启动模拟同步'))
  })
  const shopOrders = useQuery({
    queryKey: ['api', 'demo', 'orders', effectiveAccountId],
    queryFn: () => fetchDemoOrders(effectiveAccountId),
    enabled: !!effectiveAccountId && activeView === 'orders', staleTime: 30_000
  })
  const shopListings = useQuery({
    queryKey: ['api', 'demo', 'listings', effectiveAccountId],
    queryFn: () => fetchDemoListings(effectiveAccountId),
    enabled: !!effectiveAccountId && activeView === 'catalog', staleTime: 30_000
  })
  const shopInventory = useQuery({
    queryKey: ['api', 'demo', 'inventory', effectiveAccountId],
    queryFn: () => fetchDemoInventory(effectiveAccountId),
    enabled: !!effectiveAccountId && activeView === 'inventory', staleTime: 30_000
  })
  const account = overview.data?.accounts.find(a => a.id === effectiveAccountId)
  const summary = overview.data?.summaries.find(s => s.accountId === effectiveAccountId)
  const orders = overview.data?.orders.filter(o => o.accountId === effectiveAccountId) ?? []
  const tabTitle = navigation.find(n => n.key === activeView)?.label ?? '经营总览'
  const apiStatus = ping.isPending ? '连接检查中'
    : ping.isError ? 'API 不可用' : ping.data?.status === 'ok' ? 'API 已连接' : 'API 状态异常'
  const shop = (activeView === 'core' || activeView === 'ops' || activeView === 'team')
    ? '核心业务工作台' : '本地演示环境'
  const headingText = activeView === 'core'
    ? '基于当前会话的租户权限读取 PostgreSQL 核心业务表。'
    : activeView === 'ops' ? '通过规则自动检查来源数据，生成可认领、可审计的运营待办。'
    : activeView === 'team' ? '使用一次性邀请开通独立管理员，并审计 Orders 安全审核员授权。'
    : '集中查看店铺运营数据，所有示例均来自本地 Mock Gateway。'
  return <div className="workspace">
    <a className="skip-link" href="#main-content">跳转到主要内容</a>
    <aside className="sidebar">
      <div className="brand-wrap"><div className="brand-icon">M</div>
        <div className="brand-copy"><strong>merchivra</strong><small>SELLER OPERATIONS</small></div></div>
      <div className="sidebar-section-title">工作空间</div>
      <nav className="nav-links" aria-label="主导航">
        {navigation.filter(n => viewer.isOwner || n.key === 'core').map(item =>
          <button key={item.key} type="button"
            className={'nav-link' + (activeView === item.key ? ' active' : '')}
            aria-current={activeView === item.key ? 'page' : undefined}
            onClick={() => setActiveView(item.key)}>
            <span className="nav-icon" aria-hidden="true">{item.symbol}</span>{item.label}
          </button>)}
        {viewer.isOwner && <div className="nav-link nav-disabled" aria-disabled="true">
          <span className="nav-icon">▦</span> 财务与对账 <span className="soon">后续</span></div>}
      </nav>
      <div className="sidebar-spacer" />
      <div className="sidebar-info"><span className="local-indicator" />
        <div><strong>{shop}</strong><small>{demoMode ? '纯虚构数据 · 无真实连接' : '会话租户隔离 · 数据记录标注来源'}</small></div></div>
      <a className="sidebar-repo" href="https://github.com/kunlong-luo/merchivra"
        target="_blank" rel="noopener noreferrer">GitHub 源代码 <span aria-hidden="true">↗</span></a>
    </aside>
    <main id="main-content" className="main-panel" tabIndex={-1}>
      <header className="topbar"><div className="breadcrumb">
        工作空间 <span>/</span> <strong>{tabTitle}</strong></div>
        <div className="topbar-right">
          <span className={'connection' + (ping.isError ? ' connectionError' : '')}
            role="status" aria-label="后端 API 连接状态">
            <span className="connection-dot" aria-hidden="true" />{apiStatus}</span>
          <span className="avatar" aria-label="已登录的工作台">✓</span>
          <button className="logout-control" type="button" disabled={signingOut}
            onClick={onLogout}>{signingOut ? '退出中…' : '退出登录'}</button>
        </div>
      </header>
      <div className="content">
        {logoutError && <div role="alert" className="setup-error">{logoutError}</div>}
        <div className="page-heading"><div>
          <div className="eyebrow">{demoMode ? 'COMMERCE INTELLIGENCE / DEMO' : 'SECURE ERP / CORE DATA'}</div>
          <h1>{tabTitle}</h1><p>{headingText}</p>
        </div>
          {viewer.isOwner && demoMode && <div className="heading-actions">
            <label className="shop-filter"><span>当前店铺</span>
              <select value={effectiveAccountId} disabled={!overview.data?.accounts.length}
                aria-label="切换演示店铺" onChange={e => setAccountId(e.target.value)}>
                {(overview.data?.accounts ?? []).map(a =>
                  <option key={a.id} value={a.id}>{a.name}</option>)}
              </select>
            </label>
            <button className="refresh-button" type="button" disabled={overview.isFetching}
              onClick={() => void overview.refetch()}>
              <span aria-hidden="true">↻</span> {overview.isFetching ? '读取中' : '刷新'}</button>
          </div>}
        </div>
        {viewer.isOwner && demoMode && <div className="mock-banner" role="status">
          <span className="mock-banner-icon" aria-hidden="true">✦</span>
          <div><strong>MOCK 演示模式</strong>
            <span>全部商品、订单、账户和库存均为虚构数据；无 Amazon 凭证、实际财务入账或实时库存。请勿用于经营决策。</span></div>
          <span className="mock-pill">DEMO ONLY</span>
        </div>}
        {activeView === 'core' ? <CoreWorkspace tenantId={viewer.activeTenantId} isOwner={viewer.isOwner} />
          : activeView === 'ops' && viewer.isOwner ? <OperationsWorkspace tenantId={viewer.activeTenantId} />
          : activeView === 'team' && viewer.isOwner ? <TeamWorkspace tenantId={viewer.activeTenantId}
            currentUserId={viewer.userId} />
          : overview.isPending ? <StatePanel title="正在读取模拟数据…" detail="正在连接后端 Mock Marketplace Gateway" />
          : overview.isError ? <StatePanel title="暂时无法加载业务演示"
              detail={errorText(overview.error, '请检查服务状态及 Mock 配置。独立运行后端需开启 MERCHIVRA_DEMO_ENABLED。')}
              error retry={() => void overview.refetch()} />
          : overview.data && account && summary && <>
              <div className="section-meta"><span><span className="data-dot" />
                {account.name} · {account.marketplaceId} · {account.region}</span>
                <span>模拟数据截止 {timestamp(overview.data.fixtureAsOf)}</span></div>
              {activeView === 'overview' && <>
                <section className="metric-grid" aria-label="经营数据摘要">
                  <Metric title="订单预计商品收入" value={money(summary.provisionalSales)}
                    foot="按订单来源口径，不等于平台入账" icon="↗" />
                  <Metric title="订单数" value={String(summary.orderCount) + ' 笔'}
                    foot="仅当前所选演示店铺" icon="▤" />
                  <Metric title="已观测退款" value={money(summary.observedRefunds)}
                    foot="单独列示，不与收入重复相加" icon="↙" />
                  <Metric title="预估订单贡献额" value="不可计算"
                    foot="缺少完整成本和费用；不显示虚假利润" icon="○" accent />
                </section>
                <section className="two-column">
                  <div className="panel"><div className="panel-heading">
                    <div><h2>近期订单</h2><p>来源状态、退款和财务状态分开记录</p></div>
                    <button type="button" className="text-button" onClick={() => setActiveView('orders')}>查看全部 →</button></div>
                    <DataTable rows={orders} rowKey={r => r.id} label="可水平滚动的演示数据表格" columns={[
                      { title: '订单编号', className: 'strong-cell', render: r => r.externalOrderId },
                      { title: 'SKU', render: r => r.sellerSku },
                      { title: '预计金额', className: 'nowrap', render: r => money(r.provisionalSales) },
                      { title: '履约状态', render: r => <Chip positive={r.fulfillmentStatus === 'SHIPPED'}>{qualityLabel(r.fulfillmentStatus)}</Chip> }
                    ]} />
                    {!orders.length && <div className="empty-state">该店铺暂无订单</div>}
                  </div>
                  <div className="panel insight-panel">
                    <div className="panel-heading"><div><h2>需要关注</h2><p>仅反映模拟数据的质量情况</p></div></div>
                    <Insight symbol="!" title={summary.unmappedListings + ' 个 SKU 待映射'}
                      detail="原始 Listing 已保留，不影响事实入库" />
                    <Insight symbol="~" title={summary.unknownInventoryCount + ' 条库存数量未知'}
                      detail="未知 ≠ 零库存，不触发缺货结论" />
                    <Insight symbol="◌" title="平台财务交易未接入" detail="预计销售与实际入账不得合并计算" />
                    <div className="insight-footer">数据质量优先于虚假的完整指标</div>
                  </div>
                </section>
                <div className="note-panel"><span className="note-mark">i</span>
                  <div><strong>关于本页面指标</strong><p>演示数据按店铺和原币种独立汇总。预计商品收入未扣除单独显示的退款；真实财务金额、结算收入和贡献利润尚未接入。</p></div></div>
              </>}
              {activeView === 'orders' && <section className="panel full-panel">
                <div className="panel-heading"><div><h2>订单列表</h2><p>订单业务事实与退款、财务状态保持独立</p></div>
                  <span className="count-pill">{shopOrders.data?.length ?? 0} 笔</span></div>
                {shopOrders.isPending ? <div className="empty-state">读取订单中…</div>
                  : shopOrders.isError ? <div className="empty-state">订单查询失败，请检查本地服务</div>
                  : <DataTable rows={shopOrders.data ?? []} rowKey={r => r.id}
                    label="可水平滚动的演示数据表格" columns={[
                      { title: 'Amazon 订单（模拟）', className: 'strong-cell', render: r => r.externalOrderId },
                      { title: 'Seller SKU', render: r => r.sellerSku },
                      { title: '预计商品收入', className: 'nowrap', render: r => money(r.provisionalSales) },
                      { title: '退款', render: r => <><Chip muted={r.refundStatus === 'NONE'}>
                        {r.refundStatus === 'NONE' ? '无退款' : '部分退款'}</Chip>
                        {r.refundStatus !== 'NONE' && <div className="secondary-cell">{money(r.refundAmount)}</div>}</> },
                      { title: '履约', render: r => <Chip positive={r.fulfillmentStatus === 'SHIPPED'}>{qualityLabel(r.fulfillmentStatus)}</Chip> },
                      { title: '财务', render: () => <Chip muted>未入账</Chip> },
                      { title: '来源更新时间', className: 'small-cell', render: r => timestamp(r.sourceUpdatedAt) }
                    ]} />}
                {shopOrders.data?.length === 0 && <div className="empty-state">当前店铺没有 Mock 订单</div>}
              </section>}
              {activeView === 'catalog' && <section className="panel full-panel">
                <div className="panel-heading"><div><h2>Listing 与 SKU 归属</h2><p>ASIN、Seller SKU 与内部 SKU 不是同一个标识</p></div>
                  <span className="count-pill">{shopListings.data?.length ?? 0} 个 Listing</span></div>
                {shopListings.isPending ? <div className="empty-state">读取 Listing 中…</div>
                  : shopListings.isError ? <div className="empty-state">Listing 查询失败，请检查本地服务</div>
                  : <DataTable rows={shopListings.data ?? []} rowKey={r => r.id}
                    label="可水平滚动的演示数据表格" columns={[
                      { title: 'Seller SKU', className: 'strong-cell', render: r => r.sellerSku },
                      { title: 'ASIN（模拟）', render: r => r.asin },
                      { title: '内部 SKU', render: r => r.internalSku ?? '—' },
                      { title: '映射状态', render: r => <Chip positive={r.mappingStatus === 'ACTIVE'}>
                        {qualityLabel(r.mappingStatus)}</Chip> }
                    ]} />}
                <div className="panel-caption">待映射 SKU 仍保留来源记录。编辑、审核和版本化映射将在后续阶段接入。</div>
              </section>}
              {activeView === 'inventory' && <section className="panel full-panel">
                <div className="panel-heading"><div><h2>FBA 库存快照</h2>
                  <p>观测型库存，不是仓库出入库流水；未知数值不可当 0</p></div>
                  <span className="count-pill">{shopInventory.data?.length ?? 0} 条观测</span></div>
                {shopInventory.isPending ? <div className="empty-state">读取库存中…</div>
                  : shopInventory.isError ? <div className="empty-state">库存查询失败，请检查本地服务</div>
                  : <DataTable rows={shopInventory.data ?? []} rowKey={r => r.id}
                    label="可水平滚动的演示数据表格" columns={[
                      { title: 'Seller SKU', className: 'strong-cell', render: r => r.sellerSku },
                      { title: '可售', className: 'quantity-cell', render: r => <>{r.fulfillable ?? '未知'}
                        {r.fulfillable === 0 && <span className="zero-label">已观测为 0</span>}</> },
                      { title: '预留', render: r => r.reserved ?? '未知' },
                      { title: '数据质量', render: r => <Chip positive={r.quality === 'OBSERVED'}>{qualityLabel(r.quality)}</Chip> },
                      { title: '来源观测时间', className: 'small-cell', render: r => timestamp(r.observedAt) }
                    ]} />}
                <div className="panel-caption">这是静态 Mock 快照，不提供实时缺货告警，也不代表 FBM 库存。</div>
              </section>}
              {activeView === 'sync' && <section className="panel full-panel">
                <div className="panel-heading"><div><h2>本地模拟同步记录</h2>
                  <p>运行历史持久化到 PostgreSQL；只刷新虚构数据</p></div>
                  <span className="count-pill">最近 {syncRuns.data?.length ?? 0} 次</span></div>
                <div className="sync-actions">
                  <button type="button" className="primary-button" disabled={sync.isPending}
                    onClick={() => sync.mutate(false)}>{sync.isPending ? '正在同步…' : '开始模拟同步'}</button>
                  <button type="button" className="refresh-button" disabled={sync.isPending}
                    onClick={() => sync.mutate(true)}>模拟失败</button>
                  <span className="sync-hint">失败后创建新任务可重试；历史失败记录不会消失。</span>
                </div>
                {syncMessage && <div className="panel-caption" role="status">{syncMessage}</div>}
                <section className="core-storage" aria-label="核心数据库入库情况">
                  <div className="core-storage-header"><strong>PostgreSQL · 核心实体入库</strong>
                    <span>固定虚构租户 · 非正式 Amazon 业务数据</span></div>
                  {coreStatus.isPending ? <div className="core-storage-note">正在读取核心数据表…</div>
                    : coreStatus.isError ? <div className="core-storage-note">读取失败，请核查数据库迁移和演示服务。</div>
                    : coreStatus.data && <>
                      <div className="core-count-grid">
                        {([['店铺', coreStatus.data.accounts], ['内部 SKU', coreStatus.data.internalSkus],
                          ['Listing', coreStatus.data.listings], ['订单', coreStatus.data.orders],
                          ['订单项', coreStatus.data.orderItems],
                          ['FBA 观测', coreStatus.data.inventoryObservations]] as const)
                            .map(([title, count]) => <div key={title}><strong>{count}</strong><span>{title}</span></div>)}
                      </div>
                      <p className="core-storage-note">{coreStatus.data.accounts === 0
                        ? '还未运行过成功的模拟同步，点击上方按钮建立核心数据。'
                        : '成功同步后持久化；同一虚构来源重复导入不会重复新增订单与库存观测。'}</p>
                    </>}
                </section>
                {syncRuns.isPending ? <div className="empty-state">读取同步历史中…</div>
                  : syncRuns.isError ? <div className="empty-state">无法读取同步记录，请检查数据库</div>
                  : !syncRuns.data?.length ? <div className="empty-state">暂无任务。初始 Mock 快照在首次启动时保存。</div>
                  : <DataTable rows={syncRuns.data} rowKey={r => r.id} label="可水平滚动的演示数据表格" columns={[
                    { title: '任务 ID', className: 'strong-cell', render: r => r.id.slice(0, 8) + '…' },
                    { title: '结果', render: r => <Chip positive={r.status === 'SUCCEEDED'}>
                      {r.status === 'SUCCEEDED' ? '已完成' : '失败，可重试'}</Chip> },
                    { title: '快照记录数', render: r => r.fixtureCount },
                    { title: '完成时间', className: 'small-cell', render: r => timestamp(r.finishedAt) },
                    { title: '说明', render: r => r.failureCode ?? '本地快照写入' }
                  ]} />}
                <div className="panel-caption">仅供本地演示，不代表真实 Amazon 同步、后台 Worker、自动重试或生产权限已实现。</div>
              </section>}
            </>}
        <footer className="footer"><span>© Merchivra · Pre-alpha</span>
          <span>开发演示 · 不包含真实 Seller 数据</span></footer>
      </div>
    </main>
  </div>
}
function Metric({ title, value, foot, icon, accent = false }: {
  title: string; value: string; foot: string; icon: string; accent?: boolean
}) {
  return <div className={'metric-card' + (accent ? ' accent-card' : '')}>
    <div className="metric-label">{title} <span className="metric-icon">{icon}</span></div>
    <div className={'metric-value' + (accent ? ' not-ready' : '')}>{value}</div>
    <div className="metric-foot">{foot}</div></div>
}
function Insight({ symbol, title, detail }: { symbol: string; title: string; detail: string }) {
  return <div className="insight-row">
    <span className="insight-icon warning">{symbol}</span>
    <div><strong>{title}</strong><small>{detail}</small></div></div>
}
