import {afterEach,describe,expect,it,vi} from 'vitest'
import {businessOverviewSchema,businessQualityCsvUrl,
 fetchBusinessOverview} from './business-overview-v89'

afterEach(()=>vi.unstubAllGlobals())
const id='11111111-1111-4111-8111-111111111111'
const sources={mock:1,unverified:0,live:0}
const mp={
 id,marketplaceId:'V89-US',region:'NA',currency:'USD',
 listings:{total:1,unmapped:1,conflicts:0,unknownSourceTime:1,sources},
 orders:{total:1,itemsNotObserved:1,financeNotObserved:1,
  unknownRefund:1,unknownSourceTime:1,sources},
 inventory:{skus:1,observed:0,partial:0,unknown:1,stale:0,
  unknownFulfillable:1,observedZeroFulfillable:0,
  unknownSourceTime:1,sources},
 latestReport:null
}
const overview={
 capturedAt:'2026-11-04T00:00:00Z',
 sourceSnapshotFrozen:false,financialSourceComplete:false,
 bankVerified:false,ledgerPosted:false,
 crossCurrencyTotalsAvailable:false,mixedCurrencies:false,
 marketplaces:[mp]
}
describe('V89 operational source-quality overview',()=>{
 it('keeps UNKNOWN FBA separate from zero and makes no fake financial totals',()=>{
  const parsed=businessOverviewSchema.parse(overview)
  expect(parsed.marketplaces[0].inventory.unknown).toBe(1)
  expect(parsed.marketplaces[0].inventory.observedZeroFulfillable).toBe(0)
  expect(parsed.marketplaces[0].latestReport).toBeNull()
  expect(parsed.sourceSnapshotFrozen).toBe(false)
  expect(parsed.crossCurrencyTotalsAvailable).toBe(false)
 })
 it('rejects provenance gaps, duplicated site and incompatible cross-currency aggregates',()=>{
  expect(businessOverviewSchema.safeParse({
   ...overview,marketplaces:[{...mp,orders:{...mp.orders,
    sources:{mock:0,unverified:0,live:0}}}]
  }).success).toBe(false)
  expect(businessOverviewSchema.safeParse({
   ...overview,marketplaces:[{...mp,inventory:{
    ...mp.inventory,observed:1}}]
  }).success).toBe(false)
  expect(businessOverviewSchema.safeParse({
   ...overview,marketplaces:[mp,mp]
  }).success).toBe(false)
  expect(businessOverviewSchema.safeParse({
   ...overview,crossCurrencyTotalsAvailable:true
  }).success).toBe(false)
  expect(businessOverviewSchema.safeParse({
   ...overview,mixedCurrencies:true
  }).success).toBe(false)
 })
 it('permits separate USD and GBP sites without combining them',()=>{
  const parsed=businessOverviewSchema.parse({
   ...overview,mixedCurrencies:true,
   marketplaces:[mp,{...mp,id:'22222222-2222-4222-8222-222222222222',
    marketplaceId:'V89-GB',currency:'GBP'}]
  })
  expect(parsed.marketplaces.map(m=>m.currency)).toEqual(['USD','GBP'])
  expect(parsed.crossCurrencyTotalsAvailable).toBe(false)
 })
 it('accepts only successful V64 contribution in its original currency',()=>{
  const base={
   id:'33333333-3333-4333-8333-333333333333',
   state:'READY',after:'2026-11-01',before:'2026-11-04',
   currency:'USD',totalOrders:2,processedOrders:0,
   eligibleOrders:0,blockedOrders:0,contribution:null,
   cohortFrozen:true,globalSourceSnapshot:false,
   financialSourceComplete:false,bankVerified:false,ledgerPosted:false
  }
  expect(businessOverviewSchema.safeParse({
   ...overview,marketplaces:[{...mp,latestReport:base}]
  }).success).toBe(true)
  expect(businessOverviewSchema.safeParse({
   ...overview,marketplaces:[{...mp,latestReport:{
    ...base,contribution:'100.000000'}}]
  }).success).toBe(false)
  expect(businessOverviewSchema.safeParse({
   ...overview,marketplaces:[{...mp,latestReport:{
    ...base,state:'SUCCEEDED',processedOrders:2,
    eligibleOrders:2,contribution:'100.000000'}}]
  }).success).toBe(true)
  expect(businessOverviewSchema.safeParse({
   ...overview,marketplaces:[{...mp,latestReport:{
    ...base,currency:'GBP',state:'SUCCEEDED',processedOrders:2,
    eligibleOrders:2,contribution:'100.000000'}}]
  }).success).toBe(false)
 })
 it('uses owner-only same-origin cache-free API, not demo or browser-supplied tenant',async()=>{
  const calls:Array<{url:string,options:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options:RequestInit)=>{
   calls.push({url,options})
   return {ok:true,json:async()=>overview}
  }))
  expect((await fetchBusinessOverview()).marketplaces).toHaveLength(1)
  expect(calls[0].url).toBe('/api/v1/business-overview')
  expect(calls[0].options.credentials).toBe('same-origin')
  expect(calls[0].options.cache).toBe('no-store')
  expect(calls[0].url).not.toContain('tenantId')
  expect(businessQualityCsvUrl).toBe('/api/v1/business-overview/export.csv')
 })
 it('shows restricted Owner or coverage errors rather than returning fabricated zero',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>({ok:false,status:403})))
  await expect(fetchBusinessOverview()).rejects.toThrow('Owner')
 })
})
