import {z} from 'zod'
import {csrfHeader} from './auth'
const cost=z.object({id:z.string().uuid(),productSkuId:z.string().uuid(),
  unitCost:z.string(),currency:z.string().length(3),
  effectiveFrom:z.string(),effectiveTo:z.string().nullable(),createdAt:z.string()})
export type SkuCost=z.infer<typeof cost>
export async function fetchSkuCosts(skuId:string) {
 const res=await fetch('/api/v1/catalog/skus/'+encodeURIComponent(skuId)+'/costs',
  {credentials:'same-origin',cache:'no-store',headers:{Accept:'application/json'}})
 if(!res.ok)throw Error('读取 SKU 成本失败：HTTP '+res.status)
 return z.array(cost).parse(await res.json())
}
export async function addSkuCost(skuId:string,unitCost:string,currency:string,effectiveFrom:string) {
 const res=await fetch('/api/v1/catalog/skus/'+encodeURIComponent(skuId)+'/costs',
  {method:'POST',credentials:'same-origin',cache:'no-store',
   headers:{...(await csrfHeader()),'Content-Type':'application/json'},
   body:JSON.stringify({unitCost,currency,effectiveFrom})})
 if(res.status===409)throw Error('成本生效期间冲突；必须晚于最新有效成本的起始日')
 if(!res.ok)throw Error('保存 SKU 成本失败：HTTP '+res.status)
 return cost.parse(await res.json())
}
