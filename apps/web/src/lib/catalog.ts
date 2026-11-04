import {z} from 'zod'
import {csrfHeader} from './auth'
const base='/api/v1/catalog'
const sku=z.object({id:z.string().uuid(),internalSku:z.string(),displayName:z.string(),
  status:z.enum(['ACTIVE','ARCHIVED']),updatedAt:z.string()})
const mapping=z.object({listingId:z.string().uuid(),productSkuId:z.string().uuid(),
  version:z.number().int().positive()})
export type Sku=z.infer<typeof sku>

async function call<T>(url:string,schema:z.ZodType<T>,method='GET',body?:unknown):Promise<T>{
  const res=await fetch(url,{method,credentials:'same-origin',cache:'no-store',
    headers:{Accept:'application/json',...(method==='GET'?{}:await csrfHeader()),
      ...(body===undefined?{}:{'Content-Type':'application/json'})},
    ...(body===undefined?{}:{body:JSON.stringify(body)})})
  if(!res.ok){
    if(res.status===403)throw Error('仅管理员可维护内部 SKU')
    if(res.status===404)throw Error('SKU 或 Listing 不存在或无权访问')
    if(res.status===409)throw Error('SKU 冲突或正在使用中')
    throw Error('商品主数据请求失败：HTTP '+res.status)
  }
  return res.status===204?undefined as T:schema.parse(await res.json())
}
export const fetchSkus=(after='')=>call(base+'/skus?limit=100'+
  (after?'&after='+encodeURIComponent(after):''),z.array(sku))
/** V91: existing Owner-only ProductCatalogController prefix + keyset.
 * A full page has a *possible* next page (API caps at 100).
 * No new backend or unrestricted in-memory SKU catalog download.
 */
export const fetchSkusPage=(prefix='',after='',limit=100)=>{
 const query=new URLSearchParams({limit:String(limit)})
 if(prefix.trim())query.set('prefix',prefix.trim())
 if(after)query.set('after',after)
 return call(base+'/skus?'+query,z.array(sku).max(100))
}

export const createSku=(internalSku:string,displayName:string)=>
  call(base+'/skus',sku,'POST',{internalSku,displayName})
export const updateSku=(id:string,displayName:string,status:'ACTIVE'|'ARCHIVED')=>
  call(base+'/skus/'+encodeURIComponent(id),sku,'PATCH',{displayName,status})
export const mapListing=(id:string,productSkuId:string)=>
  call(base+'/listings/'+encodeURIComponent(id)+'/map',mapping,'POST',{productSkuId})
export const unmapListing=(id:string)=>call(base+'/listings/'+encodeURIComponent(id)+
  '/map',z.unknown(),'DELETE')
