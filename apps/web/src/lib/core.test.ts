import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchCoreMarketplaces, coreInventorySchema, coreOrderSchema } from './core'

afterEach(() => vi.unstubAllGlobals())

describe('core ERP read contract', () => {
  it('reads session-scoped marketplaces without putting tenant IDs in the request', async () => {
    const f = vi.fn().mockResolvedValue({ ok: true, json: async () => [] })
    vi.stubGlobal('fetch', f)
    expect(await fetchCoreMarketplaces()).toEqual([])
    expect(f.mock.calls[0][0]).toBe('/api/v1/seller-marketplaces')
    expect(f.mock.calls[0][1].credentials).toBe('same-origin')
  })

  it('keeps unknown FBA inventory distinct from observed zero', () => {
    const fields = {
      id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      sellerSku: 'SKU-001',
      observedAt: null,
      ingestedAt: '2026-10-30T00:00:00Z', dataOrigin: 'MOCK'
    }
    expect(coreInventorySchema.parse({
      ...fields, quality: 'UNKNOWN', fulfillable: null, reserved: null
    }).fulfillable).toBeNull()
    expect(coreInventorySchema.parse({
      ...fields, quality: 'OBSERVED', fulfillable: 0, reserved: 0
    }).fulfillable).toBe(0)
  })

  it('rejects impossible negative stock and keeps finance separate from fulfillment', () => {
    const id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
    expect(() => coreInventorySchema.parse({
      id, sellerSku: 'SKU-001', quality: 'OBSERVED',
      fulfillable: -1, reserved: 0, observedAt: null, ingestedAt: '2026-10-30T00:00:00Z', dataOrigin: 'MOCK'
    })).toThrow()
    expect(coreOrderSchema.parse({
      id, externalOrderId: 'ORDER-001', fulfillmentStatus: 'SHIPPED',
      refundStatus: 'PARTIAL', financialStatus: 'NOT_OBSERVED',
      itemCount: 1, sourceUpdatedAt: null, dataOrigin: 'MOCK'
    }).financialStatus).toBe('NOT_OBSERVED')
  })
})
