import {z} from 'zod'
import {csrfHeader} from './auth'

const base='/api/v1/purchasing/disputes'
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

async function api<T>(url:string,schema:z.ZodType<T>,method='GET',body?:unknown):Promise<T>{
 const response=await fetch(url,{method,credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json',...(method==='GET'?{}:await csrfHeader()),
    ...(body===undefined?{}:{'Content-Type':'application/json'})},
  ...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(!response.ok){
  if(response.status===403)throw Error('仅当前租户 Owner 可以处理供应商索赔')
  if(response.status===404)throw Error('采购单、差异或索赔来源不可用')
  if(response.status===409)throw Error('采购实收、来源或双人审批状态已变化，请刷新再审阅')
  throw Error('本地采购差异 API HTTP '+response.status)
 }
 return schema.parse(await response.json())
}
export type CaseFilter='ACTIONABLE'|'ALL'
const claimSource=z.object({
 sourceType:z.enum(['VARIANCE','QC']),sourceId:z.string().uuid(),
 maxQuantity:z.number().int().positive(),createdAt:z.string()
})
export type SupplierClaimSource=z.infer<typeof claimSource>
const cursorPage=<T extends z.ZodType>(item:T)=>z.object({
 items:z.array(item).max(100),
 nextCursor:z.string().min(1).nullable(),hasMore:z.boolean(),
 pageSize:z.number().int().min(1).max(100)
}).refine(v=>v.hasMore===(v.nextCursor!==null),{
 message:'无效的业务游标分页响应'
})
const pageUrl=(path:string,cursor:string|null,filter?:CaseFilter)=>{
 const params=new URLSearchParams({limit:'50',...(filter?{filter}:{})})
 if(cursor)params.set('cursor',cursor)
 return base+path+'/page?'+params.toString()
}
export const fetchPurchaseVariancesPage=(orderId:string,filter:CaseFilter,
 cursor:string|null=null)=>api(
 pageUrl('/orders/'+encodeURIComponent(orderId)+'/variances',cursor,filter),
 cursorPage(variance))
export const fetchSupplierClaimsPage=(orderId:string,filter:CaseFilter,
 cursor:string|null=null)=>api(
 pageUrl('/orders/'+encodeURIComponent(orderId)+'/claims',cursor,filter),
 cursorPage(claim))
/** The backend excludes claimed sources across ALL rows, including cancelled. */
export const fetchSupplierClaimSourcesPage=(orderId:string,
 cursor:string|null=null)=>api(
 pageUrl('/orders/'+encodeURIComponent(orderId)+'/claim-sources',cursor),
 cursorPage(claimSource))
export const fetchSupplierCaseEventsPage=(id:string,type:'variances'|'claims',
 cursor:string|null=null)=>api(
 pageUrl('/'+type+'/'+encodeURIComponent(id)+'/events',cursor),
 cursorPage(event))

export const fetchPurchaseVariances=(orderId:string)=>api(
 base+'/orders/'+encodeURIComponent(orderId)+'/variances',z.array(variance))
export const reportPurchaseVariance=(orderId:string,body:ReportVariance)=>api(
 base+'/orders/'+encodeURIComponent(orderId)+'/variances',variance,'POST',body)
export const actPurchaseVariance=(id:string,action:'review'|'close')=>api(
 base+'/variances/'+encodeURIComponent(id)+'/'+action,variance,'POST')
export const fetchSupplierClaims=(orderId:string)=>api(
 base+'/orders/'+encodeURIComponent(orderId)+'/claims',z.array(claim))
export const createSupplierClaim=(body:CreateSupplierClaim)=>api(
 base+'/claims',claim,'POST',body)
export const actSupplierClaim=(id:string,action:'submit'|'approve'|'close'|'cancel',
 note?:string)=>api(base+'/claims/'+encodeURIComponent(id)+'/'+action,
 claim,'POST',action==='close'?{note}:undefined)
export const fetchSupplierPerformance=()=>api(
 base+'/supplier-performance',z.array(performance))
export const fetchSupplierCaseEvents=(id:string,type:'variances'|'claims')=>api(
 base+'/'+type+'/'+encodeURIComponent(id)+'/events',z.array(event))
