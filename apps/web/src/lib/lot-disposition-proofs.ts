import {z} from 'zod'
import {csrfHeader} from './auth'
const id=z.string().uuid()
const proof=z.object({
 id,dispositionId:id,
 proofKind:z.enum(['DISPOSAL_CERTIFICATE','SUPPLIER_RETURN_RECEIPT']),
 evidenceReference:z.string(),documentSha256:z.string().nullable(),
 explanation:z.string(),submittedBy:id,
 status:z.enum(['PENDING_REVIEW','METADATA_REVIEWED','REJECTED']),
 reviewedBy:id.nullable(),reviewReason:z.string().nullable()
})
export type LotDispositionProof=z.infer<typeof proof>
export type NewDispositionProof={
 requestKey:string;dispositionId:string;
 proofKind:LotDispositionProof['proofKind'];
 evidenceReference:string;documentSha256:string|null;explanation:string
}
async function api<T>(path:string,schema:z.ZodType<T>,method='GET',body?:unknown):Promise<T>{
 const response=await fetch('/api/v1/purchasing/lot-disposition-proofs'+path,{
  method,credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json',...(method==='GET'?{}:await csrfHeader()),
   ...(body===undefined?{}:{'Content-Type':'application/json'})},
  ...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(!response.ok){
  if(response.status===403)throw Error('需要当前租户 Owner 凭据复核权限')
  if(response.status===404)throw Error('处置凭据不属于当前租户')
  if(response.status===409)throw Error('处置凭据已经审核或来源状态不符，请刷新')
  throw Error('V51 处置凭据 API HTTP '+response.status)
 }
 return schema.parse(await response.json())
}
export const fetchDispositionProofs=(warehouseId:string)=>api(
 '/warehouses/'+encodeURIComponent(warehouseId),z.array(proof))
export const submitDispositionProof=(body:NewDispositionProof)=>api(
 '',proof,'POST',body)
export const decideDispositionProof=(id:string,body:{
 decision:'REVIEW'|'REJECT';reason:string
})=>api('/'+encodeURIComponent(id)+'/decision',proof,'POST',body)
