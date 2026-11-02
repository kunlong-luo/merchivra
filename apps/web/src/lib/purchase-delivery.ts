import {z} from 'zod'

const line=z.object({
 orderId:z.string().uuid(),orderNumber:z.string(),
 orderStatus:z.enum(['APPROVED','RECEIVING','RECEIVED']),
 supplierId:z.string().uuid(),supplierName:z.string(),currency:z.string().regex(/^[A-Z]{3}$/),
 lineId:z.string().uuid(),productSkuId:z.string().uuid(),internalSku:z.string(),
 orderedQuantity:z.number().int().positive(),receivedQuantity:z.number().int().nonnegative(),
 remainingQuantity:z.number().int().nonnegative(),
 draftAsnQuantity:z.number().int().nonnegative(),
 shippedAsnQuantity:z.number().int().nonnegative(),
 reportedUnreceivedQuantity:z.number().int().nonnegative(),
 unlinkedReceiptQuantity:z.number().int().nonnegative(),
 awaitingQcReleaseQuantity:z.number().int().nonnegative(),
 releasedQcAcceptedQuantity:z.number().int().nonnegative(),
 qcUnknownQuantity:z.number().int().nonnegative(),
 unresolvedVarianceCount:z.number().int().nonnegative(),
 earliestReportedEta:z.string().nullable(),latestReportedEta:z.string().nullable(),
 replenishmentSellerSku:z.string().nullable(),
 replenishmentPricingSource:z.enum(['MANUAL','V42_RULE']).nullable(),
 deliveryState:z.enum(['DISCREPANCY_REVIEW','AWAITING_QC_RELEASE','QC_EVIDENCE_UNKNOWN',
 'LOCAL_RECEIVED','REPORTED_ETA_OVERDUE','REPORTED_ETA_DUE_SOON',
 'SHIPMENT_REPORTED','ASN_AWAITING_DISPATCH','NO_ASN_RECORDED'])
})
const totals=z.object({lineCount:z.number().int().nonnegative(),
 pendingQuantity:z.number().int().nonnegative(),
 reportedUnreceivedQuantity:z.number().int().nonnegative(),
 awaitingQcReleaseQuantity:z.number().int().nonnegative(),
 warningLines:z.number().int().nonnegative(),
 overdueLines:z.number().int().nonnegative(),
 unresolvedVarianceLines:z.number().int().nonnegative()})
export const deliveryOverviewSchema=z.object({
 asOfDateUtc:z.string(),totals,lines:z.array(line).max(300)
})
export type PurchaseDeliveryOverview=z.infer<typeof deliveryOverviewSchema>
export type PurchaseDeliveryLine=z.infer<typeof line>

/** Local records only; browser never sends tenantId. Session controls the scope. */
export async function fetchPurchaseDeliveryOverview():Promise<PurchaseDeliveryOverview> {
 const response=await fetch('/api/v1/purchasing/delivery/overview',{
  method:'GET',credentials:'same-origin',cache:'no-store',headers:{Accept:'application/json'}
 })
 if(!response.ok) {
  if(response.status===403)throw Error('仅当前租户 Owner 可查看采购交付')
  if(response.status===401)throw Error('会话已过期，请重新登录')
  throw Error('读取本地采购交付状态失败：HTTP '+response.status)
 }
 return deliveryOverviewSchema.parse(await response.json())
}
