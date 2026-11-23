import {z} from 'zod'

/** V213 is an original-receipt, cross-order quantity audit, not landed COGS. */
const count=z.number().int().nonnegative()
const currency=z.string().regex(/^[A-Z]{3}$/)
const reference=z.string().regex(/^[0-9]+(?:\.[0-9]{1,6})?$/)
const receipt=z.object({
 originalPurchaseReceiptId:z.string().uuid(),
 thisOrderClaimedUnits:count,allOrdersClaimedUnits:count,
 originalQcAcceptedUnits:count,unitsClaimedByOtherOrders:count,
 locallyUnclaimedAcceptedUnits:count,thisOrderClaimCount:count,
 totalClaimRows:count,distinctOriginalSaleOrders:count,
 staleOrRetractedClaimRows:count,originalCurrency:currency.nullable(),
 originalPurchaseReferenceUnitPrice:reference.nullable(),
 localCapacityState:z.string(),
 externallyVerifiedLandedUnitCost:z.literal(false),
 actualLandedUnitCost:z.null()
})
export const refundGlobalReceiptCapacityV213=z.object({
 marketplaceId:z.string().uuid(),orderId:z.string().uuid(),
 ruleVersion:z.literal('V213_GLOBAL_V106_RECEIPT_ALLOCATION_AND_LANDED_COST_HOLD_V1'),
 sourceBasis:z.literal('V106_IMMUTABLE_LOCAL_RECEIPT_CLAIMS_V212_REFUND_ORIGINAL_LOTS'),
 originalReceiptCount:count,globalClaimRows:count,
 sharedOriginalReceiptCount:count,otherOrderReceiptMembershipCount:count,
 receipts:z.array(receipt),
 blockerCodes:z.array(z.string()).min(6),
 completeGlobalLocalReceiptCapacity:z.boolean(),
 everyRefundUnitMappedToOriginalReceipt:z.literal(false),
 independentlyVerifiedActualLandedCosts:z.literal(false),
 physicalReturnToOriginalReceiptVerified:z.literal(false),
 originalPurchaseFxAndDutiesVerified:z.literal(false),
 crossPeriodCogsDoublePostingExcluded:z.literal(false),
 refundCogsReversalAllowed:z.literal(false),
 actualRefundCogsReversal:z.null(),realizedNetProfit:z.null()
}).superRefine((p,ctx)=>{
 if(p.originalReceiptCount!==p.receipts.length)
  ctx.addIssue({code:'custom',message:'全局采购收货批次数量不一致'})
 if(p.globalClaimRows!==p.receipts.reduce((sum,r)=>sum+r.totalClaimRows,0))
  ctx.addIssue({code:'custom',message:'跨订单批次原始认领数不守恒'})
 if(p.sharedOriginalReceiptCount!==p.receipts.filter(r=>r.distinctOriginalSaleOrders>1).length)
  ctx.addIssue({code:'custom',message:'共享采购收货批次统计不一致'})
 if(p.completeGlobalLocalReceiptCapacity && (
   p.originalReceiptCount===0 ||p.receipts.some(r=>
    r.localCapacityState!=='LOCAL_RECEIPT_CAPACITY_WITHIN_QC_UNVERIFIED_EXTERNAL')
  ))
  ctx.addIssue({code:'custom',message:'本地批次来源未知，禁止伪称全部守恒'})
})
export type RefundGlobalReceiptCapacityV213=z.infer<typeof refundGlobalReceiptCapacityV213>

export async function getRefundGlobalReceiptCapacityV213(mp:string,order:string){
 const r=await fetch('/api/v1/finance/marketplaces/'+encodeURIComponent(mp)+
   '/orders/'+encodeURIComponent(order)+'/refund-original-receipt-capacity',{
   method:'GET',credentials:'same-origin',cache:'no-store',
   headers:{Accept:'application/json'}
 })
 if(!r.ok)throw new Error(r.status===409
   ?'跨订单收货批次的数量或来源发生变化，或超过全量核对上限；禁止显示部分结果'
   :'V213 原采购收货容量复核失败（HTTP '+r.status+'）')
 return refundGlobalReceiptCapacityV213.parse(await r.json())
}
