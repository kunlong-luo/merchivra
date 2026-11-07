import {z} from 'zod'
import {
 listOperationFindings,getOperationsSummary,scanOperationsNow,
 setOperationFindingState,claimOperationFinding,releaseOperationFinding,
 snoozeOperationFinding,unsnoozeOperationFinding,getOperationFindingAttentionAudit
} from '../generated/operations-v67'

export const findingSchema=z.object({
 id:z.string().uuid(),marketplaceId:z.string().uuid(),
 ruleCode:z.enum(['UNMAPPED_LISTING','INVENTORY_UNKNOWN','LOW_STOCK',
  'REFUND_REVIEW','FBA_INBOUND_REVIEW','ADS_REPORT_REVIEW',
  'FBA_PO_EVIDENCE_CHANGED','FBA_PO_EXCEPTION_OVERDUE']),
 sourceKey:z.string(),title:z.string(),
 severity:z.enum(['INFO','WARNING','CRITICAL']),
 state:z.enum(['OPEN','ACKNOWLEDGED','DONE','CLEARED']),
 dataOrigin:z.enum(['MOCK','UNVERIFIED','LIVE']),
 lastSeenAt:z.string(),
 assignedTo:z.string().uuid().nullable(),
 snoozedUntil:z.string().nullable()
})
export const summarySchema=z.object({
 open:z.number().int().nonnegative(),
 acknowledged:z.number().int().nonnegative(),
 done:z.number().int().nonnegative(),
 cleared:z.number().int().nonnegative(),
 snoozed:z.number().int().nonnegative()
})
export const scanSchema=z.object({
 id:z.string().uuid(),triggerType:z.literal('MANUAL'),
 matchedCount:z.number().int().nonnegative(),clearedCount:z.number().int().nonnegative()
})
const attentionSchema=z.object({
 id:z.string().uuid(),state:z.enum(['OPEN','ACKNOWLEDGED','DONE','CLEARED']),
 assignedTo:z.string().uuid().nullable(),snoozedUntil:z.string().nullable()
})
const attentionAudit=z.object({
 action:z.enum(['CLAIMED','RELEASED','SNOOZED','UNSNOOZED']),
 actorId:z.string().uuid(),oldAssignee:z.string().uuid().nullable(),
 newAssignee:z.string().uuid().nullable(),
 oldSnoozedUntil:z.string().nullable(),newSnoozedUntil:z.string().nullable(),
 note:z.string().nullable(),createdAt:z.string()
})
export type Finding=z.infer<typeof findingSchema>
export type FindingState='OPEN'|'ACKNOWLEDGED'|'DONE'
export type FindingFilter='ACTIVE'|'ACTIONABLE'|'ALL'
export const ruleNames:Record<Finding['ruleCode'],string>={
 UNMAPPED_LISTING:'Listing 映射待确认',
 INVENTORY_UNKNOWN:'FBA 库存数据不完整',
 LOW_STOCK:'FBA 可售库存偏低',
 REFUND_REVIEW:'退款订单待核对',
 FBA_INBOUND_REVIEW:'本地 FBA 货件入仓待复核',
 ADS_REPORT_REVIEW:'离线 Ads 日报待独立审核',
 FBA_PO_EVIDENCE_CHANGED:'采购/FBA 证据变化，待重新确认',
 FBA_PO_EXCEPTION_OVERDUE:'采购/FBA 本地处理已超期'
}
export const stateNames:Record<Finding['state'],string>={
 OPEN:'待处理',ACKNOWLEDGED:'处理中',DONE:'已处理',CLEARED:'条件已消失'
}
export const severityNames:Record<Finding['severity'],string>={
 INFO:'提示',WARNING:'注意',CRITICAL:'优先处理'
}
export const fetchFindings=async(filter:FindingFilter)=>
 z.array(findingSchema).max(100).parse((await listOperationFindings({filter})).data)
const findingsPageSchema=z.object({
 items:z.array(findingSchema).max(100),nextCursor:z.string().min(1).nullable(),
 hasMore:z.boolean(),pageSize:z.number().int().min(1).max(100)
}).refine(p=>p.hasMore===(p.nextCursor!==null),{message:'Invalid operations cursor page'})
/** V95: HTTP GET retains Owner Session; actions still use generated Orval+CSRF. */
export const fetchFindingsPage=async(filter:FindingFilter,cursor:string|null=null)=>{
 const params=new URLSearchParams({filter,limit:'50'})
 if(cursor)params.set('cursor',cursor)
 const res=await fetch('/api/v1/operations/findings/page?'+params.toString(),{
  credentials:'same-origin',cache:'no-store',headers:{Accept:'application/json'}
 })
 if(!res.ok)throw Error('运营待办分页 HTTP '+res.status)
 return findingsPageSchema.parse(await res.json())
}
export const fetchOpsSummary=async()=>summarySchema.parse((await getOperationsSummary()).data)
export const scanOperations=async()=>scanSchema.parse((await scanOperationsNow()).data)
export const changeFindingState=async(id:string,state:FindingState)=>
 z.object({id:z.string().uuid(),state:z.enum(['OPEN','ACKNOWLEDGED','DONE'])})
  .parse((await setOperationFindingState(id,{state})).data)
export const claimFinding=async(id:string)=>attentionSchema.parse((await claimOperationFinding(id)).data)
export const releaseFinding=async(id:string)=>attentionSchema.parse((await releaseOperationFinding(id)).data)
export const snoozeFinding=async(id:string,minutes:15|60|240|1440,note:string)=>
 attentionSchema.parse((await snoozeOperationFinding(id,{minutes,note})).data)
export const unsnoozeFinding=async(id:string)=>
 attentionSchema.parse((await unsnoozeOperationFinding(id)).data)
export const fetchFindingAttentionHistory=async(id:string)=>
 z.array(attentionAudit).max(100).parse((await getOperationFindingAttentionAudit(id)).data)
