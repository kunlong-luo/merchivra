import {afterEach,describe,expect,it,vi} from 'vitest'
import {priorityRuleSchema,getPriorityRules,savePriorityRule,getPriorityAudit}
 from './exception-priority-v82'
import {bulkResolveCases,bulkResolveReceiptSchema} from './fba-purchase-exceptions'
import {exceptionNoteTemplates,templateText} from './exception-note-templates-v82'

afterEach(()=>vi.unstubAllGlobals())
const mp='11111111-1111-4111-8111-111111111111'
const owner='22222222-2222-4222-8222-222222222222'
const line='33333333-3333-4333-8333-333333333333'
const key='44444444-4444-4444-8444-444444444444'
const codes=['SOURCE_CHANGED','PAST_DUE','INDEPENDENT_IDENTITY_REVIEW',
 'LOCAL_VARIANCE_REVIEW','DUE_SOON','HIGH_SEVERITY','NEEDS_OWNER',
 'SOURCE_WARNING','ROUTINE_FOLLOWUP'] as const
const floor=['P0','P0','P1','P1','P1','P1','P2','P2','P3']
const rules=codes.map((reasonCode,i)=>({
 reasonCode,minimumPriority:floor[i],priority:floor[i],version:0,customized:false
}))
describe('V82 advisory priority policy and confirmed manual batches',()=>{
 it('keeps reason codes, versions and bounded confirmation receipts typed',()=>{
  expect(priorityRuleSchema.parse(rules[0]).minimumPriority).toBe('P0')
  expect(priorityRuleSchema.safeParse({...rules[0],minimumPriority:'P9'}).success).toBe(false)
  expect(priorityRuleSchema.safeParse({...rules[0],version:-1}).success).toBe(false)
  expect(bulkResolveReceiptSchema.parse({requestKey:key,resolvedCount:1}).resolvedCount).toBe(1)
  expect(bulkResolveReceiptSchema.safeParse({requestKey:key,resolvedCount:0}).success).toBe(false)
 })
 it('uses session-only GET for current marketplace and config history',async()=>{
  const requests:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string)=>{
   requests.push(String(url))
   return new Response(JSON.stringify(String(url).endsWith('/audit')?[]:rules),
    {headers:{'Content-Type':'application/json'}})
  }))
  expect(await getPriorityRules(mp)).toHaveLength(9)
  expect(await getPriorityAudit(mp)).toEqual([])
  expect(requests[0]).toContain('/exception-insights/marketplaces/'+mp+'/priority-rules')
  expect(requests.every(x=>!x.includes('tenantId'))).toBe(true)
 })
 it('sends CSRF, version and selected evidence to existing RESOLVE ledger',async()=>{
  const requests:Array<{url:string;options?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   requests.push({url:String(url),options})
   if(String(url).endsWith('/auth/csrf'))
    return new Response(JSON.stringify({token:'v82-local-csrf-token-long',headerName:'X-CSRF-TOKEN'}),{headers:{'Content-Type':'application/json'}})
   if(String(url).endsWith('/bulk-resolve'))
    return new Response(JSON.stringify({requestKey:key,resolvedCount:1}),{headers:{'Content-Type':'application/json'}})
   return new Response(JSON.stringify({...rules[6],priority:'P1',version:1,customized:true}),{headers:{'Content-Type':'application/json'}})
  }))
  await savePriorityRule(mp,'NEEDS_OWNER',{
   priority:'P1',expectedVersion:0,note:'V82 local scheduling review priority update'})
  const changed=requests.at(-1)!
  expect(new Headers(changed.options?.headers).get('X-CSRF-TOKEN'))
   .toBe('v82-local-csrf-token-long')
  expect(JSON.parse(String(changed.options?.body)).expectedVersion).toBe(0)
  await bulkResolveCases(mp,key,[{
   lineId:line,expectedVersion:3,expectedFingerprint:'a'.repeat(64)
  }],'V82 local disposition after human evidence review',true)
  const resolve=requests.at(-1)!
  expect(resolve.url).toContain('/workbench/bulk-resolve')
  expect(JSON.parse(String(resolve.options?.body))).toMatchObject({
   requestKey:key,confirmed:true,lines:[{lineId:line,expectedVersion:3}]
  })
  expect(resolve.url).not.toContain('tenantId')
 })
 it('keeps templates editable, nonempty and free of automatic Amazon claims',()=>{
  expect(exceptionNoteTemplates).toHaveLength(5)
  expect(templateText('resolved')).toContain('人工')
  expect(exceptionNoteTemplates.every(x=>x.text.length>=10&&x.text.length<=300)).toBe(true)
  expect(templateText('unknown')).toBe('')
  expect(exceptionNoteTemplates.some(x=>x.text.includes('Amazon 已签收'))).toBe(false)
 })
})
