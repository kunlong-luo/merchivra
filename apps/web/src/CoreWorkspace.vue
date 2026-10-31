<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import SellerSetup from './SellerSetup.vue'
import SandboxReplay from './SandboxReplay.vue'
import OrdersSyncPanel from './OrdersSyncPanel.vue'
import { coreStatusLabel, filterLoadedRows, formatSourceTime, sourceLabel } from './lib/core-view'
import {
  fetchCoreMarketplaces, fetchCoreListings, fetchCoreOrders, fetchCoreInventory
} from './lib/core'

type Section = 'listings' | 'orders' | 'inventory'
const props = defineProps<{ tenantId: string }>()
const marketplaceId = ref('')
const section = ref<Section>('listings')
const searchText = ref('')
watch([marketplaceId, section], () => { searchText.value = '' })
const { data: marketplaces, isPending: marketplacePending, isError: marketplaceError,
  error: marketplaceFailure, refetch: reloadMarketplaces } = useQuery({
  queryKey: ['core', 'marketplaces', props.tenantId],
  queryFn: fetchCoreMarketplaces,
  staleTime: 30_000,
  retry: 1
})

watch(marketplaces, rows => {
  if (!rows?.some(row => row.id === marketplaceId.value)) {
    marketplaceId.value = rows?.[0]?.id ?? ''
  }
}, { immediate: true })

const activeMarketplace = computed(() => marketplaces.value?.find(m => m.id === marketplaceId.value))
const searchPlaceholder = computed(() => section.value === 'orders'
  ? '搜索已加载订单号' : '搜索已加载 SKU、ASIN')

const { data: listings, isPending: listingPending, isError: listingError, error: listingFailure,
  refetch: reloadListings } = useQuery({
  queryKey: ['core', props.tenantId, 'listings', marketplaceId],
  queryFn: () => fetchCoreListings(marketplaceId.value),
  enabled: computed(() => !!marketplaceId.value && section.value === 'listings'),
  staleTime: 30_000,
  retry: 1
})
const { data: orders, isPending: orderPending, isError: orderError, error: orderFailure,
  refetch: reloadOrders } = useQuery({
  queryKey: ['core', props.tenantId, 'orders', marketplaceId],
  queryFn: () => fetchCoreOrders(marketplaceId.value),
  enabled: computed(() => !!marketplaceId.value && section.value === 'orders'),
  staleTime: 30_000,
  retry: 1
})
const { data: inventory, isPending: stockPending, isError: stockError, error: stockFailure,
  refetch: reloadInventory } = useQuery({
  queryKey: ['core', props.tenantId, 'inventory', marketplaceId],
  queryFn: () => fetchCoreInventory(marketplaceId.value),
  enabled: computed(() => !!marketplaceId.value && section.value === 'inventory'),
  staleTime: 30_000,
  retry: 1
})

const filteredListings = computed(() => filterLoadedRows(listings.value, searchText.value,
  row => [row.sellerSku, row.asin, row.internalSku]))
const filteredOrders = computed(() => filterLoadedRows(orders.value, searchText.value,
  row => [row.externalOrderId]))
const filteredInventory = computed(() => filterLoadedRows(inventory.value, searchText.value,
  row => [row.sellerSku]))

function reloadCurrent() {
  if (section.value === 'listings') void reloadListings()
  if (section.value === 'orders') void reloadOrders()
  if (section.value === 'inventory') void reloadInventory()
}
function observed(value: number | null) {
  return value === null ? '未知' : String(value)
}
</script>

<template>
  <div class="core-workspace">
    <div class="core-intro">
      <strong>已认证租户 · PostgreSQL 结构化数据</strong>
      <p>这里读取当前会话租户的核心业务表。测试记录会明确标注 MOCK；来源未核验的记录不会被展示为真实 Amazon 数据。</p>
    </div>

    <div v-if="marketplacePending" class="state-panel">
      <strong>正在加载可访问的 Marketplace…</strong>
    </div>
    <div v-else-if="marketplaceError" class="state-panel error-panel" role="alert">
      <h2>Marketplace 读取失败</h2>
      <p>{{ marketplaceFailure instanceof Error ? marketplaceFailure.message : '请检查登录会话与服务' }}</p>
      <button class="refresh-button" type="button" @click="reloadMarketplaces()">重试</button>
    </div>
    <div v-else-if="!marketplaces?.length" class="core-onboarding">
      <div class="state-panel core-empty">
        <div class="core-empty-icon" aria-hidden="true">▧</div>
        <h2>当前租户尚未配置 Marketplace</h2>
        <p>先创建本地占位店铺并添加站点，之后即可读取当前租户的业务表。配置站点不等于授权 Amazon。</p>
        <button class="refresh-button" type="button" @click="reloadMarketplaces()">重新检查站点</button>
      </div>
      <SellerSetup :tenant-id="tenantId" />
    </div>
    <template v-else>
      <div class="core-controls">
        <label class="shop-filter">
          <span>Marketplace（服务端授权范围）</span>
          <select v-model="marketplaceId" aria-label="切换受保护 Marketplace">
            <option v-for="market in marketplaces" :key="market.id" :value="market.id">
              {{ market.marketplaceId }} · {{ market.region }} · {{ market.currency }}
            </option>
          </select>
        </label>
        <button class="refresh-button" type="button" :disabled="!marketplaceId" @click="reloadCurrent()">刷新当前数据</button>
      </div>

      <div v-if="activeMarketplace" class="core-marketplace-summary" role="status">
        <div><strong>{{ activeMarketplace.marketplaceId }}</strong>
          <span>{{ activeMarketplace.region }} · {{ activeMarketplace.currency }} · {{ activeMarketplace.timezone }}</span></div>
        <span class="status-chip" :class="activeMarketplace.connectionStatus === 'CONNECTED' ? 'chip-green' : 'chip-amber'">
          账户状态：{{ coreStatusLabel(activeMarketplace.connectionStatus) }}
        </span>
      </div>
      <div class="core-subtabs" role="group" aria-label="核心数据分类">
        <button v-for="tab in [
          { id: 'listings', label: 'Listing' },
          { id: 'orders', label: '订单' },
          { id: 'inventory', label: 'FBA 库存' }
        ] as const" :key="tab.id" type="button"
          :class="['core-subtab', { selected: section === tab.id }]"
          :aria-pressed="section === tab.id"
          @click="section = tab.id">{{ tab.label }}</button>
      </div>

      <div class="core-table-controls">
        <label class="core-search-field" for="core-record-search">
          <span>筛选本页记录</span>
          <input id="core-record-search" v-model="searchText" type="search"
            :placeholder="searchPlaceholder" autocomplete="off" aria-describedby="core-search-scope" />
        </label>
        <p id="core-search-scope" class="core-search-scope">仅筛选已读取的最多 50 条记录，不搜索服务端其他页。<span class="core-scroll-instruction">表格可左右滑动，键盘可在表格区域使用方向键。</span></p>
      </div>
      <section v-if="section === 'listings'" id="core-panel-listings" class="panel full-panel" aria-labelledby="core-title-listings">
        <div class="panel-heading"><div><h2 id="core-title-listings">Listing（核心业务表）</h2><p>独立展示 Seller SKU 和已确认的内部 SKU 映射</p></div>
          <span class="count-pill" role="status">显示 {{ filteredListings.length }} / {{ listings?.length ?? 0 }} 条 · 最多读取 50 条</span></div>
        <div v-if="listingPending" class="empty-state" role="status">正在读取 Listing…</div>
        <div v-else-if="listingError" class="core-query-error" role="alert">
          <strong>Listing 读取失败</strong>
          <p>{{ listingFailure instanceof Error ? listingFailure.message : '请检查访问权限或稍后重试' }}</p>
          <button class="refresh-button" type="button" @click="reloadListings()">重新读取</button>
        </div>
        <div v-else-if="!listings?.length" class="empty-state">当前站点尚无 Listing 记录；本地占位配置不会自动产生业务数据。</div>
        <div v-else-if="!filteredListings.length" class="empty-state">本页没有匹配的 Listing。 <button class="text-button" type="button" @click="searchText = ''">清除筛选</button></div>
        <div v-else class="table-scroll core-data-scroll" role="region" tabindex="0" aria-label="可水平滚动的 Listing 表格">
          <table><thead><tr><th scope="col">Seller SKU</th><th scope="col">ASIN</th><th scope="col">内部 SKU</th><th scope="col">映射状态</th><th scope="col">来源</th><th scope="col">来源更新时间</th></tr></thead>
            <tbody><tr v-for="item in filteredListings" :key="item.id">
              <td class="strong-cell">{{ item.sellerSku }}</td>
              <td>{{ item.asin ?? '未知' }}</td><td>{{ item.internalSku ?? '未确认' }}</td>
              <td><span class="status-chip" :class="item.mappingStatus === 'ACTIVE' ? 'chip-green' : 'chip-amber'">{{ coreStatusLabel(item.mappingStatus) }}</span></td>
              <td><span :class="['status-chip', item.dataOrigin === 'MOCK' ? 'chip-amber' : 'chip-muted']">{{ sourceLabel(item.dataOrigin) }}</span></td>
              <td class="small-cell">{{ formatSourceTime(item.sourceUpdatedAt) }}</td>
            </tr></tbody></table>
        </div>
      </section>

      <section v-else-if="section === 'orders'" id="core-panel-orders" class="panel full-panel" aria-labelledby="core-title-orders">
        <div class="panel-heading"><div><h2 id="core-title-orders">订单（核心业务表）</h2><p>独立展示履约、退款与财务状态；不推测结算收入</p></div>
          <span class="count-pill" role="status">显示 {{ filteredOrders.length }} / {{ orders?.length ?? 0 }} 笔 · 最多读取 50 笔</span></div>
        <div v-if="orderPending" class="empty-state" role="status">正在读取订单…</div>
        <div v-else-if="orderError" class="core-query-error" role="alert">
          <strong>订单读取失败</strong>
          <p>{{ orderFailure instanceof Error ? orderFailure.message : '请检查访问权限或稍后重试' }}</p>
          <button class="refresh-button" type="button" @click="reloadOrders()">重新读取</button>
        </div>
        <div v-else-if="!orders?.length" class="empty-state">当前站点尚无订单记录；未授权来源不自动生成真实订单。</div>
        <div v-else-if="!filteredOrders.length" class="empty-state">本页没有匹配的订单。 <button class="text-button" type="button" @click="searchText = ''">清除筛选</button></div>
        <div v-else class="table-scroll core-data-scroll" role="region" tabindex="0" aria-label="可水平滚动的订单表格">
          <table><thead><tr><th scope="col">外部订单号</th><th scope="col">订单项数量</th><th scope="col">履约</th><th scope="col">退款</th><th scope="col">财务</th><th scope="col">来源</th><th scope="col">来源时间</th></tr></thead>
            <tbody><tr v-for="item in filteredOrders" :key="item.id">
              <td class="strong-cell">{{ item.externalOrderId }}</td><td>{{ item.itemCount }}</td>
              <td>{{ coreStatusLabel(item.fulfillmentStatus) }}</td><td>{{ coreStatusLabel(item.refundStatus) }}</td>
              <td><span class="status-chip chip-muted">{{ coreStatusLabel(item.financialStatus) }}</span></td>
              <td><span :class="['status-chip', item.dataOrigin === 'MOCK' ? 'chip-amber' : 'chip-muted']">{{ sourceLabel(item.dataOrigin) }}</span></td>
              <td class="small-cell">{{ formatSourceTime(item.sourceUpdatedAt) }}</td>
            </tr></tbody></table>
        </div>
      </section>

      <section v-else id="core-panel-inventory" class="panel full-panel" aria-labelledby="core-title-inventory">
        <div class="panel-heading"><div><h2 id="core-title-inventory">最新 FBA 观测（核心业务表）</h2>
          <p>每个 Seller SKU 只显示最新一次观测；未知数量不等于零</p></div>
          <span class="count-pill" role="status">显示 {{ filteredInventory.length }} / {{ inventory?.length ?? 0 }} 条 · 最多读取 50 条</span></div>
        <div v-if="stockPending" class="empty-state" role="status">正在读取 FBA 库存观测…</div>
        <div v-else-if="stockError" class="core-query-error" role="alert">
          <strong>FBA 库存读取失败</strong>
          <p>{{ stockFailure instanceof Error ? stockFailure.message : '请检查访问权限或稍后重试' }}</p>
          <button class="refresh-button" type="button" @click="reloadInventory()">重新读取</button>
        </div>
        <div v-else-if="!inventory?.length" class="empty-state">当前站点尚无 FBA 观测，未知库存不等于零。</div>
        <div v-else-if="!filteredInventory.length" class="empty-state">本页没有匹配的 SKU。 <button class="text-button" type="button" @click="searchText = ''">清除筛选</button></div>
        <div v-else class="table-scroll core-data-scroll" role="region" tabindex="0" aria-label="可水平滚动的 FBA 库存表格">
          <table><thead><tr><th scope="col">Seller SKU</th><th scope="col">可售</th><th scope="col">预留</th><th scope="col">质量</th><th scope="col">来源</th><th scope="col">观测时间</th></tr></thead>
            <tbody><tr v-for="item in filteredInventory" :key="item.id">
              <td class="strong-cell">{{ item.sellerSku }}</td>
              <td class="quantity-cell">{{ observed(item.fulfillable) }}</td>
              <td>{{ observed(item.reserved) }}</td>
              <td><span class="status-chip" :class="item.quality === 'OBSERVED' ? 'chip-green' : 'chip-amber'">{{ coreStatusLabel(item.quality) }}</span></td>
              <td><span :class="['status-chip', item.dataOrigin === 'MOCK' ? 'chip-amber' : 'chip-muted']">{{ sourceLabel(item.dataOrigin) }}</span></td>
              <td class="small-cell">{{ formatSourceTime(item.observedAt) }}</td>
            </tr></tbody></table>
        </div>
      </section>
      <div class="note-panel"><span class="note-mark">i</span><div>
        <strong>数据完整性提醒</strong>
        <p>每类最多读取 50 条，筛选仅作用于本页；分页与真实 Amazon 同步尚未完成。测试数据必须标注 MOCK，未知库存不能按 0 处理。</p>
      </div></div>
      <OrdersSyncPanel v-if="activeMarketplace && activeMarketplace.connectionStatus === 'CONNECTED'"
        :key="'live-orders-'+activeMarketplace.id"
        :tenant-id="tenantId" :marketplace-id="activeMarketplace.id" :is-live="true" />
      <details class="core-tools">
        <summary><strong>本地店铺配置与测试工具</strong><span>添加 Marketplace、手动 Mock 回放 · 不连接 Amazon</span></summary>
        <div class="core-tools-content">
          <SellerSetup :tenant-id="tenantId" />
          <OrdersSyncPanel v-if="activeMarketplace && activeMarketplace.connectionStatus === 'UNCONFIGURED'"
            :key="'orders-'+activeMarketplace.id"
            :tenant-id="tenantId" :marketplace-id="activeMarketplace.id" :is-live="false" />
          <SandboxReplay v-if="activeMarketplace" :key="activeMarketplace.id"
            :tenant-id="tenantId" :marketplace-id="activeMarketplace.id"
            :marketplace-code="activeMarketplace.marketplaceId"
            :connection-status="activeMarketplace.connectionStatus" />
        </div>
      </details>
    </template>
  </div>
</template>
