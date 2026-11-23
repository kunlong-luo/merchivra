import {z} from 'zod'

/** V212 is a quantity-only envelope, not actual return-to-lot COGS. */
const lot=z.object({
 claimId:z.string().uuid(),
 originalPurchaseReceiptId:z.string().uuid(),
 orderItemId:z.string(),
 locallyClaimedUnits:z.number().int().positive(),
 localIdentityState:z.string(),
 purchaseReferenceCurrency:z.string().nullable(),
 purchaseReferenceUnitCost:z.string().nullable(),
 actualBatchCostVerified:z.literal(false),
 actualBatchCogs:z.null()
})
const item=z.object({
 orderItemId:z.string(),
 originalSaleQuantity:z.number().int().nullable(),
 reviewedRefundQuantity:z.number().int().nonnegative(),
 restockClaimedQuantity:z.number().int().nonnegative(),
 locallyAttributedSaleQuantity:z.number().int().nonnegative(),
 attestedLocalReturnQuantity:z.number().int().nonnegative(),
 pendingLocalReturnQuantity:z.number().int().nonnegative(),
 unassignedRefundQuantity:z.number().int().nonnegative(),
 unattestedRestockQuantity:z.number().int().nonnegative(),
 distinctOriginalPurchaseReceipts:z.number().int().nonnegative(),
 localQuantityEnvelopeConsistent:z.boolean(),
 saleLotClaims:z.array(lot),
 blockerCodes:z.array(z.string()),
 actualRefundBatchCost:z.null()
})
export const refundBatchLineageV212=z.object({
 orderId:z.string().uuid(),marketplaceId:z.string().uuid(),
 contractVersion:z.literal('V212_SALE_LOT_REFUND_RETURN_INTAKE_QUANTITY_ENVELOPE_V1'),
 refundEventCount:z.number().int().nonnegative(),
 reviewedItemRows:z.number().int().nonnegative(),
 originalSaleLotClaimCount:z.number().int().nonnegative(),
 returnIntakeCandidateCount:z.number().int().nonnegative(),
 orderItems:z.array(item),
 blockerCodes:z.array(z.string()).min(6),
 localAllItemQuantitiesConsistent:z.boolean(),
 externalAmazonRefundsComplete:z.literal(false),
 originalAmazonFulfillmentVerified:z.literal(false),
 physicalReturnReceiptVerified:z.literal(false),
 independentlyVerifiedActualBatchCogs:z.literal(false),
 accountingRecognitionApproved:z.literal(false),
 refundCogsReversalAllowed:z.literal(false),
 actualRefundCogsReversal:z.null(),
 realizedNetProfit:z.null()
}).superRefine((p,ctx)=>{
 if(p.orderItems.reduce((n,i)=>n+i.saleLotClaims.length,0)!==
   p.originalSaleLotClaimCount)
  ctx.addIssue({code:'custom',message:'原始拣货批次认领总数不一致'})
 if(p.refundEventCount===0&&p.localAllItemQuantitiesConsistent)
  ctx.addIssue({code:'custom',message:'没有退款来源不可报告完整本地核对'})
})
export type RefundBatchLineageV212=z.infer<typeof refundBatchLineageV212>

export async function getRefundBatchLineageV212(mp:string,order:string){
 const response=await fetch('/api/v1/finance/marketplaces/'+encodeURIComponent(mp)+
   '/orders/'+encodeURIComponent(order)+'/refund-batch-lineage-readiness',{
   method:'GET',credentials:'same-origin',cache:'no-store',
   headers:{Accept:'application/json'}
 })
 if(!response.ok)throw new Error(response.status===409
   ?'退款、销售批次或收货来源已经变化，或记录超过安全上限：停止计算'
   :'批次来源核对失败（HTTP '+response.status+'）')
 return refundBatchLineageV212.parse(await response.json())
}
