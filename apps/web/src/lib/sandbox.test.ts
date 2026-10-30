import { afterEach, describe, expect, it, vi } from 'vitest'
import { advanceMockPage, fetchMockPageProgress, mockPageProgressSchema } from './sandbox'

afterEach(() => vi.unstubAllGlobals())

const market = '22222222-2222-4222-8222-222222222222'
describe('durable Mock page calls', () => {
  it('rejects impossible progress and accepts restart-safe records', () => {
    expect(() => mockPageProgressSchema.parse({
      status: 'RUNNING', nextPage: -1, totalPages: 2, leaseEpoch: 1, pages: []
    })).toThrow()
    expect(mockPageProgressSchema.parse({
      status: 'RUNNING', nextPage: 1, totalPages: 2, leaseEpoch: 1,
      pages: [{ status: 'RUNNING', committedPage: 0, nextPage: 1,
        totalPages: 2, recordCount: 1, leaseEpoch: 1, replayed: false }]
    }).nextPage).toBe(1)
  })

  it('reads progress through same-origin session without tenantId parameter', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true, json: async () => ({
        status: 'NOT_STARTED', nextPage: 0, totalPages: 2, leaseEpoch: 0, pages: []
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    expect((await fetchMockPageProgress(market)).status).toBe('NOT_STARTED')
    expect(fetchMock.mock.calls[0][0]).toContain('/pages')
    expect(fetchMock.mock.calls[0][1].credentials).toBe('same-origin')
  })

  it('sends a CSRF token and stable Idempotency-Key for advancing a page', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () =>
        ({ token: 'mock-csrf-value-1234', headerName: 'X-CSRF-TOKEN' }) })
      .mockResolvedValueOnce({ ok: true, json: async () => ({
        status: 'RUNNING', committedPage: 0, nextPage: 1,
        totalPages: 2, recordCount: 1, leaseEpoch: 1, replayed: false
      }) })
    vi.stubGlobal('fetch', fetchMock)
    expect((await advanceMockPage(market, 'mock-page-test-001')).nextPage).toBe(1)
    const [url, opts] = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(url).toContain('/pages/advance')
    expect(opts.headers).toMatchObject({
      'X-CSRF-TOKEN': 'mock-csrf-value-1234',
      'Idempotency-Key': 'mock-page-test-001'
    })
  })
})
