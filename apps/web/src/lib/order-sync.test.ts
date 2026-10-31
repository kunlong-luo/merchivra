import { afterEach, describe, expect, it, vi } from 'vitest'
import { orderSyncStatusSchema, startOrderSync } from './order-sync'
afterEach(() => vi.unstubAllGlobals())

describe('durable Orders sync browser API', () => {
  it('never exposes checkpoint pagination tokens', () => {
    expect(orderSyncStatusSchema.parse({
      jobId: null, state: 'NOT_STARTED', committedPages: 0, leaseEpoch: 0,
      windowAfter: null, windowBefore: null, lastSafeWatermark: null,
      nextAttemptAt: null, lastFailureCode: null
    }).state).toBe('NOT_STARTED')
    expect(orderSyncStatusSchema.safeParse({
      jobId:null,state:'CONNECTED',committedPages:0,leaseEpoch:0,
      windowAfter:null,windowBefore:null,lastSafeWatermark:null,nextAttemptAt:null,lastFailureCode:null
    }).success).toBe(false)
  })
  it('starts a manual window using only Marketplace UUID and CSRF, never a token', async () => {
    const history: Array<{ path: string; options?: RequestInit }> = []
    vi.stubGlobal('fetch', vi.fn(async (path: string, options?: RequestInit) => {
      history.push({path, options})
      if (path.endsWith('/auth/csrf')) return new Response(JSON.stringify({
        token:'test-local-csrf-token', headerName:'X-CSRF-TOKEN'
      }), {status:200})
      return new Response(JSON.stringify({
        jobId:'11111111-1111-4111-8111-111111111111',state:'READY',
        committedPages:0,leaseEpoch:0,windowAfter:'2026-10-29T00:00:00Z',
        windowBefore:'2026-10-30T00:00:00Z',lastSafeWatermark:null,
        nextAttemptAt:null,lastFailureCode:null
      }),{status:200})
    }))
    await startOrderSync('22222222-2222-4222-8222-222222222222')
    expect(history[1].path).toContain('/api/v1/orders-sync/22222222-2222-4222-8222-222222222222/start')
    expect(history[1].options?.method).toBe('POST')
    expect(history[1].options?.body).toBeUndefined()
    expect(history[1].options?.headers).toEqual({'X-CSRF-TOKEN':'test-local-csrf-token'})
  })
})
