import {describe,expect,it,vi,afterEach} from 'vitest'
import {coreListingPageSchema,coreOrderPageSchema,
 fetchCoreListingsPage,fetchCoreOrdersPage} from './core'
import {listingChoices,orderChoices,onCurrentPickerPage} from './scoped-core-picker-v90'
afterEach(()=>vi.unstubAllGlobals())
const mp='11111111-1111-4111-8111-111111111111'
const listing=(i:number)=>({
 id:'22222222-2222-4222-8222-'+String(i).padStart(12,'0'),
 sellerSku:'V90-LISTING-'+String(i).padStart(3,'0'),
 asin:'B0V90'+String(i).padStart(3,'0'),fnsku:null,
 mappingStatus:i===64?'CONFLICT':'UNMAPPED',
 internalSku:null,activeMappingVersion:null,latestMappingVersion:null,
 sourceUpdatedAt:null,dataOrigin:'MOCK'
})
const order=(i:number)=>({
 id:'33333333-3333-4333-8333-'+String(i).padStart(12,'0'),
 externalOrderId:'V90-ORDER-'+String(i).padStart(3,'0'),
 fulfillmentStatus:'SHIPPED',refundStatus:'UNKNOWN',
 financialStatus:'NOT_OBSERVED',itemCount:0,itemsObservation:'NOT_OBSERVED',
 sourceUpdatedAt:null,dataOrigin:'MOCK'
})
describe('V90 reusable cross-module full-list server picker',()=>{
 it('selects 64th Seller SKU after paging; never silently substitutes page 1',()=>{
  const first=listingChoices(coreListingPageSchema.parse({
   items:Array.from({length:50},(_,j)=>listing(j+1)),
   nextCursor:'cursor1',hasMore:true,pageSize:50,
   field:'SELLER_SKU',searchPrefix:'v90-listing-',mappingStatus:'ALL'
  }))
  const last=listingChoices(coreListingPageSchema.parse({
   items:Array.from({length:14},(_,j)=>listing(j+51)),
   nextCursor:null,hasMore:false,pageSize:50,
   field:'SELLER_SKU',searchPrefix:'v90-listing-',mappingStatus:'ALL'
  }))
  expect(first.choices).toHaveLength(50)
  expect(last.choices).toHaveLength(14)
  expect(first.hasMore).toBe(true)
  expect(last.hasMore).toBe(false)
  expect(onCurrentPickerPage(first,'V90-LISTING-064')).toBe(false)
  expect(onCurrentPickerPage(last,'V90-LISTING-064')).toBe(true)
  expect(last.choices.at(-1)?.label).toContain('CONFLICT')
  expect(last.choices.at(-1)?.source).toBe('MOCK')
  expect(onCurrentPickerPage(undefined,'V90-LISTING-064')).toBe(false)
 })
 it('selects 64th actual order UUID and does not match wrong page ID',()=>{
  const first=orderChoices(coreOrderPageSchema.parse({
   items:Array.from({length:50},(_,j)=>order(j+1)),
   nextCursor:'cursor2',hasMore:true,pageSize:50,searchPrefix:'v90-order-'
  }))
  const last=orderChoices(coreOrderPageSchema.parse({
   items:Array.from({length:14},(_,j)=>order(j+51)),
   nextCursor:null,hasMore:false,pageSize:50,searchPrefix:'v90-order-'
  }))
  const lastOrder=order(64)
  expect(onCurrentPickerPage(first,lastOrder.id)).toBe(false)
  expect(onCurrentPickerPage(last,lastOrder.id)).toBe(true)
  expect(last.choices.at(-1)?.label).toContain('V90-ORDER-064')
  expect(onCurrentPickerPage(last,order(49).id)).toBe(false)
  expect(onCurrentPickerPage(last,'')).toBe(false)
 })
 it('uses verified same-origin V86/V87 site+cursor APIs for selection',async()=>{
  const requests:Array<{url:string;init:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,init:RequestInit)=>{
   requests.push({url,init})
   const listing=url.includes('/listings/')
   const response=listing?{
    items:[listingData()],nextCursor:null,hasMore:false,pageSize:50,
    field:'SELLER_SKU',searchPrefix:'v90-',mappingStatus:'ALL'
   }:{
    items:[order(64)],nextCursor:null,hasMore:false,pageSize:50,
    searchPrefix:'v90-'
   }
   return {ok:true,json:async()=>response}
  }))
  function listingData(){return listing(64)}
  const a=listingChoices(await fetchCoreListingsPage(mp,'SELLER_SKU',
   'V90-', 'ALL','next-page-token',50))
  const b=orderChoices(await fetchCoreOrdersPage(mp,'V90-','other-token',50))
  expect(a.choices[0].value).toBe('V90-LISTING-064')
  expect(b.choices[0].value).toBe(order(64).id)
  const urls=requests.map(r=>new URL(r.url,'http://localhost'))
  expect(urls[0].pathname).toBe('/api/v1/seller-marketplaces/'+mp+'/listings/page')
  expect(urls[0].searchParams.get('cursor')).toBe('next-page-token')
  expect(urls[1].pathname).toBe('/api/v1/seller-marketplaces/'+mp+'/orders/page')
  expect(urls[1].searchParams.get('cursor')).toBe('other-token')
  expect(urls.map(x=>x.searchParams.get('search'))).toEqual(['V90-','V90-'])
  for(const r of requests){
   expect(r.init.credentials).toBe('same-origin')
   expect(r.init.cache).toBe('no-store')
   expect(r.url).not.toContain('tenantId')
  }
 })
})
