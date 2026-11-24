import {beforeEach,describe,expect,it,vi} from 'vitest'
import {getManualCostExternalProofDossierV230} from '../generated/manual-cost-v228'
import {fetchExternalProofDossierV230} from './manual-cost-proof-v230'

vi.mock('../generated/manual-cost-v228',()=>({
 getManualCostExternalProofDossierV230:vi.fn()
}))
const MP='00000000-0000-4000-8000-000000000230'
const SOURCE='00000000-0000-4000-8000-000000000231'
const base={
 sourceId:SOURCE,marketplaceId:MP,category:'INBOUND_FREIGHT',
 originalCurrency:'USD',sourceReviewState:'REVIEWED_UNVERIFIED',
 localAuditStatus:'LOCAL_AUDIT_COMPLETE',
 localAttributionStatus:'UNPROVEN',localCollisionHeld:false,
 localSourceAuditComplete:true,independentMetadataReviewComplete:true,
 localDossierStatus:'LOCAL_REVIEW_COMPLETE_EXTERNAL_UNVERIFIED',
 blockerCodes:['INDEPENDENT_EXTERNAL_ISSUER_NOT_ATTESTED',
  'ORIGINAL_DOCUMENT_BYTES_AND_SHA256'],
 requiredExternalProof:[{code:'ORIGINAL_DOCUMENT_BYTES_AND_SHA256',
  description:'真实独立原文件重算',independentlyVerified:false},
  {code:'INDEPENDENT_EXTERNAL_ISSUER_NOT_ATTESTED',
   description:'外部签发方不可证',independentlyVerified:false},
  {code:'CARRIER_INVOICE_SHIPMENT_AND_RECEIPT',
   description:'货代真实运单',independentlyVerified:false},
  {code:'EXACT_CHARGE_ORIGINAL_CURRENCY_ALLOCATION',
   description:'原币与成本守恒',independentlyVerified:false},
  {code:'ACCOUNTING_POLICY_AND_G1_DEC_APPROVAL',
   description:'会计口径批准',independentlyVerified:false}],
 originalBytesVerified:false,issuerIdentityVerified:false,
 originalChargeVerified:false,duplicateDebitExcluded:false,
 settlementOrPaymentReconciled:false,actualLandedOrRefundCogsVerified:false,
 accountingPolicyApproved:false,eligibleForRealizedProfit:false,
 realizedNetProfit:null
}
const valid={...base,blockerCodes:[
 ...base.blockerCodes,'CARRIER_INVOICE_SHIPMENT_AND_RECEIPT',
 'EXACT_CHARGE_ORIGINAL_CURRENCY_ALLOCATION',
 'ACCOUNTING_POLICY_AND_G1_DEC_APPROVAL']}
function reply(data:unknown,opts:{cache?:string;status?:number}={}){
 return {data,status:opts.status??200,
  headers:new Headers(opts.cache===undefined?{'Cache-Control':'no-store'}:
    {'Cache-Control':opts.cache})}
}
describe('V230 guarded Orval source external proof dossier',()=>{
 beforeEach(()=>vi.clearAllMocks())
 it('accepts only complete unverified third-party proof gaps',async()=>{
  vi.mocked(getManualCostExternalProofDossierV230).mockResolvedValueOnce(
   reply(valid) as Awaited<ReturnType<typeof getManualCostExternalProofDossierV230>>)
  const result=await fetchExternalProofDossierV230(MP,SOURCE,'USD')
  expect(result.localDossierStatus).toBe('LOCAL_REVIEW_COMPLETE_EXTERNAL_UNVERIFIED')
  expect(result.eligibleForRealizedProfit).toBe(false)
  expect(result.realizedNetProfit).toBeNull()
  expect(getManualCostExternalProofDossierV230).toHaveBeenCalledWith(MP,SOURCE)
 })
 it('rejects untrusted external verified flags and numeric profit',async()=>{
  for(const corrupted of [
   {...valid,originalBytesVerified:true},
   {...valid,issuerIdentityVerified:true},
   {...valid,eligibleForRealizedProfit:true},
   {...valid,realizedNetProfit:'12.00'},
   {...valid,requiredExternalProof:valid.requiredExternalProof.map(x=>
    ({...x,independentlyVerified:true}))}
  ]){
   vi.mocked(getManualCostExternalProofDossierV230).mockResolvedValueOnce(
    reply(corrupted) as Awaited<ReturnType<typeof getManualCostExternalProofDossierV230>>)
   await expect(fetchExternalProofDossierV230(MP,SOURCE,'USD'))
    .rejects.toThrow()
  }
 })
 it('rejects cross-Marketplace, wrong currency and omitted requirements',async()=>{
  for(const data of [
   {...valid,marketplaceId:'00000000-0000-4000-8000-000000000232'},
   {...valid,originalCurrency:'EUR'},
   {...valid,blockerCodes:[]},
   {...valid,requiredExternalProof:[]},
   {...valid,localAuditStatus:'LOCAL_AUDIT_INCONSISTENT'},
   {...valid,localSourceAuditComplete:false}
  ]){
   vi.mocked(getManualCostExternalProofDossierV230).mockResolvedValueOnce(
    reply(data) as Awaited<ReturnType<typeof getManualCostExternalProofDossierV230>>)
   await expect(fetchExternalProofDossierV230(MP,SOURCE,'USD'))
    .rejects.toThrow()
  }
 })
 it('rejects non-no-store or malformed status and does not send tenant',async()=>{
  vi.mocked(getManualCostExternalProofDossierV230).mockResolvedValueOnce(
   reply(valid,{cache:'max-age=60'}) as Awaited<
    ReturnType<typeof getManualCostExternalProofDossierV230>>)
  await expect(fetchExternalProofDossierV230(MP,SOURCE,'USD'))
   .rejects.toThrow('no-store')
  vi.mocked(getManualCostExternalProofDossierV230).mockResolvedValueOnce(
   reply(valid,{status:202}) as Awaited<
    ReturnType<typeof getManualCostExternalProofDossierV230>>)
  await expect(fetchExternalProofDossierV230(MP,SOURCE,'USD'))
   .rejects.toThrow('no-store')
  await expect(fetchExternalProofDossierV230('bad',SOURCE,'USD')).rejects.toThrow()
  await expect(fetchExternalProofDossierV230(MP,SOURCE,'')).rejects.toThrow()
 })
})
