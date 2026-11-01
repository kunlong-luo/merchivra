import {afterEach,describe,expect,it,vi} from 'vitest'
import {getSourceReview,requestSourceReview,sourceReview} from './settlement-source-review'

const mp='22222222-2222-4222-8222-222222222222'
const report='11111111-1111-4111-8111-111111111111'
const body={
 state:'STALE',sourceState:'SOURCE_TOTAL_DIFFERENCE',
 requestedBy:'33333333-3333-4333-8333-333333333333',
 decidedBy:'44444444-4444-4444-8444-444444444444',
 requestedAt:'2026-11-01T09:00:00Z',decidedAt:'2026-11-01T09:00:02Z',
 version:3,evidenceCurrent:false,bankVerified:false,ledgerPosted:false
}
describe('Settlement report review integrity',()=>{
 afterEach(()=>vi.unstubAllGlobals())
 it('requires false bank and ledger flags even after financial source approval',()=>{
  expect(sourceReview.parse(body).state).toBe('STALE')
  expect(()=>sourceReview.parse({...body,bankVerified:true})).toThrow()
  expect(()=>sourceReview.parse({...body,ledgerPosted:true})).toThrow()
  expect(()=>sourceReview.parse({...body,sourceState:'BANK_PAID'})).toThrow()
 })
 it('reads only the selected marketplace and report without browser tenantId',async()=>{
  const mock=vi.fn().mockResolvedValue({ok:true,json:async()=>body})
  vi.stubGlobal('fetch',mock)
  expect((await getSourceReview(mp,report)).evidenceCurrent).toBe(false)
  const [url,options]=mock.mock.calls[0]
  expect(url).toContain('/finance/marketplaces/'+mp+'/reports/'+report+'/source-review')
  expect(url).not.toContain('tenantId')
  expect(options.credentials).toBe('same-origin')
  expect(options.cache).toBe('no-store')
  expect(options).not.toHaveProperty('body')
 })
 it('uses CSRF and only a bounded text rationale for owner request',async()=>{
  const mock=vi.fn().mockImplementation(async (url:string)=>({
   ok:true,json:async()=>url.endsWith('/auth/csrf')?
    {token:'1234567890abcdef',headerName:'X-CSRF-TOKEN'}:body
  }))
  vi.stubGlobal('fetch',mock)
  await requestSourceReview(mp,report,'Audit the source amounts')
  const [url,options]=mock.mock.calls[1]
  expect(url).toContain('/source-review/request')
  expect(options.method).toBe('POST')
  expect(options.headers['X-CSRF-TOKEN']).toBe('1234567890abcdef')
  expect(JSON.parse(options.body)).toEqual({reason:'Audit the source amounts'})
  expect(options.body).not.toContain('refreshToken')
 })
 it('refuses false review success when second owner is missing',async()=>{
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:false,status:403}))
  await expect(getSourceReview(mp,report)).rejects.toThrow('另一位')
 })
})
