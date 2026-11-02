import {z} from 'zod'
import {merchivraFetch} from './merchivra-mutator'

const uuid=z.string().uuid()
const count=z.number().int().nonnegative()
const day=z.string().regex(/^\d{4}-\d{2}-\d{2}$/)
export const exceptionAuditDaySchema=z.object({
 day,registered:count,resolvedActions:count,reopenActions:count,
 assignedActions:count,acknowledgementActions:count,
 evidenceRefreshActions:count
})
export const exceptionAuditActorSchema=z.object({
 userId:uuid,displayName:z.string(),resolvedActions:count,
 reopenedActions:count,assignedActions:count,acknowledgedActions:count,
 meanFirstResolutionHours:z.number().nonnegative().nullable()
})
export const exceptionRecurrenceSchema=z.object({
 lineId:uuid,shipmentId:uuid,shipmentReference:z.string(),sellerSku:z.string(),
 state:z.enum(['OPEN','ACKNOWLEDGED','RESOLVED']),reopenActions:count,
 evidenceRefreshActions:count,
 lastReopenedAt:z.string().datetime({offset:true}).nullable()
})
export const exceptionAuditHistorySchema=z.object({
 marketplaceId:uuid,fromDay:day,throughDay:day,dayCount:z.literal(30),
 timezone:z.literal('UTC'),origin:z.literal('LOCAL_AUDIT'),
 amazonVerified:z.literal(false),fbaStockCredited:z.literal(false),
 summary:z.object({
  registered:count,auditedActions:count,resolvedActions:count,
  reopenActions:count,affectedByReopen:count,evidenceRefreshActions:count
 }),
 daily:z.array(exceptionAuditDaySchema).length(30),
 actors:z.array(exceptionAuditActorSchema).max(100),actorsTruncated:z.boolean(),
 recurrences:z.array(exceptionRecurrenceSchema).max(20),
 recurrencesTruncated:z.boolean()
})
export type ExceptionAuditHistory=z.infer<typeof exceptionAuditHistorySchema>
export async function getExceptionAuditHistory(marketplaceId:string) {
 const result=await merchivraFetch<{data:unknown}>(
  '/api/v1/exception-insights/marketplaces/'+encodeURIComponent(marketplaceId)+'/history',
  {method:'GET'})
 return exceptionAuditHistorySchema.parse(result.data)
}
