import {z} from 'zod'
const line=z.object({
 itemId:z.string(),sellerSku:z.string().nullable(),quantity:z.number().int().nullable(),
 internalSku:z.string().nullable(),unitCost:z.string().nullable(),currency:z.string().nullable(),
 extendedCost:z.string().nullable(),issue:z.string()
})
const response=z.object({
 readiness:z.string(),currency:z.string(),orderCreatedAt:z.string().nullable(),
 itemCount:z.number().int(),coveredItems:z.number().int(),
 provisionalCost:z.string().nullable(),items:z.array(line)
})
export type OrderCostPreview=z.infer<typeof response>
export async function fetchOrderCostReadiness(marketplace:string,orderId:string){
 const res=await fetch('/api/v1/finance/marketplaces/'+encodeURIComponent(marketplace)+
  '/orders/'+encodeURIComponent(orderId)+'/cost-readiness',
  {method:'GET',credentials:'same-origin',cache:'no-store',headers:{Accept:'application/json'}})
 if(!res.ok)throw Error(res.status===404?'当前站点订单不存在':
  res.status===403?'当前账号无权查看历史成本':
  '历史成本核对接口 HTTP '+res.status)
 return response.parse(await res.json())
}
