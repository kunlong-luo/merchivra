import {z} from 'zod'

/**
 * V185 consumes ONLY tenant-session-scoped read-only V92 PERIOD proposals.
 * Validation deliberately rejects attempts to promote a local straight-line
 * illustration to verified FX, tax, refunded COGS or bank-backed net profit.
 */
const plan=z.object({
 id:z.string().uuid(),
 targetKind:z.enum(['SKU','ORDER','PERIOD']),
 state:z.enum(['PENDING_REVIEW','APPROVED_UNVERIFIED','REJECTED'])
})
const money=z.string().regex(/^[0-9]{1,14}\.[0-9]{6}$/)
const month=z.object({
 period:z.string().regex(/^\d{4}-(?:0[1-9]|1[0-2])$/),
 currency:z.string().regex(/^[A-Z]{3}$/),
 projectedAmount:money
})
export const projection=z.object({
 sourceId:z.string().uuid(),allocationId:z.string().uuid(),
 allocationState:z.enum(['PENDING_REVIEW','APPROVED_UNVERIFIED','REJECTED']),
 ruleVersion:z.literal('ILLUSTRATIVE_DAILY_STRAIGHT_LINE_MICRO_UNITS_V1'),
 currency:z.string().regex(/^[A-Z]{3}$/),
 sourceAmount:money,allocatedAmount:money,
 unallocatedAmount:money,projectedAmount:money,
 months:z.array(month).max(400),
 overlappingServiceDays:z.boolean(),
 blockerCodes:z.array(z.string()).min(4),
 externalSourceVerified:z.literal(false),
 accountingPolicyApproved:z.literal(false),
 currencyConverted:z.literal(false),
 refundCogsReversalVerified:z.literal(false),
 ledgerPosted:z.literal(false),
 netProfitEligible:z.literal(false),
 realizedNetProfit:z.null()
})
export type PeriodCostProjection=z.infer<typeof projection>
const root=(mp:string,source:string)=>
 '/api/v1/finance/marketplaces/'+encodeURIComponent(mp)+
 '/cost-sources/'+encodeURIComponent(source)+'/allocations'
async function get(url:string) {
 const res=await fetch(url,{
  method:'GET',credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json'}
 })
 if(!res.ok)throw new Error(res.status===404?'当前来源的分摊计划不存在':
  '费用分摊证据 HTTP '+res.status)
 return res.json() as Promise<unknown>
}
export async function fetchPeriodAllocationPlans(mp:string,source:string) {
 return z.array(plan).max(50).parse(await get(root(mp,source)))
}
export async function fetchPeriodProjection(mp:string,source:string,id:string) {
 return projection.parse(await get(root(mp,source)+'/'+encodeURIComponent(id)+
  '/periodization-preview'))
}
