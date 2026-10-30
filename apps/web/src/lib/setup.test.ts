import { afterEach, describe, expect, it, vi } from 'vitest'
import { createLocalAccount, fetchLocalAccounts, MARKETPLACE_PRESETS } from './setup'

afterEach(() => vi.unstubAllGlobals())

describe('local seller setup', () => {
  it('does not accept a browser tenantId for account discovery', async () => {
    const mock = vi.fn().mockResolvedValue({ ok: true, json: async () => [] })
    vi.stubGlobal('fetch', mock)
    expect(await fetchLocalAccounts()).toEqual([])
    expect(mock.mock.calls[0][0]).toBe('/api/v1/seller-accounts')
  })

  it('uses a CSRF token and an idempotency key for writes', async () => {
    const mock = vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () =>
        ({ token: 'safe-csrf-token-123456', headerName: 'X-CSRF-TOKEN' }) })
      .mockResolvedValueOnce({ ok: true, json: async () => ({
        id: '11111111-1111-4111-8111-111111111111',
        displayName: 'Local Store', region: 'NA', connectionStatus: 'UNCONFIGURED'
      }) })
    vi.stubGlobal('fetch', mock)
    const created = await createLocalAccount({ displayName: 'Local Store', region: 'NA' }, 'local-request-01')
    expect(created.connectionStatus).toBe('UNCONFIGURED')
    const [url, options] = mock.mock.calls[1] as [string, RequestInit]
    expect(url).toBe('/api/v1/seller-accounts')
    expect(options.headers).toMatchObject({
      'X-CSRF-TOKEN': 'safe-csrf-token-123456', 'Idempotency-Key': 'local-request-01'
    })
    expect(String(options.body)).not.toContain('tenantId')
  })

  it('limits marketplace presets to one curated entry per region', () => {
    expect(MARKETPLACE_PRESETS.NA[0].id).toBe('ATVPDKIKX0DER')
    expect(MARKETPLACE_PRESETS.EU[0].id).toBe('A1PA6795UKMFR9')
  })
})
