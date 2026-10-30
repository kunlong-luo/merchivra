import { describe, expect, it } from 'vitest'
import { parsePingResponse } from './api'

describe('ping response contract', () => {
  it('accepts the implemented backend ping payload', () => {
    expect(parsePingResponse({ service: 'merchivra', status: 'ok', stage: 'M0' }))
      .toEqual({ service: 'merchivra', status: 'ok', stage: 'M0' })
  })

  it('rejects unknown backend response status', () => {
    expect(() => parsePingResponse({ service: 'merchivra', status: 'failed', stage: 'M0' }))
      .toThrow()
  })

  it('rejects missing fields rather than silently treating them as healthy', () => {
    expect(() => parsePingResponse({ service: 'merchivra' })).toThrow()
  })

  it('rejects spoofed services', () => {
    expect(() => parsePingResponse({ service: 'different-api', status: 'ok', stage: 'M0' }))
      .toThrow()
  })
})
