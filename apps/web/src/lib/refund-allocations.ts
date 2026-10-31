import {z} from 'zod'
import {csrfHeader} from './auth'
const entry=z.object({
 itemId:z.string(),signedAmount:z.string(),currency:z.string(),
 evidenceReference:z.string(),revision:z.number().int().positive()
})
const summary=z.object({
 status:z.enum(['SOURCE_REFUND_NOT_READY','CURRENCY_MISMATCH',
  'SIGNED_DIRECTION_MISMATCH','OVER_ALLOCATED','NO_ITEM_ALLOCATIONS',
  'PARTIALLY_ALLOCATED','SIGNED_TOTAL_MATCHES_UNVERIFIED']),
 currency:z.string(),sourceRefundAmount:z.string().nullable(),
 allocatedAmount:z.string().nullable(),remainingAmount:z.string().nullable(),
 itemCount:z.number().int().nonnegative(),
 inventoryReceiptVerified:z.literal(false),accountingPosted:z.literal(false)
})
const view=z.object({
 eventId:z.string().uuid(),orderId:z.string().uuid(),currency:z.string(),
 reviewedFinancesRefund:z.boolean(),inventoryReceiptVerified:z.literal(false),
 summary,allocatedItems:z.array(entry)
})
const audit=z.object({
 itemId:z.string(),action:z.enum(['CREATED','UPDATED','WITHDRAWN']),
 signedAmount:z.string(),currency:z.string(),evidenceReference:z.string(),
 revision:z.number().int().positive(),actorId:z.string().uuid(),occurredAt:z.string()
})
export type RefundAmountView=z.infer<typeof view>
export type RefundAmountAudit=z.infer<typeof audit>
const endpoint=(mp:string,event:string)=>'/api/v1/finance/marketplaces/'+
 encodeURIComponent(mp)+'/events/'+encodeURIComponent(event)+'/refund-allocations'
async function call<T>(url:string,schema:z.ZodType<T>|null,
 method='GET',body?:unknown):Promise<T> {
 const r=await fetch(url,{method,credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json',...(method==='GET'?{}:await csrfHeader()),
   ...(body===undefined?{}:{'Content-Type':'application/json'})},
  ...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(!r.ok)throw Error(r.status===409?
  '退款分类、商品数量审核或原币金额不满足归属要求（也可能超额分配）':
  r.status===404?'来源订单项或退款事件不存在':
  r.status===403?'没有退款金额归属审核权限':
  '退款金额审核 HTTP '+r.status)
 return schema?schema.parse(await r.json()):undefined as T
}
export const getRefundAllocations=(mp:string,event:string)=>
 call(endpoint(mp,event),view)
export const saveRefundAllocation=(mp:string,event:string,input:{
 itemId:string;signedAmount:string;currency:string;evidenceReference:string
})=>call(endpoint(mp,event),view,'POST',input)
export const removeRefundAllocation=(mp:string,event:string,itemId:string)=>
 call(endpoint(mp,event)+'/'+encodeURIComponent(itemId),null,'DELETE')
export const listRefundAllocationAudit=(mp:string,event:string)=>
 call(endpoint(mp,event)+'/audit',z.array(audit))
