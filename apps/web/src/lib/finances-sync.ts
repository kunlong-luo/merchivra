import {z} from 'zod'
import {csrfHeader} from './auth'
const statusSchema=z.object({
 jobId:z.string().uuid().nullable(),
 state:z.enum(['NOT_STARTED','READY','RUNNING','RETRY','SUCCEEDED','PAUSED']),
 committedPages:z.number().int().nonnegative(),
 generation:z.number().int().nonnegative(),
 nextRetryAt:z.string().nullable(),lastFailureCode:z.string().nullable()
})
export type FinancesSyncStatus=z.infer<typeof statusSchema>
async function call(marketplace:string,action:'status'|'start'|'step'){
 const res=await fetch('/api/v1/finances-sync/'+encodeURIComponent(marketplace)+
  (action==='status'?'':'/'+action),{
  method:action==='status'?'GET':'POST',credentials:'same-origin',
  cache:'no-store',headers:{Accept:'application/json',
  ...(action==='status'?{}:await csrfHeader())}
 })
 if(!res.ok){
  if(res.status===403)throw Error('无权访问财务同步')
  if(res.status===404)throw Error('尚未部署真实 Finances Worker 或找不到当前站点')
  if(res.status===409)throw Error('任务状态冲突或正在限流重试')
  throw Error('Finances 同步 HTTP '+res.status)
 }
 return statusSchema.parse(await res.json())
}
export const getFinancesSync=(id:string)=>call(id,'status')
export const startFinancesSync=(id:string)=>call(id,'start')
export const stepFinancesSync=(id:string)=>call(id,'step')
