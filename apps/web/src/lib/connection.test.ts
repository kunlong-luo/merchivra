import { afterEach, describe, expect, it, vi } from 'vitest'
import { getOauthStatus, sellerCentralConsentUrl, startSellerOauth, verifySellerOauth,
  revokeSellerOauth, requestRoleReview, getRoleReviews } from './connection'

afterEach(() => vi.unstubAllGlobals())

const account = '11111111-1111-4111-8111-111111111111'
const csrf = { ok: true, json: async () => ({
  token: 'synthetic-csrf-123456', headerName: 'X-CSRF-TOKEN'
}) }
const consent = 'https://sellercentral.amazon.com/apps/authorize/consent?application_id=amzn1.sellerapps.app.synthetic&state=synthetic-state'

describe('Seller connection UI API contracts', () => {
  it('recognizes revoked grant status as a separate reauthorization state', async () => {
    const mock = vi.fn().mockResolvedValue({
      ok: true, json: async () => ({ status: 'REVOKED', sellerId: 'A1SYNTHETIC', region: 'NA' })
    })
    vi.stubGlobal('fetch', mock)
    const status = await getOauthStatus(account)
    expect(status.status).toBe('REVOKED')
    expect(status.sellerId).toBe('A1SYNTHETIC')
    expect(mock.mock.calls[0][1]).toMatchObject({ cache: 'no-store', credentials: 'same-origin' })
  })

  it('refuses unapproved consent origins, userinfo, ports and paths', () => {
    expect(sellerCentralConsentUrl(consent)).toBe(consent)
    for (const input of [
      'https://evil.example/apps/authorize/consent?application_id=one&state=two',
      'http://sellercentral.amazon.com/apps/authorize/consent?application_id=one&state=two',
      'https://sellercentral.amazon.com.evil.example/apps/authorize/consent?application_id=one&state=two',
      'https://sellercentral.amazon.com:8443/apps/authorize/consent?application_id=one&state=two',
      'https://user@sellercentral.amazon.com/apps/authorize/consent?application_id=one&state=two',
      'https://sellercentral.amazon.com/other?application_id=one&state=two'
    ]) expect(() => sellerCentralConsentUrl(input)).toThrow()
  })
  it('requires CSRF for OAuth initiation and never sends seller identity or tenant from browser', async () => {
    const mock = vi.fn()
      .mockResolvedValueOnce(csrf)
      .mockResolvedValueOnce({ ok: true, json: async () => ({ authorizationUrl: consent, expiresAt: '2026-10-31T01:00:00Z' }) })
    vi.stubGlobal('fetch', mock)
    const result = await startSellerOauth(account)
    expect(result.authorizationUrl).toBe(consent)
    const [url, request] = mock.mock.calls[1] as [string, RequestInit]
    expect(url).toBe('/api/v1/seller-accounts/' + account + '/oauth/start')
    expect(request.method).toBe('POST')
    expect(request.credentials).toBe('same-origin')
    expect(request.headers).toMatchObject({ 'X-CSRF-TOKEN': 'synthetic-csrf-123456' })
    expect(request.body).toBeUndefined()
  })
  it('passes explicit beta opt-in only for Amazon Draft application consent', async () => {
    const mock = vi.fn().mockResolvedValueOnce(csrf).mockResolvedValueOnce({
      ok: true, json: async () => ({ authorizationUrl: consent + '&version=beta', expiresAt: '2026-10-31T01:00:00Z' })
    })
    vi.stubGlobal('fetch', mock)
    expect((await startSellerOauth(account, true)).authorizationUrl).toContain('version=beta')
    expect(mock.mock.calls[1][0]).toBe('/api/v1/seller-accounts/' + account + '/oauth/start?beta=true')
    expect(mock.mock.calls[1][1].method).toBe('POST')
  })
  it('never navigates to a non-Amazon URL returned by an intermediary', async () => {
    const mock = vi.fn().mockResolvedValueOnce(csrf).mockResolvedValueOnce({
      ok: true, json: async () => ({
        authorizationUrl: 'https://attacker.example/apps/authorize/consent?application_id=foo&state=bar',
        expiresAt: '2026-10-31T01:00:00Z'
      })
    })
    vi.stubGlobal('fetch', mock)
    await expect(startSellerOauth(account)).rejects.toThrow('授权地址')
  })
  it('reads only same-origin auth status without using caller-supplied tenant scope', async () => {
    const mock = vi.fn().mockResolvedValue({
      ok: true, json: async () => ({ status: 'PENDING_VERIFICATION', sellerId: 'A1SYNTHETIC', region: 'NA' })
    })
    vi.stubGlobal('fetch', mock)
    expect((await getOauthStatus(account)).status).toBe('PENDING_VERIFICATION')
    expect(mock.mock.calls[0][0]).toBe('/api/v1/seller-accounts/' + account + '/oauth/status')
    expect(mock.mock.calls[0][1]).toMatchObject({ cache: 'no-store', credentials: 'same-origin' })
  })
  it('does not auto-verify or revoke; each operation uses CSRF POST', async () => {
    const mock = vi.fn()
      .mockResolvedValueOnce(csrf)
      .mockResolvedValueOnce({ ok: true, json: async () => ({ status: 'VERIFIED', verifiedMarketplaceCount: 1 }) })
      .mockResolvedValueOnce(csrf)
      .mockResolvedValueOnce({ ok: true, status: 204 })
    vi.stubGlobal('fetch', mock)
    expect((await verifySellerOauth(account)).status).toBe('VERIFIED')
    await revokeSellerOauth(account)
    expect(mock.mock.calls[1][0]).toContain('/oauth/verify')
    expect(mock.mock.calls[3][0]).toContain('/oauth/revoke')
    expect(mock.mock.calls[3][1].headers).toMatchObject({ 'X-CSRF-TOKEN': 'synthetic-csrf-123456' })
  })
  it('role review accepts only an external digest + case reference and displays server history', async () => {
    const mock = vi.fn()
      .mockResolvedValueOnce(csrf)
      .mockResolvedValueOnce({ ok: true, json: async () => ({ id: account, state: 'PENDING' }) })
      .mockResolvedValueOnce({ ok: true, json: async () => [] })
    vi.stubGlobal('fetch', mock)
    await requestRoleReview(account, 'a'.repeat(64), 'CASE-12345678')
    const [url,request] = mock.mock.calls[1] as [string, RequestInit]
    expect(url).toContain('/orders-role-review')
    expect(JSON.parse(String(request.body))).toEqual({
      evidenceSha256: 'a'.repeat(64), externalCaseRef: 'CASE-12345678'
    })
    expect(await getRoleReviews(account)).toEqual([])
  })
})
