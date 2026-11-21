import {z} from 'zod'
import {
 listLotCountReviewsLegacy,listLotCountReviewsPage,submitLotCountReview,
 decideLotCountReview,listLotDestinationTransfersLegacy,
 listLotDestinationTransfersPage,acknowledgeLotDestinationTransfer,
 listLotDispositionReviewsLegacy,listLotDispositionReviewsPage,
 listUnplannedQuarantinesLegacy,listUnplannedQuarantinesPage,
 proposeLotDispositionReview,decideLotDispositionReview,completeLotDispositionReview
} from '../generated/purchasing-v114'
const root='/api/v1/purchasing/lot-reconciliation'
const id=z.string().uuid()
const count=z.object({
 id,observationActionId:id,positionId:id,warehouseId:id,productSkuId:id,
 expectedBinId:id,expectedPositionQuantity:z.number().int().nonnegative(),
 observedQuantity:z.number().int().nonnegative(),expectedOnHand:z.number().int().nonnegative(),
 reason:z.string(),status:z.enum([
  'PENDING_REVIEW','APPLIED','CONFIRMED_NO_CHANGE','EVIDENCE_REQUIRED','REJECTED']),
 submittedBy:id,reviewedBy:id.nullable(),operationId:id.nullable(),
 reviewReason:z.string().nullable()
})
const transfer=z.object({actionId:id,destinationWarehouseId:id,
 sourcePositionId:id,destinationPositionId:id,
 expectedQuantity:z.number().int().positive(),
 receivedQuantity:z.number().int().nonnegative(),
 status:z.enum(['POSTED_AWAITING_DESTINATION','CONFIRMED','MISMATCH']),
 transferredBy:id,acknowledgedBy:id.nullable(),reference:z.string().nullable()})
const disposition=z.object({
 id,quarantineActionId:id,warehouseId:id,quantity:z.number().int().positive(),
 method:z.enum(['DISPOSAL','SUPPLIER_RETURN']),
 status:z.enum(['PENDING_APPROVAL','APPROVED','REJECTED','EXECUTED_REPORTED']),
 reason:z.string(),proposedBy:id,reviewedBy:id.nullable(),
 completedBy:id.nullable(),completionReference:z.string().nullable()
})
export type LotCountCase=z.infer<typeof count>
export type LotTransferReceipt=z.infer<typeof transfer>
export type LotDisposition=z.infer<typeof disposition>
// V121: generated Orval shares strict same-origin Session + CSRF/no-store.
 // Dynamic UUID paths and actual response DTOs are validated fail-closed.
const pathId=(value:string)=>id.parse(value)
const decode=<T>(response:Promise<{data:unknown}>,schema:z.ZodType<T>):Promise<T>=>
 response.then(r=>schema.parse(r.data))
const pageOf=<T extends z.ZodTypeAny>(item:T)=>z.object({
 items:z.array(item).max(100),nextCursor:z.string().min(1).nullable(),
 hasMore:z.boolean(),pageSize:z.number().int().min(1).max(100)
}).refine(p=>p.hasMore===(p.nextCursor!==null),{
 message:'V50 审计分页游标与状态不一致'
})
const query=(cursor:string|null)=>({limit:50,...(cursor?{cursor}:{})})
export const fetchLotCountCases=(warehouseId:string)=>
 decode(listLotCountReviewsLegacy(pathId(warehouseId)),z.array(count))
export const fetchLotCountCasesPage=(warehouseId:string,cursor:string|null=null)=>
 decode(listLotCountReviewsPage(pathId(warehouseId),query(cursor)),pageOf(count))
export const submitLotCount=(body:{requestKey:string;observationActionId:string;reason:string})=>
 decode(submitLotCountReview(body),count)
export const decideLotCount=(id:string,body:{decision:'APPROVE'|'REJECT';reason:string})=>
 decode(decideLotCountReview(pathId(id),body),count)
export const fetchDestinationTransfers=(warehouseId:string)=>
 decode(listLotDestinationTransfersLegacy(pathId(warehouseId)),z.array(transfer))
export const fetchDestinationTransfersPage=(warehouseId:string,cursor:string|null=null)=>
 decode(listLotDestinationTransfersPage(pathId(warehouseId),query(cursor)),pageOf(transfer))
export const acknowledgeDestinationTransfer=(id:string,body:{
 requestKey:string;receivedQuantity:number;reference:string;reason:string
})=>decode(acknowledgeLotDestinationTransfer(pathId(id),body),transfer)
export const fetchLotDispositions=(warehouseId:string)=>
 decode(listLotDispositionReviewsLegacy(pathId(warehouseId)),z.array(disposition))
export const fetchLotDispositionsPage=(warehouseId:string,cursor:string|null=null)=>
 decode(listLotDispositionReviewsPage(pathId(warehouseId),query(cursor)),pageOf(disposition))
export const fetchUnplannedQuarantines=(warehouseId:string)=>
 decode(listUnplannedQuarantinesLegacy(pathId(warehouseId)),z.array(id))
export const fetchUnplannedQuarantinesPage=(warehouseId:string,cursor:string|null=null)=>
 decode(listUnplannedQuarantinesPage(pathId(warehouseId),query(cursor)),pageOf(id))
export const proposeLotDisposition=(body:{
 requestKey:string;quarantineActionId:string;
 method:'DISPOSAL'|'SUPPLIER_RETURN';reason:string
})=>decode(proposeLotDispositionReview(body),disposition)
export const decideLotDisposition=(id:string,body:{
 decision:'APPROVE'|'REJECT';reason:string
})=>decode(decideLotDispositionReview(pathId(id),body),disposition)
export const reportLotDispositionCompletion=(id:string,body:{
 reference:string;reason:string
})=>decode(completeLotDispositionReview(pathId(id),body),disposition)
