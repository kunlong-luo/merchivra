import {z} from 'zod'
import {merchivraFetch} from './merchivra-mutator'

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
const root=(marketplace:string,shipment:string)=>
 '/api/v1/fba/marketplaces/'+encodeURIComponent(marketplace)+
 '/shipments/'+encodeURIComponent(shipment)+'/purchase-links'

/** Shared Orval Fetch mutator handles same-origin Session, CSRF, errors and no-store. */
async function read<T>(url:string,schema:z.ZodType<T>):Promise<T>{
 const r=await merchivraFetch<{data:unknown}>(url,{method:'GET'})
 return schema.parse(r.data)
}
async function write<T>(url:string,payload:unknown,schema:z.ZodType<T>):Promise<T>{
 const r=await merchivraFetch<{data:unknown}>(url,{
  method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)})
 return schema.parse(r.data)
}
export const getPurchaseLinks=(marketplace:string,shipment:string)=>
 read(root(marketplace,shipment),z.array(purchaseLinkSchema).max(200))
export const getPurchaseCandidates=(marketplace:string,shipment:string,line:string)=>
 read(root(marketplace,shipment)+'/lines/'+encodeURIComponent(line)+'/candidates',
  z.array(candidateSchema).max(100))
export const proposePurchaseLink=(marketplace:string,shipment:string,body:ProposePurchaseLink)=>
 write(root(marketplace,shipment),body,purchaseLinkSchema)
export const confirmPurchaseLink=(marketplace:string,shipment:string,id:string,reason:string)=>
 write(root(marketplace,shipment)+'/'+encodeURIComponent(id)+'/confirm',
  {reason},purchaseLinkSchema)
export const revokePurchaseLink=(marketplace:string,shipment:string,id:string,reason:string)=>
 write(root(marketplace,shipment)+'/'+encodeURIComponent(id)+'/revoke',
  {reason},purchaseLinkSchema)
