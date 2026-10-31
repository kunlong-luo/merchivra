import {z} from 'zod'
import {csrfHeader} from './auth'
const root='/api/v1/seller-accounts/'
const record=z.object({id:z.string().uuid(),state:z.string(),
 externalCaseRef:z.string(),evidenceSha256:z.string(),
 requestedBy:z.string().uuid(),createdAt:z.string(),reviewedAt:z.string().nullable()})
const created=z.object({id:z.string().uuid(),state:z.string()})
const decision=z.object({state:z.string()})
async function req<T>(path:string,method:string,schema:z.ZodType<T>|null,body?:unknown):Promise<T>{
 const res=await fetch(path,{method,credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json',...(method==='GET'?{}:await csrfHeader()),
   ...(body===undefined?{}:{'Content-Type':'application/json'})},
  ...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(!res.ok)throw Error(res.status===403?'须由不同的安全审核员执行':
  '财务角色审核未启用或操作失败 (HTTP '+res.status+')')
 return schema?schema.parse(await res.json()):undefined as T
}
export const getFinanceReviews=(account:string)=>req(root+encodeURIComponent(account)+
 '/finances-role-review','GET',z.array(record))
export const requestFinanceReview=(account:string,evidenceSha256:string,externalCaseRef:string)=>
 req(root+encodeURIComponent(account)+'/finances-role-review','POST',created,
  {evidenceSha256,externalCaseRef})
export const decideFinanceReview=(review:string,action:'approve'|'reject')=>
 req(root+'finances-role-review/'+encodeURIComponent(review)+'/'+action,'POST',decision)
export const revokeFinanceReview=(account:string)=>req(root+encodeURIComponent(account)+
 '/finances-role-review/revoke','POST',null)
