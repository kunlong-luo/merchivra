import {z} from 'zod'
import {csrfHeader} from './auth'

const base='/api/v1/purchasing/lot-locations'
const bin=z.object({id:z.string().uuid(),warehouseId:z.string().uuid(),
 code:z.string(),purpose:z.enum(['QC_HOLD','STORAGE']),
 status:z.enum(['ACTIVE','ARCHIVED'])})
const piece=z.object({id:z.string().uuid(),receiptId:z.string().uuid(),
 warehouseId:z.string().uuid(),productSkuId:z.string().uuid(),
 binId:z.string().uuid(),pieceCode:z.string(),quantity:z.number().int().positive()})
const pressure=z.object({warehouseId:z.string().uuid(),productSkuId:z.string().uuid(),
 internalSku:z.string(),usableUnits:z.number().int(),
 pendingQcUnits:z.number().int().nonnegative(),
 acceptedAwaitingReleaseUnits:z.number().int().nonnegative(),
 declaredAsnInTransitUnits:z.number().int().nonnegative()})
export type StockBin=z.infer<typeof bin>
export type ReceiptLotPiece=z.infer<typeof piece>
export type ReceiptSupplyPressure=z.infer<typeof pressure>
export type SplitReceiptLot={
 requestKey:string;pieceId:string;quantity:number;pieceCode:string;
 destinationBinId:string|null;reason:string
}
export type MoveReceiptLot={
 requestKey:string;pieceId:string;expectedBinId:string;
 destinationBinId:string;reason:string
}
async function api<T>(url:string,schema:z.ZodType<T>,method='GET',body?:unknown):Promise<T>{
 const response=await fetch(url,{method,credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json',...(method==='GET'?{}:await csrfHeader()),
   ...(body===undefined?{}:{'Content-Type':'application/json'})},
  ...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(!response.ok){
  if(response.status===403)throw Error('库位操作需要当前租户 Owner 权限')
  if(response.status===404)throw Error('仓库、待检库位或批次不属于当前租户')
  if(response.status===409)throw Error('批次数量或当前库位已变化，请刷新后重试')
  throw Error('本地待检批次库位操作失败 HTTP '+response.status)
 }
 return schema.parse(await response.json())
}
export const fetchStockBins=(warehouseId:string)=>api(
 base+'/warehouses/'+encodeURIComponent(warehouseId)+'/bins',z.array(bin))
export const createStockBin=(warehouseId:string,code:string,purpose:'QC_HOLD'|'STORAGE')=>api(
 base+'/warehouses/'+encodeURIComponent(warehouseId)+'/bins',bin,'POST',{code,purpose})
export const fetchReceiptLotPieces=(receiptId:string)=>api(
 base+'/receipts/'+encodeURIComponent(receiptId)+'/pieces',z.array(piece))
export const splitReceiptLot=(receiptId:string,body:SplitReceiptLot)=>api(
 base+'/receipts/'+encodeURIComponent(receiptId)+'/split',z.array(piece),'POST',body)
export const moveReceiptLot=(receiptId:string,body:MoveReceiptLot)=>api(
 base+'/receipts/'+encodeURIComponent(receiptId)+'/move',z.array(piece),'POST',body)
export const fetchReceiptSupplyPressure=(warehouseId:string)=>api(
 base+'/warehouses/'+encodeURIComponent(warehouseId)+'/pressure',z.array(pressure))
