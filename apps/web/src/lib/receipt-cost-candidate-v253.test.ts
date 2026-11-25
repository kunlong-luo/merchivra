import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchReceiptCostCandidateV253,receiptCostCandidateV253} from './receipt-cost-candidate-v253'
const mp='11111111-1111-4111-8111-111111111111',receipt='22222222-2222-4222-8222-222222222222'
const a='33333333-3333-4333-8333-333333333333',b='44444444-4444-4444-8444-444444444444'
const component=(category:string,sourceId:string|null,total:string,sold:string,rest:string)=>({
 category,sourceId,originalCurrency:'USD',basisQuantity:3,allocatedQuantity:2,unallocatedQuantity:1,
 receiptCandidateAmount:total,allocatedCandidateAmount:sold,unallocatedCandidateAmount:rest
})
const ready={
 marketplaceId:mp,purchaseReceiptId:receipt,state:'LOCAL_PO_FREIGHT_DUTY_CANDIDATE_UNVERIFIED',
 originalCurrency:'USD',receiptCandidateCost:'45.000000',allocatedCandidateCost:'30.000000',unallocatedCandidateCost:'15.000000',
 components:[component('PURCHASE_PO_REFERENCE',null,'30.000000','20.000000','10.000000'),
  component('INBOUND_FREIGHT',a,'10.000000','6.666667','3.333333'),
  component('IMPORT_DUTY',b,'5.000000','3.333333','1.666667')],
 saleShares:[{claimId:a,candidateCost:'14.999999'},{claimId:b,candidateCost:'15.000001'}],
 blockerCodes:['EXTERNAL_COST_COVERAGE_UNPROVEN','QC_DENOMINATOR_AND_G1_UNAPPROVED'],
 completeExternalCostCoverage:false,actualBatchCogsVerified:false,actualBatchCogs:null,postingAllowed:false,realizedNetProfit:null
}
const hold={...ready,state:'LOCAL_COST_COMPOSITION_HOLD',originalCurrency:null,receiptCandidateCost:null,
 allocatedCandidateCost:null,unallocatedCandidateCost:null,components:[],saleShares:[],
 blockerCodes:['FREIGHT_OR_DUTY_EVIDENCE_UNKNOWN_NOT_ZERO']}
afterEach(()=>vi.unstubAllGlobals())
describe('V253 guarded receipt candidate via generated Orval',()=>{
 it('accepts exact source and sale conservation or unknown null',()=>{
  expect(receiptCostCandidateV253.parse(ready).receiptCandidateCost).toBe('45.000000')
  expect(receiptCostCandidateV253.parse(hold).receiptCandidateCost).toBeNull()
 })
 it('rejects false profit, partial hold, duplicate sources and micro-unit drift',()=>{
  for(const broken of [
   {...ready,actualBatchCogs:'30.000000'},{...ready,postingAllowed:true},
   {...hold,receiptCandidateCost:'0.000000'},{...ready,realizedNetProfit:'1.000000'},
   {...ready,components:[...ready.components,ready.components[1]]},
   {...ready,allocatedCandidateCost:'30.000001'},
   {...ready,saleShares:[ready.saleShares[0],ready.saleShares[0]]},
   {...ready,components:[{...ready.components[0],originalCurrency:'EUR'},...ready.components.slice(1)]},
   {...ready,saleShares:[{...ready.saleShares[0],candidateCost:'garbage'},ready.saleShares[1]]}
  ])expect(receiptCostCandidateV253.safeParse(broken).success).toBe(false)
 })
 it('uses generated same-origin no-store GET with cancellation and no tenant parameter',async()=>{
  const signal=new AbortController().signal
  const fetcher=vi.fn(async (_url:RequestInfo|URL,_options?:RequestInit)=>new Response(
   JSON.stringify(ready),{status:200,headers:{'Content-Type':'application/json','Cache-Control':'no-store'}}))
  vi.stubGlobal('fetch',fetcher)
  expect((await fetchReceiptCostCandidateV253(mp,receipt,signal)).saleShares).toHaveLength(2)
  const [url,opts]=fetcher.mock.calls[0]
  expect(url).toBe('/api/v1/finance/marketplaces/'+mp+'/purchase-receipts/'+receipt+'/cost-composition-candidate')
  expect(opts).toMatchObject({method:'GET',credentials:'same-origin',cache:'no-store',signal})
 })
 it('rejects foreign scope and cached responses',async()=>{
  for(const [data,cache] of [[{...ready,purchaseReceiptId:a},'no-store'],[ready,'max-age=60']] as const){
   vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify(data),{
    status:200,headers:{'Content-Type':'application/json','Cache-Control':cache}})))
   await expect(fetchReceiptCostCandidateV253(mp,receipt)).rejects.toThrow()
  }
 })
})
