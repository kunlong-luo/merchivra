import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchOrderSync, startOrderSync, stepOrderSync } from './lib/order-sync'
import { formatSourceTime } from './lib/core-view'
import { Chip, errorText } from './ui'

export default function OrdersSyncPanel({ tenantId, marketplaceId, isLive }: {
  tenantId: string; marketplaceId: string; isLive: boolean
}) {
  const client = useQueryClient()
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const status = useQuery({
    queryKey: ['orders-sync', tenantId, marketplaceId],
    queryFn: () => fetchOrderSync(marketplaceId), retry: false, staleTime: 10_000
  })
  function refresh() {
    void status.refetch()
    void client.invalidateQueries({ queryKey: ['core', tenantId, 'orders', marketplaceId] })
  }
  const start = useMutation({
    mutationFn: () => startOrderSync(marketplaceId),
    onSuccess: () => { setMessage('已初始化同步窗口'); setError(''); refresh() },
    onError: error => { setError(errorText(error, '初始化失败')); setMessage('') }
  })
  const step = useMutation({
    mutationFn: () => stepOrderSync(marketplaceId),
    onSuccess: value => {
      setMessage(value.state === 'SUCCEEDED' ? '同步窗口已经完整提交' : '已处理一页，最新检查点已保存')
      setError(''); refresh()
    },
    onError: error => { setError(errorText(error, '提交失败')); setMessage('') }
  })
  const value = status.data
  const stats = value ? [
    { label: '任务状态', val: value.state },
    { label: '已提交页数', val: value.committedPages },
    { label: '租约代数', val: value.leaseEpoch },
    { label: '安全水位（UTC）', val: formatSourceTime(value.lastSafeWatermark) }
  ] : []
  return <section className="panel orders-sync-panel" aria-label="V11 订单同步任务">
    <div className="panel-heading"><div>
      <h2>{isLive ? '订单增量 Worker · V11C LIVE' : '订单增量 Worker · V11A MOCK'}</h2>
      <p>{isLive ? '正式只读订单模式：只有通过 Seller OAuth、Marketplace 与 Orders 角色审核并由部署者显式批准后才能执行。不自动发货或记账。'
        : '持久分页、租约防并发、断点恢复；此模式只使用虚构订单源，不连接 Amazon。'}</p>
      </div><Chip>{isLive ? 'LIVE · 权限强校验' : 'MOCK ONLY'}</Chip></div>
    {status.isPending ? <div className="empty-state" role="status">正在读取同步检查点…</div>
      : status.isError ? <p className="setup-hint" role="status">{errorText(status.error, 'Worker 不可用')}</p>
      : value && <>
        <div className="orders-sync-grid">
          {stats.map(({ label, val }) => <div key={label}><span>{label}</span><strong>{val}</strong></div>)}
        </div>
        <p className="core-search-scope">{isLive
          ? '分页 Token 不会发送到浏览器。未完成独立角色审核、正式连接或启用开关时，服务端必须拒绝读取；LIVE 数据也不代表已结算利润。'
          : '分页 Token 不会发送到浏览器；模拟记录不可冒充 Amazon 真实经营数据。'}</p>
        <div className="sandbox-actions">
          <button type="button" className="primary-button"
            disabled={start.isPending || step.isPending || value.state === 'RUNNING'}
            onClick={() => start.mutate()}>开始新的增量窗口</button>
          <button type="button" className="refresh-button"
            disabled={step.isPending || start.isPending || ['NOT_STARTED', 'SUCCEEDED', 'PAUSED', 'RUNNING', 'WAITING_RETRY'].includes(value.state)}
            onClick={() => step.mutate()}>提交下一页</button>
          <button type="button" className="text-button" onClick={refresh}>刷新</button>
        </div>
        {value.nextAttemptAt && <p className="setup-hint">下次允许重试：{formatSourceTime(value.nextAttemptAt)}</p>}
        {value.lastFailureCode && <p className="setup-hint">上次错误代码：{value.lastFailureCode}</p>}
        {message && <p className="panel-caption" role="status">{message}</p>}
        {error && <p className="setup-error" role="alert">{error}</p>}
      </>}
  </section>
}
