import {z} from 'zod'
import {csrfHeader} from './auth'
export type CatalogStream='LISTINGS'|'FBA'
const stateSchema=z.object({
 jobId:z.string().uuid().nullable(),stream:z.enum(['LISTINGS','FBA']),
 state:z.enum(['NOT_STARTED','READY','RUNNING','RETRY','PAUSED','SUCCEEDED']),
 committedPages:z.number().int(),generation:z.number().int(),
 leaseEpoch:z.number().int(),nextRetryAt:z.string().nullable(),
 lastFailureCode:z.string().nullable(),lastFinishedAt:z.string().nullable()
})
export type CatalogStatus=z.infer<typeof stateSchema>
async function request(marketplaceId:string,stream:CatalogStream,
 action:'status'|'start'|'step'){
 const base='/api/v1/catalog-sync/'+encodeURIComponent(marketplaceId)+'/'+stream
 const res=await fetch(base+(action==='status'?'':'/'+action),{
  method:action==='status'?'GET':'POST',credentials:'same-origin',
  cache:'no-store',headers:{Accept:'application/json',
   ...(action==='status'?{}:await csrfHeader())}
 })
 if(!res.ok){
  if(res.status===404)throw new Error('真实 Listing/FBA 同步未启用，或店铺不存在')
  if(res.status===403)throw new Error('仅当前租户管理员可执行真实同步')
  if(res.status===409)throw new Error('同步未准备就绪、尚未到重试时间或租约被占用')
  throw new Error('真实同步接口 HTTP '+res.status)
 }
 return stateSchema.parse(await res.json())
}
export const catalogStatus=(id:string,stream:CatalogStream)=>request(id,stream,'status')
export const catalogStart=(id:string,stream:CatalogStream)=>request(id,stream,'start')
export const catalogStep=(id:string,stream:CatalogStream)=>request(id,stream,'step')
