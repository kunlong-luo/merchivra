import {z} from 'zod'
import {csrfHeader} from './auth'
const root='/api/v1/purchasing/lot-lifecycle'
const action=z.object({
 id:z.string().uuid(),actionType:z.enum(['MOVE','TRANSFER','EXPIRED_QUARANTINE','COUNT_OBSERVATION']),
 sourcePositionId:z.string().uuid(),sourceWarehouseId:z.string().uuid(),
 sourceBinId:z.string().uuid(),destinationWarehouseId:z.string().uuid().nullable(),
 destinationBinId:z.string().uuid().nullable(),
 destinationPositionId:z.string().uuid().nullable(),operationId:z.string().uuid().nullable(),
 quantity:z.number().int().nonnegative(),expectedRemaining:z.number().int().nonnegative(),
 expectedOnHand:z.number().int().nonnegative().nullable(),
 observedQuantity:z.number().int().nonnegative().nullable(),
 reason:z.string(),actorUserId:z.string().uuid(),createdAt:z.string()
})
const alert=z.object({positionId:z.string().uuid(),lotCode:z.string(),
 binCode:z.string(),productSkuId:z.string().uuid(),
 remainingQuantity:z.number().int().positive(),expiryDate:z.string(),
 urgency:z.enum(['EXPIRED','NEAR_EXPIRY'])})
const lineage=z.object({positionId:z.string().uuid(),
 originPositionId:z.string().uuid().nullable(),receiptId:z.string().uuid(),
 warehouseId:z.string().uuid(),binCode:z.string(),lotCode:z.string(),
 placedQuantity:z.number().int().positive(),remainingQuantity:z.number().int().nonnegative(),
 expiryDate:z.string().nullable(),generation:z.number().int().nonnegative()})
export type LotLineage=z.infer<typeof lineage>
export type LotLifecycleAction=z.infer<typeof action>
export type LotExpiryAlert=z.infer<typeof alert>
export type LotActionRequest={
 requestKey:string;actionType:LotLifecycleAction['actionType'];
 expectedBinId:string;destinationBinId:string|null;quantity:number;expectedRemaining:number;
 expectedOnHand:number|null;observedQuantity:number|null;reason:string
}
async function api<T>(url:string,schema:z.ZodType<T>,method='GET',body?:unknown):Promise<T>{
 const response=await fetch(url,{method,credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json',...(method==='GET'?{}:await csrfHeader()),
   ...(body===undefined?{}:{'Content-Type':'application/json'})},
  ...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(!response.ok){
  if(response.status===403)throw Error('此库位操作需要当前租户 Owner 权限')
  if(response.status===404)throw Error('来源库位、仓库或批次不可访问')
  if(response.status===409)throw Error('批次、目标库位或仓库库存已变化；请刷新后重试')
  throw Error('批次生命周期操作失败 HTTP '+response.status)
 }
 return schema.parse(await response.json())
}
export const actLotPosition=(positionId:string,body:LotActionRequest)=>api(
 root+'/positions/'+encodeURIComponent(positionId)+'/actions',action,'POST',body)
export const fetchLotActionHistory=(positionId:string)=>api(
 root+'/positions/'+encodeURIComponent(positionId)+'/history',z.array(action))
export const fetchLotExpiryAlerts=(warehouseId:string)=>api(
 root+'/warehouses/'+encodeURIComponent(warehouseId)+'/expiry-alerts',z.array(alert))

export const fetchLotLineage=(positionId:string)=>api(
 root+'/positions/'+encodeURIComponent(positionId)+'/lineage',z.array(lineage))
