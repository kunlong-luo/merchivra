import {z} from 'zod'

const preview=z.object({
 readiness:z.string(),currency:z.string(),
 reviewedEventCount:z.number().int().nonnegative(),
 pendingOrderLinks:z.number().int().nonnegative(),
 signedSales:z.string().nullable(),
 signedAmazonFees:z.string().nullable(),signedFbaFees:z.string().nullable(),
 provisionalUnitCost:z.string().nullable(),estimatedContribution:z.string().nullable(),
 financialSourceComplete:z.literal(false),bankSettlementVerified:z.literal(false)
})
export type ContributionPreview=z.infer<typeof preview>
export async function getOrderContribution(marketplace:string,orderId:string):
 Promise<ContributionPreview>{
 const url='/api/v1/finance/marketplaces/'+encodeURIComponent(marketplace)+
  '/orders/'+encodeURIComponent(orderId)+'/contribution-preview'
 const res=await fetch(url,{method:'GET',credentials:'same-origin',
  cache:'no-store',headers:{Accept:'application/json'}})
 if(!res.ok)throw Error(res.status===404?'该站点订单不存在':
  res.status===403?'当前账号无权查看经营贡献额':'贡献额接口 HTTP '+res.status)
 return preview.parse(await res.json())
}
