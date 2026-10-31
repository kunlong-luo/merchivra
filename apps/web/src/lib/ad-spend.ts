import {z} from 'zod'
import {csrfHeader} from './auth'
const source=z.object({
 id:z.string().uuid(),sourceKey:z.string(),campaignReference:z.string(),
 businessDate:z.string(),spendAmount:z.string(),currency:z.string(),
 evidenceOrigin:z.literal('MANUAL_UNVERIFIED')
})
const option=z.object({id:z.string().uuid(),internalSku:z.string()})
const assignment=z.object({
 skuId:z.string().uuid(),internalSku:z.string(),basisPoints:z.number().int(),
 evidenceReference:z.string(),revision:z.number().int().positive(),
 approximateSpend:z.string()
})
const preview=z.object({
 status:z.enum(['NO_SKU_ALLOCATION','PARTIALLY_ALLOCATED',
  'FULLY_ALLOCATED_UNVERIFIED']),spendAmount:z.string(),currency:z.string(),
 totalBasisPoints:z.number().int(),unallocatedBasisPoints:z.number().int(),
 attributedSpend:z.string(),unallocatedSpend:z.string(),
 adsApiVerified:z.literal(false),includedInRecognizedProfit:z.literal(false)
})
const view=z.object({source,summary:preview,assignments:z.array(assignment)})
const audit=z.object({
 skuId:z.string().uuid(),action:z.enum(['CREATED','UPDATED','WITHDRAWN']),
 basisPoints:z.number().int(),evidenceReference:z.string(),
 revision:z.number().int().positive(),actorId:z.string().uuid(),occurredAt:z.string()
})
export type AdSource=z.infer<typeof source>
export type AdSpendPreview=z.infer<typeof view>
const root=(mp:string)=>'/api/v1/finance/marketplaces/'+encodeURIComponent(mp)+'/ads-spend'
const item=(mp:string,id:string)=>root(mp)+'/'+encodeURIComponent(id)
async function req<T>(url:string,schema:z.ZodType<T>|null,
 method='GET',body?:unknown):Promise<T>{
 const r=await fetch(url,{method,credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json',...(method==='GET'?{}:await csrfHeader()),
   ...(body===undefined?{}:{'Content-Type':'application/json'})},
  ...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(!r.ok)throw Error(r.status===409?
  '广告参考号重复且数据冲突、SKU 未激活或分摊比例超过 100%':
  r.status===404?'当前广告记录或 Marketplace 不存在':
  r.status===403?'没有广告费用审核权限':'广告费用接口 HTTP '+r.status)
 return schema?schema.parse(await r.json()):undefined as T
}
export const getAdSpends=(mp:string)=>req(root(mp),z.array(source))
export const getAdSkuOptions=(mp:string)=>req(root(mp)+'/sku-options',z.array(option))
export const createAdSpend=(mp:string,value:{
 sourceKey:string;campaignReference:string;businessDate:string;
 spendAmount:string;currency:string
})=>req(root(mp),source,'POST',value)
export const getAdSpendPreview=(mp:string,id:string)=>
 req(item(mp,id)+'/preview',view)
export const assignAdSpend=(mp:string,id:string,value:{
 skuId:string;basisPoints:number;evidenceReference:string
})=>req(item(mp,id)+'/attributions',view,'POST',value)
export const withdrawAdSpend=(mp:string,id:string,sku:string)=>
 req(item(mp,id)+'/attributions/'+encodeURIComponent(sku),null,'DELETE')
export const getAdSpendAudit=(mp:string,id:string)=>
 req(item(mp,id)+'/audit',z.array(audit))
