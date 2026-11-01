import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchSkuProfitPreview,profitCsvUrl,profitQuery,skuProfitPreview} from './sku-profit'
const mp='22222222-2222-4222-8222-222222222222'
const data={
 periodAfter:'2026-10-01',periodBefore:'2026-11-01',
 timezone:'UTC',offset:0,pageSize:30,hasMore:false,periodTruncated:false,
 unverifiedAdsSpendReference:'20.00',unverifiedAdsRecordCount:1,
 adsSourceOrigin:'MANUAL_UNVERIFIED',
 totals:{scannedOrders:1,eligibleOrders:0,blockedOrders:1,
  multiSkuOrders:1,sourceEvents:0,currency:'USD',
  sales:'0',amazonFees:'0',fbaFees:'0',cogs:'0',contribution:'0',
  completePeriod:true,financialSourceComplete:false,
  bankVerified:false,ledgerPosted:false},
 skuRows:[],orders:[{
  orderId:'11111111-1111-4111-8111-111111111111',
  externalOrderId:'ORDER-A',orderedAt:'2026-10-30T00:00:00Z',
  sku:null,units:2,readiness:'MULTI_SKU_UNALLOCATED',currency:'USD',
  reviewedEventCount:0,sales:null,amazonFees:null,fbaFees:null,
  cogs:null,contribution:null
 }]
}
describe('V61 scoped contribution preview',()=>{
 afterEach(()=>vi.unstubAllGlobals())
 it('cannot spoof bank settlement or fully verified financial sources',()=>{
  expect(skuProfitPreview.parse(data).totals.contribution).toBe('0')
  expect(()=>skuProfitPreview.parse({...data,totals:{
   ...data.totals,bankVerified:true}})).toThrow()
  expect(()=>skuProfitPreview.parse({...data,totals:{
   ...data.totals,financialSourceComplete:true}})).toThrow()
  expect(()=>skuProfitPreview.parse({...data,adsSourceOrigin:'LIVE_ADS'})).toThrow()
 })
 it('uses owner session and bounded URL without browser supplied tenant ID',async()=>{
  const mock=vi.fn().mockResolvedValue(new Response(JSON.stringify(data),{
   status:200,headers:{'content-type':'application/json'}
  }))
  vi.stubGlobal('fetch',mock)
  expect((await fetchSkuProfitPreview(mp,'2026-10-01','2026-11-01',0))
    .orders[0].contribution).toBeNull()
  const [url,options]=mock.mock.calls[0]
  expect(url).toBe(profitQuery(mp,'2026-10-01','2026-11-01',0))
  expect(url).not.toContain('tenantId')
  expect(options.credentials).toBe('same-origin')
  expect(options.cache).toBe('no-store')
  expect(options).not.toHaveProperty('body')
  expect(profitCsvUrl(mp,'2026-10-01','2026-11-01',0)).toContain('/export.csv?')
 })
 it('rejects invented offsets or malformed dates before remote request',async()=>{
  const mock=vi.fn()
  vi.stubGlobal('fetch',mock)
  await expect(fetchSkuProfitPreview(mp,'fake','2026-11-01',0)).rejects.toThrow()
  await expect(fetchSkuProfitPreview(mp,'2026-10-01','2026-11-01',1)).rejects.toThrow()
  expect(mock).not.toHaveBeenCalled()
 })
})
