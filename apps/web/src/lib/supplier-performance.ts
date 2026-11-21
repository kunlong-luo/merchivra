import {z} from 'zod'
import {
 listSupplierEvidenceScorecardsLegacy,listSupplierEvidenceScorecardsPage,
 listPurchaseFollowupsLegacy,listPurchaseFollowupsPage,
 scanPurchaseFollowups as requestScan,changePurchaseFollowupState
} from '../generated/purchasing-v114'

const root='/api/v1/purchasing/supplier-performance'
const percent=z.string().regex(/^\d{1,3}\.\d$/).nullable()
const scorecard=z.object({
 supplierId:z.string().uuid(),supplierName:z.string(),
 completeAsnSampleCount:z.number().int().nonnegative(),
 observedOnTimeAsnCount:z.number().int().nonnegative(),
 inspectedReceiptSampleCount:z.number().int().nonnegative(),
 acceptedQcUnits:z.number().int().nonnegative(),inspectedQcUnits:z.number().int().nonnegative(),
 activeVarianceCount:z.number().int().nonnegative(),
 observedOnTimePercent:percent,inspectedAcceptancePercent:percent,
 evidenceScore:percent,scoringStatus:z.enum(['INSUFFICIENT_EVIDENCE','PROVISIONAL'])
})
const followup=z.object({
 id:z.string().uuid(),orderId:z.string().uuid(),orderNumber:z.string(),
 lineId:z.string().uuid(),internalSku:z.string(),supplierName:z.string(),
 ruleCode:z.enum(['ASN_COVERAGE_GAP','ASN_NOT_DISPATCHED']),
 state:z.enum(['OPEN','ACKNOWLEDGED','DONE','CLEARED']),approvedAt:z.string(),
 orderedQuantity:z.number().int().nonnegative(),
 receivedQuantity:z.number().int().nonnegative(),
 activeAsnQuantity:z.number().int().nonnegative(),
 uncoveredQuantity:z.number().int().nonnegative(),lastSeenAt:z.string()
})
const scan=z.object({id:z.string().uuid(),triggerType:z.literal('MANUAL'),
 matchedCount:z.number().int().nonnegative(),clearedCount:z.number().int().nonnegative()})
const change=z.object({id:z.string().uuid(),
 state:z.enum(['OPEN','ACKNOWLEDGED','DONE'])})
export type SupplierScorecard=z.infer<typeof scorecard>
export type PurchaseFollowup=z.infer<typeof followup>
export type FollowupState='OPEN'|'ACKNOWLEDGED'|'DONE'

// V120: same-origin generated Orval transports share Session/CSRF/no-store,
 // while Zod keeps strict runtime evidence and cursor checks.
const pathId=(value:string)=>z.string().uuid().parse(value)
const decode=<T>(response:Promise<{data:unknown}>,schema:z.ZodType<T>):Promise<T>=>
 response.then(r=>schema.parse(r.data))
const page=<T extends z.ZodTypeAny>(item:T)=>z.object({
 items:z.array(item).max(100),nextCursor:z.string().min(1).nullable(),
 hasMore:z.boolean(),pageSize:z.number().int().min(1).max(100)
}).refine(x=>x.hasMore===(x.nextCursor!==null),{
 message:'供应商分页游标与状态不一致'
})
export const fetchSupplierScorecardsPage=(cursor:string|null=null)=>
 decode(listSupplierEvidenceScorecardsPage({limit:50,...(cursor?{cursor}:{})}),page(scorecard))
export const fetchPurchaseFollowupsPage=(filter:'ACTIVE'|'ALL'='ACTIVE',
 cursor:string|null=null)=>
 decode(listPurchaseFollowupsPage({filter,limit:50,...(cursor?{cursor}:{})}),page(followup))
export const fetchSupplierScorecards=()=>
 decode(listSupplierEvidenceScorecardsLegacy(),z.array(scorecard).max(200))
export const fetchPurchaseFollowups=(filter:'ACTIVE'|'ALL'='ACTIVE')=>
 decode(listPurchaseFollowupsLegacy({filter}),z.array(followup).max(100))
export const scanPurchaseFollowups=()=>
 decode(requestScan(),scan)
export const setPurchaseFollowupState=(id:string,state:FollowupState)=>
 decode(changePurchaseFollowupState(pathId(id),{state}),change)
