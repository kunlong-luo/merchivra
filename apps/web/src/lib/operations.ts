import { z } from 'zod'
import { csrfHeader } from './auth'

export const findingSchema = z.object({
  id: z.string().uuid(),
  marketplaceId: z.string().uuid(),
  ruleCode: z.enum(['UNMAPPED_LISTING', 'INVENTORY_UNKNOWN', 'LOW_STOCK', 'REFUND_REVIEW']),
  sourceKey: z.string(),
  title: z.string(),
  severity: z.enum(['INFO', 'WARNING', 'CRITICAL']),
  state: z.enum(['OPEN', 'ACKNOWLEDGED', 'DONE', 'CLEARED']),
  dataOrigin: z.enum(['MOCK', 'UNVERIFIED', 'LIVE']),
  lastSeenAt: z.string()
})
export const summarySchema = z.object({
  open: z.number().int().nonnegative(),
  acknowledged: z.number().int().nonnegative(),
  done: z.number().int().nonnegative(),
  cleared: z.number().int().nonnegative()
})
export const scanSchema = z.object({
  id: z.string().uuid(),
  triggerType: z.literal('MANUAL'),
  matchedCount: z.number().int().nonnegative(),
  clearedCount: z.number().int().nonnegative()
})
export type Finding = z.infer<typeof findingSchema>
export type FindingState = 'OPEN' | 'ACKNOWLEDGED' | 'DONE'
export type FindingFilter = 'ACTIVE' | 'ALL'
export const ruleNames: Record<Finding['ruleCode'], string> = {
  UNMAPPED_LISTING: 'Listing 映射待确认',
  INVENTORY_UNKNOWN: 'FBA 库存数据不完整',
  LOW_STOCK: 'FBA 可售库存偏低',
  REFUND_REVIEW: '退款订单待核对'
}
export const stateNames: Record<Finding['state'], string> = {
  OPEN: '待处理', ACKNOWLEDGED: '处理中',
  DONE: '已处理', CLEARED: '条件已消失'
}
export const severityNames: Record<Finding['severity'], string> = {
  INFO: '提示', WARNING: '注意', CRITICAL: '优先处理'
}
async function decode<T>(response: Response, schema: z.ZodType<T>): Promise<T> {
  if (!response.ok) {
    if (response.status === 401) throw new Error('登录已过期，请重新登录')
    if (response.status === 403) throw new Error('当前租户没有操作权限')
    if (response.status === 404) throw new Error('记录不存在或不属于当前租户')
    if (response.status === 409) throw new Error('此记录已清除，请刷新列表')
    throw new Error('运营自动化 API HTTP ' + response.status)
  }
  return schema.parse(await response.json())
}
export async function fetchFindings(filter: FindingFilter): Promise<Finding[]> {
  return decode(await fetch('/api/v1/operations/findings?filter=' + filter, {
    credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' }
  }), z.array(findingSchema))
}
export async function fetchOpsSummary() {
  return decode(await fetch('/api/v1/operations/summary', {
    credentials: 'same-origin', cache: 'no-store'
  }), summarySchema)
}
export async function scanOperations() {
  return decode(await fetch('/api/v1/operations/scan', {
    method: 'POST', credentials: 'same-origin',
    headers: await csrfHeader()
  }), scanSchema)
}
export async function changeFindingState(id: string, state: FindingState) {
  return decode(await fetch('/api/v1/operations/findings/' + encodeURIComponent(id) + '/state', {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', ...(await csrfHeader()) },
    body: JSON.stringify({ state })
  }), z.object({ id: z.string().uuid(), state: z.enum(['OPEN', 'ACKNOWLEDGED', 'DONE']) }))
}
