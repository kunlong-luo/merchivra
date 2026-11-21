import {z} from 'zod'
import {csrfHeader} from './auth'
const uuid=z.string().uuid()
export const receiptClaim=z.object({
 id:uuid,eventId:uuid,itemId:z.string(),orderId:uuid,
 claimedQuantity:z.number().int().positive(),
 condition:z.enum(['SELLABLE_CLAIMED','UNSELLABLE_CLAIMED','UNKNOWN']),
 warehouseId:uuid.nullable(),evidenceReference:z.string(),
 state:z.enum(['PENDING','REVIEWED_MANUAL','REJECTED','STALE']),
 reviewRevision:z.number().int().positive(),
 requestedBy:uuid,decidedBy:uuid.nullable(),
 requestedAt:z.string(),evidenceCurrent:z.boolean(),
 physicalReceiptVerified:z.literal(false),saleabilityVerified:z.literal(false),
 cogsReversalEligible:z.literal(false),actualCogsReversal:z.null()
})
export type ReceiptClaim=z.infer<typeof receiptClaim>
export const receiptAudit=z.object({
 action:z.enum(['REQUESTED','REVIEWED_MANUAL','REJECTED']),
 actorId:uuid,note:z.string(),occurredAt:z.string()
})
const root=(mp:string,event:string)=>
 '/api/v1/finance/marketplaces/'+encodeURIComponent(mp)+'/events/'+
 encodeURIComponent(event)+'/return-receipts'
async function parse<T>(response:Response,schema:z.ZodType<T>):Promise<T>{
 if(!response.ok)throw Error('退货实收人工证据请求失败（HTTP '+response.status+'）')
 return schema.parse(await response.json())
}
export async function listReceiptClaims(mp:string,event:string){
 const r=await fetch(root(mp,event),{credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json'}})
 return parse(r,z.array(receiptClaim).max(100))
}
export async function requestReceiptClaim(mp:string,event:string,payload:{
 itemId:string;quantity:number;condition:'SELLABLE_CLAIMED'|'UNSELLABLE_CLAIMED'|'UNKNOWN';
 localWarehouseId:string|null;evidenceReference:string;note:string
}){
 const r=await fetch(root(mp,event),{method:'POST',credentials:'same-origin',
  cache:'no-store',headers:{...(await csrfHeader()),Accept:'application/json',
   'Content-Type':'application/json'},body:JSON.stringify(payload)})
 return parse(r,receiptClaim)
}
export async function decideReceiptClaim(mp:string,event:string,id:string,
 decision:'approve'|'reject',reason:string){
 const r=await fetch(root(mp,event)+'/'+encodeURIComponent(id)+'/'+decision,{
  method:'POST',credentials:'same-origin',cache:'no-store',
  headers:{...(await csrfHeader()),Accept:'application/json',
   'Content-Type':'application/json'},body:JSON.stringify({reason})})
 return parse(r,receiptClaim)
}
export async function getReceiptClaimAudit(mp:string,event:string,id:string){
 const r=await fetch(root(mp,event)+'/'+encodeURIComponent(id)+'/audit',{
  credentials:'same-origin',cache:'no-store',headers:{Accept:'application/json'}})
 return parse(r,z.array(receiptAudit).max(100))
}
