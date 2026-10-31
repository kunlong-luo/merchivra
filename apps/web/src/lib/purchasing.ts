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
 quantity:z.number().int(),
 movementType:z.enum(['PURCHASE_RECEIPT','OUTBOUND','TRANSFER_OUT','TRANSFER_IN','COUNT_ADJUSTMENT','QC_QUARANTINE']),
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
export const createSupplier=(supplierCode:string,displayName:string)=>
 call(base+'/suppliers',supplier,'POST',{supplierCode,displayName})
export const fetchPurchaseOrders=()=>call(base+'/orders',z.array(order))
export const fetchPurchaseOrder=(id:string)=>call(base+'/orders/'+encodeURIComponent(id),detail)
export const createPurchaseOrder=(supplierId:string,currency:string,notes:string,
 lines:NewPurchaseLine[])=>call(base+'/orders',detail,'POST',{supplierId,currency,notes,lines})
export const purchaseAction=(id:string,action:'submit'|'approve'|'cancel')=>
 call(base+'/orders/'+encodeURIComponent(id)+'/'+action,detail,'POST')
export const fetchWarehouses=()=>call(base+'/warehouses',z.array(warehouse))
export const createWarehouse=(warehouseCode:string,displayName:string)=>
 call(base+'/warehouses',warehouse,'POST',{warehouseCode,displayName})
export const fetchWarehouseStock=(warehouseId:string)=>
 call(base+'/warehouse-stock?warehouseId='+encodeURIComponent(warehouseId),z.array(stock))
export const fetchWarehouseLedger=(warehouseId:string)=>
 call(base+'/warehouse-ledger?warehouseId='+encodeURIComponent(warehouseId),z.array(movement))
export const fetchPendingReceipts=()=>call(base+'/pending-receipts',z.array(pending))
export const fetchStockOperations=(warehouseId:string)=>
 call(base+'/warehouse-operations?warehouseId='+encodeURIComponent(warehouseId),
  z.array(stockOperation))
export const postStockOperation=(body:StockOperationRequest)=>
 call(base+'/warehouse-operations',stockOperation,'POST',body)
export const receivePurchase=(id:string,lineId:string,quantity:number,
 warehouseId:string,asnId?:string|null)=>
 call(base+'/orders/'+encodeURIComponent(id)+'/receive',detail,'POST',
  {lineId,quantity,warehouseId,...(asnId?{asnId}:{})})
