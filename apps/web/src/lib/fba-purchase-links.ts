import {z} from 'zod'
import {listFbaPurchaseLinks,listFbaPurchaseCandidates,proposeFbaPurchaseLink,
 confirmFbaPurchaseLink,revokeFbaPurchaseLink} from '../generated/fba-inbound'

const uuid=z.string().uuid()
const nullableId=uuid.nullable()
const integer=z.number().int().nonnegative()
export const candidateSchema=z.object({
 purchaseOrderId:uuid,purchaseOrderLineId:uuid,orderNumber:z.string(),
 supplierName:z.string(),orderedQuantity:integer,localReceivedQuantity:integer,
 asnId:nullableId,asnStatus:z.string().nullable(),
 asnQuantity:integer.nullable(),asnReceivedQuantity:integer
})
export const purchaseLinkSchema=z.object({
 id:uuid,shipmentId:uuid,inboundLineId:uuid,sellerSku:z.string(),
 shipmentState:z.enum(['DRAFT','IN_TRANSIT','RECEIVING','REVIEWING','RECONCILED']),
 shipmentOrigin:z.enum(['MOCK','MANUAL_UNVERIFIED']),
 inboundPlannedQuantity:integer,inboundReportedReceived:integer,
 purchaseOrderId:uuid,purchaseOrderLineId:uuid,orderNumber:z.string(),
 purchaseState:z.string(),poOrderedQuantity:integer,localPoReceived:integer,
 asnId:nullableId,asnStatus:z.string().nullable(),
 asnQuantity:integer.nullable(),asnLocalReceived:integer.nullable(),
 state:z.enum(['PROPOSED','CONFIRMED','REVOKED']),
 proofReference:z.string().min(10).max(200),
 proposedBy:uuid,confirmedBy:nullableId,createdAt:z.string(),
 confirmedAt:z.string().nullable()
})
export type LocalPurchaseLink=z.infer<typeof purchaseLinkSchema>
export type PurchaseCandidate=z.infer<typeof candidateSchema>
export type ProposePurchaseLink={
 requestKey:string;inboundLineId:string;purchaseOrderLineId:string;
 asnId:string|null;proofReference:string
}
/** V145: actual Springdoc/Orval operations, with UUID paths and Zod responses.
 * Legacy limits remain 200 links / 100 candidates; neither is a full history.
 */
export const getPurchaseLinks=async(marketplace:string,shipment:string)=>
 z.array(purchaseLinkSchema).max(200).parse((await listFbaPurchaseLinks(
  uuid.parse(marketplace),uuid.parse(shipment))).data)
export const getPurchaseCandidates=async(marketplace:string,shipment:string,line:string)=>
 z.array(candidateSchema).max(100).parse((await listFbaPurchaseCandidates(
  uuid.parse(marketplace),uuid.parse(shipment),uuid.parse(line))).data)
export const proposePurchaseLink=async(marketplace:string,shipment:string,body:ProposePurchaseLink)=>
 purchaseLinkSchema.parse((await proposeFbaPurchaseLink(
  uuid.parse(marketplace),uuid.parse(shipment),body)).data)
export const confirmPurchaseLink=async(marketplace:string,shipment:string,id:string,reason:string)=>
 purchaseLinkSchema.parse((await confirmFbaPurchaseLink(
  uuid.parse(marketplace),uuid.parse(shipment),uuid.parse(id),{reason})).data)
export const revokePurchaseLink=async(marketplace:string,shipment:string,id:string,reason:string)=>
 purchaseLinkSchema.parse((await revokeFbaPurchaseLink(
  uuid.parse(marketplace),uuid.parse(shipment),uuid.parse(id),{reason})).data)
