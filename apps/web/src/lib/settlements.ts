import {z} from 'zod'
import {csrfHeader} from './auth'
const origin='/api/v1/finance/marketplaces/'
const statement=z.object({
 id:z.string().uuid(),reference:z.string(),periodStart:z.string(),periodEnd:z.string(),
 reportedNet:z.string(),currency:z.string(),evidenceOrigin:z.literal('MANUAL_UNVERIFIED'),
 createdAt:z.string()
})
const event=z.object({
 id:z.string().uuid(),sourceKey:z.string(),sourceType:z.string(),
 amount:z.string(),currency:z.string(),postedAt:z.string(),
 matchedReferenceKind:z.string().nullable()
})
const line=z.object({
 eventId:z.string().uuid(),sourceEventKey:z.string(),sourceType:z.string(),
 signedAmount:z.string(),currency:z.string(),postedAt:z.string(),withinPeriod:z.boolean()
})
const preview=z.object({
 status:z.enum(['NO_LINKED_EVENTS','CURRENCY_MISMATCH','OUTSIDE_STATEMENT_PERIOD',
  'SIGNED_TOTAL_DIFFERS','SIGNED_TOTAL_MATCHES_UNVERIFIED']),
 statementCurrency:z.string(),statementNet:z.string(),linkedSourceNet:z.string().nullable(),
 signedDifference:z.string().nullable(),linkedCount:z.number().int(),
 outsidePeriodCount:z.number().int(),unassignedSourceCount:z.number().int(),
 bankVerified:z.literal(false),linkedEvents:z.array(line)
})
const audit=z.object({
 action:z.enum(['CREATED','EVENT_LINKED','EVENT_UNLINKED']),
 eventId:z.string().uuid().nullable(),actorId:z.string().uuid(),occurredAt:z.string()
})
export type SettlementStatement=z.infer<typeof statement>
export type SettlementCandidate=z.infer<typeof event>
export type SettlementPreview=z.infer<typeof preview>
async function req<T>(url:string,schema:z.ZodType<T>|null,
  method='GET',data?:unknown):Promise<T>{
 const response=await fetch(url,{
  method,credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json',...(method==='GET'?{}:await csrfHeader()),
   ...(data===undefined?{}:{'Content-Type':'application/json'})},
  ...(data===undefined?{}:{body:JSON.stringify(data)})})
 if(!response.ok)throw new Error(response.status===404?'结算参考或来源交易不存在':
  response.status===409?'交易已被分配或结算参考重复；请刷新核对':
  response.status===403?'只有租户 Owner 可以核对结算':
  '结算核对请求 HTTP '+response.status)
 return schema?schema.parse(await response.json()):undefined as T
}
const root=(mp:string)=>origin+encodeURIComponent(mp)+'/settlements'
const item=(mp:string,id:string)=>root(mp)+'/'+encodeURIComponent(id)
export const listStatements=(mp:string)=>req(root(mp),z.array(statement))
export const createStatement=(mp:string,data:{
 reference:string;periodStart:string;periodEnd:string;reportedNet:string;currency:string
})=>req(root(mp),statement,'POST',data)
export const settlementPreview=(mp:string,id:string)=>req(item(mp,id)+'/preview',preview)
export const settlementCandidates=(mp:string,id:string)=>
 req(item(mp,id)+'/candidates',z.array(event))
export const settlementAudit=(mp:string,id:string)=>
 req(item(mp,id)+'/audit',z.array(audit))
export const assignSettlementEvent=(mp:string,id:string,eventId:string)=>
 req(item(mp,id)+'/events/'+encodeURIComponent(eventId),null,'POST')
export const unassignSettlementEvent=(mp:string,id:string,eventId:string)=>
 req(item(mp,id)+'/events/'+encodeURIComponent(eventId),null,'DELETE')
