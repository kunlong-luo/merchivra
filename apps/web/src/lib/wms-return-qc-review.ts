import {z} from 'zod'
import {csrfHeader} from './auth'

const uuid=z.string().uuid()
const condition=z.enum(['SELLABLE_CLAIMED','UNSELLABLE_CLAIMED','UNKNOWN'])
const comparison=z.enum(['QC_MATCHED_UNVERIFIED','QC_QUANTITY_MISMATCH',
 'QC_CONDITION_MISMATCH','QC_BOTH_MISMATCH'])
const hold=z.enum(['STALE_LOCAL_HOLD','REVOKED_SOURCE_HOLD','QC_REJECTED_HOLD',
 'QC_PENDING_HOLD','QC_SIGNED_MISMATCH_HOLD','LOCAL_SIGNED_MISMATCH_HOLD',
 'OPERATOR_IDENTITY_UNVERIFIED_HOLD'])
const review=z.object({
 id:uuid,observationId:uuid,externalReceiptId:z.string(),
 externalQuantity:z.number().int(),externalCondition:condition,
 localQuantity:z.number().int(),localCondition:condition,
 qcQuantity:z.number().int(),qcCondition:condition,qcComparison:comparison,
 evidenceReference:z.string(),explanation:z.string(),requestedBy:uuid,
 requestedAt:z.string(),state:z.enum(['PENDING','ACKNOWLEDGED','REJECTED']),
 decidedBy:uuid.nullable(),decidedAt:z.string().nullable(),
 decisionReason:z.string().nullable(),evidenceCurrent:z.boolean(),
 sourceActive:z.boolean(),operationalHold:hold,
 operatorIdentityVerified:z.literal(false),physicalReceiptVerified:z.literal(false),
 inventoryPosted:z.literal(false),cogsReversalEligible:z.literal(false),
 actualCogsReversal:z.null()
})
export type WmsQcReview=z.infer<typeof review>
export type WmsQcCondition=z.infer<typeof condition>
const path=(mp:string,event:string,intake:string)=>
 '/api/v1/finance/marketplaces/'+encodeURIComponent(mp)+'/events/'+
 encodeURIComponent(event)+'/return-intakes/'+encodeURIComponent(intake)+'/wms-qc-review'
async function checked(response:Response) {
 if(!response.ok)throw new Error('WMS 手工 QC 复核 HTTP '+response.status)
 const body=await response.text()
 return body?review.parse(JSON.parse(body)):null
}
export async function readWmsQcReview(mp:string,event:string,intake:string) {
 return checked(await fetch(path(mp,event,intake),{credentials:'same-origin',
  cache:'no-store',headers:{Accept:'application/json'}}))
}
export async function requestWmsQcReview(mp:string,event:string,intake:string,
 payload:{qcQuantity:number;qcCondition:WmsQcCondition;
 evidenceReference:string;explanation:string}) {
 return checked(await fetch(path(mp,event,intake),{method:'POST',
  credentials:'same-origin',cache:'no-store',
  headers:{...(await csrfHeader()),Accept:'application/json',
   'Content-Type':'application/json'},body:JSON.stringify(payload)}))
}
export async function decideWmsQcReview(mp:string,event:string,intake:string,
 caseId:string,acknowledge:boolean,reason:string) {
 return checked(await fetch(path(mp,event,intake)+'/'+encodeURIComponent(caseId)+
  (acknowledge?'/acknowledge':'/reject'),{method:'POST',
   credentials:'same-origin',cache:'no-store',
   headers:{...(await csrfHeader()),Accept:'application/json',
    'Content-Type':'application/json'},body:JSON.stringify({reason})}))
}
