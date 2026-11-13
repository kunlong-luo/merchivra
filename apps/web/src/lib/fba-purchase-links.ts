import {z} from 'zod'
import {listFbaPurchaseLinks,listFbaPurchaseCandidates,listFbaPurchaseLinksPage,
 listFbaPurchaseCandidatesPage,proposeFbaPurchaseLink,
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
/** V147: strict complete source pages with explicit completeness metadata. */
const cursorSchema=z.string().min(1).max(2048).regex(/^[A-Za-z0-9_-]+$/)
const pageOf=<T extends z.ZodType>(item:T)=>z.object({
 items:z.array(item).max(100),nextCursor:cursorSchema.nullable(),
 hasMore:z.boolean(),pageSize:z.number().int().min(1).max(100)
}).superRefine((v,ctx)=>{
 if(v.items.length>v.pageSize)ctx.addIssue({code:'custom',message:'Page exceeds declared size'})
 if(v.hasMore&&(!v.nextCursor||v.items.length===0))
  ctx.addIssue({code:'custom',message:'Page missing next cursor'})
 if(!v.hasMore&&v.nextCursor)
  ctx.addIssue({code:'custom',message:'Terminal page must not advertise a cursor'})
})
export const purchaseLinkPageSchema=pageOf(purchaseLinkSchema)
export const purchaseCandidatePageSchema=pageOf(candidateSchema)
export type PurchaseLinkPage=z.infer<typeof purchaseLinkPageSchema>
export type PurchaseCandidatePage=z.infer<typeof purchaseCandidatePageSchema>
const pageLimit=z.number().int().min(1).max(100)
const linkState=z.enum(['ALL','PROPOSED','CONFIRMED','REVOKED'])

/** New source pages use the actual OpenAPI/Orval client; legacy array calls remain compatible. */
export async function getPurchaseLinksPage(
 marketplace:string,shipment:string,cursor:string|null=null,
 state:'ALL'|'PROPOSED'|'CONFIRMED'|'REVOKED'='ALL',
 inboundLineId:string|null=null,limit=50):Promise<PurchaseLinkPage>{
 const params={
  limit:pageLimit.parse(limit),state:linkState.parse(state),
  ...(inboundLineId?{inboundLineId:uuid.parse(inboundLineId)}:{}),
  ...(cursor?{cursor:cursorSchema.parse(cursor)}:{})
 }
 const response=await listFbaPurchaseLinksPage(uuid.parse(marketplace),uuid.parse(shipment),params)
 return purchaseLinkPageSchema.parse(response.data)
}
export async function getPurchaseCandidatesPage(
 marketplace:string,shipment:string,line:string,cursor:string|null=null,
 purchaseLineId:string|null=null,limit=50):Promise<PurchaseCandidatePage>{
 const params={
  limit:pageLimit.parse(limit),
  ...(purchaseLineId?{purchaseLineId:uuid.parse(purchaseLineId)}:{}),
  ...(cursor?{cursor:cursorSchema.parse(cursor)}:{})
 }
 const response=await listFbaPurchaseCandidatesPage(
  uuid.parse(marketplace),uuid.parse(shipment),uuid.parse(line),params)
 return purchaseCandidatePageSchema.parse(response.data)
}

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
