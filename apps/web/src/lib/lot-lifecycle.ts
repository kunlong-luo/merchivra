import {z} from 'zod'
import {
 actReleasedLotPosition,listReleasedLotActionHistory,
 listReleasedLotActionHistoryPage,getReleasedLotLineage,listReleasedLotExpiryAlerts
} from '../generated/purchasing-v114'
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
const actionPage=z.object({
 items:z.array(action).max(100),nextCursor:z.string().min(1).nullable(),
 hasMore:z.boolean(),pageSize:z.number().int().min(1).max(100)
}).refine(p=>p.hasMore===(p.nextCursor!==null),{
 message:'V49 批次操作历史游标与分页状态不一致'
})
export type LotActionHistoryPage=z.infer<typeof actionPage>
export type LotExpiryAlert=z.infer<typeof alert>
export type LotActionRequest={
 requestKey:string;actionType:LotLifecycleAction['actionType'];
 expectedBinId:string;destinationBinId:string|null;quantity:number;expectedRemaining:number;
 expectedOnHand:number|null;observedQuantity:number|null;reason:string
}
// Orval uses reviewed Spring DTO contracts and the shared CSRF/no-store
// request transport. Dynamic paths must first be exact UUID values.
const pathId=(id:string)=>z.string().uuid().parse(id)
const decode=<T>(promise:Promise<{data:unknown}>,schema:z.ZodType<T>):Promise<T>=>
 promise.then(r=>schema.parse(r.data))
export const actLotPosition=(positionId:string,body:LotActionRequest)=>
 decode(actReleasedLotPosition(pathId(positionId),body),action)
export const fetchLotActionHistory=(positionId:string)=>
 decode(listReleasedLotActionHistory(pathId(positionId)),z.array(action))
export const fetchLotActionHistoryPage=(positionId:string,cursor:string|null=null)=>
 decode(listReleasedLotActionHistoryPage(pathId(positionId),{
  limit:50,...(cursor?{cursor}:{})
 }),actionPage)
export const fetchLotExpiryAlerts=(warehouseId:string)=>
 decode(listReleasedLotExpiryAlerts(pathId(warehouseId)),z.array(alert))
export const fetchLotLineage=(positionId:string)=>
 decode(getReleasedLotLineage(pathId(positionId)),z.array(lineage))
