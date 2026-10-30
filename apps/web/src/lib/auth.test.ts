import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchViewer, loginSession, logoutSession, viewerSchema } from './auth'

afterEach(() => vi.unstubAllGlobals())

describe('session authentication contract', () => {
  it('treats 401 as no session, not a logged-in user', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 401 }))
    expect(await fetchViewer()).toBeNull()
  })

  it('rejects a user with invalid tenant identity', () => {
    expect(() => viewerSchema.parse({
      userId: 'known-user', activeTenantId: 'attacker-controlled',
      availableTenantIds: []
    })).toThrow()
  })

  it('sends CSRF header and never places password in a URL', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ token: 'long-secure-csrf-token', headerName: 'X-CSRF-TOKEN' })
      })
      .mockResolvedValueOnce({ ok: true })
    vi.stubGlobal('fetch', fetchMock)
    await loginSession('local-admin', 'example-secret-not-real')
    const [url, request] = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(url).toBe('/api/v1/auth/login')
    expect(request.headers).toMatchObject({ 'X-CSRF-TOKEN': 'long-secure-csrf-token' })
    expect(String(request.body)).toContain('username=local-admin')
    expect(url).not.toContain('example-secret-not-real')
  })

  it('requires a CSRF header for logout', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ token: 'long-secure-csrf-token', headerName: 'X-CSRF-TOKEN' })
      })
      .mockResolvedValueOnce({ ok: true })
    vi.stubGlobal('fetch', fetchMock)
    await logoutSession()
    const [url, request] = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(url).toBe('/api/v1/auth/logout')
    expect(request.method).toBe('POST')
    expect(request.headers).toMatchObject({ 'X-CSRF-TOKEN': 'long-secure-csrf-token' })
  })
})
