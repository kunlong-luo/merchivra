import { describe, expect, it, vi, afterEach } from 'vitest'
import { findingSchema, ruleNames, scanOperations, severityNames, stateNames } from './operations'

afterEach(() => vi.unstubAllGlobals())

describe('operations advisory UI contract', () => {
  it('rejects invented verified provenance and invalid states', () => {
    const row = {
      id: '11111111-1111-4111-8111-111111111111',
      marketplaceId: '22222222-2222-4222-8222-222222222222',
      ruleCode: 'LOW_STOCK', sourceKey: 'TEST-SKU',
      title: 'FBA 可售库存偏低', severity: 'CRITICAL', state: 'OPEN',
      dataOrigin: 'MOCK', lastSeenAt: '2026-10-31T00:00:00Z'
    }
    expect(findingSchema.safeParse(row).success).toBe(true)
    expect(findingSchema.safeParse({ ...row, dataOrigin: 'VERIFIED' }).success).toBe(false)
    expect(findingSchema.safeParse({ ...row, state: 'SENT_TO_AMAZON' }).success).toBe(false)
    expect(ruleNames.LOW_STOCK).toContain('库存')
    expect(stateNames.CLEARED).toBe('条件已消失')
    expect(severityNames.CRITICAL).toBe('优先处理')
  })
  it('never sends a tenantId or Seller credential from the browser on manual scan', async () => {
    const requests: Array<{ input: RequestInfo | URL; init?: RequestInit }> = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      requests.push({ input, init })
      if (String(input).endsWith('/auth/csrf')) {
        return new Response(JSON.stringify({
          token: 'safe-mock-csrf-token',
          headerName: 'X-CSRF-TOKEN'
        }), { status: 200 })
      }
      return new Response(JSON.stringify({
        id: '11111111-1111-4111-8111-111111111111',
        triggerType: 'MANUAL', matchedCount: 2, clearedCount: 0
      }), { status: 200 })
    }))
    expect((await scanOperations()).matchedCount).toBe(2)
    expect(requests).toHaveLength(2)
    expect(String(requests[1].input)).toBe('/api/v1/operations/scan')
    expect(requests[1].init?.method).toBe('POST')
    expect(requests[1].init?.headers).toEqual({
      'X-CSRF-TOKEN': 'safe-mock-csrf-token'
    })
    expect(requests[1].init?.body).toBeUndefined()
  })
})
