import {csrfHeader} from './auth'

/**
 * Orval's single secure transport; only internal app-relative routes.
 * Never sends Seller credentials, Bearer tokens or tenant IDs from the browser.
 * Generated TanStack Query functions use this directly.
 */
export async function merchivraFetch<T>(url:string,options:RequestInit={}):Promise<T>{
 if(!url.startsWith('/api/v1/')||url.startsWith('//'))
  throw new Error('Only same-origin internal API requests are allowed')
 const method=(options.method??'GET').toUpperCase()
 const headers=new Headers(options.headers)
 if(!headers.has('Accept'))headers.set('Accept','application/json')
 if(!['GET','HEAD','OPTIONS'].includes(method)){
  const csrf=await csrfHeader()
  for(const [key,value] of Object.entries(csrf))headers.set(key,value)
 }
 const response=await fetch(url,{
  ...options,
  credentials:'same-origin',
  cache:'no-store',
  headers
 })
 if(!response.ok){
  const reason=response.status===401?'未登录或登录已失效':
   response.status===403?'当前用户没有操作权限':
   response.status===404?'所选租户或 Marketplace 下没有此数据':
   response.status===409?'业务状态变化，请刷新来源后重试':
   'API 请求失败（HTTP '+response.status+'）'
  throw new Error(reason)
 }
 if(response.status===204)return {data:undefined,status:204,headers:response.headers} as T
 const contentType=response.headers.get('content-type')??''
 // Some API test doubles omit Content-Type even when returning JSON.
 // For an absent header, parse valid JSON but preserve plain text fallbacks.
 // Explicit non-JSON media types remain text, avoiding hidden coercions.
 const data=contentType.includes('application/json')||contentType.includes('+json')
  ?await response.json()
  :contentType===''?await (async()=>{
    const raw=await response.text()
    try{return JSON.parse(raw) as unknown}catch{return raw}
   })()
  :await response.text()
 // Orval v8 custom Fetch response shape is {data,status,headers},
 // not the raw JSON body (otherwise typed callers silently receive wrong data).
 return {data,status:response.status,headers:response.headers} as T
}
export type ErrorType<E> = Error & {cause?:E}
export type BodyType<T> = T
