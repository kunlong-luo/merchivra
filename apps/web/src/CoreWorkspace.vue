<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import SellerSetup from './SellerSetup.vue'
import SandboxReplay from './SandboxReplay.vue'
import {
  fetchCoreMarketplaces, fetchCoreListings, fetchCoreOrders, fetchCoreInventory
} from './lib/core'

type Section = 'listings' | 'orders' | 'inventory'
const props = defineProps<{ tenantId: string }>()
const marketplaceId = ref('')
const section = ref<Section>('listings')
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

function reloadCurrent() {
  if (section.value === 'listings') void reloadListings()
  if (section.value === 'orders') void reloadOrders()
  if (section.value === 'inventory') void reloadInventory()
}
function observed(value: number | null) {
  return value === null ? '未知' : String(value)
}
function isoTime(value: string | null) {
  if (!value) return '来源时间未知'
  return new Date(value).toLocaleString('zh-CN', { timeZone: 'UTC', hour12: false }) + ' UTC'
}
</script>

<template>
  <div class="core-workspace">
    <div class="core-intro">
      <strong>已认证租户 · PostgreSQL 结构化数据</strong>
      <p>这里直接读取核心业务表，不读取虚构 Mock 快照。租户由后端会话确定；当前只支持 Owner 范围内的只读操作。</p>
    </div>

    <SellerSetup :tenant-id="tenantId" />

    <div v-if="marketplacePending" class="state-panel">
      <strong>正在加载可访问的 Marketplace…</strong>
    </div>
    <div v-else-if="marketplaceError" class="state-panel error-panel" role="alert">
      <h2>Marketplace 读取失败</h2>
      <p>{{ marketplaceFailure instanceof Error ? marketplaceFailure.message : '请检查登录会话与服务' }}</p>
      <button class="refresh-button" type="button" @click="reloadMarketplaces()">重试</button>
    </div>
    <div v-else-if="!marketplaces?.length" class="state-panel core-empty">
      <div class="core-empty-icon">▧</div>
      <h2>当前租户还没有已连接的 Marketplace</h2>
      <p>这是正常的初始状态。首次 Owner 账户不会自动拥有其他租户的数据，也不会自动导入演示店铺。</p>
      <p>后续接入真实 Seller 授权后，可在此查看商品、订单与 FBA 观测。在此之前不要使用任何真实卖家凭据。</p>
      <button class="refresh-button" type="button" @click="reloadMarketplaces()">重新检查</button>
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
        <button class="refresh-button" type="button" @click="reloadCurrent()">刷新当前数据</button>
      </div>

      <div v-if="activeMarketplace" class="section-meta">
        <span><span class="data-dot"></span>{{ activeMarketplace.marketplaceId }} · {{ activeMarketplace.region }} · {{ activeMarketplace.currency }}</span>
        <span>连接状态：{{ activeMarketplace.connectionStatus }}</span>
      </div>

      <SandboxReplay v-if="activeMarketplace" :key="activeMarketplace.id"
        :tenant-id="tenantId" :marketplace-id="activeMarketplace.id"
        :marketplace-code="activeMarketplace.marketplaceId"
        :connection-status="activeMarketplace.connectionStatus" />
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

      <section v-if="section === 'listings'" class="panel full-panel">
        <div class="panel-heading"><div><h2>Listing（核心业务表）</h2><p>独立展示 Seller SKU 和已确认的内部 SKU 映射</p></div>
          <span class="count-pill">本页 {{ listings?.length ?? 0 }} 条 · 最多 50 条</span></div>
        <div v-if="listingPending" class="empty-state">查询 Listing 中…</div>
        <div v-else-if="listingError" class="empty-state" role="alert">{{ listingFailure instanceof Error ? listingFailure.message : '读取失败' }}</div>
        <div v-else-if="!listings?.length" class="empty-state">当前 Marketplace 尚无 Listing 记录</div>
        <div v-else class="table-scroll">
          <table><thead><tr><th>Seller SKU</th><th>ASIN</th><th>内部 SKU</th><th>映射状态</th><th>来源</th><th>来源更新时间</th></tr></thead>
            <tbody><tr v-for="item in listings" :key="item.id">
              <td class="strong-cell">{{ item.sellerSku }}</td>
              <td>{{ item.asin ?? '未知' }}</td><td>{{ item.internalSku ?? '未确认' }}</td>
              <td><span class="status-chip" :class="item.mappingStatus === 'ACTIVE' ? 'chip-green' : 'chip-amber'">{{ item.mappingStatus }}</span></td>
              <td><span :class="['status-chip', item.dataOrigin === 'MOCK' ? 'chip-amber' : 'chip-muted']">{{ item.dataOrigin }}</span></td>
              <td class="small-cell">{{ isoTime(item.sourceUpdatedAt) }}</td>
            </tr></tbody></table>
        </div>
      </section>

      <section v-else-if="section === 'orders'" class="panel full-panel">
        <div class="panel-heading"><div><h2>订单（核心业务表）</h2><p>独立展示履约、退款与财务状态；不推测结算收入</p></div>
          <span class="count-pill">本页 {{ orders?.length ?? 0 }} 笔 · 最多 50 笔</span></div>
        <div v-if="orderPending" class="empty-state">查询订单中…</div>
        <div v-else-if="orderError" class="empty-state" role="alert">{{ orderFailure instanceof Error ? orderFailure.message : '读取失败' }}</div>
        <div v-else-if="!orders?.length" class="empty-state">当前 Marketplace 尚无订单记录</div>
        <div v-else class="table-scroll">
          <table><thead><tr><th>外部订单号</th><th>订单项数量</th><th>履约</th><th>退款</th><th>财务</th><th>来源</th><th>来源时间</th></tr></thead>
            <tbody><tr v-for="item in orders" :key="item.id">
              <td class="strong-cell">{{ item.externalOrderId }}</td><td>{{ item.itemCount }}</td>
              <td>{{ item.fulfillmentStatus }}</td><td>{{ item.refundStatus }}</td>
              <td><span class="status-chip chip-muted">{{ item.financialStatus }}</span></td>
              <td class="small-cell">{{ isoTime(item.sourceUpdatedAt) }}</td>
            </tr></tbody></table>
        </div>
      </section>

      <section v-else class="panel full-panel">
        <div class="panel-heading"><div><h2>最新 FBA 观测（核心业务表）</h2>
          <p>每个 Seller SKU 只显示最新一次观测；未知数量不等于零</p></div>
          <span class="count-pill">本页 {{ inventory?.length ?? 0 }} 条 · 最多 50 条</span></div>
        <div v-if="stockPending" class="empty-state">查询库存观测中…</div>
        <div v-else-if="stockError" class="empty-state" role="alert">{{ stockFailure instanceof Error ? stockFailure.message : '读取失败' }}</div>
        <div v-else-if="!inventory?.length" class="empty-state">当前 Marketplace 尚无 FBA 库存观测</div>
        <div v-else class="table-scroll">
          <table><thead><tr><th>Seller SKU</th><th>可售</th><th>预留</th><th>质量</th><th>来源</th><th>观测时间</th></tr></thead>
            <tbody><tr v-for="item in inventory" :key="item.id">
              <td class="strong-cell">{{ item.sellerSku }}</td>
              <td class="quantity-cell">{{ observed(item.fulfillable) }}</td>
              <td>{{ observed(item.reserved) }}</td>
              <td><span class="status-chip" :class="item.quality === 'OBSERVED' ? 'chip-green' : 'chip-amber'">{{ item.quality }}</span></td>
              <td><span :class="['status-chip', item.dataOrigin === 'MOCK' ? 'chip-amber' : 'chip-muted']">{{ item.dataOrigin }}</span></td>
              <td class="small-cell">{{ isoTime(item.observedAt) }}</td>
            </tr></tbody></table>
        </div>
      </section>
      <div class="note-panel"><span class="note-mark">i</span><div>
        <strong>数据完整性提醒</strong>
        <p>最多读取前 50 条；分页与真实 Amazon 数据接入尚未完成。没有真实 Seller 连接时返回空列表是正确结果，不应回退到虚构演示数据。</p>
      </div></div>
    </template>
  </div>
</template>
