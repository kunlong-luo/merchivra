import {z} from 'zod'

const base='/api/v1/replenishment/marketplaces/'
export const prioritySchema=z.object({
 sellerSku:z.string(),stockState:z.enum(['NO_OBSERVATION','STALE_OBSERVATION',
  'INCOMPLETE_OBSERVATION','REORDER','HEALTHY']),
 demandStatus:z.enum(['NO_POLICY_SIGNAL','UNMAPPED','INCOMPLETE_COVERAGE',
  'INCOMPLETE_ITEMS','NO_OBSERVED_ORDERS','MOCK_SAMPLE','OBSERVED_ONLY']),
 evidenceLevel:z.enum(['STOCK_UNTRUSTED','DEMAND_INCOMPLETE','SOURCE_MISMATCH',
  'NO_SKU_RATE','MOCK_SAMPLE','OBSERVED_HISTORY']),
 riskCode:z.enum(['STOCK_NOT_OBSERVED','DEMAND_NOT_ESTABLISHED','SOURCE_MISMATCH',
  'RATE_NOT_ESTABLISHED','LEAD_WINDOW_EXPOSURE','POLICY_REORDER','HISTORICAL_COVERAGE_OK']),
 priorityCode:z.enum(['BLOCKED','REVIEW_STOCK_ONLY','HIGH','MEDIUM','LOW']),
 stockSourceMode:z.enum(['MOCK','LIVE']).nullable(),
 demandSourceMode:z.enum(['MOCK','LIVE']).nullable(),
 observationId:z.string().uuid().nullable(),policyUpdatedAt:z.string(),
 fulfillable:z.number().int().nonnegative().nullable(),observedAt:z.string().nullable(),
 policyLeadTimeDays:z.number().int().min(1).max(365),
 observedUnits28d:z.number().int().nonnegative().nullable(),
 observedDailyUnits28d:z.string().nullable(),
 illustrativeCoverageDays:z.string().regex(/^\d+\.\d$/).nullable(),
 illustrativePolicyLeadUnits:z.number().int().nonnegative().nullable(),
 readySupplierQuoteCount:z.number().int().nonnegative(),
 shortestSupplierTermDays:z.number().int().min(0).max(365).nullable(),
 longestSupplierTermDays:z.number().int().min(0).max(365).nullable(),
 localDraftOrSubmittedUnits:z.number().int().nonnegative(),
 localApprovedUnreceivedUnits:z.number().int().nonnegative(),
 localReceivedUnits:z.number().int().nonnegative(),
 localPendingOrderCount:z.number().int().nonnegative(),
 existingLocalOrderNeedsReview:z.boolean()
})
export type ReplenishmentPriority=z.infer<typeof prioritySchema>

/** Strict read-only, current Owner Session tenant; never a supplier PO action. */
export async function getReplenishmentPriorities(marketplaceId:string){
 const response=await fetch(base+encodeURIComponent(marketplaceId)+'/priorities',{
  method:'GET',credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json'}})
 if(!response.ok){
  if(response.status===403)throw Error('当前账号没有补货分析权限')
  if(response.status===404)throw Error('站点不存在或不属于当前租户')
  throw Error('补货优先级读取失败：HTTP '+response.status)
 }
 return z.array(prioritySchema).max(500).parse(await response.json())
}
