import {z} from 'zod'
import {csrfHeader} from './auth'
const disposition=z.enum(['RESTOCKED_CLAIMED','NOT_RESTOCKED','UNKNOWN'])
const item=z.object({
 itemId:z.string(),sellerSku:z.string().nullable(),
 orderedQuantity:z.number().int().nullable(),internalSku:z.string().nullable(),
 unitCost:z.string().nullable(),unitCurrency:z.string().nullable(),
 reviewedQuantity:z.number().int().nullable(),
 disposition:disposition.nullable(),evidenceReference:z.string().nullable(),
 revision:z.number().int().nullable(),restockedCostSensitivity:z.string().nullable()
})
const snapshot=z.object({
 eventId:z.string().uuid(),orderId:z.string().uuid(),
 externalOrderId:z.string(),refundSourceAmount:z.string(),currency:z.string(),
 status:z.enum(['MANUAL_REFUND_ITEM_REVIEW','REFUND_CLASSIFICATION_INCOMPLETE']),
 costReadiness:z.string(),inventoryReceiptVerified:z.literal(false),
 items:z.array(item)
})
const audit=z.object({
 itemId:z.string(),action:z.enum(['CREATED','UPDATED','WITHDRAWN']),
 quantity:z.number().int().positive(),disposition,
 evidenceReference:z.string(),revision:z.number().int().positive(),
 actorId:z.string().uuid(),timestamp:z.string()
})
export type RefundSnapshot=z.infer<typeof snapshot>
export type RefundReviewDisposition=z.infer<typeof disposition>

function root(mp:string,event:string) {
 return '/api/v1/finance/marketplaces/'+encodeURIComponent(mp)+
  '/events/'+encodeURIComponent(event)+'/refund-items'
}
async function req<T>(url:string,schema:z.ZodType<T>|null,
 method='GET',body?:unknown):Promise<T> {
 const response=await fetch(url,{method,cache:'no-store',credentials:'same-origin',
  headers:{Accept:'application/json',...(method==='GET'?{}:await csrfHeader()),
   ...(body===undefined?{}:{'Content-Type':'application/json'})},
  ...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(!response.ok)throw Error(response.status===409?
  '需先完成来源退款分类与订单审核关联，或审核数量超过原订单量':
  response.status===404?'退款来源或订单项不存在':
  response.status===403?'当前账号没有退款审核权限':
  '退款审核接口 HTTP '+response.status)
 return schema?schema.parse(await response.json()):undefined as T
}
export const getRefundSnapshot=(mp:string,event:string)=>
 req(root(mp,event),snapshot)
export const saveRefundItem=(mp:string,event:string,data:{
 itemId:string;quantity:number;disposition:RefundReviewDisposition;evidenceReference:string
})=>req(root(mp,event),snapshot,'POST',data)
export const withdrawRefundItem=(mp:string,event:string,itemId:string)=>
 req(root(mp,event)+'/'+encodeURIComponent(itemId),null,'DELETE')
export const getRefundAudit=(mp:string,event:string)=>
 req(root(mp,event)+'/audit',z.array(audit))
