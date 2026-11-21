import {z} from 'zod'

/** V64 frozen original-currency cohort with live V91/V92/V165/V168 evidence.
 * "Unverified sensitivity" is never realized net profit or an approved debit.
 */
const money=z.string().regex(/^-?[0-9]+\.[0-9]{6}$/)
const positiveMoney=z.string().regex(/^[0-9]+\.[0-9]{6}$/)
const costLine=z.object({
 category:z.enum(['ADS_SPEND','INBOUND_FREIGHT','FBA_STORAGE','FBM_SHIPPING',
  'RETURN_HANDLING','IMPORT_DUTY','PAYMENT_AND_FX','OTHER_OPERATING']),
 currency:z.string().regex(/^[A-Z]{3}$/),
 primaryDimension:z.enum(['SKU','ORDER','PERIOD','UNASSIGNED']),
 sourceCount:z.number().int().positive(),
 approvedUnverifiedSourceCount:z.number().int().nonnegative(),
 pendingSourceCount:z.number().int().nonnegative(),
 unassignedSourceCount:z.number().int().nonnegative(),
 missingApprovalAuditCount:z.number().int().nonnegative(),
 claimedAmount:positiveMoney,
 approvedUnverifiedAllocatedAmount:positiveMoney.nullable(),
 approvedUnverifiedRemainderAmount:positiveMoney.nullable()
})
export const periodProfitWaterfallV188=z.object({
 marketplaceId:z.string().uuid(),reportJobId:z.string().uuid(),
 periodStart:z.iso.date(),periodEndExclusive:z.iso.date(),
 currency:z.string().regex(/^[A-Z]{3}$/),
 ruleVersion:z.literal(
  'V188_V64_ELIGIBLE_CONTRIBUTION_MINUS_V92_APPROVED_UNVERIFIED_ORIGINAL_CURRENCY_V1'),
 dateBasis:z.literal('V64_ORDER_UTC_V91_COST_SOURCE_BUSINESS_DATE'),
 sourceSnapshotBasis:z.literal(
  'V64_IMMUTABLE_ORDER_EXTRACTS_V91_V92_V165_V168_LIVE_READ'),
 jobState:z.enum(['READY','RETRY','PAUSED','SUCCEEDED']),
 totalOrders:z.number().int().nonnegative(),
 eligibleOrders:z.number().int().nonnegative(),
 blockedOrders:z.number().int().nonnegative(),
 manualCostSourceCount:z.number().int().nonnegative(),
 potentialCollisionSourceCount:z.number().int().nonnegative(),
 sharedSettlementLineSourceCount:z.number().int().nonnegative(),
 eligibleSignedSales:money.nullable(),
 eligibleSignedAmazonFees:money.nullable(),
 eligibleSignedFbaFees:money.nullable(),
 eligibleProvisionalCogs:positiveMoney.nullable(),
 eligibleContribution:money.nullable(),
 approvedUnverifiedLocalCostExposure:positiveMoney.nullable(),
 unverifiedCostSensitivityContribution:money.nullable(),
 sensitivityState:z.enum(['ORDER_FINANCIAL_BASELINE_INCOMPLETE',
  'ADDITIONAL_COST_SOURCES_UNKNOWN','MIXED_ORIGINAL_CURRENCY_BLOCKED',
  'CROSS_SOURCE_COLLISION_BLOCKED','V92_UNVERIFIED_COST_DISTRIBUTION_INCOMPLETE',
  'UNVERIFIED_COST_SENSITIVITY_ONLY']),
 costLines:z.array(costLine),
 categoriesWithoutSourceClaims:z.array(z.string()),
 blockerCodes:z.array(z.string()).min(8),
 financeFeesExcludedFromAdditionalCosts:z.literal(true),
 additionalCostExternallyVerified:z.literal(false),
 accountingPolicyApproved:z.literal(false),
 refundCogsReversalVerified:z.literal(false),
 fxConversionApproved:z.literal(false),
 bankSettlementVerified:z.literal(false),
 ledgerPosted:z.literal(false),
 realizedNetProfitVerified:z.literal(false),
 realizedNetProfit:z.null()
}).superRefine((p,ctx)=>{
 const canShow=p.sensitivityState==='UNVERIFIED_COST_SENSITIVITY_ONLY'
 if(canShow!==Boolean(p.unverifiedCostSensitivityContribution!==null &&
  p.approvedUnverifiedLocalCostExposure!==null))
  ctx.addIssue({code:'custom',message:'未核验算术敏感度必须与阻断状态一致'})
 if(p.manualCostSourceCount!==p.costLines.reduce((n,b)=>n+b.sourceCount,0))
  ctx.addIssue({code:'custom',message:'成本来源不能跨维度重复计数'})
 if(p.potentialCollisionSourceCount>p.manualCostSourceCount)
  ctx.addIssue({code:'custom',message:'本地重复风险超过来源总数'})
})
export type PeriodProfitWaterfallV188=z.infer<typeof periodProfitWaterfallV188>

export async function getPeriodProfitWaterfallV188(mp:string,jobId:string){
 const r=await fetch('/api/v1/finance/marketplaces/'+encodeURIComponent(mp)+
  '/cost-waterfall-preview/'+encodeURIComponent(jobId),{
   method:'GET',credentials:'same-origin',cache:'no-store',
   headers:{Accept:'application/json'}
  })
 if(!r.ok)throw new Error('V188 统一期间成本瀑布读取 HTTP '+r.status)
 return periodProfitWaterfallV188.parse(await r.json())
}
