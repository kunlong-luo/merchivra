import {z} from 'zod'
import {csrfHeader} from './auth'
const policy=z.object({marketplaceId:z.string().uuid(),sellerSku:z.string(),
 reorderPoint:z.number().int(),targetStock:z.number().int(),leadTimeDays:z.number().int(),
 updatedAt:z.string()})
const advice=z.object({policy,observationQuality:z.string().nullable(),
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
