import {z} from 'zod'
import {getManualCostExternalProofDossierV230} from '../generated/manual-cost-v228'

/** V230 read-only proof-gap review. Every source is MANUAL_UNVERIFIED;
 * no user-uploaded digest, local match, Owner review or mock may promote it.
 */
const uid=z.string().uuid()
const requirement=z.object({
 code:z.string().min(1),
 description:z.string().min(1),
 independentlyVerified:z.literal(false)
})
export const externalProofDossierV230=z.object({
 sourceId:uid,marketplaceId:uid,category:z.enum([
  'ADS_SPEND','INBOUND_FREIGHT','FBA_STORAGE','FBM_SHIPPING',
  'RETURN_HANDLING','IMPORT_DUTY','PAYMENT_AND_FX','OTHER_OPERATING']),
 originalCurrency:z.string().regex(/^[A-Z]{3}$/),
 sourceReviewState:z.enum(['STAGED','REVIEWED_UNVERIFIED','REJECTED']),
 localAuditStatus:z.enum(['LOCAL_AUDIT_COMPLETE','LOCAL_AUDIT_INCONSISTENT']),
 localAttributionStatus:z.enum(['CONFLICT_EVIDENCE',
  'SUPPORTING_LOCAL_METADATA_UNVERIFIED','UNPROVEN']),
 localCollisionHeld:z.boolean(),
 localSourceAuditComplete:z.boolean(),
 independentMetadataReviewComplete:z.boolean(),
 localDossierStatus:z.enum([
  'LOCAL_AUDIT_INCONSISTENT_HOLD','LOCAL_SOURCE_REJECTED',
  'LOCAL_METADATA_REVIEW_PENDING','LOCAL_CROSS_SOURCE_CONFLICT_HOLD',
  'LOCAL_REVIEW_COMPLETE_EXTERNAL_UNVERIFIED']),
 blockerCodes:z.array(z.string()),
 requiredExternalProof:z.array(requirement).min(5),
 originalBytesVerified:z.literal(false),
 issuerIdentityVerified:z.literal(false),
 originalChargeVerified:z.literal(false),
 duplicateDebitExcluded:z.literal(false),
 settlementOrPaymentReconciled:z.literal(false),
 actualLandedOrRefundCogsVerified:z.literal(false),
 accountingPolicyApproved:z.literal(false),
 eligibleForRealizedProfit:z.literal(false),
 realizedNetProfit:z.null()
}).superRefine((d,ctx)=>{
 if((d.localAuditStatus==='LOCAL_AUDIT_COMPLETE')!==d.localSourceAuditComplete)
  ctx.addIssue({code:'custom',message:'来源审计布尔值与审计状态不一致'})
 if(d.independentMetadataReviewComplete &&
  (!d.localSourceAuditComplete||d.sourceReviewState!=='REVIEWED_UNVERIFIED'))
  ctx.addIssue({code:'custom',message:'本地独立审核不能绕开来源审计或拒绝状态'})
 if(d.requiredExternalProof.some(r=>!d.blockerCodes.includes(r.code)))
  ctx.addIssue({code:'custom',message:'原始外部凭证缺项与阻断代码不一致'})
 if(d.localDossierStatus==='LOCAL_REVIEW_COMPLETE_EXTERNAL_UNVERIFIED' &&
  (!d.localSourceAuditComplete||!d.independentMetadataReviewComplete||
   d.localCollisionHeld||d.sourceReviewState!=='REVIEWED_UNVERIFIED'))
  ctx.addIssue({code:'custom',message:'本地审核显示与来源审计/冲突证据矛盾'})
 if(d.localDossierStatus==='LOCAL_AUDIT_INCONSISTENT_HOLD' &&
  d.localSourceAuditComplete)
  ctx.addIssue({code:'custom',message:'审计状态不一致'})
})
export type ExternalProofDossierV230=z.infer<typeof externalProofDossierV230>

/** Existing Orval-generated same-origin no-cache read uses shared mutator. */
export async function fetchExternalProofDossierV230(
 marketplaceId:string,sourceId:string,expectedCurrency:string
):Promise<ExternalProofDossierV230>{
 uid.parse(marketplaceId)
 uid.parse(sourceId)
 if(!/^[A-Z]{3}$/.test(expectedCurrency))
  throw Error('Marketplace 原币不可用，禁止推断费用币种')
 const response=await getManualCostExternalProofDossierV230(
  marketplaceId,sourceId)
 if(response.status!==200||
  !response.headers.get('Cache-Control')?.includes('no-store'))
  throw Error('来源证据报告没有提供可验证的安全 no-store 响应')
 const dossier=externalProofDossierV230.parse(response.data)
 if(dossier.sourceId!==sourceId||dossier.marketplaceId!==marketplaceId||
  dossier.originalCurrency!==expectedCurrency)
  throw Error('服务端来源证据与当前 Marketplace 原币/身份不匹配')
 return dossier
}
