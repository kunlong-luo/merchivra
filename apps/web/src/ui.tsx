import type { ReactNode } from 'react'

export function errorText(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback
}
export function timestamp(value?: string | null) {
  if (!value) return '暂无来源时间'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '时间未知'
    : date.toLocaleString('zh-CN', { timeZone: 'UTC', hour12: false }) + ' UTC'
}
export function money(value?: { amount: string; currency: string } | null) {
  return value ? value.currency + ' ' + value.amount : '不可计算'
}
const qualityNames: Record<string, string> = {
  OBSERVED: '已观测', UNKNOWN: '未取得', STALE: '已过期', PARTIAL: '部分数据',
  UNMAPPED: '待映射', ACTIVE: '已映射', SHIPPED: '已发货',
  UNSHIPPED: '待发货', PARTIAL_REFUND: '部分退款', NOT_OBSERVED: '未取得财务数据'
}
export function qualityLabel(key: string) { return qualityNames[key] ?? key }
export function StatePanel({ title, detail, retry, error = false }: {
  title: string; detail?: string; retry?: () => void; error?: boolean
}) {
  return <div className={'state-panel' + (error ? ' error-panel' : '')} role={error ? 'alert' : 'status'}>
    <strong>{title}</strong>{detail && <p>{detail}</p>}
    {retry && <button className="refresh-button" type="button" onClick={retry}>重试</button>}
  </div>
}
export type Column<T> = { title: string; render: (row: T) => ReactNode; className?: string }
export function DataTable<T>({ rows, columns, label, rowKey }: {
  rows: readonly T[]; columns: readonly Column<T>[]; label: string; rowKey: (row: T) => string
}) {
  return <div className="table-scroll" role="region" tabIndex={0} aria-label={label}>
    <table><thead><tr>{columns.map(col => <th key={col.title} scope="col">{col.title}</th>)}</tr></thead>
      <tbody>{rows.map(row => <tr key={rowKey(row)}>{columns.map(col =>
        <td key={col.title} className={col.className}>{col.render(row)}</td>)}</tr>)}</tbody></table>
  </div>
}
export function Chip({ children, positive = false, muted = false }: {
  children: ReactNode; positive?: boolean; muted?: boolean
}) {
  return <span className={'status-chip ' + (positive ? 'chip-green' : muted ? 'chip-muted' : 'chip-amber')}>{children}</span>
}
