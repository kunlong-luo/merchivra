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
 internalSku:z.string(),recordedReceivedQuantity:z.number().int().nonnegative()})
const movement=z.object({id:z.string().uuid(),warehouseId:z.string().uuid(),
 productSkuId:z.string().uuid(),internalSku:z.string(),purchaseReceiptId:z.string().uuid(),
 quantity:z.number().int().positive(),movementType:z.literal('PURCHASE_RECEIPT'),
 createdAt:z.string()})
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
  if(res.status===409)throw Error('采购状态、数量或审批人不满足要求')
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
export const receivePurchase=(id:string,lineId:string,quantity:number,warehouseId:string)=>
 call(base+'/orders/'+encodeURIComponent(id)+'/receive',detail,'POST',
  {lineId,quantity,warehouseId})
