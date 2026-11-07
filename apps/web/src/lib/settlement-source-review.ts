import {z} from 'zod'
import {financeAuditPage,financeAuditUrl} from './finance-audit-pages'
import {csrfHeader} from './auth'

export const sourceReview=z.object({
 state:z.enum(['NOT_REQUESTED','PENDING','APPROVED','REJECTED','STALE']),
 sourceState:z.enum(['REPORT_DETAIL_INCOMPLETE','REPORT_INTERNAL_DIFFERENCE',
   'CURRENCY_CONFLICT','NO_FINANCES_SETTLEMENT_REFERENCE',
   'SOURCE_TOTAL_DIFFERENCE','SOURCE_AMOUNTS_AGREE_UNVERIFIED']),
 requestedBy:z.string().uuid().nullable(),decidedBy:z.string().uuid().nullable(),
 requestedAt:z.string().nullable(),decidedAt:z.string().nullable(),
 version:z.number().int().nonnegative(),evidenceCurrent:z.boolean(),
 bankVerified:z.literal(false),ledgerPosted:z.literal(false)
})
export const sourceReviewAudit=z.object({
 action:z.enum(['REQUESTED','REOPENED','APPROVED','REJECTED']),
 sourceState:sourceReview.shape.sourceState,
 actorId:z.string().uuid(),reason:z.string().min(10).max(500),
 version:z.number().int().positive(),occurredAt:z.string()
})
export type SourceReview=z.infer<typeof sourceReview>

const root=(mp:string,report:string)=>
 '/api/v1/finance/marketplaces/'+encodeURIComponent(mp)+
 '/reports/'+encodeURIComponent(report)+'/source-review'

async function call<T>(url:string,schema:z.ZodType<T>,
  method:'GET'|'POST'='GET',reason?:string):Promise<T>{
 const response=await fetch(url,{
  method,credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json',
   ...(method==='POST'?await csrfHeader():{}),
   ...(reason===undefined?{}:{'Content-Type':'application/json'})},
  ...(reason===undefined?{}:{body:JSON.stringify({reason})})
 })
 if(!response.ok)throw new Error(
  response.status===403?'需要另一位有权限的 Owner 操作；不能审批自己的申请':
  response.status===409?'审核已变化、来源发生变化或金额不满足审核条件，请刷新再操作':
  response.status===400?'审核说明需 10–500 字，不得含控制字符':
  response.status===404?'报告不存在或不属于当前站点':
  '来源人工审核失败 HTTP '+response.status)
 return schema.parse(await response.json())
}
export const getSourceReview=(mp:string,report:string)=>
 call(root(mp,report),sourceReview)
export const getSourceReviewAudit=(mp:string,report:string)=>
 call(root(mp,report)+'/audit',z.array(sourceReviewAudit).max(100))
export const requestSourceReview=(mp:string,report:string,reason:string)=>
 call(root(mp,report)+'/request',sourceReview,'POST',reason)
export const decideSourceReview=(mp:string,report:string,
 action:'approve'|'reject',reason:string)=>
 call(root(mp,report)+'/'+action,sourceReview,'POST',reason)

export const getSourceReviewAuditPage=(mp:string,report:string,cursor:string|null=null)=>
 call(financeAuditUrl(root(mp,report),cursor),financeAuditPage(sourceReviewAudit))
