import {z} from 'zod'
import {csrfHeader} from './auth'
const base='/api/v1/finance'
export const eventSchema=z.object({
 id:z.string().uuid(),marketplaceId:z.string().uuid(),
 sourceFamily:z.enum(['MANUAL','SPAPI_FINANCES']),
 eventKey:z.string(),dataOrigin:z.enum(['MANUAL_UNVERIFIED','LIVE']),
 category:z.string(),amount:z.string(),currency:z.string(),
 postedAt:z.string(),externalOrderId:z.string().nullable(),sourceType:z.string(),
 reviewStatus:z.enum(['REVIEW_REQUIRED','MATCHED','UNMATCHED']),
 reviewedOrderId:z.string().uuid().nullable()
})
const bucketSchema=z.object({dataOrigin:z.string(),currency:z.string(),
 category:z.string(),amount:z.string(),eventCount:z.number().int()})
const reviewSchema=z.object({eventId:z.string().uuid(),state:z.string(),
 orderId:z.string().uuid().nullable()})
export type FinanceEvent=z.infer<typeof eventSchema>
export type FinanceBucket=z.infer<typeof bucketSchema>

async function request<T>(url:string,schema:z.ZodType<T>,method='GET',body?:unknown):Promise<T>{
 const res=await fetch(url,{method,credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json',...(method==='GET'?{}:await csrfHeader()),
  ...(body===undefined?{}:{'Content-Type':'application/json'})},
  ...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(!res.ok){
  if(res.status===401)throw Error('登录已过期')
  if(res.status===403)throw Error('当前账号没有财务操作权限')
  if(res.status===404)throw Error('站点、订单或财务事件不可访问')
  if(res.status===409)throw Error('来源事件重复且内容不同，或审核对象发生变化')
  throw Error('财务接口 HTTP '+res.status)
 }
 return schema.parse(await res.json())
}
export function fetchFinanceEvents(marketplaceId:string,cursor?:{
 beforeAt:string;beforeId:string
}){
 const params=new URLSearchParams({limit:'50'})
 if(cursor){params.set('beforeAt',cursor.beforeAt);params.set('beforeId',cursor.beforeId)}
 return request(base+'/marketplaces/'+encodeURIComponent(marketplaceId)+
  '/events?'+params,z.array(eventSchema))
}
export function fetchFinanceBuckets(marketplaceId:string){
 return request(base+'/marketplaces/'+encodeURIComponent(marketplaceId)+
  '/buckets',z.array(bucketSchema))
}
export type ManualFinanceEvent={
 marketplaceId:string;eventKey:string;category:string;amount:string;currency:string;
 postedAt:string;externalOrderId:string|null;sourceType:string
}
export function createManualFinanceEvent(event:ManualFinanceEvent){
 return request(base+'/manual-events',eventSchema,'POST',event)
}
export function reviewFinanceEvent(eventId:string,marketplaceId:string,orderId:string|null){
 return request(base+'/events/'+encodeURIComponent(eventId)+'/review',reviewSchema,'POST',
  {marketplaceId,orderId})
}

const breakdownSchema=z.object({
 path:z.string(),parent:z.string().nullable(),sourceType:z.string(),
 amount:z.string().nullable(),currency:z.string().nullable(),leaf:z.boolean()
})
export type FinanceBreakdown=z.infer<typeof breakdownSchema>
export function fetchFinanceBreakdowns(marketplaceId:string,eventId:string){
 return request(base+'/marketplaces/'+encodeURIComponent(marketplaceId)+
  '/events/'+encodeURIComponent(eventId)+'/breakdowns',z.array(breakdownSchema))
}
