import {z} from 'zod'
import {
 listLocalStockBins,listLocalStockBinsPage,createLocalStockBin,
 listLocalReceiptLotPieces,listLocalReceiptLotPiecesPage,
 splitLocalReceiptLot,moveLocalReceiptLot,listLocalReceiptSupplyPressure,
 listLocalReceiptSupplyPressurePage
} from '../generated/purchasing-v114'

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
// V116 generated contracts; all dynamic path IDs validated before Orval
// interpolates them, and existing Zod still verifies server response payloads.
const pathId=(id:string)=>z.string().uuid().parse(id)
const decode=<T>(promise:Promise<{data:unknown}>,schema:z.ZodType<T>):Promise<T>=>
 promise.then(r=>schema.parse(r.data))
const cursorPageOf=<T extends z.ZodTypeAny>(item:T)=>z.object({
 items:z.array(item).max(100),nextCursor:z.string().min(1).nullable(),
 hasMore:z.boolean(),pageSize:z.number().int().min(1).max(100)
}).refine(p=>p.hasMore===(p.nextCursor!==null),{message:'Invalid page cursor'})
const piecesPageSchema=z.object({
 items:z.array(piece).max(100),nextCursor:z.string().min(1).nullable(),
 hasMore:z.boolean(),pageSize:z.number().int().min(1).max(100),
 totalQuantity:z.number().int().nonnegative()
}).refine(p=>p.hasMore===(p.nextCursor!==null),{message:'Invalid piece cursor'})

export const fetchStockBinsPage=(warehouseId:string,cursor:string|null=null)=>
 decode(listLocalStockBinsPage(pathId(warehouseId),
  {limit:50,...(cursor?{cursor}:{})}),cursorPageOf(bin))
export const fetchReceiptLotPiecesPage=(receiptId:string,cursor:string|null=null)=>
 decode(listLocalReceiptLotPiecesPage(pathId(receiptId),
  {limit:50,...(cursor?{cursor}:{})}),piecesPageSchema)
export const fetchStockBins=(warehouseId:string)=>
 decode(listLocalStockBins(pathId(warehouseId)),z.array(bin))
export const createStockBin=(warehouseId:string,code:string,purpose:'QC_HOLD'|'STORAGE')=>
 decode(createLocalStockBin(pathId(warehouseId),{code,purpose}),bin)
export const fetchReceiptLotPieces=(receiptId:string)=>
 decode(listLocalReceiptLotPieces(pathId(receiptId)),z.array(piece))
export const splitReceiptLot=(receiptId:string,body:SplitReceiptLot)=>
 decode(splitLocalReceiptLot(pathId(receiptId),body),z.array(piece))
export const moveReceiptLot=(receiptId:string,body:MoveReceiptLot)=>
 decode(moveLocalReceiptLot(pathId(receiptId),body),z.array(piece))
export const fetchReceiptSupplyPressure=(warehouseId:string)=>
 decode(listLocalReceiptSupplyPressure(pathId(warehouseId)),z.array(pressure))
export const fetchReceiptSupplyPressurePage=(warehouseId:string,cursor:string|null=null)=>
 decode(listLocalReceiptSupplyPressurePage(pathId(warehouseId),
  {limit:50,...(cursor?{cursor}:{})}),cursorPageOf(pressure))
