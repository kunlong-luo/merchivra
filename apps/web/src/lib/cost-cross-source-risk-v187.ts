import {z} from 'zod'

/**
 * V187 full-period read-only V165/V168 local risk diagnostics.
 * Never convert matching keys or imported settlement lines to a verified
 * duplicate bank debit, certified expense or refundable COGS.
 */
const riskExample=z.object({
 sourceId:z.string().uuid(),businessDate:z.iso.date(),
 localSignalCodes:z.array(z.string()),
 sharedSettlementLineClaim:z.boolean(),
 status:z.literal('POTENTIAL_LOCAL_COLLISION_HOLD_UNVERIFIED'),
 duplicateDebitVerified:z.literal(false),
 expenseDeductionAllowed:z.literal(false)
})
export const riskReport=z.object({
 marketplaceId:z.string().uuid(),
 periodStart:z.iso.date(),periodEndExclusive:z.iso.date(),
 dateBasis:z.literal('V91_SOURCE_BUSINESS_DATE_NOT_ACCOUNTING_RECOGNITION_DATE'),
 sourceCount:z.number().int().nonnegative(),
 potentialIdentityOverlapSourceCount:z.number().int().nonnegative(),
 sharedSettlementLineSourceCount:z.number().int().nonnegative(),
 anyLocalCollisionSourceCount:z.number().int().nonnegative(),
 noLocalMatchSourceCount:z.number().int().nonnegative(),
 examples:z.array(riskExample).max(20),
 examplesTruncated:z.boolean(),
 blockerCodes:z.array(z.string()).min(5),
 duplicateDebitVerified:z.literal(false),
 externalChargeVerified:z.literal(false),
 refundCogsReversalVerified:z.literal(false),
 accountingPolicyApproved:z.literal(false),
 bankReconciled:z.literal(false),
 netProfitEligible:z.literal(false),realizedNetProfit:z.null()
})
export type CostCrossSourceRiskV187=z.infer<typeof riskReport>

export async function fetchCostCrossSourceRiskV187(marketplaceId:string,
 start:string,endExclusive:string):Promise<CostCrossSourceRiskV187>{
 const params=new URLSearchParams({periodStart:start,periodEndExclusive:endExclusive})
 const r=await fetch('/api/v1/finance/marketplaces/'+
  encodeURIComponent(marketplaceId)+'/cost-cross-source-risk?'+params.toString(),{
  method:'GET',credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json'}
 })
 if(!r.ok)throw new Error('全期间跨来源费用冲突核对 HTTP '+r.status)
 return riskReport.parse(await r.json())
}
