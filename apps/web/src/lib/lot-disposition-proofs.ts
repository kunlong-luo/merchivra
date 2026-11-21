import {z} from 'zod'
import {
 listLocalDispositionProofs,submitLocalDispositionProof,
 decideLocalDispositionProof
} from '../generated/purchasing-v114'
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
// V118 JSON metadata only. V52 private file upload remains multipart with
// explicit MIME/size, SHA-256 and its existing separate CSRF implementation.
const pathId=(value:string)=>id.parse(value)
const decode=<T>(res:Promise<{data:unknown}>,schema:z.ZodType<T>):Promise<T>=>
 res.then(r=>schema.parse(r.data))
export const fetchDispositionProofs=(warehouseId:string)=>
 decode(listLocalDispositionProofs(pathId(warehouseId)),z.array(proof))
export const submitDispositionProof=(body:NewDispositionProof)=>
 decode(submitLocalDispositionProof(body),proof)
export const decideDispositionProof=(proofId:string,body:{
 decision:'REVIEW'|'REJECT';reason:string
})=>decode(decideLocalDispositionProof(pathId(proofId),body),proof)
