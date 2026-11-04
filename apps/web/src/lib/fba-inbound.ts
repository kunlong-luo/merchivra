import {z} from 'zod'
import {listFbaInboundShipments,listFbaInboundShipmentPage,listFbaInboundTransit,getFbaInboundShipment,
 createFbaInboundShipment,dispatchFbaInboundShipment,recordFbaInboundReceipt,
 startFbaInboundReview,resolveFbaInboundVariance,finalizeFbaInboundReconciliation}
 from '../generated/fba-inbound'

const id=z.string().uuid()
const origin=z.enum(['MOCK','MANUAL_UNVERIFIED'])
const nonnegative=z.number().int().nonnegative()
const optionalTimestamp=z.string().nullable()
const shipment=z.object({
 id,reference:z.string(),origin,
 state:z.enum(['DRAFT','IN_TRANSIT','RECEIVING','REVIEWING','RECONCILED']),
 createdAt:z.string(),dispatchedAt:optionalTimestamp,
 reviewStartedAt:optionalTimestamp,reconciledAt:optionalTimestamp
})
const line=z.object({
 id,sellerSku:z.string(),plannedQuantity:nonnegative,
 reportedReceived:nonnegative,estimatedInTransit:nonnegative.nullable(),
 delta:z.number().int(),decision:z.enum(['ACCEPT_VARIANCE']).nullable(),
 reviewedBy:id.nullable(),reviewNote:z.string().nullable(),reviewedAt:optionalTimestamp
})
const receiptEvent=z.object({
 id,sellerSku:z.string(),quantity:nonnegative,evidenceReference:z.string(),
 origin,recordedBy:id,recordedAt:z.string()
})
export const inboundDetail=z.object({
 shipment,lines:z.array(line).max(100),receipts:z.array(receiptEvent).max(1000),
 amazonVerified:z.literal(false),fbaStockCredited:z.literal(false)
})
const transit=z.object({
 sellerSku:z.string(),origin,planned:nonnegative,
 reportedReceived:nonnegative,estimatedInTransit:nonnegative,
 amazonVerified:z.literal(false)
})
/** V92: local history pages are source observations, never Amazon shipments. */
export const inboundShipmentPageSchema=z.object({
 items:z.array(shipment).max(100),
 nextCursor:z.string().min(1).max(4096).nullable(),
 hasMore:z.boolean(),
 pageSize:z.number().int().min(1).max(100),
 searchPrefix:z.string().max(80),
 state:z.enum(['ALL','DRAFT','IN_TRANSIT','RECEIVING','REVIEWING','RECONCILED'])
}).superRefine((p,ctx)=>{
 if(p.hasMore&&!p.nextCursor)ctx.addIssue({code:'custom',message:'缺少下一页游标'})
 if(!p.hasMore&&p.nextCursor)ctx.addIssue({code:'custom',message:'末页不应返回游标'})
})
export type InboundShipmentPage=z.infer<typeof inboundShipmentPageSchema>
export type InboundHistoryState=InboundShipmentPage['state']
/** Spring-owned OpenAPI + Orval-generated endpoint, with Zod runtime checks. */
export async function getShipmentsPage(
 mp:string,search='',state:InboundHistoryState='ALL',cursor:string|null=null,
 limit=50):Promise<InboundShipmentPage>{
 const response=await listFbaInboundShipmentPage(mp,{
  limit,state,
  ...(search.trim()?{search:search.trim()}:{}),
  ...(cursor?{cursor}:{})
 })
 return inboundShipmentPageSchema.parse(response.data)
}
export type InboundDetail=z.infer<typeof inboundDetail>
export type InboundShipment=z.infer<typeof shipment>
export type InboundLine=z.infer<typeof line>
export type InboundTransit=z.infer<typeof transit>
export type InboundOrigin=z.infer<typeof origin>
export type CreateShipmentInput={
 requestKey:string;reference:string;origin:InboundOrigin;
 lines:{sellerSku:string;quantity:number}[]
}
export type RecordReceiptInput={
 requestKey:string;sellerSku:string;quantity:number;
 evidenceReference:string;origin:InboundOrigin
}
export const getShipments=async(mp:string)=>
 z.array(shipment).max(100).parse((await listFbaInboundShipments(mp)).data)
export const getTransit=async(mp:string)=>
 z.array(transit).max(500).parse((await listFbaInboundTransit(mp)).data)
export const getShipment=async(mp:string,id:string)=>
 inboundDetail.parse((await getFbaInboundShipment(mp,id)).data)
export const addShipment=async(mp:string,body:CreateShipmentInput)=>
 inboundDetail.parse((await createFbaInboundShipment(mp,body)).data)
export const dispatchShipment=async(mp:string,id:string)=>
 inboundDetail.parse((await dispatchFbaInboundShipment(mp,id)).data)
export const addReceipt=async(mp:string,id:string,body:RecordReceiptInput)=>
 inboundDetail.parse((await recordFbaInboundReceipt(mp,id,body)).data)
export const beginReview=async(mp:string,id:string)=>
 inboundDetail.parse((await startFbaInboundReview(mp,id)).data)
export const resolveVariance=async(mp:string,id:string,sellerSku:string,note:string)=>
 inboundDetail.parse((await resolveFbaInboundVariance(mp,id,{sellerSku,note})).data)
export const closeReview=async(mp:string,id:string)=>
 inboundDetail.parse((await finalizeFbaInboundReconciliation(mp,id)).data)
