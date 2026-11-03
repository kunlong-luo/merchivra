import {afterEach,describe,expect,it,vi} from 'vitest'
import {coreOrderPageSchema,fetchCoreOrdersPage} from './core'

afterEach(()=>vi.unstubAllGlobals())
const mp='11111111-1111-4111-8111-111111111111'
const row={id:'22222222-2222-4222-8222-222222222222',
 externalOrderId:'V86-SAMPLE-001',fulfillmentStatus:'SHIPPED',
 refundStatus:'PARTIAL',financialStatus:'NOT_OBSERVED',itemCount:0,
 itemsObservation:'NOT_OBSERVED',sourceUpdatedAt:null,dataOrigin:'MOCK'}
const page={items:[row],nextCursor:'opaque-cursor',hasMore:true,
 pageSize:50,searchPrefix:'v86'}
describe('V86 server-paged core orders',()=>{
 it('keeps null source timestamp and independent business statuses',()=>{
  expect(coreOrderPageSchema.parse(page).items[0].sourceUpdatedAt).toBeNull()
  expect(coreOrderPageSchema.parse(page).items[0].refundStatus).toBe('PARTIAL')
  expect(coreOrderPageSchema.parse({...page,hasMore:false,nextCursor:null}).hasMore)
   .toBe(false)
 })
 it('rejects inconsistent cursor, count overflow and fabricated origin',()=>{
  expect(coreOrderPageSchema.safeParse({...page,nextCursor:null}).success).toBe(false)
  expect(coreOrderPageSchema.safeParse({...page,hasMore:false}).success).toBe(false)
  expect(coreOrderPageSchema.safeParse({...page,items:Array(101).fill(row)}).success)
   .toBe(false)
  expect(coreOrderPageSchema.safeParse({...page,items:[{...row,dataOrigin:'AMAZON_VERIFIED'}]})
   .success).toBe(false)
 })
 it('uses same-origin read without tenantId, escaping server-side prefix and cursor',async()=>{
  const calls:Array<{url:string,options:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options:RequestInit)=>{
   calls.push({url,options})
   return {ok:true,json:async()=>page}
  }))
  const result=await fetchCoreOrdersPage(mp,'V86 % test','opaque+/=',50)
  expect(result.items).toHaveLength(1)
  const u=new URL(calls[0].url,'http://localhost')
  expect(u.pathname).toBe('/api/v1/seller-marketplaces/'+mp+'/orders/page')
  expect(u.searchParams.get('search')).toBe('V86 % test')
  expect(u.searchParams.get('cursor')).toBe('opaque+/=')
  expect(u.searchParams.get('limit')).toBe('50')
  expect(calls[0].options.credentials).toBe('same-origin')
  expect(calls[0].options.cache).toBe('no-store')
  expect(calls[0].url).not.toContain('tenantId')
 })
 it('omits empty search and missing cursor rather than inventing a position',async()=>{
  const seen:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string)=>{
   seen.push(url);return {ok:true,json:async()=>({...page,nextCursor:null,hasMore:false})}
  }))
  await fetchCoreOrdersPage(mp,'',null)
  expect(seen[0]).not.toContain('cursor=')
  expect(seen[0]).not.toContain('search=')
 })
})
