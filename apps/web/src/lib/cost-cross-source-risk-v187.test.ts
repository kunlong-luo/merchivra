import {afterEach,describe,expect,it,vi} from 'vitest'
import {riskReport,fetchCostCrossSourceRiskV187}
 from './cost-cross-source-risk-v187'

const mp='11111111-1111-4111-8111-111111111111'
const sample='22222222-2222-4222-8222-222222222222'
const base={
 marketplaceId:mp,periodStart:'2026-11-01',periodEndExclusive:'2026-12-01',
 dateBasis:'V91_SOURCE_BUSINESS_DATE_NOT_ACCOUNTING_RECOGNITION_DATE',
 sourceCount:3,potentialIdentityOverlapSourceCount:2,
 sharedSettlementLineSourceCount:1,
 anyLocalCollisionSourceCount:2,noLocalMatchSourceCount:1,
 examples:[{sourceId:sample,businessDate:'2026-11-09',
  localSignalCodes:['OTHER_MANUAL_COST_SAME_CLAIMED_DOCUMENT'],
  sharedSettlementLineClaim:true,
  status:'POTENTIAL_LOCAL_COLLISION_HOLD_UNVERIFIED',
  duplicateDebitVerified:false,expenseDeductionAllowed:false}],
 examplesTruncated:false,
 blockerCodes:['INDEPENDENT_EXTERNAL_EXPENSE_AUTHENTICITY_MISSING',
  'NO_VERIFIED_DUPLICATE_DEBIT_ADJUDICATION',
  'REFUND_COGS_REVERSAL_REQUIRES_RECEIPT_AND_G1_POLICY',
  'DEC_08_FX_POLICY_NOT_APPROVED',
  'BANK_LEDGER_AND_V162_V64_NET_PROFIT_UNVERIFIED',
  'V165_CROSS_SOURCE_IDENTITY_OVERLAP_HOLD'],
 duplicateDebitVerified:false,externalChargeVerified:false,
 refundCogsReversalVerified:false,accountingPolicyApproved:false,
 bankReconciled:false,netProfitEligible:false,realizedNetProfit:null
}
afterEach(()=>vi.unstubAllGlobals())
describe('V187 full-period local conflict exposure remains unverified',()=>{
 it('presents risk as local hypotheses, never verified duplicate debits',()=>{
  const data=riskReport.parse(base)
  expect(data.anyLocalCollisionSourceCount).toBe(2)
  expect(data.realizedNetProfit).toBeNull()
  expect(data.examples[0].status).toBe('POTENTIAL_LOCAL_COLLISION_HOLD_UNVERIFIED')
 })
 it.each(['duplicateDebitVerified','externalChargeVerified',
  'refundCogsReversalVerified','accountingPolicyApproved',
  'bankReconciled','netProfitEligible'])(
  'forbids promoted finance trust flag %s',key=>{
   expect(()=>riskReport.parse({...base,[key]:true})).toThrow()
  })
 it.each(['duplicateDebitVerified','expenseDeductionAllowed'])(
  'forbids marking a sample as a real debit %s',key=>{
   expect(()=>riskReport.parse({...base,examples:[{
    ...base.examples[0],[key]:true}]})).toThrow()
  })
 it('forbids fake profit, unbounded evidence examples and false status',()=>{
  expect(()=>riskReport.parse({...base,realizedNetProfit:'1.000000'})).toThrow()
  expect(()=>riskReport.parse({...base,examples:Array.from({length:21},
   (_,i)=>({...base.examples[0],sourceId:sample,
    businessDate:'2026-11-09',localSignalCodes:[String(i)]}))})).toThrow()
  expect(()=>riskReport.parse({...base,examples:[{
   ...base.examples[0],status:'CONFIRMED_DUPLICATE'}]})).toThrow()
 })
 it('uses existing Owner Session scope and full-period no-store GET',async()=>{
  const request=vi.fn(async (_url:RequestInfo|URL,_opts?:RequestInit)=>
   new Response(JSON.stringify(base),{status:200}))
  vi.stubGlobal('fetch',request)
  const result=await fetchCostCrossSourceRiskV187(mp,'2026-11-01','2026-12-01')
  expect(result.sourceCount).toBe(3)
  expect(request).toHaveBeenCalledOnce()
  const [url,opts]=request.mock.calls[0]
  expect(url).toBe('/api/v1/finance/marketplaces/'+mp+
   '/cost-cross-source-risk?periodStart=2026-11-01&'+
   'periodEndExclusive=2026-12-01')
  expect(opts).toMatchObject({method:'GET',credentials:'same-origin',cache:'no-store'})
 })
 it('never interprets scoped 404 as zero expense or zero duplicate risk',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response('',{status:404})))
  await expect(fetchCostCrossSourceRiskV187(
   mp,'2026-11-01','2026-12-01')).rejects.toThrow('404')
 })
})
