import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchViewer, loginSession, logoutSession, viewerSchema,
  SessionCheckError,sessionFailureDetails,retryableSessionFailure } from './auth'

afterEach(() => vi.unstubAllGlobals())

describe('session authentication contract', () => {
  it('treats 401 as no session, not a logged-in user', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 401 }))
    expect(await fetchViewer()).toBeNull()
  })

  it('does not confuse HTTP 500 and proxy errors with incorrect credentials', async () => {
    for (const status of [500,502,503,504]) {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({status,ok:false}))
      await expect(fetchViewer()).rejects.toMatchObject({
        kind:'SERVICE',httpStatus:status
      })
      const description=sessionFailureDetails(new SessionCheckError('SERVICE',status))
      expect(description.heading).toBe('本地服务暂时未就绪')
      expect(description.description).not.toContain('密码错误')
      expect(description.diagnostic).toContain(`HTTP ${status}`)
      expect(retryableSessionFailure(new SessionCheckError('SERVICE',status))).toBe(true)
    }
  })

  it('labels missing proxy route and permission denied separately', async () => {
    for (const [status,kind] of [[403,'FORBIDDEN'],[404,'ROUTE']] as const) {
      vi.stubGlobal('fetch',vi.fn().mockResolvedValue({status,ok:false}))
      await expect(fetchViewer()).rejects.toMatchObject({kind,httpStatus:status})
      expect(retryableSessionFailure(new SessionCheckError(kind,status))).toBe(false)
    }
  })

  it('protects login when backend is offline or returns malformed user data', async () => {
    vi.stubGlobal('fetch',vi.fn().mockRejectedValue(new TypeError('connection refused')))
    await expect(fetchViewer()).rejects.toMatchObject({kind:'NETWORK',httpStatus:null})
    const network=sessionFailureDetails(new SessionCheckError('NETWORK'))
    expect(network.description).toContain('登录服务')
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({
      status:200,ok:true,json:async()=>({userId:'wrong',isOwner:true})
    }))
    await expect(fetchViewer()).rejects.toMatchObject({kind:'RESPONSE'})
    expect(retryableSessionFailure(new SessionCheckError('RESPONSE'))).toBe(false)
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
