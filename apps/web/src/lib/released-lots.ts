import {z} from 'zod'
import {csrfHeader} from './auth'
const root='/api/v1/purchasing'
const grade=z.object({id:z.string().uuid(),receiptId:z.string().uuid(),
 pieceId:z.string().uuid(),acceptedQuantity:z.number().int().nonnegative(),
 rejectedQuantity:z.number().int().nonnegative(),reason:z.string(),
 inspectedBy:z.string().uuid(),createdAt:z.string()})
const inspection=z.object({id:z.string().uuid(),receiptId:z.string().uuid(),
 warehouseId:z.string().uuid(),productSkuId:z.string().uuid(),
 inspectedQuantity:z.number().int().positive(),acceptedQuantity:z.number().int().nonnegative(),
 rejectedQuantity:z.number().int().nonnegative(),reason:z.string(),
 inspectedBy:z.string().uuid(),createdAt:z.string()})
const position=z.object({id:z.string().uuid(),receiptId:z.string().uuid(),
 warehouseId:z.string().uuid(),productSkuId:z.string().uuid(),binId:z.string().uuid(),
 binCode:z.string(),lotCode:z.string(),placedQuantity:z.number().int().positive(),
 remainingQuantity:z.number().int().nonnegative(),expiryDate:z.string().nullable(),
 createdAt:z.string()})
const pickLine=z.object({positionId:z.string().uuid(),lotCode:z.string(),
 binCode:z.string(),quantity:z.number().int().positive(),
 expiryDate:z.string().nullable()})
const pick=z.object({id:z.string().uuid(),warehouseId:z.string().uuid(),
 productSkuId:z.string().uuid(),operationId:z.string().uuid(),
 policy:z.enum(['FIFO','FEFO']),quantity:z.number().int().positive(),
 expectedOnHand:z.number().int().nonnegative(),reason:z.string(),
 actorUserId:z.string().uuid(),allocations:z.array(pickLine)})
export type PieceGrade=z.infer<typeof grade>
export type LotPosition=z.infer<typeof position>
export type StockLotPick=z.infer<typeof pick>
export type LotPolicy='FIFO'|'FEFO'
async function api<T>(url:string,schema:z.ZodType<T>,method='GET',body?:unknown):Promise<T>{
 const res=await fetch(url,{method,credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json',...(method==='GET'?{}:await csrfHeader()),
   ...(body===undefined?{}:{'Content-Type':'application/json'})},
  ...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(!res.ok){
  if(res.status===403)throw Error('此操作需要当前租户的 Owner 权限')
  if(res.status===404)throw Error('收货、SKU、库位或来源记录不存在')
  if(res.status===409)throw Error('数量、批次或可用库存已变化；请刷新后核对')
  throw Error('批次库存 API HTTP '+res.status)
 }
 return schema.parse(await res.json())
}
export const fetchPieceGrades=(receiptId:string)=>api(
 root+'/piece-grades/receipts/'+encodeURIComponent(receiptId),z.array(grade))
export const gradePiece=(receiptId:string,body:{
 requestKey:string;pieceId:string;acceptedQuantity:number;rejectedQuantity:number;reason:string
})=>api(root+'/piece-grades/receipts/'+encodeURIComponent(receiptId),grade,'POST',body)
export const finalizePieceGrades=(receiptId:string,body:{requestKey:string;reason:string})=>api(
 root+'/piece-grades/receipts/'+encodeURIComponent(receiptId)+'/finalize',
 inspection,'POST',body)
export const fetchLotPositions=(warehouseId:string)=>api(
 root+'/released-lots/warehouses/'+encodeURIComponent(warehouseId)+'/positions',
 z.array(position))
export const putawayReleasedLot=(body:{requestKey:string;receiptId:string;binId:string;
 quantity:number;expiryDate:string|null;reason:string})=>api(
 root+'/released-lots/putaway',position,'POST',body)
export const previewLotPick=(warehouseId:string,productSkuId:string,policy:LotPolicy)=>api(
 root+'/released-lots/warehouses/'+encodeURIComponent(warehouseId)+
 '/skus/'+encodeURIComponent(productSkuId)+'/pick-preview?policy='+policy,
 z.array(position))
export const pickReleasedLots=(body:{requestKey:string;warehouseId:string;productSkuId:string;
 policy:LotPolicy;quantity:number;expectedOnHand:number;reason:string})=>api(
 root+'/released-lots/picks',pick,'POST',body)
