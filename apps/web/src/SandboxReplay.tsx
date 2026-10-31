import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { replaySandbox, fetchSandboxRuns, fetchMockPageProgress, advanceMockPage } from './lib/sandbox'
import { Chip, DataTable, errorText } from './ui'

const supported = ['ATVPDKIKX0DER', 'A1PA6795UKMFR9']
export default function SandboxReplay({ tenantId, marketplaceId, marketplaceCode, connectionStatus }: {
  tenantId: string; marketplaceId: string; marketplaceCode: string; connectionStatus: string
}) {
  const client = useQueryClient()
  const [message, setMessage] = useState('')
  const [failure, setFailure] = useState('')
  const [key, setKey] = useState(() => crypto.randomUUID())
  const [pageKey, setPageKey] = useState(() => crypto.randomUUID())
  const enabled = supported.includes(marketplaceCode) && connectionStatus === 'UNCONFIGURED'
  const runs = useQuery({
    queryKey: ['sandbox', tenantId, marketplaceId],
    queryFn: () => fetchSandboxRuns(marketplaceId), enabled, staleTime: 15_000
  })
  const page = useQuery({
    queryKey: ['sandbox-page', tenantId, marketplaceId],
    queryFn: () => fetchMockPageProgress(marketplaceId), enabled, staleTime: 10_000
  })
  const advance = useMutation({
    mutationFn: () => advanceMockPage(marketplaceId, pageKey),
    onSuccess: result => {
      setMessage(result.replayed ? '同一幂等键已执行过：不会再提交页面' :
        '已持久提交第 ' + result.nextPage + '/' + result.totalPages + ' 页；事务代数 ' + result.leaseEpoch)
      setFailure(''); setPageKey(crypto.randomUUID()); void page.refetch()
      void client.invalidateQueries({ queryKey: ['core', tenantId] })
      if (result.status === 'SUCCEEDED') void client.invalidateQueries({ queryKey: ['setup', tenantId, 'audit'] })
    },
    onError: error => setFailure(errorText(error, '分页提交失败'))
  })
  const replay = useMutation({
    mutationFn: (simulateFailure: boolean) => replaySandbox(marketplaceId, key, simulateFailure),
    onSuccess: result => {
      setMessage(result.status === 'SUCCEEDED'
        ? '模拟 Orders（' + result.orderPageCount + ' 页）/Listing（' + result.listingCount +
          '）/FBA（' + result.inventoryCount + '）已幂等写入当前租户；财务未获授权'
        : '模拟失败已经记录；没有改变 Listing、订单或库存数据')
      setFailure(''); setKey(crypto.randomUUID())
      void client.invalidateQueries({ queryKey: ['sandbox', tenantId] })
      void client.invalidateQueries({ queryKey: ['core', tenantId] })
      void client.invalidateQueries({ queryKey: ['setup', tenantId, 'audit'] })
    },
    onError: error => setFailure(errorText(error, '模拟任务失败'))
  })
  return <section className="sandbox-panel" aria-label="纯虚构 SP-API 模拟调用">
    <div className="sandbox-panel-top"><div><strong>模拟 SP-API 操作链 · Sandbox</strong>
      <p>显式触发，使用固定纯虚构数据模拟 searchOrders 分页、Listing 与 getInventorySummaries；严格标记 MOCK，不连接 Amazon、不请求买家信息、没有财务授权。</p>
      </div><Chip>MOCK ONLY</Chip></div>
    {connectionStatus !== 'UNCONFIGURED' ? <p className="setup-hint">仅对未连接的本地店铺开放模拟回放，不会覆盖已连接来源。</p>
      : !supported.includes(marketplaceCode) ? <p className="setup-hint">此站点尚无虚构场景。目前仅支持美国站和德国站；日本站不能伪造出来源数据。</p>
      : <>
        <div className="sandbox-actions">
          <button className="primary-button" type="button" disabled={replay.isPending}
            onClick={() => replay.mutate(false)}>{replay.isPending ? '模拟运行中…' : '模拟拉取并入库'}</button>
          <button className="refresh-button" type="button" disabled={replay.isPending}
            onClick={() => replay.mutate(true)}>模拟一次失败</button>
          <span className="setup-hint">每次操作生成新的幂等键；同一请求失败时可重试。</span>
        </div>
        <div className="sandbox-page-progress">
          <strong>持久分页检查点（手动一页一事务）</strong>
          <p>第 {page.data?.nextPage ?? 0} / {page.data?.totalPages ?? '—'} 页 ·
            状态 {page.data?.status ?? '读取中'} · 事务代数 {page.data?.leaseEpoch ?? 0}（不会连接真实 Amazon）</p>
          {!!page.data?.totalPages && <progress value={page.data.nextPage}
            max={page.data.totalPages} aria-label="已持久提交的模拟订单页进度" />}
          <div className="sandbox-actions">
            <button className="refresh-button" type="button"
              disabled={advance.isPending || page.data?.status === 'SUCCEEDED' || page.isError}
              onClick={() => advance.mutate()}>{advance.isPending ? '正在提交…' : '提交下一模拟订单页'}</button>
            <button className="text-button" type="button" onClick={() => void page.refetch()}>刷新检查点</button>
          </div>
          {page.isError && <p className="setup-hint" role="alert">无法读取检查点，请检查授权或 API。</p>}
          <p className="setup-hint">每次操作创建一个新的幂等请求；断电或重启后继续处理下一页。原有整批 Mock 导入入口仍保留。</p>
        </div>
        {message && <div className="panel-caption" role="status">{message}</div>}
        {failure && <div className="setup-error" role="alert">{failure}</div>}
        {runs.isError ? <p className="setup-hint">任务历史读取失败，请检查服务端 Session 和数据库。</p>
          : !!runs.data?.length && <div className="sandbox-history"><strong>模拟同步记录（最近 20 次）</strong>
            <DataTable rows={runs.data} rowKey={r => r.id} label="可水平滚动的模拟同步历史表格" columns={[
              { title: '时间', className: 'small-cell', render: r => r.createdAt },
              { title: '状态', render: r => <Chip positive={r.status === 'SUCCEEDED'}>{r.status}</Chip> },
              { title: 'Orders 页', render: r => r.orderPageCount },
              { title: 'Listing', render: r => r.listingCount },
              { title: '订单', render: r => r.orderCount },
              { title: 'FBA', render: r => r.inventoryCount },
              { title: '财务', render: r => r.financeStatus }
            ]} />
          </div>}
      </>}
  </section>
}
