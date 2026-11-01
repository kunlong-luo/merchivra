import {z} from 'zod'
import {listOperationRules,saveOperationRule,listOperationRuleAudit} from '../generated/operations-v67'
import {findingSchema} from './operations'

const code=findingSchema.shape.ruleCode
export const opsRuleSchema=z.object({
  ruleCode:code,
  enabled:z.boolean(),
  lowStockThreshold:z.number().int().min(0).max(10000).nullable(),
  version:z.number().int().nonnegative(),
  updatedAt:z.string().nullable()
})
export const opsRuleAuditSchema=z.object({
  ruleCode:code,actorId:z.string().uuid(),
  oldEnabled:z.boolean().nullable(),newEnabled:z.boolean(),
  oldThreshold:z.number().int().nullable(),newThreshold:z.number().int().nullable(),
  oldVersion:z.number().int().nullable(),newVersion:z.number().int().positive(),
  createdAt:z.string()
})
export type OpsRule=z.infer<typeof opsRuleSchema>
export async function fetchOperationRules(marketplaceId:string){
  return z.array(opsRuleSchema).length(6).parse((await listOperationRules(marketplaceId)).data)
}
export async function putOperationRule(marketplaceId:string,rule:OpsRule,
  enabled:boolean,threshold:number|null){
  if(rule.ruleCode==='LOW_STOCK'){
    if(threshold===null||!Number.isInteger(threshold)||threshold<0||threshold>10000)
      throw new Error('低库存阈值需要是 0 至 10000 的整数')
  }else if(threshold!==null)throw new Error('只有低库存规则可以设置阈值')
  return opsRuleSchema.parse((await saveOperationRule(marketplaceId,rule.ruleCode,{
    enabled,lowStockThreshold:threshold??undefined,expectedVersion:rule.version
  })).data)
}
export async function fetchOperationRuleAudit(marketplaceId:string){
  return z.array(opsRuleAuditSchema).max(100).parse(
    (await listOperationRuleAudit(marketplaceId)).data)
}
