import {z} from 'zod'
import {csrfHeader} from './auth'

const money=z.string().regex(/^-?[0-9]+(?:\.[0-9]+)?$/)
export const itemAllocation=z.object({
 eventId:z.string().uuid(),orderId:z.string().uuid(),
 state:z.enum(['NOT_STARTED','DRAFT','PENDING','APPROVED','REJECTED','STALE']),
 version:z.number().int().nonnegative(),currency:z.string().regex(/^[A-Z]{3}$/),
 requestedBy:z.string().uuid().nullable(),approvedBy:z.string().uuid().nullable(),
 evidenceCurrent:z.boolean(),financialSourceComplete:z.literal(false),
 bankVerified:z.literal(false),
 amounts:z.object({fullyAllocated:z.boolean(),residuals:z.array(z.object({
  category:z.enum(['PRODUCT_SALES','AMAZON_FEE','FBA_FEE']),
  currency:z.string().length(3),sourceTotal:money,allocated:money,
  remainder:money,exact:z.boolean()
 })).max(3)}),
 items:z.array(z.object({externalItemId:z.string(),sku:z.string(),
  cost:money,currency:z.string(),quantity:z.number().int().positive()})).max(100),
 lines:z.array(z.object({
  externalItemId:z.string(),category:z.string(),signedAmount:money,
  currency:z.string(),evidenceReference:z.string()
 })).max(300)
})
export type ItemAllocation=z.infer<typeof itemAllocation>
export const itemAllocationAudit=z.object({
 version:z.number().int().positive(),
 action:z.enum(['DRAFT_CREATED','LINE_SAVED','LINE_WITHDRAWN',
  'SUBMITTED','APPROVED','REJECTED','REOPENED']),
 itemId:z.string().nullable(),category:z.string().nullable(),
 signedAmount:money.nullable(),actor:z.string().uuid(),reason:z.string(),
 occurredAt:z.string()
})
const url=(mp:string,event:string)=>
 '/api/v1/finance/marketplaces/'+encodeURIComponent(mp)+
 '/item-allocations/'+encodeURIComponent(event)
const errors=(response:Response)=>
 response.status===403?'需要不同的 Owner 独立审核，草稿也只能由原编制人编辑':
 response.status===409?'订单项、财务事件、分类、金额或历史成本已变化；重新检查来源证据':
 response.status===404?'找不到当前站点对应的事件':
 response.status===400?'输入金额、来源编号或原因不符合要求':
 '逐项金额分配请求失败（HTTP '+response.status+'）'
export async function fetchItemAllocation(mp:string,event:string) {
 const r=await fetch(url(mp,event),{credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json'}})
 if(!r.ok)throw new Error(errors(r))
 return itemAllocation.parse(await r.json())
}
export async function fetchItemAllocationAudit(mp:string,event:string) {
 const r=await fetch(url(mp,event)+'/audit',{credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json'}})
 if(!r.ok)throw new Error(errors(r))
 return z.array(itemAllocationAudit).max(100).parse(await r.json())
}
export type AllocationAction='submit'|'approve'|'reject'|'reopen'
async function mutation(mp:string,event:string,path:string,method:'PUT'|'POST',
 body:Record<string,string|null>){
 const r=await fetch(url(mp,event)+path,{method,
  credentials:'same-origin',cache:'no-store',
  headers:{...(await csrfHeader()),Accept:'application/json',
   'Content-Type':'application/json'},body:JSON.stringify(body)})
 if(!r.ok)throw new Error(errors(r))
 return itemAllocation.parse(await r.json())
}
export async function saveItemAllocation(mp:string,event:string,
 input:{itemId:string;category:string;signedAmount:string|null;
 currency:string;evidenceReference:string;reason:string}){
 return mutation(mp,event,'/line','PUT',input)
}
export async function reviewItemAllocation(mp:string,event:string,
 action:AllocationAction,reason:string) {
 return mutation(mp,event,'/'+action,'POST',{reason})
}
