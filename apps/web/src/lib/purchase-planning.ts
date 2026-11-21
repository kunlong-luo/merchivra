import {z} from 'zod'
import {
 listSupplierSkuRulesLegacy,listSupplierSkuRulesPage,upsertSupplierSkuRule,
 deleteSupplierSkuRule,listLocalPurchaseRecommendationsLegacy,
 listLocalPurchaseRecommendationsPage,listLocalPurchasePlansLegacy,
 listLocalPurchasePlansPage,createLocalPurchasePlanProposal,
 convertLocalPurchasePlan,cancelLocalPurchasePlan
} from '../generated/purchasing-v114'

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

// V118: source-backed Orval methods, retaining the Zod runtime guards and
// existing business-facing function signatures for current TanStack consumers.
const pathId=(id:string)=>z.string().uuid().parse(id)
const decode=<T>(res:Promise<{data:unknown}>,schema:z.ZodType<T>):Promise<T>=>
 res.then(r=>schema.parse(r.data))
export type CursorPage<T>={items:T[];nextCursor:string|null;hasMore:boolean;pageSize:number}
const pageOf=<T extends z.ZodTypeAny>(item:T)=>z.object({
 items:z.array(item).max(100),nextCursor:z.string().min(1).nullable(),
 hasMore:z.boolean(),pageSize:z.number().int().min(1).max(100)
}).refine(p=>p.hasMore===(p.nextCursor!==null),{message:'Invalid page cursor'})
const params=(cursor:string|null)=>({limit:50,...(cursor?{cursor}:{})})

export const fetchSupplierSkuRules=()=>
 decode(listSupplierSkuRulesLegacy(),z.array(rule))
export const fetchSupplierSkuRulesPage=(cursor:string|null=null)=>
 decode(listSupplierSkuRulesPage(params(cursor)),pageOf(rule))
export const fetchLocalPurchaseRecommendationsPage=(warehouseId:string,cursor:string|null=null)=>
 decode(listLocalPurchaseRecommendationsPage(pathId(warehouseId),params(cursor)),
  pageOf(recommendation))
export const fetchLocalPurchasePlansPage=(warehouseId:string,cursor:string|null=null)=>
 decode(listLocalPurchasePlansPage(pathId(warehouseId),params(cursor)),pageOf(plan))
export const setSupplierSkuRule=(supplierId:string,productSkuId:string,
 minimumOrderQuantity:number,packMultiple:number,leadTimeDays:number,
 currency:string,unitCost:string)=>
 decode(upsertSupplierSkuRule({supplierId,productSkuId,minimumOrderQuantity,
  packMultiple,leadTimeDays,currency,unitCost}),rule)
export const removeSupplierSkuRule=(supplierId:string,skuId:string)=>
 deleteSupplierSkuRule(pathId(supplierId),pathId(skuId)).then(()=>undefined)
export const fetchLocalPurchaseRecommendations=(warehouseId:string)=>
 decode(listLocalPurchaseRecommendationsLegacy(pathId(warehouseId)),
  z.array(recommendation))
export const fetchLocalPurchasePlans=(warehouseId:string)=>
 decode(listLocalPurchasePlansLegacy(pathId(warehouseId)),z.array(plan))
export const proposeLocalPurchasePlan=(body:NewPlanRequest)=>
 decode(createLocalPurchasePlanProposal(body),plan)
export const actOnLocalPurchasePlan=(id:string,action:'convert'|'cancel')=>
 decode(action==='convert'?convertLocalPurchasePlan(pathId(id)):
  cancelLocalPurchasePlan(pathId(id)),plan)
