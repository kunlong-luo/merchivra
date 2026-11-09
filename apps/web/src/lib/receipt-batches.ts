import {z} from 'zod'
import {
 listReceiptQualityBatchesLegacy,listReceiptQualityBatchesPage,
 getReceiptQualityBatchSummary,listWarehouseReceiptQualityBatchesLegacy,
 listWarehouseReceiptQualityBatchesPage,relabelReceiptQualityBatch,
 releaseReceiptQualityBatch,listReceiptQualityBatchEventsLegacy,
 listReceiptQualityBatchEventsPage
} from '../generated/purchasing-v114'
const base='/api/v1/purchasing/receipt-batches'
const batch=z.object({
 receiptId:z.string().uuid(),warehouseId:z.string().uuid(),
 productSkuId:z.string().uuid(),lotCode:z.string(),
 receivedQuantity:z.number().int().positive(),
 status:z.enum(['PENDING_QC','INSPECTED','RELEASED']),
 inspectionId:z.string().uuid().nullable(),
 acceptedQuantity:z.number().int().nonnegative(),
 rejectedQuantity:z.number().int().nonnegative(),
 inspectedBy:z.string().uuid().nullable(),releasedBy:z.string().uuid().nullable(),
 createdAt:z.string()
})
const event=z.object({id:z.string().uuid(),
 eventType:z.enum(['RECEIVED_TO_HOLD','LOT_RELABELED','QC_RECORDED','QC_RELEASED']),
 actorUserId:z.string().uuid(),oldLotCode:z.string().nullable(),
 newLotCode:z.string().nullable(),quantity:z.number().int().nonnegative().nullable(),
 note:z.string().nullable(),createdAt:z.string()})
export type ReceiptBatch=z.infer<typeof batch>
export type ReceiptBatchEvent=z.infer<typeof event>
const summary=z.object({pendingReceivedUnits:z.number().int().nonnegative(),
 awaitingReleaseAcceptedUnits:z.number().int().nonnegative(),
 pendingBatchCount:z.number().int().nonnegative(),
 awaitingReleaseBatchCount:z.number().int().nonnegative()})

// V116 Orval generated requests with one secure CSRF/Session transport.
// Orval path templates interpolate raw strings: require canonical UUIDs first.
const pathId=(id:string)=>z.string().uuid().parse(id)
const decode=<T>(promise:Promise<{data:unknown}>,schema:z.ZodType<T>):Promise<T>=>
 promise.then(r=>schema.parse(r.data))
const pageOf=<T extends z.ZodTypeAny>(schema:T)=>z.object({
 items:z.array(schema).max(100),nextCursor:z.string().min(1).nullable(),
 hasMore:z.boolean(),pageSize:z.number().int().min(1).max(100)
}).refine(p=>p.hasMore===(p.nextCursor!==null),{message:'Invalid page cursor'})
export const fetchReceiptBatchesPage=(orderId:string,cursor:string|null=null)=>
 decode(listReceiptQualityBatchesPage(pathId(orderId),{limit:50,...(cursor?{cursor}:{})}),
  pageOf(batch))
export const fetchReceiptBatchSummary=(orderId:string)=>
 decode(getReceiptQualityBatchSummary(pathId(orderId)),summary)
export const fetchWarehouseReceiptBatchesPage=(warehouseId:string,cursor:string|null=null)=>
 decode(listWarehouseReceiptQualityBatchesPage(pathId(warehouseId),
  {limit:50,...(cursor?{cursor}:{})}),pageOf(batch))
export const fetchReceiptBatches=(orderId:string)=>
 decode(listReceiptQualityBatchesLegacy(pathId(orderId)),z.array(batch))
export const fetchWarehouseReceiptBatches=(warehouseId:string)=>
 decode(listWarehouseReceiptQualityBatchesLegacy(pathId(warehouseId)),z.array(batch))
export const relabelReceiptBatch=(receiptId:string,expectedLotCode:string,
 newLotCode:string,reason:string)=>
 decode(relabelReceiptQualityBatch(pathId(receiptId),{expectedLotCode,newLotCode,reason}),batch)
export const releaseReceiptBatch=(receiptId:string)=>
 decode(releaseReceiptQualityBatch(pathId(receiptId)),batch)
export const fetchReceiptBatchEvents=(receiptId:string)=>
 decode(listReceiptQualityBatchEventsLegacy(pathId(receiptId)),z.array(event))
export const fetchReceiptBatchEventsPage=(receiptId:string,cursor:string|null=null)=>
 decode(listReceiptQualityBatchEventsPage(pathId(receiptId),
  {limit:50,...(cursor?{cursor}:{})}),pageOf(event))
