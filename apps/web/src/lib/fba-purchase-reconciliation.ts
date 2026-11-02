import {z} from 'zod'
import {merchivraFetch} from './merchivra-mutator'

const id=z.string().uuid()
const units=z.number().int().nonnegative()
const nullableId=id.nullable()
const severity=z.enum(['HIGH','WARNING','INFO'])
const status=z.enum([
 'LOCAL_OVER_REPORT','PENDING_IDENTITY_REVIEW','LINK_SOURCE_CHANGED',
 'LOCAL_VARIANCE_PENDING','LOCAL_REPORT_WITHOUT_IDENTITY',
 'DISPATCHED_WITHOUT_LINK','UNLINKED_DRAFT','LOCAL_VARIANCE_ACCEPTED',
 'CONFIRMED_PO_ONLY','IDENTITY_CONFIRMED'
])
const action=z.enum(['REVIEW_LOCAL_VARIANCE','SECOND_OWNER_REVIEW',
 'RECHECK_SOURCE_IDENTITY','REVIEW_MISSING_LINK','OPTIONAL_MANUAL_LINK',
 'OPTIONAL_ASN_EVIDENCE','NONE'])
export const reconciliationLineSchema=z.object({
 shipmentId:id,shipmentReference:z.string(),shipmentState:z.enum([
  'DRAFT','IN_TRANSIT','RECEIVING','REVIEWING','RECONCILED']),
 shipmentOrigin:z.enum(['MOCK','MANUAL_UNVERIFIED']),
 inboundLineId:id,sellerSku:z.string(),plannedQuantity:units,
 locallyReportedQuantity:units,localVarianceDecision:z.enum(['ACCEPT_VARIANCE']).nullable(),
 linkId:nullableId,linkState:z.enum(['PROPOSED','CONFIRMED']).nullable(),
 proofReference:z.string().nullable(),purchaseOrderId:nullableId,
 purchaseOrderNumber:z.string().nullable(),purchaseOrderState:z.string().nullable(),
 purchaseOrderedQuantity:units.nullable(),purchaseLocalReceivedQuantity:units.nullable(),
 asnId:nullableId,asnState:z.string().nullable(),asnReportedQuantity:units.nullable(),
 asnLocalReceivedQuantity:units.nullable(),
 findingCode:status,severity,recommendedAction:action
})
const totals=z.object({
 examinedLines:units,high:units,warning:units,informational:units,
 pendingIdentity:units,unlinked:units,localVariance:units
})
export const reconciliationSchema=z.object({
 truncated:z.boolean(),shipmentScope:units,maxRows:units.optional(),
 totals,lines:z.array(reconciliationLineSchema).max(500),
 amazonVerified:z.literal(false),fbaStockCredited:z.literal(false)
})
export type FbaPurchaseReconciliation=z.infer<typeof reconciliationSchema>
export type FbaReconciliationLine=z.infer<typeof reconciliationLineSchema>

/** Session tenant + Marketplace scoped GET, no Amazon credentials / writes. */
export async function getPurchaseReconciliation(marketplace:string){
 const url='/api/v1/fba/marketplaces/'+encodeURIComponent(marketplace)+
  '/purchase-reconciliation'
 const result=await merchivraFetch<{data:unknown}>(url,{method:'GET'})
 return reconciliationSchema.parse(result.data)
}
