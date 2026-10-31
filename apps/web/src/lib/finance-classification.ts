import {z} from 'zod'
import {csrfHeader} from './auth'

const base='/api/v1/finance/marketplaces/'
const category=z.enum(['PRODUCT_SALES','PRODUCT_REFUND','AMAZON_FEE',
 'FBA_FEE','ADJUSTMENT','UNCLASSIFIED'])
const rule=z.object({
 id:z.string().uuid(),sourceType:z.string(),category,
 state:z.enum(['ACTIVE','INACTIVE']),revision:z.number().int().positive(),
 updatedAt:z.string().nullable()
})
const audit=z.object({
 action:z.enum(['CREATED','CHANGED','DEACTIVATED']),
 previousCategory:z.string().nullable(),newCategory:z.string().nullable(),
 previousState:z.string().nullable(),newState:z.string(),
 revision:z.number().int(),actorId:z.string().uuid(),occurredAt:z.string()
})
const analysis=z.object({
 coverage:z.enum(['NO_BREAKDOWNS','MISSING_AMOUNT','CURRENCY_MISMATCH',
  'UNMAPPED_LEAF','AMOUNT_MISMATCH','RECONCILED_UNVERIFIED']),
 sourceTotal:z.string(),currency:z.string(),leafTotal:z.string().nullable(),
 leafCount:z.number().int(),mappedCount:z.number().int(),
 leaves:z.array(z.object({path:z.string(),sourceType:z.string(),
  amount:z.string().nullable(),currency:z.string().nullable(),
  proposedCategory:z.string()})),
 provisionalTotals:z.array(z.object({
  category:z.string(),currency:z.string(),signedAmount:z.string()
 }))
})
const preview=z.object({eventId:z.string().uuid(),sourceFamily:z.string(),
 dataOrigin:z.string(),status:z.string(),analysis:analysis.nullable()})
export type ClassificationRule=z.infer<typeof rule>
export type ClassificationPreview=z.infer<typeof preview>
export type ClassificationCategory=z.infer<typeof category>

async function req<T>(url:string,schema:z.ZodType<T>,
 method='GET',body?:unknown):Promise<T>{
 const response=await fetch(url,{
  method,credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json',...(method==='GET'?{}:await csrfHeader()),
   ...(body===undefined?{}:{'Content-Type':'application/json'})},
  ...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(!response.ok){
  if(response.status===401)throw Error('登录会话已过期')
  if(response.status===403)throw Error('只有已授权的租户 Owner 可维护分类规则')
  if(response.status===404)throw Error('站点、规则或来源事件不存在')
  if(response.status===409)throw Error('分类规则与其他变更冲突')
  throw Error('财务分类接口 HTTP '+response.status)
 }
 return schema.parse(await response.json())
}
const root=(mp:string)=>base+encodeURIComponent(mp)
export function getClassificationRules(mp:string){
 return req(root(mp)+'/classification-rules',z.array(rule))
}
export function getClassificationPreview(mp:string,event:string){
 return req(root(mp)+'/events/'+encodeURIComponent(event)+
  '/classification-preview',preview)
}
export function upsertClassificationRule(mp:string,sourceType:string,
 kind:ClassificationCategory){
 return req(root(mp)+'/classification-rules',rule,'POST',
  {sourceType,category:kind})
}
export function deactivateClassificationRule(mp:string,id:string){
 return req(root(mp)+'/classification-rules/'+encodeURIComponent(id),rule,'DELETE')
}
export function getClassificationAudit(mp:string,id:string){
 return req(root(mp)+'/classification-rules/'+encodeURIComponent(id)+
  '/audit',z.array(audit))
}
