import {
 listWarehousesPage,getWarehouseItem,listSuppliersPage,listPurchaseOrdersPage,
 listWarehouseStockPage,getWarehouseStockItem,listWarehouseLedgerPage,
 listPendingReceiptsPage,
 createLocalSupplier,createLocalWarehouse,createLocalPurchaseOrder,
 getLocalPurchaseOrder,submitLocalPurchaseOrder,approveLocalPurchaseOrder,
 cancelLocalPurchaseOrder,receiveLocalPurchaseOrder,createLocalStockOperation,
 resolveLocalWarehouseLabels
} from '../generated/purchasing-v114'
import {z} from 'zod'
import {csrfHeader} from './auth'

const base='/api/v1/purchasing'
const supplier=z.object({id:z.string().uuid(),code:z.string(),name:z.string(),status:z.string()})
const order=z.object({id:z.string().uuid(),orderNumber:z.string(),supplierId:z.string().uuid(),
 status:z.enum(['DRAFT','SUBMITTED','APPROVED','RECEIVING','RECEIVED','CANCELLED']),
 currency:z.string(),createdBy:z.string().uuid(),approvedBy:z.string().uuid().nullable(),
 notes:z.string().nullable(),updatedAt:z.string()})
const line=z.object({id:z.string().uuid(),productSkuId:z.string().uuid(),
 orderedQuantity:z.number().int(),receivedQuantity:z.number().int(),unitCost:z.string()})
export const purchaseDetailSchema=z.object({order,lines:z.array(line)})
const detail=purchaseDetailSchema
export type Supplier=z.infer<typeof supplier>
export type PurchaseOrder=z.infer<typeof order>
export type PurchaseLine=z.infer<typeof line>
export type PurchaseDetail=z.infer<typeof detail>
export type NewPurchaseLine={productSkuId:string;quantity:number;unitCost:string}
const warehouse=z.object({id:z.string().uuid(),code:z.string(),name:z.string(),
 status:z.enum(['ACTIVE','ARCHIVED'])})
const stock=z.object({warehouseId:z.string().uuid(),productSkuId:z.string().uuid(),
 internalSku:z.string(),recordedReceivedQuantity:z.number().int().nonnegative(),
 onHandQuantity:z.number().int().nonnegative()})
const movement=z.object({id:z.string().uuid(),warehouseId:z.string().uuid(),
 productSkuId:z.string().uuid(),internalSku:z.string(),purchaseReceiptId:z.string().uuid().nullable(),
 operationId:z.string().uuid().nullable(),inspectionId:z.string().uuid().nullable().optional(),
  lotActionId:z.string().uuid().nullable().optional(),
 quantity:z.number().int(),
 movementType:z.enum(['PURCHASE_RECEIPT','OUTBOUND','TRANSFER_OUT','TRANSFER_IN','COUNT_ADJUSTMENT','QC_QUARANTINE','QC_RELEASE','LOT_QUARANTINE']),
 reason:z.string().nullable(),createdAt:z.string()})
const stockOperation=z.object({id:z.string().uuid(),
 type:z.enum(['OUTBOUND','TRANSFER','COUNT']),warehouseId:z.string().uuid(),
 destinationWarehouseId:z.string().uuid().nullable(),productSkuId:z.string().uuid(),
 requestedQuantity:z.number().int(),expectedOnHand:z.number().int().nonnegative(),
 appliedDelta:z.number().int(),afterQuantity:z.number().int().nonnegative(),
 reason:z.string(),createdBy:z.string().uuid(),createdAt:z.string()})
export type WarehouseStockOperation=z.infer<typeof stockOperation>
export type StockOperationRequest={requestKey:string;type:'OUTBOUND'|'TRANSFER'|'COUNT';
 warehouseId:string;destinationWarehouseId:string|null;productSkuId:string;
 quantity:number;expectedOnHand:number;reason:string}
const pending=z.object({purchaseOrderId:z.string().uuid(),orderNumber:z.string(),
 purchaseOrderLineId:z.string().uuid(),productSkuId:z.string().uuid(),internalSku:z.string(),
 status:z.enum(['APPROVED','RECEIVING']),orderedQuantity:z.number().int().positive(),
 receivedQuantity:z.number().int().nonnegative(),
 remainingQuantity:z.number().int().positive(),currency:z.string(),unitCost:z.string()})
export type LocalWarehouse=z.infer<typeof warehouse>
export type LocalStock=z.infer<typeof stock>
export type LocalStockMovement=z.infer<typeof movement>
export type PendingPurchaseReceipt=z.infer<typeof pending>

async function call<T>(url:string,schema:z.ZodType<T>,method='GET',body?:unknown):Promise<T>{
 const res=await fetch(url,{method,credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json',...(method==='GET'?{}:await csrfHeader()),
   ...(body===undefined?{}:{'Content-Type':'application/json'})},
  ...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(!res.ok){
  if(res.status===403)throw Error('当前账号没有采购操作权限')
  if(res.status===404)throw Error('供应商、采购单或 SKU 不存在')
  if(res.status===409)throw Error('库存已变化、余额不足、请求重复或采购审批状态冲突，请刷新后重新确认')
  throw Error('采购操作失败：HTTP '+res.status)
 }
 return schema.parse(await res.json())
}
export const fetchSuppliers=()=>call(base+'/suppliers',z.array(supplier))
// V111: supplier search and selection is tenant-scoped on the server, not the
// first 200 suppliers returned by the legacy compatibility endpoint.
const supplierPage=z.object({
 items:z.array(supplier).max(100),nextCursor:z.string().min(1).nullable(),
 hasMore:z.boolean(),pageSize:z.number().int().min(1).max(100)
}).refine(p=>p.hasMore===(p.nextCursor!==null),{
 message:'Invalid supplier page cursor contract'
})
export type SupplierPage=z.infer<typeof supplierPage>
export const fetchSuppliersPage=({limit=50,cursor=null,status='ALL',prefix=''}:{
 limit?:number;cursor?:string|null;status?:'ALL'|'ACTIVE'|'ARCHIVED';prefix?:string
}={}):Promise<SupplierPage>=>{
 return listSuppliersPage({limit,status,prefix,...(cursor?{cursor}:{})})
  .then(r=>supplierPage.parse(r.data))
}

export const createSupplier=(supplierCode:string,displayName:string)=>
 createLocalSupplier({supplierCode,displayName})
  .then(r=>supplier.parse(r.data))
// V110: a page is an API-scoped view, never a full-tenant count or audit snapshot.
const purchaseOrdersPage=z.object({
 items:z.array(order).max(100),nextCursor:z.string().min(1).nullable(),
 hasMore:z.boolean(),pageSize:z.number().int().min(1).max(100)
}).refine(p=>p.hasMore===(p.nextCursor!==null),{
 message:'Invalid purchase order page cursor contract'
}).refine(p=>p.items.length<=p.pageSize,{
 message:'Purchase order page exceeds declared server size'
})
export type PurchaseOrdersPage=z.infer<typeof purchaseOrdersPage>
export const fetchPurchaseOrdersPage=({limit=50,cursor=null,status='ALL',search=''}:{
 limit?:number;cursor?:string|null;status?:string;search?:string
}={}):Promise<PurchaseOrdersPage>=>{
 return listPurchaseOrdersPage({
  limit,status:status as 'ALL'|'DRAFT'|'SUBMITTED'|'APPROVED'|'RECEIVING'|'RECEIVED'|'CANCELLED',
  search,...(cursor?{cursor}:{})
 }).then(r=>purchaseOrdersPage.parse(r.data))
}
export const fetchPurchaseOrders=()=>call(base+'/orders',z.array(order))
export const fetchPurchaseOrder=(id:string)=>
 getLocalPurchaseOrder(id).then(r=>detail.parse(r.data))
export const createPurchaseOrder=(supplierId:string,currency:string,notes:string,
 lines:NewPurchaseLine[])=>
 createLocalPurchaseOrder({supplierId,currency,notes,lines})
  .then(r=>detail.parse(r.data))
// Keep explicit generated POST functions; never implement state transitions via GET.
export const purchaseAction=(id:string,action:'submit'|'approve'|'cancel')=>
 (action==='submit'?submitLocalPurchaseOrder(id):
  action==='approve'?approveLocalPurchaseOrder(id):cancelLocalPurchaseOrder(id))
  .then(r=>detail.parse(r.data))
// V114: complete tenant-scoped directory, never the first 200 legacy warehouses.
const warehousePage=z.object({
 items:z.array(warehouse).max(100),nextCursor:z.string().min(1).nullable(),
 hasMore:z.boolean(),pageSize:z.number().int().min(1).max(100)
}).refine(p=>p.hasMore===(p.nextCursor!==null),{
 message:'Invalid warehouse page cursor contract'
})
export type WarehousePage=z.infer<typeof warehousePage>
export const fetchWarehousesPage=({limit=50,status='ALL',prefix='',cursor=null}:{
 limit?:number;status?:'ALL'|'ACTIVE'|'ARCHIVED';prefix?:string;cursor?:string|null
}={}):Promise<WarehousePage>=>{
 return listWarehousesPage({limit,status,prefix,...(cursor?{cursor}:{})})
  .then(r=>warehousePage.parse(r.data))
}
/** Resolve up to 100 historical warehouse IDs in one tenant-bound HTTP call.
 * The server omits unknown/foreign IDs; never synthesize a label. */
export const fetchWarehouseLabels=(ids:string[]):Promise<LocalWarehouse[]>=>{
 if(ids.length<1||ids.length>100||new Set(ids).size!==ids.length)
  return Promise.reject(new Error('Warehouse label batch must have 1..100 unique IDs'))
 return resolveLocalWarehouseLabels({ids}).then(r=>z.array(warehouse).max(100).parse(r.data))
}
export const fetchWarehouseItem=(warehouseId:string)=>
 getWarehouseItem({warehouseId}).then(r=>warehouse.parse(r.data))
export const fetchWarehouses=()=>call(base+'/warehouses',z.array(warehouse))
export const createWarehouse=(warehouseCode:string,displayName:string)=>
 createLocalWarehouse({warehouseCode,displayName})
  .then(r=>warehouse.parse(r.data))
// V113: paged warehouse balances and pending purchase lines are read-only,
// tenant-scoped API views; an unseen SKU is NEVER treated as zero.
const stockPage=z.object({
 items:z.array(stock).max(100),nextCursor:z.string().min(1).nullable(),
 hasMore:z.boolean(),pageSize:z.number().int().min(1).max(100)
}).refine(p=>p.hasMore===(p.nextCursor!==null),{
 message:'Invalid warehouse stock cursor contract'
})
export type WarehouseStockPage=z.infer<typeof stockPage>
export const fetchWarehouseStockPage=({warehouseId,limit=50,search='',cursor=null}:{
 warehouseId:string;limit?:number;search?:string;cursor?:string|null
}):Promise<WarehouseStockPage>=>{
 return listWarehouseStockPage({warehouseId,limit,search,...(cursor?{cursor}:{})})
  .then(r=>stockPage.parse(r.data))
}
export const fetchWarehouseStockItem=(warehouseId:string,productSkuId:string)=>
 getWarehouseStockItem({warehouseId,productSkuId}).then(r=>stock.parse(r.data))
const pendingPage=z.object({
 items:z.array(pending).max(100),nextCursor:z.string().min(1).nullable(),
 hasMore:z.boolean(),pageSize:z.number().int().min(1).max(100)
}).refine(p=>p.hasMore===(p.nextCursor!==null),{
 message:'Invalid pending receipt cursor contract'
})
export type PendingReceiptsPage=z.infer<typeof pendingPage>
export const fetchPendingReceiptsPage=({limit=50,status='ALL',search='',cursor=null}:{
 limit?:number;status?:'ALL'|'APPROVED'|'RECEIVING';search?:string;cursor?:string|null
}={}):Promise<PendingReceiptsPage>=>{
 return listPendingReceiptsPage({limit,status,search,...(cursor?{cursor}:{})})
  .then(r=>pendingPage.parse(r.data))
}
export const fetchWarehouseStock=(warehouseId:string)=>
 call(base+'/warehouse-stock?warehouseId='+encodeURIComponent(warehouseId),z.array(stock))
// V112: page filters operate over the entire server-side warehouse history.
const warehouseLedgerPage=z.object({
 items:z.array(movement).max(100),nextCursor:z.string().min(1).nullable(),
 hasMore:z.boolean(),pageSize:z.number().int().min(1).max(100)
}).refine(p=>p.hasMore===(p.nextCursor!==null),{
 message:'Invalid warehouse ledger cursor contract'
})
export type WarehouseLedgerPage=z.infer<typeof warehouseLedgerPage>
export const fetchWarehouseLedgerPage=({warehouseId,limit=50,movementType='ALL',
 search='',cursor=null}:{
 warehouseId:string;limit?:number;movementType?:string;search?:string;cursor?:string|null
}):Promise<WarehouseLedgerPage>=>{
 return listWarehouseLedgerPage({
  warehouseId,limit,
  movementType:movementType as 'ALL'|'PURCHASE_RECEIPT'|'OUTBOUND'|'TRANSFER_OUT'|
   'TRANSFER_IN'|'COUNT_ADJUSTMENT'|'QC_QUARANTINE'|'QC_RELEASE'|'LOT_QUARANTINE',
  search,...(cursor?{cursor}:{})
 }).then(r=>warehouseLedgerPage.parse(r.data))
}
export const fetchWarehouseLedger=(warehouseId:string)=>
 call(base+'/warehouse-ledger?warehouseId='+encodeURIComponent(warehouseId),z.array(movement))
export const fetchPendingReceipts=()=>call(base+'/pending-receipts',z.array(pending))
export const fetchStockOperations=(warehouseId:string)=>
 call(base+'/warehouse-operations?warehouseId='+encodeURIComponent(warehouseId),
  z.array(stockOperation))
export const postStockOperation=(body:StockOperationRequest)=>
 createLocalStockOperation(body).then(r=>stockOperation.parse(r.data))
export const receivePurchase=(id:string,lineId:string,quantity:number,
 warehouseId:string,asnId?:string|null)=>
 receiveLocalPurchaseOrder(id,{lineId,quantity,warehouseId,...(asnId?{asnId}:{})})
  .then(r=>detail.parse(r.data))
