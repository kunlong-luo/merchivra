import {z} from 'zod'

const sourceCounts=z.object({
 mock:z.number().int().nonnegative(),
 unverified:z.number().int().nonnegative(),
 live:z.number().int().nonnegative()
})
const listing=z.object({
 total:z.number().int().nonnegative(),
 unmapped:z.number().int().nonnegative(),
 conflicts:z.number().int().nonnegative(),
 unknownSourceTime:z.number().int().nonnegative(),sources:sourceCounts
})
const order=z.object({
 total:z.number().int().nonnegative(),
 itemsNotObserved:z.number().int().nonnegative(),
 financeNotObserved:z.number().int().nonnegative(),
 unknownRefund:z.number().int().nonnegative(),
 unknownSourceTime:z.number().int().nonnegative(),sources:sourceCounts
})
const inventory=z.object({
 skus:z.number().int().nonnegative(),
 observed:z.number().int().nonnegative(),
 partial:z.number().int().nonnegative(),
 unknown:z.number().int().nonnegative(),
 stale:z.number().int().nonnegative(),
 unknownFulfillable:z.number().int().nonnegative(),
 observedZeroFulfillable:z.number().int().nonnegative(),
 unknownSourceTime:z.number().int().nonnegative(),sources:sourceCounts
})
const report=z.object({
 id:z.string().uuid(),
 state:z.enum(['READY','RETRY','PAUSED','SUCCEEDED']),
 after:z.string(),before:z.string(),
 currency:z.string().regex(/^[A-Z]{3}$/),
 totalOrders:z.number().int().nonnegative().max(5000),
 processedOrders:z.number().int().nonnegative().max(5000),
 eligibleOrders:z.number().int().nonnegative(),
 blockedOrders:z.number().int().nonnegative(),
 contribution:z.string().regex(/^-?[0-9]+(?:\.[0-9]+)?$/).nullable(),
 cohortFrozen:z.literal(true),
 globalSourceSnapshot:z.literal(false),
 financialSourceComplete:z.literal(false),
 bankVerified:z.literal(false),
 ledgerPosted:z.literal(false)
}).superRefine((r,ctx)=>{
 if(r.state!=='SUCCEEDED'&&r.contribution!==null)
  ctx.addIssue({code:'custom',message:'Incomplete V64 report must not claim contribution'})
 if(r.processedOrders>r.totalOrders)
  ctx.addIssue({code:'custom',message:'Invalid report progress'})
 if(r.eligibleOrders+r.blockedOrders>r.processedOrders)
  ctx.addIssue({code:'custom',message:'Invalid financial coverage'})
})
const marketplace=z.object({
 id:z.string().uuid(),marketplaceId:z.string().min(1),
 region:z.string(),currency:z.string().regex(/^[A-Z]{3}$/),
 listings:listing,orders:order,inventory,
 latestReport:report.nullable()
}).superRefine((m,ctx)=>{
 for(const [path,value,total] of [
  ['listings',m.listings.sources,m.listings.total],
  ['orders',m.orders.sources,m.orders.total],
  ['inventory',m.inventory.sources,m.inventory.skus]
 ] as const){
  if(value.mock+value.unverified+value.live!==total)
   ctx.addIssue({code:'custom',message:'Incomplete '+path+' source coverage'})
 }
 if(m.inventory.observed+m.inventory.partial+m.inventory.unknown+
    m.inventory.stale!==m.inventory.skus)
  ctx.addIssue({code:'custom',message:'FBA latest-source quality buckets are inconsistent'})
 if(m.latestReport&&m.latestReport.currency!==m.currency)
  ctx.addIssue({code:'custom',message:'Report currency differs from Marketplace currency'})
})
export const businessOverviewSchema=z.object({
 capturedAt:z.string().datetime({offset:true}),
 sourceSnapshotFrozen:z.literal(false),
 financialSourceComplete:z.literal(false),
 bankVerified:z.literal(false),
 ledgerPosted:z.literal(false),
 crossCurrencyTotalsAvailable:z.literal(false),
 mixedCurrencies:z.boolean(),
 marketplaces:z.array(marketplace).max(100)
}).superRefine((data,ctx)=>{
 if(new Set(data.marketplaces.map(x=>x.id)).size!==data.marketplaces.length)
  ctx.addIssue({code:'custom',message:'Duplicate marketplace in source-quality overview'})
 if((new Set(data.marketplaces.map(x=>x.currency)).size>1)!==data.mixedCurrencies)
  ctx.addIssue({code:'custom',message:'Mixed-currency flag contradicts marketplace data'})
})
export type BusinessOverview=z.infer<typeof businessOverviewSchema>
export type BusinessMarketplace=z.infer<typeof marketplace>
export type BusinessSources=z.infer<typeof sourceCounts>

export async function fetchBusinessOverview():Promise<BusinessOverview> {
 const response=await fetch('/api/v1/business-overview',{
  credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json'}
 })
 if(!response.ok){
  if(response.status===401)throw new Error('会话已过期，请重新登录')
  if(response.status===403)throw new Error('仅当前租户 Owner 可查看经营数据质量')
  if(response.status===409)throw new Error('Marketplace 超过 100 个，无法保证全量展示')
  throw new Error('经营总览 HTTP '+response.status)
 }
 return businessOverviewSchema.parse(await response.json())
}
export const businessQualityCsvUrl='/api/v1/business-overview/export.csv'
