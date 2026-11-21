import {afterEach,describe,expect,it,vi} from 'vitest'
import {
 projection,fetchPeriodAllocationPlans,fetchPeriodProjection
} from './period-cost-accrual-v185'

const mp='11111111-1111-4111-8111-111111111111'
const source='22222222-2222-4222-8222-222222222222'
const plan='33333333-3333-4333-8333-333333333333'
const result={
 sourceId:source,allocationId:plan,allocationState:'APPROVED_UNVERIFIED',
 ruleVersion:'ILLUSTRATIVE_DAILY_STRAIGHT_LINE_MICRO_UNITS_V1',
 currency:'USD',sourceAmount:'12.000001',allocatedAmount:'10.000001',
 unallocatedAmount:'2.000000',projectedAmount:'10.000001',
 months:[
  {period:'2026-11',currency:'USD',projectedAmount:'5.000001'},
  {period:'2026-12',currency:'USD',projectedAmount:'5.000000'}
 ],
 overlappingServiceDays:false,blockerCodes:[
  'EXTERNAL_EXPENSE_AUTHENTICITY_NOT_VERIFIED',
  'G1_DAILY_PERIODIZATION_POLICY_NOT_APPROVED',
  'DEC_08_FX_CONVERSION_POLICY_NOT_APPROVED',
  'BANK_RECONCILIATION_INCOMPLETE'
 ],
 externalSourceVerified:false,accountingPolicyApproved:false,
 currencyConverted:false,refundCogsReversalVerified:false,
 ledgerPosted:false,netProfitEligible:false,realizedNetProfit:null
}
afterEach(()=>vi.unstubAllGlobals())

describe('V185 original-currency period preview strict trust boundary',()=>{
 it('accepts an explicitly unverified month-by-month projection',()=>{
  expect(projection.parse(result).months).toHaveLength(2)
  expect(projection.parse(result).netProfitEligible).toBe(false)
 })
 it.each(['externalSourceVerified','accountingPolicyApproved',
  'currencyConverted','refundCogsReversalVerified',
  'ledgerPosted','netProfitEligible'])(
  'rejects any elevated finance claim %s',key=>{
   expect(()=>projection.parse({...result,[key]:true})).toThrow()
  })
 it('rejects net profit amount, unknown rule and floating point precision',()=>{
  expect(()=>projection.parse({...result,realizedNetProfit:'10.00'})).toThrow()
  expect(()=>projection.parse({...result,ruleVersion:'ACCOUNTING_VERIFIED'})).toThrow()
  expect(()=>projection.parse({...result,projectedAmount:'10.0000001'})).toThrow()
  expect(()=>projection.parse({...result,months:[{period:'2026-13',
   currency:'USD',projectedAmount:'10.000001'}]})).toThrow()
 })
 it('only requests the owner-session scoped read-only allocation list',async()=>{
  const request=vi.fn(async()=>new Response(JSON.stringify([
   {id:plan,targetKind:'PERIOD',state:'APPROVED_UNVERIFIED'}
  ]),{status:200}))
  vi.stubGlobal('fetch',request)
  expect((await fetchPeriodAllocationPlans(mp,source))[0].id).toBe(plan)
  expect(request).toHaveBeenCalledOnce()
  const [url,opts]=request.mock.calls[0]
  expect(url).toBe('/api/v1/finance/marketplaces/'+mp+
   '/cost-sources/'+source+'/allocations')
  expect(opts).toMatchObject({method:'GET',credentials:'same-origin',cache:'no-store'})
 })
 it('only requests scoped V92 plan ID and validates external authenticity flags',async()=>{
  const request=vi.fn(async()=>new Response(JSON.stringify(result),{status:200}))
  vi.stubGlobal('fetch',request)
  const x=await fetchPeriodProjection(mp,source,plan)
  expect(x.projectedAmount).toBe('10.000001')
  const [url,opts]=request.mock.calls[0]
  expect(url).toBe('/api/v1/finance/marketplaces/'+mp+
   '/cost-sources/'+source+'/allocations/'+plan+'/periodization-preview')
  expect(opts).toMatchObject({method:'GET',credentials:'same-origin',cache:'no-store'})
 })
 it('never substitutes a 404 source/plan for a zero expense',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response('',{status:404})))
  await expect(fetchPeriodProjection(mp,source,plan)).rejects.toThrow('不存在')
 })
})
