import {z} from 'zod'
import {
 listReceiptPieceGrades,gradeReceiptPiece,finalizeReceiptPieceGrades,
 listReleasedLotPositions,putawayReleasedLotPosition,
 previewReleasedLotPick,pickReleasedLotPositions
} from '../generated/purchasing-v114'
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
// V130: the GET preview contains at most the first 500 matching positions.
// Its sum is a full bound only when fewer than 500 rows are returned. The
// signed warehouse balance remains authoritative and the POST rechecks all
// eligible positions transactionally; never treat omitted rows as zero.
export function lotPickCapacityHint(
 preview:ReadonlyArray<{remainingQuantity:number}>|undefined,
 onHand:number|undefined
){
 const shown=preview?.reduce((sum,p)=>sum+p.remainingQuantity,0)??0
 const mayHaveMore=(preview?.length??0)>=500
 return {shown,mayHaveMore,maxPickable:onHand===undefined?0:
  mayHaveMore?onHand:Math.min(onHand,shown)}
}
// V117: Orval source-backed Fetch transport + strict runtime Zod validation.
// Reject dynamic path interpolation except canonical UUID strings.
const pathId=(id:string)=>z.string().uuid().parse(id)
const decode=<T>(promise:Promise<{data:unknown}>,schema:z.ZodType<T>):Promise<T>=>
 promise.then(r=>schema.parse(r.data))
export const fetchPieceGrades=(receiptId:string)=>
 decode(listReceiptPieceGrades(pathId(receiptId)),z.array(grade))
export const gradePiece=(receiptId:string,body:{
 requestKey:string;pieceId:string;acceptedQuantity:number;rejectedQuantity:number;reason:string
})=>decode(gradeReceiptPiece(pathId(receiptId),body),grade)
export const finalizePieceGrades=(receiptId:string,body:{requestKey:string;reason:string})=>
 decode(finalizeReceiptPieceGrades(pathId(receiptId),body),inspection)
export const fetchLotPositions=(warehouseId:string)=>
 decode(listReleasedLotPositions(pathId(warehouseId)),z.array(position))
export const putawayReleasedLot=(body:{requestKey:string;receiptId:string;binId:string;
 quantity:number;expiryDate:string|null;reason:string})=>
 decode(putawayReleasedLotPosition(body),position)
export const previewLotPick=(warehouseId:string,productSkuId:string,policy:LotPolicy)=>
 decode(previewReleasedLotPick(pathId(warehouseId),pathId(productSkuId),{policy}),
  z.array(position))
export const pickReleasedLots=(body:{requestKey:string;warehouseId:string;productSkuId:string;
 policy:LotPolicy;quantity:number;expectedOnHand:number;reason:string})=>
 decode(pickReleasedLotPositions(body),pick)
