import {z} from 'zod'
import {merchivraFetch} from './merchivra-mutator'
import {caseMetricsSchema,ownerWorkloadSchema} from './fba-purchase-exceptions'
import {findingSchema} from './operations'

const uuid=z.string().uuid()
const count=z.number().int().nonnegative()
const category=z.object({
 ruleCode:findingSchema.shape.ruleCode,
 open:count,acknowledged:count,done:count,cleared:count
})
const recommendation=z.object({
 lineId:uuid,shipmentId:uuid,
 shipmentReference:z.string(),sellerSku:z.string(),findingCode:z.string(),
 severity:z.enum(['HIGH','WARNING','INFO']),
 state:z.enum(['OPEN','ACKNOWLEDGED','RESOLVED']),
 reminder:z.enum(['NONE','DISABLED','OVERDUE','DUE_SOON','RECHECK']),
 assigneeId:uuid.nullable(),recommendedOwnerId:uuid.nullable(),
 dueAt:z.string().datetime({offset:true}).nullable(),
 priority:z.enum(['P0','P1','P2','P3']),
 reasonCode:z.enum([
  'SOURCE_CHANGED','PAST_DUE','INDEPENDENT_IDENTITY_REVIEW',
  'LOCAL_VARIANCE_REVIEW','DUE_SOON','HIGH_SEVERITY',
  'NEEDS_OWNER','SOURCE_WARNING','ROUTINE_FOLLOWUP'
 ]),
 nextStep:z.string().min(1)
})
export const exceptionInsightsSchema=z.object({
 marketplaceId:uuid,amazonVerified:z.literal(false),
 fbaStockCredited:z.literal(false),sourceTruncated:z.boolean(),
 shipmentScope:count,evidenceLines:count.max(500),
 actionableCount:count,recommendationsTruncated:z.boolean(),
 cases:caseMetricsSchema,workload:ownerWorkloadSchema,
 operations:z.array(category).max(8),
 recommendations:z.array(recommendation).max(30)
})
export type ExceptionInsights=z.infer<typeof exceptionInsightsSchema>
export type CaseAdvice=z.infer<typeof recommendation>

export async function getExceptionInsights(marketplaceId:string) {
 const response=await merchivraFetch<{data:unknown}>(
  '/api/v1/exception-insights/marketplaces/'+encodeURIComponent(marketplaceId),
  {method:'GET'})
 return exceptionInsightsSchema.parse(response.data)
}
