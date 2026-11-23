import {z} from 'zod'
import {periodProfitWaterfallV188} from './period-profit-waterfall-v188'

/**
 * V211 is a *read-only blocked risk overlay*, never an accounting post.
 * V64 order creation UTC and Finances refund posted UTC remain separate axes.
 * Do not sum exported V64 snapshots or deduct a refund again from signed sales.
 */
const money=z.string().regex(/^-?[0-9]+(?:\.[0-9]{1,6})?$/)
const currency=z.string().regex(/^[A-Z]{3}$/)

const sourceCurrency=z.object({
 currency,
 sourceEventCount:z.number().int().nonnegative(),
 signedRefundSourceAmount:money,
 fullyAttributedToV64Cohort:z.boolean(),
 actualRefundCostRecognized:z.literal(false),
 recognizedRefundCogs:z.null()
})
const sourceEvent=z.object({
 eventId:z.string().uuid(),matchedOrderId:z.string().uuid().nullable(),
 postedAtUtc:z.iso.datetime(),currency,
 signedRefundSourceAmount:money,
 matchedLinkCount:z.number().int().nonnegative(),
 inV64FrozenCohort:z.boolean(),
 currentV210SourceIdentity:z.boolean(),
 blockerCodes:z.array(z.string())
})
const refundOrder=z.object({
 orderId:z.string().uuid(),sourceEventCount:z.number().int().nonnegative(),
 refundEventsOutsideV64OrderDateWindow:z.number().int().nonnegative(),
 allLocalManualRefundEvidenceConsistent:z.boolean(),
 crossEventQuantityConflictCount:z.number().int().nonnegative(),
 actualRefundCogsVerified:z.literal(false),
 actualRefundCogs:z.null()
})

export const periodRefundProfitGuardV211=z.object({
 marketplaceId:z.string().uuid(),reportJobId:z.string().uuid(),
 periodStartUtc:z.iso.datetime(),periodEndExclusiveUtc:z.iso.datetime(),
 dateBasis:z.literal('V64_ORDER_CREATED_AT_UTC_VS_FINANCES_REFUND_POSTED_AT_UTC'),
 ruleVersion:z.literal('V211_V188_V210_PERIOD_REFUND_SOURCE_AXES_V1'),
 v188Baseline:periodProfitWaterfallV188,
 periodPostedSourceEventCount:z.number().int().nonnegative(),
 cohortRefundOrderCount:z.number().int().nonnegative(),
 refundEventsOutsideV64OrderDateWindow:z.number().int().nonnegative(),
 unmatchedPeriodSourceCount:z.number().int().nonnegative(),
 multiMatchedPeriodSourceCount:z.number().int().nonnegative(),
 periodRefundSourceOutsideFrozenCohortCount:z.number().int().nonnegative(),
 intersectingOtherFrozenReportCount:z.number().int().nonnegative(),
 repeatedFrozenOrderCount:z.number().int().nonnegative(),
 currencies:z.array(sourceCurrency),
 periodPostedEvents:z.array(sourceEvent),
 reviewedCohortOrders:z.array(refundOrder),
 originalCurrencySourceSnapshotConsistent:z.boolean(),
 allLocalManualRefundConservationComplete:z.boolean(),
 refundRecognitionPeriodPolicyApproved:z.literal(false),
 actualRefundCogsVerified:z.literal(false),
 independentAmazonRefundCoverageVerified:z.literal(false),
 doubleJournalDeductionAllowed:z.literal(false),
 refundAdjustedContribution:z.null(),
 realizedNetProfit:z.null(),
 blockerCodes:z.array(z.string()).min(7)
}).superRefine((p,ctx)=>{
 if(p.periodPostedSourceEventCount!==p.periodPostedEvents.length)
  ctx.addIssue({code:'custom',message:'退款事件计数和整期来源列表不一致'})
 if(p.cohortRefundOrderCount!==p.reviewedCohortOrders.length)
  ctx.addIssue({code:'custom',message:'冻结订单证据数量不一致'})
 if(p.currencies.reduce((n,c)=>n+c.sourceEventCount,0)!==
    p.periodPostedSourceEventCount)
  ctx.addIssue({code:'custom',message:'不同原币的来源数量之和不一致'})
 if(p.refundEventsOutsideV64OrderDateWindow!==
    p.reviewedCohortOrders.reduce((n,o)=>
     n+o.refundEventsOutsideV64OrderDateWindow,0))
  ctx.addIssue({code:'custom',message:'跨期间退款次数汇总不一致'})
 if(p.unmatchedPeriodSourceCount>p.periodPostedSourceEventCount||
    p.multiMatchedPeriodSourceCount>p.periodPostedSourceEventCount||
    p.periodRefundSourceOutsideFrozenCohortCount>p.periodPostedSourceEventCount)
  ctx.addIssue({code:'custom',message:'退款归属风险计数超过来源集合'})
 if(p.marketplaceId!==p.v188Baseline.marketplaceId||
    p.reportJobId!==p.v188Baseline.reportJobId)
  ctx.addIssue({code:'custom',message:'V188 冻结利润基线不属于本次退款报告'})
})
export type PeriodRefundProfitGuardV211=z.infer<typeof periodRefundProfitGuardV211>

export async function getPeriodRefundProfitGuardV211(mp:string,job:string){
 const response=await fetch('/api/v1/finance/marketplaces/'+encodeURIComponent(mp)+
   '/period-refund-profit-guard/'+encodeURIComponent(job),{
    method:'GET',credentials:'same-origin',cache:'no-store',
    headers:{Accept:'application/json'}
   })
 if(!response.ok)
  throw new Error(response.status===409
   ?'退款或成本证据已经变化，或者订单/退款数量超出安全核对上限；不能显示部分期间利润'
   :response.status===401?'登录状态已失效，请重新登录后再核对'
   :'V211 期间退款核对失败（HTTP '+response.status+'）')
 return periodRefundProfitGuardV211.parse(await response.json())
}
