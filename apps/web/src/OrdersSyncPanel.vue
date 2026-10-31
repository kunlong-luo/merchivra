<script setup lang="ts">
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, ref } from 'vue'
import { fetchOrderSync, startOrderSync, stepOrderSync } from './lib/order-sync'
import { formatSourceTime } from './lib/core-view'

const props = defineProps<{ tenantId: string; marketplaceId: string; isLive: boolean }>()
const client = useQueryClient()
const message = ref('')
const error = ref('')
const key = computed(() => ['orders-sync', props.tenantId, props.marketplaceId])
const { data: status, isPending, isError, error: fetchError, refetch } = useQuery({
  queryKey: key,
  queryFn: () => fetchOrderSync(props.marketplaceId),
  retry: false, staleTime: 10_000
})
function refresh() {
  void refetch()
  void client.invalidateQueries({ queryKey: ['core', props.tenantId, 'orders', props.marketplaceId] })
}
const start = useMutation({
  mutationFn: () => startOrderSync(props.marketplaceId),
  onSuccess: () => { message.value = '已初始化同步窗口'; error.value = ''; refresh() },
  onError: (e: Error) => { error.value = e.message; message.value = '' }
})
const step = useMutation({
  mutationFn: () => stepOrderSync(props.marketplaceId),
  onSuccess: value => {
    message.value = value.state === 'SUCCEEDED' ? '同步窗口已经完整提交' :
      '已处理一页，最新检查点已保存'
    error.value = ''
    refresh()
  },
  onError: (e: Error) => { error.value = e.message; message.value = '' }
})
</script>

<template>
  <section class="panel orders-sync-panel" aria-label="V11 离线订单同步任务">
    <div class="panel-heading">
      <div><h2>{{ isLive ? '订单增量 Worker · V11C LIVE' : '订单增量 Worker · V11A MOCK' }}</h2>
        <p v-if="isLive">正式只读订单模式：只有通过 Seller OAuth、Marketplace 与 Orders 角色审核并由部署者显式批准后才能执行。不自动发货或记账。</p>
        <p v-else>持久分页、租约防并发、断点恢复；此模式只使用虚构订单源，不连接 Amazon。</p></div>
      <span class="status-chip chip-amber">{{ isLive ? 'LIVE · 权限强校验' : 'MOCK ONLY' }}</span>
    </div>
    <div v-if="isPending" class="empty-state" role="status">正在读取同步检查点…</div>
    <p v-else-if="isError" class="setup-hint" role="status">
      {{ fetchError instanceof Error ? fetchError.message : 'Worker 不可用' }}
    </p>
    <template v-else-if="status">
      <div class="orders-sync-grid">
        <div><span>任务状态</span><strong>{{ status.state }}</strong></div>
        <div><span>已提交页数</span><strong>{{ status.committedPages }}</strong></div>
        <div><span>租约代数</span><strong>{{ status.leaseEpoch }}</strong></div>
        <div><span>安全水位（UTC）</span><strong>{{ formatSourceTime(status.lastSafeWatermark) }}</strong></div>
      </div>
      <p class="core-search-scope">{{ isLive
        ? '分页 Token 不会发送到浏览器。未完成独立角色审核、正式连接或启用开关时，服务端必须拒绝读取；LIVE 数据也不代表已结算利润。'
        : '分页 Token 不会发送到浏览器；模拟记录不可冒充 Amazon 真实经营数据。' }}</p>
      <div class="sandbox-actions">
        <button type="button" class="primary-button" :disabled="start.isPending.value || step.isPending.value || status.state === 'RUNNING'"
          @click="start.mutate()">开始新的增量窗口</button>
        <button type="button" class="refresh-button"
          :disabled="step.isPending.value || start.isPending.value || status.state === 'NOT_STARTED' || status.state === 'SUCCEEDED' || status.state === 'PAUSED' || status.state === 'RUNNING' || status.state === 'WAITING_RETRY'"
          @click="step.mutate()">提交下一页</button>
        <button type="button" class="text-button" @click="refresh()">刷新</button>
      </div>
      <p v-if="status.nextAttemptAt" class="setup-hint">下次允许重试：{{ formatSourceTime(status.nextAttemptAt) }}</p>
      <p v-if="status.lastFailureCode" class="setup-hint">上次错误代码：{{ status.lastFailureCode }}</p>
      <p v-if="message" class="panel-caption" role="status">{{ message }}</p>
      <p v-if="error" class="setup-error" role="alert">{{ error }}</p>
    </template>
  </section>
</template>
