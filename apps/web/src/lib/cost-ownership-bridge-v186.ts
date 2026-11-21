import {z} from 'zod'

/**
 * V186 full-period original currency V91/V92 cost-ownership diagnostics.
 * No report may claim recognized expenses, refund reversal or true net profit.
 */
const exactMoney=z.string().regex(/^[0-9]+\.[0-9]{6}$/)
const bucket=z.object({
 category:z.string(),currency:z.string().regex(/^[A-Z]{3}$/),
 primaryDimension:z.enum(['SKU','ORDER','PERIOD','UNASSIGNED']),
 sourceCount:z.number().int().positive(),
 approvedUnverifiedSourceCount:z.number().int().nonnegative(),
 pendingSourceCount:z.number().int().nonnegative(),
 unassignedSourceCount:z.number().int().nonnegative(),
 approvedMissingAuditSourceCount:z.number().int().nonnegative(),
 sourceClaimedAmount:exactMoney,
 approvedUnverifiedAllocatedAmount:exactMoney.nullable(),
 approvedUnverifiedRemainderAmount:exactMoney.nullable(),
 derivativeSkuOrderPeriodProfitDeductionsAllowed:z.literal(false),
 externalChargeVerified:z.literal(false),netProfitEligible:z.literal(false)
})
export const bridge=z.object({
 marketplaceId:z.string().uuid(),periodStart:z.iso.date(),
 periodEndExclusive:z.iso.date(),
 sourceDateBasis:z.literal(
  'V91_SOURCE_BUSINESS_DATE_NOT_ACCOUNTING_RECOGNITION_DATE'),
 sourceCount:z.number().int().nonnegative(),
 buckets:z.array(bucket),
 categoriesWithoutSourceClaims:z.array(z.string()),
 blockerCodes:z.array(z.string()).min(6),
 doubleCountingAcrossDimensionsAllowed:z.literal(false),
 externalChargeVerified:z.literal(false),
 refundCogsReversalVerified:z.literal(false),
 fxConversionApproved:z.literal(false),bankReconciled:z.literal(false),
 ledgerPosted:z.literal(false),netProfitEligible:z.literal(false),
 realizedNetProfit:z.null()
})
export type CostOwnershipBridge=z.infer<typeof bridge>

export async function fetchCostOwnershipBridgeV186(marketplaceId:string,
 start:string,endExclusive:string):Promise<CostOwnershipBridge>{
 const q=new URLSearchParams({periodStart:start,periodEndExclusive:endExclusive})
 const r=await fetch('/api/v1/finance/marketplaces/'+
  encodeURIComponent(marketplaceId)+'/cost-ownership-bridge?'+q.toString(),{
  credentials:'same-origin',method:'GET',cache:'no-store',
  headers:{Accept:'application/json'}
 })
 if(!r.ok)throw new Error('单一来源成本归属桥接 HTTP '+r.status)
 return bridge.parse(await r.json())
}
