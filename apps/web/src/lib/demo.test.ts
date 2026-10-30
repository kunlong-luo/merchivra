import { describe, expect, it } from 'vitest'
import { parseDemoOverview } from './demo'

const base = {
  mode: 'MOCK',
  notice: '虚构演示',
  fixtureAsOf: '2026-10-28T12:00:00Z',
  accounts: [],
  listings: [],
  orders: [],
  inventory: [],
  summaries: []
}

describe('demo overview contract', () => {
  it('accepts a valid empty mock envelope', () => {
    expect(parseDemoOverview(base).mode).toBe('MOCK')
  })

  it('rejects unmarked data that might be mistaken for real seller data', () => {
    expect(() => parseDemoOverview({ ...base, mode: 'LIVE' })).toThrow()
  })

  it('accepts explicit unknown FBA stock rather than inventing zero', () => {
    const withUnknown = {
      ...base,
      inventory: [{
        id: 'inv-1',
        accountId: '11111111-1111-4111-8111-111111111111',
        sellerSku: 'UNKNOWN-01',
        fulfillable: null,
        reserved: null,
        quality: 'UNKNOWN',
        observedAt: null
      }]
    }
    expect(parseDemoOverview(withUnknown).inventory[0].fulfillable).toBeNull()
  })

  it('rejects negative stock quantities', () => {
    const wrong = {
      ...base,
      inventory: [{
        id: 'inv-1',
        accountId: '11111111-1111-4111-8111-111111111111',
        sellerSku: 'SKU-01',
        fulfillable: -1,
        reserved: 0,
        quality: 'OBSERVED',
        observedAt: null
      }]
    }
    expect(() => parseDemoOverview(wrong)).toThrow()
  })
})
