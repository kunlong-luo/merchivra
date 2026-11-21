import {z} from 'zod'
import {
 listPurchaseVariancesLegacy,listPurchaseVariancesPage,reportPurchaseVariance as requestVariance,
 changePurchaseVariance,listSupplierClaimsLegacy,listSupplierClaimsPage,
 listSupplierClaimSourcesPage,createLocalSupplierClaim,changeLocalSupplierClaim,
 listSupplierVarianceEventsLegacy,listSupplierVarianceEventsPage,
 listSupplierClaimEventsLegacy,listSupplierClaimEventsPage,
 listSupplierDisputePerformance
} from '../generated/purchasing-v114'

const variance=z.object({
 id:z.string().uuid(),orderId:z.string().uuid(),lineId:z.string().uuid(),
 asnId:z.string().uuid().nullable(),
 kind:z.enum(['PO_SHORTAGE','ASN_SHORTAGE','OVERAGE_UNPOSTED']),
 expectedQuantity:z.number().int().nonnegative(),observedQuantity:z.number().int().nonnegative(),
 varianceQuantity:z.number().int().positive(),reason:z.string(),
 state:z.enum(['OPEN','REVIEWED','CLOSED']),createdBy:z.string().uuid(),
 reviewedBy:z.string().uuid().nullable(),createdAt:z.string()
})
const claim=z.object({
 id:z.string().uuid(),orderId:z.string().uuid(),supplierId:z.string().uuid(),
 varianceId:z.string().uuid().nullable(),inspectionId:z.string().uuid().nullable(),
 requestedQuantity:z.number().int().positive(),
 remedy:z.enum(['REPLACEMENT','CREDIT_REQUEST']),reason:z.string(),
 status:z.enum(['DRAFT','SUBMITTED','APPROVED','CLOSED','CANCELLED']),
 createdBy:z.string().uuid(),approvedBy:z.string().uuid().nullable(),
 resolutionNote:z.string().nullable(),createdAt:z.string()
})
const performance=z.object({
 supplierId:z.string().uuid(),supplierName:z.string(),
 orderCount:z.number().int().nonnegative(),orderedUnits:z.number().int().nonnegative(),
 receivedUnits:z.number().int().nonnegative(),rejectedUnits:z.number().int().nonnegative(),
 openDiscrepancies:z.number().int().nonnegative(),openClaims:z.number().int().nonnegative(),
 overdueAsns:z.number().int().nonnegative()
})
const event=z.object({id:z.string().uuid(),eventType:z.string(),
 actorUserId:z.string().uuid(),note:z.string().nullable(),createdAt:z.string()})
export type PurchaseVariance=z.infer<typeof variance>
export type SupplierClaim=z.infer<typeof claim>
export type SupplierPerformance=z.infer<typeof performance>
export type ReportVariance={
 requestKey:string;lineId:string;kind:PurchaseVariance['kind'];
 asnId:string|null;reportedExcessQuantity:number;reason:string
}
export type CreateSupplierClaim={
 requestKey:string;varianceId:string|null;inspectionId:string|null;
 requestedQuantity:number;remedy:'REPLACEMENT'|'CREDIT_REQUEST';reason:string
}

// Source-backed V118 dispute contracts; response DTO and cursor invariants
// remain runtime-checked, rather than treating typed generated code as proof.
const pathId=(id:string)=>z.string().uuid().parse(id)
const decode=<T>(res:Promise<{data:unknown}>,schema:z.ZodType<T>):Promise<T>=>
 res.then(r=>schema.parse(r.data))
export type CaseFilter='ACTIONABLE'|'ALL'
const claimSource=z.object({
 sourceType:z.enum(['VARIANCE','QC']),sourceId:z.string().uuid(),
 maxQuantity:z.number().int().positive(),createdAt:z.string()
})
export type SupplierClaimSource=z.infer<typeof claimSource>
const cursorPage=<T extends z.ZodTypeAny>(item:T)=>z.object({
 items:z.array(item).max(100),
 nextCursor:z.string().min(1).nullable(),hasMore:z.boolean(),
 pageSize:z.number().int().min(1).max(100)
}).refine(v=>v.hasMore===(v.nextCursor!==null),{
 message:'无效的业务游标分页响应'
})
const paging=(cursor:string|null)=>({limit:50,...(cursor?{cursor}:{})})
export const fetchPurchaseVariancesPage=(orderId:string,filter:CaseFilter,
 cursor:string|null=null)=>
 decode(listPurchaseVariancesPage(pathId(orderId),{...paging(cursor),filter}),cursorPage(variance))
export const fetchSupplierClaimsPage=(orderId:string,filter:CaseFilter,
 cursor:string|null=null)=>
 decode(listSupplierClaimsPage(pathId(orderId),{...paging(cursor),filter}),cursorPage(claim))
export const fetchSupplierClaimSourcesPage=(orderId:string,cursor:string|null=null)=>
 decode(listSupplierClaimSourcesPage(pathId(orderId),paging(cursor)),cursorPage(claimSource))
export const fetchSupplierCaseEventsPage=(id:string,type:'variances'|'claims',
 cursor:string|null=null)=>
 decode(type==='variances'?
  listSupplierVarianceEventsPage(pathId(id),paging(cursor)):
  listSupplierClaimEventsPage(pathId(id),paging(cursor)),cursorPage(event))

export const fetchPurchaseVariances=(orderId:string)=>
 decode(listPurchaseVariancesLegacy(pathId(orderId)),z.array(variance))
export const reportPurchaseVariance=(orderId:string,body:ReportVariance)=>
 decode(requestVariance(pathId(orderId),body),variance)
export const actPurchaseVariance=(id:string,action:'review'|'close')=>
 decode(changePurchaseVariance(pathId(id),action),variance)
export const fetchSupplierClaims=(orderId:string)=>
 decode(listSupplierClaimsLegacy(pathId(orderId)),z.array(claim))
export const createSupplierClaim=(body:CreateSupplierClaim)=>
 decode(createLocalSupplierClaim(body),claim)
export const actSupplierClaim=(id:string,action:'submit'|'approve'|'close'|'cancel',
 note?:string)=>
 decode(changeLocalSupplierClaim(pathId(id),action,
  action==='close'?{note:note??''}:undefined),claim)
export const fetchSupplierPerformance=()=>
 decode(listSupplierDisputePerformance(),z.array(performance))
export const fetchSupplierCaseEvents=(id:string,type:'variances'|'claims')=>
 decode(type==='variances'?listSupplierVarianceEventsLegacy(pathId(id)):
  listSupplierClaimEventsLegacy(pathId(id)),z.array(event))
