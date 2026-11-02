import {afterEach,describe,expect,it,vi} from 'vitest'
import {exceptionInsightsSchema,getExceptionInsights} from './exception-insights-v80'

afterEach(()=>vi.unstubAllGlobals())
const mp='11111111-1111-4111-8111-111111111111'
const owner='22222222-2222-4222-8222-222222222222'
const line='33333333-3333-4333-8333-333333333333'
const snapshot={
 marketplaceId:mp,amazonVerified:false,fbaStockCredited:false,
 sourceTruncated:false,shipmentScope:2,evidenceLines:4,
 actionableCount:2,recommendationsTruncated:false,
 cases:{total:2,open:1,acknowledged:0,resolved:1,overdue:1,
  meanResolutionHours:4.2},
 workload:{totalCases:2,unassigned:1,inactiveAssignee:0,
  owners:[{userId:owner,displayName:'Local Owner',open:0,
   acknowledged:0,overdue:0}]},
 operations:[{ruleCode:'FBA_PO_EXCEPTION_OVERDUE',open:1,
  acknowledged:0,done:0,cleared:1}],
 recommendations:[{lineId:line,shipmentId:owner,shipmentReference:'LOCAL-TEST',
  sellerSku:'SKU-1',findingCode:'UNLINKED_DRAFT',severity:'INFO',
  state:'OPEN',reminder:'OVERDUE',assigneeId:null,
  recommendedOwnerId:owner,dueAt:'2026-11-02T00:00:00Z',
  priority:'P0',reasonCode:'PAST_DUE',
  nextStep:'联系负责人并手动修改期限'}]
}
describe('V80 evidence-based cross-module insights',()=>{
 it('enforces no Amazon verification or inventory credit and bounded recommendations',()=>{
  expect(exceptionInsightsSchema.parse(snapshot).recommendations).toHaveLength(1)
  expect(exceptionInsightsSchema.safeParse({...snapshot,amazonVerified:true}).success).toBe(false)
  expect(exceptionInsightsSchema.safeParse({...snapshot,fbaStockCredited:true}).success).toBe(false)
  expect(exceptionInsightsSchema.safeParse({...snapshot,
   recommendations:[{...snapshot.recommendations[0],priority:'P9'}]}).success).toBe(false)
  expect(exceptionInsightsSchema.safeParse({...snapshot,
   recommendations:Array(31).fill(snapshot.recommendations[0])}).success).toBe(false)
  expect(exceptionInsightsSchema.safeParse({...snapshot,
   cases:{...snapshot.cases,meanResolutionHours:null}}).success).toBe(true)
 })
 it('uses a read-only scoped GET without browser-provided tenant or CSRF',async()=>{
  const requests:Array<{url:string,options?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   requests.push({url:String(url),options})
   return new Response(JSON.stringify(snapshot),{
    headers:{'Content-Type':'application/json'}})
  }))
  const result=await getExceptionInsights(mp)
  expect(result.recommendations[0].reasonCode).toBe('PAST_DUE')
  expect(requests).toHaveLength(1)
  expect(requests[0].url).toBe('/api/v1/exception-insights/marketplaces/'+mp)
  expect(requests[0].url).not.toContain('tenantId')
  expect(requests[0].options).toMatchObject({method:'GET',
   credentials:'same-origin',cache:'no-store'})
 })
})
