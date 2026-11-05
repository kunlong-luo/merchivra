import {z} from 'zod'
import {csrfHeader} from './auth'
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

async function api<T>(url:string,schema:z.ZodType<T>,method='GET',body?:unknown):Promise<T>{
 const response=await fetch(url,{method,credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json',...(method==='GET'?{}:await csrfHeader()),
   ...(body===undefined?{}:{'Content-Type':'application/json'})},
  ...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(!response.ok){
  if(response.status===403)throw Error('当前账号不具备批次质检放行权限')
  if(response.status===404)throw Error('本地收货批次不存在或不属于当前租户')
  if(response.status===409)throw Error('批次、质检审批或仓库状态已改变，请刷新后操作')
  throw Error('本地收货批次 API HTTP '+response.status)
 }
 return schema.parse(await response.json())
}
const pageOf=<T extends z.ZodTypeAny>(schema:T)=>z.object({
 items:z.array(schema).max(100),nextCursor:z.string().min(1).nullable(),
 hasMore:z.boolean(),pageSize:z.number().int().min(1).max(100)
}).refine(p=>p.hasMore===(p.nextCursor!==null),{message:'Invalid page cursor'})
const batchPage=z.object({
 items:z.array(batch).max(100),nextCursor:z.string().min(1).nullable(),
 hasMore:z.boolean(),pageSize:z.number().int().min(1).max(100)
}).refine(p=>p.hasMore===(p.nextCursor!==null),{message:'Invalid batch page cursor'})
export const fetchReceiptBatchesPage=(orderId:string,cursor:string|null=null)=>{
 const params=new URLSearchParams({limit:'50'})
 if(cursor)params.set('cursor',cursor)
 return api(base+'/orders/'+encodeURIComponent(orderId)+'/page?'+params.toString(),batchPage)
}
export const fetchReceiptBatchSummary=(orderId:string)=>api(
 base+'/orders/'+encodeURIComponent(orderId)+'/summary',summary)
export const fetchWarehouseReceiptBatchesPage=(warehouseId:string,cursor:string|null=null)=>{
 const params=new URLSearchParams({limit:'50'})
 if(cursor)params.set('cursor',cursor)
 return api(base+'/warehouses/'+encodeURIComponent(warehouseId)+'/page?'+params.toString(),batchPage)
}
export const fetchReceiptBatches=(orderId:string)=>api(
 base+'/orders/'+encodeURIComponent(orderId),z.array(batch))
export const fetchWarehouseReceiptBatches=(warehouseId:string)=>api(
 base+'/warehouses/'+encodeURIComponent(warehouseId),z.array(batch))
export const relabelReceiptBatch=(receiptId:string,expectedLotCode:string,
 newLotCode:string,reason:string)=>api(
 base+'/'+encodeURIComponent(receiptId)+'/relabel',batch,'POST',
 {expectedLotCode,newLotCode,reason})
export const releaseReceiptBatch=(receiptId:string)=>api(
 base+'/'+encodeURIComponent(receiptId)+'/release',batch,'POST')
export const fetchReceiptBatchEvents=(receiptId:string)=>api(
 base+'/'+encodeURIComponent(receiptId)+'/events',z.array(event))

export const fetchReceiptBatchEventsPage=(receiptId:string,cursor:string|null=null)=>{
 const params=new URLSearchParams({limit:'50'})
 if(cursor)params.set('cursor',cursor)
 return api(base+'/'+encodeURIComponent(receiptId)+'/events/page?'+params.toString(),pageOf(event))
}
