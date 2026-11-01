import {z} from 'zod'
import {createProfitReport,listProfitReports,getProfitReport,
  getProfitReportAudit,advanceProfitReport,resumeProfitReport,
  getExportProfitReportCsvUrl} from '../generated/finance-profits'
const money=z.string().regex(/^-?[0-9]+(?:\.[0-9]+)?$/)
const job=z.object({
 id:z.string().uuid(),state:z.enum(['READY','RETRY','SUCCEEDED','PAUSED']),
 after:z.string(),before:z.string(),currency:z.string().length(3),
 totalOrders:z.number().int().nonnegative().max(5000),
 processedOrders:z.number().int().nonnegative().max(5000),
 eligibleOrders:z.number().int().nonnegative(),
 blockedOrders:z.number().int().nonnegative(),
 retryCount:z.number().int().nonnegative().max(5),
 failureCode:z.string().nullable(),
 manualAdsReference:money,manualAdsCount:z.number().int().nonnegative(),
 createdAt:z.string(),completedAt:z.string().nullable(),
 cohortFrozen:z.literal(true),globalSourceSnapshot:z.literal(false),
 financialSourceComplete:z.literal(false),bankVerified:z.literal(false),
 ledgerPosted:z.literal(false)
})
const sku=z.object({
 sku:z.string(),orderCount:z.number().int().nonnegative(),
 units:z.number().int().nonnegative(),currency:z.string().length(3),
 sales:money,amazonFees:money,fbaFees:money,cogs:money,contribution:money
})
const order=z.object({
 orderId:z.string().uuid(),position:z.number().int().nonnegative(),
 state:z.enum(['PENDING','ELIGIBLE','BLOCKED']),
 readiness:z.string().nullable(),currency:z.string().length(3),
 sales:money.nullable(),fees:money.nullable(),fba:money.nullable(),
 cogs:money.nullable(),contribution:money.nullable(),
 extractedAt:z.string().nullable(),extractSha256:z.string().nullable()
})
export const periodProfitReport=z.object({
 job,
 totals:z.object({
  currency:z.string().length(3),eligibleOrders:z.number().int().nonnegative(),
  blockedOrders:z.number().int().nonnegative(),
  reviewedEvents:z.number().int().nonnegative(),
  signedSales:money,signedAmazonFees:money,signedFbaFees:money,
  provisionalCogs:money,contribution:money
 }),skus:z.array(sku).max(1000),orders:z.array(order).max(100),
 orderOffset:z.number().int().nonnegative(),
 hasMore:z.boolean()
})
export type PeriodProfitJob=z.infer<typeof job>
export type PeriodProfitReport=z.infer<typeof periodProfitReport>


export const getPeriodProfitJobs=async(mp:string)=>
 z.array(job).max(20).parse((await listProfitReports(mp)).data)
export const getPeriodProfitReport=(mp:string,id:string,offset=0)=>{
 if(offset<0||offset>5000||offset%100!==0)throw new Error('无效报告分页')
 return getProfitReport(mp,id,{offset}).then(result=>periodProfitReport.parse(result.data))
}
export const getPeriodProfitAudit=async(mp:string,id:string)=>
 z.array(z.object({action:z.string(),processedOrders:z.number().int().nonnegative(),
   failureCode:z.string().nullable(),occurredAt:z.string()})).max(100)
  .parse((await getProfitReportAudit(mp,id)).data)
export const createPeriodProfitReport=async(mp:string,requestKey:string,after:string,before:string)=>
 job.parse((await createProfitReport(mp,{requestKey,after,before})).data)
export const progressPeriodProfitReport=async(mp:string,id:string)=>
 job.parse((await advanceProfitReport(mp,id)).data)
export const resumePeriodProfitReport=async(mp:string,id:string)=>
 job.parse((await resumeProfitReport(mp,id)).data)
export const exportPeriodProfitUrl=(mp:string,id:string)=>
 getExportProfitReportCsvUrl(mp,id)
