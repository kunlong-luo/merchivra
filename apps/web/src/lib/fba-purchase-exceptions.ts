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

/** V78: persisted human case SLAs, not Amazon delivery or customer promises. */
export const caseMetricsSchema=z.object({
 total:positive,open:positive,acknowledged:positive,resolved:positive,
 overdue:positive,meanResolutionHours:z.number().nonnegative().nullable()
})
export type ExceptionCaseMetrics=z.infer<typeof caseMetricsSchema>
export const getExceptionMetrics=(marketplace:string)=>
 get(root(marketplace)+'/metrics',caseMetricsSchema)

/** V79 reuses the V77 atomic/CSRF-protected batch ledger for ASSIGN. */
export const bulkAssignReceiptSchema=z.object({
 requestKey:uuid,assigneeId:uuid,assignedCount:z.number().int().min(1).max(50)
})
export const assignOwnerLoadSchema=z.object({
 userId:uuid,displayName:z.string(),
 open:positive,acknowledged:positive,overdue:positive
})
export const ownerWorkloadSchema=z.object({
 totalCases:positive,unassigned:positive,inactiveAssignee:positive,
 owners:z.array(assignOwnerLoadSchema).max(100)
})
export type OwnerWorkload=z.infer<typeof ownerWorkloadSchema>
export const getOwnerWorkload=(marketplace:string)=>
 get(root(marketplace)+'/workload',ownerWorkloadSchema)
export const bulkAssignCases=(marketplace:string,requestKey:string,
 assigneeId:string,lines:BulkSelection[],note:string)=>
 post(root(marketplace)+'/bulk-assign',{requestKey,assigneeId,lines,note},
  bulkAssignReceiptSchema)

/** V82: deliberately explicit human acknowledgement, never auto-close. */
export const bulkResolveReceiptSchema=z.object({
 requestKey:uuid,resolvedCount:z.number().int().min(1).max(50)
})
export const bulkResolveCases=(marketplace:string,requestKey:string,
 lines:BulkSelection[],note:string,confirmed:boolean)=>
 post(root(marketplace)+'/bulk-resolve',{requestKey,confirmed,lines,note},
  bulkResolveReceiptSchema)

/** V83: advisory dry run. The transaction MUST repeat all checks on submit. */
const resolveReason=z.enum([
 'READY','OUT_OF_SCOPE','CASE_NOT_REGISTERED','EVIDENCE_CHANGED',
 'VERSION_CONFLICT','NOT_ACKNOWLEDGED','NO_ASSIGNEE',
 'VARIANCE_REVIEW_REQUIRED','IDENTITY_REVIEW_REQUIRED'
])
export const resolvePreviewSchema=z.object({
 allEligible:z.boolean(),selectedCount:z.number().int().min(1).max(50),
 eligibleCount:z.number().int().min(0).max(50),
 amazonVerified:z.literal(false),fbaStockCredited:z.literal(false),
 lines:z.array(z.object({
  lineId:uuid,shipmentReference:z.string().nullable(),
  sellerSku:z.string().nullable(),state:state.nullable(),
  assigneeId:uuid.nullable(),currentVersion:positive,
  currentFingerprint:digest.nullable(),eligible:z.boolean(),
  reasonCode:resolveReason
 })).min(1).max(50)
})
export type ResolvePreview=z.infer<typeof resolvePreviewSchema>
export const preflightBulkResolve=(marketplace:string,lines:BulkSelection[])=>
 post(root(marketplace)+'/bulk-resolve/preflight',{lines},resolvePreviewSchema)

export const batchActivitySchema=z.object({
 requestKey:uuid,actionType:z.enum(['ACKNOWLEDGE','ASSIGN','RESOLVE']),
 actorId:uuid,affectedCount:z.number().int().min(1).max(50),
 createdAt:iso
})
export const getExceptionBatchActivities=(marketplace:string)=>
 get(root(marketplace)+'/batches',z.array(batchActivitySchema).max(50))

/** V84: exact FK-linked per-batch audited rows; older batches are incomplete. */
export const batchDetailSchema=z.object({
 batch:batchActivitySchema,lineageComplete:z.boolean(),
 linkedCount:z.number().int().nonnegative().max(50),
 linkedActions:z.array(z.object({
  lineId:uuid,shipmentReference:z.string(),sellerSku:z.string(),
  action:z.enum(['ACKNOWLEDGE','ASSIGN','RESOLVE']),
  actorId:uuid,newState:state,occurredAt:iso
 })).max(50)
})
export const getExceptionBatchDetail=(marketplace:string,requestKey:string)=>
 get(root(marketplace)+'/batches/'+encodeURIComponent(requestKey),batchDetailSchema)
