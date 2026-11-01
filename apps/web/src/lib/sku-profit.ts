import {z} from 'zod'

const money=z.string().regex(/^-?[0-9]+(?:\.[0-9]+)?$/)
export const skuProfitOrder=z.object({
 orderId:z.string().uuid(),externalOrderId:z.string(),orderedAt:z.string(),
 sku:z.string().nullable(),units:z.number().int().nonnegative(),
 readiness:z.string(),currency:z.string().length(3),
 reviewedEventCount:z.number().int().nonnegative(),
 sales:money.nullable(),amazonFees:money.nullable(),fbaFees:money.nullable(),
 cogs:money.nullable(),contribution:money.nullable()
})
export const skuProfitSku=z.object({
 sku:z.string(),orderCount:z.number().int().nonnegative(),
 units:z.number().int().nonnegative(),currency:z.string().length(3),
 sales:money,amazonFees:money,fbaFees:money,cogs:money,contribution:money
})
export const skuProfitPreview=z.object({
 periodAfter:z.string(),periodBefore:z.string(),timezone:z.literal('UTC'),
 offset:z.number().int().nonnegative(),pageSize:z.literal(30),
 hasMore:z.boolean(),periodTruncated:z.boolean(),
 unverifiedAdsSpendReference:money,
 unverifiedAdsRecordCount:z.number().int().nonnegative(),
 adsSourceOrigin:z.literal('MANUAL_UNVERIFIED'),
 totals:z.object({
  scannedOrders:z.number().int().nonnegative(),
  eligibleOrders:z.number().int().nonnegative(),
  blockedOrders:z.number().int().nonnegative(),
  multiSkuOrders:z.number().int().nonnegative(),
  sourceEvents:z.number().int().nonnegative(),
  currency:z.string().length(3),sales:money,amazonFees:money,
  fbaFees:money,cogs:money,contribution:money,
  completePeriod:z.boolean(),financialSourceComplete:z.literal(false),
  bankVerified:z.literal(false),ledgerPosted:z.literal(false)
 }),skuRows:z.array(skuProfitSku).max(30),
 orders:z.array(skuProfitOrder).max(30)
})
export type SkuProfitPreview=z.infer<typeof skuProfitPreview>

const root=(mp:string)=>
 '/api/v1/finance/marketplaces/'+encodeURIComponent(mp)+'/sku-profit-preview'
export function profitQuery(mp:string,after:string,before:string,offset:number){
 const query=new URLSearchParams({after,before,offset:String(offset)})
 return root(mp)+'?'+query.toString()
}
export function profitCsvUrl(mp:string,after:string,before:string,offset:number){
 const query=new URLSearchParams({after,before,offset:String(offset)})
 return root(mp)+'/export.csv?'+query.toString()
}
export async function fetchSkuProfitPreview(mp:string,after:string,before:string,offset:number){
 if(!/^\d{4}-\d{2}-\d{2}$/.test(after)||
    !/^\d{4}-\d{2}-\d{2}$/.test(before)||
    offset<0||offset%30!==0||offset>3000)
  throw new Error('日期或页码不符合要求')
 const res=await fetch(profitQuery(mp,after,before,offset),{
  credentials:'same-origin',cache:'no-store',headers:{Accept:'application/json'}
 })
 if(!res.ok)throw new Error(res.status===403?'当前租户没有财务 Owner 权限':
  res.status===400?'仅支持 1–92 天的 UTC 时间窗口':
  res.status===404?'当前站点不存在':
  '利润来源预览失败（HTTP '+res.status+'）')
 return skuProfitPreview.parse(await res.json())
}
