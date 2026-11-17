import {z} from 'zod'
import {csrfHeader} from './auth'

const uuid=z.string().uuid()
const condition=z.enum(['SELLABLE_CLAIMED','UNSELLABLE_CLAIMED','UNKNOWN'])
const result=z.object({
 intakeId:uuid,localQuantity:z.number().int().positive(),
 localCondition:condition,localReference:z.string(),
 reconciliation:z.enum(['NO_EVIDENCE','MATCHED_UNVERIFIED','QUANTITY_MISMATCH',
  'CONDITION_MISMATCH','BOTH_MISMATCH']),
 operationalHold:z.enum(['PENDING_EXTERNAL_EVIDENCE','STALE_LOCAL_HOLD',
  'REVOKED_SOURCE_HOLD','MISMATCH_QUARANTINE_HOLD','IDENTITY_UNVERIFIED_HOLD']),
 externalReceiptId:z.string().nullable(),externalQuantity:z.number().int().nullable(),
 externalCondition:condition.nullable(),quantityDelta:z.number().int().nullable(),
 sourceCode:z.string().nullable(),sourceActive:z.boolean(),
 evidenceCurrent:z.boolean(),signatureValid:z.boolean(),
 operatorIdentityVerified:z.literal(false),physicalReceiptVerified:z.literal(false),
 inventoryPosted:z.literal(false),cogsReversalEligible:z.literal(false),
 actualCogsReversal:z.null()
})
export type WmsReconciliation=z.infer<typeof result>
export type WmsObservedCondition=z.infer<typeof condition>
const path=(mp:string,event:string,intake:string)=>
 '/api/v1/finance/marketplaces/'+encodeURIComponent(mp)+'/events/'+
 encodeURIComponent(event)+'/return-intakes/'+encodeURIComponent(intake)+'/wms-reconciliation'
async function checked(r:Response){
 if(!r.ok)throw new Error('外部签名差异核对 HTTP '+r.status)
 return result.parse(await r.json())
}
export async function readWmsReconciliation(mp:string,event:string,intake:string){
 const response=await fetch(path(mp,event,intake),{
  credentials:'same-origin',cache:'no-store',headers:{Accept:'application/json'}})
 return checked(response)
}
export async function importWmsObservation(mp:string,event:string,intake:string,
 payload:{sourceId:string;externalReceiptId:string;observedAt:string;
 externalQuantity:number;externalCondition:WmsObservedCondition;
 signatureBase64:string}) {
 const response=await fetch(path(mp,event,intake),{
  method:'POST',credentials:'same-origin',cache:'no-store',
  headers:{...(await csrfHeader()),Accept:'application/json',
   'Content-Type':'application/json'},body:JSON.stringify(payload)})
 return checked(response)
}
export function canonicalWmsObservation(tenant:string,mp:string,event:string,
 intake:{id:string;warehouseId:string;sourceReference:string;
 quantity:number;condition:string},sourceId:string,externalRef:string,
 externalQuantity:number,externalCondition:string,observedAt:string) {
 const date=new Date(observedAt)
 if(!Number.isFinite(date.getTime()))return ''
 const stamp=date.toISOString().replace(/\.000Z$/,'Z')
 return ['MERCHIVRA_WMS_OBSERVATION_V2',tenant,mp,event,intake.id,
  intake.warehouseId,sourceId,externalRef,intake.sourceReference,
  String(intake.quantity),intake.condition,String(externalQuantity),
  externalCondition,stamp,''].join('\n')
}
