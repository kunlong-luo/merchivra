import {afterEach,describe,expect,it,vi} from 'vitest'
import {createPeriodProfitReport,exportPeriodProfitUrl,
 getPeriodProfitReport,periodProfitReport,progressPeriodProfitReport,
 resumePeriodProfitReport} from './period-profit'
const mp='22222222-2222-4222-8222-222222222222'
const id='11111111-1111-4111-8111-111111111111'
const requestKey='33333333-3333-4333-8333-333333333333'
const job={
 id,state:'SUCCEEDED',after:'2026-09-01',before:'2026-10-01',
 currency:'USD',totalOrders:1,processedOrders:1,eligibleOrders:0,
 blockedOrders:1,retryCount:0,failureCode:null,manualAdsReference:'25.00',
 manualAdsCount:2,createdAt:'2026-11-01T00:00:00Z',
 completedAt:'2026-11-01T00:01:00Z',
 cohortFrozen:true,globalSourceSnapshot:false,
 financialSourceComplete:false,bankVerified:false,ledgerPosted:false
}
const report={
 job,totals:{currency:'USD',eligibleOrders:0,blockedOrders:1,
  reviewedEvents:0,signedSales:'0',signedAmazonFees:'0',signedFbaFees:'0',
  provisionalCogs:'0',contribution:'0'},skus:[],
 orders:[{orderId:'44444444-4444-4444-8444-444444444444',
  position:0,state:'BLOCKED',readiness:'REFUND_COST_REVERSAL_UNVERIFIED',
  currency:'USD',sales:null,fees:null,fba:null,cogs:null,contribution:null,
  extractedAt:'2026-11-01T00:00:00Z',extractSha256:'a'.repeat(64)}],
 orderOffset:0,hasMore:false
}
describe('V64 durable period contribution API',()=>{
 afterEach(()=>vi.unstubAllGlobals())
 it('rejects false global source snapshot or bank / financial claims',()=>{
  expect(periodProfitReport.parse(report).job.blockedOrders).toBe(1)
  expect(()=>periodProfitReport.parse({...report,job:{
   ...job,globalSourceSnapshot:true}})).toThrow()
  expect(()=>periodProfitReport.parse({...report,job:{
   ...job,bankVerified:true}})).toThrow()
  expect(()=>periodProfitReport.parse({...report,job:{
   ...job,financialSourceComplete:true}})).toThrow()
  expect(()=>periodProfitReport.parse({...report,job:{
   ...job,ledgerPosted:true}})).toThrow()
 })
 it('reads immutable extracted source amounts without browser tenant',async()=>{
  const mock=vi.fn().mockResolvedValue({ok:true,json:async()=>report})
  vi.stubGlobal('fetch',mock)
  expect((await getPeriodProfitReport(mp,id)).orders[0].sales).toBeNull()
  const [url,init]=mock.mock.calls[0]
  expect(url).toContain('/marketplaces/'+mp+'/profit-reports/'+id+'?offset=0')
  expect(url).not.toContain('tenantId')
  expect(init.credentials).toBe('same-origin')
  expect(init.cache).toBe('no-store')
  expect(exportPeriodProfitUrl(mp,id)).toContain('/export.csv')
  await expect(getPeriodProfitReport(mp,id,51)).rejects.toThrow()
 })
 it('creates an explicit idempotent job with CSRF and no outbound Amazon API',async()=>{
  const mock=vi.fn().mockImplementation(async (url:string)=>({
   ok:true,json:async()=>url.endsWith('/auth/csrf')?
    {token:'1234567890abcdef',headerName:'X-CSRF-TOKEN'}:job
  }))
  vi.stubGlobal('fetch',mock)
  await createPeriodProfitReport(mp,requestKey,'2026-09-01','2026-10-01')
  const [url,init]=mock.mock.calls[1]
  expect(url).toContain('/marketplaces/'+mp+'/profit-reports')
  expect(init.method).toBe('POST')
  expect(init.headers['X-CSRF-TOKEN']).toBe('1234567890abcdef')
  expect(JSON.parse(init.body)).toEqual({
   requestKey,after:'2026-09-01',before:'2026-10-01'
  })
  expect(init.body).not.toContain('tenantId')
  await progressPeriodProfitReport(mp,id)
  await resumePeriodProfitReport(mp,id)
  expect(mock.mock.calls[3][0]).toContain('/step')
  expect(mock.mock.calls[5][0]).toContain('/resume')
 })
})
