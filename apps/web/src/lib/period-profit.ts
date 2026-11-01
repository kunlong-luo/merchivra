import {z} from 'zod'
import {csrfHeader} from './auth'
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

const root=(marketplace:string)=>
 '/api/v1/finance/marketplaces/'+encodeURIComponent(marketplace)+'/profit-reports'
const error=(status:number)=>
 status===401?'请先登录':
 status===403?'仅当前租户 Owner 可生成来源报表':
 status===404?'报表或 Marketplace 不在当前租户':
 status===409?'重复请求窗口冲突、待处理任务已达上限或任务状态不允许':
 status===422?'当前日期范围超过 5,000 个 LIVE 订单，请缩短时间窗口':
 '报表请求失败（HTTP '+status+'）'
async function read<T>(url:string,schema:z.ZodType<T>){
 const res=await fetch(url,{credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json'}})
 if(!res.ok)throw new Error(error(res.status))
 return schema.parse(await res.json())
}
async function write<T>(url:string,body:unknown,schema:z.ZodType<T>){
 const res=await fetch(url,{method:'POST',credentials:'same-origin',cache:'no-store',
  headers:{...(await csrfHeader()),'Content-Type':'application/json',
   Accept:'application/json'},body:JSON.stringify(body)})
 if(!res.ok)throw new Error(error(res.status))
 return schema.parse(await res.json())
}
export const getPeriodProfitJobs=(mp:string)=>
 read(root(mp),z.array(job).max(20))
export const getPeriodProfitReport=(mp:string,id:string,offset=0)=>{
 if(offset<0||offset>5000||offset%100!==0)
  throw new Error('无效报告分页')
 return read(root(mp)+'/'+encodeURIComponent(id)+'?offset='+offset,periodProfitReport)
}
export const getPeriodProfitAudit=(mp:string,id:string)=>
 read(root(mp)+'/'+encodeURIComponent(id)+'/audit',
  z.array(z.object({action:z.string(),processedOrders:z.number().int().nonnegative(),
   failureCode:z.string().nullable(),occurredAt:z.string()})).max(100))
export const createPeriodProfitReport=(mp:string,requestKey:string,after:string,before:string)=>
 write(root(mp),{requestKey,after,before},job)
export const progressPeriodProfitReport=(mp:string,id:string)=>
 write(root(mp)+'/'+encodeURIComponent(id)+'/step',{},job)
export const resumePeriodProfitReport=(mp:string,id:string)=>
 write(root(mp)+'/'+encodeURIComponent(id)+'/resume',{},job)
export const exportPeriodProfitUrl=(mp:string,id:string)=>
 root(mp)+'/'+encodeURIComponent(id)+'/export.csv'
