<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { AuthViewer } from './lib/auth'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { fetchPing } from './lib/api'
import CoreWorkspace from './CoreWorkspace.vue'
import OperationsWorkspace from './OperationsWorkspace.vue'
import { fetchDemoOverview, fetchDemoSyncRuns, startDemoSync, fetchDemoOrders, fetchDemoListings, fetchDemoInventory, fetchDemoCoreStatus } from './lib/demo'

type View = 'core' | 'ops' | 'overview' | 'orders' | 'catalog' | 'inventory' | 'sync'

defineProps<{ viewer: AuthViewer; signingOut: boolean }>()
const emit = defineEmits<{ logout: [] }>()
const activeView = ref<View>('core')
const accountId = ref('')
const queryClient = useQueryClient()
const syncMessage = ref('')
const { data: ping, isError: pingError, isPending: pingPending } = useQuery({
  queryKey: ['api', 'ping'], queryFn: fetchPing, staleTime: 30_000, retry: 1
})
const {
  data: overview,
  isPending: demoPending,
  isError: demoError,
  error: demoFailure,
  isFetching: demoFetching,
  refetch
} = useQuery({
  queryKey: ['api', 'demo', 'overview'], queryFn: fetchDemoOverview,
  enabled: computed(() => activeView.value !== 'core' && activeView.value !== 'ops'), staleTime: 60_000, retry: 1
})

watch(overview, value => {
  if (value?.accounts.length && !value.accounts.some(a => a.id === accountId.value)) {
    accountId.value = value.accounts[0].id
  }
}, { immediate: true })

const { data: coreStatus, isPending: coreStatusPending, isError: coreStatusError } = useQuery({
  queryKey: ['api', 'demo', 'core-status'],
  queryFn: fetchDemoCoreStatus,
  enabled: computed(() => activeView.value === 'sync'),
  staleTime: 15_000,
  retry: 1
})

const { data: syncRuns, isPending: syncRunsPending, isError: syncRunsError } = useQuery({
  queryKey: ['api', 'demo', 'sync-runs'],
  queryFn: fetchDemoSyncRuns,
  enabled: computed(() => activeView.value === 'sync'),
  staleTime: 15_000
})
const { mutate: runDemoSync, isPending: syncPending } = useMutation({
  mutationFn: startDemoSync,
  onSuccess: run => {
    syncMessage.value = run.status === 'SUCCEEDED'
      ? '模拟同步成功：快照和运行记录已保存'
      : '模拟失败已记录：快照未覆盖，可重新创建任务'
    void queryClient.invalidateQueries({ queryKey: ['api', 'demo'] })
  },
  onError: error => {
    syncMessage.value = error instanceof Error ? error.message : '无法启动模拟同步'
  }
})
const { data: shopOrders, isPending: shopOrdersPending, isError: shopOrdersError } = useQuery({
  queryKey: ['api', 'demo', 'orders', accountId],
  queryFn: () => fetchDemoOrders(accountId.value),
  enabled: computed(() => !!accountId.value && activeView.value === 'orders'),
  staleTime: 30_000
})
const { data: shopListings, isPending: shopListingsPending, isError: shopListingsError } = useQuery({
  queryKey: ['api', 'demo', 'listings', accountId],
  queryFn: () => fetchDemoListings(accountId.value),
  enabled: computed(() => !!accountId.value && activeView.value === 'catalog'),
  staleTime: 30_000
})
const { data: shopInventory, isPending: shopInventoryPending, isError: shopInventoryError } = useQuery({
  queryKey: ['api', 'demo', 'inventory', accountId],
  queryFn: () => fetchDemoInventory(accountId.value),
  enabled: computed(() => !!accountId.value && activeView.value === 'inventory'),
  staleTime: 30_000
})

const account = computed(() => overview.value?.accounts.find(a => a.id === accountId.value))
const summary = computed(() => overview.value?.summaries.find(s => s.accountId === accountId.value))
const orders = computed(() => overview.value?.orders.filter(o => o.accountId === accountId.value) ?? [])
const listings = computed(() => overview.value?.listings.filter(l => l.accountId === accountId.value) ?? [])
const inventory = computed(() => overview.value?.inventory.filter(i => i.accountId === accountId.value) ?? [])
const apiStatus = computed(() => {
  if (pingPending.value) return '连接检查中'
  if (pingError.value) return 'API 不可用'
  return ping.value?.status === 'ok' ? 'API 已连接' : 'API 状态异常'
})
const navigation: { key: View; label: string; symbol: string }[] = [
  { key: 'core', label: '核心业务数据', symbol: '▣' },
  { key: 'ops', label: '运营自动化', symbol: '✳' },
  { key: 'overview', label: '经营总览', symbol: '◫' },
  { key: 'orders', label: '订单管理', symbol: '▤' },
  { key: 'catalog', label: '商品与 Listing', symbol: '▧' },
  { key: 'inventory', label: 'FBA 库存', symbol: '▥' },
  { key: 'sync', label: '模拟同步', symbol: '↻' }
]

function money(amount?: { amount: string; currency: string } | null) {
  if (!amount) return '不可计算'
  return `${amount.currency} ${amount.amount}`
}
function timestamp(value?: string | null) {
  if (!value) return '暂无来源时间'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '时间未知'
  return date.toLocaleString('zh-CN', { timeZone: 'UTC', hour12: false }) + ' UTC'
}
function qualityLabel(value: string) {
  const labels: Record<string, string> = {
    OBSERVED: '已观测',
    UNKNOWN: '未取得',
    STALE: '已过期',
    PARTIAL: '部分数据',
    UNMAPPED: '待映射',
    ACTIVE: '已映射',
    SHIPPED: '已发货',
    UNSHIPPED: '待发货',
    PARTIAL_REFUND: '部分退款',
    NOT_OBSERVED: '未取得财务数据'
  }
  return labels[value] ?? value
}
function tabTitle() {
  return navigation.find(n => n.key === activeView.value)?.label ?? '经营总览'
}
</script>

<template>
  <div class="workspace">
    <a class="skip-link" href="#main-content">跳转到主要内容</a>
    <aside class="sidebar">
      <div class="brand-wrap">
        <div class="brand-icon">M</div>
        <div class="brand-copy"><strong>merchivra</strong><small>SELLER OPERATIONS</small></div>
      </div>

      <div class="sidebar-section-title">工作空间</div>
      <nav class="nav-links" aria-label="主导航">
        <button
          v-for="item in navigation" :key="item.key" type="button"
          :class="['nav-link', { active: activeView === item.key }]"
          :aria-current="activeView === item.key ? 'page' : undefined"
          @click="activeView = item.key"
        >
          <span class="nav-icon" aria-hidden="true">{{ item.symbol }}</span>
          {{ item.label }}
        </button>
        <div class="nav-link nav-disabled" aria-disabled="true"><span class="nav-icon">▦</span> 财务与对账 <span class="soon">后续</span></div>
      </nav>

      <div class="sidebar-spacer"></div>
      <div class="sidebar-info">
        <span class="local-indicator"></span>
        <div><strong>{{ activeView === 'core' || activeView === 'ops' ? '核心业务工作台' : '本地演示环境' }}</strong><small>{{ activeView === 'core' || activeView === 'ops' ? '会话租户隔离 · 数据记录标注来源' : '纯虚构数据 · 无真实连接' }}</small></div>
      </div>
      <a class="sidebar-repo" href="https://github.com/kunlong-luo/merchivra" target="_blank" rel="noopener noreferrer">
        GitHub 源代码 <span aria-hidden="true">↗</span>
      </a>
    </aside>

    <main id="main-content" class="main-panel" tabindex="-1">
      <header class="topbar">
        <div class="breadcrumb">工作空间 <span>/</span> <strong>{{ tabTitle() }}</strong></div>
        <div class="topbar-right">
          <span :class="['connection', { connectionError: pingError }]" role="status" aria-label="后端 API 连接状态"><span class="connection-dot" aria-hidden="true"></span>{{ apiStatus }}</span>
          <span class="avatar" aria-label="已登录的工作台">✓</span>
          <button type="button" class="logout-control" :disabled="signingOut"
            @click="emit('logout')">{{ signingOut ? '退出中…' : '退出登录' }}</button>
        </div>
      </header>

      <div class="content">
        <div class="page-heading">
          <div>
            <div class="eyebrow">{{ activeView === 'core' || activeView === 'ops' ? 'SECURE ERP / CORE DATA' : 'COMMERCE INTELLIGENCE / DEMO' }}</div>
            <h1>{{ tabTitle() }}</h1>
            <p>{{ activeView === 'core' ? '基于当前会话的租户权限读取 PostgreSQL 核心业务表。' : activeView === 'ops' ? '通过规则自动检查来源数据，生成可认领、可审计的运营待办。' : '集中查看店铺运营数据，所有示例均来自本地 Mock Gateway。' }}</p>
          </div>
          <div v-if="activeView !== 'core' && activeView !== 'ops'" class="heading-actions">
            <label class="shop-filter">
              <span>当前店铺</span>
              <select v-model="accountId" :disabled="!overview?.accounts.length" aria-label="切换演示店铺">
                <option v-for="shop in overview?.accounts ?? []" :key="shop.id" :value="shop.id">{{ shop.name }}</option>
              </select>
            </label>
            <button class="refresh-button" type="button" :disabled="demoFetching" @click="refetch()">
              <span aria-hidden="true">↻</span> {{ demoFetching ? '读取中' : '刷新' }}
            </button>
          </div>
        </div>

        <div v-if="activeView !== 'core' && activeView !== 'ops'" class="mock-banner" role="status">
          <span class="mock-banner-icon" aria-hidden="true">✦</span>
          <div><strong>MOCK 演示模式</strong><span>全部商品、订单、账户和库存均为虚构数据；无 Amazon 凭证、实际财务入账或实时库存。请勿用于经营决策。</span></div>
          <span class="mock-pill">DEMO ONLY</span>
        </div>

        <CoreWorkspace v-if="activeView === 'core'" :tenant-id="viewer.activeTenantId" />

        <OperationsWorkspace v-else-if="activeView === 'ops'" :tenant-id="viewer.activeTenantId" />

        <div v-else-if="demoPending" class="state-panel">
          <div class="loading-ring"></div>
          <strong>正在读取模拟数据…</strong><p>正在连接后端 Mock Marketplace Gateway</p>
        </div>

        <div v-else-if="demoError" class="state-panel error-panel" role="alert">
          <div class="state-symbol">!</div>
          <h2>暂时无法加载业务演示</h2>
          <p>{{ demoFailure instanceof Error ? demoFailure.message : '请检查服务状态及 Mock 配置。' }}</p>
          <p>本地 Docker Compose 已配置启用 Mock；独立运行后端时需设置 MERCHIVRA_DEMO_ENABLED=true。</p>
          <button class="primary-button" @click="refetch()">重新尝试</button>
        </div>

        <div v-else-if="overview && account && summary">
          <div class="section-meta">
            <span><span class="data-dot"></span> {{ account.name }} · {{ account.marketplaceId }} · {{ account.region }}</span>
            <span>模拟数据截止 {{ timestamp(overview.fixtureAsOf) }}</span>
          </div>

          <template v-if="activeView === 'overview'">
            <section class="metric-grid" aria-label="经营数据摘要">
              <div class="metric-card">
                <div class="metric-label">订单预计商品收入 <span class="metric-icon">↗</span></div>
                <div class="metric-value">{{ money(summary.provisionalSales) }}</div>
                <div class="metric-foot">按订单来源口径，不等于平台入账</div>
              </div>
              <div class="metric-card">
                <div class="metric-label">订单数 <span class="metric-icon">▤</span></div>
                <div class="metric-value">{{ summary.orderCount }} <span class="metric-unit">笔</span></div>
                <div class="metric-foot">仅当前所选演示店铺</div>
              </div>
              <div class="metric-card">
                <div class="metric-label">已观测退款 <span class="metric-icon">↙</span></div>
                <div class="metric-value">{{ money(summary.observedRefunds) }}</div>
                <div class="metric-foot">单独列示，不与收入重复相加</div>
              </div>
              <div class="metric-card accent-card">
                <div class="metric-label">预估订单贡献额 <span class="metric-icon">○</span></div>
                <div class="metric-value not-ready">不可计算</div>
                <div class="metric-foot">缺少完整成本和费用；不显示虚假利润</div>
              </div>
            </section>

            <section class="two-column">
              <div class="panel">
                <div class="panel-heading"><div><h2>近期订单</h2><p>来源状态、退款和财务状态分开记录</p></div>
                  <button class="text-button" @click="activeView = 'orders'">查看全部 →</button></div>
                <div class="table-scroll" role="region" tabindex="0" aria-label="可水平滚动的演示数据表格">
                  <table><thead><tr><th scope="col">订单编号</th><th scope="col">SKU</th><th scope="col">预计金额</th><th scope="col">履约状态</th></tr></thead>
                    <tbody><tr v-for="order in orders" :key="order.id">
                      <td class="strong-cell">{{ order.externalOrderId }}</td><td>{{ order.sellerSku }}</td>
                      <td class="nowrap">{{ money(order.provisionalSales) }}</td>
                      <td><span class="status-chip" :class="order.fulfillmentStatus === 'SHIPPED' ? 'chip-green' : 'chip-amber'">{{ qualityLabel(order.fulfillmentStatus) }}</span></td>
                    </tr></tbody></table>
                </div>
                <div v-if="!orders.length" class="empty-state">该店铺暂无订单</div>
              </div>

              <div class="panel insight-panel">
                <div class="panel-heading"><div><h2>需要关注</h2><p>仅反映模拟数据的质量情况</p></div></div>
                <div class="insight-row"><span class="insight-icon warning">!</span><div><strong>{{ summary.unmappedListings }} 个 SKU 待映射</strong><small>原始 Listing 已保留，不影响事实入库</small></div></div>
                <div class="insight-row"><span class="insight-icon info">~</span><div><strong>{{ summary.unknownInventoryCount }} 条库存数量未知</strong><small>未知 ≠ 零库存，不触发缺货结论</small></div></div>
                <div class="insight-row"><span class="insight-icon muted">◌</span><div><strong>平台财务交易未接入</strong><small>预计销售与实际入账不得合并计算</small></div></div>
                <div class="insight-footer">数据质量优先于虚假的完整指标</div>
              </div>
            </section>

            <div class="note-panel"><span class="note-mark">i</span><div><strong>关于本页面指标</strong><p>演示数据按店铺和原币种独立汇总。预计商品收入未扣除右侧单独显示的退款；真实的财务金额、结算收入和贡献利润尚未接入。</p></div></div>
          </template>

          <section v-else-if="activeView === 'orders'" class="panel full-panel">
            <div class="panel-heading"><div><h2>订单列表</h2><p>订单业务事实与退款、财务状态保持独立</p></div><span class="count-pill">{{ shopOrders?.length ?? 0 }} 笔</span></div>
            <div v-if="shopOrdersPending" class="empty-state">读取订单中…</div>
            <div v-else-if="shopOrdersError" class="empty-state">订单查询失败，请检查本地服务</div>
            <div v-else class="table-scroll" role="region" tabindex="0" aria-label="可水平滚动的演示数据表格">
              <table><thead><tr><th scope="col">Amazon 订单（模拟）</th><th scope="col">Seller SKU</th><th scope="col">预计商品收入</th><th scope="col">退款</th><th scope="col">履约</th><th scope="col">财务</th><th scope="col">来源更新时间</th></tr></thead>
                <tbody><tr v-for="order in shopOrders ?? []" :key="order.id">
                  <td class="strong-cell">{{ order.externalOrderId }}</td><td>{{ order.sellerSku }}</td>
                  <td class="nowrap">{{ money(order.provisionalSales) }}</td>
                  <td><span :class="['status-chip', order.refundStatus === 'NONE' ? 'chip-muted' : 'chip-amber']">{{ order.refundStatus === 'NONE' ? '无退款' : '部分退款' }}</span><div v-if="order.refundStatus !== 'NONE'" class="secondary-cell">{{ money(order.refundAmount) }}</div></td>
                  <td><span class="status-chip" :class="order.fulfillmentStatus === 'SHIPPED' ? 'chip-green' : 'chip-amber'">{{ qualityLabel(order.fulfillmentStatus) }}</span></td>
                  <td><span class="status-chip chip-muted">未入账</span></td>
                  <td class="small-cell">{{ timestamp(order.sourceUpdatedAt) }}</td>
                </tr></tbody></table>
            </div>
            <div v-if="shopOrders && !shopOrders.length" class="empty-state">当前店铺没有 Mock 订单</div>
          </section>

          <section v-else-if="activeView === 'catalog'" class="panel full-panel">
            <div class="panel-heading"><div><h2>Listing 与 SKU 归属</h2><p>ASIN、Seller SKU 与内部 SKU 不是同一个标识</p></div><span class="count-pill">{{ shopListings?.length ?? 0 }} 个 Listing</span></div>
            <div v-if="shopListingsPending" class="empty-state">读取 Listing 中…</div>
            <div v-else-if="shopListingsError" class="empty-state">Listing 查询失败，请检查本地服务</div>
            <div v-else class="table-scroll" role="region" tabindex="0" aria-label="可水平滚动的演示数据表格">
              <table><thead><tr><th scope="col">Seller SKU</th><th scope="col">ASIN（模拟）</th><th scope="col">内部 SKU</th><th scope="col">映射状态</th></tr></thead>
                <tbody><tr v-for="item in shopListings ?? []" :key="item.id">
                  <td class="strong-cell">{{ item.sellerSku }}</td><td>{{ item.asin }}</td>
                  <td>{{ item.internalSku ?? '—' }}</td><td><span class="status-chip" :class="item.mappingStatus === 'ACTIVE' ? 'chip-green' : 'chip-amber'">{{ qualityLabel(item.mappingStatus) }}</span></td>
                </tr></tbody></table>
            </div>
            <div class="panel-caption">待映射 SKU 仍保留来源记录。编辑、审核和版本化映射将在后续阶段接入。</div>
          </section>

          <section v-else-if="activeView === 'inventory'" class="panel full-panel">
            <div class="panel-heading"><div><h2>FBA 库存快照</h2><p>观测型库存，不是仓库出入库流水；未知数值不可当 0</p></div><span class="count-pill">{{ shopInventory?.length ?? 0 }} 条观测</span></div>
            <div v-if="shopInventoryPending" class="empty-state">读取库存中…</div>
            <div v-else-if="shopInventoryError" class="empty-state">库存查询失败，请检查本地服务</div>
            <div v-else class="table-scroll" role="region" tabindex="0" aria-label="可水平滚动的演示数据表格">
              <table><thead><tr><th scope="col">Seller SKU</th><th scope="col">可售</th><th scope="col">预留</th><th scope="col">数据质量</th><th scope="col">来源观测时间</th></tr></thead>
                <tbody><tr v-for="item in shopInventory ?? []" :key="item.id">
                  <td class="strong-cell">{{ item.sellerSku }}</td>
                  <td class="quantity-cell">{{ item.fulfillable === null ? '未知' : item.fulfillable }} <span v-if="item.fulfillable === 0" class="zero-label">已观测为 0</span></td>
                  <td>{{ item.reserved === null ? '未知' : item.reserved }}</td>
                  <td><span class="status-chip" :class="item.quality === 'OBSERVED' ? 'chip-green' : 'chip-amber'">{{ qualityLabel(item.quality) }}</span></td>
                  <td class="small-cell">{{ timestamp(item.observedAt) }}</td>
                </tr></tbody></table>
            </div>
            <div class="panel-caption">这是静态 Mock 快照，不提供实时缺货告警，也不代表 FBM 库存。</div>
          </section>

          <section v-else-if="activeView === 'sync'" class="panel full-panel">
            <div class="panel-heading"><div><h2>本地模拟同步记录</h2><p>运行历史持久化到 PostgreSQL；只刷新虚构数据</p></div>
              <span class="count-pill">最近 {{ syncRuns?.length ?? 0 }} 次</span></div>
            <div class="sync-actions">
              <button type="button" class="primary-button" :disabled="syncPending" @click="runDemoSync(false)">{{ syncPending ? '正在同步…' : '开始模拟同步' }}</button>
              <button type="button" class="refresh-button" :disabled="syncPending" @click="runDemoSync(true)">模拟失败</button>
              <span class="sync-hint">失败后创建新任务可重试；历史失败记录不会消失。</span>
            </div>
            <div v-if="syncMessage" class="panel-caption" role="status">{{ syncMessage }}</div>
            <section class="core-storage" aria-label="核心数据库入库情况">
              <div class="core-storage-header">
                <strong>PostgreSQL · 核心实体入库</strong>
                <span>固定虚构租户 · 非正式 Amazon 业务数据</span>
              </div>
              <div v-if="coreStatusPending" class="core-storage-note">正在读取核心数据表…</div>
              <div v-else-if="coreStatusError" class="core-storage-note">读取失败，请核查数据库迁移和演示服务。</div>
              <template v-else-if="coreStatus">
                <div class="core-count-grid">
                  <div><strong>{{ coreStatus.accounts }}</strong><span>店铺</span></div>
                  <div><strong>{{ coreStatus.internalSkus }}</strong><span>内部 SKU</span></div>
                  <div><strong>{{ coreStatus.listings }}</strong><span>Listing</span></div>
                  <div><strong>{{ coreStatus.orders }}</strong><span>订单</span></div>
                  <div><strong>{{ coreStatus.orderItems }}</strong><span>订单项</span></div>
                  <div><strong>{{ coreStatus.inventoryObservations }}</strong><span>FBA 观测</span></div>
                </div>
                <p class="core-storage-note">
                  {{ coreStatus.accounts === 0 ? '还未运行过成功的模拟同步，点击上方按钮建立核心数据。' :
                    '成功同步后持久化；同一虚构来源重复导入不会重复新增订单与库存观测。' }}
                </p>
              </template>
            </section>
            <div v-if="syncRunsPending" class="empty-state">读取同步历史中…</div>
            <div v-else-if="syncRunsError" class="empty-state">无法读取同步记录，请检查数据库</div>
            <div v-else-if="!syncRuns?.length" class="empty-state">暂无任务。初始 Mock 快照在首次启动时保存。</div>
            <div v-else class="table-scroll" role="region" tabindex="0" aria-label="可水平滚动的演示数据表格">
              <table><thead><tr><th scope="col">任务 ID</th><th scope="col">结果</th><th scope="col">快照记录数</th><th scope="col">完成时间</th><th scope="col">说明</th></tr></thead>
                <tbody><tr v-for="run in syncRuns" :key="run.id">
                  <td class="strong-cell">{{ run.id.slice(0, 8) }}…</td>
                  <td><span :class="['status-chip', run.status === 'SUCCEEDED' ? 'chip-green' : 'chip-amber']">{{ run.status === 'SUCCEEDED' ? '已完成' : '失败，可重试' }}</span></td>
                  <td>{{ run.fixtureCount }}</td><td class="small-cell">{{ timestamp(run.finishedAt) }}</td>
                  <td>{{ run.failureCode ?? '本地快照写入' }}</td>
                </tr></tbody></table>
            </div>
            <div class="panel-caption">仅供本地演示，不代表真实 Amazon 同步、后台 Worker、自动重试或生产权限已实现。</div>
          </section>
        </div>

        <footer class="footer">
          <span>© Merchivra · Pre-alpha</span><span>开发演示 · 不包含真实 Seller 数据</span>
        </footer>
      </div>
    </main>
  </div>
</template>
