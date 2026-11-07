import {z} from 'zod'
import {csrfHeader} from './auth'
const id=z.string().uuid()
const resolution=z.object({
 id,resolutionType:z.enum(['RELEASED_SURPLUS_BIND','TRANSFER_SHORTAGE']),
 countReviewId:id.nullable(),transferActionId:id.nullable(),
 sourceReceiptId:id.nullable(),positionId:id,warehouseId:id,productSkuId:id,
 expectedBinId:id,expectedPositionQuantity:z.number().int().nonnegative(),
 expectedOnHand:z.number().int().nonnegative(),
 adjustmentQuantity:z.number().int().positive(),
 evidenceReference:z.string(),reason:z.string(),
 status:z.enum(['PENDING_REVIEW','APPLIED','REJECTED']),
 proposedBy:id,reviewedBy:id.nullable(),reviewReason:z.string().nullable(),
 operationId:id.nullable()
})
export type LotExceptionResolution=z.infer<typeof resolution>
export type NewLotException={
 requestKey:string;resolutionType:LotExceptionResolution['resolutionType'];
 sourceId:string;sourceReceiptId:string|null;evidenceReference:string;reason:string
}
async function api<T>(path:string,schema:z.ZodType<T>,method='GET',body?:unknown):Promise<T>{
 const res=await fetch('/api/v1/purchasing/lot-exceptions'+path,{
  method,credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json',...(method==='GET'?{}:await csrfHeader()),
   ...(body===undefined?{}:{'Content-Type':'application/json'})},
  ...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(!res.ok) {
  if(res.status===403)throw Error('需要当前租户 Owner 审核权限')
  if(res.status===404)throw Error('库存异常、收货来源或仓库不属于当前租户')
  if(res.status===409)throw Error('库存数量、库位或来源证据已经变化，必须刷新后重新审核')
  throw Error('V51 异常纠正 API HTTP '+res.status)
 }
 return schema.parse(await res.json())
}
export const fetchLotExceptions=(warehouseId:string)=>api(
 '/warehouses/'+encodeURIComponent(warehouseId),z.array(resolution))
const pageSchema=z.object({items:z.array(resolution).max(100),
 nextCursor:z.string().min(1).nullable(),hasMore:z.boolean(),
 pageSize:z.number().int().min(1).max(100)
}).refine(p=>p.hasMore===(p.nextCursor!==null),{message:'Invalid lot resolution cursor'})
export type LotExceptionFilter='PENDING'|'ALL'
export const fetchLotExceptionsPage=(warehouseId:string,filter:LotExceptionFilter='PENDING',
 cursor:string|null=null)=>{
 const params=new URLSearchParams({filter,limit:'50'})
 if(cursor)params.set('cursor',cursor)
 return api('/warehouses/'+encodeURIComponent(warehouseId)+'/page?'+params.toString(),
  pageSchema)
}
export const proposeLotException=(body:NewLotException)=>
 api('',resolution,'POST',body)
export const decideLotException=(id:string,body:{
 decision:'APPROVE'|'REJECT';reason:string
})=>api('/'+encodeURIComponent(id)+'/decision',resolution,'POST',body)
