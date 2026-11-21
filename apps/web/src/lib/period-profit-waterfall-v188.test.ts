import {afterEach,describe,expect,it,vi} from 'vitest'
import {getPeriodProfitWaterfallV188,periodProfitWaterfallV188}
 from './period-profit-waterfall-v188'

const MP='11111111-1111-4111-8111-111111111111'
const JOB='22222222-2222-4222-8222-222222222222'
const source={
 marketplaceId:MP,reportJobId:JOB,
 periodStart:'2026-11-01',periodEndExclusive:'2026-12-01',
 currency:'USD',
 ruleVersion:'V188_V64_ELIGIBLE_CONTRIBUTION_MINUS_V92_APPROVED_UNVERIFIED_ORIGINAL_CURRENCY_V1',
 dateBasis:'V64_ORDER_UTC_V91_COST_SOURCE_BUSINESS_DATE',
 sourceSnapshotBasis:'V64_IMMUTABLE_ORDER_EXTRACTS_V91_V92_V165_V168_LIVE_READ',
 jobState:'SUCCEEDED',totalOrders:1,eligibleOrders:1,blockedOrders:0,
 reviewedCostSourceCount:1,potentialCollisionSourceCount:0,
 sharedSettlementLineSourceCount:0,
 eligibleSignedSales:'100.000000',eligibleSignedAmazonFees:'-10.000000',
 eligibleSignedFbaFees:'-5.000000',eligibleProvisionalCogs:'40.000000',
 eligibleContribution:'45.000000',
 approvedUnverifiedLocalCostExposure:'12.000001',
 unverifiedCostSensitivityContribution:'32.999999',
 sensitivityState:'UNVERIFIED_COST_SENSITIVITY_ONLY',
 costLines:[{category:'INBOUND_FREIGHT',currency:'USD',primaryDimension:'PERIOD',
  sourceCount:1,approvedUnverifiedSourceCount:1,pendingSourceCount:0,
  unassignedSourceCount:0,missingApprovalAuditCount:0,
  claimedAmount:'12.000001',
  approvedUnverifiedAllocatedAmount:'12.000001',
  approvedUnverifiedRemainderAmount:'0.000000'}],
 categoriesWithoutSourceClaims:['ADS_SPEND'],
 blockerCodes:[
  'V64_ORDER_UTC_V91_SOURCE_BUSINESS_DATE_AXES_DIFFER',
  'V64_BATCH_FROZEN_V91_V92_V165_V168_EVIDENCE_LIVE',
  'MANUAL_V91_V92_COSTS_NOT_INDEPENDENTLY_EXTERNALLY_ATTESTED',
  'G1_DEC_06_07_08_POLICY_NOT_APPROVED',
  'REFUND_COGS_REVERSAL_NOT_VERIFIED',
  'BANK_SETTLEMENT_AND_LEDGER_NOT_RECONCILED',
  'V162_V64_REALIZED_NET_PROFIT_NOT_AVAILABLE',
  'NO_DOUBLE_DEDUCTION_FROM_EXISTING_SIGNED_AMAZON_FBA_FEES'
 ],
 financeFeesExcludedFromAdditionalCosts:true,
 additionalCostExternallyVerified:false,
 accountingPolicyApproved:false,refundCogsReversalVerified:false,
 fxConversionApproved:false,bankSettlementVerified:false,
 ledgerPosted:false,realizedNetProfitVerified:false,realizedNetProfit:null
}
afterEach(()=>vi.unstubAllGlobals())
describe('V188 V64 to V92 original-currency candidate cost waterfall',()=>{
 it('accepts signed fees and 1e-6 sensitivity without calling it net profit',()=>{
  const p=periodProfitWaterfallV188.parse(source)
  expect(p.eligibleSignedAmazonFees).toBe('-10.000000')
  expect(p.unverifiedCostSensitivityContribution).toBe('32.999999')
  expect(p.realizedNetProfit).toBeNull()
 })
 it.each(['additionalCostExternallyVerified','accountingPolicyApproved',
  'refundCogsReversalVerified','fxConversionApproved',
  'bankSettlementVerified','ledgerPosted','realizedNetProfitVerified'])(
  'never trusts an upgraded external proof %s',key=>{
   expect(()=>periodProfitWaterfallV188.parse({...source,[key]:true})).toThrow()
  })
 it('always excludes existing platform fees from additional expenses',()=>{
  expect(()=>periodProfitWaterfallV188.parse({
   ...source,financeFeesExcludedFromAdditionalCosts:false})).toThrow()
 })
 it('blocks fabricated recognized net profit, loose money and duplicate source counts',()=>{
  expect(()=>periodProfitWaterfallV188.parse({
   ...source,realizedNetProfit:'32.999999'})).toThrow()
  expect(()=>periodProfitWaterfallV188.parse({
   ...source,approvedUnverifiedLocalCostExposure:'12.0000011'})).toThrow()
  expect(()=>periodProfitWaterfallV188.parse({
   ...source,reviewedCostSourceCount:2})).toThrow()
  expect(()=>periodProfitWaterfallV188.parse({
   ...source,sensitivityState:'CROSS_SOURCE_COLLISION_BLOCKED'})).toThrow()
 })
 it('accepts not-calculable incomplete order/FX/dup source status with null amount',()=>{
  const p=periodProfitWaterfallV188.parse({
   ...source,sensitivityState:'MIXED_ORIGINAL_CURRENCY_BLOCKED',
   approvedUnverifiedLocalCostExposure:null,
   unverifiedCostSensitivityContribution:null})
  expect(p.unverifiedCostSensitivityContribution).toBeNull()
 })
 it('GET uses tenant-scoped same-origin Owner Session and no-store',async()=>{
  const request=vi.fn(async (_url:RequestInfo|URL,_opts?:RequestInit)=>
   new Response(JSON.stringify(source),{status:200}))
  vi.stubGlobal('fetch',request)
  const p=await getPeriodProfitWaterfallV188(MP,JOB)
  expect(p.jobState).toBe('SUCCEEDED')
  expect(request).toHaveBeenCalledOnce()
  const [url,opts]=request.mock.calls[0]
  expect(url).toBe('/api/v1/finance/marketplaces/'+MP+
   '/profit-reports/'+JOB+'/cost-waterfall-preview')
  expect(opts).toMatchObject({method:'GET',credentials:'same-origin',cache:'no-store'})
 })
 it('never interprets 401/404 or failed evidence as a computed zero',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response('',{status:404})))
  await expect(getPeriodProfitWaterfallV188(MP,JOB)).rejects.toThrow('404')
 })
})
