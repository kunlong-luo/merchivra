import {z} from 'zod'
import {csrfHeader} from './auth'
const uuid=z.string().uuid()
const source=z.object({
 id:uuid,warehouseId:uuid,sourceCode:z.string(),state:z.enum([
  'PENDING','APPROVED','REVOKED']),requestedBy:uuid,decidedBy:uuid.nullable(),
 operatorIdentityVerified:z.literal(false)
})
const receipt=z.object({
 id:uuid,intakeId:uuid,sourceId:uuid,externalReceiptId:z.string(),
 observedAtUtc:z.string(),quantity:z.number().int().positive(),
 condition:z.enum(['UNKNOWN','SELLABLE_CLAIMED','UNSELLABLE_CLAIMED']),
 canonicalSha256:z.string().regex(/^[a-f0-9]{64}$/),
 signatureValid:z.literal(true),operatorIdentityVerified:z.literal(false),
 physicalReceiptVerified:z.literal(false),inventoryPosted:z.literal(false),
 cogsReversalEligible:z.literal(false),actualCogsReversal:z.null()
})
export type SigningSource=z.infer<typeof source>
export type WmsSignedEvidence=z.infer<typeof receipt>
const root='/api/v1/finance/wms-signing-sources'
const signed=(mp:string,event:string,intake:string)=>
 '/api/v1/finance/marketplaces/'+encodeURIComponent(mp)+'/events/'+
 encodeURIComponent(event)+'/return-intakes/'+encodeURIComponent(intake)+'/signed-wms'
async function parsed<T>(r:Response,schema:z.ZodType<T>):Promise<T>{
 if(!r.ok)throw new Error('签名仓储来源 HTTP '+r.status)
 return schema.parse(await r.json())
}
async function post<T>(url:string,payload:unknown,schema:z.ZodType<T>){
 const r=await fetch(url,{method:'POST',credentials:'same-origin',cache:'no-store',
 headers:{...(await csrfHeader()),Accept:'application/json',
 'Content-Type':'application/json'},body:JSON.stringify(payload)})
 return parsed(r,schema)
}
export async function fetchWmsSources(warehouseId:string){
 const r=await fetch(root+'?warehouseId='+encodeURIComponent(warehouseId),{
  credentials:'same-origin',cache:'no-store',headers:{Accept:'application/json'}})
 return parsed(r,z.array(source).max(100))
}
export function registerWmsSource(payload:{warehouseId:string;sourceCode:string;
 publicKeySpkiBase64:string;reason:string}){
 return post(root,payload,source)
}
export function decideWmsSource(id:string,action:'approve'|'revoke',reason:string){
 return post(root+'/'+encodeURIComponent(id)+'/'+action,{reason},source)
}
export async function fetchSignedWmsReceipts(mp:string,event:string,intake:string){
 const r=await fetch(signed(mp,event,intake),{credentials:'same-origin',
 cache:'no-store',headers:{Accept:'application/json'}})
 return parsed(r,z.array(receipt).max(10))
}
export function importWmsReceipt(mp:string,event:string,intake:string,payload:{
 sourceId:string;externalReceiptId:string;observedAt:string;
 quantity:number;condition:'UNKNOWN'|'SELLABLE_CLAIMED'|'UNSELLABLE_CLAIMED';
 signatureBase64:string}){
 return post(signed(mp,event,intake),payload,receipt)
}
