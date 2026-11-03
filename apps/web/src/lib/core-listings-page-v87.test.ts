import {afterEach,describe,expect,it,vi} from 'vitest'
import {coreListingPageSchema,fetchCoreListingsPage} from './core'

afterEach(()=>vi.unstubAllGlobals())
const mp='11111111-1111-4111-8111-111111111111'
const row={
 id:'22222222-2222-4222-8222-222222222222',
 sellerSku:'SKU-ONE',asin:'B0V8701',fnsku:null,
 mappingStatus:'CONFLICT',internalSku:null,
 activeMappingVersion:null,latestMappingVersion:3,
 sourceUpdatedAt:null,dataOrigin:'MOCK'
}
const page={
 items:[row],nextCursor:'opaque-cursor',hasMore:true,
 pageSize:50,field:'ASIN',searchPrefix:'b0v87',mappingStatus:'CONFLICT'
}
describe('V87 complete Listing server-side search/pagination',()=>{
 it('keeps conflicting listing visible with no invented active internal SKU',()=>{
  const parsed=coreListingPageSchema.parse(page)
  expect(parsed.items[0].mappingStatus).toBe('CONFLICT')
  expect(parsed.items[0].internalSku).toBeNull()
  expect(parsed.items[0].activeMappingVersion).toBeNull()
  expect(parsed.items[0].latestMappingVersion).toBe(3)
  expect(parsed.items[0].sourceUpdatedAt).toBeNull()
 })
 it('rejects fabricated mapping revisions, origins or cursors',()=>{
  expect(coreListingPageSchema.safeParse({...page,hasMore:false}).success)
   .toBe(false)
  expect(coreListingPageSchema.safeParse({...page,nextCursor:null}).success)
   .toBe(false)
  expect(coreListingPageSchema.safeParse({...page,field:'AMAZON_PRICE'}).success)
   .toBe(false)
  expect(coreListingPageSchema.safeParse({...page,mappingStatus:'AUTOMATICALLY_LINKED'}).success)
   .toBe(false)
  expect(coreListingPageSchema.safeParse({...page,items:[{
   ...row,latestMappingVersion:-1
  }]}).success).toBe(false)
  expect(coreListingPageSchema.safeParse({...page,items:[{
   ...row,dataOrigin:'AMAZON_VERIFIED'
  }]}).success).toBe(false)
  expect(coreListingPageSchema.safeParse({...page,items:Array(101).fill(row)}).success)
   .toBe(false)
  expect(coreListingPageSchema.parse({
   ...page,items:[{...row,mappingStatus:'ACTIVE',
    internalSku:'INTERNAL-A',activeMappingVersion:2,latestMappingVersion:2}]
  }).items[0].activeMappingVersion).toBe(2)
 })
 it('requests exactly the selected marketplace, field, prefix, status, cursor and page size',async()=>{
  const requests:Array<{url:string,options:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options:RequestInit)=>{
   requests.push({url,options})
   return {ok:true,json:async()=>page}
  }))
  expect((await fetchCoreListingsPage(mp,'ASIN','B0v87 % _!',
   'CONFLICT','page+/=',50)).items).toHaveLength(1)
  const url=new URL(requests[0].url,'http://localhost')
  expect(url.pathname).toBe('/api/v1/seller-marketplaces/'+mp+'/listings/page')
  expect(url.searchParams.get('field')).toBe('ASIN')
  expect(url.searchParams.get('search')).toBe('B0v87 % _!')
  expect(url.searchParams.get('status')).toBe('CONFLICT')
  expect(url.searchParams.get('cursor')).toBe('page+/=')
  expect(url.searchParams.get('limit')).toBe('50')
  expect(requests[0].options.credentials).toBe('same-origin')
  expect(requests[0].options.cache).toBe('no-store')
  expect(requests[0].url).not.toContain('tenantId')
 })
 it('first page omits empty cursor and empty search, retaining explicit status',async()=>{
  const requests:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string)=>{
   requests.push(url)
   return {ok:true,json:async()=>({...page,
    hasMore:false,nextCursor:null,field:'SELLER_SKU',
    searchPrefix:'',mappingStatus:'ALL'})}
  }))
  await fetchCoreListingsPage(mp)
  const url=new URL(requests[0],'http://localhost')
  expect(url.searchParams.has('search')).toBe(false)
  expect(url.searchParams.has('cursor')).toBe(false)
  expect(url.searchParams.get('field')).toBe('SELLER_SKU')
  expect(url.searchParams.get('status')).toBe('ALL')
 })
})
