import { z } from 'zod'
import { csrfHeader } from './auth'

export const sandboxRunSchema = z.object({
  id: z.string().uuid(),
  status: z.enum(['SUCCEEDED','FAILED_RETRYABLE']),
  source: z.literal('MOCK'),
  marketplaceId: z.string().uuid(),
  orderPageCount: z.number().int().nonnegative(),
  listingCount: z.number().int().nonnegative(),
  orderCount: z.number().int().nonnegative(),
  inventoryCount: z.number().int().nonnegative(),
  financeStatus: z.literal('NOT_AUTHORIZED'),
  failureCode: z.string().nullable(),
  createdAt: z.string()
})
export type SandboxRun = z.infer<typeof sandboxRunSchema>
async function decode<T>(res: Response, schema: z.ZodType<T>) {
  if (!res.ok) {
    if (res.status === 404) throw new Error('此站点不属于当前租户或不是本地占位店铺')
    if (res.status === 409) throw new Error('重复请求参数与原任务不一致')
    if (res.status === 422) throw new Error('当前站点暂无纯虚构模拟场景（目前支持美国/德国）')
    if (res.status === 401 || res.status === 403) throw new Error('当前会话无操作权限')
    throw new Error('模拟调用失败 HTTP ' + res.status)
  }
  return schema.parse(await res.json())
}
export async function replaySandbox(marketplaceId: string, key: string, simulateFailure: boolean) {
  return decode(await fetch('/api/v1/demo/sandbox/' + encodeURIComponent(marketplaceId) + '/replay', {
    method: 'POST', credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json', 'Idempotency-Key': key,
      ...(await csrfHeader())
    },
    body: JSON.stringify({ simulateFailure })
  }), sandboxRunSchema)
}
export async function fetchSandboxRuns(marketplaceId: string) {
  return decode(await fetch(
    '/api/v1/demo/sandbox/' + encodeURIComponent(marketplaceId) + '/runs',
    { credentials: 'same-origin', cache: 'no-store' }
  ), z.array(sandboxRunSchema))
}

export const mockPageStepSchema = z.object({
  status: z.enum(['RUNNING', 'SUCCEEDED']),
  committedPage: z.number().int().nonnegative(),
  nextPage: z.number().int().nonnegative(),
  totalPages: z.number().int().positive(),
  recordCount: z.number().int().nonnegative(),
  leaseEpoch: z.number().int().nonnegative(),
  replayed: z.boolean()
})
export const mockPageProgressSchema = z.object({
  status: z.enum(['NOT_STARTED', 'RUNNING', 'SUCCEEDED']),
  nextPage: z.number().int().nonnegative(),
  totalPages: z.number().int().nonnegative(),
  leaseEpoch: z.number().int().nonnegative(),
  pages: z.array(mockPageStepSchema)
})
export type MockPageProgress = z.infer<typeof mockPageProgressSchema>

export async function fetchMockPageProgress(marketplaceId: string) {
  return decode(await fetch(
    '/api/v1/demo/sandbox/' + encodeURIComponent(marketplaceId) + '/pages',
    { credentials: 'same-origin', cache: 'no-store' }
  ), mockPageProgressSchema)
}

export async function advanceMockPage(marketplaceId: string, key: string) {
  return decode(await fetch(
    '/api/v1/demo/sandbox/' + encodeURIComponent(marketplaceId) + '/pages/advance',
    {
      method: 'POST', credentials: 'same-origin',
      headers: { 'Idempotency-Key': key, ...(await csrfHeader()) }
    }
  ), mockPageStepSchema)
}
