import {z} from 'zod'
import {csrfHeader} from './auth'

const uuid=z.string().uuid()
const intake=z.object({
 id:uuid,claimId:uuid,itemId:z.string(),warehouseId:uuid,
 sourceReference:z.string(),quantity:z.number().int().positive(),
 condition:z.enum(['SELLABLE_CLAIMED','UNSELLABLE_CLAIMED','UNKNOWN']),
 status:z.enum(['PENDING','ATTESTED_LOCAL','REJECTED','STALE']),
 requestedBy:uuid,decidedBy:uuid.nullable(),createdAt:z.string(),
 evidenceCurrent:z.boolean(),externalWmsVerified:z.literal(false),
 inventoryPosted:z.literal(false),cogsReversalEligible:z.literal(false),
 actualCogsReversal:z.null()
})
const coverage=z.object({
 claimId:uuid,itemId:z.string(),manualClaimedQuantity:z.number().int(),
 pendingQuantity:z.number().int().nonnegative(),
 locallyAttestedQuantity:z.number().int().nonnegative(),
 remainingQuantity:z.number().int().nonnegative(),
 evidenceCurrent:z.boolean(),externalWmsVerified:z.literal(false),
 inventoryPosted:z.literal(false),verifiedCogsReversal:z.literal(false)
})
const page=z.object({
 eventId:uuid,coverage:z.array(coverage),receipts:z.array(intake).max(50),
 hasMore:z.boolean(),nextBeforeAt:z.string().nullable(),
 nextBeforeId:uuid.nullable(),externalWarehouseReconciled:z.literal(false),
 realizedNetProfitVerified:z.literal(false)
})
const audit=z.object({action:z.enum(['REQUESTED','ATTESTED_LOCAL','REJECTED']),
 actorId:uuid,note:z.string(),occurredAt:z.string()})
export type LocalReturnIntake=z.infer<typeof intake>
export type LocalReturnCursor={beforeAt:string;beforeId:string}
const root=(mp:string,event:string)=>
 '/api/v1/finance/marketplaces/'+encodeURIComponent(mp)+'/events/'+
 encodeURIComponent(event)+'/return-intakes'
async function parsed<T>(response:Response,schema:z.ZodType<T>):Promise<T>{
 if(!response.ok)throw Error('本地退货实收核查 HTTP '+response.status)
 return schema.parse(await response.json())
}
export async function getLocalReturnIntakes(mp:string,event:string,
 cursor?:LocalReturnCursor) {
 const p=new URLSearchParams()
 if(cursor){p.set('beforeAt',cursor.beforeAt);p.set('beforeId',cursor.beforeId)}
 const u=root(mp,event)+(cursor?'?'+p:'')
 const r=await fetch(u,{credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json'}})
 return parsed(r,page)
}
export async function requestLocalReturnIntake(mp:string,event:string,
 payload:{claimId:string;warehouseId:string;receiptReference:string;
 quantity:number;condition:'SELLABLE_CLAIMED'|'UNSELLABLE_CLAIMED'|'UNKNOWN';
 note:string}) {
 const r=await fetch(root(mp,event),{method:'POST',credentials:'same-origin',
 cache:'no-store',headers:{...(await csrfHeader()),Accept:'application/json',
 'Content-Type':'application/json'},body:JSON.stringify(payload)})
 return parsed(r,intake)
}
export async function decideLocalReturnIntake(mp:string,event:string,id:string,
 action:'attest'|'reject',reason:string) {
 const r=await fetch(root(mp,event)+'/'+encodeURIComponent(id)+'/'+action,{
 method:'POST',credentials:'same-origin',cache:'no-store',
 headers:{...(await csrfHeader()),Accept:'application/json',
 'Content-Type':'application/json'},body:JSON.stringify({reason})})
 return parsed(r,intake)
}
export async function auditLocalReturnIntake(mp:string,event:string,id:string){
 const r=await fetch(root(mp,event)+'/'+encodeURIComponent(id)+'/audit',{
 credentials:'same-origin',cache:'no-store',headers:{Accept:'application/json'}})
 return parsed(r,z.array(audit).max(100))
}
