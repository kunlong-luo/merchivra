import {z} from 'zod'
import {csrfHeader} from './auth'
import {purchaseDetailSchema} from './purchasing'
const policy=z.object({marketplaceId:z.string().uuid(),sellerSku:z.string(),
 reorderPoint:z.number().int(),targetStock:z.number().int(),leadTimeDays:z.number().int(),
 updatedAt:z.string()})
const advice=z.object({policy,observationId:z.string().uuid().nullable(),
 observationQuality:z.string().nullable(),
 fulfillable:z.number().int().nullable(),reserved:z.number().int().nullable(),
 observedAt:z.string().nullable(),state:z.enum(['NO_OBSERVATION','STALE_OBSERVATION',
 'INCOMPLETE_OBSERVATION','REORDER','HEALTHY']),suggestedQuantity:z.number().int().nullable()})
export type ReplenishmentAdvice=z.infer<typeof advice>
async function api<T>(marketplace:string,action:string,schema:z.ZodType<T>,
 method='GET',body?:unknown):Promise<T>{
 const res=await fetch('/api/v1/replenishment/marketplaces/'+encodeURIComponent(marketplace)+action,
  {method,credentials:'same-origin',cache:'no-store',headers:{
    Accept:'application/json',...(method==='GET'?{}:await csrfHeader()),
    ...(body===undefined?{}:{'Content-Type':'application/json'})},
    ...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(!res.ok){
  if(res.status===403)throw Error('仅 Owner 可配置补货建议')
  if(res.status===404)throw Error('Listing 或补货策略未找到')
  if(res.status===409)throw Error('建议、映射或库存观测已变化，请刷新后重新确认；或该请求编号已被使用')
  throw Error('补货接口 HTTP '+res.status)
 }
 return res.status===204?undefined as T:schema.parse(await res.json())
}
export const getReplenishmentPolicies=(marketplace:string)=>
 api(marketplace,'/policies',z.array(policy))
export const getReplenishmentSuggestions=(marketplace:string)=>
 api(marketplace,'/suggestions',z.array(advice))
export const saveReplenishmentPolicy=(marketplace:string,sellerSku:string,
 reorderPoint:number,targetStock:number,leadTimeDays:number)=>
 api(marketplace,'/policies',policy,'POST',{sellerSku,reorderPoint,targetStock,leadTimeDays})
export const removeReplenishmentPolicy=(marketplace:string,sellerSku:string)=>
 api(marketplace,'/policies?sellerSku='+encodeURIComponent(sellerSku),z.unknown(),'DELETE')

const draftOrigin=z.object({purchaseOrderId:z.string().uuid(),orderNumber:z.string(),
 status:z.string(),sellerSku:z.string(),productSkuId:z.string().uuid(),
 fulfillable:z.number().int(),suggestedQuantity:z.number().int(),currency:z.string(),
 unitCost:z.string(),observedAt:z.string(),createdAt:z.string()})
export type ReplenishmentDraftOrigin=z.infer<typeof draftOrigin>
export type ReplenishmentDraftRequest={
 requestKey:string;sellerSku:string;supplierId:string;currency:string;unitCost:string;
 expectedPolicyUpdatedAt:string;expectedObservationId:string;expectedQuantity:number
}
export const getReplenishmentPurchaseDrafts=(marketplace:string)=>
 api(marketplace,'/purchase-drafts',z.array(draftOrigin))
export const createReplenishmentPurchaseDraft=(marketplace:string,body:ReplenishmentDraftRequest)=>
 api(marketplace,'/purchase-drafts',purchaseDetailSchema,'POST',body)
