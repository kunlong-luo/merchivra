import {z} from 'zod'
import {merchivraFetch} from './merchivra-mutator'

const uuid=z.string().uuid()
const digest=z.string().regex(/^[0-9a-f]{64}$/)
const positive=z.number().int().nonnegative()
const iso=z.string().datetime({offset:true})
const state=z.enum(['OPEN','ACKNOWLEDGED','RESOLVED'])
const action=z.enum(['ASSIGN','ACKNOWLEDGE','RESOLVE','REOPEN','SET_DUE'])
const policy=z.object({
 enabled:z.boolean(),leadHours:z.number().int().min(1).max(168),
 defaultDueHours:z.number().int().min(1).max(720),version:positive
})
export const workItemSchema=z.object({
 lineId:uuid,shipmentId:uuid,shipmentReference:z.string(),sellerSku:z.string(),
 findingCode:z.string(),severity:z.enum(['HIGH','WARNING','INFO']),
 fingerprint:digest,state,assigneeId:uuid.nullable(),dueAt:iso.nullable(),
 reminder:z.enum(['NONE','DISABLED','OVERDUE','DUE_SOON','RECHECK']),
 stale:z.boolean(),version:positive,updatedAt:iso.nullable()
})
export const workbenchSchema=z.object({
 truncated:z.boolean(),shipmentScope:positive,
 amazonVerified:z.literal(false),fbaStockCredited:z.literal(false),
 policy,assignees:z.array(z.object({userId:uuid,displayName:z.string()})).max(100),
 items:z.array(workItemSchema).max(500)
})
const history=z.object({
 action,oldState:state.nullable(),newState:state,
 oldAssignee:uuid.nullable(),newAssignee:uuid.nullable(),
 oldDueAt:iso.nullable(),newDueAt:iso.nullable(),
 oldFingerprint:digest.nullable(),newFingerprint:digest,
 note:z.string(),actorId:uuid,occurredAt:iso
})
const policyAudit=z.object({
 newVersion:positive,oldEnabled:z.boolean().nullable(),newEnabled:z.boolean(),
 oldLeadHours:positive.nullable(),newLeadHours:positive,
 oldDefaultDueHours:positive.nullable(),newDefaultDueHours:positive,
 actorId:uuid,note:z.string(),occurredAt:iso
})
export type ExceptionItem=z.infer<typeof workItemSchema>
export type ExceptionBoard=z.infer<typeof workbenchSchema>
export type ExceptionAction=z.infer<typeof action>
export type SavePolicy={enabled:boolean;leadHours:number;defaultDueHours:number;
 expectedVersion:number;note:string}
export type ActBody={action:ExceptionAction;expectedVersion:number;
 expectedFingerprint:string;assigneeId:string|null;dueAt:string|null;note:string}
export type BulkSelection={lineId:string;expectedVersion:number;
 expectedFingerprint:string}
const root=(marketplace:string)=>'/api/v1/fba/marketplaces/'+
 encodeURIComponent(marketplace)+'/purchase-reconciliation/workbench'
async function get<T>(url:string,schema:z.ZodType<T>):Promise<T>{
 const res=await merchivraFetch<{data:unknown}>(url,{method:'GET'})
 return schema.parse(res.data)
}
async function post<T>(url:string,body:unknown,schema:z.ZodType<T>):Promise<T>{
 const res=await merchivraFetch<{data:unknown}>(url,{
  method:'POST',headers:{'Content-Type':'application/json'},
  body:JSON.stringify(body)})
 return schema.parse(res.data)
}
export const getExceptionBoard=(marketplace:string)=>
 get(root(marketplace),workbenchSchema)
export const postExceptionAction=(marketplace:string,lineId:string,body:ActBody)=>
 post(root(marketplace)+'/cases/'+encodeURIComponent(lineId)+'/actions',
  body,workItemSchema)
export const bulkAcknowledge=(marketplace:string,requestKey:string,
 lines:BulkSelection[],note:string)=>
 post(root(marketplace)+'/bulk-acknowledge',{requestKey,lines,note},
  z.object({requestKey:uuid,acknowledgedCount:z.number().int().min(0).max(50)}))
export const getExceptionHistory=(marketplace:string,lineId:string)=>
 get(root(marketplace)+'/cases/'+encodeURIComponent(lineId)+'/history',
  z.array(history).max(100))
export const saveReminderPolicy=(marketplace:string,body:SavePolicy)=>
 post(root(marketplace)+'/policy',body,policy)
export const getReminderPolicyHistory=(marketplace:string)=>
 get(root(marketplace)+'/policy/history',z.array(policyAudit).max(100))
