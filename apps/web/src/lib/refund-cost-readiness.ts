import {z} from 'zod'

const money=z.string().regex(/^-?[0-9]{1,14}(?:\.[0-9]{1,6})?$/)
const bucket=z.object({
 currency:z.string().regex(/^[A-Z]{3}$/),sourceEventCount:z.number().int(),
 signedProductRefundAmount:money,completeRefundCoverage:z.literal(false),
 verifiedCogsReversal:z.literal(false)
})
const source=z.object({
 eventId:z.string().uuid(),postedAtUtc:z.string(),
 currency:z.string().regex(/^[A-Z]{3}$/),signedSourceRefundAmount:money,
 reviewedOrderId:z.string().uuid().nullable(),evidenceState:z.string(),
 evidenceGap:z.string(),historicalCostExposure:money.nullable(),
 claimedRestockCostSensitivity:money.nullable(),evidenceCurrent:z.boolean(),
 externalReceiptVerified:z.literal(false),
 actualCogsReversalAllowed:z.literal(false),actualCogsReversal:z.null()
})
const page=z.object({
 marketplaceId:z.string().uuid(),periodStartUtc:z.string(),
 periodEndExclusiveUtc:z.string(),buckets:z.array(bucket),events:z.array(source),
 hasMore:z.boolean(),nextBeforeAt:z.string().nullable(),
 nextBeforeId:z.string().uuid().nullable(),
 allFinancesRefundsCovered:z.literal(false),bankReconciled:z.literal(false),
 actualCogsReversalsVerified:z.literal(false),
 realizedNetProfitVerified:z.literal(false)
})
export type RefundCostReadiness=z.infer<typeof page>
export type RefundCostCursor={beforeAt:string;beforeId:string}
export async function fetchRefundCostReadiness(mp:string,start:string,until:string,
 cursor?:RefundCostCursor){
 const params=new URLSearchParams({periodStart:start,periodEndExclusive:until})
 if(cursor){params.set('beforeAt',cursor.beforeAt);params.set('beforeId',cursor.beforeId)}
 const response=await fetch('/api/v1/finance/marketplaces/'+encodeURIComponent(mp)+
  '/refund-cost-readiness?'+params,{credentials:'same-origin',cache:'no-store',
   headers:{Accept:'application/json'}})
 if(!response.ok)throw Error('退款成本风险接口 HTTP '+response.status)
 return page.parse(await response.json())
}
