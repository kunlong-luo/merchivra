import { z } from 'zod'
import { csrfHeader } from './auth'

export const orderSyncStatusSchema = z.object({
  jobId: z.string().uuid().nullable(),
  state: z.enum(['NOT_STARTED','READY','RUNNING','WAITING_RETRY','PAUSED','SUCCEEDED']),
  committedPages: z.number().int().nonnegative(),
  leaseEpoch: z.number().int().nonnegative(),
  windowAfter: z.string().nullable(),
  windowBefore: z.string().nullable(),
  lastSafeWatermark: z.string().nullable(),
  nextAttemptAt: z.string().nullable(),
  lastFailureCode: z.string().nullable()
})
export type OrderSyncStatus = z.infer<typeof orderSyncStatusSchema>

async function load(id: string, method: 'GET' | 'POST', action = '') {
  const response = await fetch('/api/v1/orders-sync/' + encodeURIComponent(id) + action, {
    method, credentials: 'same-origin', cache: 'no-store',
    ...(method === 'POST' ? { headers: await csrfHeader() } : {})
  })
  if (response.status === 404) throw new Error('订单同步服务未启用或当前站点不满足来源/授权条件')
  if (response.status === 409) throw new Error('任务正在执行、等待重试或已完成；请刷新检查点')
  if (!response.ok) throw new Error('订单同步操作未完成（HTTP ' + response.status + '）')
  return orderSyncStatusSchema.parse(await response.json())
}
export const fetchOrderSync = (mp: string) => load(mp, 'GET')
export const startOrderSync = (mp: string) => load(mp, 'POST', '/start')
export const stepOrderSync = (mp: string) => load(mp, 'POST', '/step')
