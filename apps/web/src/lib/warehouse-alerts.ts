import {z} from 'zod'
import {csrfHeader} from './auth'

const base='/api/v1/purchasing/warehouse-alerts'
const policy=z.object({warehouseId:z.string().uuid(),productSkuId:z.string().uuid(),
 reorderPoint:z.number().int().nonnegative(),targetStock:z.number().int().positive(),
 updatedAt:z.string()})
const advice=z.object({policy,internalSku:z.string(),onHandQuantity:z.number().int().nullable(),
 state:z.enum(['NO_LEDGER','LEDGER_ANOMALY','LOW_STOCK','HEALTHY']),
 suggestedQuantity:z.number().int().nullable(),
 approvedUnreceivedQuantity:z.number().int().nonnegative()})
const finding=z.object({id:z.string().uuid(),warehouseId:z.string().uuid(),
 productSkuId:z.string().uuid(),internalSku:z.string(),
 ruleCode:z.enum(['LOCAL_LOW_STOCK','LOCAL_LEDGER_NEGATIVE','LOCAL_COUNT_VARIANCE']),
 state:z.enum(['OPEN','ACKNOWLEDGED','DONE','CLEARED']),lastSeenAt:z.string()})
const scan=z.object({id:z.string().uuid(),triggerType:z.literal('MANUAL'),
 matchedCount:z.number().int().nonnegative(),clearedCount:z.number().int().nonnegative()})
export type WarehouseAdvice=z.infer<typeof advice>
export type WarehouseFinding=z.infer<typeof finding>
export type WarehouseFindingState='OPEN'|'ACKNOWLEDGED'|'DONE'
async function api<T>(url:string,schema:z.ZodType<T>,method='GET',body?:unknown):Promise<T>{
 const response=await fetch(url,{method,credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json',...(method==='GET'?{}:await csrfHeader()),
   ...(body===undefined?{}:{'Content-Type':'application/json'})},
  ...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(!response.ok){
  if(response.status===403)throw Error('仅当前租户 Owner 可配置仓库告警')
  if(response.status===404)throw Error('当前仓库、SKU 或告警不可用')
  if(response.status===409)throw Error('告警状态已变化，请刷新后重新确认')
  throw Error('本地仓库告警 HTTP '+response.status)
 }
 return response.status===204?undefined as T:schema.parse(await response.json())
}
export const fetchWarehouseAdvice=(warehouseId:string)=>api(
 base+'/'+encodeURIComponent(warehouseId)+'/advice',z.array(advice))
export const saveWarehousePolicy=(warehouseId:string,productSkuId:string,
 reorderPoint:number,targetStock:number)=>api(
 base+'/'+encodeURIComponent(warehouseId)+'/policies',policy,'POST',
 {productSkuId,reorderPoint,targetStock})
export const deleteWarehousePolicy=(warehouseId:string,productSkuId:string)=>api(
 base+'/'+encodeURIComponent(warehouseId)+'/policies/'+encodeURIComponent(productSkuId),
 z.unknown(),'DELETE')
export const fetchWarehouseFindings=(warehouseId:string,filter:'ACTIVE'|'ALL')=>api(
 base+'/'+encodeURIComponent(warehouseId)+'/findings?filter='+filter,z.array(finding))
export const scanWarehouseAlerts=()=>api(base+'/scan',scan,'POST')
export const changeWarehouseFinding=(id:string,state:WarehouseFindingState)=>api(
 base+'/findings/'+encodeURIComponent(id)+'/state',
 z.object({id:z.string().uuid(),state:z.enum(['OPEN','ACKNOWLEDGED','DONE'])}),
 'POST',{state})
