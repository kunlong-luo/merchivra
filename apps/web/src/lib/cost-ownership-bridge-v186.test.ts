import {afterEach,describe,expect,it,vi} from 'vitest'
import {bridge,fetchCostOwnershipBridgeV186} from './cost-ownership-bridge-v186'

const mp='11111111-1111-4111-8111-111111111111'
const good={
 marketplaceId:mp,periodStart:'2026-11-01',
 periodEndExclusive:'2026-12-01',
 sourceDateBasis:'V91_SOURCE_BUSINESS_DATE_NOT_ACCOUNTING_RECOGNITION_DATE',
 sourceCount:2,
 buckets:[{
  category:'INBOUND_FREIGHT',currency:'USD',primaryDimension:'PERIOD',
  sourceCount:2,approvedUnverifiedSourceCount:1,pendingSourceCount:1,
  unassignedSourceCount:0,approvedMissingAuditSourceCount:0,
  sourceClaimedAmount:'22.000001',
  approvedUnverifiedAllocatedAmount:'7.250000',
  approvedUnverifiedRemainderAmount:'2.750000',
  derivativeSkuOrderPeriodProfitDeductionsAllowed:false,
  externalChargeVerified:false,netProfitEligible:false
 }],
 categoriesWithoutSourceClaims:['ADS_SPEND'],
 blockerCodes:[
  'INDEPENDENT_EXTERNAL_COST_SOURCE_ATTESTATION_MISSING',
  'G1_MULTI_DIMENSION_COST_OWNERSHIP_POLICY_NOT_APPROVED',
  'REFUND_COGS_REVERSAL_NEEDS_PHYSICAL_RECEIPT_AND_FINANCIAL_POLICY',
  'DEC_08_FX_CONVERSION_NOT_APPROVED',
  'BANK_RECONCILIATION_AND_LEDGER_NOT_VERIFIED',
  'V162_V64_RECOGNIZED_PROFIT_NOT_AVAILABLE'
 ],
 doubleCountingAcrossDimensionsAllowed:false,
 externalChargeVerified:false,refundCogsReversalVerified:false,
 fxConversionApproved:false,bankReconciled:false,ledgerPosted:false,
 netProfitEligible:false,realizedNetProfit:null
}

afterEach(()=>vi.unstubAllGlobals())

describe('V186 one source one primary cost dimension fail-closed UI',()=>{
 it('accepts only source-level unverified amounts and null recognized profit',()=>{
  const x=bridge.parse(good)
  expect(x.buckets[0].primaryDimension).toBe('PERIOD')
  expect(x.buckets[0].approvedUnverifiedAllocatedAmount).toBe('7.250000')
  expect(x.realizedNetProfit).toBeNull()
 })
 it.each(['doubleCountingAcrossDimensionsAllowed','externalChargeVerified',
  'refundCogsReversalVerified','fxConversionApproved','bankReconciled',
  'ledgerPosted','netProfitEligible'])(
  'does not accept false-to-true privilege escalation %s',key=>{
   expect(()=>bridge.parse({...good,[key]:true})).toThrow()
  })
 it.each(['derivativeSkuOrderPeriodProfitDeductionsAllowed',
  'externalChargeVerified','netProfitEligible'])(
  'forbids bucket claiming secondary recognized expense %s',key=>{
   expect(()=>bridge.parse({...good,buckets:[{
    ...good.buckets[0],[key]:true}]})).toThrow()
  })
 it('does not accept fabricated net profit or floating money',()=>{
  expect(()=>bridge.parse({...good,realizedNetProfit:'0.000000'})).toThrow()
  expect(()=>bridge.parse({...good,buckets:[{
   ...good.buckets[0],sourceClaimedAmount:'22.0000011'}]})).toThrow()
  expect(()=>bridge.parse({...good,sourceDateBasis:'RECOGNIZED_PROFIT'})).toThrow()
 })
 it('uses scoped authenticated no-cache GET across entire requested period',async()=>{
  const fetcher=vi.fn(async (_url:RequestInfo|URL,_opts?:RequestInit)=>
   new Response(JSON.stringify(good),{status:200}))
  vi.stubGlobal('fetch',fetcher)
  const out=await fetchCostOwnershipBridgeV186(
   mp,'2026-11-01','2026-12-01')
  expect(out.sourceCount).toBe(2)
  expect(fetcher).toHaveBeenCalledOnce()
  const [url,opts]=fetcher.mock.calls[0]
  expect(url).toBe('/api/v1/finance/marketplaces/'+mp+
   '/cost-ownership-bridge?periodStart=2026-11-01&'+
   'periodEndExclusive=2026-12-01')
  expect(opts).toMatchObject({method:'GET',cache:'no-store',
   credentials:'same-origin'})
 })
 it('never interprets HTTP 404/403 or unavailable source as zero cost',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response('',{status:404})))
  await expect(fetchCostOwnershipBridgeV186(
   mp,'2026-11-01','2026-12-01')).rejects.toThrow('404')
 })
})
