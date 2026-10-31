/**
 * Client-side presentation only. Lists from the API are capped at 50 records;
 * filtering them never claims to search additional server-side pages.
 */
export function filterLoadedRows<T>(
  rows: readonly T[] | undefined,
  query: string,
  fields: (row: T) => readonly (string | null | undefined)[]
): T[] {
  const needle = query.trim().normalize('NFKC').toLocaleLowerCase()
  if (!rows) return []
  if (!needle) return [...rows]
  return rows.filter(row => fields(row).some(field =>
    (field ?? '').normalize('NFKC').toLocaleLowerCase().includes(needle)
  ))
}

const labels: Record<string, string> = {
  UNCONFIGURED: '未连接',
  AUTHORIZATION_PENDING: '等待授权验证',
  CONNECTED: '已连接',
  DISCONNECTED: '未连接',
  REVOKED: '已撤销',
  ACTIVE: '已映射',
  UNMAPPED: '待映射',
  OBSERVED: '已观测',
  PARTIAL: '部分数据',
  UNKNOWN: '未知',
  STALE: '已过期',
  SHIPPED: '已发货',
  UNSHIPPED: '未发货',
  PENDING: '待处理',
  CANCELLED: '已取消',
  NONE: '无退款',
  PARTIAL_REFUND: '部分退款',
  FULL_REFUND: '已全额退款',
  NOT_OBSERVED: '尚无财务数据',
  NOT_AUTHORIZED: '财务未授权'
}

export function coreStatusLabel(status: string): string {
  // Unknown future API values must remain visible, not silently misrepresented.
  return labels[status] ?? status
}
export function sourceLabel(origin: 'MOCK' | 'UNVERIFIED'): string {
  return origin === 'MOCK' ? '模拟测试' : '来源未核验'
}
export function formatSourceTime(value: string | null): string {
  if (!value) return '来源时间未知'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '来源时间无效'
  return date.toLocaleString('zh-CN', { timeZone: 'UTC', hour12: false }) + ' UTC'
}
