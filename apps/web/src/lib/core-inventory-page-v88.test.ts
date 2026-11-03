import {afterEach,describe,expect,it,vi} from 'vitest'
import {coreInventoryPageSchema,fetchCoreInventoryPage} from './core'

afterEach(()=>vi.unstubAllGlobals())
const mp='11111111-1111-4111-8111-111111111111'
const unknown={
 id:'22222222-2222-4222-8222-222222222222',sellerSku:'V88-UNKNOWN',
 quality:'UNKNOWN',fulfillable:null,reserved:null,
 observedAt:null,ingestedAt:'2026-11-03T10:00:00Z',dataOrigin:'UNVERIFIED'
}
const zero={
 id:'33333333-3333-4333-8333-333333333333',sellerSku:'V88-ZERO',
 quality:'OBSERVED',fulfillable:0,reserved:0,
 observedAt:'2026-11-02T12:00:00Z',
 ingestedAt:'2026-11-03T10:00:00Z',dataOrigin:'MOCK'
}
const stale={
 id:'44444444-4444-4444-8444-444444444444',sellerSku:'V88-STALE',
 quality:'STALE',fulfillable:7,reserved:2,
 observedAt:'2026-11-01T12:00:00Z',
 ingestedAt:'2026-11-03T10:00:00Z',dataOrigin:'MOCK'
}
const page={items:[unknown,zero,stale],nextCursor:null,hasMore:false,
 pageSize:50,searchPrefix:'v88',quality:'ALL',origin:'ALL'}
describe('V88 complete latest FBA inventory observation pages',()=>{
 it('preserves UNKNOWN null, true observed zero, STALE and separate source times',()=>{
  const rows=coreInventoryPageSchema.parse(page).items
  expect(rows[0].fulfillable).toBeNull()
  expect(rows[0].reserved).toBeNull()
  expect(rows[0].observedAt).toBeNull()
  expect(rows[0].ingestedAt).toMatch(/^2026-/)
  expect(rows[1].fulfillable).toBe(0)
  expect(rows[1].reserved).toBe(0)
  expect(rows[2].quality).toBe('STALE')
  expect(rows[2].fulfillable).toBe(7)
 })
 it('refuses untrusted quality, origin, oversized data or incoherent paging',()=>{
  expect(coreInventoryPageSchema.safeParse({...page,quality:'FRESH'}).success)
   .toBe(false)
  expect(coreInventoryPageSchema.safeParse({...page,origin:'VERIFIED_BY_AMAZON'}).success)
   .toBe(false)
  expect(coreInventoryPageSchema.safeParse({...page,hasMore:true}).success)
   .toBe(false)
  expect(coreInventoryPageSchema.safeParse({...page,nextCursor:'invented'}).success)
   .toBe(false)
  expect(coreInventoryPageSchema.safeParse({...page,items:[{
   ...unknown,fulfillable:-1
  }]}).success).toBe(false)
  expect(coreInventoryPageSchema.safeParse({...page,items:[{
   ...unknown,dataOrigin:'PRESUMED_LIVE'
  }]}).success).toBe(false)
  expect(coreInventoryPageSchema.safeParse({...page,items:Array(101).fill(zero)}).success)
   .toBe(false)
 })
 it('requests exact server scope/filter/keyset via same-origin GET, no tenantId',async()=>{
  const requests:Array<{url:string,options:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options:RequestInit)=>{
   requests.push({url,options})
   return {ok:true,json:async()=>page}
  }))
  expect((await fetchCoreInventoryPage(mp,'V88 %_!','UNKNOWN',
   'UNVERIFIED','opaque+/=',50)).items).toHaveLength(3)
  const url=new URL(requests[0].url,'http://localhost')
  expect(url.pathname).toBe('/api/v1/seller-marketplaces/'+mp+'/inventory/fba/page')
  expect(url.searchParams.get('search')).toBe('V88 %_!')
  expect(url.searchParams.get('quality')).toBe('UNKNOWN')
  expect(url.searchParams.get('origin')).toBe('UNVERIFIED')
  expect(url.searchParams.get('cursor')).toBe('opaque+/=')
  expect(url.searchParams.get('limit')).toBe('50')
  expect(requests[0].options.credentials).toBe('same-origin')
  expect(requests[0].options.cache).toBe('no-store')
  expect(requests[0].url).not.toContain('tenantId')
 })
 it('omits empty search/cursor while leaving explicit ALL filters',async()=>{
  const requests:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string)=>{
   requests.push(url)
   return {ok:true,json:async()=>page}
  }))
  await fetchCoreInventoryPage(mp)
  const url=new URL(requests[0],'http://localhost')
  expect(url.searchParams.has('search')).toBe(false)
  expect(url.searchParams.has('cursor')).toBe(false)
  expect(url.searchParams.get('quality')).toBe('ALL')
  expect(url.searchParams.get('origin')).toBe('ALL')
 })
})
