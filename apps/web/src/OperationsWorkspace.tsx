import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchFindings, fetchOpsSummary, scanOperations, changeFindingState,
  ruleNames, severityNames, stateNames, type FindingFilter, type FindingState } from './lib/operations'
import { formatSourceTime, sourceLabel } from './lib/core-view'
import { Chip, DataTable, errorText } from './ui'

export default function OperationsWorkspace({ tenantId }: { tenantId: string }) {
  const client = useQueryClient()
  const [filter, setFilter] = useState<FindingFilter>('ACTIVE')
  const [message, setMessage] = useState('')
  const [failure, setFailure] = useState('')
  const [editing, setEditing] = useState('')
  const summary = useQuery({ queryKey: ['operations', tenantId, 'summary'], queryFn: fetchOpsSummary, staleTime: 30_000 })
  const findings = useQuery({
    queryKey: ['operations', tenantId, 'findings', filter],
    queryFn: () => fetchFindings(filter), staleTime: 30_000
  })
  function refresh() { void client.invalidateQueries({ queryKey: ['operations', tenantId] }) }
  const scan = useMutation({
    mutationFn: scanOperations,
    onSuccess: result => {
      setFailure('')
      setMessage('已完成巡检：识别 ' + result.matchedCount + ' 个符合规则的数据项，自动清除 ' + result.clearedCount + ' 个过期待办。')
      refresh()
    },
    onError: error => { setFailure(errorText(error, '巡检失败')); setMessage('') }
  })
  const change = useMutation({
    mutationFn: ({ id, state }: { id: string; state: FindingState }) => changeFindingState(id, state),
    onMutate: ({ id }) => { setEditing(id) },
    onSuccess: () => { setMessage('待办状态已保存'); setFailure(''); refresh() },
    onError: error => setFailure(errorText(error, '保存失败')),
    onSettled: () => setEditing('')
  })
  const rows = findings.data ?? []
  return <div className="ops-workspace">
    <div className="core-intro"><strong>运营自动化 · PostgreSQL 任务队列</strong>
      <p>根据当前租户已有的 Listing、订单和 FBA 事实生成待办，不连接 Amazon、不自动采购、不发送客户消息。
        Mock 测试、未核验与已授权 API 记录始终保留来源标记。</p></div>
    <div className="ops-summary" aria-label="异常待办状态统计">
      {([
        ['待处理', summary.data?.open], ['处理中', summary.data?.acknowledged],
        ['已处理', summary.data?.done], ['条件已消失', summary.data?.cleared]
      ] as const).map(([name, count]) =>
        <div key={name}><span>{name}</span><strong>{summary.isPending ? '—' : count ?? 0}</strong></div>)}
    </div>
    <section className="panel" aria-label="运营巡检与待办">
      <div className="panel-heading"><div><h2>智能运营待办</h2>
        <p>自动识别 SKU 映射异常、库存未知/低于阈值和退款待核对；同一来源不会生成重复待办。</p></div>
        <Chip muted>仅建议 · 不自动改动业务事实</Chip></div>
      <div className="ops-toolbar">
        <label className="shop-filter"><span>查看范围</span>
          <select value={filter} aria-label="筛选运营待办状态"
            onChange={e => setFilter(e.target.value as FindingFilter)}>
            <option value="ACTIVE">待处理 + 处理中</option><option value="ALL">全部状态</option>
          </select></label>
        <button className="primary-button" type="button" disabled={scan.isPending} onClick={() => scan.mutate()}>
          {scan.isPending ? '巡检中…' : '立即巡检当前租户'}</button>
        <button className="refresh-button" type="button" onClick={refresh}>刷新待办</button>
      </div>
      <div className="ops-rule-note">规则：待映射 Listing、FBA 数据不完整、可售库存 ≤ 部署阈值（默认 3）、部分/全部退款订单。定时巡检需部署者显式开启；否则仅支持手动巡检。</div>
      {message && <div role="status" className="panel-caption">{message}</div>}
      {failure && <div role="alert" className="setup-error">{failure}</div>}
      {findings.isPending ? <div role="status" className="empty-state">正在读取运营待办…</div>
        : findings.isError ? <div className="core-query-error" role="alert">
          <strong>读取运营待办失败</strong><p>{errorText(findings.error, '服务端暂时不可用')}</p>
          <button className="refresh-button" onClick={refresh}>重试</button></div>
        : !rows.length ? <div className="empty-state">当前筛选条件下没有待办。首次使用请点击“立即巡检”；没有记录也不等于真实 Amazon 经营无异常。</div>
        : <DataTable rows={rows} rowKey={r => r.id} label="可水平滚动的运营待办列表" columns={[
          { title: '级别', render: r => <Chip muted={r.severity !== 'CRITICAL'}>{severityNames[r.severity]}</Chip> },
          { title: '异常类型', className: 'strong-cell', render: r => ruleNames[r.ruleCode] },
          { title: '关联来源', className: 'small-cell', render: r => r.sourceKey },
          { title: '来源', render: r => <Chip muted={r.dataOrigin !== 'MOCK'}>{sourceLabel(r.dataOrigin)}</Chip> },
          { title: '状态', render: r => stateNames[r.state] },
          { title: '上次发现', className: 'small-cell', render: r => formatSourceTime(r.lastSeenAt) },
          { title: '操作', render: r => <div className="ops-row-actions">
            {r.state === 'OPEN' && <button type="button" className="text-button" disabled={!!editing}
              onClick={() => change.mutate({ id: r.id, state: 'ACKNOWLEDGED' })}>认领</button>}
            {(r.state === 'OPEN' || r.state === 'ACKNOWLEDGED') && <button type="button" className="text-button" disabled={!!editing}
              onClick={() => change.mutate({ id: r.id, state: 'DONE' })}>标记已处理</button>}
            {(r.state === 'DONE' || r.state === 'ACKNOWLEDGED') && <button type="button" className="text-button" disabled={!!editing}
              onClick={() => change.mutate({ id: r.id, state: 'OPEN' })}>重新打开</button>}
          </div> }
        ]} />}
      <div className="panel-caption">最多显示前 100 条。已处理待办在条件仍存在时不重复打开；异常消失后自动清除，未来再次出现才重新打开。来源未核验或 Mock 的异常不可作为自动补货/发货依据。</div>
    </section>
  </div>
}
