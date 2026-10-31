<script setup lang="ts">
import { computed, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { fetchFindings, fetchOpsSummary, scanOperations, changeFindingState,
  ruleNames, severityNames, stateNames } from './lib/operations'
import type { FindingFilter, FindingState } from './lib/operations'
import { formatSourceTime, sourceLabel } from './lib/core-view'

const props = defineProps<{ tenantId: string }>()
const client = useQueryClient()
const filter = ref<FindingFilter>('ACTIVE')
const message = ref('')
const failure = ref('')
const { data: summary, isPending: summaryPending } = useQuery({
  queryKey: ['operations', props.tenantId, 'summary'],
  queryFn: fetchOpsSummary, staleTime: 30_000
})
const { data: findings, isPending: findingsPending, isError: findingsError,
  error: findingsFailure } = useQuery({
  queryKey: ['operations', props.tenantId, 'findings', filter],
  queryFn: () => fetchFindings(filter.value), staleTime: 30_000
})
const openItems = computed(() => findings.value ?? [])
function refresh() {
  void client.invalidateQueries({ queryKey: ['operations', props.tenantId] })
}
const scan = useMutation({
  mutationFn: scanOperations,
  onSuccess: result => {
    failure.value = ''
    message.value = '已完成巡检：识别 ' + result.matchedCount +
      ' 个符合规则的数据项，自动清除 ' + result.clearedCount + ' 个过期待办。'
    refresh()
  },
  onError: error => {
    failure.value = error instanceof Error ? error.message : '巡检失败'
    message.value = ''
  }
})
const editing = ref('')
const change = useMutation({
  mutationFn: ({ id, state }: { id: string; state: FindingState }) =>
    changeFindingState(id, state),
  onMutate: args => { editing.value = args.id },
  onSuccess: () => {
    message.value = '待办状态已保存'
    failure.value = ''
    refresh()
  },
  onError: error => {
    failure.value = error instanceof Error ? error.message : '保存失败'
  },
  onSettled: () => { editing.value = '' }
})
</script>

<template>
  <div class="ops-workspace">
    <div class="core-intro">
      <strong>运营自动化 · PostgreSQL 任务队列</strong>
      <p>根据当前租户已有的 Listing、订单和 FBA 事实生成待办，不连接 Amazon、不自动采购、不发送客户消息。
        Mock 测试记录与未核验来源始终保留来源标记。</p>
    </div>
    <div class="ops-summary" aria-label="异常待办状态统计">
      <div><span>待处理</span><strong>{{ summaryPending ? '—' : summary?.open ?? 0 }}</strong></div>
      <div><span>处理中</span><strong>{{ summaryPending ? '—' : summary?.acknowledged ?? 0 }}</strong></div>
      <div><span>已处理</span><strong>{{ summaryPending ? '—' : summary?.done ?? 0 }}</strong></div>
      <div><span>条件已消失</span><strong>{{ summaryPending ? '—' : summary?.cleared ?? 0 }}</strong></div>
    </div>
    <section class="panel" aria-label="运营巡检与待办">
      <div class="panel-heading">
        <div><h2>智能运营待办</h2>
          <p>自动识别 SKU 映射异常、库存未知/低于阈值和退款待核对；同一来源不会生成重复待办。</p></div>
        <span class="status-chip chip-muted">仅建议 · 不自动改动业务事实</span>
      </div>
      <div class="ops-toolbar">
        <label class="shop-filter"><span>查看范围</span>
          <select v-model="filter" aria-label="筛选运营待办状态">
            <option value="ACTIVE">待处理 + 处理中</option>
            <option value="ALL">全部状态</option>
          </select>
        </label>
        <button class="primary-button" type="button" :disabled="scan.isPending.value"
          @click="scan.mutate()">
          {{ scan.isPending.value ? '巡检中…' : '立即巡检当前租户' }}
        </button>
        <button type="button" class="refresh-button" @click="refresh()">刷新待办</button>
      </div>
      <div class="ops-rule-note">
        规则：待映射 Listing、FBA 数据不完整、可售库存 ≤ 部署阈值（默认 3）、
        部分/全部退款订单。定时巡检需部署者显式开启；否则仅支持手动巡检。
      </div>
      <div v-if="message" role="status" class="panel-caption">{{ message }}</div>
      <div v-if="failure" role="alert" class="setup-error">{{ failure }}</div>
      <div v-if="findingsPending" role="status" class="empty-state">正在读取运营待办…</div>
      <div v-else-if="findingsError" class="core-query-error" role="alert">
        <strong>读取运营待办失败</strong>
        <p>{{ findingsFailure instanceof Error ? findingsFailure.message : '服务端暂时不可用' }}</p>
        <button type="button" class="refresh-button" @click="refresh()">重试</button>
      </div>
      <div v-else-if="!openItems.length" class="empty-state">
        当前筛选条件下没有待办。首次使用请点击“立即巡检”；没有记录也不等于真实 Amazon 经营无异常。
      </div>
      <div v-else class="table-scroll" role="region" tabindex="0" aria-label="可水平滚动的运营待办列表">
        <table>
          <thead><tr>
            <th scope="col">级别</th><th scope="col">异常类型</th><th scope="col">关联来源</th>
            <th scope="col">来源</th><th scope="col">状态</th><th scope="col">上次发现</th>
            <th scope="col">操作</th>
          </tr></thead>
          <tbody><tr v-for="item in openItems" :key="item.id">
            <td><span class="status-chip" :class="item.severity === 'CRITICAL' ? 'chip-amber' : 'chip-muted'">
              {{ severityNames[item.severity] }}</span></td>
            <td class="strong-cell">{{ ruleNames[item.ruleCode] }}</td>
            <td class="small-cell">{{ item.sourceKey }}</td>
            <td><span class="status-chip" :class="item.dataOrigin === 'MOCK' ? 'chip-amber' : 'chip-muted'">
              {{ sourceLabel(item.dataOrigin) }}</span></td>
            <td>{{ stateNames[item.state] }}</td>
            <td class="small-cell">{{ formatSourceTime(item.lastSeenAt) }}</td>
            <td><div class="ops-row-actions">
              <button v-if="item.state === 'OPEN'" type="button" class="text-button"
                :disabled="!!editing" @click="change.mutate({ id: item.id, state: 'ACKNOWLEDGED' })">认领</button>
              <button v-if="item.state === 'OPEN' || item.state === 'ACKNOWLEDGED'"
                type="button" class="text-button" :disabled="!!editing"
                @click="change.mutate({ id: item.id, state: 'DONE' })">标记已处理</button>
              <button v-if="item.state === 'DONE' || item.state === 'ACKNOWLEDGED'"
                type="button" class="text-button" :disabled="!!editing"
                @click="change.mutate({ id: item.id, state: 'OPEN' })">重新打开</button>
            </div></td>
          </tr></tbody>
        </table>
      </div>
      <div class="panel-caption">最多显示前 100 条。已处理待办在条件仍存在时不重复打开；异常消失后自动清除，
        未来再次出现才重新打开。来源未核验或 Mock 的异常不可作为自动补货/发货依据。</div>
    </section>
  </div>
</template>
