import {z} from 'zod'
import {csrfHeader} from './auth'

const base='/api/v1/purchasing/planning'
const rule=z.object({supplierId:z.string().uuid(),productSkuId:z.string().uuid(),
 minimumOrderQuantity:z.number().int().positive(),packMultiple:z.number().int().positive(),
 leadTimeDays:z.number().int().nonnegative(),currency:z.string(),unitCost:z.string(),
 updatedAt:z.string()})
const recommendation=z.object({
 warehouseId:z.string().uuid(),supplierId:z.string().uuid(),supplierName:z.string(),
 productSkuId:z.string().uuid(),internalSku:z.string(),
 sourceMovementId:z.string().uuid().nullable(),
 policyUpdatedAt:z.string(),ruleUpdatedAt:z.string(),
 onHandQuantity:z.number().int().nullable(),reorderPoint:z.number().int().nonnegative(),
 targetStock:z.number().int().positive(),minimumOrderQuantity:z.number().int().positive(),
 packMultiple:z.number().int().positive(),leadTimeDays:z.number().int().nonnegative(),
 currency:z.string(),unitCost:z.string(),
 state:z.enum(['NO_LEDGER','LEDGER_ANOMALY','HEALTHY','LOW_STOCK','LIMIT_EXCEEDED']),
 suggestedQuantity:z.number().int().nullable(),orderQuantity:z.number().int().nullable()
})
const plan=z.object({
 id:z.string().uuid(),warehouseId:z.string().uuid(),supplierId:z.string().uuid(),
 productSkuId:z.string().uuid(),sourceMovementId:z.string().uuid(),
 policyUpdatedAt:z.string(),ruleUpdatedAt:z.string(),
 onHandQuantity:z.number().int().nonnegative(),reorderPoint:z.number().int(),
 targetStock:z.number().int(),suggestedQuantity:z.number().int().positive(),
 minimumOrderQuantity:z.number().int().positive(),packMultiple:z.number().int().positive(),
 leadTimeDays:z.number().int().nonnegative(),orderedQuantity:z.number().int().positive(),
 currency:z.string(),unitCost:z.string(),estimatedArrivalDate:z.string(),
 status:z.enum(['PROPOSED','CONVERTED','CANCELLED']),
 purchaseOrderId:z.string().uuid().nullable(),createdBy:z.string().uuid(),createdAt:z.string()
})
export type SupplierSkuRule=z.infer<typeof rule>
export type PurchaseRecommendation=z.infer<typeof recommendation>
export type LocalPurchasePlan=z.infer<typeof plan>
export type NewPlanRequest={
 requestKey:string;warehouseId:string;supplierId:string;productSkuId:string;
 expectedLastMovementId:string;expectedPolicyUpdatedAt:string;
 expectedRuleUpdatedAt:string;expectedOnHand:number;expectedOrderQuantity:number
}

async function api<T>(url:string,schema:z.ZodType<T>,method='GET',body?:unknown):Promise<T> {
 const response=await fetch(url,{method,credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json',...(method==='GET'?{}:await csrfHeader()),
   ...(body===undefined?{}:{'Content-Type':'application/json'})},
  ...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(!response.ok){
  if(response.status===403)throw Error('当前账号无采购计划操作权限')
  if(response.status===404)throw Error('仓库、供应商、SKU 或采购计划不存在')
  if(response.status===409)throw Error('采购计划已存在或库存及供应商规则已经变化，请刷新核实')
  throw Error('采购计划操作失败：HTTP '+response.status)
 }
 return response.status===204?undefined as T:schema.parse(await response.json())
}
export const fetchSupplierSkuRules=()=>api(base+'/supplier-rules',z.array(rule))
export const setSupplierSkuRule=(supplierId:string,productSkuId:string,
 minimumOrderQuantity:number,packMultiple:number,leadTimeDays:number,
 currency:string,unitCost:string)=>api(base+'/supplier-rules',rule,'POST',{
 supplierId,productSkuId,minimumOrderQuantity,packMultiple,leadTimeDays,currency,unitCost})
export const removeSupplierSkuRule=(supplierId:string,skuId:string)=>api(
 base+'/supplier-rules/'+encodeURIComponent(supplierId)+'/'+encodeURIComponent(skuId),
 z.unknown(),'DELETE')
export const fetchLocalPurchaseRecommendations=(warehouseId:string)=>api(
 base+'/warehouses/'+encodeURIComponent(warehouseId)+'/recommendations',
 z.array(recommendation))
export const fetchLocalPurchasePlans=(warehouseId:string)=>api(
 base+'/warehouses/'+encodeURIComponent(warehouseId)+'/plans',z.array(plan))
export const proposeLocalPurchasePlan=(body:NewPlanRequest)=>api(
 base+'/plans',plan,'POST',body)
export const actOnLocalPurchasePlan=(id:string,action:'convert'|'cancel')=>api(
 base+'/plans/'+encodeURIComponent(id)+'/'+action,plan,'POST')
