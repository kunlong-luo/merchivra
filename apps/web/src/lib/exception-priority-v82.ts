import {z} from 'zod'
import {merchivraFetch} from './merchivra-mutator'

const uuid=z.string().uuid()
const priority=z.enum(['P0','P1','P2','P3'])
const reason=z.enum([
 'SOURCE_CHANGED','PAST_DUE','INDEPENDENT_IDENTITY_REVIEW',
 'LOCAL_VARIANCE_REVIEW','DUE_SOON','HIGH_SEVERITY',
 'NEEDS_OWNER','SOURCE_WARNING','ROUTINE_FOLLOWUP'
])
export const priorityRuleSchema=z.object({
 reasonCode:reason,minimumPriority:priority,priority,
 version:z.number().int().nonnegative(),customized:z.boolean()
})
export const priorityAuditSchema=z.object({
 reasonCode:reason,oldPriority:priority.nullable(),newPriority:priority,
 oldVersion:z.number().int().nonnegative(),newVersion:z.number().int().positive(),
 actorId:uuid,note:z.string(),occurredAt:z.string().datetime({offset:true})
})
const root=(marketplace:string)=>'/api/v1/exception-insights/marketplaces/'+
 encodeURIComponent(marketplace)+'/priority-rules'
export const getPriorityRules=async(marketplace:string)=>{
 const response=await merchivraFetch<{data:unknown}>(root(marketplace),{method:'GET'})
 return z.array(priorityRuleSchema).length(9).parse(response.data)
}
export const getPriorityAudit=async(marketplace:string)=>{
 const response=await merchivraFetch<{data:unknown}>(
  root(marketplace)+'/audit',{method:'GET'})
 return z.array(priorityAuditSchema).max(100).parse(response.data)
}
export const savePriorityRule=async(marketplace:string,reasonCode:string,
 body:{priority:string;expectedVersion:number;note:string})=>{
 const response=await merchivraFetch<{data:unknown}>(
  root(marketplace)+'/'+encodeURIComponent(reasonCode),{
   method:'POST',headers:{'Content-Type':'application/json'},
   body:JSON.stringify(body)})
 return priorityRuleSchema.parse(response.data)
}
export const priorityReasons:Record<z.infer<typeof reason>,string>={
 SOURCE_CHANGED:'来源证据变更',PAST_DUE:'人工处理超期',
 INDEPENDENT_IDENTITY_REVIEW:'第二 Owner 身份审核',
 LOCAL_VARIANCE_REVIEW:'本地入仓差异复核',
 DUE_SOON:'人工处理临期',HIGH_SEVERITY:'高严重度证据',
 NEEDS_OWNER:'尚未分派',SOURCE_WARNING:'本地来源风险提示',
 ROUTINE_FOLLOWUP:'常规人工跟进'
}
