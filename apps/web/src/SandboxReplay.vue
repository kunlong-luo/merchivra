<script setup lang="ts">
import { ref, watch } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { replaySandbox, fetchSandboxRuns } from './lib/sandbox'

const props = defineProps<{ tenantId: string; marketplaceId: string; marketplaceCode: string; connectionStatus: string }>()
const client = useQueryClient()
const supported = ['ATVPDKIKX0DER','A1PA6795UKMFR9']
const message = ref('')
const failure = ref('')
const key = ref(crypto.randomUUID())
const { data: runs, isError: runsError } = useQuery({
  queryKey: ['sandbox', props.tenantId, props.marketplaceId],
  queryFn: () => fetchSandboxRuns(props.marketplaceId),
  enabled: () => supported.includes(props.marketplaceCode) && props.connectionStatus === 'UNCONFIGURED',
  staleTime: 15_000
})
watch(() => props.marketplaceId, () => { message.value = ''; failure.value = ''; key.value = crypto.randomUUID() })
const replay = useMutation({
  mutationFn: (simulateFailure: boolean) => replaySandbox(props.marketplaceId, key.value, simulateFailure),
  onSuccess: result => {
    message.value = result.status === 'SUCCEEDED'
      ? '模拟 Orders（' + result.orderPageCount + ' 页）/Listing（' + result.listingCount +
        '）/FBA（' + result.inventoryCount + '）已幂等写入当前租户；财务未获授权'
      : '模拟失败已经记录；没有改变 Listing、订单或库存数据'
    failure.value = ''
    key.value = crypto.randomUUID()
    void client.invalidateQueries({ queryKey: ['sandbox', props.tenantId] })
    void client.invalidateQueries({ queryKey: ['core', props.tenantId] })
    void client.invalidateQueries({ queryKey: ['setup', props.tenantId, 'audit'] })
  },
  onError: err => { failure.value = err instanceof Error ? err.message : '模拟任务失败' }
})
</script>

<template>
  <section class="sandbox-panel" aria-label="纯虚构 SP-API 模拟调用">
    <div class="sandbox-panel-top">
      <div><strong>模拟 SP-API 操作链 · Sandbox</strong>
        <p>显式触发，使用固定纯虚构数据模拟 searchOrders 分页、Listing 与 getInventorySummaries；
          严格标记 MOCK，**不连接 Amazon、不请求买家信息、没有财务授权**。</p>
      </div>
      <span class="status-chip chip-amber">MOCK ONLY</span>
    </div>
    <p v-if="connectionStatus !== 'UNCONFIGURED'" class="setup-hint">
      仅对未连接的本地店铺开放模拟回放，不会覆盖已连接来源。
    </p>
    <p v-else-if="!supported.includes(marketplaceCode)" class="setup-hint">
      此站点尚无虚构场景。目前仅支持美国站和德国站；日本站不能伪造出来源数据。
    </p>
    <template v-else>
      <div class="sandbox-actions">
        <button class="primary-button" type="button" :disabled="replay.isPending.value" @click="replay.mutate(false)">
          {{ replay.isPending.value ? '模拟运行中…' : '模拟拉取并入库' }}
        </button>
        <button class="refresh-button" type="button" :disabled="replay.isPending.value" @click="replay.mutate(true)">模拟一次失败</button>
        <span class="setup-hint">每次操作生成新的幂等键；同一请求失败时可重试。</span>
      </div>
      <div v-if="message" class="panel-caption" role="status">{{ message }}</div>
      <div v-if="failure" class="setup-error" role="alert">{{ failure }}</div>
      <p v-if="runsError" class="setup-hint">任务历史读取失败，请检查服务端 Session 和数据库。</p>
      <div v-else-if="runs?.length" class="sandbox-history">
        <strong>模拟同步记录（最近 20 次）</strong>
        <div class="table-scroll"><table>
          <thead><tr><th>时间</th><th>状态</th><th>Orders 页</th><th>Listing</th><th>订单</th><th>FBA</th><th>财务</th></tr></thead>
          <tbody><tr v-for="run in runs" :key="run.id">
            <td class="small-cell">{{ run.createdAt }}</td>
            <td><span :class="['status-chip', run.status === 'SUCCEEDED' ? 'chip-green' : 'chip-amber']">{{ run.status }}</span></td>
            <td>{{ run.orderPageCount }}</td><td>{{ run.listingCount }}</td><td>{{ run.orderCount }}</td>
            <td>{{ run.inventoryCount }}</td><td>{{ run.financeStatus }}</td>
          </tr></tbody>
        </table></div>
      </div>
    </template>
  </section>
</template>
