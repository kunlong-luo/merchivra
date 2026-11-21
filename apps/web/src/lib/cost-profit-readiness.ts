import {z} from 'zod'

const bucket=z.object({
 category:z.string(),currency:z.string(),sourceCount:z.number().int(),
 independentlyReviewedMetadataCount:z.number().int(),
 claimedAmount:z.string(),reviewedUnverifiedAmount:z.string(),
 approvedUnverifiedAllocationAmount:z.string(),
 approvedUnverifiedUnallocatedAmount:z.string(),
 sharedLineSourceCount:z.number().int(),
 externallyVerified:z.literal(false),allowedToDeductFromProfit:z.literal(false)
})
const source=z.object({
 sourceId:z.string().uuid(),businessDate:z.string(),category:z.string(),
 currency:z.string(),claimedAmount:z.string(),metadataReviewState:z.string(),
 activeAllocationState:z.string().nullable(),
 approvedUnverifiedAllocationAmount:z.string().nullable(),
 approvedUnverifiedUnallocatedAmount:z.string().nullable(),
 localIdentityStatus:z.string(),approvalHeld:z.boolean(),
 linkedSettlementLineCount:z.number().int(),sharedSettlementLineCount:z.number().int(),
 localAttributionStatus:z.enum(['CONFLICT_EVIDENCE',
  'SUPPORTING_LOCAL_METADATA_UNVERIFIED','UNPROVEN']),
 localAttributionReasonCodes:z.array(z.string()),
 frozenIdentityCount:z.number().int().nonnegative(),
 missingCaptureCount:z.number().int().nonnegative(),
 independentReviewAuditComplete:z.boolean(),
 externallyVerified:z.literal(false),allowedToDeductFromProfit:z.literal(false)
})
const evidenceBarriers=z.object({
 sourceCount:z.number().int().nonnegative(),
 metadataReviewPendingSourceCount:z.number().int().nonnegative(),
 noSettlementAssociationSourceCount:z.number().int().nonnegative(),
 missingFrozenCaptureSourceCount:z.number().int().nonnegative(),
 sharedSettlementLineSourceCount:z.number().int().nonnegative(),
 pendingLineReviewSourceCount:z.number().int().nonnegative(),
 missingLineAuditSourceCount:z.number().int().nonnegative(),
 multipleLineClaimsSourceCount:z.number().int().nonnegative(),
 externallyUnverifiedSourceCount:z.number().int().nonnegative(),
 blockerCodes:z.array(z.string()),
 eligibleForV162OrV64Profit:z.literal(false)
})
const page=z.object({
 marketplaceId:z.string().uuid(),periodStart:z.string(),
 periodEndExclusive:z.string(),buckets:z.array(bucket),sources:z.array(source),
 hasMore:z.boolean(),nextBeforeDate:z.string().nullable(),
 nextBeforeId:z.string().uuid().nullable(),
 evidenceBarriers,
 categoriesWithoutVerifiedExternalCoverage:z.array(z.string()),
 fullCostCovered:z.literal(false),bankReconciled:z.literal(false),
 realizedNetProfitVerified:z.literal(false),realizedNetProfit:z.null()
})
export type CostReadinessPage=z.infer<typeof page>
export type CostReadinessCursor={beforeDate:string;beforeId:string}

export async function fetchCostProfitReadiness(marketplaceId:string,
 start:string,endExclusive:string,cursor?:CostReadinessCursor){
 const query=new URLSearchParams({
  periodStart:start,periodEndExclusive:endExclusive
 })
 if(cursor){query.set('beforeDate',cursor.beforeDate);query.set('beforeId',cursor.beforeId)}
 const r=await fetch('/api/v1/finance/marketplaces/'+encodeURIComponent(marketplaceId)+
  '/cost-profit-readiness?'+query.toString(),
  {credentials:'same-origin',cache:'no-store',headers:{Accept:'application/json'}})
 if(!r.ok)throw Error('成本准备度接口 HTTP '+r.status)
 return page.parse(await r.json())
}
