<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  MARKETPLACE_PRESETS, fetchLocalAccounts, fetchSetupAudit,
  createLocalAccount, addLocalMarketplace
} from './lib/setup'

const props = defineProps<{ tenantId: string }>()
const client = useQueryClient()
const displayName = ref('')
const region = ref<'NA' | 'EU' | 'FE'>('NA')
const accountId = ref('')
const marketplaceId = ref('')
const requestKey = ref(crypto.randomUUID())
const feedback = ref('')
const failure = ref('')
const { data: accounts, isPending: loadingAccounts, isError: accountsError } = useQuery({
  queryKey: ['setup', props.tenantId, 'accounts'], queryFn: fetchLocalAccounts, staleTime: 15_000
})
const { data: audit, isPending: auditPending, isError: auditError } = useQuery({
  queryKey: ['setup', props.tenantId, 'audit'], queryFn: fetchSetupAudit, staleTime: 15_000
})
const localAccounts = computed(() =>
  accounts.value?.filter(a => a.connectionStatus === 'UNCONFIGURED') ?? [])
const selected = computed(() => localAccounts.value.find(a => a.id === accountId.value))
const choices = computed(() => MARKETPLACE_PRESETS[selected.value?.region ?? 'NA'])
watch(localAccounts, rows => {
  if (!rows.some(a => a.id === accountId.value)) accountId.value = rows[0]?.id ?? ''
}, { immediate: true })
watch(choices, rows => {
  if (!rows.some(m => m.id === marketplaceId.value)) marketplaceId.value = rows[0]?.id ?? ''
}, { immediate: true })
function refresh() {
  void client.invalidateQueries({ queryKey: ['setup', props.tenantId] })
  void client.invalidateQueries({ queryKey: ['core', 'marketplaces'] })
}
const create = useMutation({
  mutationFn: () => createLocalAccount({
    displayName: displayName.value, region: region.value
  }, requestKey.value),
  onSuccess: result => {
    failure.value = ''
    feedback.value = result.displayName + ' 已创建（尚未连接 Amazon）'
    accountId.value = result.id
    displayName.value = ''
    requestKey.value = crypto.randomUUID()
    refresh()
  },
  onError: e => { failure.value = e instanceof Error ? e.message : '创建失败' }
})
const add = useMutation({
  mutationFn: () => addLocalMarketplace(accountId.value, marketplaceId.value),
  onSuccess: result => {
    failure.value = ''
    feedback.value = result.marketplaceId + ' 已添加（仅本地占位配置）'
    refresh()
  },
  onError: e => { failure.value = e instanceof Error ? e.message : '添加失败' }
})
</script>

<template>
  <section class="panel setup-panel">
    <div class="panel-heading">
      <div><h2>本地 Seller Account 配置</h2>
        <p>仅为当前租户创建未连接的店铺占位信息，不输入 Amazon Seller ID、Token 或 SP-API 密钥。</p></div>
      <span class="status-chip chip-amber">未连接</span>
    </div>
    <div class="setup-forms">
      <form class="setup-form" :aria-busy="create.isPending.value" @submit.prevent="create.mutate()">
        <strong>创建店铺占位记录</strong>
        <label for="local-shop-name">店铺名称</label>
        <input id="local-shop-name" v-model.trim="displayName" required minlength="2" maxlength="100"
          placeholder="例如：测试店铺 A" autocomplete="off" />
        <label for="local-shop-region">区域</label>
        <select id="local-shop-region" v-model="region">
          <option value="NA">北美 NA</option><option value="EU">欧洲 EU</option><option value="FE">远东 FE</option>
        </select>
        <button type="submit" class="primary-button" :disabled="create.isPending.value || displayName.length < 2">
          {{ create.isPending.value ? '保存中…' : '创建本地店铺' }}
        </button>
      </form>

      <form class="setup-form" :aria-busy="add.isPending.value" @submit.prevent="add.mutate()">
        <strong>添加 Marketplace 配置</strong>
        <p v-if="loadingAccounts" class="setup-hint">加载店铺列表中…</p>
        <p v-else-if="accountsError" class="setup-hint">店铺列表加载失败，请刷新页面。</p>
        <template v-else>
          <label for="local-shop-select">店铺</label>
          <select id="local-shop-select" v-model="accountId" :disabled="!localAccounts.length">
            <option v-for="account in localAccounts" :key="account.id" :value="account.id">
              {{ account.displayName }} · {{ account.region }}
            </option>
          </select>
          <label for="local-marketplace">Marketplace</label>
          <select id="local-marketplace" v-model="marketplaceId" :disabled="!selected">
            <option v-for="choice in choices" :key="choice.id" :value="choice.id">{{ choice.label }}</option>
          </select>
          <button type="submit" class="primary-button" :disabled="add.isPending.value || !selected || !marketplaceId">
            {{ add.isPending.value ? '保存中…' : '添加站点' }}
          </button>
          <p v-if="!localAccounts.length" class="setup-hint">请先创建本地店铺。</p>
        </template>
      </form>
    </div>
    <div v-if="feedback" role="status" class="panel-caption">{{ feedback }}</div>
    <div v-if="failure" role="alert" class="setup-error">{{ failure }}</div>
    <div class="setup-audit">
      <div class="setup-audit-heading">
        <strong>配置审计 · 最近 30 条</strong>
        <button type="button" class="text-button" @click="refresh()">刷新</button>
      </div>
      <p v-if="auditPending" class="setup-hint" role="status">正在读取配置审计…</p>
      <p v-else-if="auditError" class="setup-hint" role="alert">审计读取失败，请尝试刷新。</p>
      <p v-else-if="!audit?.length" class="setup-hint">暂无配置操作记录。</p>
      <div v-else class="table-scroll" role="region" tabindex="0" aria-label="可水平滚动的本地配置审计表格">
        <table><thead><tr><th scope="col">操作</th><th scope="col">资源标识</th><th scope="col">发生时间</th></tr></thead>
          <tbody><tr v-for="event in audit" :key="event.id">
            <td>{{ event.action === 'LOCAL_SELLER_ACCOUNT_CREATED' ? '创建店铺' : '添加站点' }}</td>
            <td>{{ event.resourceId.slice(0, 8) }}…</td>
            <td>{{ event.createdAt }}</td>
          </tr></tbody></table>
      </div>
    </div>
  </section>
</template>
