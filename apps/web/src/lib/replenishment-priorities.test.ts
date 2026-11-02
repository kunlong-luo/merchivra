import {afterEach,describe,expect,it,vi} from 'vitest'
import {getReplenishmentPriorities,prioritySchema} from './replenishment-priorities'

afterEach(()=>vi.unstubAllGlobals())
describe('V74 evidence-only replenishment priority',()=>{
 const sample={
  sellerSku:'V74-SYNTHETIC',stockState:'REORDER',
  demandStatus:'MOCK_SAMPLE',evidenceLevel:'MOCK_SAMPLE',
  riskCode:'LEAD_WINDOW_EXPOSURE',priorityCode:'HIGH',
  stockSourceMode:'MOCK',demandSourceMode:'MOCK',
  observationId:'11111111-1111-4111-8111-111111111111',
  policyUpdatedAt:'2026-11-02T00:00:00Z',fulfillable:2,
  observedAt:'2026-11-02T00:00:00Z',policyLeadTimeDays:14,
  observedUnits28d:28,observedDailyUnits28d:'1.000',
  illustrativeCoverageDays:'2.0',illustrativePolicyLeadUnits:14,
  readySupplierQuoteCount:1,shortestSupplierTermDays:17,
  longestSupplierTermDays:17,localDraftOrSubmittedUnits:36,
  localApprovedUnreceivedUnits:0,localReceivedUnits:0,
  localPendingOrderCount:1,existingLocalOrderNeedsReview:true
 }
 it('uses read-only Session endpoint without tenantId or CSRF',async()=>{
  const calls:Array<{url:string;init?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,init?:RequestInit)=>{
   calls.push({url,init})
   return new Response(JSON.stringify([sample]),{status:200})
  }))
  expect((await getReplenishmentPriorities('22222222-2222-4222-8222-222222222222'))[0]
    .illustrativeCoverageDays).toBe('2.0')
  expect(calls[0].url).toBe('/api/v1/replenishment/marketplaces/22222222-2222-4222-8222-222222222222/priorities')
  expect(calls[0].url).not.toContain('tenantId')
  expect(calls[0].init).toMatchObject({method:'GET',credentials:'same-origin',cache:'no-store'})
  expect(calls[0].init?.body).toBeUndefined()
 })
 it('preserves missing rate as NULL instead of zero',()=>{
  const v={...sample,stockState:'NO_OBSERVATION',
   demandStatus:'INCOMPLETE_COVERAGE',evidenceLevel:'STOCK_UNTRUSTED',
   riskCode:'STOCK_NOT_OBSERVED',priorityCode:'BLOCKED',
   observationId:null,fulfillable:null,observedAt:null,
   observedUnits28d:null,observedDailyUnits28d:null,
   illustrativeCoverageDays:null,illustrativePolicyLeadUnits:null,
   stockSourceMode:null,demandSourceMode:null,
   readySupplierQuoteCount:0,shortestSupplierTermDays:null,
   longestSupplierTermDays:null}
  const parsed=prioritySchema.parse(v)
  expect(parsed.fulfillable).toBeNull()
  expect(parsed.illustrativeCoverageDays).toBeNull()
 })
 it('rejects invented carrier-verified signals, negative quantities and bad priority enums',()=>{
  expect(prioritySchema.safeParse({...sample,stockSourceMode:'CARRIER'}).success).toBe(false)
  expect(prioritySchema.safeParse({...sample,localApprovedUnreceivedUnits:-1}).success).toBe(false)
  expect(prioritySchema.safeParse({...sample,priorityCode:'AUTO_BUY'}).success).toBe(false)
 })
 it('fails closed when Owner Session is forbidden',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response('',{status:403})))
  await expect(getReplenishmentPriorities('22222222-2222-4222-8222-222222222222'))
    .rejects.toThrow('权限')
 })
})
